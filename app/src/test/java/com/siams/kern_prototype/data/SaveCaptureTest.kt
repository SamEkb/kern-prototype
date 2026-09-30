package com.siams.kern_prototype.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.siams.kern_prototype.data.db.KernDatabase
import com.siams.kern_prototype.data.storage.FileStorage
import com.siams.kern_prototype.data.storage.writeAtomically
import com.siams.kern_prototype.domain.model.CameraProfile
import com.siams.kern_prototype.domain.model.LampType
import com.siams.kern_prototype.TestJpeg
import com.siams.kern_prototype.camera.MemoryReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** R08: новый кадр фиксируется до удаления прежнего; неудачный кадр ничего не портит. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SaveCaptureTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private lateinit var db: KernDatabase
    private lateinit var repo: KernRepository
    private val profile = CameraProfile.defaultFor(LampType.DAYLIGHT)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, KernDatabase::class.java).allowMainThreadQueries().build()
        repo = KernRepository(
            db, db.drillingDao(), db.tableDao(), db.layerDao(), FileStorage(context), PassportCodec(json), json,
        )
    }

    @After
    fun tearDown() = db.close()

    private fun shot(drillingId: String, bytes: ByteArray = TestJpeg.bytes()): Pair<String, File> =
        repo.newLayerFile(drillingId).also { (_, f) -> f.writeBytes(bytes) }

    @Test
    fun reshootSwitchesRecordThenDeletesOldFile() = runBlocking {
        val d = repo.createDrilling("Д", emptyMap())
        val (id1, f1) = shot(d)
        val table = repo.saveCapture(d, null, id1, LampType.DAYLIGHT, f1, profile)
        repo.setLayerDepth(table.id, 5.0, 6.0)

        val (id2, f2) = shot(d)
        val same = repo.saveCapture(d, table.id, id2, LampType.DAYLIGHT, f2, profile)

        assertEquals(table.id, same.id)
        val layer = db.layerDao().getByTableAndLamp(table.id, "DAYLIGHT")!!
        assertEquals(id1, layer.id)                  // запись та же, файл новый
        assertEquals(f2.absolutePath, layer.filePath)
        assertEquals(5.0, layer.kernUpperDepth!!, 0.0) // глубины стола сохраняются
        assertFalse(f1.exists())                     // старый удалён после commit
        assertTrue(f2.exists())
    }

    @Test
    fun incompleteJpegKeepsPreviousLayerAndCreatesNoTable() = runBlocking {
        val d = repo.createDrilling("Д", emptyMap())
        val (id1, f1) = shot(d)
        val table = repo.saveCapture(d, null, id1, LampType.DAYLIGHT, f1, profile)

        val (id2, f2) = shot(d, TestJpeg.bytes().copyOf(5)) // обрезан: нет EOI
        try { repo.saveCapture(d, table.id, id2, LampType.DAYLIGHT, f2, profile); fail() } catch (_: IllegalStateException) {}
        assertEquals(f1.absolutePath, db.layerDao().getByTableAndLamp(table.id, "DAYLIGHT")!!.filePath)
        assertTrue(f1.exists())

        // Первый кадр нового стола не удался → пустой стол не создаётся.
        val (id3, f3) = shot(d, ByteArray(0))
        try { repo.saveCapture(d, null, id3, LampType.DAYLIGHT, f3, profile); fail() } catch (_: IllegalStateException) {}
        assertEquals(1, db.tableDao().countByDrilling(d))
    }

    /** Инвариант архива: каждый слой ссылается на существующий файл. */
    private suspend fun assertNoDanglingLayers(drillingId: String) {
        db.drillingDao().getWithContent(drillingId)!!.tables.flatMap { it.layers }.forEach {
            assertTrue("слой ${it.id} ссылается на удалённый файл", File(it.filePath).exists())
        }
    }

    @Test
    fun cancellationAtCommitBoundaryNeverDeletesReferencedFile() = runBlocking {
        val d = repo.createDrilling("Д", emptyMap())
        val (id1, f1) = shot(d)
        val table = repo.saveCapture(d, null, id1, LampType.DAYLIGHT, f1, profile)

        // Отмена приходит в самом конце тела транзакции — после неё commit может состояться,
        // но вызывающий код всё равно получит CancellationException.
        val (id2, f2) = shot(d)
        lateinit var job: Job
        repo.onTransactionBodyEndForTest = { job.cancel() }
        job = launch(start = CoroutineStart.LAZY) {
            repo.saveCapture(d, table.id, id2, LampType.DAYLIGHT, f2, profile)
        }
        job.join()
        repo.onTransactionBodyEndForTest = null
        assertTrue(job.isCancelled)

        assertNoDanglingLayers(d)
        // Room фиксирует транзакцию, а вызывающий получает отмену — именно этот случай проверяем.
        val layer = db.layerDao().getByTableAndLamp(table.id, "DAYLIGHT")!!
        assertEquals("commit должен был состояться", f2.absolutePath, layer.filePath)
        assertTrue(f2.exists())
        assertFalse("зафиксирован новый — старый должен быть убран", f1.exists())
    }

    @Test
    fun failureInsideTransactionRollsBackAndRemovesOnlyNewFiles() = runBlocking {
        val d = repo.createDrilling("Д", emptyMap())
        val (id1, f1) = shot(d)
        val table = repo.saveCapture(d, null, id1, LampType.DAYLIGHT, f1, profile)

        val (id2, f2) = shot(d)
        repo.onTransactionBodyEndForTest = { throw IllegalStateException("сбой до commit") }
        try { repo.saveCapture(d, table.id, id2, LampType.DAYLIGHT, f2, profile); fail() } catch (_: IllegalStateException) {}
        repo.onTransactionBodyEndForTest = null

        assertEquals(f1.absolutePath, db.layerDao().getByTableAndLamp(table.id, "DAYLIGHT")!!.filePath)
        assertTrue(f1.exists())
        assertFalse(f2.exists())
        assertNoDanglingLayers(d)
    }

    @Test
    fun discardUncommittedCaptureKeepsReferencedFile() = runBlocking {
        val d = repo.createDrilling("Д", emptyMap())
        val (id1, f1) = shot(d)
        repo.saveCapture(d, null, id1, LampType.DAYLIGHT, f1, profile)
        // Отмена в VM после фиксации: файл уже в БД → удалять нельзя.
        repo.discardUncommittedCapture(f1)
        assertTrue(f1.exists())
        // Файл, не попавший в БД, — убирается.
        val (_, f2) = shot(d)
        repo.discardUncommittedCapture(f2)
        assertFalse(f2.exists())
    }

    @Test
    fun corruptedMiddleIsRejectedAtCaptureAndShaIsRecorded() = runBlocking {
        val d = repo.createDrilling("Д", emptyMap())
        val (id1, f1) = shot(d, TestJpeg.corruptMiddle(TestJpeg.bytes(320, 240), "cut"))
        try { repo.saveCapture(d, null, id1, LampType.DAYLIGHT, f1, profile); fail() } catch (_: IllegalStateException) {}
        assertEquals(0, db.tableDao().countByDrilling(d))

        val (id2, f2) = shot(d)
        val table = repo.saveCapture(d, null, id2, LampType.DAYLIGHT, f2, profile)
        assertEquals(FileStorage(context).sha256(f2), db.layerDao().getByTableAndLamp(table.id, "DAYLIGHT")!!.fileSha256)
    }

    @Test
    fun captureAuditIsStoredWithTheLayer() = runBlocking {
        val d = repo.createDrilling("Д", emptyMap())
        val (id, f) = shot(d)
        val audit = com.siams.kern_prototype.camera.CaptureAudit(
            requested = com.siams.kern_prototype.camera.CaptureAudit.Requested(8_000_000L, 25, 100, 1.9f, 1f, 1.7f),
            applied = com.siams.kern_prototype.camera.ToupcamProfileApplier.ApplyReport(8000, 100, listOf(59, 0, 49), 100,
                results = mapOf("ExpoAGain" to "0x00000000")),
            limits = com.siams.kern_prototype.camera.CameraLimits.FALLBACK,
            readback = null,
        )
        val table = repo.saveCapture(d, null, id, LampType.DAYLIGHT, f, profile, audit = audit)
        val stored = db.layerDao().getByTableAndLamp(table.id, "DAYLIGHT")!!.captureAuditJson!!
        val decoded = json.decodeFromString(com.siams.kern_prototype.camera.CaptureAudit.serializer(), stored)
        assertEquals(audit, decoded.copy(saveMemory = null))
        assertEquals(null, decoded.readback)
        // R21: замер памяти сохранения (проверка JPEG, SHA-256, миниатюра) — вместе с аудитом.
        val save = decoded.saveMemory!!
        assertEquals("save-layer", save.operation)
        assertEquals(listOf("start", "validated", "sha256", "end:ok"), save.phases.map { it.name })
    }

    /**
     * R21: метрики, чтение PSS которых висит до [gate] (медленный /proc). Ожидание прерываемо —
     * как и у реального опроса, остановка замера его прерывает.
     */
    private class BlockingReader(private val gate: CountDownLatch) : MemoryReader {
        val entered = CountDownLatch(1)
        override fun javaUsedBytes() = 1L
        override fun javaMaxBytes() = 2L
        override fun nativeAllocatedBytes() = 3L
        override fun pssKb(): Long { entered.countDown(); gate.await(10, TimeUnit.SECONDS); return 4L }
    }

    private fun simpleAudit() = com.siams.kern_prototype.camera.CaptureAudit(
        requested = com.siams.kern_prototype.camera.CaptureAudit.Requested(8_000_000L, 25, 100, 1.9f, 1f, 1.7f),
        applied = null,
        limits = com.siams.kern_prototype.camera.CameraLimits.FALLBACK,
        readback = null,
    )

    private fun probeThreadsAlive() = Thread.getAllStackTraces().keys.count { it.name == "memory-probe" && it.isAlive }

    private fun waitUntil(ms: Long = 3000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(5) }
        return cond()
    }

    /** R21: медленное чтение метрик при сохранении не занимает поток вызывающего (главный). */
    @Test
    fun slowMemoryReadsDoNotBlockTheCallerThread() {
        val d = runBlocking { repo.createDrilling("Д", emptyMap()) }
        val (id, f) = shot(d)
        val gate = CountDownLatch(1)
        val reader = BlockingReader(gate)
        repo.memoryReader = reader
        val main = Executors.newSingleThreadExecutor()
        try {
            val scope = CoroutineScope(main.asCoroutineDispatcher())
            val save = scope.async { repo.saveCapture(d, null, id, LampType.DAYLIGHT, f, profile, audit = simpleAudit()) }
            assertTrue("замер начался", reader.entered.await(5, TimeUnit.SECONDS))
            // Пока метрики «читаются», главный поток свободен.
            main.submit {}.get(1, TimeUnit.SECONDS)
            gate.countDown()
            val table = runBlocking { withTimeout(10_000) { save.await() } }
            val stored = runBlocking { db.layerDao().getByTableAndLamp(table.id, "DAYLIGHT") }!!.captureAuditJson!!
            val decoded = json.decodeFromString(com.siams.kern_prototype.camera.CaptureAudit.serializer(), stored)
            assertEquals(listOf("start", "validated", "sha256", "end:ok"), decoded.saveMemory!!.phases.map { it.name })
            assertTrue("опрос остановлен", waitUntil { probeThreadsAlive() == 0 })
        } finally {
            gate.countDown()
            main.shutdownNow()
        }
    }

    /** R21: отклонённый (повреждённый) снимок завершает замер — опрос не остаётся работать. */
    @Test
    fun rejectedCaptureStopsTheProbe() = runBlocking {
        val d = repo.createDrilling("Д", emptyMap())
        val (id, f) = shot(d, TestJpeg.corruptMiddle(TestJpeg.bytes(320, 240), "cut"))
        try {
            repo.saveCapture(d, null, id, LampType.DAYLIGHT, f, profile, audit = simpleAudit())
            fail()
        } catch (_: IllegalStateException) {}
        assertTrue("после ошибки опрос памяти не работает", waitUntil { probeThreadsAlive() == 0 })
    }

    /** R21: отмена сохранения посреди замера не оставляет фоновый опрос работающим. */
    @Test
    fun cancellationDuringMeasurementStopsTheProbe() {
        val d = runBlocking { repo.createDrilling("Д", emptyMap()) }
        val (id, f) = shot(d)
        val gate = CountDownLatch(1)
        val reader = BlockingReader(gate)
        repo.memoryReader = reader
        val main = Executors.newSingleThreadExecutor()
        try {
            val scope = CoroutineScope(main.asCoroutineDispatcher())
            val save = scope.async { repo.saveCapture(d, null, id, LampType.DAYLIGHT, f, profile, audit = simpleAudit()) }
            assertTrue(reader.entered.await(5, TimeUnit.SECONDS))
            save.cancel()                      // экран закрыт посреди замера
            gate.countDown()                   // медленное чтение завершилось
            runBlocking { withTimeout(10_000) { save.join() } }
            assertTrue(save.isCancelled)
            assertTrue("после отмены опрос памяти не работает", waitUntil { probeThreadsAlive() == 0 })
            assertNull("отменённое сохранение не записано", runBlocking { db.layerDao().getById(id) })
        } finally {
            gate.countDown()
            main.shutdownNow()
        }
    }

    /** R15: глубина, введённая после DAYLIGHT, достаётся и доснятому позже УФ. */
    @Test
    fun layerShotLaterInheritsTableDepths() = runBlocking {
        val d = repo.createDrilling("Д", emptyMap())
        val (id1, f1) = shot(d)
        val table = repo.saveCapture(d, null, id1, LampType.DAYLIGHT, f1, profile)
        repo.setLayerDepth(table.id, 1520.5, 1530.0)
        val (id2, f2) = shot(d)
        repo.saveCapture(d, table.id, id2, LampType.UF, f2, profile)
        val uf = db.layerDao().getByTableAndLamp(table.id, "UF")!!
        assertEquals(1520.5, uf.kernUpperDepth!!, 0.0)
        assertEquals(1530.0, uf.kernLowerDepth!!, 0.0)
    }

    /** R15: слой старых данных без глубин при пересъёмке получает глубины стола. */
    @Test
    fun reshotLegacyLayerTakesTableDepths() = runBlocking {
        val d = repo.createDrilling("Д", emptyMap())
        val (id1, f1) = shot(d)
        val table = repo.saveCapture(d, null, id1, LampType.DAYLIGHT, f1, profile)
        val (id2, f2) = shot(d)
        repo.saveCapture(d, table.id, id2, LampType.UF, f2, profile)
        // Данные до исправления: глубина только у дневного слоя.
        val day = db.layerDao().getByTableAndLamp(table.id, "DAYLIGHT")!!
        db.layerDao().update(day.copy(kernUpperDepth = 10.0, kernLowerDepth = 12.0))
        val (id3, f3) = shot(d)
        repo.saveCapture(d, table.id, id3, LampType.UF, f3, profile)
        val uf = db.layerDao().getByTableAndLamp(table.id, "UF")!!
        assertEquals(10.0 to 12.0, uf.kernUpperDepth to uf.kernLowerDepth)
    }

    /**
     * R15: старые данные, где пара глубин, собранная по границам из разных слоёв, — обратный интервал
     * (DAYLIGHT: кровля 200; UF: 100…150 → 200…150). Пересъёмка отказывает до записи: прежний слой,
     * его файлы и глубины не меняются, новый файл не остаётся.
     */
    @Test
    fun reshootWithContradictoryLegacyDepthsIsRefusedWithoutChanges() = runBlocking {
        val d = repo.createDrilling("Д", emptyMap())
        val (id1, f1) = shot(d)
        val table = repo.saveCapture(d, null, id1, LampType.DAYLIGHT, f1, profile)
        val (id2, f2) = shot(d)
        repo.saveCapture(d, table.id, id2, LampType.UF, f2, profile)
        val day = db.layerDao().getByTableAndLamp(table.id, "DAYLIGHT")!!
        val uf = db.layerDao().getByTableAndLamp(table.id, "UF")!!
        db.layerDao().update(day.copy(kernUpperDepth = 200.0, kernLowerDepth = null))
        db.layerDao().update(uf.copy(kernUpperDepth = 100.0, kernLowerDepth = 150.0))
        val before = db.layerDao().listByTable(table.id).sortedBy { it.id }

        try {
            repo.checkCanSaveCapture(table.id, LampType.UF); fail("предварительная проверка должна отказать")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!, e.message!!.contains("кровля 200, подошва 150"))
        }
        val (id3, f3) = shot(d)
        try {
            repo.saveCapture(d, table.id, id3, LampType.UF, f3, profile); fail("пересъёмка должна отказать")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!, e.message!!.contains("Исправьте глубины"))
        }
        assertEquals("слои не изменились", before, db.layerDao().listByTable(table.id).sortedBy { it.id })
        assertTrue("прежний файл УФ на месте", File(uf.filePath).exists())
        assertFalse("новый файл не остался", f3.exists())
        assertFalse(FileStorage(context).thumbFileFor(f3).exists())

        // После исправления глубин оператором пересъёмка проходит.
        repo.setLayerDepth(table.id, 100.0, 150.0)
        val (id4, f4) = shot(d)
        repo.saveCapture(d, table.id, id4, LampType.UF, f4, profile)
        assertEquals(100.0 to 150.0, db.layerDao().getByTableAndLamp(table.id, "UF")!!.let { it.kernUpperDepth to it.kernLowerDepth })
    }

    /** R15: неверные глубины репозиторий не записывает (защита помимо формы). */
    @Test
    fun invalidDepthsAreRejectedWithoutChanges() = runBlocking {
        val d = repo.createDrilling("Д", emptyMap())
        val (id1, f1) = shot(d)
        val table = repo.saveCapture(d, null, id1, LampType.DAYLIGHT, f1, profile)
        repo.setLayerDepth(table.id, 5.0, 6.0)
        try { repo.setLayerDepth(table.id, 7.0, 6.0); fail() } catch (_: IllegalArgumentException) {}
        val layer = db.layerDao().getByTableAndLamp(table.id, "DAYLIGHT")!!
        assertEquals(5.0 to 6.0, layer.kernUpperDepth to layer.kernLowerDepth)
    }

    /** R15: черновики удалённого стола/долбления не остаются; черновик нового долбления не трогается. */
    @Test
    fun draftsOfDeletedTablesAndDrillingsAreRemoved() = runBlocking {
        val d = repo.createDrilling("Д", emptyMap())
        val (id1, f1) = shot(d)
        val t1 = repo.saveCapture(d, null, id1, LampType.DAYLIGHT, f1, profile)
        val (id2, f2) = shot(d)
        val t2 = repo.saveCapture(d, null, id2, LampType.DAYLIGHT, f2, profile)
        val keys = KernRepository.DraftKeys
        listOf(keys.depth(t1.id), keys.depth(t2.id), keys.passport(d), keys.passport(null))
            .forEach { repo.writeDraft(it, mapOf("x" to "1")) }

        repo.deleteTable(t1.id)
        assertNull(repo.readDraft(keys.depth(t1.id)))
        assertEquals(mapOf("x" to "1"), repo.readDraft(keys.depth(t2.id)))

        repo.deleteDrilling(d)
        assertNull(repo.readDraft(keys.depth(t2.id)))
        assertNull(repo.readDraft(keys.passport(d)))
        assertEquals("черновик нового долбления не относится к удалённому", mapOf("x" to "1"), repo.readDraft(keys.passport(null)))
    }

    /** R15: сбой между записью данных и удалением черновика откатывает обе операции. */
    @Test
    fun dataWriteAndDraftClearAreAtomic() = runBlocking {
        val keys = KernRepository.DraftKeys
        repo.onBeforeDraftClearForTest = { error("сбой между записью и удалением черновика") }

        // Правка паспорта.
        val d = repo.createDrilling("Д", mapOf("well_number" to "1"))
        val before = repo.getDrilling(d)!!
        repo.writeDraft(keys.passport(d), mapOf("well_number" to "12а"))
        try { repo.updateDrilling(d, "Д", mapOf("well_number" to "2"), clearDraftKey = keys.passport(d)); fail() } catch (_: IllegalStateException) {}
        assertEquals("правка откатилась", before, repo.getDrilling(d))
        assertEquals("черновик на месте", mapOf("well_number" to "12а"), repo.readDraft(keys.passport(d)))

        // Создание долбления.
        repo.writeDraft(keys.passport(null), mapOf("__title" to "Новое"))
        try { repo.createDrilling("Новое", emptyMap(), clearDraftKey = keys.passport(null)); fail() } catch (_: IllegalStateException) {}
        assertEquals("долбление не создано", listOf(d), db.drillingDao().listIdsByStatus(com.siams.kern_prototype.domain.model.SyncStatus.NOT_SENT))
        assertEquals(mapOf("__title" to "Новое"), repo.readDraft(keys.passport(null)))

        // Глубины стола.
        repo.onBeforeDraftClearForTest = null
        val (id1, f1) = shot(d)
        val table = repo.saveCapture(d, null, id1, LampType.DAYLIGHT, f1, profile)
        repo.setLayerDepth(table.id, 5.0, 6.0)
        repo.writeDraft(keys.depth(table.id), mapOf("upper" to "x"))
        repo.onBeforeDraftClearForTest = { error("сбой") }
        try { repo.setLayerDepth(table.id, 7.0, 8.0, clearDraftKey = keys.depth(table.id)); fail() } catch (_: IllegalStateException) {}
        val layer = db.layerDao().getByTableAndLamp(table.id, "DAYLIGHT")!!
        assertEquals(5.0 to 6.0, layer.kernUpperDepth to layer.kernLowerDepth)
        assertEquals(mapOf("upper" to "x"), repo.readDraft(keys.depth(table.id)))
        repo.onBeforeDraftClearForTest = null
    }

    @Test
    fun atomicWriteLeavesNoPartialFile() {
        val target = File(context.filesDir, "atomic/x.jpg")
        try {
            writeAtomically(target) { it.write(byteArrayOf(1, 2)); false } // compress вернул false
            fail()
        } catch (_: IllegalStateException) {}
        assertFalse(target.exists())
        assertFalse(File(target.path + ".tmp").exists())

        writeAtomically(target) { it.write(TestJpeg.bytes()); true }
        assertNull(FileStorage(context).jpegProblem(target))
        assertNull(target.parentFile!!.listFiles()!!.firstOrNull { it.name.endsWith(".tmp") })
    }
}
