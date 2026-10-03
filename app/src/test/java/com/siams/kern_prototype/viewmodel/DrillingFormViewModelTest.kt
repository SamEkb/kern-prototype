package com.siams.kern_prototype.viewmodel

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.siams.kern_prototype.data.KernRepository
import com.siams.kern_prototype.data.KernRepository.DraftKeys
import com.siams.kern_prototype.data.PassportCodec
import com.siams.kern_prototype.data.db.KernDatabase
import com.siams.kern_prototype.data.settings.ServerSettingsStore
import com.siams.kern_prototype.data.storage.FileStorage
import com.siams.kern_prototype.domain.model.SyncStatus
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * R15: форма паспорта — ввод как есть, ошибки у полей, автосохранение правок (без кнопки),
 * черновик неверного/ещё не созданного ввода переживает выход и восстанавливается.
 */
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
    }

    @After
    fun tearDown() {
        stopViewModels(vms, main)
        Dispatchers.resetMain()
        main.shutdown()
        db.close()
        assertEquals("необработанные исключения", emptyList<Throwable>(), crashes.toList())
    }

    /** Открыть форму (как экран) и дождаться загрузки. */
    private fun open(drillingId: String? = null): DrillingFormViewModel {
        val args = drillingId?.let { mapOf(Routes.ARG_DRILLING_ID to it) } ?: emptyMap()
        val vm = onMain { DrillingFormViewModel(repo, ServerSettingsStore(context, com.siams.kern_prototype.data.settings.TestSecrets.cipher()), SavedStateHandle(args)).also { vms += it } }
        waitUntil { vm.loaded.value }
        return vm
    }

    /** «Назад»: дождаться автосохранения. */
    private fun leave(vm: DrillingFormViewModel) {
        val done = CountDownLatch(1)
        onMain { vm.flush { done.countDown() } }
        assertTrue(done.await(5, TimeUnit.SECONDS))
    }

    private fun drilling(id: String) = runBlocking { repo.getDrilling(id) }!!
    private fun draft(key: String) = runBlocking { repo.readDraft(key) }

    @Test
    fun newDrillingInvalidInputBlocksCreationAndIsKeptAsDraft() {
        val vm = open()
        var created: String? = null
        onMain {
            vm.onTitleChange("Д")
            vm.onFieldChange("perforation_number", "12а")
            vm.onFieldChange("core_recovery", "1e5")
            vm.onFieldChange("well_number", "0012")
        }
        assertEquals("недопустимые символы остаются видимыми", "12а", vm.form.value.values["perforation_number"])
        onMain { vm.create { created = it } }
        onMain { }
        assertNull(created)
        assertEquals(setOf("perforation_number", "core_recovery"), vm.errors.value.keys)
        assertTrue("ничего не создано", runBlocking { db.drillingDao().listIdsByStatus(SyncStatus.NOT_SENT) }.isEmpty())
        waitUntil { draft(DraftKeys.passport(null))?.get("perforation_number") == "12а" } // ввод до создания — черновиком, как введён

        onMain { vm.onFieldChange("perforation_number", "12") }
        waitUntil { vm.errors.value.keys == setOf("core_recovery") } // исправленное поле без ошибки, остальные — с ошибкой
        leave(vm)
        assertEquals("12", draft(DraftKeys.passport(null))!!["perforation_number"])
    }

    @Test
    fun newDrillingDraftIsRestoredAfterLeavingAndClearedByCreate() {
        val first = open()
        onMain { first.onTitleChange("Керн-1"); first.onFieldChange("well_number", "0012") }
        leave(first)

        val again = open()
        assertTrue(again.restoredDraft.value)
        assertEquals("Керн-1", again.form.value.title)
        assertEquals("0012", again.form.value.values["well_number"])
        val done = CountDownLatch(1)
        var id: String? = null
        onMain { again.create { id = it; done.countDown() } }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals("0012", repo.passportStrings(drilling(id!!).passportJson)["well_number"])
        assertNull("черновик нового удалён после создания", draft(DraftKeys.passport(null)))
    }

    @Test
    fun editIsSavedAutomaticallyWithoutButton() {
        val id = runBlocking { repo.createDrilling("Д", mapOf("well_number" to "1")) }
        val vm = open(id)
        onMain { vm.onFieldChange("well_number", "0012"); vm.onTitleChange("Д-2") }
        leave(vm)
        val d = drilling(id)
        assertEquals("0012", repo.passportStrings(d.passportJson)["well_number"])
        assertEquals("Д-2", d.title)
        assertNull(draft(DraftKeys.passport(id)))
        assertEquals(DrillingFormViewModel.SaveStatus.SAVED, vm.status.value)
    }

    @Test
    fun invalidEditIsNotWrittenButRestoredFromDraftAndCanBeDiscarded() {
        val id = runBlocking { repo.createDrilling("Д", mapOf("perforation_number" to "7")) }
        val before = drilling(id)
        val vm = open(id)
        onMain { vm.onFieldChange("perforation_number", "12а") }
        leave(vm)
        assertEquals("паспорт не изменён", before.passportJson, drilling(id).passportJson)
        assertEquals(before.editRevision, drilling(id).editRevision)
        assertEquals(DrillingFormViewModel.SaveStatus.INVALID, vm.status.value)

        val reopened = open(id)                         // «Назад» и снова в форму
        assertTrue(reopened.restoredDraft.value)
        assertEquals("12а", reopened.form.value.values["perforation_number"])
        assertEquals(setOf("perforation_number"), reopened.errors.value.keys)
        onMain { reopened.discardDraft() }
        leave(reopened)
        assertEquals("7", reopened.form.value.values["perforation_number"])
        assertNull(draft(DraftKeys.passport(id)))
        assertEquals(before.passportJson, drilling(id).passportJson)
        assertEquals("совпадающие значения не переписываются", before.editRevision, drilling(id).editRevision)
    }

    @Test
    fun blankTitleIsAnErrorAndDoesNotReplaceTitle() {
        val id = runBlocking { repo.createDrilling("Д", emptyMap()) }
        val vm = open(id)
        onMain { vm.onTitleChange("  ") }
        leave(vm)
        assertEquals(setOf(DrillingFormViewModel.TITLE_KEY), vm.errors.value.keys)
        assertEquals("Д", drilling(id).title)
    }

    /** Открытие старой записи без правок ничего не пишет (нет ложной пометки «изменено»). */
    @Test
    fun reopeningLegacyExponentRecordWithoutChangesWritesNothing() {
        val id = runBlocking { repo.createDrilling("Д", emptyMap()) }
        runBlocking {
            db.drillingDao().updatePassport(id, null, """{"core_recovery":1.0E-4,"depth_correction":1.0E20,"well_number":"0012"}""")
        }
        val before = drilling(id)
        val vm = open(id)
        assertEquals("0.0001", vm.form.value.values["core_recovery"])
        assertEquals("100000000000000000000", vm.form.value.values["depth_correction"])
        assertTrue(vm.errors.value.isEmpty())
        leave(vm)
        assertEquals(before, drilling(id))
        assertFalse(vm.restoredDraft.value)
    }

    /** Ошибка записи: экран не закрывается, ввод не теряется; повторное нажатие сохраняет. */
    @Test
    fun failedWriteKeepsScreenOpenAndRetrySaves() {
        val id = runBlocking { repo.createDrilling("Д", mapOf("well_number" to "1")) }
        val vm = open(id)
        repo.onBeforeDraftClearForTest = { error("сбой записи") }
        onMain { vm.onFieldChange("well_number", "2") }
        var left = false
        onMain { vm.flush { left = true } }
        waitUntil { !vm.busy.value && vm.message.value != null }
        assertFalse("экран не закрыт", left)
        assertTrue(vm.message.value!!, vm.message.value!!.contains("Не удалось сохранить"))
        assertEquals("1", repo.passportStrings(drilling(id).passportJson)["well_number"])
        assertEquals("ввод на месте", "2", vm.form.value.values["well_number"])

        repo.onBeforeDraftClearForTest = null
        leave(vm)
        assertEquals("2", repo.passportStrings(drilling(id).passportJson)["well_number"])
    }

    /** Таймаут записи: экран не закрывается; после завершения записи выход проходит. */
    @Test
    fun slowWriteIsTimeoutAndKeepsScreenOpen() {
        val id = runBlocking { repo.createDrilling("Д", emptyMap()) }
        val vm = open(id)
        vm.flushTimeoutMs = 200
        val gate = CountDownLatch(1)
        repo.onBeforeDraftClearForTest = { gate.await(5, TimeUnit.SECONDS) }
        onMain { vm.onFieldChange("well_number", "0012") }
        var left = false
        onMain { vm.flush { left = true } }
        waitUntil { !vm.busy.value && vm.message.value != null }
        assertFalse(left)
        assertTrue(vm.message.value!!, vm.message.value!!.contains("не завершено"))
        gate.countDown()
        repo.onBeforeDraftClearForTest = null
        vm.flushTimeoutMs = 5_000
        leave(vm)
        assertEquals("0012", repo.passportStrings(drilling(id).passportJson)["well_number"])
    }

    /** Задержанная запись → выход → новый ввод: ввод заблокирован, выход после записи последнего. */
    @Test
    fun inputDuringDelayedLeaveIsBlocked() {
        val id = runBlocking { repo.createDrilling("Д", emptyMap()) }
        val vm = open(id)
        val gate = CountDownLatch(1)
        repo.onBeforeDraftClearForTest = { gate.await(5, TimeUnit.SECONDS) }
        onMain { vm.onFieldChange("well_number", "1") }
        val left = CountDownLatch(1)
        onMain { vm.flush { left.countDown() } }
        assertTrue(vm.busy.value)
        onMain { vm.onFieldChange("well_number", "2"); vm.onTitleChange("другое") }
        assertEquals("ввод во время выхода не принимается", "1", vm.form.value.values["well_number"])
        assertEquals("Д", vm.form.value.title)
        gate.countDown()
        assertTrue(left.await(5, TimeUnit.SECONDS))
        repo.onBeforeDraftClearForTest = null
        assertEquals("1", repo.passportStrings(drilling(id).passportJson)["well_number"])
    }

    /** Задержанное создание → новый ввод: создаётся проверенная форма, ввод заблокирован. */
    @Test
    fun inputDuringDelayedCreateIsBlocked() {
        val vm = open()
        onMain { vm.onTitleChange("Керн"); vm.onFieldChange("well_number", "1") }
        val gate = CountDownLatch(1)
        repo.onBeforeDraftClearForTest = { gate.await(5, TimeUnit.SECONDS) }
        val created = CountDownLatch(1)
        var id: String? = null
        onMain { vm.create { id = it; created.countDown() } }
        waitUntil { vm.busy.value }
        onMain { vm.onFieldChange("well_number", "2") }
        assertEquals("1", vm.form.value.values["well_number"])
        gate.countDown()
        assertTrue(created.await(5, TimeUnit.SECONDS))
        repo.onBeforeDraftClearForTest = null
        assertEquals("1", repo.passportStrings(drilling(id!!).passportJson)["well_number"])
        assertNull(draft(DraftKeys.passport(null)))
    }

    /**
     * Задержанная запись черновика → «Создать» → таймаут: долбление не создано, перехода нет;
     * после завершения записи повторное «Создать» создаёт, черновика не остаётся.
     */
    @Test
    fun createWaitsForDraftWriteAndRefusesOnTimeout() {
        val vm = open()
        vm.flushTimeoutMs = 200
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        repo.onDraftWriteForTest = { gate.await() } // запись черновика «висит», не занимая поток
        onMain { vm.onTitleChange("Керн"); vm.onFieldChange("well_number", "0012") }
        var created: String? = null
        onMain { vm.create { created = it } }
        waitUntil { !vm.busy.value && vm.message.value != null }
        assertNull("перехода нет", created)
        assertTrue(vm.message.value!!, vm.message.value!!.contains("не завершено"))
        assertTrue("долбление не создано", runBlocking { db.drillingDao().listIdsByStatus(SyncStatus.NOT_SENT) }.isEmpty())

        gate.complete(Unit)                                // запись черновика завершилась
        repo.onDraftWriteForTest = null
        vm.flushTimeoutMs = 5_000
        val done = CountDownLatch(1)
        onMain { vm.consumeMessage(); vm.create { created = it; done.countDown() } }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals("0012", repo.passportStrings(drilling(created!!).passportJson)["well_number"])
        Thread.sleep(100)                                  // запоздалых записей черновика нет
        assertNull("черновика не осталось", draft(DraftKeys.passport(null)))
    }

    /** Ошибка записи черновика → «Создать»: не создаётся, форма открыта; после устранения — создаётся. */
    @Test
    fun createRefusesWhenDraftWriteFailed() {
        val vm = open()
        repo.onDraftWriteForTest = { error("диск занят") }
        onMain { vm.onTitleChange("Керн") }
        var created: String? = null
        onMain { vm.create { created = it } }
        waitUntil { !vm.busy.value && vm.message.value?.contains("Не удалось сохранить ввод") == true }
        assertNull(created)
        assertTrue(runBlocking { db.drillingDao().listIdsByStatus(SyncStatus.NOT_SENT) }.isEmpty())
        assertEquals("ввод на месте", "Керн", vm.form.value.title)

        repo.onDraftWriteForTest = null
        val done = CountDownLatch(1)
        onMain { vm.create { created = it; done.countDown() } }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals("Керн", drilling(created!!).title)
        assertNull(draft(DraftKeys.passport(null)))
    }
}
