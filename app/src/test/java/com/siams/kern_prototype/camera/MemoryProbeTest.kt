package com.siams.kern_prototype.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** R21: замер памяти операции — отметки фаз, пики опроса, «неизвестно» при сбое чтения. */
class MemoryProbeTest {

    /** Управляемые метрики: значения меняются тестом, число чтений считается. */
    private class FakeReader : MemoryReader {
        @Volatile var java = 10L
        @Volatile var native = 100L
        @Volatile var pss = 1000L          // КиБ
        @Volatile var failNative = false
        @Volatile var reads = 0
        override fun javaUsedBytes() = java
        override fun javaMaxBytes() = 512L
        override fun nativeAllocatedBytes(): Long { reads++; if (failNative) error("сбой"); return native }
        override fun pssKb() = pss
    }

    private fun waitUntil(ms: Long = 2000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(2) }
        throw AssertionError("условие не выполнено")
    }

    @Test
    fun phasesAreRecordedInOrderWithValues() {
        val r = FakeReader()
        val p = MemoryProbe("op", r, intervalMs = 5)
        r.native = 300
        p.mark("pulled")
        r.native = 200
        val t = p.finish("ok", mapOf("stillBitmap" to 192L))
        assertEquals(listOf("start", "pulled", "end:ok"), t.phases.map { it.name })
        assertEquals(listOf(100L, 300L, 200L), t.phases.map { it.nativeBytes })
        assertEquals("PSS в байтах", 1000L * 1024, t.phases[0].pssBytes)
        assertEquals(512L, t.javaMaxBytes)
        assertEquals(mapOf("stillBitmap" to 192L), t.buffers)
        assertEquals("op", t.operation)
    }

    @Test
    fun peakBetweenMarksIsCaughtBySampler() {
        val r = FakeReader()
        val p = MemoryProbe("op", r, intervalMs = 2)
        r.native = 9_999                       // кратковременный пик внутри «вызова SDK»
        val before = r.reads
        waitUntil { r.reads > before + 2 }     // опрос его увидел
        r.native = 100
        val t = p.finish()
        assertEquals(9_999L, t.peakNativeBytes)
        assertTrue("пика нет ни в одной отметке", t.phases.none { it.nativeBytes == 9_999L })
    }

    @Test
    fun readFailureIsUnknownNotZero() {
        val r = FakeReader().apply { failNative = true; pss = 0 }
        val t = MemoryProbe("op", r, intervalMs = 5).finish()
        assertNull(t.peakNativeBytes)
        assertNull(t.peakPssBytes)
        assertTrue(t.phases.all { it.nativeBytes == null && it.pssBytes == null })
        assertEquals(10L, t.peakJavaBytes)     // остальные метрики не пострадали
    }

    @Test
    fun finishStopsSamplerAndIsIdempotent() {
        val r = FakeReader()
        val p = MemoryProbe("op", r, intervalMs = 2)
        val t = p.finish("error")
        waitUntil { p.samplerStopped }
        val reads = r.reads
        Thread.sleep(30)
        assertEquals("после завершения опроса нет", reads, r.reads)
        p.mark("late")
        assertSame(t, p.finish("ok"))
        assertEquals(listOf("start", "end:error"), t.phases.map { it.name })
    }
}
