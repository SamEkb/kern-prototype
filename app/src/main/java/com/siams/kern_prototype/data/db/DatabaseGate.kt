package com.siams.kern_prototype.data.db

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Единственная точка открытия БД. Не зависит от графа БД: экран ошибки, старт приложения и воркеры
 * спрашивают её прежде, чем получать что-либо, что держит БД.
 *
 * [open] один раз за процесс: копия перед обновлением ([DatabaseBackup]), сборка Room и
 * ПРИНУДИТЕЛЬНОЕ открытие (миграции, проверка схемы). Итог — [State.Ready] или [State.Failed] с
 * понятной причиной; исключения наружу не выходят. При отказе БД не подменяется и не создаётся
 * пустая: файлы остаются как есть (в т.ч. при повреждении — см. [KeepFilesOnCorruption]).
 */
@Singleton
class DatabaseGate internal constructor(
    private val context: Context,
    private val build: (Context) -> KernDatabase,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context, ::buildDatabase)

    sealed interface State {
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private var state: State? = null
    private var db: KernDatabase? = null

    @Synchronized
    fun open(): State {
        state?.let { return it }
        val result: State = try {
            val d = build(context)
            try {
                d.openHelper.writableDatabase // миграции и проверка схемы — сейчас, а не при первом запросе
                db = d
                State.Ready
            } catch (t: Throwable) {
                runCatching { d.close() }
                throw t
            }
        } catch (t: Throwable) {
            runCatching { Log.e(TAG, "База данных недоступна", t) }
            State.Failed(t.message ?: t.javaClass.simpleName)
        }
        state = result
        return result
    }

    /** БД, если открыта; иначе исключение (недоступная БД никогда не выдаётся). */
    fun requireDatabase(): KernDatabase {
        open()
        return db ?: throw IllegalStateException(
            "База данных недоступна: ${(state as? State.Failed)?.message ?: "не открыта"}",
        )
    }

    /** [block] с открытой БД; недоступна — null, без исключения. */
    fun <T> whenReady(block: (KernDatabase) -> T): T? = if (open() is State.Ready) block(db!!) else null

    @androidx.annotation.VisibleForTesting
    internal fun closeForTest() = synchronized(this) { runCatching { db?.close() } }

    /**
     * Колбэк открытия, который при повреждении НЕ удаляет файлы (по умолчанию помощник SQLite
     * удаляет БД в onCorruption), а прерывает открытие.
     */
    private class KeepFilesOnCorruption(private val delegate: SupportSQLiteOpenHelper.Callback) :
        SupportSQLiteOpenHelper.Callback(delegate.version) {
        override fun onConfigure(db: SupportSQLiteDatabase) = delegate.onConfigure(db)
        override fun onCreate(db: SupportSQLiteDatabase) = delegate.onCreate(db)
        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = delegate.onUpgrade(db, oldVersion, newVersion)
        override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = delegate.onDowngrade(db, oldVersion, newVersion)
        override fun onOpen(db: SupportSQLiteDatabase) = delegate.onOpen(db)
        override fun onCorruption(db: SupportSQLiteDatabase) {
            runCatching { Log.e(TAG, "Обнаружено повреждение БД ${db.path} — файлы не удаляются") }
            throw IllegalStateException(
                "Файл базы данных повреждён — открытие остановлено, данные не изменены. " +
                    "Не удаляйте приложение: файл можно восстановить или передать для анализа.",
            )
        }
    }

    companion object {
        private const val TAG = "DatabaseGate"

        /** Сборка Room: копия перед обновлением (R07), явные миграции, без разрушительного fallback. */
        fun buildDatabase(context: Context): KernDatabase {
            DatabaseBackup.prepareForOpen(context, KernDatabase.NAME, KernDatabase.VERSION)
            val frameworkFactory = FrameworkSQLiteOpenHelperFactory()
            return Room.databaseBuilder(context, KernDatabase::class.java, KernDatabase.NAME)
                .addMigrations(*KernMigrations.ALL)
                .openHelperFactory { config ->
                    frameworkFactory.create(
                        SupportSQLiteOpenHelper.Configuration.builder(config.context)
                            .name(config.name)
                            .callback(KeepFilesOnCorruption(config.callback))
                            .noBackupDirectory(config.useNoBackupDirectory)
                            .allowDataLossOnRecovery(false)
                            .build(),
                    )
                }
                .build()
        }
    }
}
