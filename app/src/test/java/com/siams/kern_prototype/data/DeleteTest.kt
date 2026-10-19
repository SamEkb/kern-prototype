package com.siams.kern_prototype.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.siams.kern_prototype.data.db.KernDatabase
import com.siams.kern_prototype.data.storage.FileStorage
import com.siams.kern_prototype.domain.model.CameraProfile
import com.siams.kern_prototype.domain.model.LampType
import com.siams.kern_prototype.TestJpeg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** П.4: удаление слоя/стола/долбления — сначала БД, потом файлы; неизвестный исход — файлы целы. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DeleteTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private lateinit var db: KernDatabase
    private lateinit var repo: KernRepository
    private lateinit var drillingId: String
    private lateinit var tableId: String
    private lateinit var layerId: String
    private lateinit var photo: File

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, KernDatabase::class.java).allowMainThreadQueries().build()
        repo = KernRepository(db, db.drillingDao(), db.tableDao(), db.layerDao(), FileStorage(context), PassportCodec(json), json)
        drillingId = repo.createDrilling("Д", emptyMap())
        val (id, f) = repo.newLayerFile(drillingId)
        f.writeBytes(TestJpeg.bytes())
        tableId = repo.saveCapture(drillingId, null, id, LampType.DAYLIGHT, f, CameraProfile.defaultFor(LampType.DAYLIGHT)).id
        layerId = db.layerDao().getByTableAndLamp(tableId, "DAYLIGHT")!!.id
        photo = f
    }

    @After
    fun tearDown() {
        photo.parentFile?.setWritable(true)
        db.close()
    }

    /**
     * Запретить удаление файлов снимков: папка без права записи. Где запрет не действует (процесс
     * от root, например на CI-раннере), ошибку удаления так не воспроизвести — тест пропускается,
     * а не падает и не проходит впустую.
     */
    private fun blockDeletion() {
        val dir = photo.parentFile!!
        dir.setWritable(false)
        val probe = File(dir, ".write-probe")
        val enforced = runCatching { !probe.createNewFile() }.getOrDefault(true)
        if (!enforced) {
            probe.delete()
            dir.setWritable(true)
        }
        assumeTrue("запрет записи в папку не действует (root?) — ошибку удаления не воспроизвести", enforced)
    }

    /** Запустить [op], отменив её в конце тела транзакции (commit Room при этом состоится). */
    private fun cancelAtCommit(op: suspend () -> Unit) = runBlocking {
        lateinit var job: Job
        repo.onTransactionBodyEndForTest = { job.cancel() }
        job = launch(start = CoroutineStart.LAZY) { op() }
        job.join()
        repo.onTransactionBodyEndForTest = null
        assertTrue(job.isCancelled)
    }

    /** Отмена, замеченная внутри транзакции до commit → откат. */
    private fun cancelBeforeCommit(op: suspend () -> Unit) = runBlocking {
        repo.onTransactionBodyEndForTest = { throw CancellationException("отмена до commit") }
        try { op(); fail() } catch (_: CancellationException) {}
        repo.onTransactionBodyEndForTest = null
    }

    // ---- слой ----

    @Test
    fun deleteLayer_removesRecordThenFiles() = runBlocking {
        repo.deleteLayer(layerId)
        assertNull(db.layerDao().getById(layerId))
        assertFalse(photo.exists())
    }

    @Test
    fun deleteLayer_cancelAfterCommit_cleansFiles() {
        cancelAtCommit { repo.deleteLayer(layerId) }
        runBlocking { assertNull(db.layerDao().getById(layerId)) }
        assertFalse("commit был — файл не нужен", photo.exists())
    }

    @Test
    fun deleteLayer_cancelBeforeCommit_keepsRecordAndFiles() {
        cancelBeforeCommit { repo.deleteLayer(layerId) }
        runBlocking { assertNotNull(db.layerDao().getById(layerId)) }
        assertTrue(photo.exists())
    }

    @Test
    fun deleteLayer_fileDeletionErrorKeepsDbConsistent() = runBlocking {
        blockDeletion() // файл не удалить
        repo.deleteLayer(layerId)             // операция не падает
        assertNull(db.layerDao().getById(layerId))
        assertTrue("лишний файл остаётся, ссылок на него нет", photo.exists())
    }

    // ---- стол ----

    @Test
    fun deleteTable_cancelAfterCommit_cleansFiles() {
        cancelAtCommit { repo.deleteTable(tableId) }
        runBlocking {
            assertNull(db.tableDao().getById(tableId))
            assertNull(db.layerDao().getById(layerId))
        }
        assertFalse(photo.exists())
    }

    @Test
    fun deleteTable_cancelBeforeCommit_keepsEverything() {
        cancelBeforeCommit { repo.deleteTable(tableId) }
        runBlocking {
            assertNotNull(db.tableDao().getById(tableId))
            assertEquals(photo.absolutePath, db.layerDao().getById(layerId)!!.filePath)
        }
        assertTrue(photo.exists())
    }

    @Test
    fun deleteTable_fileDeletionError() = runBlocking {
        blockDeletion()
        repo.deleteTable(tableId)
        assertNull(db.tableDao().getById(tableId))
        assertTrue(photo.exists())
    }

    // ---- несколько столов ----

    /** Второй стол того же долбления со снимком; возвращает id стола и файл. */
    private fun secondTable(): Pair<String, File> = runBlocking {
        val (id, f) = repo.newLayerFile(drillingId)
        f.writeBytes(TestJpeg.bytes())
        repo.saveCapture(drillingId, null, id, LampType.DAYLIGHT, f, CameraProfile.defaultFor(LampType.DAYLIGHT)).id to f
    }

    @Test
    fun deleteTables_removesAllSelectedAndTheirFiles() = runBlocking {
        val (table2, photo2) = secondTable()
        val (table3, photo3) = secondTable()
        val revision = db.drillingDao().getById(drillingId)!!.editRevision
        assertEquals(2, repo.deleteTables(setOf(tableId, table2, "нет-такого")))
        assertNull(db.tableDao().getById(tableId))
        assertNull(db.tableDao().getById(table2))
        assertNotNull("невыбранный стол цел", db.tableDao().getById(table3))
        assertFalse(photo.exists())
        assertFalse(photo2.exists())
        assertTrue(photo3.exists())
        assertTrue("долбление помечено изменённым", db.drillingDao().getById(drillingId)!!.editRevision > revision)
    }

    @Test
    fun deleteTables_whileSendingDeletesNothing() = runBlocking {
        val (table2, photo2) = secondTable()
        db.drillingDao().update(db.drillingDao().getById(drillingId)!!.copy(syncStatus = com.siams.kern_prototype.domain.model.SyncStatus.SENDING))
        try { repo.deleteTables(setOf(tableId, table2)); fail() } catch (e: IllegalStateException) {
            assertTrue(e.message, e.message!!.contains("удалить столы"))
        }
        assertNotNull(db.tableDao().getById(tableId))
        assertNotNull(db.tableDao().getById(table2))
        assertTrue(photo.exists())
        assertTrue(photo2.exists())
    }

    @Test
    fun deleteTables_cancelBeforeCommit_keepsEverything() {
        val (table2, photo2) = secondTable()
        cancelBeforeCommit { repo.deleteTables(setOf(tableId, table2)) }
        runBlocking {
            assertNotNull(db.tableDao().getById(tableId))
            assertNotNull(db.tableDao().getById(table2))
        }
        assertTrue(photo.exists())
        assertTrue(photo2.exists())
    }

    @Test
    fun deleteTables_cancelAfterCommit_cleansFiles() {
        val (table2, photo2) = secondTable()
        cancelAtCommit { repo.deleteTables(setOf(tableId, table2)) }
        runBlocking {
            assertNull(db.tableDao().getById(tableId))
            assertNull(db.tableDao().getById(table2))
        }
        assertFalse(photo.exists())
        assertFalse(photo2.exists())
    }

    @Test
    fun deleteTables_nothingToDelete() = runBlocking {
        assertEquals(0, repo.deleteTables(setOf("нет-такого")))
        assertNotNull(db.tableDao().getById(tableId))
    }

    // ---- долбление ----

    @Test
    fun deleteDrilling_cancelAfterCommit_removesDir() {
        cancelAtCommit { repo.deleteDrilling(drillingId) }
        runBlocking { assertNull(db.drillingDao().getById(drillingId)) }
        assertFalse(photo.parentFile!!.exists())
    }

    @Test
    fun deleteDrilling_cancelBeforeCommit_keepsEverything() {
        cancelBeforeCommit { repo.deleteDrilling(drillingId) }
        runBlocking {
            assertNotNull(db.drillingDao().getById(drillingId))
            assertNotNull(db.layerDao().getById(layerId))
        }
        assertTrue(photo.exists())
    }

    @Test
    fun deleteDrilling_fileDeletionError() = runBlocking {
        blockDeletion()
        repo.deleteDrilling(drillingId)
        assertNull(db.drillingDao().getById(drillingId))
        assertTrue(photo.exists())
    }
}
