package com.siams.kern_prototype.camera

import com.siams.kern_prototype.domain.model.CameraProfile
import com.siams.kern_prototype.domain.model.LampType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R14: границы камеры и их применение; запасной диапазон — явный и по каждому параметру. */
class CameraLimitsTest {

    private fun rb(expo: CameraReadback.IntRange3?, gain: CameraReadback.IntRange3?) =
        CameraReadback(exposureRangeUs = expo, gainRangePercent = gain)

    @Test
    fun sdkRangesAreUsedWhenValid() {
        val l = CameraLimits.from(rb(CameraReadback.IntRange3(50, 2_000_000, 8000), CameraReadback.IntRange3(100, 1600, 100)))
        assertEquals(CameraLimits.Range(50, 2_000_000, fromSdk = true), l.exposureUs)
        assertEquals(CameraLimits.Range(100, 1600, fromSdk = true), l.gainPercent)
    }

    @Test
    fun missingOrInvalidRangeFallsBackPerParameter() {
        val noReadback = CameraLimits.from(null)
        assertFalse(noReadback.exposureUs.fromSdk)
        assertEquals(CameraLimits.FALLBACK_EXPO_MIN_US, noReadback.exposureUs.min)
        assertEquals(CameraLimits.FALLBACK_GAIN_MAX_PERCENT, noReadback.gainPercent.max)

        val mixed = CameraLimits.from(rb(CameraReadback.IntRange3(500, 100, 0), CameraReadback.IntRange3(100, 800, 100)))
        assertFalse("выдержка: max < min — запасной", mixed.exposureUs.fromSdk)
        assertTrue(mixed.exposureUs.note!!.contains("некорректный"))
        assertTrue("усиление — из SDK", mixed.gainPercent.fromSdk)

        val zero = CameraLimits.from(rb(CameraReadback.IntRange3(0, 100, 0), null))
        assertFalse("выдержка: min < 1 — запасной", zero.exposureUs.fromSdk)
        assertFalse(zero.gainPercent.fromSdk)
        assertEquals("SDK не сообщил диапазон", zero.gainPercent.note)
    }

    @Test
    fun applierClampsToCameraLimitsKeepingCallOrder() {
        val calls = mutableListOf<String>()
        val dev = object : ToupcamDevice by NoopDevice() {
            override fun setAutoExpoEnable(mode: Int): Int { calls += "ae$mode"; return 0 }
            override fun setExpoTime(microseconds: Int): Int { calls += "expo=$microseconds"; return 0 }
            override fun setExpoAGain(percent: Int): Int { calls += "gain=$percent"; return 0 }
            override fun setWhiteBalanceGain(r: Int, g: Int, b: Int): Int { calls += "wb"; return 0x80004001.toInt() }
            override fun setGamma(gamma: Int): Int { calls += "gamma=$gamma"; return 0 }
        }
        val limits = CameraLimits.from(rb(CameraReadback.IntRange3(1000, 50_000, 8000), CameraReadback.IntRange3(100, 800, 100)))
        val profile = CameraProfile.defaultFor(LampType.DAYLIGHT).copy(exposureTimeNs = 900_000_000L, iso = 25, gamma = 500)
        val report = ToupcamProfileApplier.apply(dev, profile, limits)

        assertEquals(listOf("ae0", "expo=50000", "gain=100", "wb", "gamma=180"), calls)
        assertEquals(50_000, report.exposureUs)
        assertEquals(100, report.gainPercent)
        assertEquals(180, report.gamma)
        assertEquals(listOf("AutoExpoEnable", "ExpoTime", "ExpoAGain", "WhiteBalanceGain", "Gamma"), report.results.keys.toList())
        assertEquals(mapOf("WhiteBalanceGain" to "0x80004001"), report.rejected)
        assertEquals("сохранённый профиль не переписывается", 25, profile.iso)
    }

    @Test
    fun sdkCallSuccessIsDerivedFromHresultSign() {
        assertTrue(ToupcamController.SdkCall("op", "", 0).ok)
        assertTrue(ToupcamController.SdkCall("op", "", 1).ok)                       // S_FALSE
        assertFalse(ToupcamController.SdkCall("op", "", 0x80004001.toInt()).ok)
        assertFalse("отрицательный HRESULT не только 0x8…", ToupcamController.SdkCall("op", "", 0x90000001.toInt()).ok)
        assertFalse(ToupcamController.SdkCall("op", "", 0xC0000005.toInt()).ok)
        assertEquals("0x90000001", ToupcamController.SdkCall("op", "", 0x90000001.toInt()).hr)
    }

    /** Все методы — заглушки (для делегирования в тестах). */
    private class NoopDevice : ToupcamDevice {
        override suspend fun requestAccess() {}
        override fun open(listener: (Int) -> Unit) {}
        override fun detachListener() {}
        override fun close() {}
        override fun previewSize() = intArrayOf(8, 6)
        override fun stillSize() = intArrayOf(8, 6)
        override fun snap() = true
        override fun pullImage(bitmap: android.graphics.Bitmap) {}
        override fun pullStillImage(bitmap: android.graphics.Bitmap) = true
        override fun applyFlatField(still: android.graphics.Bitmap, template: android.graphics.Bitmap) = true
        override fun setAutoExpoEnable(mode: Int) = 0
        override fun setExpoTime(microseconds: Int) = 0
        override fun setExpoAGain(percent: Int) = 0
        override fun setWhiteBalanceGain(r: Int, g: Int, b: Int) = 0
        override fun setGamma(gamma: Int) = 0
        override fun setMaxAutoExpoTimeAGain(maxTimeUs: Int, maxGainPercent: Int) = 0
        override fun getExpoTime() = 0
        override fun getExpoAGain() = 0
        override fun awbOnce() = 0
        override fun getWhiteBalanceGain() = intArrayOf(0, 0, 0)
        override fun readState() = IntArray(26)
        override fun describe() = ""
    }
}
