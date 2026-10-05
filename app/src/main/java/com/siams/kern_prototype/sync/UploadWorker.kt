package com.siams.kern_prototype.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.siams.kern_prototype.data.KernRepository
import com.siams.kern_prototype.data.db.DatabaseGate
import com.siams.kern_prototype.domain.model.SyncStatus
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * Фоновая задача отправки одного долбления (§9). Экспоненциальные ретраи —
 * настраиваются в [SyncManager]. Статусы пишутся в Room для отображения в UI.
 */
@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val databaseGate: DatabaseGate,
    // Зависимости БД — лениво: при недоступной БД они не создаются.
    private val uploaderLazy: dagger.Lazy<DrillingUploader>,
    private val repositoryLazy: dagger.Lazy<KernRepository>,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val drillingId = inputData.getString(KEY_DRILLING_ID) ?: return Result.failure()
        // БД недоступна — работу с данными не начинаем, задачу откладываем (данные не трогаются).
        if (databaseGate.open() !is DatabaseGate.State.Ready) return Result.retry()
        val uploader = uploaderLazy.get()
        val repository = repositoryLazy.get()
        return try {
            uploader.upload(drillingId)
            Result.success()
        } catch (e: CancellationException) {
            // Остановка задачи (система, ограничения) — не ошибка отправки: статус не трогаем,
            // WorkManager перезапустит задачу; незавершённость отсечёт условный SENT.
            throw e
        } catch (e: UploadRejectedException) {
            // Повтор не поможет (повреждён архив, расхождение с сервером) — сразу ERROR.
            repository.setSyncStatus(drillingId, SyncStatus.ERROR, e.message)
            Result.failure()
        } catch (t: Throwable) {
            val giveUp = runAttemptCount + 1 >= MAX_ATTEMPTS
            repository.setSyncStatus(
                drillingId,
                if (giveUp) SyncStatus.ERROR else SyncStatus.SENDING,
                t.message,
            )
            if (giveUp) Result.failure() else Result.retry()
        }
    }

    companion object {
        const val KEY_DRILLING_ID = "drilling_id"
        const val MAX_ATTEMPTS = 5
    }
}
