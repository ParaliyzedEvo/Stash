package com.stash.core.data.sync.workers

import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.stash.core.data.audio.AudioDurationExtractor
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.mapper.toDomain
import com.stash.core.data.sync.SyncNotificationManager
import com.stash.core.data.sync.TrackDownloadOutcome
import com.stash.core.data.sync.TrackDownloader
import com.stash.core.data.sync.workers.StashMixRefreshWorker
import com.stash.core.model.DownloadFailureType
import com.stash.core.model.DownloadStatus
import com.stash.core.model.Track
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File

/**
 * Drains `download_queue` rows produced by [StashDiscoveryWorker]
 * (`sync_id IS NULL`). Parallels [TrackDownloadWorker]'s per-track flow
 * (blocklist guard -> [TrackDownloader.downloadTrack] -> mark COMPLETED
 * with isDownloaded=true + filePath, or FAILED with retry accounting)
 * without the sync-history coupling that worker requires.
 *
 * Chained from the tail of [StashDiscoveryWorker.doWork] (REPLACE policy
 * on the unique work name so a rapid discovery + drain cycle doesn't
 * double-run). At the end of the drain, enqueues a one-shot
 * [StashMixRefreshWorker] so mixes re-materialize and the user sees the
 * newly-downloaded survivors without manual refresh.
 *
 * Foreground-service promotion via [getForegroundInfo] is the same
 * pattern TrackDownloadWorker uses — required for long batches that
 * outlive normal background limits.
 */
@HiltWorker
class DiscoveryDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val downloadQueueDao: DownloadQueueDao,
    private val trackDao: TrackDao,
    private val trackDownloader: TrackDownloader,
    private val audioDurationExtractor: AudioDurationExtractor,
    private val blocklistGuard: BlocklistGuard,
    private val syncNotificationManager: SyncNotificationManager,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val UNIQUE_WORK_NAME = "discovery_download"
        private const val TAG = "DiscoveryDownload"

        fun enqueueOneTime(context: Context, constraints: Constraints) {
            val work = OneTimeWorkRequestBuilder<DiscoveryDownloadWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                work,
            )
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return buildForegroundInfo("Downloading discoveries", "Preparing\u2026", progress = -1f)
    }

    /**
     * Promote to foreground. Wrapped in runCatching because the JVM unit
     * tests instantiate the worker directly (no WorkManager runtime) — a
     * raw setForeground / WorkManager.getInstance call would throw
     * IllegalStateException there. In production WorkManager invokes
     * [getForegroundInfo] separately before [doWork], so the loop-level
     * updates here are best-effort refreshes and a failed mid-run update
     * doesn't compromise the worker's primary contract (drain the queue,
     * then re-mix).
     */
    private suspend fun safeUpdateForeground(title: String, text: String, progress: Float) {
        runCatching { setForeground(buildForegroundInfo(title, text, progress)) }
            .onFailure { Log.w(TAG, "setForeground failed (likely test env or foreground-restricted): $title / $text", it) }
    }

    override suspend fun doWork(): Result {
        safeUpdateForeground("Downloading discoveries", "Preparing\u2026", progress = -1f)

        val pending = downloadQueueDao.pendingDiscoveryDownloads()
        if (pending.isEmpty()) {
            // Empty queue = nothing downloaded this run = no new playable
            // content to materialize. Chaining here was the keystone of the
            // runaway refresh→discovery→download→chainRefresh→… loop: it
            // re-fired the all-recipes mix refresh even when there was
            // nothing to surface. Terminate the chain instead.
            //
            // Recovery is preserved: a leftover/retry row from a prior crashed
            // run leaves `pending` NON-empty, so the drain loop below still
            // runs and re-chains once it completes something.
            Log.d(TAG, "no pending discovery downloads — nothing to drain; not chaining refresh")
            return Result.success()
        }

        Log.i(TAG, "draining ${pending.size} discovery download(s)")

        // Count downloads that produced NEW playable content this run. Only a
        // non-zero count is allowed to chain the mix refresh — an all-FAILED /
        // Unmatched / Deferred / blocked run surfaces nothing new, so chaining
        // would just re-spin the worker cycle for no benefit.
        var completed = 0

        for ((index, queueItem) in pending.withIndex()) {
            safeUpdateForeground(
                title = "Downloading discoveries",
                text = "${index + 1} of ${pending.size}",
                progress = (index.toFloat() / pending.size),
            )

            // v0.9.20: stamp IN_PROGRESS BEFORE invoking the downloader.
            // REPLACE policy on UNIQUE_WORK_NAME prevents concurrent instances
            // of THIS worker; the sync-side partition predicate prevents
            // TrackDownloadWorker from touching the same row. Defense-in-depth:
            // a stale IN_PROGRESS row left over from a crashed run gets reset
            // to PENDING on the next sync (the existing `resetStaleInProgress`
            // sweep in TrackDownloadWorker's startup) — that path is unchanged.
            downloadQueueDao.updateStatus(
                id = queueItem.id,
                status = DownloadStatus.IN_PROGRESS,
            )

            val trackEntity = trackDao.getById(queueItem.trackId) ?: continue

            if (blocklistGuard.isBlocked(
                    artist = trackEntity.artist,
                    title = trackEntity.title,
                    spotifyUri = null,
                    youtubeId = null,
                )) {
                Log.d(TAG, "Skipping blocked track ${trackEntity.id}")
                downloadQueueDao.deleteByTrackId(trackEntity.id)
                continue
            }

            if (trackEntity.isDownloaded && trackEntity.filePath != null) {
                downloadQueueDao.updateStatus(
                    id = queueItem.id,
                    status = DownloadStatus.COMPLETED,
                    completedAt = System.currentTimeMillis(),
                )
                // Already-on-disk → newly-COMPLETED queue row = new linkable
                // playable membership. Counts as progress so the refresh chains.
                completed++
                continue
            }

            val track = trackEntity.toDomain()
            val outcome = runCatching {
                trackDownloader.downloadTrack(track = track, preResolvedUrl = queueItem.youtubeUrl)
            }.getOrElse {
                Log.e(TAG, "downloadTrack threw for ${track.artist} - ${track.title}", it)
                TrackDownloadOutcome.Failed(error = it.message.orEmpty())
            }

            when (outcome) {
                is TrackDownloadOutcome.Success -> {
                    handleSuccess(queueItem, trackEntity, outcome)
                    // New audio on disk + COMPLETED row = real progress.
                    completed++
                }
                is TrackDownloadOutcome.Unmatched -> handleUnmatched(queueItem, track, outcome)
                is TrackDownloadOutcome.Failed -> handleFailed(queueItem, track, outcome)
                is TrackDownloadOutcome.Deferred -> {
                    // Lossless deferred — TrackDownloaderImpl already moved the row
                    // to WAITING_FOR_LOSSLESS. LosslessRetryWorker owns the
                    // re-attempt. No-op here.
                    Log.i(TAG, "Deferred (waiting for lossless): ${track.artist} - ${track.title}")
                }
            }
        }

        // Only chain when this run produced new playable content. An all-
        // FAILED / Unmatched / Deferred / blocked drain surfaces nothing new,
        // so chaining would just re-spin the worker cycle (see the keystone
        // note on the empty-queue early return above).
        if (completed > 0) {
            chainRefresh()
        } else {
            Log.d(TAG, "drained ${pending.size} row(s) but completed 0 — not chaining refresh")
        }
        return Result.success()
    }

    private suspend fun handleSuccess(
        queueItem: DownloadQueueEntity,
        trackEntity: TrackEntity,
        outcome: TrackDownloadOutcome.Success,
    ) {
        val fileSize = try { File(outcome.filePath).length() } catch (_: Exception) { 0L }
        val meta = audioDurationExtractor.extract(outcome.filePath)

        trackDao.markAsDownloaded(
            trackId = trackEntity.id,
            filePath = outcome.filePath,
            fileSizeBytes = fileSize,
            sampleRateHz = meta?.sampleRateHz,
            bitsPerSample = meta?.bitsPerSample,
        )

        if (meta != null && meta.format != "unknown") {
            runCatching {
                trackDao.setFormatAndQuality(
                    trackId = trackEntity.id,
                    fileFormat = meta.format,
                    qualityKbps = meta.bitrateKbps,
                )
            }
        }

        // v0.9.21: Discovery downloads start as stubs with duration_ms=0
        // (no Spotify metadata to seed from). Without this fill, every
        // discovery track shows 0:00 in playlist UI and contributes 0 to
        // the header's total-length sum. The sync path's TrackDownloadWorker
        // has its own duration update; this is the discovery equivalent.
        if (meta != null && meta.durationMs > 0) {
            runCatching { trackDao.fillMissingDuration(trackEntity.id, meta.durationMs) }
                .onFailure { Log.w(TAG, "fillMissingDuration failed for ${trackEntity.id}", it) }
        }

        downloadQueueDao.updateStatus(
            id = queueItem.id,
            status = DownloadStatus.COMPLETED,
            completedAt = System.currentTimeMillis(),
        )
    }

    private suspend fun handleUnmatched(
        queueItem: DownloadQueueEntity,
        track: Track,
        outcome: TrackDownloadOutcome.Unmatched,
    ) {
        val err = "No YouTube match for: ${track.artist} - ${track.title}"
        Log.w(TAG, err)
        downloadQueueDao.incrementRetryCount(queueItem.id)
        downloadQueueDao.updateStatus(
            id = queueItem.id,
            status = DownloadStatus.FAILED,
            failureType = DownloadFailureType.NO_MATCH,
            errorMessage = err,
            rejectedVideoId = outcome.rejectedVideoId,
        )
    }

    private suspend fun handleFailed(
        queueItem: DownloadQueueEntity,
        track: Track,
        outcome: TrackDownloadOutcome.Failed,
    ) {
        Log.e(TAG, "Download failed for ${track.artist} - ${track.title}: ${outcome.error}")
        downloadQueueDao.incrementRetryCount(queueItem.id)
        downloadQueueDao.updateStatus(
            id = queueItem.id,
            status = DownloadStatus.FAILED,
            failureType = DownloadFailureType.UNKNOWN,
            errorMessage = outcome.error.take(500),
        )
    }

    /**
     * Enqueue the all-recipes mix refresh so newly-downloaded survivors
     * surface without a manual refresh.
     *
     * IMPORTANT: callers MUST gate this on real download progress (a
     * non-empty queue that completed at least one row). Firing it on an
     * idle/no-op run re-spun the runaway refresh→discovery→download→
     * chainRefresh→… worker cycle that re-materialized every mix every
     * ~45s. See the gating in [doWork].
     *
     * runCatching guards JVM unit tests where WorkManager isn't
     * initialized; production paths always succeed. `internal` (not
     * `private`) only so the worker-loop convergence tests can verify it
     * via MockK spyk — body/behaviour is otherwise unchanged.
     */
    internal fun chainRefresh() {
        runCatching { StashMixRefreshWorker.enqueueOneTime(applicationContext) }
            .onFailure { Log.w(TAG, "mix refresh chain failed \u2014 mixes may show stale content until next refresh", it) }
    }

    /**
     * Inline mirror of [TrackDownloadWorker]'s private `createForegroundInfo`
     * helper. Duplicated rather than promoted to a public method on
     * [SyncNotificationManager] because the WorkManager cancel intent is
     * per-worker-instance and SyncNotificationManager is a singleton.
     */
    private fun buildForegroundInfo(
        title: String,
        text: String,
        progress: Float,
    ): ForegroundInfo {
        val notification = syncNotificationManager.buildProgressNotification(
            title = title,
            text = text,
            progress = progress,
            cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
        )
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(
                SyncNotificationManager.NOTIFICATION_ID_PROGRESS,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(SyncNotificationManager.NOTIFICATION_ID_PROGRESS, notification)
        }
    }
}
