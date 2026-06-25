package com.stash.core.media.streaming

import android.util.Log
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.arcod.ArcodClient
import com.stash.data.download.lossless.arcod.ArcodMatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * Stream-URL resolver backed by ARCOD's single stream-URL GET via [ArcodClient].
 *
 * Flow: open `get-music` search → [ArcodMatcher.best] → the matched Qobuz track id
 * → one authenticated stream GET (the operator's private endpoint, base injected
 * via BuildConfig) that returns an open, Range-capable, short-lived FLAC URL. No
 * job render/poll (that path is kept only for downloads). The URL plays through
 * the default media-source factory.
 *
 * The streaming quality tier comes from [StreamQualityPolicy] (per-network /
 * Save-Data), mirroring [AmzStreamResolver] — so ARCOD streaming respects the
 * user's cellular/Wi-Fi tier instead of always pulling max.
 *
 * Sits LAST among the lossless streaming sources and foreground-only (gated in
 * [StreamSourceRegistry]); reached only when kennyy and squid both miss.
 *
 * Returns null when search has no confident match, or the stream GET fails.
 */
@Singleton
class ArcodStreamResolver @Inject constructor(
    private val client: ArcodClient,
    private val qualityPolicy: StreamQualityPolicy,
) {
    suspend fun resolve(track: TrackEntity): StreamUrl? {
        Log.d(TAG, "resolve attempt id=${track.id} title='${track.title}'")
        val query = TrackQuery(
            artist = track.artist,
            title = track.title,
            album = track.album.takeIf { it.isNotBlank() },
            isrc = track.isrc?.takeIf { it.isNotBlank() },
            durationMs = track.durationMs,
        )
        return try {
            val items = client.search("${query.artist} ${query.title}".trim())
            val match = ArcodMatcher.best(query, items) ?: run {
                Log.d(TAG, "no_match id=${track.id}")
                return null
            }
            val item = match.item
            val code = qualityPolicy.streamingTier().qobuzCode
            val stream = client.streamUrl(item.id, code) ?: run {
                Log.d(TAG, "no_stream_url id=${track.id} trackId=${item.id} quality=$code")
                return null
            }
            val ttlMs = stream.expiresInSec
                ?.let { (it.toLong() * 1000L - EXPIRY_SAFETY_MS).coerceAtLeast(MIN_TTL_MS) }
                ?: DEFAULT_TTL_MS
            // The single stream GET returns only a URL, so derive the delivered
            // bit-depth/sample-rate from the matched catalog maximums clamped to
            // the requested tier — the deterministic Qobuz contract, no extra call.
            val delivered = ArcodDeliveredQuality.of(code, item.maxBitDepth, item.maxSamplingRate)
            Log.d(TAG, "resolved id=${track.id} origin=$ORIGIN quality=$code")
            StreamUrl(
                url = stream.url,
                expiresAtMs = System.currentTimeMillis() + ttlMs,
                codec = "flac",
                bitsPerSample = delivered.bitsPerSample,
                sampleRateHz = delivered.sampleRateHz,
                origin = ORIGIN,
                coverArtUrl = item.album?.image?.large?.takeIf { it.isNotBlank() },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "resolve failed id=${track.id}", e)
            null
        }
    }

    companion object {
        const val ORIGIN = "arcod"
        private const val TAG = "ArcodStreamResolver"
        /** No parseable expiry on the URL — conservative reuse window. */
        private const val DEFAULT_TTL_MS = 280_000L
        /** Re-resolve this much before a server-stated expiry to avoid a 403. */
        private const val EXPIRY_SAFETY_MS = 20_000L
        /** Floor for a server-stated expiry so a tiny lifetime yields a short TTL
         *  (prompt re-resolve) rather than the 280s default — never over-cache a
         *  short-lived URL. */
        private const val MIN_TTL_MS = 5_000L
    }
}
