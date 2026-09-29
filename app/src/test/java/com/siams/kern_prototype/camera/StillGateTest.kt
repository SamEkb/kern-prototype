package com.siams.kern_prototype.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Учёт снимка: позднее событие/чтение не завершает новую операцию (п.3–5 ревью). */
class StillGateTest {

    private val results = mutableListOf<Pair<String, Result<Unit>>>()
    private fun cb(name: String): (Result<Unit>) -> Unit = { results += name to it }

    private fun connectedGate(): Pair<StillGate<String>, Long> {
        val g = StillGate<String>()
        val gen = g.startConnection()
        g.connected()
        return g to gen
    }

    @Test
    fun normalSnapCompletesOnce() {
        val (g, gen) = connectedGate()
        val p = g.begin("buf", cb("a"))
        val taken = g.take(gen)!!
        assertSame(p, taken)
        assertEquals("buf", taken.target)
        assertNull("повторное событие — не к кому", g.take(gen))
        g.finish(taken, Result.success(Unit))
        g.finish(taken, Result.success(Unit)) // повторное завершение игнорируется
        assertEquals(1, results.size)
        assertFalse(g.needsRecovery)
        g.begin("buf", cb("b"))                // операция освобождена
    }

    @Test
    fun operationStaysBusyWhileFrameIsRead() {
        val (g, gen) = connectedGate()
        val p = g.begin("buf", cb("a"))
        g.take(gen) // событие получено, чтение идёт
        try { g.begin("buf2", cb("b")); fail("пока кадр читается, новый снимок запрещён") } catch (_: IllegalStateException) {}
        g.finish(p, Result.success(Unit))
        g.begin("buf2", cb("b"))
    }

    @Test
    fun cancellationDuringReadRequiresRecovery() {
        val (g, gen) = connectedGate()
        val p = g.begin("buf", cb("a"))
        g.take(gen)                 // чтение началось
        g.abandon(p)                // ожидающий отменён посреди чтения
        assertTrue("исход неизвестен — нужно переподключение", g.needsRecovery)
        try { g.begin("buf2", cb("b")); fail() } catch (_: IllegalStateException) {}
        g.finish(p, Result.success(Unit)) // чтение закончилось позже
        try { g.begin("buf2", cb("b")); fail("до переподключения всё ещё нельзя") } catch (_: IllegalStateException) {}
    }

    @Test
    fun timeoutBlocksNextSnapUntilSuccessfulReconnect() {
        val (g, gen1) = connectedGate()
        g.abandon(g.begin("buf", cb("old"))) // таймаут
        assertTrue(g.needsRecovery)
        try { g.begin("buf", cb("new")); fail("снимок до восстановления запрещён") } catch (_: IllegalStateException) {}
        assertNull(g.take(gen1)) // позднее событие старого Snap — ни к чему не относится

        // Переподключение НЕ удалось: новое поколение начато, но признак остаётся.
        g.startConnection()
        g.markRecoveryRequired()
        assertTrue(g.needsRecovery)
        try { g.begin("buf", cb("new")); fail("после неудачного переподключения снимать нельзя") } catch (_: IllegalStateException) {}

        // Следующая попытка удалась.
        val gen3 = g.startConnection()
        assertTrue("startConnection сам по себе признак не снимает", g.needsRecovery)
        g.connected()
        val fresh = g.begin("buf", cb("new"))
        assertNull("событие прежнего подключения игнорируется", g.take(gen1))
        assertSame(fresh, g.take(gen3))
        assertTrue(results.isEmpty())
    }

    @Test
    fun rejectedSnapNeedsNoRecovery() {
        val (g, _) = connectedGate()
        g.rejected(g.begin("buf", cb("a")))
        assertFalse(g.needsRecovery)
        g.begin("buf", cb("b"))
    }

    @Test
    fun onlyOneSnapAtATime() {
        val (g, _) = connectedGate()
        g.begin("buf", cb("a"))
        try { g.begin("buf", cb("b")); fail() } catch (_: IllegalStateException) {}
    }

    @Test
    fun disconnectFailsWaitingOperationAndRequiresRecovery() {
        val (g, _) = connectedGate()
        g.begin("buf", cb("a"))
        g.fail(IllegalStateException("отключена"))
        assertEquals("a", results.single().first)
        assertTrue(results.single().second.isFailure)
        assertTrue(g.needsRecovery)
    }

    @Test
    fun disconnectDuringReadLeavesCompletionToReader() {
        val (g, gen) = connectedGate()
        val p = g.begin("buf", cb("a"))
        g.take(gen)
        g.fail(IllegalStateException("отключена"))
        assertTrue(results.isEmpty())              // читающий обработчик ещё работает
        g.finish(p, Result.failure(IllegalStateException("pull failed")))
        assertEquals(1, results.size)
        assertTrue(g.needsRecovery)
    }
}
