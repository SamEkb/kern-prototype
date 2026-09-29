package com.siams.kern_prototype.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R07: обновление схемы не теряет архив. БД создаётся по экспортированной схеме
 * старой версии (app/schemas), заполняется «полевыми» данными и мигрируется.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        KernDatabase::class.java,
    )

    @Test
    fun migrate3To4_keepsDataAndResetsMetaConfirmation() {
        helper.createDatabase(dbName, 3).apply {
            execSQL(
                """INSERT INTO drilling (id, title, passportJson, createdAt, syncStatus, remoteId,
                   lastError, uploadedTables, totalTables)
                   VALUES ('d1', 'Долбление 1', '{"field":"Тест","core_recovery":6.5}', 1000, 'SENT',
                   'd1', NULL, 1, 1)""",
            )
            execSQL("INSERT INTO core_table (id, drillingId, number, stackUid) VALUES ('t1', 'd1', 1, 's1')")
            execSQL(
                """INSERT INTO layer (id, tableId, lampType, filePath, thumbPath, cameraProfileJson,
                   capturedAt, coreImageType, kernUpperDepth, kernLowerDepth, remoteImageId)
                   VALUES ('l1', 't1', 'DAYLIGHT', '/f/l1.jpg', '/f/l1_thumb.jpg', '{}', 2000,
                   'UNKNOWN', 1256.0, 1262.0, 'img-1')""",
            )
            execSQL(
                """INSERT INTO layer (id, tableId, lampType, filePath, thumbPath, cameraProfileJson,
                   capturedAt, coreImageType, kernUpperDepth, kernLowerDepth, remoteImageId)
                   VALUES ('l2', 't1', 'UF', '/f/l2.jpg', NULL, '{}', 2001, 'UNKNOWN', NULL, NULL, NULL)""",
            )
            close()
        }

        // Валидация схемы v4 против экспортированной 4.json.
        helper.runMigrationsAndValidate(dbName, 4, true, *KernMigrations.ALL).close()

        // Открываем через Room с теми же миграциями, что и приложение, и проверяем данные.
        val db = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            KernDatabase::class.java,
            dbName,
        ).addMigrations(*KernMigrations.ALL).build()
        try {
            runBlocking {
                val content = db.drillingDao().getWithContent("d1")
                assertNotNull(content)
                assertEquals("Долбление 1", content!!.drilling.title)
                assertEquals("d1", content.drilling.remoteId)
                val layers = content.tables.single().layers.associateBy { it.id }
                val l1 = layers.getValue("l1")
                assertEquals("img-1", l1.remoteImageId)
                assertEquals(1256.0, l1.kernUpperDepth!!, 0.0)
                assertEquals("/f/l1_thumb.jpg", l1.thumbPath)
                assertFalse(l1.metaConfirmed) // статус SENT v3 не доказывает доставку метаданных
                assertFalse(layers.getValue("l2").metaConfirmed)
            }
        } finally {
            db.close()
        }
    }
}
