package com.siams.kern_prototype.viewmodel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.siams.kern_prototype.camera.CameraControllerState
import com.siams.kern_prototype.camera.KernCamera
import com.siams.kern_prototype.data.settings.CameraProfileStore
import com.siams.kern_prototype.data.settings.ProfileTransfer
import com.siams.kern_prototype.domain.model.CameraProfile
import com.siams.kern_prototype.domain.model.LampType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executors

/** R13/R23: автопроцедуры против конфликтующих действий; черновики профилей по лампам. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraProfilesViewModelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val main = Executors.newSingleThreadExecutor()
    private val store = CameraProfileStore(context, json)
    private lateinit var camera: FakeCamera
    private lateinit var vm: CameraProfilesViewModel

    /** Камера, у которой авто-процедуры ждут [gate]. */
    private class FakeCamera : KernCamera {
        @Volatile var gate: CompletableDeferred<Unit>? = null
        val started = mutableListOf<String>()
        override val state = MutableStateFlow<CameraControllerState>(CameraControllerState.Ready(android.util.Size(8, 6), true))
        override val activeProfile get() = CameraProfile.defaultFor(LampType.DAYLIGHT)
        override suspend fun open(lampType: LampType) {}
        override suspend fun startSession(surface: android.view.Surface) {}
        override suspend fun applyProfile(profile: CameraProfile) {}
        override suspend fun switchLamp(lampType: LampType) {}
        override suspend fun capture(target: File, lampType: LampType) = com.siams.kern_prototype.camera.CaptureOutcome(CameraProfile.defaultFor(lampType), audit = null)
        override suspend fun autoExposure(current: CameraProfile): CameraProfile {
            started += "ae"; gate?.await(); return current.copy(iso = 777)
        }
        override suspend fun autoWhiteBalance(current: CameraProfile): CameraProfile {
            started += "awb"; gate?.await(); return current.copy(wbRedGain = 3f)
        }
        /** R22: непустой — эталон отклоняется с этим отчётом. */
        @Volatile var ffcRejection: com.siams.kern_prototype.camera.FfcQuality.Report? = null
        override suspend fun captureFfcReference(lampType: LampType): com.siams.kern_prototype.camera.FfcReference {
            started += "ffc-${lampType.name}"; gate?.await()
            ffcRejection?.let { throw com.siams.kern_prototype.camera.FfcQuality.RejectedException(it) }
            return com.siams.kern_prototype.camera.FfcReference("/ffc/${lampType.name}.png", ffcReport(lampType, emptyList()))
        }
        override fun release() {}
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(main.asCoroutineDispatcher())
        camera = FakeCamera()
        onMain { vm = CameraProfilesViewModel(store, ProfileTransfer(context, json, store), camera, com.siams.kern_prototype.camera.UsbDeviceEvents(context)).also { vms += it } }
        waitUntil { vm.loaded.value == LampType.DAYLIGHT }
    }

    /** Все созданные VM: их корутины останавливаются до сброса Dispatchers.Main. */
    private val vms = java.util.Collections.synchronizedList(mutableListOf<androidx.lifecycle.ViewModel>())

    @After
    fun tearDown() {
        stopViewModels(vms, main)
        Dispatchers.resetMain()
        main.shutdown()
    }

    private fun onMain(block: () -> Unit) = main.submit(block).get()
    private fun <T> onMainGet(block: () -> T): T = main.submit(block).get()

    private fun waitUntil(ms: Long = 3000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (onMainGet(cond)) return; Thread.sleep(10) }
        throw AssertionError("условие не выполнено за $ms мс")
    }

    private fun edit(iso: Int) = onMain { vm.update(vm.working.value.copy(iso = iso)) }

    @Test
    fun draftsOfBothLampsSurviveSwitching() {
        edit(250)
        onMain { vm.selectLamp(LampType.UF) }
        waitUntil { vm.loaded.value == LampType.UF }
        edit(1600)
        onMain { vm.selectLamp(LampType.DAYLIGHT) }
        waitUntil { vm.loaded.value == LampType.DAYLIGHT }
        assertEquals("черновик дневного не потерян", 250, vm.working.value.iso)
        assertEquals(setOf(LampType.DAYLIGHT, LampType.UF), vm.dirty.value)
        onMain { vm.selectLamp(LampType.UF) }
        waitUntil { vm.loaded.value == LampType.UF }
        assertEquals(1600, vm.working.value.iso)
    }

    @Test
    fun exitWithDraftsAsksAndDiscardKeepsStoreUnchanged() = runBlocking {
        edit(250)
        onMain { vm.requestExit() }
        assertEquals(setOf(LampType.DAYLIGHT), vm.exitPrompt.value)
        assertFalse(vm.exit.value)
        onMain { vm.discardAndExit() }
        assertTrue(vm.exit.value)
        assertEquals(CameraProfile.defaultFor(LampType.DAYLIGHT).iso, store.read(LampType.DAYLIGHT).iso)
    }

    @Test
    fun saveAllOnExitSavesEveryDraft() = runBlocking {
        edit(250)
        onMain { vm.selectLamp(LampType.UF) }
        waitUntil { vm.loaded.value == LampType.UF }
        edit(1600)
        onMain { vm.requestExit(); vm.saveAllAndExit() }
        waitUntil { vm.exit.value }
        assertEquals(250, store.read(LampType.DAYLIGHT).iso)
        assertEquals(1600, store.read(LampType.UF).iso)
    }

    @Test
    fun exitWithoutDraftsIsImmediate() {
        onMain { vm.requestExit() }
        assertTrue(vm.exit.value)
        assertNull(vm.exitPrompt.value)
    }

    @Test
    fun conflictingActionsAreRefusedDuringAutoExposure() = runBlocking {
        camera.gate = CompletableDeferred()
        onMain { vm.runAutoExposure() }
        waitUntil { camera.started.contains("ae") }
        onMain {
            vm.selectLamp(LampType.UF)                        // смена лампы
            vm.update(vm.working.value.copy(iso = 123))       // ползунок
            vm.requestSave()                                  // сохранение
            vm.stageImport("{}")                              // импорт
            vm.requestExit()                                  // выход
            vm.runAutoWhiteBalance()                          // вторая процедура
        }
        assertEquals(LampType.DAYLIGHT, vm.lamp.value)
        assertFalse(vm.showModifyWarning.value)
        assertFalse(vm.showImportWarning.value)
        assertFalse(vm.exit.value)
        assertNull(vm.exitPrompt.value)
        assertEquals(listOf("ae"), camera.started)
        try { vm.buildExportJson(); throw AssertionError("экспорт во время процедуры") } catch (_: IllegalStateException) {}

        camera.gate!!.complete(Unit)
        waitUntil { vm.autoProc.value == AutoProc.NONE }
        assertEquals("результат AE — в черновик дневного", 777, vm.working.value.iso)
        assertEquals(CameraProfile.defaultFor(LampType.DAYLIGHT).iso, store.read(LampType.DAYLIGHT).iso)
    }

    @Test
    fun ffcPathGoesToDraftOfLampItWasTakenFor() {
        onMain { vm.selectLamp(LampType.UF) }
        waitUntil { vm.loaded.value == LampType.UF }
        camera.gate = CompletableDeferred()
        onMain { vm.confirmFfc() }
        waitUntil { camera.started.contains("ffc-UF") }
        onMain { vm.selectLamp(LampType.DAYLIGHT) }           // запрещено во время съёмки эталона
        camera.gate!!.complete(Unit)
        waitUntil { vm.autoProc.value == AutoProc.NONE }
        assertEquals(LampType.UF, vm.lamp.value)
        assertEquals("/ffc/UF.png", vm.working.value.ffcTemplatePath)
        onMain { vm.selectLamp(LampType.DAYLIGHT) }
        waitUntil { vm.loaded.value == LampType.DAYLIGHT }
        assertEquals("эталон УФ не попал в дневной профиль", "", vm.working.value.ffcTemplatePath)
    }


    /** R22: отклонённый эталон не попадает в черновик — остаётся прежний; причина — в сообщении. */
    @Test
    fun rejectedFfcKeepsPreviousTemplateAndExplainsWhy() {
        waitUntil { vm.loaded.value == LampType.DAYLIGHT }
        onMain { vm.update(vm.working.value.copy(ffcTemplatePath = "/ffc/old.png")) }
        camera.ffcRejection = ffcReport(LampType.DAYLIGHT, listOf("пересвет 5.00% пикселей"))
        onMain { vm.confirmFfc() }
        waitUntil { camera.started.contains("ffc-DAYLIGHT") && vm.autoProc.value == AutoProc.NONE }
        assertEquals("/ffc/old.png", vm.working.value.ffcTemplatePath)
        val msg = vm.message.value.orEmpty()
        assertTrue(msg, msg.contains("отклонён") && msg.contains("пересвет") && msg.contains("Прежний эталон сохранён"))
    }

    @Test
    fun acceptedFfcReportsQualityInMessage() {
        waitUntil { vm.loaded.value == LampType.DAYLIGHT }
        onMain { vm.confirmFfc() }
        waitUntil { camera.started.contains("ffc-DAYLIGHT") && vm.autoProc.value == AutoProc.NONE }
        assertEquals("/ffc/DAYLIGHT.png", vm.working.value.ffcTemplatePath)
        assertTrue(vm.message.value.orEmpty(), vm.message.value.orEmpty().contains("медиана"))
    }

    @Test
    fun editsBeforeProfileIsLoadedAreNotAccepted() {
        // Новый VM: профиль лампы ещё грузится — правка от значений по умолчанию недопустима.
        lateinit var fresh: CameraProfilesViewModel
        onMain {
            fresh = CameraProfilesViewModel(store, ProfileTransfer(context, json, store), camera, com.siams.kern_prototype.camera.UsbDeviceEvents(context)).also { vms += it }
            fresh.update(fresh.working.value.copy(iso = 999)) // до загрузки
        }
        waitUntil { fresh.loaded.value == LampType.DAYLIGHT }
        assertEquals(CameraProfile.defaultFor(LampType.DAYLIGHT).iso, fresh.working.value.iso)
        assertTrue(fresh.dirty.value.isEmpty())
        onMain { fresh.update(fresh.working.value.copy(iso = 999)) } // после загрузки — принимается
        assertEquals(setOf(LampType.DAYLIGHT), fresh.dirty.value)
    }

    @Test
    fun proceduresRequireReadyCamera() {
        camera.state.value = CameraControllerState.Opening
        onMain { vm.runAutoExposure(); vm.runAutoWhiteBalance(); vm.confirmFfc() }
        assertTrue("без готовой камеры процедуры не запускаются", camera.started.isEmpty())
        assertEquals(AutoProc.NONE, vm.autoProc.value)
    }
}

/** R22: отчёт проверки эталона для подменной камеры. */
private fun ffcReport(lamp: LampType, problems: List<String>) = com.siams.kern_prototype.camera.FfcQuality.Report(
    lamp = lamp, width = 8, height = 6,
    channels = List(3) { com.siams.kern_prototype.camera.FfcQuality.Channel(128.0, 120, 128, 136, 0.0) },
    anySaturatedFraction = 0.0, correctionGain = listOf(1.1, 1.1, 1.1),
    thresholds = com.siams.kern_prototype.camera.FfcQuality.Thresholds.forLamp(lamp),
    problems = problems,
)
