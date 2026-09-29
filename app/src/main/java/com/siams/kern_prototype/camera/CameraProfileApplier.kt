package com.siams.kern_prototype.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.util.Range
import android.util.Rational
import com.siams.kern_prototype.domain.model.CameraProfile

/**
 * Единая точка применения ручных параметров к [CaptureRequest.Builder] (§6.2, §13).
 * Жёстко блокирует всю автоматику: AE/AWB/AF = OFF. Значения клампятся к диапазонам
 * устройства из [CameraCharacteristics].
 */
object CameraProfileApplier {

    /** Единичная матрица цветокоррекции (3×3) — фиксирует TRANSFORM_MATRIX-режим (§6.2). */
    private val IDENTITY_TRANSFORM = ColorSpaceTransform(
        arrayOf(
            Rational(1, 1), Rational(0, 1), Rational(0, 1),
            Rational(0, 1), Rational(1, 1), Rational(0, 1),
            Rational(0, 1), Rational(0, 1), Rational(1, 1),
        ),
    )

    fun apply(
        builder: CaptureRequest.Builder,
        profile: CameraProfile,
        chars: CameraCharacteristics,
    ) {
        // CONTROL_MODE = AUTO + индивидуально AE/AWB/AF = OFF — самый совместимый вариант (§6.2).
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)

        // Экспозиция / ISO (AE OFF)
        val expRange: Range<Long>? = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val exposure = clampLong(profile.exposureTimeNs, expRange)
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)

        val isoRange: Range<Int>? = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, clampInt(profile.iso, isoRange))

        // Длительность кадра: не меньше экспозиции; по умолчанию = exposure.
        val frameDuration = if (profile.frameDurationNs > 0) profile.frameDurationNs else exposure
        builder.set(CaptureRequest.SENSOR_FRAME_DURATION, frameDuration)

        // Фокус (AF OFF). Кламп к минимальной дистанции фокуса устройства.
        val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val focus = profile.focusDistance.coerceIn(0f, if (minFocus > 0f) minFocus else Float.MAX_VALUE)
        builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focus)

        // Баланс белого (AWB OFF): TRANSFORM_MATRIX + COLOR_CORRECTION_GAINS.
        // Матрицу цветокоррекции ФИКСИРУЕМ явно (единичная), иначе preview (TEMPLATE_PREVIEW)
        // и снимок (TEMPLATE_STILL_CAPTURE) могут взять разные дефолтные матрицы и дать
        // разный цвет — это ломает идентичность (§5, §6.2). При явной матрице цвет полностью
        // определяется gains и воспроизводим от кадра к кадру и от сессии к сессии.
        builder.set(
            CaptureRequest.COLOR_CORRECTION_MODE,
            CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX,
        )
        builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, IDENTITY_TRANSFORM)
        builder.set(
            CaptureRequest.COLOR_CORRECTION_GAINS,
            RggbChannelVector(
                profile.wbRedGain,
                profile.wbGreenEvenGain,
                profile.wbGreenOddGain,
                profile.wbBlueGain,
            ),
        )

        // Отключить стабилизацию — влияет на геометрию кадра (§6.2).
        builder.set(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
        )
        if (hasOis(chars)) {
            builder.set(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF,
            )
        }
    }

    /** MANUAL_SENSOR + MANUAL_POST_PROCESSING (§6.1). */
    fun supportsManual(chars: CameraCharacteristics): Boolean {
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return false
        val sensor = caps.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR,
        )
        val post = caps.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING,
        )
        return sensor && post
    }

    private fun hasOis(chars: CameraCharacteristics): Boolean {
        val modes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        return modes != null && modes.any { it == CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON }
    }

    private fun clampLong(v: Long, range: Range<Long>?): Long =
        if (range == null) v else v.coerceIn(range.lower, range.upper)

    private fun clampInt(v: Int, range: Range<Int>?): Int =
        if (range == null) v else v.coerceIn(range.lower, range.upper)
}
