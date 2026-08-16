package com.stash.core.data.sync

import android.content.Context
import androidx.work.WorkManager
import com.stash.core.data.db.dao.DownloadQueueDao
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Makes cancellation durable before stopping any best-effort runtime work. */
@Singleton
class DownloadCancellationController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadQueueDao: DownloadQueueDao,
    private val jobs: DownloadJobRegistry,
) {
    suspend fun cancel(queueId: Long): Boolean {
        val changed = downloadQueueDao.markCancelledIfActive(queueId, System.currentTimeMillis())
        if (changed == 0) return false
        jobs.cancel(queueId)
        WorkManager.getInstance(context)
            .cancelUniqueWork(SingleTrackDownloadEnqueuer.workName(queueId))
        return true
    }
}
