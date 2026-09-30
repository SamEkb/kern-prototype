package com.siams.kern_prototype.viewmodel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/** R15: записи по одной, промежуточные пропускаются, последнее всегда записывается; ошибка не останавливает. */
class AutosaverTest {

    @Test
    fun writesOneAtATimeAndAlwaysTheLatest() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val written = Collections.synchronizedList(mutableListOf<Int>())
        val gate = CompletableDeferred<Unit>()
        var active = 0
        var maxActive = 0
        val saver = Autosaver<Int>(scope, onError = {}) { v ->
            synchronized(written) { active++; maxActive = maxOf(maxActive, active) }
            if (v == 1) gate.await()                         // первая запись «долгая»
            written += v
            synchronized(written) { active-- }
        }
        saver.submit(1)
        while (written.isEmpty() && !gate.isCompleted) { kotlinx.coroutines.delay(5); if (maxActive > 0) break }
        (2..5).forEach(saver::submit)                         // накопились за время записи
        // Первая запись ещё идёт: параллельная реализация успела бы начать запись 5 здесь.
        kotlinx.coroutines.delay(150)
        assertEquals("пока идёт первая запись, следующая не начинается", emptyList<Int>(), written.toList())
        gate.complete(Unit)
        assertEquals(Autosaver.FlushResult.Saved, saver.flush())
        assertEquals(listOf(1, 5), written.toList())
        assertEquals(1, maxActive)
        scope.cancel()
    }

    /** Ошибка записи — не «записано»: flush повторяет один раз и сообщает об ошибке. */
    @Test
    fun failedWriteIsNotReportedAsSaved() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val attempts = Collections.synchronizedList(mutableListOf<Int>())
        val broken = java.util.concurrent.atomic.AtomicBoolean(true)
        val saver = Autosaver<Int>(scope, onError = { errors += it.message.orEmpty() }) { v ->
            attempts += v
            if (broken.get()) error("диск занят")
        }
        saver.submit(1)
        val r = saver.flush()
        assertTrue(r is Autosaver.FlushResult.Failed)
        assertEquals("одна повторная попытка", listOf(1, 1), attempts.toList())
        broken.set(false)
        assertEquals("повторное нажатие повторяет запись", Autosaver.FlushResult.Saved, saver.flush())
        assertEquals(listOf(1, 1, 1), attempts.toList())
        scope.cancel()
    }

    /** Ввод, поданный во время ожидания, тоже дожидается записи. */
    @Test
    fun flushWaitsForInputSubmittedWhileWaiting() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val written = Collections.synchronizedList(mutableListOf<Int>())
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val saver = Autosaver<Int>(scope, onError = {}) { v ->
            if (v == 1) { started.complete(Unit); gate.await() }
            if (v == 2) kotlinx.coroutines.delay(150) // запись нового ввода не мгновенная
            written += v
        }
        saver.submit(1)
        started.await()
        val flushing = async { saver.flush() }
        kotlinx.coroutines.delay(50)
        saver.submit(2)                                      // пока ждём запись 1
        gate.complete(Unit)
        assertEquals(Autosaver.FlushResult.Saved, flushing.await())
        assertEquals("к возврату flush записано последнее", listOf(1, 2), written.toList())
        scope.cancel()
    }

    @Test
    fun slowWriteIsATimeoutNotSuccess() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gate = CompletableDeferred<Unit>()
        val saver = Autosaver<Int>(scope, onError = {}) { gate.await() }
        saver.submit(1)
        assertEquals(Autosaver.FlushResult.Timeout, saver.flush(timeoutMs = 100))
        gate.complete(Unit)
        assertEquals(Autosaver.FlushResult.Saved, saver.flush())
        scope.cancel()
    }
}
