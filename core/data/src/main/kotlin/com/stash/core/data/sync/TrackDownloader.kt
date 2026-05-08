package com.stash.core.data.sync

import com.stash.core.model.Track

/**
 * Result of downloading a single track through the pipeline.
 */
sealed class TrackDownloadOutcome {
    /** Download succeeded. [filePath] is the absolute path on disk. */
    data class Success(val filePath: String) : TrackDownloadOutcome()

    /** No YouTube match found for the track. [rejectedVideoId] is the best candidate that failed verification. */
    data class Unmatched(val rejectedVideoId: String? = null) : TrackDownloadOutcome()

    /** Download failed. [error] describes exactly what went wrong. */
    data class Failed(val error: String) : TrackDownloadOutcome()

    /**
     * v0.9.17 — strict-FLAC stay-in-queue signal. The lossless registry
     * had no source for this track and yt-dlp fallback is disabled, so
     * the track was NOT downloaded but is NOT a retryable failure either.
     * The DAO row is already at [com.stash.core.model.DownloadStatus.WAITING_FOR_LOSSLESS]
     * by the time the worker sees this — the worker must NOT increment
     * retry count, NOT mark FAILED, and return [androidx.work.ListenableWorker.Result.success]
     * so WorkManager doesn't reschedule. The reactive triggers in
     * `LosslessRetryScheduler` (Task 9) own the re-attempt signal.
     */
    data object Deferred : TrackDownloadOutcome()
}

/**
 * Abstraction over the download pipeline used by [workers.TrackDownloadWorker].
 *
 * The interface lives in `:core:data` so the worker can depend on it without
 * introducing a circular dependency on `:data:download`. The concrete
 * implementation ([com.stash.data.download.TrackDownloaderImpl]) is provided
 * by Hilt from the `:data:download` module.
 */
interface TrackDownloader {

    /**
     * Downloads a single track through the full pipeline (search, download,
     * metadata embed, file organization).
     *
     * @param track          The track to download.
     * @param preResolvedUrl Optional YouTube URL if already resolved (skips search).
     * @return A [TrackDownloadOutcome] with the file path or a detailed error message.
     */
    suspend fun downloadTrack(track: Track, preResolvedUrl: String? = null): TrackDownloadOutcome
}
