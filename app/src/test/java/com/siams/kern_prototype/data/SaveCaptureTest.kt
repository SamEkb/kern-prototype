package com.siams.kern_prototype.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.siams.kern_prototype.data.db.KernDatabase
import com.siams.kern_prototype.data.storage.FileStorage
import com.siams.kern_prototype.data.storage.writeAtomically
import com.siams.kern_prototype.domain.model.CameraProfile
import com.siams.kern_prototype.domain.model.LampType
import com.siams.kern_prototype.sync.DrillingUploaderTest.Companion.MINIMAL_JPEG
import kotlinx.coroutines.CoroutineStart
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
import java.io.File

/** R08: новый кадр фиксируется до удаления прежнего; неудачный кадр ничего не портит. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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

    private fun shot(drillingId: String, bytes: ByteArray = MINIMAL_JPEG): Pair<String, File> =
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

        val (id2, f2) = shot(d, MINIMAL_JPEG.copyOf(5)) // обрезан: нет EOI
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
    fun atomicWriteLeavesNoPartialFile() {
        val target = File(context.filesDir, "atomic/x.jpg")
        try {
            writeAtomically(target) { it.write(byteArrayOf(1, 2)); false } // compress вернул false
            fail()
        } catch (_: IllegalStateException) {}
        assertFalse(target.exists())
        assertFalse(File(target.path + ".tmp").exists())

        writeAtomically(target) { it.write(MINIMAL_JPEG); true }
        assertTrue(FileStorage(context).isCompleteJpeg(target))
        assertNull(target.parentFile!!.listFiles()!!.firstOrNull { it.name.endsWith(".tmp") })
    }
}
