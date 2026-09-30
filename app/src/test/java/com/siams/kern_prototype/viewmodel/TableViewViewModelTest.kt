package com.siams.kern_prototype.viewmodel

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.siams.kern_prototype.TestJpeg
import com.siams.kern_prototype.data.DepthInput.Field
import com.siams.kern_prototype.data.KernRepository
import com.siams.kern_prototype.data.PassportCodec
import com.siams.kern_prototype.data.db.KernDatabase
import com.siams.kern_prototype.data.storage.FileStorage
import com.siams.kern_prototype.domain.model.CameraProfile
import com.siams.kern_prototype.domain.model.LampType
import com.siams.kern_prototype.ui.nav.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** R15: ввод глубин стола — текст как введён, ошибки у полей, общие глубины всех слоёв. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TableViewViewModelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val crashes = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
    private val main = Executors.newSingleThreadExecutor { r ->
        Thread(r, "test-main").apply { setUncaughtExceptionHandler { _, e -> crashes += e } }
    }
    private lateinit var db: KernDatabase
    private lateinit var repo: KernRepository
    private lateinit var tableId: String
    private lateinit var vm: TableViewViewModel
    private val vms = java.util.Collections.synchronizedList(mutableListOf<androidx.lifecycle.ViewModel>())

    private fun <T> onMain(block: () -> T): T = main.submit<T> { block() }.get(5, TimeUnit.SECONDS)

    private fun waitUntil(ms: Long = 5000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(5) }
        throw AssertionError("условие не выполнено")
    }

    private fun layers() = runBlocking { db.layerDao().listByTable(tableId) }

    @Before
    fun setUp() {
        Dispatchers.setMain(main.asCoroutineDispatcher())
        db = Room.inMemoryDatabaseBuilder(context, KernDatabase::class.java).allowMainThreadQueries().build()
        repo = KernRepository(db, db.drillingDao(), db.tableDao(), db.layerDao(), FileStorage(context), PassportCodec(json), json)
        runBlocking {
            val d = repo.createDrilling("Д", emptyMap())
            val profile = CameraProfile.defaultFor(LampType.DAYLIGHT)
            val (id1, f1) = repo.newLayerFile(d).also { (_, f) -> f.writeBytes(TestJpeg.bytes()) }
            val t = repo.saveCapture(d, null, id1, LampType.DAYLIGHT, f1, profile)
            val (id2, f2) = repo.newLayerFile(d).also { (_, f) -> f.writeBytes(TestJpeg.bytes()) }
            repo.saveCapture(d, t.id, id2, LampType.UF, f2, profile)
            repo.setLayerDepth(t.id, 0.0001, 1500.0)
            tableId = t.id
        }
    }

    private fun newVm(): TableViewViewModel = onMain {
        TableViewViewModel(repo, SavedStateHandle(mapOf(Routes.ARG_TABLE_ID to tableId))).also { v ->
            vms += v
            v.viewModelScope.launch { v.depthMismatch.collect { } } // подписка экрана
        }
    }

    @After
    fun tearDown() {
        stopViewModels(vms, main)
        Dispatchers.resetMain()
        main.shutdown()
        db.close()
    }

    /** «Назад»/«Переснять»: дождаться автосохранения глубин. */
    private fun leave(v: TableViewViewModel) {
        val done = java.util.concurrent.CountDownLatch(1)
        onMain { v.flush { done.countDown() } }
        assertTrue(done.await(5, TimeUnit.SECONDS))
    }

    /** Дождаться автосохранения, оставаясь на экране (как «Переснять»). */
    private fun settle(v: TableViewViewModel) {
        val done = java.util.concurrent.CountDownLatch(1)
        onMain { v.flush(leaving = false) { done.countDown() } }
        assertTrue(done.await(5, TimeUnit.SECONDS))
    }

    private fun depthDraft() = runBlocking { repo.readDraft(KernRepository.DraftKeys.depth(tableId)) }

    @Test
    fun fieldsShowTableDepthsAsPlainDecimals() {
        vm = newVm()
        waitUntil { vm.depth.value.loaded }
        assertEquals("0.0001", vm.depth.value.upper)
        assertEquals("1500", vm.depth.value.lower)
    }

    @Test
    fun invalidInputStaysVisibleIsNotWrittenAndIsRestoredFromDraft() {
        vm = newVm()
        waitUntil { vm.depth.value.loaded }
        onMain { vm.onDepthChange(Field.UPPER, "12а") }
        assertEquals("12а", vm.depth.value.upper)
        settle(vm)
        assertEquals(setOf(Field.UPPER), vm.depth.value.errors.keys)
        assertEquals(TableViewViewModel.DepthStatus.INVALID, vm.depth.value.status)
        onMain { vm.onDepthChange(Field.UPPER, "1600") }   // кровля ниже подошвы
        settle(vm)
        assertEquals(setOf(Field.LOWER), vm.depth.value.errors.keys)
        assertTrue("ничего не записано", layers().all { it.kernUpperDepth == 0.0001 && it.kernLowerDepth == 1500.0 })
        assertEquals("1600", depthDraft()!!["upper"])

        val reopened = newVm()                                  // «Назад» и снова в стол
        waitUntil { reopened.depth.value.loaded }
        assertTrue(reopened.depth.value.restoredDraft)
        assertEquals("1600", reopened.depth.value.upper)
        assertEquals(setOf(Field.LOWER), reopened.depth.value.errors.keys)
        onMain { reopened.discardDepthDraft() }
        settle(reopened)
        assertEquals("0.0001", reopened.depth.value.upper)
        assertEquals(null, depthDraft())
    }

    @Test
    fun validInputIsSavedAutomaticallyToAllLayersBeforeReshoot() {
        vm = newVm()
        waitUntil { vm.depth.value.loaded }
        var layersAtReshoot: List<Pair<Double?, Double?>>? = null
        val done = java.util.concurrent.CountDownLatch(1)
        onMain {
            vm.onDepthChange(Field.UPPER, "1520,5")
            vm.onDepthChange(Field.LOWER, "1530")
            // «Переснять»: переход — только после записи глубин (новый слой берёт глубины стола).
            vm.flush { layersAtReshoot = layers().map { it.kernUpperDepth to it.kernLowerDepth }; done.countDown() }
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(1520.5 to 1530.0, 1520.5 to 1530.0), layersAtReshoot)
        assertEquals(null, depthDraft())
        assertEquals(TableViewViewModel.DepthStatus.SAVED, vm.depth.value.status)
    }

    @Test
    fun mismatchIsHealedOnlyByExplicitAction() {
        // Данные до исправления: у УФ глубин нет.
        val uf = layers().first { it.lampType == LampType.UF }
        runBlocking { db.layerDao().update(uf.copy(kernUpperDepth = null, kernLowerDepth = null)) }
        vm = newVm()
        waitUntil { vm.depthMismatch.value && vm.depth.value.loaded }
        settle(vm)
        assertTrue("открытие стола само ничего не подставляет", layers().any { it.kernUpperDepth == null })
        onMain { vm.applyDepthToAllLayers() }
        settle(vm)
        assertTrue(layers().all { it.kernUpperDepth == 0.0001 && it.kernLowerDepth == 1500.0 })
        waitUntil { !vm.depthMismatch.value }
        assertEquals(emptyList<Throwable>(), crashes.toList())
    }

    /** Ошибка записи глубин: «Переснять» не открывает съёмку со старыми глубинами. */
    @Test
    fun failedDepthWriteBlocksReshoot() {
        vm = newVm()
        waitUntil { vm.depth.value.loaded }
        repo.onBeforeDraftClearForTest = { error("сбой записи") }
        onMain { vm.onDepthChange(Field.UPPER, "1400") }
        var reshoot = false
        onMain { vm.flush(leaving = false) { reshoot = true } }
        waitUntil { !vm.depthBusy.value && vm.message.value != null }
        assertTrue(!reshoot)
        assertTrue(layers().all { it.kernUpperDepth == 0.0001 })
        repo.onBeforeDraftClearForTest = null
        leave(vm)
        assertTrue(layers().all { it.kernUpperDepth == 1400.0 })
    }

    /** Задержанная запись → «Назад» → новый ввод: ввод заблокирован. */
    @Test
    fun depthInputDuringDelayedLeaveIsBlocked() {
        vm = newVm()
        waitUntil { vm.depth.value.loaded }
        val gate = java.util.concurrent.CountDownLatch(1)
        repo.onBeforeDraftClearForTest = { gate.await(5, TimeUnit.SECONDS) }
        onMain { vm.onDepthChange(Field.UPPER, "1400") }
        val left = java.util.concurrent.CountDownLatch(1)
        onMain { vm.flush { left.countDown() } }
        onMain { vm.onDepthChange(Field.UPPER, "1450") }
        assertEquals("1400", vm.depth.value.upper)
        gate.countDown()
        assertTrue(left.await(5, TimeUnit.SECONDS))
        repo.onBeforeDraftClearForTest = null
        assertTrue(layers().all { it.kernUpperDepth == 1400.0 })
    }
}
