package com.siams.kern_prototype.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Явные миграции схемы Room (R07). Разрушительный fallback для пользовательских данных
 * запрещён: каждая поддерживаемая версия обновляется миграцией, иначе Room упадёт при
 * открытии, а не удалит архив. Схемы версий — в `app/schemas` (exportSchema).
 *
 * Версии 1–2 существовали только в ранних сборках разработки; их схемы не сохранились,
 * поэтому миграций для них нет: обновление такой БД останавливается без удаления данных
 * (см. [DatabaseBackup.prepareForOpen]).
 */
object KernMigrations {

    /**
     * v3 → v4 (R01/R05): `layer.metaConfirmed`, `layer.staleRemoteImageId` (NULL) и
     * `drilling.editRevision`.
     * `editRevision` для существующих долблений — 0 (отсчёт правок начинается после обновления).
     * `metaConfirmed` для всех существующих слоёв — 0 («не подтверждено»):
     * статус SENT старой версии не гарантирует доставку метаданных (именно дефект R01), поэтому
     * при следующей отправке для уже загруженных слоёв будет выполнен только PUT метаданных
     * (идемпотентен), файлы повторно не грузятся.
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE layer ADD COLUMN metaConfirmed INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE drilling ADD COLUMN editRevision INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE layer ADD COLUMN staleRemoteImageId TEXT")
        }
    }

    /**
     * v4 → v5 (R03, R02): `drilling.remoteDestination` и `layer.fileSha256` (NULL у прежних
     * снимков — они проверяются полным декодированием). Для уже отправленных долблений назначение
     * неизвестно (NULL): при следующей отправке принимается текущее (прежнее поведение), дальше
     * смена сервера распознаётся.
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE drilling ADD COLUMN remoteDestination TEXT")
            db.execSQL("ALTER TABLE layer ADD COLUMN fileSha256 TEXT")
        }
    }

    /** v5 → v6 (R14): `layer.captureAuditJson`; у существующих снимков аудита нет (NULL). */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE layer ADD COLUMN captureAuditJson TEXT")
        }
    }

    /**
     * v6 → v7 (R15): таблица черновиков форм `form_draft`. Существующие данные не затрагиваются;
     * черновиков у прежних версий не было — таблица пуста.
     */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `form_draft` (`key` TEXT NOT NULL, `valuesJson` TEXT NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))",
            )
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
}
