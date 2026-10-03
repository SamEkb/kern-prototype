package com.siams.kern_prototype.ui.screens

import com.siams.kern_prototype.camera.CameraLimits
import com.siams.kern_prototype.camera.CameraLimits.Range
import com.siams.kern_prototype.domain.model.CameraProfile
import com.siams.kern_prototype.domain.model.LampType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R14: преобразования ползунков редактора профиля и подсказка «будет отправлено в SDK». */
class ProfileParamsTest {

    private val base = CameraProfile.defaultFor(LampType.DAYLIGHT)

    private fun limits(expo: Range = CameraLimits.FALLBACK.exposureUs, gain: Range = CameraLimits.FALLBACK.gainPercent) =
        CameraLimits(expo, gain, CameraLimits.FALLBACK.gamma)

    private fun spec(limits: CameraLimits, label: String) = profileParams(limits).single { it.label == label }

    @Test
    fun fixedRangeIsDisabledAndAlwaysYieldsTheOnlyValidValue() {
        val gain = spec(limits(gain = Range(100, 100, fromSdk = true)), "Усиление")
        assertTrue(gain.fixed)
        val slider = gain.toSlider(gain.get(base.copy(iso = 800)))
        assertTrue("положение внутри диапазона ползунка", slider in gain.sliderRange)
        for (t in listOf(0f, 0.3f, 1f, gain.sliderRange.start, gain.sliderRange.endInclusive)) {
            assertEquals("любое положение → единственное значение", 100, gain.set(base, gain.fromSlider(t)).iso)
        }
        // То же для линейной шкалы.
        val lin = ParamSpec("x", 5f, 5f, get = { 0f }, set = { p, _ -> p }, format = { "$it" })
        assertTrue(lin.fixed)
        assertEquals(5f, lin.fromSlider(0f))
        assertEquals(5f, lin.fromSlider(1f))
        assertTrue(lin.toSlider(7f) in lin.sliderRange)
    }

    @Test
    fun logScaleRoundTripsWithinCameraRange() {
        val expo = spec(limits(), "Выдержка")
        assertFalse(expo.fixed)
        assertEquals(0f..1f, expo.sliderRange)
        val p = base.copy(exposureTimeNs = 20_000_000L) // 20 мс
        val back = expo.set(base, expo.fromSlider(expo.toSlider(expo.get(p))))
        assertEquals(20_000_000.0, back.exposureTimeNs.toDouble(), 20_000.0)
        assertEquals(expo.min, expo.fromSlider(0f), 1e-3f)
        assertEquals(expo.max, expo.fromSlider(1f), expo.max * 1e-4f)
    }

    @Test
    fun linearScaleStaysInRange() {
        val gamma = spec(limits(), "Гамма")
        assertEquals(20f..180f, gamma.sliderRange)
        assertEquals(180f, gamma.toSlider(500f))
        assertEquals(20f, gamma.fromSlider(-3f))
    }

    @Test
    fun hintOnlyForParamsClampedLikeTheControllerAndSaysSentToSdk() {
        val all = profileParams(limits(gain = Range(100, 800, fromSdk = true)), com.siams.kern_prototype.camera.CameraCapabilities(supportsFocus = true))
        assertEquals(setOf("Выдержка", "Усиление", "Гамма"), all.filter { it.clampedLikeController }.map { it.label }.toSet())

        val gain = all.single { it.label == "Усиление" }
        val hint = gain.sentToSdkHint(2000f)
        assertNotNull(hint)
        assertTrue(hint!!, hint.contains("будет отправлено в SDK 800%"))
        assertFalse(hint.contains("применится"))
        assertNull("внутри границ подсказки нет", gain.sentToSdkHint(400f))

        for (label in listOf("Фокус", "WB R", "WB G", "WB B")) {
            val s = all.single { it.label == label }
            assertNull("$label: расчёт отправляемого значения не общий с контроллером", s.sentToSdkHint(s.max + 5f))
            assertNull(s.sentToSdkHint(s.min - 5f))
        }
    }

    /** FIXES §3: «Фокус» — только у камеры, которая его применяет (у Touptek — нет). */
    @Test
    fun focusIsShownOnlyWhenCameraSupportsIt() {
        val touptek = profileParams(limits())
        assertFalse(touptek.any { it.label == "Фокус" })
        assertEquals(listOf("Выдержка", "Усиление", "Гамма", "WB R", "WB G", "WB B"), touptek.map { it.label })
        val camera2 = profileParams(limits(), com.siams.kern_prototype.camera.CameraCapabilities(supportsFocus = true))
        assertTrue(camera2.any { it.label == "Фокус" })
    }

    private val ru = java.util.Locale.forLanguageTag("ru")
    private val touptek = com.siams.kern_prototype.camera.CameraCapabilities(
        supportsFocus = false, terms = com.siams.kern_prototype.camera.ParameterTerms.TOUPTEK,
    )
    private val camera2 = com.siams.kern_prototype.camera.CameraCapabilities(
        supportsFocus = true, terms = com.siams.kern_prototype.camera.ParameterTerms.CAMERA2,
    )

    /** P3: HUD Touptek — значения профиля в мс, %, гамма, WB R/G/B; без ISO и фокуса. */
    @Test
    fun hudLineUsesTouptekTerms() {
        val p = CameraProfile.defaultFor(LampType.DAYLIGHT).copy(
            exposureTimeNs = 8_000_000L, iso = 250, gamma = 120,
            wbRedGain = 1.9f, wbGreenEvenGain = 1f, wbGreenOddGain = 1f, wbBlueGain = 1.7f,
        )
        assertEquals(
            "Профиль: выдержка 8,00 мс · усиление 250% · гамма 120% · WB 1,90/1,00/1,70",
            profileLine(p, touptek, ru),
        )
    }

    @Test
    fun hudLineShowsExposureOverOneSecondInSeconds() {
        val p = CameraProfile.defaultFor(LampType.UF).copy(exposureTimeNs = 1_500_000_000L)
        assertTrue(profileLine(p, touptek, ru), profileLine(p, touptek, ru).contains("выдержка 1,50 с ·"))
        assertEquals("999,99 мс", formatExposureMs(999.99f, ru))
        assertEquals("граница — ровно 1 с", "1,00 с", formatExposureMs(1000f, ru))
        assertEquals("15,00 с", formatExposureMs(15_000f, ru))
        assertEquals("0,10 мс", formatExposureMs(0.1f, ru))
    }

    /** Camera2 — прежние обозначения (1/x с, ISO) и фокус, если поддерживается. */
    @Test
    fun hudLineKeepsCamera2Terms() {
        val p = CameraProfile.defaultFor(LampType.DAYLIGHT).copy(exposureTimeNs = 8_000_000L, iso = 100, focusDistance = 0f)
        val line = profileLine(p, camera2, ru)
        assertTrue(line, line.startsWith("Профиль: выдержка 1/125 с · ISO 100 · WB "))
        assertTrue(line, line.endsWith(" · фокус ∞"))
        assertFalse(line.contains("усиление"))
        assertFalse(profileLine(p, camera2.copy(supportsFocus = false), ru).contains("фокус"))
    }
}
