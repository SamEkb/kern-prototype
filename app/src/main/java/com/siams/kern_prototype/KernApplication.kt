package com.siams.kern_prototype

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Точка входа приложения. Инициализирует Hilt и настраивает WorkManager на
 * [HiltWorkerFactory], чтобы фоновые задачи отправки (§9) могли инжектить
 * зависимости. Дефолтный инициализатор WorkManager отключён в манифесте.
 */
@HiltAndroidApp
class KernApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
