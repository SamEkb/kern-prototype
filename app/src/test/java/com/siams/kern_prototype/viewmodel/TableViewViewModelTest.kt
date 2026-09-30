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

    @Test
    fun fieldsShowTableDepthsAsPlainDecimals() {
        vm = newVm()
        waitUntil { vm.depth.value.loaded }
        assertEquals("0.0001", vm.depth.value.upper)
        assertEquals("1500", vm.depth.value.lower)
    }

    @Test
    fun invalidInputStaysVisibleAndIsNotSaved() {
        vm = newVm()
        waitUntil { vm.depth.value.loaded }
        onMain { vm.onDepthChange(Field.UPPER, "12а"); vm.saveDepth() }
        assertEquals("12а", vm.depth.value.upper)
        assertEquals(setOf(Field.UPPER), vm.depth.value.errors.keys)
        onMain { vm.onDepthChange(Field.UPPER, "1600"); vm.saveDepth() }  // кровля ниже подошвы
        assertEquals(setOf(Field.LOWER), vm.depth.value.errors.keys)
        onMain { }
        assertTrue("ничего не записано", layers().all { it.kernUpperDepth == 0.0001 && it.kernLowerDepth == 1500.0 })
        assertEquals(emptyList<Throwable>(), crashes.toList())
    }

    @Test
    fun validInputIsWrittenToAllLayersAndHealsMismatch() {
        // Данные до исправления: у УФ глубин нет.
        val uf = layers().first { it.lampType == LampType.UF }
        runBlocking { db.layerDao().update(uf.copy(kernUpperDepth = null, kernLowerDepth = null)) }
        vm = newVm()
        waitUntil { vm.depthMismatch.value }
        onMain { vm.onDepthChange(Field.UPPER, "1520,5"); vm.onDepthChange(Field.LOWER, "1530"); vm.saveDepth() }
        waitUntil { layers().all { it.kernUpperDepth == 1520.5 && it.kernLowerDepth == 1530.0 } }
        waitUntil { !vm.depthMismatch.value }
        assertEquals(emptyList<Throwable>(), crashes.toList())
    }
}
