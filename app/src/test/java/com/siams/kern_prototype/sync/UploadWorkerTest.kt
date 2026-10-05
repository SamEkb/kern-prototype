package com.siams.kern_prototype.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.siams.kern_prototype.data.db.DatabaseGate
import com.siams.kern_prototype.data.db.KernDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Ошибка открытия БД: воркер отправки не работает с недоступной БД — откладывает задачу. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UploadWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun unavailableDatabaseDefersUploadWithoutTouchingData() = runBlocking {
        val gate = DatabaseGate(context) { error("Файл базы данных повреждён (тест)") }
        var touched = false
        val worker = TestListenableWorkerBuilder<UploadWorker>(context)
            .setInputData(workDataOf(UploadWorker.KEY_DRILLING_ID to "d1"))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, params: WorkerParameters): ListenableWorker =
                    UploadWorker(appContext, params, gate,
                        { touched = true; error("отправка не должна создаваться") },
                        { touched = true; error("репозиторий не должен создаваться") })
            })
            .build()
        assertEquals(ListenableWorker.Result.retry(), worker.doWork())
        assertFalse("данные и отправка не затронуты", touched)
    }

    /** Старт приложения: сверка отправок не выполняется при недоступной БД. */
    @Test
    fun startupReconcileIsSkippedWhenDatabaseUnavailable() = runBlocking {
        var reconciled = false
        val ran = com.siams.kern_prototype.reconcileIfDatabaseReady(DatabaseGate(context) { error("повреждён") }) { reconciled = true }
        assertFalse(ran)
        assertFalse(reconciled)
    }
}
