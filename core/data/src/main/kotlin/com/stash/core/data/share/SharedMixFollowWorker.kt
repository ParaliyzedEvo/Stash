package com.stash.core.data.share

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.stash.core.data.db.dao.SharedMixDao
import com.stash.core.data.db.entity.SharedMixEntity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/** Checks followed mixes for new versions (spec §6): after each sync (forced), and on app start at most every 6 h. */
@HiltWorker
class SharedMixFollowWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val sharedMixDao: SharedMixDao,
    private val repository: SharedMixRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val force = inputData.getBoolean(KEY_FORCE, false)
        val now = System.currentTimeMillis()
        for (row in sharedMixDao.activeFollowed()) {
            if (isStopped) break
            if (!isDue(row, now, force)) continue
            try {
                repository.checkForUpdate(row, now)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "check failed for ${row.shareId}", e) // one bad mix never stops the rest
            }
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "SharedMix"
        private const val WORK_NAME = "stash_shared_mix_follow"
        private const val KEY_FORCE = "force"
        private const val INTERVAL_MS = 6 * 3600_000L

        fun isDue(row: SharedMixEntity, now: Long, force: Boolean): Boolean =
            force || row.lastCheckedAt == null || now - row.lastCheckedAt >= INTERVAL_MS

        fun enqueue(context: Context, force: Boolean) {
            val work = OneTimeWorkRequestBuilder<SharedMixFollowWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(KEY_FORCE to force))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME, if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, work,
            )
        }
    }
}
