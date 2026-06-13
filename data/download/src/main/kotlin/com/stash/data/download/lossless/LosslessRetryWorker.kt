package com.stash.data.download.lossless

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.model.DownloadStatus
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * One-shot sweep over rows in [DownloadStatus.WAITING_FOR_LOSSLESS]. For
 * each, calls the lossless [LosslessSourceRegistry]; on success flips the
 * row to [DownloadStatus.PENDING] so the standard
 * [com.stash.core.data.sync.workers.TrackDownloadWorker] picks it up, on
 * failure leaves the row alone for the next trigger.
 *
 * Does not download — it only re-resolves and re-queues. Never writes
 * COMPLETED or FAILED; the standard worker chain owns the actual download
 * once status is PENDING.
 *
 * Enqueued by `LosslessRetryScheduler` (Task 9) under a unique work name
 * so multiple triggers within a short window collapse to a single sweep.
 * Uses the standard download Constraints (network mode pref) so it
 * doesn't fire on metered if the user disabled that.
 *
 * Lives in `:data:download` rather than `:core:data` because
 * [LosslessSourceRegistry] is module-local to `:data:download`, and the
 * dependency graph runs `:data:download` -> `:core:data` (not the other
 * way). Putting the worker in `:core:data` would require a circular
 * module dependency.
 */
@HiltWorker
class LosslessRetryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val downloadQueueDao: DownloadQueueDao,
    private val trackDao: TrackDao,
    private val registry: LosslessSourceRegistry,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val deferred = downloadQueueDao.waitingForLosslessTracks()
        if (deferred.isEmpty()) {
            return Result.success(
                workDataOf(
                    KEY_RESOLVED to 0,
                    KEY_TOTAL to 0,
                ),
            )
        }

        var resolvedCount = 0
        for (entry in deferred) {
            val track = trackDao.getById(entry.trackId) ?: continue
            // runCatching mirrors DownloadManager's defensive pattern:
            // a single bad source / network blip mustn't halt the whole
            // sweep. Sources are also expected to swallow their own
            // errors and return null, but defense in depth is cheap.
            val match = runCatching {
                registry.resolve(
                    TrackQuery(
                        artist = track.artist,
                        title = track.title,
                        album = track.album.takeIf { it.isNotBlank() },
                        isrc = track.isrc,
                        durationMs = track.durationMs.takeIf { it > 0 },
                        spotifyUri = track.spotifyUri,
                    ),
                )
            }.getOrNull()
            if (match != null) {
                downloadQueueDao.updateStatus(
                    id = entry.id,
                    status = DownloadStatus.PENDING,
                )
                resolvedCount++
            }
        }
        return Result.success(
            workDataOf(
                KEY_RESOLVED to resolvedCount,
                KEY_TOTAL to deferred.size,
            ),
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "lossless-retry"

        /** Output-data key: how many WAITING_FOR_LOSSLESS rows were flipped to PENDING this sweep. */
        const val KEY_RESOLVED = "lossless_retry_resolved"

        /** Output-data key: how many WAITING_FOR_LOSSLESS rows existed when the sweep started. */
        const val KEY_TOTAL = "lossless_retry_total"
    }
}
