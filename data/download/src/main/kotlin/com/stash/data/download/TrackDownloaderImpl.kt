package com.stash.data.download

import android.util.Log
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.files.LocalFileOps
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
    private val localFileOps: LocalFileOps,
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
            is TrackDownloadResult.Success -> {
                // Validate the committed file before it can be marked downloaded.
                // A "successful" download can still be junk — e.g. yt-dlp writing
                // a ~274-byte error body into a .webm. If that were marked
                // isDownloaded it would later fail playback with
                // ERROR_CODE_PARSING_CONTAINER_MALFORMED and skip-storm the queue.
                // acceptDownloadOrDelete deletes the junk + returns false, so the
                // track stays not-downloaded (and therefore streamable); the
                // worker routes Failed through its normal bounded-retry handling.
                if (localFileOps.acceptDownloadOrDelete(result.filePath)) {
                    TrackDownloadOutcome.Success(result.filePath)
                } else {
                    Log.w(TAG, "discarded too-small download for '${track.artist} - ${track.title}': ${result.filePath}")
                    TrackDownloadOutcome.Failed("Downloaded file too small — discarded as a failed download")
                }
            }
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

    private companion object {
        const val TAG = "TrackDownloader"
    }
}
