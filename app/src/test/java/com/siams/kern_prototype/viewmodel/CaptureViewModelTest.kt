package com.siams.kern_prototype.viewmodel

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.siams.kern_prototype.TestJpeg
import com.siams.kern_prototype.camera.CameraControllerState
import com.siams.kern_prototype.camera.KernCamera
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
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
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

    /** Камера, у которой снимок ждёт [release]; записывает освещение каждого снимка. */
    private class SlowCamera : KernCamera {
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val switches = mutableListOf<LampType>()
        override val state = MutableStateFlow<CameraControllerState>(CameraControllerState.Ready(android.util.Size(8, 6), true))
        override val activeProfile get() = CameraProfile.defaultFor(LampType.DAYLIGHT)
        override suspend fun open(lampType: LampType) {}
        override suspend fun startSession(surface: android.view.Surface) {}
        override suspend fun applyProfile(profile: CameraProfile) {}
        override suspend fun switchLamp(lampType: LampType) { switches += lampType }
        override suspend fun capture(target: File, lampType: LampType): CameraProfile {
            started.complete(Unit)
            release.await()
            target.writeBytes(TestJpeg.bytes())
            return CameraProfile.defaultFor(lampType)
        }
        override fun release() {}
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(main.asCoroutineDispatcher())
        db = Room.inMemoryDatabaseBuilder(context, KernDatabase::class.java).allowMainThreadQueries().build()
        repo = KernRepository(db, db.drillingDao(), db.tableDao(), db.layerDao(), FileStorage(context), PassportCodec(json), json)
    }

    @After
    fun tearDown() {
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
        onMain { vm = CaptureViewModel(repo, camera, SavedStateHandle(mapOf(Routes.ARG_DRILLING_ID to drillingId))) }

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
}
