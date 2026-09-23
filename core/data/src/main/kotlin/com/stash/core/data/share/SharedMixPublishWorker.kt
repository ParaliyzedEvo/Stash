package com.stash.core.data.share

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.stash.core.data.db.dao.SharedMixDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/** Republishes owned shared mixes whose content changed (spec §5). Quiet: failures retry with backoff. */
@HiltWorker
class SharedMixPublishWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val sharedMixDao: SharedMixDao,
    private val repository: SharedMixRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        var retry = false
        for (row in sharedMixDao.activeOwnedWithUpdates()) {
            if (isStopped) break
            // Only transient failures retry; Rejected/Forbidden would fail identically forever.
            if (repository.publishIfChanged(row) == PublishOutcome.Failed) retry = true
        }
        return if (retry) Result.retry() else Result.success()
    }

    companion object {
        private const val WORK_NAME = "stash_shared_mix_publish"

        /** After a sync ([delaySeconds] 0) or ~30 s after a local edit, so a burst of edits becomes one publish. */
        fun enqueue(context: Context, delaySeconds: Long = 0) {
            val work = OneTimeWorkRequestBuilder<SharedMixPublishWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, work)
        }
    }
}
