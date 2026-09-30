package com.siams.kern_prototype.camera

import kotlinx.serialization.Serializable

/**
 * R14: фактическое состояние параметров камеры по данным SDK (readback) — в отличие от
 * запрошенного профиля. null в поле — SDK не сообщил значение (вызов вернул ошибку).
 * Для Camera2 и подменных камер может отсутствовать.
 */
@Serializable
data class CameraReadback(
    val exposureUs: Int? = null,
    val realExposureUs: Int? = null,
    val gainPercent: Int? = null,
    val gamma: Int? = null,
    /** Знаковые усиления WB R/G/B (-127..127), если SDK работает в режиме RGB Gain. */
    val wbGain: List<Int>? = null,
    val autoExposureMode: Int? = null,
    val exposureRangeUs: IntRange3? = null,
    val gainRangePercent: IntRange3? = null,
    val builtinWbGainOption: Int? = null,
    val ispOption: Int? = null,
) {
    @Serializable
    data class IntRange3(val min: Int, val max: Int, val default: Int)

    companion object {
        private fun ok(hr: Int) = hr >= 0 // HRESULT: неотрицательный — успех

        /** Разбор массива из `CamLib.readState()` (раскладка — jnicam.cpp). */
        fun parse(v: IntArray): CameraReadback {
            fun at(i: Int) = v.getOrElse(i) { -1 }
            fun value(hrIdx: Int) = if (ok(at(hrIdx))) at(hrIdx + 1) else null
            return CameraReadback(
                exposureUs = value(0),
                realExposureUs = value(2),
                gainPercent = value(4),
                gamma = value(6),
                wbGain = if (ok(at(8))) listOf(at(9), at(10), at(11)) else null,
                autoExposureMode = value(12),
                exposureRangeUs = if (ok(at(14))) IntRange3(at(15), at(16), at(17)) else null,
                gainRangePercent = if (ok(at(18))) IntRange3(at(19), at(20), at(21)) else null,
                builtinWbGainOption = value(22),
                ispOption = value(24),
            )
        }
    }
}
