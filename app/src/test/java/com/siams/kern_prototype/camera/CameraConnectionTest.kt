package com.siams.kern_prototype.camera

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
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
        val conn = CameraConnection.open(device(), 1_000) { _, _, _ -> }
        conn.close().let { withTimeout(2_000) { it.await() } }
    }

    @Test
    fun timeoutAfterAcquireReturnsPermit() = runBlocking {
        // Разрешение получено, но таймаут ожидания срабатывает до выхода из блока.
        CameraConnection.afterAcquireForTest = { kotlinx.coroutines.delay(500) }
        try {
            CameraConnection.open(device(), 100) { _, _, _ -> }
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
        val opening = async(Dispatchers.IO) { CameraConnection.open(device(), 10_000) { _, _, _ -> } }
        withTimeout(2_000) { acquired.await() }
        opening.cancel()
        try { opening.await(); fail() } catch (_: CancellationException) {}
        CameraConnection.afterAcquireForTest = null
        assertOwnershipFree()
    }

    @Test
    fun busyWhileOwnedThenFreeAfterClose() = runBlocking {
        val first = CameraConnection.open(device(), 1_000) { _, _, _ -> }
        try {
            CameraConnection.open(device(), 100) { _, _, _ -> }
            fail("пока первое подключение не закрыто, второе невозможно")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("занята"))
        }
        withTimeout(2_000) { first.close().await() }
        assertOwnershipFree()
    }
}
