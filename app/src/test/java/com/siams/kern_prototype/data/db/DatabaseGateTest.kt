package com.siams.kern_prototype.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Ошибка открытия БД: шлюз сообщает «недоступна» с причиной, не бросая исключения; файлы не
 * удаляются и не подменяются, пустая БД не создаётся; при исправной БД — «готово».
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseGateTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val file: File get() = context.getDatabasePath(KernDatabase.NAME)
    private val gates = mutableListOf<DatabaseGate>()

    private fun gate() = DatabaseGate(context).also { gates += it }

    private fun clean() {
        file.parentFile?.mkdirs()
        listOf("", "-wal", "-shm", "-journal").forEach { File(file.path + it).delete() }
        File(context.filesDir, "db_backup").deleteRecursively()
    }

    @Before fun setUp() = clean()

    @After
    fun tearDown() {
        gates.forEach { it.closeForTest() }
        clean()
    }

    @Test
    fun corruptedFileIsReportedWithoutDeletingOrReplacingIt() {
        val garbage = ByteArray(8192) { i -> ((i * 31 + 7) % 251).toByte() }
        file.writeBytes(garbage)
        repeat(2) { attempt ->
            val state = gate().open()
            assertTrue("попытка ${attempt + 1}: $state", state is DatabaseGate.State.Failed)
            assertTrue((state as DatabaseGate.State.Failed).message, state.message.contains("повреждён"))
            assertTrue("файл не удалён", file.exists())
            assertTrue("файл не изменён и не заменён пустой БД", file.readBytes().contentEquals(garbage))
        }
    }

    @Test
    fun unsupportedVersionIsReportedAndDataKept() {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE drilling (id TEXT PRIMARY KEY, title TEXT)")
            db.execSQL("INSERT INTO drilling VALUES ('old', 'Старое долбление')")
            db.version = 2
        }
        val state = gate().open()
        assertTrue("$state", state is DatabaseGate.State.Failed && state.message.contains("версии 2"))
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { assertEquals(2, it.version) }
    }

    @Test
    fun requireDatabaseThrowsWhenUnavailableAndNeverHandsOutADatabase() {
        file.writeBytes(ByteArray(4096) { 7 })
        val g = gate()
        try { g.requireDatabase(); fail("недоступная БД не выдаётся") } catch (e: IllegalStateException) {
            assertTrue(e.message!!, e.message!!.contains("недоступна"))
        }
        assertEquals(null, g.whenReady { it })
    }

    @Test
    fun healthyDatabaseIsReadyAndOpenedOnce() = runBlocking {
        val g = gate()
        assertTrue(g.open() is DatabaseGate.State.Ready)
        val db = g.requireDatabase()
        assertSame("один экземпляр", db, g.requireDatabase())
        assertEquals(emptyList<String>(), db.drillingDao().listIdsByStatus(com.siams.kern_prototype.domain.model.SyncStatus.NOT_SENT))
        assertFalse(g.whenReady { it } == null)
    }

    /**
     * Повреждение, которое предварительная проверка версии не видит (заголовок цел, испорчены
     * следующие страницы): Room при открытии не должен удалять файл (по умолчанию помощник SQLite
     * удаляет БД в onCorruption) — открытие останавливается, файл прежний.
     */
    @Test
    fun corruptionFoundByRoomDoesNotDeleteTheFile() {
        val first = gate()
        assertTrue(first.open() is DatabaseGate.State.Ready)
        runBlocking { first.requireDatabase().drillingDao().upsert(
            com.siams.kern_prototype.data.db.entity.DrillingEntity(
                id = "d1", title = "Д", passportJson = "{}", createdAt = 1,
                syncStatus = com.siams.kern_prototype.domain.model.SyncStatus.NOT_SENT,
            ),
        ) }
        first.closeForTest()
        listOf("-wal", "-shm").forEach { File(file.path + it).delete() }
        val bytes = file.readBytes()
        for (i in 4096 until bytes.size) bytes[i] = 0x5A.toByte()   // страницы после первой — мусор
        file.writeBytes(bytes)

        val state = gate().open()
        assertTrue("файл не удалён", file.exists())
        assertTrue("файл не изменён", file.readBytes().contentEquals(bytes))
        assertTrue("$state", state is DatabaseGate.State.Failed)
    }
}
