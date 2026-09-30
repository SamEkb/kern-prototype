package com.siams.kern_prototype.camera

import android.graphics.Bitmap
import com.siams.kern_prototype.domain.model.LampType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** R22: критерии пригодности эталона FFC — насыщение, полезный сигнал, усиление коррекции. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FfcQualityTest {

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun bmp(w: Int, h: Int, color: (x: Int, y: Int) -> Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until h) for (x in 0 until w) setPixel(x, y, color(x, y))
        }

    private fun uniform(v: Int) = bmp(20, 10) { _, _ -> rgb(v, v, v) }

    @Test
    fun uniformMidGreyIsAccepted() {
        val r = FfcQuality.evaluate(LampType.DAYLIGHT, uniform(128), uniform(128))
        assertTrue(r.problems.toString(), r.accepted)
        assertEquals(List(3) { 128 }, r.channels.map { it.median })
        assertEquals(List(3) { 1.0 }, r.correctionGain)
        assertEquals(0.0, r.anySaturatedFraction, 0.0)
    }

    @Test
    fun saturationCountsPixelsWithAnyChannelClipped() {
        // 10 % пикселей насыщены только в R, ещё 10 % — только в B: по каналам по 10 %, в сумме 20 %.
        val still = bmp(20, 10) { x, _ -> when (x) { 0, 1 -> rgb(255, 100, 100); 2, 3 -> rgb(100, 100, 255); else -> rgb(100, 100, 100) } }
        val r = FfcQuality.evaluate(LampType.DAYLIGHT, still, uniform(100))
        assertEquals(0.1, r.channels[0].saturatedFraction, 1e-9)
        assertEquals(0.0, r.channels[1].saturatedFraction, 1e-9)
        assertEquals(0.2, r.anySaturatedFraction, 1e-9)
        assertFalse(r.accepted)
        assertTrue(r.problems.single(), r.problems.single().startsWith("пересвет 20.00%"))
    }

    @Test
    fun smallSaturationWithinThresholdIsAccepted() {
        // 1 пиксель из 2000 = 0.05 % ≤ 0.1 %.
        val still = bmp(100, 20) { x, y -> if (x == 0 && y == 0) rgb(255, 255, 255) else rgb(120, 120, 120) }
        assertTrue(FfcQuality.evaluate(LampType.DAYLIGHT, still, uniform(120)).accepted)
    }

    @Test
    fun weakSignalIsJudgedPerChannelAndPerLamp() {
        val dim = bmp(20, 10) { _, _ -> rgb(30, 90, 90) }        // R слабый
        val day = FfcQuality.evaluate(LampType.DAYLIGHT, dim, dim)
        assertEquals(1, day.problems.size)
        assertTrue(day.problems.single(), day.problems.single().contains("медиана R=30") && !day.problems.single().contains("G="))
        assertTrue("для УФ порог сигнала ниже (предварительный)", FfcQuality.evaluate(LampType.UF, dim, dim).accepted)
        assertTrue(day.thresholds.provisional)
    }

    @Test
    fun correctionGainMatchesTheCorrectionRule() {
        // Шаблон R: среднее (20 + 9·108)/10 = 99.2, минимум 20 → усиление 4.96 (> 4); нулевой канал считается как 1.
        val tmpl = bmp(10, 1) { x, _ -> if (x == 0) rgb(20, 0, 100) else rgb(108, 100, 100) }
        val gains = FfcQuality.correctionGain(tmpl)
        assertEquals(4.96, gains[0], 1e-9)
        assertEquals(90.0, gains[1], 1e-9)       // среднее 90 / max(0, 1)
        assertEquals(1.0, gains[2], 1e-9)
        val r = FfcQuality.evaluate(LampType.DAYLIGHT, uniform(128), tmpl)
        assertTrue(r.problems.single(), r.problems.single().startsWith("неравномерность: усиление коррекции R=4.96, G=90.00"))
    }

    @Test
    fun percentilesComeFromTheFullFrameHistogram() {
        val hist = LongArray(256).also { it[10] = 1; it[100] = 98; it[250] = 1 }
        assertEquals(10, FfcQuality.percentile(hist, 100, 0.01))
        assertEquals(100, FfcQuality.percentile(hist, 100, 0.5))
        assertEquals(100, FfcQuality.percentile(hist, 100, 0.99))
        assertEquals(250, FfcQuality.percentile(hist, 100, 1.0))
    }
}
