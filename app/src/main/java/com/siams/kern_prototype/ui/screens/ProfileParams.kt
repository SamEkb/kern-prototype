package com.siams.kern_prototype.ui.screens

import com.siams.kern_prototype.camera.CameraCapabilities
import com.siams.kern_prototype.camera.CameraLimits
import com.siams.kern_prototype.domain.model.CameraProfile
import kotlin.math.exp
import kotlin.math.ln

/**
 * Один редактируемый параметр профиля: границы (в единицах отображения), чтение/запись, формат.
 *
 *  - [log] — логарифмическая шкала ползунка (широкие диапазоны выдержки/усиления);
 *  - [fixed] — диапазон из одного значения (min == max): ползунок отключается, преобразования
 *    возвращают это значение (иначе в профиль попадало бы положение ползунка 0/1);
 *  - [clampedLikeController] — отправляемое в SDK значение считается так же, как в контроллере
 *    (ограничение границами камеры). Только для таких параметров показывается подсказка
 *    «будет отправлено в SDK» — для остальных (WB, фокус) расчёт другой или его нет.
 */
internal data class ParamSpec(
    val label: String,
    val min: Float,
    val max: Float,
    val get: (CameraProfile) -> Float,
    val set: (CameraProfile, Float) -> CameraProfile,
    val format: (Float) -> String,
    val log: Boolean = false,
    val clampedLikeController: Boolean = false,
    val note: String? = null,
) {
    val fixed: Boolean get() = max <= min

    /** Диапазон ползунка: 0..1 для логарифмической шкалы и для фиксированного значения. */
    val sliderRange: ClosedFloatingPointRange<Float> get() = if (log || fixed) 0f..1f else min..max

    fun toSlider(v: Float): Float = when {
        fixed -> 0f
        log -> (ln(v.coerceIn(min, max) / min) / ln(max / min))
        else -> v.coerceIn(min, max)
    }

    fun fromSlider(t: Float): Float = when {
        fixed -> min
        log -> min * exp(t.coerceIn(0f, 1f) * ln(max / min))
        else -> t.coerceIn(min, max)
    }

    /** Подсказка, если сохранённое значение вне границ камеры (только для [clampedLikeController]). */
    fun sentToSdkHint(current: Float): String? {
        if (!clampedLikeController || (current >= min && current <= max)) return null
        return "Вне границ камеры (${format(min)}–${format(max)}): будет отправлено в SDK " +
            format(current.coerceIn(min, max))
    }
}

/**
 * R14: выдержка, усиление и гамма — в границах ТЕКУЩЕЙ камеры (тех же, что при применении профиля);
 * источник границ (SDK или запасной) показывается в подсказке.
 */
/** Выдержка в единицах Touptek: до секунды — в мс, от секунды — в секундах (редактор и HUD). */
internal fun formatExposureMs(ms: Float, locale: java.util.Locale = java.util.Locale.getDefault()): String =
    if (ms >= 1000f) String.format(locale, "%.2f с", ms / 1000f) else String.format(locale, "%.2f мс", ms)

internal fun profileParams(
    limits: CameraLimits,
    capabilities: CameraCapabilities = CameraCapabilities(),
): List<ParamSpec> {
    fun source(r: CameraLimits.Range) = if (r.fromSdk) null else "границы: запасные (${r.note})"
    val e = limits.exposureUs
    val g = limits.gainPercent
    val gm = limits.gamma
    return listOf(
        ParamSpec(
            "Выдержка", e.min / 1000f, e.max / 1000f,
            get = { it.exposureTimeNs / 1_000_000f },
            set = { p, v -> p.copy(exposureTimeNs = (v * 1_000_000).toLong()) },
            format = { formatExposureMs(it) },
            log = true, clampedLikeController = true, note = source(e),
        ),
        ParamSpec(
            "Усиление", g.min.toFloat(), g.max.toFloat(),   // §3.2 Gain (аналог ISO), %; хранится в поле iso
            get = { it.iso.toFloat() },
            set = { p, v -> p.copy(iso = v.toInt()) },
            format = { "%d%%".format(it.toInt()) },
            log = true, clampedLikeController = true, note = source(g),
        ),
        ParamSpec(
            "Гамма", gm.min.toFloat(), gm.max.toFloat(),   // §3.3 Gamma, проценты (100 = 1.0); критична для УФ
            get = { it.gamma.toFloat() },
            set = { p, v -> p.copy(gamma = v.toInt()) },
            format = { "%d%%".format(it.toInt()) },
            clampedLikeController = true,
        ),
        ParamSpec(
            "WB R", 1f, 4f,
            get = { it.wbRedGain },
            set = { p, v -> p.copy(wbRedGain = v) },
            format = { "%.2f".format(it) },
        ),
        ParamSpec(
            "WB G", 1f, 4f,
            get = { it.wbGreenEvenGain },
            set = { p, v -> p.copy(wbGreenEvenGain = v, wbGreenOddGain = v) },
            format = { "%.2f".format(it) },
        ),
        ParamSpec(
            "WB B", 1f, 4f,
            get = { it.wbBlueGain },
            set = { p, v -> p.copy(wbBlueGain = v) },
            format = { "%.2f".format(it) },
        ),
    ) + listOfNotNull(
        // FIXES §3: фокус — только если камера его применяет (у Touptek — нет).
        ParamSpec(
            "Фокус", 0f, 10f,
            get = { it.focusDistance },
            set = { p, v -> p.copy(focusDistance = v) },
            format = { if (it < 0.05f) "∞" else "%.1f дптр".format(it) },
        ).takeIf { capabilities.supportsFocus },
    )
}
