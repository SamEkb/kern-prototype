package com.siams.kern_prototype.camera

import com.siams.kern_prototype.domain.model.CameraProfile
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Единая точка применения ручного профиля к камере Touptek (аналог
 * [CameraProfileApplier] для Camera2, §6.2, §13). Жёстко выключает автоэкспозицию
 * и фиксирует WB — иначе воспроизводимость («идентичность» пары кадров) не гарантируется.
 *
 * Модель [CameraProfile] задумана под Camera2 (нс, ISO, RGGB-gains). Здесь она
 * отображается в родные параметры Touptek:
 *  - `exposureTimeNs` → `setExpoTime`, микросекунды (0.1 мс … 15 с);
 *  - `iso`            → `setExpoAGain`, аналоговое усиление в % (100 = 1.0x);
 *  - RGGB-gains       → `setWhiteBalanceGain` (знаковые R/G/B), G — опорный;
 *  - `focusDistance`  — ИГНОРИРУЕТСЯ: у микроскопа фокус механический.
 */
object ToupcamProfileApplier {

    private const val EXPO_MIN_US = 100          // ~0.1 мс
    private const val EXPO_MAX_US = 15_000_000   // 15 с
    private const val GAIN_MIN_PERCENT = 100     // 1.0x
    private const val GAIN_MAX_PERCENT = 5100    // запас; реально клампится камерой
    private const val WBGAIN_LIMIT = 127         // TOUPCAM_WBGAIN_MIN/MAX = -127..127
    const val GAMMA_MIN = 20                      // TOUPCAM_GAMMA_MIN
    const val GAMMA_MAX = 180                     // TOUPCAM_GAMMA_MAX
    const val GAMMA_DEF = 100                     // TOUPCAM_GAMMA_DEF (1.0)

    /**
     * R14: что отправлено в SDK (нативные значения) и какие сеттеры SDK отклонил (HRESULT).
     * Отказ сеттера снимок не останавливает — он фиксируется для диагностики и аудита.
     */
    @kotlinx.serialization.Serializable
    data class ApplyReport(
        val exposureUs: Int,
        val gainPercent: Int,
        val wbGain: List<Int>,
        val gamma: Int,
        /** Сеттер → HRESULT (hex) для отклонённых; пусто — все приняты. */
        val rejected: Map<String, String> = emptyMap(),
    )

    internal fun apply(cam: ToupcamDevice, profile: CameraProfile): ApplyReport {
        val rejected = linkedMapOf<String, String>()
        fun check(name: String, hr: Int) {
            if (hr < 0) rejected[name] = "0x%08x".format(hr)
        }
        // Автоэкспозиция — OFF (детерминизм, §6.2).
        check("AutoExpoEnable", cam.setAutoExpoEnable(0))

        val us = (profile.exposureTimeNs / 1_000L).coerceIn(EXPO_MIN_US.toLong(), EXPO_MAX_US.toLong()).toInt()
        check("ExpoTime", cam.setExpoTime(us))

        // ISO трактуем как процент усиления (ISO 100 → 100 % = 1.0x).
        val gain = profile.iso.coerceIn(GAIN_MIN_PERCENT, GAIN_MAX_PERCENT)
        check("ExpoAGain", cam.setExpoAGain(gain))

        // WB: относительные множители R/B к зелёному → знаковые gain'ы Touptek.
        val green = ((profile.wbGreenEvenGain + profile.wbGreenOddGain) / 2f).coerceAtLeast(0.01f)
        val wb = listOf(toWbGain(profile.wbRedGain / green), 0, toWbGain(profile.wbBlueGain / green))
        check("WhiteBalanceGain", cam.setWhiteBalanceGain(wb[0], wb[1], wb[2]))

        // Гамма (§3.3) — тональная кривая, критична для УФ.
        val gamma = profile.gamma.coerceIn(GAMMA_MIN, GAMMA_MAX)
        check("Gamma", cam.setGamma(gamma))
        return ApplyReport(us, gain, wb, gamma, rejected)
    }

    /**
     * Линейный множитель WB → знаковый gain Touptek. Принято multiplier = 2^(v/64),
     * т.е. v = 64·log2(multiplier). Клампится к диапазону камеры. Приближение
     * (у Touptek свой ISP), но ДЕТЕРМИНИРОВАННОЕ и настраиваемое в редакторе профиля.
     */
    private fun toWbGain(ratio: Float): Int {
        if (ratio <= 0f) return 0
        val v = (64.0 * ln(ratio.toDouble()) / ln(2.0)).roundToInt()
        return v.coerceIn(-WBGAIN_LIMIT, WBGAIN_LIMIT)
    }
}
