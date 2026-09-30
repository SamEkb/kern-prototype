package com.siams.kern_prototype.viewmodel

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.siams.kern_prototype.data.KernRepository
import com.siams.kern_prototype.data.PassportCodec
import com.siams.kern_prototype.data.db.KernDatabase
import com.siams.kern_prototype.data.settings.ServerSettingsStore
import com.siams.kern_prototype.data.storage.FileStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** R15: форма паспорта не сохраняет неверные поля молча — показывает ошибки. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DrillingFormViewModelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    /** Необработанные исключения главного потока (в приложении — падение). */
    private val crashes = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
    private val main = Executors.newSingleThreadExecutor { r ->
        Thread(r, "test-main").apply { setUncaughtExceptionHandler { _, e -> crashes += e } }
    }
    private lateinit var db: KernDatabase
    private lateinit var repo: KernRepository
    private lateinit var vm: DrillingFormViewModel
    private val vms = java.util.Collections.synchronizedList(mutableListOf<androidx.lifecycle.ViewModel>())

    private fun <T> onMain(block: () -> T): T = main.submit<T> { block() }.get(5, TimeUnit.SECONDS)

    @Before
    fun setUp() {
        Dispatchers.setMain(main.asCoroutineDispatcher())
        db = Room.inMemoryDatabaseBuilder(context, KernDatabase::class.java).allowMainThreadQueries().build()
        repo = KernRepository(db, db.drillingDao(), db.tableDao(), db.layerDao(), FileStorage(context), PassportCodec(json), json)
        vm = onMain { DrillingFormViewModel(repo, ServerSettingsStore(context), SavedStateHandle()).also { vms += it } }
    }

    @After
    fun tearDown() {
        stopViewModels(vms, main)
        Dispatchers.resetMain()
        main.shutdown()
        db.close()
    }

    @Test
    fun invalidFieldsBlockSavingAndAreReported() {
        var saved: String? = null
        // Путь ввода формы: текст поля как введён (в т.ч. вставка) — без фильтрации символов.
        onMain {
            vm.onFieldChange("perforation_number", "12а")
            vm.onFieldChange("core_recovery", "1e5")
            vm.onFieldChange("well_number", "0012")
        }
        assertEquals("недопустимые символы остаются видимыми", "12а", vm.values.value["perforation_number"])
        assertEquals("1e5", vm.values.value["core_recovery"])
        onMain { vm.save("Д") { saved = it } }
        Thread.sleep(100)
        assertEquals(null, saved)
        assertEquals(setOf("perforation_number", "core_recovery"), vm.errors.value.keys)
        onMain { }
        assertEquals("сохранение не запускалось (без падения)", emptyList<Throwable>(), crashes.toList())
        assertTrue("ничего не сохранено", runBlocking { db.drillingDao().listIdsByStatus(com.siams.kern_prototype.domain.model.SyncStatus.NOT_SENT) }.isEmpty())

        onMain { vm.onFieldChange("perforation_number", "12") }
        assertEquals("правка поля снимает только его ошибку", setOf("core_recovery"), vm.errors.value.keys)
    }

    @Test
    fun validFieldsAreSavedAsEntered() {
        val done = CountDownLatch(1)
        var saved: String? = null
        onMain {
            vm.onFieldChange("perforation_number", "12")
            vm.onFieldChange("well_number", "0012")
            vm.save("Д") { saved = it; done.countDown() }
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        val d = runBlocking { repo.getDrilling(saved!!) }!!
        assertEquals("0012", repo.passportStrings(d.passportJson)["well_number"])
        assertEquals("12", repo.passportStrings(d.passportJson)["perforation_number"])
        assertEquals(emptyList<Throwable>(), crashes.toList())
    }

    /** R15: сохранённый паспорт открывается для правки без ошибок и сохраняется теми же числами. */
    @Test
    fun reopenedPassportWithSmallAndExponentNumbersSavesUnchanged() {
        val id = runBlocking { repo.createDrilling("Д", emptyMap()) }
        // Старая запись: числа с экспонентой (так kotlinx пишет малые/большие Double).
        runBlocking {
            db.drillingDao().updatePassport(id, null, """{"core_recovery":1.0E-4,"depth_correction":1.0E20,"well_number":"0012"}""")
        }
        lateinit var edit: DrillingFormViewModel
        onMain { edit = DrillingFormViewModel(repo, ServerSettingsStore(context), SavedStateHandle(mapOf(com.siams.kern_prototype.ui.nav.Routes.ARG_DRILLING_ID to id))).also { vms += it } }
        val end = System.currentTimeMillis() + 5_000
        while (!edit.initial.value.loaded && System.currentTimeMillis() < end) Thread.sleep(5)
        assertEquals("0.0001", edit.values.value["core_recovery"])
        assertEquals("0012", edit.values.value["well_number"])
        val done = CountDownLatch(1)
        onMain { edit.save("Д") { done.countDown() } }
        assertTrue("без правок сохраняется без ошибок: ${edit.errors.value}", done.await(5, TimeUnit.SECONDS))
        val stored = repo.passportStrings(runBlocking { repo.getDrilling(id) }!!.passportJson)
        assertEquals("0.0001", stored["core_recovery"])
        assertEquals("100000000000000000000", stored["depth_correction"])
    }
}
