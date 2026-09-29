package com.siams.kern_prototype.data.db

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile

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
     * `PRAGMA user_version` из заголовка файла SQLite (смещение 60, big-endian), без
     * открытия БД — чтобы не держать блокировок до Room. Если свежая версия ещё в `-wal`,
     * заголовок покажет прежнюю — будет лишняя копия, что безопасно. Ошибка чтения
     * пробрасывается: открывать непрочитанный файл вслепую нельзя.
     */
    private fun readUserVersion(db: File): Int =
        RandomAccessFile(db, "r").use { f ->
            if (f.length() < 64) return 0
            f.seek(60)
            f.readInt()
        }
}
