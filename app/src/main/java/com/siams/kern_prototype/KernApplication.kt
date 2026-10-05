package com.siams.kern_prototype

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.siams.kern_prototype.sync.SyncManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Точка входа приложения. Инициализирует Hilt и настраивает WorkManager на
 * [HiltWorkerFactory], чтобы фоновые задачи отправки (§9) могли инжектить
 * зависимости. Дефолтный инициализатор WorkManager отключён в манифесте.
 */
@HiltAndroidApp
class KernApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    // Зависит от БД — лениво: при недоступной БД не создаётся (старт не падает).
    @Inject lateinit var syncManager: dagger.Lazy<SyncManager>
    @Inject lateinit var databaseGate: com.siams.kern_prototype.data.db.DatabaseGate
    @Inject lateinit var serverSettings: com.siams.kern_prototype.data.settings.ServerSettingsStore

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // R05: снять «зависшие» SENDING без активной задачи отправки (до действий пользователя).
        // Согласовано с постановкой в отправку общим замком SyncManager (не «до действий пользователя»).
        appScope.launch { reconcileIfDatabaseReady(databaseGate) { syncManager.get().reconcileStuck() } }
        // R20: открытый пароль прежней версии → зашифрованное хранилище (до переноса он продолжает работать).
        appScope.launch {
            runCatching { serverSettings.migrateLegacyPassword() }
                .onFailure { android.util.Log.w("KernApplication", "Перенос пароля не выполнен: ${it.message}") }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}

/**
 * Сверка «зависших» отправок при старте — только с доступной БД; иначе пропускается (экран ошибки
 * БД покажет причину). Возвращает true, если сверка выполнялась.
 */
internal suspend fun reconcileIfDatabaseReady(
    gate: com.siams.kern_prototype.data.db.DatabaseGate,
    reconcile: suspend () -> Unit,
): Boolean {
    if (gate.open() !is com.siams.kern_prototype.data.db.DatabaseGate.State.Ready) {
        runCatching { android.util.Log.w("KernApplication", "База данных недоступна — сверка отправок пропущена") }
        return false
    }
    runCatching { reconcile() }
    return true
}
