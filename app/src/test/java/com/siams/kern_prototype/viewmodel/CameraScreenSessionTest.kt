package com.siams.kern_prototype.viewmodel

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import com.siams.kern_prototype.camera.CameraControllerState
import com.siams.kern_prototype.camera.KernCamera
import com.siams.kern_prototype.camera.UsbDeviceEvents
import com.siams.kern_prototype.domain.model.CameraProfile
import com.siams.kern_prototype.domain.model.LampType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * R16: переходы поверхности превью. Все обращения к камере по поводу поверхности — в одной очереди;
 * главный поток не ждёт отрисовку; итог совпадает с последним решением экрана; текстура
 * освобождается только после подтверждённого отсоединения именно её поверхности.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraScreenSessionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val main = Executors.newSingleThreadExecutor()
    private val surfaceThread = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + main.asCoroutineDispatcher())
    private val events = Collections.synchronizedList(mutableListOf<String>())
    private val names = Collections.synchronizedMap(IdentityHashMap<Any, String>())

    /**
     * Камера с семантикой контроллера: подключение/отсоединение поверхности — под тем же замком,
     * что и отрисовка кадра ([drawOnCurrent]); отсоединение — по экземпляру.
     */
    private inner class FakeCamera : KernCamera {
        private val surfaceLock = Any()
        @Volatile var current: Surface? = null
        @Volatile var failDetach = false
        override val state = MutableStateFlow<CameraControllerState>(CameraControllerState.Ready(android.util.Size(8, 6), true))
        override val activeProfile get() = CameraProfile.defaultFor(LampType.DAYLIGHT)
        override suspend fun open(lampType: LampType) {}
        override suspend fun startSession(surface: Surface) = synchronized(surfaceLock) {
            current = surface
            events += "session:${names[surface]}"
        }
        override suspend fun applyProfile(profile: CameraProfile) {}
        override suspend fun switchLamp(lampType: LampType) {}
        override suspend fun capture(target: File, lampType: LampType) = com.siams.kern_prototype.camera.CaptureOutcome(CameraProfile.defaultFor(lampType), audit = null)
        override fun detachSurface(surface: Surface) = synchronized(surfaceLock) {
            if (failDetach) error("отсоединение не удалось")
            if (current === surface) current = null
            events += "detach:${names[surface]}"
        }
        override fun release() {}

        /** Идёт отрисовка кадра: замок поверхности занят до [gate]. */
        fun drawOnCurrent(gate: CountDownLatch) {
            val started = CountDownLatch(1)
            thread {
                synchronized(surfaceLock) {
                    events += "drawStart"
                    started.countDown()
                    gate.await(5, TimeUnit.SECONDS)
                    events += "drawEnd"
                }
            }
            started.await(2, TimeUnit.SECONDS)
        }
    }

    private val camera = FakeCamera()
    private val releasedSurfaces = Collections.synchronizedList(mutableListOf<String>())

    private val session = main.submit<CameraScreenSession> {
        CameraScreenSession(
            camera, scope, UsbDeviceEvents(context),
            lamp = { LampType.DAYLIGHT }, onMessage = {}, afterSession = {},
            releaseTexture = { events += "textureReleased:${names[it]}" },
            surfaceExecutor = surfaceThread,
            newSurface = { st -> Surface(st).also { names[it] = names[st] } },
        ).also { it.start() }
    }.get()

    private fun texture(name: String) = SurfaceTexture(0).also { names[it] = name }

    private fun onMain(block: () -> Unit) = main.submit(block).get(2, TimeUnit.SECONDS)
    private fun drain() = surfaceThread.submit {}.get(5, TimeUnit.SECONDS)

    /** Задержать очередь переходов поверхности до [gate]. */
    private fun holdQueue(gate: CountDownLatch) = surfaceThread.execute { gate.await(5, TimeUnit.SECONDS) }

    private fun available(t: SurfaceTexture) = onMain { session.onSurfaceAvailable(t) }
    private fun destroy(t: SurfaceTexture): Boolean = main.submit<Boolean> { session.onSurfaceDestroyed(t) }.get(2, TimeUnit.SECONDS)
    private fun currentName() = camera.current?.let { names[it] }

    private fun waitFor(ms: Long = 3000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(10) }
        throw AssertionError("условие не выполнено: $events")
    }

    @After
    fun tearDown() {
        scope.cancel()
        main.shutdown()
        surfaceThread.shutdown()
    }

    @Test
    fun attachingNewSurfaceDuringDrawDoesNotBlockMainThread() {
        val a = texture("A")
        available(a); drain()
        val draw = CountDownLatch(1)
        camera.drawOnCurrent(draw)                     // идёт долгая отрисовка на A
        val b = texture("B")
        available(b)                                   // главный поток не ждёт замок поверхности
        onMain { }                                     // и остаётся свободным
        assertEquals("A", currentName())
        draw.countDown()
        drain()
        assertEquals("после отрисовки превью на B", "B", currentName())
    }

    @Test
    fun delayedQueueEndsWithScreensLastDecision() {
        val a = texture("A")
        available(a); drain()
        onMain { session.onStop() }; drain()          // фон: A отсоединена
        assertNull(camera.current)

        val gate = CountDownLatch(1)
        holdQueue(gate)                                // очередь задержана
        onMain { session.onStart() }                   // возврат: подключение A — в очередь
        destroy(a)                                     // A уничтожена: отсоединение/освобождение — в очередь
        val b = texture("B")
        available(b)                                   // появилась B: подключение — в очередь
        gate.countDown()
        drain()

        assertEquals("в конце подключена B", "B", currentName())
        assertTrue(events.contains("textureReleased:A"))
        assertFalse("A не подключалась поверх B", events.lastIndexOf("session:A") > events.indexOf("session:B"))
        assertEquals("отозванную (уничтожаемую) A камера больше не подключает", 1, events.count { it == "session:A" })
    }

    @Test
    fun destroyingOldTextureDetachesItsOwnSurfaceOnly() {
        val a = texture("A")
        available(a); drain()
        val b = texture("B")
        available(b); drain()                          // новая текстура подключена раньше, чем уничтожена старая
        destroy(a)                                     // запоздалое уничтожение A
        drain()
        assertEquals("B", currentName())
        assertTrue(events.contains("detach:A"))
        assertFalse("поверхность B не отсоединялась", events.contains("detach:B"))
        assertTrue(events.contains("textureReleased:A"))
        assertFalse(events.contains("textureReleased:B"))
    }

    @Test
    fun textureIsReleasedOnlyAfterDrawFinished() {
        val a = texture("A")
        available(a); drain()
        val draw = CountDownLatch(1)
        camera.drawOnCurrent(draw)
        assertFalse("TextureView не освобождает текстуру сам", destroy(a))
        Thread.sleep(150)
        assertFalse(events.contains("textureReleased:A"))
        draw.countDown()
        drain()
        assertTrue(events.indexOf("drawEnd") < events.indexOf("detach:A"))
        assertTrue(events.indexOf("detach:A") < events.indexOf("textureReleased:A"))
    }

    @Test
    fun cleanupRunsEvenIfScreenScopeIsAlreadyCancelled() {
        val a = texture("A")
        available(a); drain()
        scope.cancel()                                 // VM закрыт до обратного вызова
        destroy(a)
        drain()
        assertTrue(events.indexOf("detach:A") < events.indexOf("textureReleased:A"))
    }

    @Test
    fun cancellationDuringDelayedDrawDoesNotReleaseTextureEarly() {
        val a = texture("A")
        available(a); drain()
        val draw = CountDownLatch(1)
        camera.drawOnCurrent(draw)
        destroy(a)
        scope.cancel()                                 // экран закрывается посреди отрисовки
        Thread.sleep(150)
        assertFalse(events.contains("textureReleased:A"))
        draw.countDown()
        drain()
        assertTrue(events.indexOf("drawEnd") < events.indexOf("textureReleased:A"))
    }

    @Test
    fun failedDetachDoesNotReleaseTexture() {
        val a = texture("A")
        available(a); drain()
        camera.failDetach = true
        destroy(a)
        drain()
        assertFalse(events.contains("textureReleased:A"))
    }

    @Test
    fun backgroundDetachesAndForegroundReattachesSurface() {
        val a = texture("A")
        available(a); drain()
        onMain { session.onStop() }; drain()
        assertNull("в фоне камера не рисует на поверхность", camera.current)
        onMain { session.onStart() }; drain()
        assertEquals("A", currentName())
    }
}
