package com.siams.kern_prototype.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
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
) {
    private val workManager = WorkManager.getInstance(context)

    fun enqueue(drillingId: String) {
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

        workManager.enqueueUniqueWork(
            uniqueName(drillingId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun enqueueAll(drillingIds: List<String>) = drillingIds.forEach(::enqueue)

    private fun uniqueName(drillingId: String) = "upload-$drillingId"

    companion object {
        const val TAG = "drilling-upload"
    }
}
