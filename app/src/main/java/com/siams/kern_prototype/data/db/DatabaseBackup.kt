package com.siams.kern_prototype.data.db

import android.content.Context
import android.util.Log
import java.io.File
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException

/**
 * Защита архива при обновлении схемы (R07). Вызывается ДО открытия Room.
 *
 * Если версия существующего файла БД ниже целевой, файлы БД (`.db`, `-wal`, `-shm`)
 * копируются в `filesDir/db_backup/`. Если копию снять не удалось или версия не
 * поддерживается миграциями, обновление ОСТАНАВЛИВАЕТСЯ исключением: архив остаётся в
 * исходном виде, разрушительного пересоздания нет. Копии автоматически не удаляются.
 */
object DatabaseBackup {

    private const val TAG = "DatabaseBackup"

    /** Минимальная версия, для которой есть миграции (схемы v1–v2 не сохранились). */
    const val MIN_MIGRATABLE_VERSION = 3

    fun prepareForOpen(context: Context, name: String, targetVersion: Int) {
        val db = context.getDatabasePath(name)
        if (!db.exists()) return
        val version = readUserVersion(db)
        // 0 — файл пуст/не инициализирован Room (данных нет); >= target — обновление не нужно
        // (понижение версии Room отклонит сам, без разрушительного fallback).
        if (version == 0 || version >= targetVersion) return
        val dir = backup(context, db, name, version)
        check(version >= MIN_MIGRATABLE_VERSION) {
            "База данных версии $version не поддерживается обновлением. Обновление остановлено, " +
                "данные не изменены; копия: ${dir.path}"
        }
    }

    private fun backup(context: Context, db: File, name: String, version: Int): File {
        val dir = File(context.filesDir, "db_backup")
        try {
            check(dir.isDirectory || dir.mkdirs()) { "нет каталога ${dir.path}" }
            val stamp = System.currentTimeMillis()
            for (suffix in listOf("", "-wal", "-shm")) {
                val src = File(db.path + suffix)
                if (src.exists()) src.copyTo(File(dir, "$name-v$version-$stamp$suffix"))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Не удалось сохранить копию БД v$version", t)
            throw IllegalStateException(
                "Не удалось сохранить копию базы данных перед обновлением. Обновление остановлено, " +
                    "данные не изменены. Освободите место и перезапустите приложение.",
                t,
            )
        }
        Log.i(TAG, "Копия БД v$version сохранена: ${dir.path}")
        return dir
    }

    /**
     * Версия схемы (`PRAGMA user_version`) — через SQLite, а не из заголовка файла: в режиме WAL
     * (у Room по умолчанию) после аварийного завершения актуальный заголовок может быть только в
     * `-wal`, а основной файл — устаревшим (вплоть до 0). Соединение только для чтения не
     * выполняет контрольную точку и не меняет режим журнала — файлы для копии не изменяются.
     *
     * Повреждённый файл НЕ удаляется: стандартный обработчик Android ([android.database.DefaultDatabaseErrorHandler])
     * удаляет файлы БД прямо внутри открытия (до любого catch и до копии), поэтому передаётся
     * [KeepFilesOnCorruption] — он останавливает открытие исключением. Обновление прекращается,
     * файлы остаются побайтно прежними; повторная попытка снова останавливается, пустая БД не создаётся.
     */
    private fun readUserVersion(db: File): Int {
        if (db.length() == 0L) return 0
        return try {
            SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READONLY, KeepFilesOnCorruption).use { it.version }
        } catch (e: SQLiteException) {
            throw corrupted(e)
        } catch (e: CorruptDatabaseException) {
            throw corrupted(e)
        }
    }

    private fun corrupted(cause: Throwable) = IllegalStateException(
        "Файл базы данных повреждён или не читается — обновление остановлено, данные не изменены. " +
            "Не удаляйте приложение: файл можно восстановить или передать для анализа.",
        cause,
    )

    private class CorruptDatabaseException(path: String) : RuntimeException("Повреждение БД: $path")

    /** Обработчик повреждения, который ничего не удаляет и прерывает открытие. */
    private object KeepFilesOnCorruption : DatabaseErrorHandler {
        override fun onCorruption(dbObj: SQLiteDatabase) {
            runCatching { Log.e(TAG, "Обнаружено повреждение БД ${dbObj.path} — файлы не удаляются") }
            throw CorruptDatabaseException(dbObj.path)
        }
    }
}
