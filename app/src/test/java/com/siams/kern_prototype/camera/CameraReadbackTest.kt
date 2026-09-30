package com.siams.kern_prototype.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** R14: разбор фактического состояния SDK; значения с ошибкой вызова — «не сообщено». */
class CameraReadbackTest {
    private val eNotImpl = 0x80004001.toInt()

    @Test
    fun parsesValuesAndMarksFailedCallsAsUnknown() {
        val r = CameraReadback.parse(
            intArrayOf(
                0, 8000, 0, 7990, 0, 200, 0, 100, eNotImpl, 9, 9, 9, 0, 0,
                0, 100, 15_000_000, 8000, 0, 100, 5000, 100, 0, 1, eNotImpl, 7,
            ),
        )
        assertEquals(8000, r.exposureUs)
        assertEquals(7990, r.realExposureUs)
        assertEquals(200, r.gainPercent)
        assertEquals(100, r.gamma)
        assertNull("WB не сообщён (E_NOTIMPL)", r.wbGain)
        assertEquals(CameraReadback.IntRange3(100, 15_000_000, 8000), r.exposureRangeUs)
        assertEquals(CameraReadback.IntRange3(100, 5000, 100), r.gainRangePercent)
        assertEquals(1, r.builtinWbGainOption)
        assertNull(r.ispOption)
    }

    @Test
    fun shortArrayDoesNotCrash() {
        assertNull(CameraReadback.parse(IntArray(0)).exposureUs)
    }
}
