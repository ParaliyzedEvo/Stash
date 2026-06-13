package com.stash.data.download.search

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.SimpleCache
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.sync.SyncNotificationManager
import com.stash.core.model.DownloadStatus
import com.stash.core.model.TrackItem
import com.stash.data.download.DownloadExecutor
import com.stash.data.download.DownloadResult
import com.stash.data.download.lossless.AudioFormat
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.LosslessSourceRegistry
import com.stash.data.download.lossless.SourceResult
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lyrics.LyricsFetchTrigger
import com.stash.data.download.shared.TrackFinalizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Replaces the direct [DownloadExecutor.download] call in
 * [com.stash.core.media.actions.TrackActionsDelegate.downloadTrack].
 *
 * Workflow:
 *   1. Dedupe — if a download for this trackKey is already in flight,
 *      join it.
 *   2. Re-resolve via [LosslessSourceRegistry] (don't reuse the
 *      prefetcher's cached match — its signed URL may be stale).
 *   3. Hit (confidence >= 0.65): use CacheDataSource to read upstream
 *      bytes through the preview cache into tempFile. Any preview-cached
 *      ranges are reused; missing ranges fetched fresh. Then hand off to
 *      TrackFinalizer for embed/commit/probe; do search-specific DB writes
 *      inline (insert/update Track row, link to "Your Downloads" playlist).
 *   4. Miss: fall through to existing yt-dlp DownloadExecutor.
 *
 * Note on [CacheKeyFactory]: this coordinator lives in `:data:download`,
 * which cannot depend on `:core:media` (circular — `:core:media` already
 * depends on `:data:download`). The concrete [com.stash.core.media.preview.TrackKeyCacheKeyFactory]
 * is therefore injected through the interface, bound in
 * `core/media/.../di/PreviewCacheModule.provideCacheKeyFactory()`.
 */
@Singleton
class SearchDownloadCoordinator @Inject constructor(
    private val registry: LosslessSourceRegistry,
    private val previewCache: SimpleCache,
    private val httpDataSourceFactory: HttpDataSource.Factory,
    /** Resolved at runtime to TrackKeyCacheKeyFactory via PreviewCacheModule. */
    private val cacheKeyFactory: CacheKeyFactory,
    private val downloadExecutor: DownloadExecutor,
    private val trackFinalizer: TrackFinalizer,
    private val trackDao: com.stash.core.data.db.dao.TrackDao,
    private val musicRepository: com.stash.core.data.repository.MusicRepository,
    private val blocklistGuard: com.stash.core.data.blocklist.BlocklistGuard,
    @ApplicationContext private val context: Context,
    /**
     * v0.9.17: Used by the strict-FLAC defer branch. When the lossless
     * registry can't serve the track and the user has yt-dlp fallback off,
     * the coordinator emits [SearchDownloadStatus.WaitingForLossless] and
     * marks the queue row [DownloadStatus.WAITING_FOR_LOSSLESS] instead
     * of falling through to yt-dlp.
     */
    private val losslessPrefs: LosslessSourcePreferences,
    private val downloadQueueDao: DownloadQueueDao,
    private val localFileOps: com.stash.core.data.files.LocalFileOps,
    private val loudnessMeasurer: com.stash.core.data.audio.LoudnessMeasurer,
    /**
     * v0.9.36: enqueue a [com.stash.data.lyrics.worker.LyricsFetchWorker]
     * after a successful finalize on either branch. Interface lives in
     * `:data:download` and the production binding in `:app`, mirroring
     * the [com.stash.data.download.DownloadManager] hookup — see
     * [LyricsFetchTrigger] for the cyclic-dep rationale.
     */
    private val lyricsFetchTrigger: LyricsFetchTrigger,
    private val syncNotificationManager: SyncNotificationManager,
) {
    // App-lifetime scope. Class is @Singleton.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * In-flight download map keyed by videoId. Protected by [mutex].
     *
     * A [Deferred] is inserted before the download starts and removed in the
     * `finally` block of [download], so a concurrent caller on the same
     * videoId joins the same coroutine rather than launching a duplicate
     * network+disk operation.
     */
    private val inFlight = mutableMapOf<String, Deferred<DownloadJobResult>>()
    private val mutex = Mutex()

    /**
     * Initiates or joins an in-flight download for [track] and emits status
     * updates as a cold [Flow].
     *
     * Emitted sequence:
     *   [SearchDownloadStatus.Resolving] → [SearchDownloadStatus.Downloading] →
     *   [SearchDownloadStatus.Completed] | [SearchDownloadStatus.Failed]
     *
     * v0.9.17 strict-FLAC: when the lossless registry returns null AND
     * [LosslessSourcePreferences.youtubeFallbackEnabledNow] is false the
     * sequence shortens to:
     *   [SearchDownloadStatus.Resolving] → [SearchDownloadStatus.WaitingForLossless]
     *
     * No Stash-Mix exemption here — the search-tab pipeline is always an
     * explicit user action on a specific track, so the user's fallback
     * preference governs unconditionally.
     *
     * The flow does not throw; all errors are mapped to [SearchDownloadStatus.Failed].
     */
    fun download(track: TrackItem): Flow<SearchDownloadStatus> = flow {
        val key = track.videoId
        emit(SearchDownloadStatus.Resolving)

        val deferred = mutex.withLock {
            inFlight.getOrPut(key) { scope.async { performDownload(track) } }
        }

        try {
            when (val job = deferred.await()) {
                is DownloadJobResult.Resolved -> {
                    // Signal which source is delivering bytes now that resolution is done.
                    emit(SearchDownloadStatus.Downloading(job.source))
                    emit(
                        when (val r = job.outcome) {
                            is TrackFinalizer.FinalizeResult.Success -> SearchDownloadStatus.Completed
                            is TrackFinalizer.FinalizeResult.Failed -> SearchDownloadStatus.Failed(r.message)
                        }
                    )
                }
                is DownloadJobResult.Deferred -> {
                    emit(SearchDownloadStatus.WaitingForLossless)
                }
            }
        } finally {
            // Remove on completion OR cancellation — ensures the map never
            // holds a reference to a completed/cancelled Deferred.
            mutex.withLock { inFlight.remove(key) }
        }
    }

    // -------------------------------------------------------------------------
    // Internal: resolve + route
    // -------------------------------------------------------------------------

    private suspend fun performDownload(track: TrackItem): DownloadJobResult {
        // Master lossless switch OFF → skip the registry (and the strict-FLAC
        // defer) entirely and go straight to yt-dlp, mirroring
        // DownloadManager.executeDownload. Without this, "lossless off" still
        // resolved the registry, missed (sources down / no captcha cookie),
        // and — with fallback also off — deferred to WAITING_FOR_LOSSLESS, so
        // artist-page / search downloads hung on "waiting for lossless" forever.
        if (!losslessPrefs.enabledNow()) {
            return DownloadJobResult.Resolved(
                source = SearchDownloadStatus.Source.YOUTUBE,
                outcome = finalizeFromYtDlp(track),
            )
        }

        val match = runCatching { registry.resolve(track.toQuery()) }
            .onFailure { e ->
                Log.w(TAG, "registry.resolve threw for ${track.videoId}: ${e.message}")
            }
            .getOrNull()

        if (match != null && match.confidence >= MIN_SEARCH_CONFIDENCE) {
            return DownloadJobResult.Resolved(
                source = SearchDownloadStatus.Source.LOSSLESS,
                outcome = finalizeFromLossless(track, match),
            )
        }

        // v0.9.17 strict-FLAC defer branch — mirrors DownloadManager.
        // No Stash-Mix exemption: search-tab is always an explicit user
        // action, so the fallback pref governs unconditionally.
        if (!losslessPrefs.youtubeFallbackEnabledNow()) {
            Log.i(
                TAG,
                "deferring search download '${track.artist} - ${track.title}': lossless unavailable, fallback off",
            )
            // Mark the queue row WAITING_FOR_LOSSLESS so the retry scheduler
            // (Task 9) can pick it up later. Lookup is best-effort and
            // chained on the local Track row — the search-tab path bypasses
            // download_queue for fresh tracks, so most search defers won't
            // find a row here. The write only matters when the user re-taps
            // Download on a track the sync pipeline previously deferred.
            runCatching {
                val trackId = trackDao.findByYoutubeId(track.videoId)?.id
                if (trackId != null) {
                    downloadQueueDao.getByTrackId(trackId)?.let { row ->
                        downloadQueueDao.updateStatus(
                            id = row.id,
                            status = DownloadStatus.WAITING_FOR_LOSSLESS,
                        )
                    }
                }
            }.onFailure { e ->
                Log.w(TAG, "WAITING_FOR_LOSSLESS DAO write failed for ${track.videoId}: ${e.message}")
            }
            return DownloadJobResult.Deferred
        }

        return DownloadJobResult.Resolved(
            source = SearchDownloadStatus.Source.YOUTUBE,
            outcome = finalizeFromYtDlp(track),
        )
    }

    // -------------------------------------------------------------------------
    // Lossless path — ExoPlayer CacheDataSource → temp file → TrackFinalizer
    // -------------------------------------------------------------------------

    private suspend fun finalizeFromLossless(
        track: TrackItem,
        match: SourceResult,
    ): TrackFinalizer.FinalizeResult {
        // Cache key mirrors what SearchPreviewMediaSource uses so any bytes
        // already streamed during preview are reused here.
        val cacheKey = "lossless:${track.videoId}"
        val tempFile = File(
            context.cacheDir,
            "search_lossless_${track.videoId}.${match.format.codec}",
        )
        runCatching { tempFile.delete() }

        // CacheDataSource with FLAG_BLOCK_ON_CACHE reads cached spans first,
        // then fills missing byte ranges from upstream HTTP. Returns bytes
        // contiguously regardless of which spans the preview pre-filled.
        val dataSource = CacheDataSource.Factory()
            .setCache(previewCache)
            .setUpstreamDataSourceFactory(httpDataSourceFactory)
            .setCacheKeyFactory(cacheKeyFactory)
            .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)
            .createDataSource()

        runCatching {
            val spec = DataSpec.Builder()
                .setUri(match.downloadUrl)
                // media3 1.9.2 API: DataSpec.Builder.setKey (NOT setCustomCacheKey).
                // The CacheKeyFactory reads spec.key and returns it as the cache key.
                .setKey(cacheKey)
                .build()

            dataSource.open(spec)
            tempFile.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = dataSource.read(buf, 0, buf.size)
                    // C.RESULT_END_OF_INPUT (-1) signals EOF from the data source.
                    if (n == C.RESULT_END_OF_INPUT) break
                    out.write(buf, 0, n)
                }
            }
        }.onFailure { e ->
            runCatching { dataSource.close() }
            Log.w(TAG, "lossless cache->file copy failed for $cacheKey: ${e.message}")
            return TrackFinalizer.FinalizeResult.Failed("Cache fill failed: ${e.message}")
        }
        runCatching { dataSource.close() }

        val finalized = trackFinalizer.finalizeFile(
            sourceFile = tempFile,
            track = track.toDomainStub(),
            format = match.format,
        )

        // Search-specific DB writes — done here, not in TrackFinalizer,
        // so sync's TrackFinalizer path is unaffected. Wrapped in
        // runCatching: an exception escaping here would propagate up the
        // flow and prevent SearchDownloadStatus.Completed from emitting,
        // leaving the UI's per-row spinner stuck even though the file is
        // already on disk. The file is the load-bearing artefact; if a DB
        // write fails the next library scan / sync will reconcile.
        if (finalized is TrackFinalizer.FinalizeResult.Success) {
            runCatching {
                upsertSearchTrack(track, match.format, finalized, match.coverArtUrl)
            }.onFailure { e ->
                Log.e(TAG, "upsertSearchTrack failed for ${track.videoId}: ${e.message}", e)
            }
            // v0.9.36 lyrics integration: chain the lyrics-fetch enqueue off
            // the stamp's resolved trackId so we don't repeat findByYoutubeId.
            // If the stamp lookup failed (returned null), skip lyrics too —
            // we have no stable id to key the worker on.
            stampEmbeddedAt(track.videoId)?.let { lyricsFetchTrigger.enqueueFor(it) }
            syncNotificationManager.showDownloadCompleteNotification(track.title, track.artist)
        }

        // Free preview-cache space now that bytes are on permanent storage.
        runCatching { previewCache.removeResource(cacheKey) }
            .onFailure { e -> Log.w(TAG, "removeResource failed for $cacheKey: ${e.message}") }

        return finalized
    }

    // -------------------------------------------------------------------------
    // YouTube / yt-dlp fallback path
    // -------------------------------------------------------------------------

    private suspend fun finalizeFromYtDlp(track: TrackItem): TrackFinalizer.FinalizeResult {
        val tempDir = File(context.cacheDir, "search_ytdlp").also { it.mkdirs() }
        val filename = "search_${track.videoId}"

        val ytDlpResult = runCatching {
            downloadExecutor.download(
                url = "https://www.youtube.com/watch?v=${track.videoId}",
                outputDir = tempDir,
                filename = filename,
                qualityArgs = emptyList(),
            )
        }.getOrElse { e ->
            return TrackFinalizer.FinalizeResult.Failed("yt-dlp threw: ${e.message}")
        }

        val tempFile = when (ytDlpResult) {
            is DownloadResult.Success -> ytDlpResult.file
            is DownloadResult.YtDlpError ->
                return TrackFinalizer.FinalizeResult.Failed("yt-dlp: ${ytDlpResult.message}")
            is DownloadResult.Error ->
                return TrackFinalizer.FinalizeResult.Failed("yt-dlp error: ${ytDlpResult.message}")
            is DownloadResult.NoOutput ->
                return TrackFinalizer.FinalizeResult.Failed("yt-dlp produced no output")
        }

        // opus/0 are placeholder values — TrackFinalizer probes the real codec
        // post-download and upsertSearchTrack writes the probed values if available.
        val format = AudioFormat(codec = "opus", bitrateKbps = 0)
        val finalized = trackFinalizer.finalizeFile(
            sourceFile = tempFile,
            track = track.toDomainStub(),
            format = format,
        )

        if (finalized is TrackFinalizer.FinalizeResult.Success) {
            // Same defensive runCatching as the lossless path — DB-write
            // failures must not prevent Completed from emitting.
            runCatching {
                upsertSearchTrack(track, format, finalized, track.thumbnailUrl)
            }.onFailure { e ->
                Log.e(TAG, "upsertSearchTrack (yt-dlp) failed for ${track.videoId}: ${e.message}", e)
            }
            // v0.9.36 lyrics integration: parity with the lossless branch.
            stampEmbeddedAt(track.videoId)?.let { lyricsFetchTrigger.enqueueFor(it) }
            syncNotificationManager.showDownloadCompleteNotification(track.title, track.artist)
        }
        return finalized
    }

    /**
     * Stamps `tracks.metadata_embedded_at` after a successful finalize so the
     * v0.9.35 backfill worker skips this row. Lookup is best-effort: when the
     * Track row isn't found by videoId (rare — `upsertSearchTrack` always
     * inserts before we get here, but defensive against a concurrent delete)
     * the stamp is simply skipped. Failure is non-fatal — the file is on
     * disk and playable regardless.
     *
     * v0.9.36: returns the resolved Long trackId so the caller can hand it
     * straight to [LyricsFetchTrigger.enqueueFor] without repeating the
     * `findByYoutubeId` lookup. Returns null when the row isn't found OR
     * the DAO call threw — both cases mean we couldn't establish a stable
     * trackId, so the lyrics enqueue must also be skipped.
     */
    private suspend fun stampEmbeddedAt(videoId: String): Long? {
        return runCatching {
            val trackId = trackDao.findByYoutubeId(videoId)?.id ?: return null
            trackDao.setMetadataEmbeddedAt(trackId, System.currentTimeMillis())
            trackId
        }.onFailure { e ->
            Log.w(TAG, "setMetadataEmbeddedAt failed for $videoId: ${e.message}")
        }.getOrNull()
    }

    // -------------------------------------------------------------------------
    // DB upsert — search-specific path
    // -------------------------------------------------------------------------

    /**
     * Looks up an existing Track row by videoId or canonical identity;
     * inserts a new stub row when neither matches. Then records the
     * download columns and links to the "Your Downloads" playlist so
     * orphan-cleanup cannot delete the file.
     */
    private suspend fun upsertSearchTrack(
        track: TrackItem,
        format: AudioFormat,
        finalized: TrackFinalizer.FinalizeResult.Success,
        coverArtUrl: String?,
    ) {
        // v0.9.15: Reject blocklisted identities. Without this, tapping
        // "Download" on a search result for a song the user previously
        // blocked would silently resurrect the file + create a new
        // "Your Downloads" link.
        if (blocklistGuard.isBlocked(
                artist = track.artist, title = track.title,
                spotifyUri = null, youtubeId = track.videoId,
            )) {
            android.util.Log.d("SearchDownload", "Refused download of blocked: ${track.artist} - ${track.title}")
            return
        }

        val existing = trackDao.findByYoutubeId(track.videoId)
            ?: trackDao.findByCanonicalIdentity(
                title = canonicalize(track.title),
                artist = canonicalize(track.artist),
            )

        val albumName = track.album.orEmpty()
        val albumArtistName = track.albumArtist.orEmpty()
        val trackId: Long = existing?.id ?: trackDao.insert(
            com.stash.core.data.db.entity.TrackEntity(
                title = track.title,
                artist = track.artist,
                // Album from the discovery-screen context (set by
                // AlbumDiscoveryScreen / AlbumDiscoveryViewModel when the user
                // taps Download All on an album, or null when downloading a
                // loose search result). Without this the row lands with
                // album = "" and TrackDao.getAllAlbums (filtered by
                // album != '') never surfaces it in the Library Albums tab.
                album = albumName,
                // v0.9.26 — album_artist disambiguates same-titled releases
                // by different artists ("Singles" by Usher vs "Singles" by
                // Drake) and lets multi-artist collab albums stay grouped
                // even when per-track artist credits vary.
                albumArtist = albumArtistName,
                youtubeId = track.videoId,
                canonicalTitle = canonicalize(track.title),
                canonicalArtist = canonicalize(track.artist),
                // (durationSeconds * 1_000).toLong() preserves sub-second precision.
                // Doing .toLong() first then * 1_000L would truncate 3.7s → 3_000ms.
                durationMs = (track.durationSeconds * 1_000).toLong(),
                source = com.stash.core.model.MusicSource.YOUTUBE,
                albumArtUrl = track.thumbnailUrl,
            )
        )

        // If the row already existed (from a prior sync or a different
        // identity-equivalent download) and has no album/album_artist
        // recorded, fill them in now that we know them. Same fix as the
        // insert path — without this, an imported-then-redownloaded track
        // would still hide its album from the Library tab.
        if (existing != null && existing.album.isBlank() && albumName.isNotBlank()) {
            runCatching { trackDao.updateAlbumIfEmpty(trackId, albumName) }
                .onFailure { e -> Log.w(TAG, "updateAlbumIfEmpty failed: ${e.message}") }
        }
        if (existing != null && existing.albumArtist.isBlank() && albumArtistName.isNotBlank()) {
            runCatching { trackDao.updateAlbumArtistIfEmpty(trackId, albumArtistName) }
                .onFailure { e -> Log.w(TAG, "updateAlbumArtistIfEmpty failed: ${e.message}") }
        }

        // Reject a "successful" download whose file is too small to be audio
        // (a failed yt-dlp run leaving a tiny error body). Delete it + leave
        // the track not-downloaded (streamable) rather than mark junk.
        if (!localFileOps.acceptDownloadOrDelete(finalized.committed.filePath)) {
            Log.w(TAG, "search download: discarded too-small file for trackId=$trackId: ${finalized.committed.filePath}")
            return
        }

        trackDao.markAsDownloaded(
            trackId = trackId,
            filePath = finalized.committed.filePath,
            fileSizeBytes = finalized.committed.sizeBytes,
            sampleRateHz = finalized.meta?.sampleRateHz,
            bitsPerSample = finalized.meta?.bitsPerSample,
        )

        finalized.meta?.let { meta ->
            // Only write probed values when the probe succeeded and reported
            // a real codec. "unknown" indicates MediaMetadataRetriever returned
            // no MIME type — writing it would corrupt the format column.
            if (meta.format != "unknown") {
                runCatching { trackDao.setFormatAndQuality(trackId, meta.format, meta.bitrateKbps) }
                    .onFailure { e -> Log.w(TAG, "setFormatAndQuality failed: ${e.message}") }
            }
        }

        // Trigger loudness measurement off the download thread. The
        // measurement (~25–50 s of ffmpeg ebur128) used to run synchronously
        // inside TrackFinalizer and serialised entire albums behind a
        // single measurer mutex. Now it's fire-and-forget — the row gets
        // updated whenever the background scan completes, the download flow
        // returns immediately.
        loudnessMeasurer.measureAndPersistInBackground(
            trackId = trackId,
            file = java.io.File(finalized.committed.filePath),
        )

        coverArtUrl?.let {
            runCatching { trackDao.fillMissingAlbumArtUrl(trackId, it) }
                .onFailure { e -> Log.w(TAG, "fillMissingAlbumArtUrl failed: ${e.message}") }
        }

        // Link to the "Your Downloads" / downloads-mix playlist so orphan
        // cleanup never deletes this track's file on next sync.
        runCatching { musicRepository.linkTrackToDownloadsMix(trackId) }
            .onFailure { e -> Log.e(TAG, "linkTrackToDownloadsMix failed for $trackId", e) }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Normalises a string for database identity matching.
     * Lowercased, non-alphanumeric chars replaced with spaces, runs of
     * spaces collapsed, leading/trailing whitespace stripped.
     */
    private fun canonicalize(s: String): String =
        s.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Converts a [TrackItem] to a [TrackQuery] for the lossless registry.
     *  Passes [TrackItem.album] through so lossless matching can use it as
     *  a tie-breaker on releases with the same track title across albums. */
    private fun TrackItem.toQuery() = TrackQuery(
        artist = artist,
        title = title,
        album = album?.takeIf { it.isNotBlank() },
        isrc = null,
        // (durationSeconds * 1_000).toLong() preserves sub-second precision —
        // .toLong().times(1_000L) would truncate 3.7s → 3_000ms (wrong).
        durationMs = durationSeconds.takeIf { it > 0 }?.let { (it * 1_000).toLong() },
        // TrackItem is the YouTube/search-tab flow (videoId/title/artist/
        // duration only) — no Spotify URI exists, so search-tab downloads
        // correctly skip antra (which requires a spotify track URL).
        spotifyUri = null,
    )

    /**
     * Builds a minimal [com.stash.core.model.Track] stub for [TrackFinalizer].
     * The finalizer uses title/artist/album for metadata embedding and
     * artist/title for the library file path.
     *
     * [TrackItem.album] flows through here so album-context downloads land
     * with a non-empty `tracks.album` value. Without that, downloaded album
     * tracks don't show up in the Library's Albums view (TrackDao.getAllAlbums
     * filters out tracks with empty album values).
     */
    private fun TrackItem.toDomainStub() = com.stash.core.model.Track(
        title = title,
        artist = artist,
        album = album.orEmpty(),
        albumArtist = albumArtist.orEmpty(),
        durationMs = (durationSeconds * 1_000).toLong(),
        albumArtUrl = thumbnailUrl,
        youtubeId = videoId,
    )

    /**
     * Outcome of [performDownload]. Either:
     *  - [Resolved]: a source was chosen and bytes were finalized (or
     *    finalization failed) — flow emits Downloading + Completed/Failed.
     *  - [Deferred]: v0.9.17 strict-FLAC defer — registry returned null and
     *    the user has yt-dlp fallback off — flow emits WaitingForLossless.
     */
    private sealed interface DownloadJobResult {
        /** Pairs a [SearchDownloadStatus.Source] with the [TrackFinalizer.FinalizeResult]. */
        data class Resolved(
            val source: SearchDownloadStatus.Source,
            val outcome: TrackFinalizer.FinalizeResult,
        ) : DownloadJobResult

        /** v0.9.17: lossless unavailable + fallback off → WaitingForLossless. */
        data object Deferred : DownloadJobResult
    }

    companion object {
        private const val TAG = "SearchDownloadCoordinator"

        /** Minimum confidence threshold for accepting a lossless source match. */
        const val MIN_SEARCH_CONFIDENCE = 0.65f
    }
}
