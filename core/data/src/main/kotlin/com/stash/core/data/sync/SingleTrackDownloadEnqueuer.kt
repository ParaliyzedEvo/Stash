package com.stash.core.data.sync

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.stash.core.data.prefs.DownloadNetworkPreference
import com.stash.core.data.sync.workers.TrackDownloadWorker
import com.stash.core.data.sync.workers.constraintsForManualTrigger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enqueues a one-shot [TrackDownloadWorker] run for a single
 * `download_queue` row. Used by the Failed Downloads viewer to
 * immediately retry a row the user tapped, without waiting for the
 * next scheduled sync.
 *
 * Uses a unique work name keyed by `queueId` so a second tap on the
 * same row coalesces with the in-flight retry instead of queueing two.
 *
 * Concurrency for "Retry all" / "Retry group" comes from the caller
 * fanning out N independent enqueue calls — each one runs its own
 * `TrackDownloadWorker` instance. WorkManager's per-worker queue +
 * the OS scheduler cap concurrency naturally; no semaphore needed
 * here.
 *
 * Constraints come from [constraintsForManualTrigger] — same helper
 * every other manual-trigger enqueuer in this module uses (sync chain,
 * `DiscoveryDownloadWorker`, `StashMixRefreshWorker`). Without
 * constraints, WorkManager would fire the worker on a dead battery /
 * no-network, the download would fail immediately inside
 * `TrackDownloadWorker`, and the user would see the row flip back to
 * FAILED with a confusing reason.
 *
 * `enqueue` is `suspend` because reading the current
 * [com.stash.core.model.DownloadNetworkMode] from
 * [DownloadNetworkPreference] is a suspending DataStore read. The
 * eventual caller (`FailedDownloadsViewModel.retry` in Task 16) runs
 * in a `viewModelScope.launch { ... }`, so suspending is natural.
 */
@Singleton
class SingleTrackDownloadEnqueuer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadNetworkPreference: DownloadNetworkPreference,
) {
    suspend fun enqueue(queueId: Long) {
        val mode = downloadNetworkPreference.current()
        val request = OneTimeWorkRequestBuilder<TrackDownloadWorker>()
            .setInputData(
                Data.Builder()
                    .putLong(TrackDownloadWorker.KEY_QUEUE_ID, queueId)
                    .build()
            )
            .setConstraints(constraintsForManualTrigger(mode))
            .addTag("single_track_retry")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "single_track_$queueId",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
