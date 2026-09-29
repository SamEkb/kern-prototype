package com.siams.kern_prototype.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.siams.kern_prototype.data.KernRepository
import com.siams.kern_prototype.domain.model.SyncStatus
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Фоновая задача отправки одного долбления (§9). Экспоненциальные ретраи —
 * настраиваются в [SyncManager]. Статусы пишутся в Room для отображения в UI.
 */
@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val uploader: DrillingUploader,
    private val repository: KernRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val drillingId = inputData.getString(KEY_DRILLING_ID) ?: return Result.failure()
        return try {
            uploader.upload(drillingId)
            Result.success()
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
