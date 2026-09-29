package com.siams.kern_prototype.domain.model

import kotlinx.serialization.Serializable

/**
 * Сохранённый набор РУЧНЫХ параметров съёмки под тип освещения (§6).
 * Хранится глобально в DataStore и переживает перезапуск. Снимок профиля также
 * пишется рядом с каждым кадром (для аудита идентичности, §6.5).
 *
 * Баланс белого хранится как COLOR_CORRECTION_GAINS (RGGB) — при применении
 * ставится вместе с COLOR_CORRECTION_MODE = TRANSFORM_MATRIX и AWB = OFF (§6.2).
 */
@Serializable
data class CameraProfile(
    val lampType: LampType,
    /** SENSOR_EXPOSURE_TIME, нс. AE = OFF. */
    val exposureTimeNs: Long,
    /** SENSOR_SENSITIVITY (ISO). AE = OFF. */
    val iso: Int,
    /** LENS_FOCUS_DISTANCE, диоптрии (0 = бесконечность). AF = OFF. */
    val focusDistance: Float,
    // COLOR_CORRECTION_GAINS (RGGB), AWB = OFF.
    val wbRedGain: Float,
    val wbGreenEvenGain: Float,
    val wbGreenOddGain: Float,
    val wbBlueGain: Float,
    /**
     * Гамма (тональная кривая), проценты. Touptek: `put_Gamma` 20..180 (100 = 1.0).
     * §3.3 ТЗ: критична для УФ — проявляет детали в тенях без пересвета.
     */
    val gamma: Int = 100,
    /**
     * Путь к эталонному кадру FFC (§2.3) для этого профиля освещения, «» = не снят.
     * Это уменьшенный, размытый шаблон неравномерности; каждый снимок керна
     * нормализуется делением на него. Свой эталон на профиль (дневной/УФ).
     */
    val ffcTemplatePath: String = "",
    /** SENSOR_FRAME_DURATION, нс (0 → выбрать по exposure). */
    val frameDurationNs: Long = 0L,
    val jpegQuality: Int = 100,
    /**
     * Время последнего сохранения профиля, мс (0 = профиль ни разу не калибровался,
     * т.е. это дефолт). §4.1 ТЗ: показывается пользователю; ненулевое значение
     * означает «уже сохранённый профиль» → при повторном изменении нужно предупреждение.
     */
    val lastModifiedAt: Long = 0L,
) {
    companion object {
        /** Разумные дефолты для «Дневного света» до первой калибровки оператором. */
        fun defaultFor(lampType: LampType): CameraProfile = when (lampType) {
            LampType.DAYLIGHT -> CameraProfile(
                lampType = LampType.DAYLIGHT,
                exposureTimeNs = 8_000_000L,   // 1/125 c
                iso = 100,
                focusDistance = 0f,            // бесконечность
                wbRedGain = 1.9f,
                wbGreenEvenGain = 1.0f,
                wbGreenOddGain = 1.0f,
                wbBlueGain = 1.7f,
            )
            LampType.UF -> CameraProfile(
                lampType = LampType.UF,
                exposureTimeNs = 33_000_000L,  // 1/30 c — УФ темнее
                iso = 400,
                focusDistance = 0f,
                wbRedGain = 1.5f,
                wbGreenEvenGain = 1.0f,
                wbGreenOddGain = 1.0f,
                wbBlueGain = 2.2f,
            )
        }
    }
}
