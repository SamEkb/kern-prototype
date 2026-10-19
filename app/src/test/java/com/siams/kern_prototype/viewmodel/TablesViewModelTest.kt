package com.siams.kern_prototype.viewmodel

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.siams.kern_prototype.TestJpeg
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Удаление столов из списка и из экрана стола: отказ — сообщением, без падения и без потери выбора. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TablesViewModelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val crashes = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
    private val main = Executors.newSingleThreadExecutor { r ->
        Thread(r, "test-main").apply { setUncaughtExceptionHandler { _, e -> crashes += e } }
    }
    private lateinit var db: KernDatabase
    private lateinit var repo: KernRepository
    private lateinit var drillingId: String
    private val tableIds = mutableListOf<String>()
    private val photos = mutableListOf<File>()
    private val vms = java.util.Collections.synchronizedList(mutableListOf<androidx.lifecycle.ViewModel>())

    private fun <T> onMain(block: () -> T): T = main.submit<T> { block() }.get(5, TimeUnit.SECONDS)

    private fun waitUntil(ms: Long = 5000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(5) }
        throw AssertionError("условие не выполнено")
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(main.asCoroutineDispatcher())
        db = Room.inMemoryDatabaseBuilder(context, KernDatabase::class.java).allowMainThreadQueries().build()
        repo = KernRepository(db, db.drillingDao(), db.tableDao(), db.layerDao(), FileStorage(context), PassportCodec(json), json)
        runBlocking {
            drillingId = repo.createDrilling("Д", emptyMap())
            repeat(2) {
                val (id, f) = repo.newLayerFile(drillingId)
                f.writeBytes(TestJpeg.bytes())
                tableIds += repo.saveCapture(drillingId, null, id, LampType.DAYLIGHT, f, CameraProfile.defaultFor(LampType.DAYLIGHT)).id
                photos += f
            }
        }
    }

    @After
    fun tearDown() {
        stopViewModels(vms, main)
        Dispatchers.resetMain()
        main.shutdown()
        db.close()
    }

    private fun newVm(): TablesViewModel = onMain {
        TablesViewModel(repo, SavedStateHandle(mapOf(Routes.ARG_DRILLING_ID to drillingId))).also { vms += it }
    }

    /** Ошибка БД при записи (не R05): SQLite отказывает в DELETE столов. */
    private fun forbidTableDelete() {
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER test_no_delete BEFORE DELETE ON core_table BEGIN SELECT RAISE(ABORT, 'запрет записи'); END",
        )
    }

    private fun tablesLeft() = runBlocking { tableIds.count { db.tableDao().getById(it) != null } }

    @Test
    fun deleteTablesSuccessCallsOnDeleted() {
        val vm = newVm()
        val done = AtomicInteger()
        onMain { vm.deleteTables(tableIds.toSet()) { done.incrementAndGet() } }
        waitUntil { done.get() == 1 }
        assertEquals(0, tablesLeft())
        assertNull(vm.message.value)
        assertTrue(crashes.isEmpty())
    }

    @Test
    fun databaseErrorBecomesMessageKeepsSelectionAndData() {
        forbidTableDelete()
        val vm = newVm()
        val done = AtomicInteger()
        onMain { vm.deleteTables(tableIds.toSet()) { done.incrementAndGet() } }
        waitUntil { vm.message.value != null }
        assertTrue(vm.message.value!!, vm.message.value!!.contains("ничего не удалено"))
        assertEquals("выбор не снимается — onDeleted не вызван", 0, done.get())
        assertEquals("столы целы", 2, tablesLeft())
        assertTrue("файлы целы", photos.all { it.exists() })
        assertTrue("приложение не падает: ${crashes.firstOrNull()}", crashes.isEmpty())
    }

    @Test
    fun sendingRefusalKeepsRepositoryMessage() {
        runBlocking {
            db.drillingDao().update(db.drillingDao().getById(drillingId)!!.copy(syncStatus = com.siams.kern_prototype.domain.model.SyncStatus.SENDING))
        }
        val vm = newVm()
        val done = AtomicInteger()
        onMain { vm.deleteTables(tableIds.toSet()) { done.incrementAndGet() } }
        waitUntil { vm.message.value != null }
        assertTrue(vm.message.value!!, vm.message.value!!.contains("отправляется"))
        assertEquals(0, done.get())
        assertEquals(2, tablesLeft())
    }

    @Test
    fun tableScreenDeleteDatabaseErrorBecomesMessage() {
        forbidTableDelete()
        val vm = onMain {
            TableViewViewModel(repo, SavedStateHandle(mapOf(Routes.ARG_TABLE_ID to tableIds[0]))).also { vms += it }
        }
        onMain { vm.deleteTable() }
        waitUntil { vm.message.value != null }
        assertTrue(vm.message.value!!, vm.message.value!!.contains("ничего не удалено"))
        assertFalse("экран стола не закрывается", vm.closed.value)
        assertNotNull(runBlocking { db.tableDao().getById(tableIds[0]) })
        assertTrue("приложение не падает: ${crashes.firstOrNull()}", crashes.isEmpty())
    }
}
