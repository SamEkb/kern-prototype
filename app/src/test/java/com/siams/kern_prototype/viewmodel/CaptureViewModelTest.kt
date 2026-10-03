package com.siams.kern_prototype.viewmodel

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.siams.kern_prototype.TestJpeg
import com.siams.kern_prototype.camera.CameraControllerState
import com.siams.kern_prototype.camera.KernCamera
import com.siams.kern_prototype.camera.UsbDeviceEvents
import com.siams.kern_prototype.data.KernRepository
import com.siams.kern_prototype.data.PassportCodec
import com.siams.kern_prototype.data.db.KernDatabase
import com.siams.kern_prototype.data.storage.FileStorage
import com.siams.kern_prototype.domain.model.CameraProfile
import com.siams.kern_prototype.domain.model.LampType
import com.siams.kern_prototype.ui.nav.Routes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.Executors

/** R10/FIXES §7: освещение и стол снимка фиксируются в момент нажатия; конфликтующие действия блокируются. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CaptureViewModelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val main = Executors.newSingleThreadExecutor()
    private lateinit var db: KernDatabase
    private lateinit var repo: KernRepository

    /** USB-события, которые тест посылает сам. */
    private class FakeUsbEvents(context: Context) : UsbDeviceEvents(context) {
        val events = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        override val attached: kotlinx.coroutines.flow.Flow<Unit> get() = events
    }

    /** Камера, у которой снимок ждёт [release]; записывает освещение каждого снимка. */
    private class SlowCamera : KernCamera {
        @Volatile var openFailures = 0
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val switches = mutableListOf<LampType>()
        override val state = MutableStateFlow<CameraControllerState>(CameraControllerState.Idle)
        override val activeProfile get() = CameraProfile.defaultFor(LampType.DAYLIGHT)
        override suspend fun open(lampType: LampType) {
            calls += "open"
            if (openFailures > 0) {
                openFailures--
                state.value = CameraControllerState.Error("нет камеры")
                error("USB-камера не найдена")
            }
            state.value = CameraControllerState.Ready(android.util.Size(8, 6), true)
        }
        override suspend fun recover() {
            calls += "recover"
            state.value = CameraControllerState.Ready(android.util.Size(8, 6), true)
        }
        override fun detachSurface(surface: android.view.Surface) { calls += "detach" }
        override suspend fun startSession(surface: android.view.Surface) { calls += "session" }
        override suspend fun applyProfile(profile: CameraProfile) {}
        override suspend fun switchLamp(lampType: LampType) { switches += lampType }
        override suspend fun capture(target: File, lampType: LampType): com.siams.kern_prototype.camera.CaptureOutcome {
            started.complete(Unit)
            release.await()
            target.writeBytes(TestJpeg.bytes())
            return com.siams.kern_prototype.camera.CaptureOutcome(CameraProfile.defaultFor(lampType), audit = null)
        }
        override fun release() {}
    }

    private val usb by lazy { FakeUsbEvents(context) }

    private fun newVm(camera: SlowCamera): CaptureViewModel {
        lateinit var vm: CaptureViewModel
        val drillingId = runBlocking { repo.createDrilling("Д", emptyMap()) }
        onMain { vm = CaptureViewModel(repo, camera, SavedStateHandle(mapOf(Routes.ARG_DRILLING_ID to drillingId)), usb).also { vms += it } }
        return vm
    }

    private fun waitUntil(ms: Long = 3000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (main.submit<Boolean> { cond() }.get()) return; Thread.sleep(10) }
        throw AssertionError("условие не выполнено за $ms мс")
    }

    @Test
    fun failedConnectionCanBeRetriedFromScreen() {
        val camera = SlowCamera().apply { openFailures = 1 }
        val vm = newVm(camera)
        onMain { vm.start() }
        waitUntil { vm.cameraState.value is CameraControllerState.Error && vm.message.value != null }
        onMain { vm.start() }                        // повторный вход не блокируется...
        waitUntil { vm.cameraState.value is CameraControllerState.Ready }
        assertEquals(listOf("open", "open"), camera.calls.filter { it == "open" })
        onMain { vm.reconnect() }                    // ...а после успеха «Переподключить» = recover
        waitUntil { camera.calls.contains("recover") }
    }

    @Test
    fun usbAttachRecoversOnlyCameraInError() {
        val camera = SlowCamera()
        val vm = newVm(camera)
        onMain { vm.start() }
        waitUntil { vm.cameraState.value is CameraControllerState.Ready }
        usb.events.tryEmit(Unit)                     // камера исправна — не трогаем
        Thread.sleep(200)
        assertFalse(camera.calls.contains("recover"))
        camera.state.value = CameraControllerState.Error("Камера Touptek отключена", canRetry = true)
        usb.events.tryEmit(Unit)                     // подключили снова
        waitUntil { camera.calls.contains("recover") }
        waitUntil { vm.cameraState.value is CameraControllerState.Ready }
    }

    @Test
    fun destroyedSurfaceIsDetachedAndTextureKeptForVm() {
        val camera = SlowCamera()
        val vm = newVm(camera)
        onMain { vm.start() }
        waitUntil { vm.cameraState.value is CameraControllerState.Ready }
        val texture = android.graphics.SurfaceTexture(0)
        onMain { vm.onSurfaceAvailable(texture) }
        waitUntil { camera.calls.contains("session") }
        val released = main.submit<Boolean> { vm.onSurfaceDestroyed(texture) }.get()
        assertFalse("текстуру освобождает VM после отключения камеры от неё", released)
        waitUntil { camera.calls.contains("detach") }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(main.asCoroutineDispatcher())
        db = Room.inMemoryDatabaseBuilder(context, KernDatabase::class.java).allowMainThreadQueries().build()
        repo = KernRepository(db, db.drillingDao(), db.tableDao(), db.layerDao(), FileStorage(context), PassportCodec(json), json)
    }

    /** Все созданные VM: их корутины останавливаются до сброса Dispatchers.Main. */
    private val vms = java.util.Collections.synchronizedList(mutableListOf<androidx.lifecycle.ViewModel>())

    @After
    fun tearDown() {
        stopViewModels(vms, main)
        Dispatchers.resetMain()
        main.shutdown()
        db.close()
    }

    private fun onMain(block: () -> Unit) = main.submit(block).get()

    @Test
    fun lampSwitchAndNewTableAreIgnoredDuringCapture() = runBlocking {
        val drillingId = repo.createDrilling("Д", emptyMap())
        val camera = SlowCamera()
        lateinit var vm: CaptureViewModel
        onMain { vm = CaptureViewModel(repo, camera, SavedStateHandle(mapOf(Routes.ARG_DRILLING_ID to drillingId)), usb).also { vms += it } }

        onMain { vm.capture() }
        withTimeout(3_000) { camera.started.await() }
        onMain {
            vm.switchLamp(LampType.UF) // во время снимка — игнорируется
            vm.newTable()
        }
        assertEquals(LampType.DAYLIGHT, vm.lamp.value)

        camera.release.complete(Unit)
        withTimeout(5_000) { while (vm.capturing.value) kotlinx.coroutines.delay(20) }
        val content = repo.getDrillingContent(drillingId)!!
        val layer = content.tables.single().layers.single()
        assertEquals("кадр сохранён с освещением момента нажатия", LampType.DAYLIGHT, layer.lampType)
        assertEquals(emptyList<LampType>(), camera.switches)
    }

    /** FIXES §6: «снят» — по слоям стола из Room: после фиксации слоя, а не после нажатия спуска. */
    @Test
    fun capturedMarkAppearsOnlyAfterLayerIsSaved() = runBlocking {
        val camera = SlowCamera()
        val vm = newVm(camera)
        onMain { vm.capture() }
        withTimeout(3_000) { camera.started.await() }
        Thread.sleep(100)
        assertEquals("снимок идёт — отметки нет", emptySet<LampType>(), vm.capturedLamps.value)

        camera.release.complete(Unit)
        waitUntil { vm.capturedLamps.value == setOf(LampType.DAYLIGHT) }
        val layers = repo.getDrillingContent(vm.drillingId)!!.tables.single().layers
        assertEquals("отметка соответствует записанному слою", listOf(LampType.DAYLIGHT), layers.map { it.lampType })
    }
}
