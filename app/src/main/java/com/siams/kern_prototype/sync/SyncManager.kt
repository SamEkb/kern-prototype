package com.siams.kern_prototype.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.await
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.siams.kern_prototype.data.KernRepository
import com.siams.kern_prototype.domain.model.SyncStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Постановка долблений в очередь отправки (§9). Одна уникальная задача на долбление,
 * constraints = сеть подключена, экспоненциальные ретраи.
 */
@Singleton
class SyncManager @Inject constructor(
    @ApplicationContext context: Context,
    private val repository: KernRepository,
) {
    private val workManager = WorkManager.getInstance(context)

    fun enqueue(drillingId: String): Operation {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setInputData(workDataOf(UploadWorker.KEY_DRILLING_ID to drillingId))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()

        // R05: KEEP — если отправка этого долбления уже в очереди или идёт, вторая не запускается
        // и не прерывает первую (REPLACE отменял бы работающую задачу посреди загрузки). Правки,
        // сделанные тем временем, отсекаются условным SENT, и задача повторяется.
        enqueueForTest?.let { return it(drillingId) }
        return workManager.enqueueUniqueWork(
            uniqueName(drillingId),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Есть ли для долбления незавершённая задача отправки (в очереди, ожидает, выполняется).
     */
    suspend fun hasActiveWork(drillingId: String): Boolean =
        workManager.getWorkInfosForUniqueWorkFlow(uniqueName(drillingId)).first()
            .any { !it.state.isFinished }

    /**
     * Поставить долбления в отправку: статус SENDING + задача WorkManager. Под общим с
     * [reconcileStuck] замком, и замок держится до ФАКТИЧЕСКОГО сохранения задачи
     * (постановка WorkManager асинхронна — дожидаемся её [Operation]). Неуспешная постановка —
     * долбление выходит из SENDING в ERROR (иначе пересъёмка/удаление были бы заблокированы).
     *
     * Отмена вызывающего не прерывает уже начатую постановку: её исход неизвестен, пока WorkManager
     * не закончит, поэтому она доводится до конца под замком ([NonCancellable]; Future постановки
     * не отменяется). Лишь затем отмена передаётся дальше; остальные долбления не ставятся.
     */
    suspend fun send(ids: List<String>) = statusLock.withLock {
        for (id in ids) {
            withContext(NonCancellable) {
                repository.setSyncStatus(id, SyncStatus.SENDING, null)
                try {
                    enqueue(id).await()
                } catch (e: Throwable) {
                    repository.failIfSending(id, "Не удалось поставить отправку в очередь: ${e.message ?: e}")
                }
            }
            currentCoroutineContext().ensureActive()
        }
    }

    /**
     * R05: долбление в SENDING без активной задачи (задачу потеряли/отменили) блокировало бы
     * пересъёмку и удаление навсегда. Такие отправки помечаются ERROR — их можно отправить снова.
     * Под общим с [send] замком (постановка не вклинится между проверкой задачи и записью), а
     * ERROR пишется только если долбление ВСЁ ЕЩЁ в SENDING (задача могла успеть завершиться).
     */
    suspend fun reconcileStuck() = statusLock.withLock {
        repository.listDrillingIds(SyncStatus.SENDING).forEach { id ->
            val active = hasActiveWork(id)
            afterWorkCheckForTest?.invoke()
            if (!active) repository.failIfSending(id, "Отправка была прервана — отправьте повторно")
        }
    }

    /** Общий замок постановки в отправку и сверки зависших задач. */
    private val statusLock = Mutex()

    /** Точка для теста: подмена постановки задачи (задержка/сбой сохранения); в приложении null. */
    @Volatile
    internal var enqueueForTest: ((String) -> Operation)? = null

    /** Точка для теста гонки «сверка ↔ постановка»; в приложении null. */
    @Volatile
    internal var afterWorkCheckForTest: (suspend () -> Unit)? = null


    private fun uniqueName(drillingId: String) = "upload-$drillingId"

    companion object {
        const val TAG = "drilling-upload"
    }
}
