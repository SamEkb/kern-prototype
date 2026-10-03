package com.siams.kern_prototype.camera

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** FIXES §1: отрисовка кадра превью с сохранением пропорций (fit-center). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FitCenterTest {

    @Test
    fun widerSourceFitsWidthWithBarsTopAndBottom() {
        assertEquals(Rect(0, 280, 1080, 1000), fitCenter(1500, 1000, 1080, 1280))
    }

    @Test
    fun narrowerSourceFitsHeightWithBarsLeftAndRight() {
        assertEquals(Rect(285, 0, 1395, 740), fitCenter(1500, 1000, 1680, 740))
    }

    @Test
    fun sameAspectFillsWholeSurface() {
        assertEquals(Rect(0, 0, 1080, 720), fitCenter(1500, 1000, 1080, 720))
    }

    @Test
    fun degenerateSizesUseWholeSurface() {
        assertEquals(Rect(0, 0, 100, 50), fitCenter(0, 10, 100, 50))
        assertEquals(Rect(0, 0, 0, 0), fitCenter(10, 10, 0, 0))
    }
}
