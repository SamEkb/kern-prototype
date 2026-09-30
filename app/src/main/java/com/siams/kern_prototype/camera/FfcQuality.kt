package com.siams.kern_prototype.camera

import android.graphics.Bitmap
import com.siams.kern_prototype.domain.model.LampType
import kotlinx.serialization.Serializable

/**
 * R22: проверка пригодности эталона FFC ДО его принятия.
 *
 *  - Насыщение — доля пикселей полного кадра, где хотя бы один канал достиг [Thresholds.saturationLevel]:
 *    пересвеченный эталон теряет информацию о неравномерности.
 *  - Полезный сигнал — медиана каждого канала полного кадра не ниже [Thresholds.minMedian]:
 *    слишком тёмный эталон усиливает шум.
 *  - Усиление коррекции — по готовому (уменьшенному, размытому) шаблону, тем же правилом, что
 *    применяет коррекция снимка: `среднее канала / минимум канала`. Большое усиление означает
 *    тёмные области (виньетирование, тень, край фона), где коррекция поднимет шум.
 *
 * Пороги — ПРЕДВАРИТЕЛЬНЫЕ ([Thresholds.provisional]): их нужно определить на мишени для каждого
 * освещения. Статистика по каналам сохраняется в отчёте целиком, чтобы подобрать их по данным.
 */
object FfcQuality {

    @Serializable
    data class Thresholds(
        val saturationLevel: Int,
        val maxSaturatedFraction: Double,
        val minMedian: Int,
        val maxCorrectionGain: Double,
        val provisional: Boolean = true,
    ) {
        companion object {
            /** Предварительные пороги по освещению (определить на мишени). */
            fun forLamp(lamp: LampType): Thresholds = when (lamp) {
                LampType.DAYLIGHT -> Thresholds(saturationLevel = 255, maxSaturatedFraction = 0.001, minMedian = 64, maxCorrectionGain = 4.0)
                // УФ: свечение фона слабее и неравномерно по каналам — нижний порог сигнала ниже.
                LampType.UF -> Thresholds(saturationLevel = 255, maxSaturatedFraction = 0.001, minMedian = 16, maxCorrectionGain = 4.0)
            }
        }
    }

    /** Статистика канала полного кадра (значения 0..255). */
    @Serializable
    data class Channel(val mean: Double, val p01: Int, val median: Int, val p99: Int, val saturatedFraction: Double)

    @Serializable
    data class Report(
        val lamp: LampType,
        val width: Int,
        val height: Int,
        /** R, G, B. */
        val channels: List<Channel>,
        /** Доля пикселей, где насыщен хотя бы один канал. */
        val anySaturatedFraction: Double,
        /** Усиление коррекции по шаблону для R, G, B (нулевой канал шаблона считается как 1 — как в коррекции). */
        val correctionGain: List<Double>,
        val thresholds: Thresholds,
        /** Причины отказа; пусто — эталон пригоден. */
        val problems: List<String>,
    ) {
        val accepted: Boolean get() = problems.isEmpty()

        fun summary(): String {
            val names = listOf("R", "G", "B")
            val med = channels.indices.joinToString(" ") { "${names[it]}=${channels[it].median}" }
            val gain = correctionGain.indices.joinToString(" ") { "${names[it]}=${fmt(correctionGain[it])}" }
            return "медиана $med; насыщено ${pct(anySaturatedFraction)}; усиление коррекции $gain"
        }
    }

    /** Эталон отклонён: файл не создан, прежний эталон профиля не изменён. */
    class RejectedException(val report: Report) : IllegalStateException(
        "Эталон FFC отклонён: ${report.problems.joinToString("; ")}. Прежний эталон сохранён." +
            if (report.thresholds.provisional) " (пороги предварительные)" else "",
    )

    /** Гистограммы полного кадра по строкам (без копии кадра целиком). */
    internal class Histograms(val width: Int, val height: Int, val saturationLevel: Int) {
        val hist = Array(3) { LongArray(256) }
        var anySaturated = 0L

        fun addRow(row: IntArray, n: Int) {
            val hr = hist[0]; val hg = hist[1]; val hb = hist[2]
            for (i in 0 until n) {
                val p = row[i]
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF
                hr[r]++; hg[g]++; hb[b]++
                if (r >= saturationLevel || g >= saturationLevel || b >= saturationLevel) anySaturated++
            }
        }
    }

    internal fun histograms(still: Bitmap, saturationLevel: Int): Histograms {
        val w = still.width
        val h = still.height
        val hs = Histograms(w, h, saturationLevel)
        val row = IntArray(w)
        for (y in 0 until h) {
            still.getPixels(row, 0, w, 0, y, w, 1)
            hs.addRow(row, w)
        }
        return hs
    }

    /**
     * Статистика шаблона по каналам R, G, B. [translucent] — число пикселей с alpha < 255:
     * [Bitmap.getPixels] отдаёт RGB без умножения на альфу, а нативная коррекция читает память
     * Bitmap (RGB умножены на альфу) — для таких пикселей проверка видела бы не те значения.
     */
    internal class TemplateStats(val mean: List<Double>, val max: List<Int>, val gain: List<Double>, val translucent: Long)

    internal fun templateStats(template: Bitmap): TemplateStats {
        val w = template.width
        val h = template.height
        val px = IntArray(w * h)
        template.getPixels(px, 0, w, 0, 0, w, h)
        val sum = DoubleArray(3)
        val min = IntArray(3) { 255 }
        val max = IntArray(3)
        var translucent = 0L
        for (p in px) {
            if ((p ushr 24) != 0xFF) translucent++
            val c = intArrayOf((p ushr 16) and 0xFF, (p ushr 8) and 0xFF, p and 0xFF)
            for (k in 0..2) { sum[k] += c[k]; if (c[k] < min[k]) min[k] = c[k]; if (c[k] > max[k]) max[k] = c[k] }
        }
        val mean = (0..2).map { sum[it] / px.size }
        // Коррекция делит на max(t, 1) (jnicam.cpp) — нулевой минимум даёт усиление «среднее / 1».
        return TemplateStats(mean, max.toList(), (0..2).map { k -> mean[k] / maxOf(min[k], 1) }, translucent)
    }

    /** Усиление коррекции по шаблону — как у коррекции снимка: среднее / max(минимум, 1). */
    internal fun correctionGain(template: Bitmap): List<Double> = templateStats(template).gain

    fun evaluate(lamp: LampType, still: Bitmap, template: Bitmap, thresholds: Thresholds = Thresholds.forLamp(lamp)): Report {
        val hs = histograms(still, thresholds.saturationLevel)
        val total = hs.width.toLong() * hs.height
        val channels = hs.hist.map { channel(it, total, thresholds.saturationLevel) }
        val ts = templateStats(template)
        val gains = ts.gain
        val anySat = if (total == 0L) 0.0 else hs.anySaturated.toDouble() / total
        return Report(
            lamp = lamp,
            width = hs.width,
            height = hs.height,
            channels = channels,
            anySaturatedFraction = anySat,
            correctionGain = gains,
            thresholds = thresholds,
            problems = problems(channels, anySat, thresholds) + templateProblems(ts, thresholds),
        )
    }

    /**
     * R22: проверка готового шаблона без исходного кадра (импорт профиля). Уменьшенный размытый
     * шаблон не позволяет восстановить пересвет и сигнал исходного кадра — они НЕ проверяются;
     * проверяются прозрачность пикселей, нулевые каналы и усиление коррекции.
     */
    @Serializable
    data class TemplateReport(
        val lamp: LampType,
        val width: Int,
        val height: Int,
        val channelMean: List<Double>,
        val correctionGain: List<Double>,
        val thresholds: Thresholds,
        val problems: List<String>,
        val note: String = "пересвет и сигнал исходного кадра по шаблону не проверяются",
    ) {
        val accepted: Boolean get() = problems.isEmpty()
    }

    fun evaluateTemplate(lamp: LampType, template: Bitmap, thresholds: Thresholds = Thresholds.forLamp(lamp)): TemplateReport {
        val ts = templateStats(template)
        return TemplateReport(
            lamp = lamp,
            width = template.width,
            height = template.height,
            channelMean = ts.mean,
            correctionGain = ts.gain,
            thresholds = thresholds,
            problems = templateProblems(ts, thresholds),
        )
    }

    /**
     * Проверки шаблона: полупрозрачные пиксели (значения для коррекции и для проверки расходятся),
     * полностью нулевой канал (коррекция умножит канал снимка на 0 — усиление
     * «среднее / max(0, 1)» = 0 проверку усиления проходит) и усиление коррекции.
     */
    internal fun templateProblems(ts: TemplateStats, t: Thresholds): List<String> {
        val names = listOf("R", "G", "B")
        val out = mutableListOf<String>()
        if (ts.translucent > 0) {
            // Проверяются пиксели, а не наличие альфа-канала в формате.
            out += "шаблон содержит полупрозрачные пиксели (${ts.translucent}) — коррекция увидит их " +
                "затемнёнными; нужен непрозрачный эталон"
        }
        val zero = ts.max.indices.filter { ts.max[it] == 0 }
        if (zero.isNotEmpty()) {
            out += "нулевой канал шаблона " + zero.joinToString(", ") { names[it] } +
                " — коррекция обнулит его в снимках"
        }
        val strong = ts.gain.indices.filter { it !in zero && !(ts.gain[it] <= t.maxCorrectionGain) }
        if (strong.isNotEmpty()) {
            out += "неравномерность: усиление коррекции " + strong.joinToString(", ") { "${names[it]}=${fmt(ts.gain[it])}" } +
                " (допустимо ≤ ${fmt(t.maxCorrectionGain)}) — фон должен заполнять кадр и быть освещён равномерно"
        }
        return out
    }

    /** Проверки исходного кадра (пересвет, сигнал); шаблон — [templateProblems]. */
    internal fun problems(channels: List<Channel>, anySat: Double, t: Thresholds): List<String> {
        val names = listOf("R", "G", "B")
        val out = mutableListOf<String>()
        if (anySat > t.maxSaturatedFraction) {
            out += "пересвет ${pct(anySat)} пикселей (допустимо ≤ ${pct(t.maxSaturatedFraction)}) — уменьшите выдержку/усиление"
        }
        val dark = channels.indices.filter { channels[it].median < t.minMedian }
        if (dark.isNotEmpty()) {
            out += "слабый сигнал: медиана " + dark.joinToString(", ") { "${names[it]}=${channels[it].median}" } +
                " (нужно ≥ ${t.minMedian}) — увеличьте выдержку"
        }
        return out
    }

    private fun channel(hist: LongArray, total: Long, saturationLevel: Int): Channel {
        if (total == 0L) return Channel(0.0, 0, 0, 0, 0.0)
        var sum = 0.0
        for (v in 0..255) sum += v.toDouble() * hist[v]
        var sat = 0L
        for (v in saturationLevel.coerceIn(0, 255)..255) sat += hist[v]
        return Channel(
            mean = sum / total,
            p01 = percentile(hist, total, 0.01),
            median = percentile(hist, total, 0.5),
            p99 = percentile(hist, total, 0.99),
            saturatedFraction = sat.toDouble() / total,
        )
    }

    /** Наименьшее значение, до которого (включительно) набирается доля [q] пикселей. */
    internal fun percentile(hist: LongArray, total: Long, q: Double): Int {
        val need = kotlin.math.ceil(q * total).toLong().coerceAtLeast(1)
        var acc = 0L
        for (v in 0..255) {
            acc += hist[v]
            if (acc >= need) return v
        }
        return 255
    }

    private fun pct(f: Double) = "%.2f%%".format(f * 100)
    private fun fmt(v: Double) = if (v.isInfinite()) "∞" else "%.2f".format(v)
}
