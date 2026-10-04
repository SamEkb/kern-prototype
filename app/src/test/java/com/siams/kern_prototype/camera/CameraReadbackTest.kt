package com.siams.kern_prototype.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /** 5l: новые чтения — значение при успехе, «неизвестно» при отказе; прежние индексы не сдвинуты. */
    @Test
    fun extendedReadsParseAndFailuresAreUnknown() {
        val base = IntArray(26).also { it[0] = 0; it[1] = 8000 }
        val extra = intArrayOf(
            0, 120,                   // цель AE
            0x80004001.toInt(), 0, 0, // Temp/Tint — отказ
            0, 1,                     // политика AE
            0, 10,                    // процент AE
            0, 1,                     // LINEAR
            0, 2,                     // CURVE
            -1, 0,                    // BYTEORDER — отказ
            0, 0,                     // RGB
        )
        val r = CameraReadback.parse(base + extra)
        assertEquals("прежние поля на месте", 8000, r.exposureUs)
        assertEquals(120, r.autoExposureTarget)
        assertNull("отказ Temp/Tint — неизвестно", r.tempTint)
        assertEquals(1, r.autoExposurePolicy)
        assertEquals(10, r.autoExposurePercent)
        assertEquals(1, r.linearToneMapping)
        assertEquals(2, r.curveToneMapping)
        assertNull(r.byteOrder)
        assertEquals(0, r.rgbFormat)
        assertEquals(listOf(6503, 1000), CameraReadback.parse(base + extra.copyOf().also { it[2] = 0; it[3] = 6503; it[4] = 1000 }).tempTint)
    }

    /** Прежняя раскладка (26 значений): новые поля — «неизвестно», а не ноль. */
    @Test
    fun legacyLayoutLeavesNewFieldsUnknown() {
        val r = CameraReadback.parse(IntArray(26))
        assertNull(r.autoExposureTarget)
        assertNull(r.tempTint)
        assertNull(r.autoExposurePolicy)
        assertNull(r.curveToneMapping)
        assertNull(r.rgbFormat)
    }

    /** 5l: HRESULT новых чтений сохраняются — разные коды ошибок различимы; есть и в JSON аудита. */
    @Test
    fun readHresultsKeepDistinctCodesAndSurviveSerialization() {
        val extra = intArrayOf(
            0, 120,                            // цель AE — S_OK
            0x80004001.toInt(), 0, 0,          // Temp/Tint — E_NOTIMPL
            0x80070057.toInt(), 0,             // политика AE — E_INVALIDARG
            1, 10,                             // процент AE — S_FALSE (успех)
            0, 1, 0, 2, 0, 0, 0, 0,
        )
        val r = CameraReadback.parse(IntArray(26) + extra)
        val hr = r.readHresults!!
        assertEquals("0x00000000", hr["autoExposureTarget"])
        assertEquals("0x80004001", hr["tempTint"])
        assertEquals("0x80070057", hr["autoExposurePolicy"])
        assertEquals("0x00000001", hr["autoExposurePercent"])
        assertEquals(10, r.autoExposurePercent)
        assertEquals(8, hr.size)
        assertNull("E_INVALIDARG — значение неизвестно", r.autoExposurePolicy)

        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val audit = CaptureAudit(
            requested = CaptureAudit.Requested(1L, 100, 100, 1f, 1f, 1f),
            applied = null, limits = CameraLimits.FALLBACK, readback = r,
        )
        val text = json.encodeToString(CaptureAudit.serializer(), audit)
        assertTrue(text, text.contains("\"tempTint\":\"0x80004001\"") && text.contains("\"autoExposurePolicy\":\"0x80070057\""))
        assertEquals(r, json.decodeFromString(CaptureAudit.serializer(), text).readback)
        assertTrue("в журнал/сводки — через toString", r.toString().contains("tempTint=0x80004001"))
    }

    /** Прежняя раскладка: кодов новых чтений нет — неизвестно, без выдуманного отказа. */
    @Test
    fun legacyLayoutHasNoReadHresults() {
        assertNull(CameraReadback.parse(IntArray(26)).readHresults)
        val partial = CameraReadback.parse(IntArray(30))   // есть только цель AE и Temp/Tint
        assertEquals(setOf("autoExposureTarget", "tempTint"), partial.readHresults!!.keys)
    }
}
