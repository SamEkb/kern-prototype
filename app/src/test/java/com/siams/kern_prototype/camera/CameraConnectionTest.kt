package com.siams.kern_prototype.camera

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Владение камерой на процесс: разрешение не теряется ни при каком неуспешном выходе. */
class CameraConnectionTest {

    private val rig = FakeCameraRig()
    private var n = 0
    private fun device() = FakeToupcamDevice(rig, "D${++n}")

    @After
    fun tearDown() {
        CameraConnection.afterAcquireForTest = null
    }

    /** Следующее подключение открывается (разрешение свободно) и корректно закрывается. */
    private suspend fun assertOwnershipFree() {
        val conn = CameraConnection.open(device(), 1_000) { _, _, _, _ -> }
        conn.close().let { withTimeout(2_000) { it.await() } }
    }

    @Test
    fun timeoutAfterAcquireReturnsPermit() = runBlocking {
        // Разрешение получено, но таймаут ожидания срабатывает до выхода из блока.
        CameraConnection.afterAcquireForTest = { kotlinx.coroutines.delay(500) }
        try {
            CameraConnection.open(device(), 100) { _, _, _, _ -> }
            fail("ожидался отказ по таймауту")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("занята"))
        }
        CameraConnection.afterAcquireForTest = null
        assertOwnershipFree()
    }

    @Test
    fun callerCancellationAfterAcquireReturnsPermit() = runBlocking {
        val acquired = CompletableDeferred<Unit>()
        CameraConnection.afterAcquireForTest = { acquired.complete(Unit); awaitCancellation() }
        val opening = async(Dispatchers.IO) { CameraConnection.open(device(), 10_000) { _, _, _, _ -> } }
        withTimeout(2_000) { acquired.await() }
        opening.cancel()
        try { opening.await(); fail() } catch (_: CancellationException) {}
        CameraConnection.afterAcquireForTest = null
        assertOwnershipFree()
    }

    @Test
    fun busyWhileOwnedThenFreeAfterClose() = runBlocking {
        val first = CameraConnection.open(device(), 1_000) { _, _, _, _ -> }
        try {
            CameraConnection.open(device(), 100) { _, _, _, _ -> }
            fail("пока первое подключение не закрыто, второе невозможно")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("занята"))
        }
        withTimeout(2_000) { first.close().await() }
        assertOwnershipFree()
    }

    /** 5k: время и номер события — в момент поступления; задача после границы — после события. */
    @Test
    fun arrivalTimeIsTakenBeforeTheQueue() = runBlocking {
        val handled = CompletableDeferred<Pair<Long, Long>>()   // (время поступления, время обработки)
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val conn = CameraConnection.open(device(), 1_000) { _, _, _, arrivedNs ->
            order += "event"
            handled.complete(arrivedNs to System.nanoTime())
        }
        val gate = java.util.concurrent.CountDownLatch(1)
        val started = java.util.concurrent.CountDownLatch(1)
        val blocker = async(Dispatchers.IO) { conn.run { started.countDown(); gate.await(5, java.util.concurrent.TimeUnit.SECONDS) } }
        started.await(3, java.util.concurrent.TimeUnit.SECONDS)
        val posted = System.nanoTime()
        conn.post(0x000d)
        val boundary = conn.arrivalBoundary()
        val after = async(Dispatchers.IO) { conn.run { order += "after-boundary" } }
        Thread.sleep(300)
        gate.countDown()
        blocker.await(); after.await()
        val (arrivedNs, processedNs) = handled.await()
        assertTrue("время поступления — при post", (arrivedNs - posted) / 1_000_000 < 50)
        assertTrue("обработано после задержки очереди", (processedNs - arrivedNs) / 1_000_000 >= 250)
        assertEquals(1L, boundary)
        assertEquals("задача после границы — после события", listOf("event", "after-boundary"), order.toList())
        conn.close().await()
    }
}
