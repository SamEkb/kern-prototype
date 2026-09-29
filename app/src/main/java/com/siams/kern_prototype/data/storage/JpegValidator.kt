package com.siams.kern_prototype.data.storage

/**
 * R02: проверка целостности baseline-JPEG по энтропийным данным — без преобразования в пиксели,
 * но с прохождением ВСЕХ блоков изображения (детерминированно, одинаково на телефоне и в тестах).
 *
 * Критерии отклонения:
 *  - нарушена цепочка сегментов (SOI, длины сегментов, маркеры вне скана);
 *  - неизвестный код Хаффмана, коэффициентов в блоке больше 64, неверная категория DC;
 *  - данные скана обрываются маркером (в т.ч. преждевременным EOI) раньше последнего блока;
 *  - число блоков не совпадает с размерами кадра (после последнего блока — не EOI);
 *  - маркеры рестарта (DRI) отсутствуют или идут не по порядку.
 *
 * Поддерживаются последовательные JPEG с Хаффманом (SOF0/SOF1) и одним сканом — так пишут
 * `Bitmap.compress` и камеры. Для остальных (прогрессивный, арифметический, несколько сканов)
 * результат [Result.Unsupported] — проверять другим способом.
 *
 * Ограничение: порча, после которой данные остаются формально корректными (например, подмена
 * значений коэффициентов без нарушения кодов), так не обнаруживается — её ловит только сверка
 * контрольной суммы, записанной при съёмке.
 */
object JpegValidator {

    sealed interface Result {
        data object Ok : Result
        data class Corrupt(val reason: String) : Result
        data object Unsupported : Result
    }

    private class Corrupt(message: String) : Exception(message)
    private class Unsupported : Exception()

    private class Huffman(counts: IntArray, private val symbols: IntArray) {
        private val maxCode = IntArray(18) { -1 }
        private val valPtr = IntArray(17)
        private val minCode = IntArray(17)

        init {
            var code = 0
            var k = 0
            for (len in 1..16) {
                valPtr[len] = k
                minCode[len] = code
                code += counts[len - 1]
                k += counts[len - 1]
                maxCode[len] = if (counts[len - 1] > 0) code - 1 else -1
                code = code shl 1
            }
            maxCode[17] = Int.MAX_VALUE
        }

        fun decode(r: BitReader): Int {
            var code = 0
            for (len in 1..16) {
                code = (code shl 1) or r.bit()
                if (maxCode[len] >= 0 && code <= maxCode[len]) return symbols[valPtr[len] + code - minCode[len]]
            }
            throw Corrupt("неизвестный код Хаффмана")
        }
    }

    /** Чтение битов энтропийных данных: FF00 → FF; любой другой маркер останавливает чтение. */
    private class BitReader(private val d: ByteArray, var pos: Int) {
        private var acc = 0
        private var bits = 0
        /** Маркер, на котором остановилось чтение (позиция байта FF), или -1. */
        var markerAt = -1
            private set

        fun bit(): Int {
            if (bits == 0) fill()
            bits--
            return (acc shr bits) and 1
        }

        fun receive(n: Int): Int {
            var v = 0
            repeat(n) { v = (v shl 1) or bit() }
            return v
        }

        private fun fill() {
            if (markerAt >= 0 || pos >= d.size) throw Corrupt("данные изображения обрываются")
            val b = d[pos].toInt() and 0xFF
            if (b == 0xFF) {
                if (pos + 1 >= d.size) throw Corrupt("данные изображения обрываются")
                val next = d[pos + 1].toInt() and 0xFF
                if (next != 0x00) {
                    markerAt = pos
                    throw Corrupt("данные изображения обрываются маркером 0xFF%02X".format(next))
                }
                pos += 2
            } else {
                pos++
            }
            acc = b
            bits = 8
        }

        /** Выравнивание на байт перед маркером; возвращает позицию следующего маркера (FF). */
        fun alignToMarker(): Int {
            bits = 0
            if (markerAt >= 0) return markerAt
            // Байты-заполнители FF перед маркером допустимы.
            if (pos + 1 < d.size && d[pos] == 0xFF.toByte() && d[pos + 1] != 0x00.toByte()) return pos
            throw Corrupt("после последнего блока остались лишние данные")
        }

        fun resetAfterMarker(markerPos: Int) {
            pos = markerPos + 2
            markerAt = -1
            bits = 0
        }
    }

    private class Component(val id: Int, val h: Int, val v: Int) {
        var dc: Huffman? = null
        var ac: Huffman? = null
    }

    fun check(d: ByteArray): Result = try {
        validate(d)
        Result.Ok
    } catch (e: Corrupt) {
        Result.Corrupt(e.message ?: "повреждён")
    } catch (_: Unsupported) {
        Result.Unsupported
    } catch (_: IndexOutOfBoundsException) {
        Result.Corrupt("структура файла нарушена")
    }

    private fun u16(d: ByteArray, p: Int) = ((d[p].toInt() and 0xFF) shl 8) or (d[p + 1].toInt() and 0xFF)

    private fun validate(d: ByteArray) {
        if (d.size < 4 || d[0] != 0xFF.toByte() || d[1] != 0xD8.toByte()) throw Corrupt("нет начала JPEG")
        val dcTables = arrayOfNulls<Huffman>(4)
        val acTables = arrayOfNulls<Huffman>(4)
        var comps: List<Component>? = null
        var width = 0
        var height = 0
        var restartInterval = 0
        var p = 2
        while (true) {
            if (p >= d.size || d[p] != 0xFF.toByte()) throw Corrupt("нарушена цепочка сегментов")
            while (p < d.size && d[p] == 0xFF.toByte()) p++ // заполнители
            val m = d[p].toInt() and 0xFF
            p++
            when (m) {
                0xD8, 0x01, in 0xD0..0xD7 -> throw Corrupt("неожиданный маркер 0xFF%02X".format(m))
                0xD9 -> throw Corrupt("конец изображения до данных")
            }
            val len = u16(d, p)
            if (len < 2 || p + len > d.size) throw Corrupt("неверная длина сегмента")
            val s = p + 2
            when (m) {
                0xC0, 0xC1 -> {
                    if (d[s].toInt() and 0xFF != 8) throw Unsupported()
                    height = u16(d, s + 1)
                    width = u16(d, s + 3)
                    val n = d[s + 5].toInt() and 0xFF
                    if (width == 0 || height == 0 || n !in 1..4) throw Corrupt("неверный заголовок кадра")
                    comps = (0 until n).map { i ->
                        val o = s + 6 + i * 3
                        val hv = d[o + 1].toInt() and 0xFF
                        Component(d[o].toInt() and 0xFF, hv shr 4, hv and 15)
                    }
                    if (comps.any { it.h !in 1..4 || it.v !in 1..4 }) throw Corrupt("неверная дискретизация")
                }
                in 0xC2..0xCF -> if (m != 0xC4) throw Unsupported() // прогрессивный/арифметический и пр.
                0xDD -> restartInterval = u16(d, s)
            }
            if (m == 0xC4) {
                var q = s
                while (q < p + len) {
                    val tc = (d[q].toInt() and 0xFF) shr 4
                    val th = d[q].toInt() and 0x0F
                    if (tc > 1 || th > 3) throw Corrupt("неверная таблица Хаффмана")
                    val counts = IntArray(16) { d[q + 1 + it].toInt() and 0xFF }
                    val total = counts.sum()
                    val symbols = IntArray(total) { d[q + 17 + it].toInt() and 0xFF }
                    (if (tc == 0) dcTables else acTables)[th] = Huffman(counts, symbols)
                    q += 17 + total
                }
                if (q != p + len) throw Corrupt("неверная таблица Хаффмана")
            }
            if (m == 0xDA) {
                val frame = comps ?: throw Corrupt("скан без заголовка кадра")
                val ns = d[s].toInt() and 0xFF
                if (ns != frame.size) throw Unsupported() // несколько сканов
                for (i in 0 until ns) {
                    val id = d[s + 1 + i * 2].toInt() and 0xFF
                    val t = d[s + 2 + i * 2].toInt() and 0xFF
                    val c = frame.firstOrNull { it.id == id } ?: throw Corrupt("неизвестный компонент скана")
                    c.dc = dcTables[t shr 4] ?: throw Corrupt("нет таблицы DC")
                    c.ac = acTables[t and 15] ?: throw Corrupt("нет таблицы AC")
                }
                val ss = d[s + 1 + ns * 2].toInt() and 0xFF
                val se = d[s + 2 + ns * 2].toInt() and 0xFF
                if (ss != 0 || se != 63) throw Unsupported()
                decodeScan(d, p + len, frame, width, height, restartInterval)
                return
            }
            p += len
        }
    }

    private fun decodeScan(d: ByteArray, start: Int, comps: List<Component>, w: Int, h: Int, restart: Int) {
        val hMax = comps.maxOf { it.h }
        val vMax = comps.maxOf { it.v }
        val single = comps.size == 1
        val mcus: Long
        val blocksPerMcu: List<Pair<Component, Int>>
        if (single) {
            val c = comps[0]
            val bw = (w * c.h + 8 * hMax - 1) / (8 * hMax)
            val bh = (h * c.v + 8 * vMax - 1) / (8 * vMax)
            mcus = bw.toLong() * bh
            blocksPerMcu = listOf(c to 1)
        } else {
            mcus = ((w + 8 * hMax - 1) / (8 * hMax)).toLong() * ((h + 8 * vMax - 1) / (8 * vMax))
            blocksPerMcu = comps.map { it to it.h * it.v }
        }
        val r = BitReader(d, start)
        var expectedRst = 0
        for (mcu in 0 until mcus) {
            if (restart > 0 && mcu > 0 && mcu % restart == 0L) {
                val mp = r.alignToMarker()
                val marker = d[mp + 1].toInt() and 0xFF
                if (marker != 0xD0 + expectedRst) throw Corrupt("нарушен порядок маркеров рестарта")
                expectedRst = (expectedRst + 1) and 7
                r.resetAfterMarker(mp)
            }
            for ((c, n) in blocksPerMcu) repeat(n) { decodeBlock(r, c) }
        }
        val mp = r.alignToMarker()
        if ((d[mp + 1].toInt() and 0xFF) != 0xD9) throw Unsupported() // за сканом — ещё сегменты
    }

    private fun decodeBlock(r: BitReader, c: Component) {
        val t = c.dc!!.decode(r)
        if (t > 11) throw Corrupt("неверная категория DC")
        r.receive(t)
        var k = 1
        val ac = c.ac!!
        while (k < 64) {
            val rs = ac.decode(r)
            val run = rs shr 4
            val size = rs and 15
            if (size == 0) {
                if (run == 15) k += 16 else break
            } else {
                k += run
                if (k > 63) throw Corrupt("коэффициентов в блоке больше 64")
                r.receive(size)
                k++
            }
        }
        if (k > 64) throw Corrupt("коэффициентов в блоке больше 64")
    }
}
