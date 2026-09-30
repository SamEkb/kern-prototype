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

    private const val WBGAIN_LIMIT = 127         // TOUPCAM_WBGAIN_MIN/MAX = -127..127
    const val GAMMA_MIN = CameraLimits.GAMMA_MIN
    const val GAMMA_MAX = CameraLimits.GAMMA_MAX
    const val GAMMA_DEF = 100                     // TOUPCAM_GAMMA_DEF (1.0)

    /**
     * R14: что отправлено в SDK (нативные значения ПОСЛЕ ограничения границами камеры) и результат
     * каждого сеттера (HRESULT). Отказ сеттера снимок не останавливает — он фиксируется для
     * диагностики и аудита.
     */
    @kotlinx.serialization.Serializable
    data class ApplyReport(
        val exposureUs: Int,
        val gainPercent: Int,
        val wbGain: List<Int>,
        val gamma: Int,
        /** Сеттер → HRESULT (hex) для ВСЕХ вызовов, в порядке вызова. */
        val results: Map<String, String> = emptyMap(),
        /** Сеттер → HRESULT (hex) для отклонённых (hr < 0); пусто — все приняты. */
        val rejected: Map<String, String> = emptyMap(),
    )

    /**
     * Применить профиль с границами [limits] текущей камеры. Вызовы SDK и их порядок — прежние;
     * границы влияют только на значения (сохранённый профиль не меняется).
     */
    internal fun apply(cam: ToupcamDevice, profile: CameraProfile, limits: CameraLimits = CameraLimits.FALLBACK): ApplyReport {
        val results = linkedMapOf<String, String>()
        val rejected = linkedMapOf<String, String>()
        fun check(name: String, hr: Int) {
            val hex = "0x%08x".format(hr)
            results[name] = hex
            if (hr < 0) rejected[name] = hex
        }
        // Автоэкспозиция — OFF (детерминизм, §6.2).
        check("AutoExpoEnable", cam.setAutoExpoEnable(0))

        val us = limits.exposureUs.clamp(profile.exposureTimeNs / 1_000L)
        check("ExpoTime", cam.setExpoTime(us))

        // ISO трактуем как процент усиления (ISO 100 → 100 % = 1.0x).
        val gain = limits.gainPercent.clamp(profile.iso)
        check("ExpoAGain", cam.setExpoAGain(gain))

        // WB: относительные множители R/B к зелёному → знаковые gain'ы Touptek.
        val green = ((profile.wbGreenEvenGain + profile.wbGreenOddGain) / 2f).coerceAtLeast(0.01f)
        val wb = listOf(toWbGain(profile.wbRedGain / green), 0, toWbGain(profile.wbBlueGain / green))
        check("WhiteBalanceGain", cam.setWhiteBalanceGain(wb[0], wb[1], wb[2]))

        // Гамма (§3.3) — тональная кривая, критична для УФ.
        val gamma = limits.gamma.clamp(profile.gamma)
        check("Gamma", cam.setGamma(gamma))
        return ApplyReport(us, gain, wb, gamma, results, rejected)
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
