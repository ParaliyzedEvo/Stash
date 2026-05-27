package com.stash.data.download

import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.sync.TrackDownloadOutcome
import com.stash.core.data.sync.TrackDownloader
import com.stash.core.data.sync.TrackDownloadProgress
import com.stash.core.model.DownloadStatus
import com.stash.core.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Concrete implementation of [TrackDownloader] that delegates to [DownloadManager].
 *
 * Bound into the Hilt graph via [di.DownloadModule] so that any component
 * depending on the [TrackDownloader] interface (e.g. TrackDownloadWorker in
 * `:core:data`) receives this implementation without a circular module dependency.
 */
@Singleton
class TrackDownloaderImpl @Inject constructor(
    private val downloadManager: DownloadManager,
    private val downloadQueueDao: DownloadQueueDao,
) : TrackDownloader {

    override val progressFlow: Flow<TrackDownloadProgress> =
        downloadManager.progress.map {
            TrackDownloadProgress(
                trackId = it.trackId,
                progress = it.progress,
                status = it.status.name,
            )
        }


    override suspend fun downloadTrack(track: Track, preResolvedUrl: String?): TrackDownloadOutcome {
        return when (val result = downloadManager.downloadTrack(track, preResolvedUrl)) {
            is TrackDownloadResult.Success -> TrackDownloadOutcome.Success(result.filePath)
            is TrackDownloadResult.Unmatched -> TrackDownloadOutcome.Unmatched(result.rejectedVideoId)
            is TrackDownloadResult.Failed -> TrackDownloadOutcome.Failed(result.error)
            // v0.9.17: Deferred is the strict-FLAC stay-in-queue signal.
            // Translate to a DownloadStatus.WAITING_FOR_LOSSLESS DAO write so
            // the row persists in the queue (out of the retryable-FAILED set)
            // until the LosslessRetryScheduler re-resolves it. The benign
            // [TrackDownloadOutcome.Deferred] outcome tells [TrackDownloadWorker]
            // not to increment retries, not to mark FAILED, and not to fail
            // the worker chain.
            TrackDownloadResult.Deferred -> {
                val queueEntry = downloadQueueDao.getByTrackId(track.id)
                queueEntry?.let {
                    downloadQueueDao.updateStatus(
                        id = it.id,
                        status = DownloadStatus.WAITING_FOR_LOSSLESS,
                    )
                }
                TrackDownloadOutcome.Deferred
            }
        }
    }
}
