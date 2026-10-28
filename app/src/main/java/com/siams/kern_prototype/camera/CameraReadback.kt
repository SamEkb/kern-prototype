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
    // 5l (диагностика WB/AE, только чтение): null — SDK отказал или значение не прочитано.
    /** Цель автоэкспозиции (`get_AutoExpoTarget`). */
    val autoExposureTarget: Int? = null,
    /** Температура/оттенок, если SDK работает в режиме Temp/Tint (`get_TempTint`). */
    val tempTint: List<Int>? = null,
    /** `TOUPCAM_OPTION_AUTOEXP_POLICY`. */
    val autoExposurePolicy: Int? = null,
    /** `TOUPCAM_OPTION_AUTOEXPOSURE_PERCENT`. */
    val autoExposurePercent: Int? = null,
    /** `TOUPCAM_OPTION_LINEAR` (встроенное линейное тональное преобразование). */
    val linearToneMapping: Int? = null,
    /** `TOUPCAM_OPTION_CURVE` (встроенная кривая: 0 — выкл., 1 — полином, 2 — логарифм). */
    val curveToneMapping: Int? = null,
    /** `TOUPCAM_OPTION_BYTEORDER` (0 — RGB, 1 — BGR). */
    val byteOrder: Int? = null,
    /** `TOUPCAM_OPTION_RGB` (0 — RGB24, …). */
    val rgbFormat: Int? = null,
    /**
     * 5l: HRESULT каждого нового чтения («0x…»), по имени поля. Различает неподдерживаемое чтение,
     * другую ошибку SDK и успех. Нет записи — код неизвестен (например, массив прежней раскладки):
     * отказ при этом не подразумевается.
     */
    val readHresults: Map<String, String>? = null,
) {
    @Serializable
    data class IntRange3(val min: Int, val max: Int, val default: Int)

    companion object {
        private fun ok(hr: Int) = hr >= 0 // HRESULT: неотрицательный — успех

        /** Индексы HRESULT чтений результата авто-процедур в массиве `readState` (за ними — значения). */
        internal const val HR_EXPOSURE = 0
        internal const val HR_GAIN = 4
        internal const val HR_WB = 8

        /** 5l: новые чтения — имя поля и индекс HRESULT в массиве `readState` (jnicam.cpp). */
        private val DIAGNOSTIC_READS = listOf(
            "autoExposureTarget" to 26, "tempTint" to 28, "autoExposurePolicy" to 31,
            "autoExposurePercent" to 33, "linearToneMapping" to 35, "curveToneMapping" to 37,
            "byteOrder" to 39, "rgbFormat" to 41,
        )

        /**
         * Разбор массива из `CamLib.readState()` (раскладка — jnicam.cpp). Короче ожидаемого —
         * недостающие значения «неизвестно» (null), а не ноль.
         */
        fun parse(v: IntArray): CameraReadback {
            fun at(i: Int) = v.getOrElse(i) { -1 }
            fun value(hrIdx: Int) = if (ok(at(hrIdx))) at(hrIdx + 1) else null
            return CameraReadback(
                exposureUs = value(HR_EXPOSURE),
                realExposureUs = value(2),
                gainPercent = value(HR_GAIN),
                gamma = value(6),
                wbGain = if (ok(at(HR_WB))) listOf(at(HR_WB + 1), at(HR_WB + 2), at(HR_WB + 3)) else null,
                autoExposureMode = value(12),
                exposureRangeUs = if (ok(at(14))) IntRange3(at(15), at(16), at(17)) else null,
                gainRangePercent = if (ok(at(18))) IntRange3(at(19), at(20), at(21)) else null,
                builtinWbGainOption = value(22),
                ispOption = value(24),
                autoExposureTarget = value(26),
                tempTint = if (ok(at(28))) listOf(at(29), at(30)) else null,
                autoExposurePolicy = value(31),
                autoExposurePercent = value(33),
                linearToneMapping = value(35),
                curveToneMapping = value(37),
                byteOrder = value(39),
                rgbFormat = value(41),
                readHresults = DIAGNOSTIC_READS
                    .mapNotNull { (name, hrIdx) -> v.getOrNull(hrIdx)?.let { name to "0x%08x".format(it) } }
                    .toMap()
                    .ifEmpty { null },
            )
        }
    }
}
