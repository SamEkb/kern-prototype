package com.siams.kern_prototype.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.siams.kern_prototype.di.DatabaseModule
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * R07: миграции на JVM (Robolectric, нативный SQLite). БД старой версии строится ровно по
 * экспортированной схеме `app/schemas/.../<v>.json` (она совпадает с полевыми APK), затем
 * открывается Room приложения с [KernMigrations.ALL]: Room сам сверяет итог с текущими
 * сущностями и упадёт при расхождении. Инструментальный аналог — androidTest MigrationTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KernMigrationsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun migrate3ToCurrent_keepsArchive() {
        val name = "mig3.db"
        createFromSchema(name, 3) { db ->
            db.execSQL(
                """INSERT INTO drilling (id, title, passportJson, createdAt, syncStatus, remoteId,
                   lastError, uploadedTables, totalTables)
                   VALUES ('d1', 'Долбление 1', '{"field":"Тест","core_recovery":6.5}', 1000, 'SENT',
                   'd1', NULL, 1, 1)""",
            )
            db.execSQL("INSERT INTO core_table (id, drillingId, number, stackUid) VALUES ('t1', 'd1', 1, 's1')")
            db.execSQL(
                """INSERT INTO layer (id, tableId, lampType, filePath, thumbPath, cameraProfileJson,
                   capturedAt, coreImageType, kernUpperDepth, kernLowerDepth, remoteImageId)
                   VALUES ('l1', 't1', 'DAYLIGHT', '/f/l1.jpg', '/f/l1_thumb.jpg', '{}', 2000,
                   'UNKNOWN', 1256.0, 1262.0, 'img-1')""",
            )
            db.execSQL(
                """INSERT INTO layer (id, tableId, lampType, filePath, thumbPath, cameraProfileJson,
                   capturedAt, coreImageType, kernUpperDepth, kernLowerDepth, remoteImageId)
                   VALUES ('l2', 't1', 'UF', '/f/l2.jpg', NULL, '{}', 2001, 'UNKNOWN', NULL, NULL, NULL)""",
            )
        }

        val room = Room.databaseBuilder(context, KernDatabase::class.java, name)
            .addMigrations(*KernMigrations.ALL)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                val content = room.drillingDao().getWithContent("d1")
                assertNotNull(content)
                assertEquals("Долбление 1", content!!.drilling.title)
                assertEquals("d1", content.drilling.remoteId)
                assertEquals(0L, content.drilling.editRevision)
                assertEquals("""{"field":"Тест","core_recovery":6.5}""", content.drilling.passportJson)
                val table = content.tables.single()
                assertEquals("s1", table.table.stackUid)
                val layers = table.layers.associateBy { it.id }
                val l1 = layers.getValue("l1")
                assertEquals("img-1", l1.remoteImageId)
                assertEquals("/f/l1.jpg", l1.filePath)
                assertEquals("/f/l1_thumb.jpg", l1.thumbPath)
                assertEquals(1256.0, l1.kernUpperDepth!!, 0.0)
                assertEquals(1262.0, l1.kernLowerDepth!!, 0.0)
                // SENT старой версии не доказывает доставку метаданных (R01) → PUT повторится.
                assertFalse(l1.metaConfirmed)
                assertFalse(layers.getValue("l2").metaConfirmed)
            }
            assertEquals(KernDatabase.VERSION, room.openHelper.readableDatabase.version)
        } finally {
            room.close()
        }
    }

    @Test
    fun migrate4To5_keepsSentStateAndLeavesDestinationUnknown() {
        val name = "mig4.db"
        createFromSchema(name, 4) { db ->
            db.execSQL(
                """INSERT INTO drilling (id, title, passportJson, createdAt, syncStatus, remoteId, lastError,
                   uploadedTables, totalTables, editRevision) VALUES ('d1', 'Д', '{}', 1, 'SENT', 'd1', NULL, 1, 1, 7)""",
            )
            db.execSQL("INSERT INTO core_table (id, drillingId, number, stackUid) VALUES ('t1', 'd1', 1, 's1')")
            db.execSQL(
                """INSERT INTO layer (id, tableId, lampType, filePath, thumbPath, cameraProfileJson, capturedAt,
                   coreImageType, kernUpperDepth, kernLowerDepth, remoteImageId, metaConfirmed, staleRemoteImageId)
                   VALUES ('l1', 't1', 'DAYLIGHT', '/f/l1.jpg', NULL, '{}', 2, 'UNKNOWN', NULL, NULL, 'img-1', 1, NULL)""",
            )
        }
        val room = Room.databaseBuilder(context, KernDatabase::class.java, name)
            .addMigrations(*KernMigrations.ALL).allowMainThreadQueries().build()
        try {
            runBlocking {
                val d = room.drillingDao().getById("d1")!!
                assertEquals("d1", d.remoteId)
                assertEquals(7L, d.editRevision)
                assertEquals(null, d.remoteDestination) // назначение до v5 неизвестно
                val l = room.layerDao().getById("l1")!!
                assertEquals("img-1", l.remoteImageId)
                assertTrue(l.metaConfirmed)
            }
        } finally {
            room.close()
        }
    }

    @Test
    fun migrate5To6_keepsDataAndLeavesAuditMissing() {
        val name = "mig5.db"
        createFromSchema(name, 5) { db ->
            db.execSQL(
                """INSERT INTO drilling (id, title, passportJson, createdAt, syncStatus, remoteId, lastError,
                   uploadedTables, totalTables, editRevision, remoteDestination)
                   VALUES ('d1', 'Д', '{"field":"Тест"}', 1, 'SENT', 'd1', NULL, 1, 1, 3, 'http://a:8080|user')""",
            )
            db.execSQL("INSERT INTO core_table (id, drillingId, number, stackUid) VALUES ('t1', 'd1', 1, 's1')")
            db.execSQL(
                """INSERT INTO layer (id, tableId, lampType, filePath, thumbPath, cameraProfileJson, capturedAt,
                   coreImageType, kernUpperDepth, kernLowerDepth, remoteImageId, metaConfirmed, staleRemoteImageId, fileSha256)
                   VALUES ('l1', 't1', 'DAYLIGHT', '/f/l1.jpg', '/f/l1_t.jpg', '{"iso":100}', 2, 'UNKNOWN', 1.5, 2.5,
                   'img-1', 1, NULL, 'abc123')""",
            )
        }
        val room = Room.databaseBuilder(context, KernDatabase::class.java, name)
            .addMigrations(*KernMigrations.ALL).allowMainThreadQueries().build()
        try {
            runBlocking {
                val d = room.drillingDao().getById("d1")!!
                assertEquals("http://a:8080|user", d.remoteDestination)
                assertEquals(3L, d.editRevision)
                val l = room.layerDao().getById("l1")!!
                assertEquals("img-1", l.remoteImageId)
                assertTrue(l.metaConfirmed)
                assertEquals("abc123", l.fileSha256)
                assertEquals("{\"iso\":100}", l.cameraProfileJson)
                assertEquals(1.5, l.kernUpperDepth!!, 0.0)
                assertEquals("у старого снимка аудита нет", null, l.captureAuditJson)
            }
        } finally {
            room.close()
        }
    }

    @Test
    fun appOpenOfV3_makesBackupAndMigrates() {
        createFromSchema(KernDatabase.NAME, 3) { db ->
            db.execSQL(
                """INSERT INTO drilling (id, title, passportJson, createdAt, syncStatus, uploadedTables, totalTables)
                   VALUES ('d1', 'Д', '{}', 1, 'NOT_SENT', 0, 0)""",
            )
        }
        val room = DatabaseModule.provideDatabase(context)
        try {
            runBlocking { assertNotNull(room.drillingDao().getById("d1")) }
        } finally {
            room.close()
        }
        val backups = File(context.filesDir, "db_backup").list().orEmpty()
        assertTrue(backups.any { it.startsWith("${KernDatabase.NAME}-v3-") && !it.contains("-wal") })
    }

    @Test
    fun unsupportedOldVersion_stopsUpgradeAndKeepsData() {
        // v2: схема неизвестна → миграции нет. Данные должны остаться нетронутыми.
        val file = context.getDatabasePath(KernDatabase.NAME).apply { parentFile?.mkdirs(); delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE drilling (id TEXT PRIMARY KEY, title TEXT)")
            db.execSQL("INSERT INTO drilling VALUES ('old', 'Старое долбление')")
            db.version = 2
        }
        try {
            DatabaseModule.provideDatabase(context).openHelper.writableDatabase
            fail("обновление v2 должно быть остановлено")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("версии 2"))
        }
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(2, db.version)
            db.rawQuery("SELECT title FROM drilling", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Старое долбление", c.getString(0))
            }
        }
        assertTrue(File(context.filesDir, "db_backup").list().orEmpty().any { it.contains("-v2-") })
    }

    @Test
    fun backupFailure_stopsUpgrade() {
        createFromSchema(KernDatabase.NAME, 3) {}
        // Каталог копий занят файлом → копию не сохранить.
        File(context.filesDir, "db_backup").apply { deleteRecursively(); writeText("x") }
        try {
            DatabaseModule.provideDatabase(context)
            fail("без копии обновление должно быть остановлено")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("копию"))
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(KernDatabase.NAME).path, null, SQLiteDatabase.OPEN_READONLY)
            .use { assertEquals(3, it.version) }
    }

    /** Создать файл БД версии [version] по экспортированной схеме и заполнить данными. */
    private fun createFromSchema(name: String, version: Int, fill: (SQLiteDatabase) -> Unit) {
        val schema = Json.parseToJsonElement(
            File("schemas/com.siams.kern_prototype.data.db.KernDatabase/$version.json").readText(),
        ).jsonObject.getValue("database").jsonObject
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs(); delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("PRAGMA foreign_keys = ON")
            schema.getValue("entities").jsonArray.forEach { e ->
                val entity = e.jsonObject
                val table = entity.getValue("tableName").jsonPrimitive.content
                db.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray?.forEach { i ->
                    db.execSQL(i.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            fill(db)
            db.version = version
        }
    }
}
