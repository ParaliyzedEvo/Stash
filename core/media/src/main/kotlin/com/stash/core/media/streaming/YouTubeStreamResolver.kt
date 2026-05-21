package com.stash.core.media.streaming

import android.util.Log
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.download.preview.PreviewUrlExtractor
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.SearchResultSection
import com.stash.data.ytmusic.model.TopResultItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Last-resort stream-URL resolver that pulls an audio stream URL from
 * YouTube via [PreviewUrlExtractor]. Reached only when Kennyy AND
 * squid both miss the track in the Qobuz catalog — i.e. the track
 * genuinely isn't lossless-available.
 *
 * **Why this exists.** Qobuz has gaps: underground / independent
 * releases (Bandcamp re-uploads, label-less artists, region-exclusive
 * tracks) aren't in the catalog. For users with large libraries —
 * Liked Songs at ~2.5k tracks — even a 1-2% catalog gap means dozens
 * of tracks silently dropping out of the queue in streaming mode.
 * YouTube has effectively complete coverage; falling back to it
 * trades lossless fidelity for actually playing the music.
 *
 * **Why InnerTube-only (fast path).** [PreviewUrlExtractor] races an
 * InnerTube call (~1-2s, returns unciphered audio URL when available)
 * against a yt-dlp call (~15-35s, slower but more reliable). For
 * streaming queue resolution we need the call to complete quickly —
 * a 20-second yt-dlp invocation on the queue's critical path would
 * be a worse UX than just dropping the track. We bound the entire
 * call to [YT_RESOLVE_TIMEOUT_MS]; if InnerTube doesn't return in
 * time, we treat it as unavailable and yt-dlp can be tried later
 * via the download path (which has explicit user intent + a longer
 * tolerance for latency).
 *
 * **VideoId resolution.** If the track already has a `youtubeId`
 * (YT-synced rows, or Spotify rows that were cross-matched during
 * sync), we use it directly. Otherwise — pure-Spotify rows with no
 * cross-match, like underground techno releases not on YT Music's
 * curated catalog — we do a fall-back YT search by `"$artist $title"`
 * and take the top Songs-shelf hit. Top-result fuzzy match accepts
 * small wrong-version risk in exchange for actually playing tracks
 * that would otherwise drop.
 *
 * **Returns null when:**
 * - Neither path produces a videoId (search returned no Songs hits).
 * - The extractor returns no URL (video deleted, region-blocked,
 *   private, age-gated, etc).
 * - Either step times out.
 *
 * **URL expiry.** YouTube signed URLs carry an `expire=<unix-seconds>`
 * query param. We parse it for the StreamUrl TTL; on miss we use a
 * conservative 1-hour default, then [RefreshingDataSourceFactory]
 * handles mid-stream 403s by re-resolving via the registry.
 */
@Singleton
class YouTubeStreamResolver @Inject constructor(
    private val urlExtractor: PreviewUrlExtractor,
    private val ytMusicApiClient: YTMusicApiClient,
) {
    suspend fun resolve(track: TrackEntity): StreamUrl? {
        // Prefer the existing youtubeId when present (YT-synced rows
        // and cross-matched Spotify rows already have one). Otherwise
        // fall through to a YT Music search by metadata — covers the
        // pure-Spotify case where the original sync never linked to YT.
        val videoId = track.youtubeId?.takeIf { it.isNotBlank() }
            ?: searchYouTubeForVideoId(track)
            ?: return null

        val url = withTimeoutOrNull(YT_RESOLVE_TIMEOUT_MS) {
            runCatching { urlExtractor.extractStreamUrl(videoId) }
                .onFailure { t ->
                    // CancellationException MUST propagate — swallowing it would
                    // surface as StreamRoutingResult.NotAvailable upstream, firing
                    // a "Couldn't find this track" snackbar for a track that was
                    // simply preempted by a newer tap or queue change.
                    if (t is CancellationException) throw t
                    Log.d(TAG, "extraction failed for $videoId: ${t.message}")
                }
                .getOrNull()
        } ?: return null

        val expiresAtMs = parseExpireMs(url) ?: (System.currentTimeMillis() + DEFAULT_TTL_MS)

        return StreamUrl(
            url = url,
            expiresAtMs = expiresAtMs,
            // YouTube audio streams are typically Opus or AAC at ~128-160
            // kbps. We don't get precise codec metadata back from the
            // extractor (it just hands us a URL), so we report "aac" as a
            // best-effort label that's correct for the majority of YT
            // Music tracks. The UI badge derives "lossy via YT" from the
            // origin field below, not the codec, so this only affects the
            // format-string display in Now Playing.
            codec = "aac",
            bitsPerSample = null,
            sampleRateHz = null,
            bitrateKbps = null,
            coverArtUrl = null,
            origin = ORIGIN,
        )
    }

    /**
     * Falls back to a YT Music search when the track has no stored
     * youtubeId. Uses `"$artist $title"` as the query (no extra
     * normalisation — the InnerTube search is already tolerant). Picks
     * the top Songs-shelf hit and returns its videoId. Null when:
     *  - The artist/title combo is blank (defensive — shouldn't happen).
     *  - The search timed out.
     *  - The search returned no Songs section, or the section was empty.
     *
     * We don't do a confidence-score gate here: when this path runs, the
     * alternative is silent failure. A loosely-matching top result is
     * better than no audio at all. Wrong-version risk is the same risk
     * the user accepts when they originally added a non-cross-matched
     * Spotify track.
     */
    private suspend fun searchYouTubeForVideoId(track: TrackEntity): String? {
        val query = "${track.artist} ${track.title}".trim()
        if (query.isBlank()) return null
        return withTimeoutOrNull(YT_SEARCH_TIMEOUT_MS) {
            val results = runCatching { ytMusicApiClient.searchAll(query) }
                .onFailure { t ->
                    if (t is CancellationException) throw t
                    Log.d(TAG, "search failed for '$query': ${t.message}")
                }
                .getOrNull() ?: return@withTimeoutOrNull null

            // YT Music search returns sections of various kinds — Top
            // result card, Songs shelf, Albums shelf, Artists shelf,
            // Videos shelf, etc. For our streaming-fallback purpose, we
            // want a *playable* videoId. Try in priority order:
            //   1. Songs shelf — exact track matches with audio-stream
            //      playback. Best signal.
            //   2. Top result card — when YT decides the first hit is
            //      authoritative; carries a videoId for Song/Video tops.
            //   3. Videos shelf — music videos, fine for audio playback.
            // For underground / Bandcamp-uploaded tracks (Vril releases,
            // niche electronic, etc.) YT Music often returns ONLY a Top
            // result card and a Videos shelf, no Songs shelf — those used
            // to fall through to null with the original Songs-only lookup.
            val songFirst = results.sections
                .filterIsInstance<SearchResultSection.Songs>()
                .firstOrNull()
                ?.tracks?.firstOrNull()
                ?.videoId
                ?.takeIf { it.isNotBlank() }
            if (songFirst != null) {
                Log.d(TAG, "search hit '$query' -> $songFirst (Songs)")
                return@withTimeoutOrNull songFirst
            }

            val topVideoId = results.sections
                .filterIsInstance<SearchResultSection.Top>()
                .firstOrNull()
                ?.item
                ?.let { it as? TopResultItem.TrackTop }
                ?.track
                ?.videoId
                ?.takeIf { it.isNotBlank() }
            if (topVideoId != null) {
                Log.d(TAG, "search hit '$query' -> $topVideoId (Top)")
                return@withTimeoutOrNull topVideoId
            }

            // Last resort: scan every section type for a TrackSummary-
            // shaped item. Defensive against future shelf variants we
            // haven't enumerated above.
            Log.d(
                TAG,
                "search for '$query' returned ${results.sections.size} section(s) " +
                    "but no Songs/Top videoId — sections=${results.sections.map { it::class.simpleName }}",
            )
            null
        }
    }

    private fun parseExpireMs(url: String): Long? {
        val match = EXPIRE_REGEX.find(url) ?: return null
        val secs = match.groupValues[1].toLongOrNull() ?: return null
        return secs * 1000L
    }

    private companion object {
        private const val TAG = "YouTubeStreamResolver"
        const val ORIGIN = "youtube"

        /**
         * Total budget for the YT-fallback extraction. The fast InnerTube
         * path usually returns in ~1-2s — but tracks with `UNPLAYABLE`
         * InnerTube status (common for licensed music releases not
         * cleared for anonymous /player extraction, like Vril releases
         * on Delsin Records) only resolve via the heavier yt-dlp +
         * QuickJS signature path, which takes ~10-15s on a typical
         * Android device. 35s gives that path room to finish without
         * stalling forever on a genuinely unavailable video.
         *
         * Trade-off: for setQueue background-fill, every track that
         * needs yt-dlp eats up to 35s of one of the 16 parallel resolve
         * slots. With ~5% YT-fallback hit rate, a 2700-track Liked Songs
         * queue takes ~5 min of background fill at the tail end (the
         * head plays immediately — playback starts as soon as the start
         * track resolves). Acceptable trade for actually playing music
         * that the previous 5s timeout silently dropped.
         */
        private const val YT_RESOLVE_TIMEOUT_MS = 35_000L

        /**
         * Budget for the YT Music search when the track has no stored
         * youtubeId. InnerTube search typically returns in ~500ms; 3s
         * is enough headroom even on slow connections without keeping
         * the queue waiting too long on a track that might not be on
         * YouTube either.
         */
        private const val YT_SEARCH_TIMEOUT_MS = 3_000L

        /** Fallback expiry when the YT URL has no `expire=` parameter. */
        private const val DEFAULT_TTL_MS = 60 * 60 * 1000L

        val EXPIRE_REGEX = Regex("""[?&]expire=(\d+)""")
    }
}
