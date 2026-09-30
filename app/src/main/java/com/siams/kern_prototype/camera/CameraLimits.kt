package com.siams.kern_prototype.camera

import kotlinx.serialization.Serializable

/**
 * R14: границы параметров текущей камеры — ОДНИ И ТЕ ЖЕ для применения профиля и для ползунков
 * редактора. Выдержка и усиление — из SDK (`get_ExpTimeRange`, `get_ExpoAGainRange`); если SDK
 * диапазон не сообщил или он некорректен — явный согласованный запасной диапазон этого параметра
 * (прежние границы применения). Гамма: у SDK нет функции диапазона — константы `toupcam.h`.
 * Сохранённые профили по этим границам не переписываются — ограничивается только применяемое значение.
 */
@Serializable
data class CameraLimits(
    val exposureUs: Range,
    val gainPercent: Range,
    val gamma: Range,
) {
    /** [fromSdk] = false — запасной диапазон; [note] — почему. */
    @Serializable
    data class Range(val min: Int, val max: Int, val fromSdk: Boolean, val note: String? = null) {
        fun clamp(v: Long): Int = v.coerceIn(min.toLong(), max.toLong()).toInt()
        fun clamp(v: Int): Int = v.coerceIn(min, max)
        fun contains(v: Long): Boolean = v in min..max
    }

    companion object {
        // Согласованный запасной диапазон = прежние границы применения профиля (ToupcamProfileApplier).
        const val FALLBACK_EXPO_MIN_US = 100          // ~0.1 мс
        const val FALLBACK_EXPO_MAX_US = 15_000_000   // 15 с
        const val FALLBACK_GAIN_MIN_PERCENT = 100     // 1.0x (TOUPCAM_EXPOGAIN_MIN)
        const val FALLBACK_GAIN_MAX_PERCENT = 5100
        const val GAMMA_MIN = 20                      // TOUPCAM_GAMMA_MIN
        const val GAMMA_MAX = 180                     // TOUPCAM_GAMMA_MAX

        private val GAMMA = Range(GAMMA_MIN, GAMMA_MAX, fromSdk = false, note = "у SDK нет диапазона гаммы (toupcam.h)")

        /** Границы до получения данных SDK (или без камеры). */
        val FALLBACK = CameraLimits(
            exposureUs = Range(FALLBACK_EXPO_MIN_US, FALLBACK_EXPO_MAX_US, fromSdk = false, note = "камера не подключена"),
            gainPercent = Range(FALLBACK_GAIN_MIN_PERCENT, FALLBACK_GAIN_MAX_PERCENT, fromSdk = false, note = "камера не подключена"),
            gamma = GAMMA,
        )

        /** Границы по данным SDK; каждый параметр проверяется отдельно, некорректный — запасной. */
        fun from(readback: CameraReadback?): CameraLimits {
            fun pick(r: CameraReadback.IntRange3?, fbMin: Int, fbMax: Int, minAllowed: Int): Range = when {
                r == null -> Range(fbMin, fbMax, false, "SDK не сообщил диапазон")
                r.min < minAllowed || r.max < r.min -> Range(fbMin, fbMax, false, "некорректный диапазон SDK ${r.min}..${r.max}")
                else -> Range(r.min, r.max, true)
            }
            return CameraLimits(
                exposureUs = pick(readback?.exposureRangeUs, FALLBACK_EXPO_MIN_US, FALLBACK_EXPO_MAX_US, minAllowed = 1),
                gainPercent = pick(readback?.gainRangePercent, FALLBACK_GAIN_MIN_PERCENT, FALLBACK_GAIN_MAX_PERCENT, minAllowed = 1),
                gamma = GAMMA,
            )
        }
    }
}
