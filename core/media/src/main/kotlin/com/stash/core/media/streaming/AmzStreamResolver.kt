package com.stash.core.media.streaming

import android.util.Log
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.amz.AmzApiClient
import com.stash.data.download.lossless.amz.AmzMatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Stream-URL resolver backed by `amz.squid.wtf/api` (an Amazon Music
 * proxy) via [AmzApiClient]. Counterpart to [QobuzStreamResolver] and
 * [KennyyStreamResolver] — same `resolve(track) -> StreamUrl?` shape,
 * different upstream operator.
 *
 * Ranked AFTER the two Qobuz proxies (kennyy/squid) and BEFORE the
 * YouTube fallback in [StreamSourceRegistry]: when neither Qobuz proxy
 * has a confident match but Amazon does, this serves lossless FLAC
 * rather than dropping to lossy YouTube.
 *
 * Auth is the `x-captcha-token` header attached automatically by the
 * shared client's captcha interceptor — NOT this resolver's concern.
 *
 * Returns null when:
 *  - Amazon search yields no candidates.
 *  - [AmzMatcher] finds no confident match.
 *  - The per-track `/api/track` metadata lookup fails.
 *  - Any non-cancellation error occurs (defensive: the registry just
 *    falls through to the next source).
 *
 * Unlike the Qobuz resolvers, the amz `/api/stream` URL is *stable* —
 * there is no signed-URL `etsp` expiry to parse. Auth lives in the
 * interceptor-refreshed captcha header, so [StreamUrl.expiresAtMs] is
 * [Long.MAX_VALUE] (never expires from the caller's caching standpoint).
 * Bit-depth / sample-rate are left null — unknown until playback decodes
 * the stream.
 */
@Singleton
class AmzStreamResolver @Inject constructor(
    private val client: AmzApiClient,
    private val fileProvider: com.stash.data.download.lossless.amz.AmzStreamFileProvider,
    private val qualityPolicy: StreamQualityPolicy,
) {
    suspend fun resolve(track: TrackEntity): StreamUrl? {
        Log.d(TAG, "resolve attempt id=${track.id} title='${track.title}'")
        // Per-network streaming tier (Save Data → CD; cellular → cellularTier;
        // Wi-Fi → wifiTier), mapped to amz's wire tier. CD maps to amz `high`
        // (256 AAC) — the data-saver floor for metered streaming.
        val amzTier = qualityPolicy.streamingTier().amzTier
        val query = TrackQuery(
            artist = track.artist,
            title = track.title,
            album = track.album.takeIf { it.isNotBlank() },
            isrc = track.isrc?.takeIf { it.isNotBlank() },
            durationMs = track.durationMs,
        )
        return try {
            // Text search only — never ISRC (spec Requirement 2): Amazon
            // search results carry no ISRC, and the proxy's ISRC search is
            // unreliable. Title + artist text is the match signal.
            val candidates = client.search("${query.artist} ${query.title}".trim())
            val match = AmzMatcher.best(query, candidates) ?: run {
                Log.d(TAG, "no_match id=${track.id}")
                return null
            }
            val amz = client.track(match.item.asin, amzTier) ?: run {
                Log.d(TAG, "no_meta id=${track.id} asin=${match.item.asin}")
                return null
            }
            val meta = amz.meta
            // amz serves CENC-encrypted CMAF — it can't be progressively
            // streamed. Decrypt the whole track to a local cache file
            // (ffmpeg -decryption_key) and hand the player a file:// URL, which
            // plays via the default/local factory like a downloaded track. The
            // per-track AES key is mandatory; without it the bytes are unplayable.
            val key = amz.decryptionKey ?: run {
                Log.d(TAG, "no_drm_key id=${track.id} asin=${meta.asin}")
                return null
            }
            val encryptedUrl = amz.streamUrl ?: client.streamUrl(meta.asin, amzTier)
            val localFile = fileProvider.resolveLocalFile(meta.asin, encryptedUrl, key) ?: run {
                Log.d(TAG, "decrypt_failed id=${track.id} asin=${meta.asin}")
                return null
            }
            // Read the true bit-depth/sample-rate from the decrypted FLAC's
            // STREAMINFO so Now Playing shows "24-bit / 96 kHz" instead of a
            // bare "FLAC". amz's /api/track reports bitDepth=null, so the file
            // is the authoritative source; the API stream object supplies the
            // bitrate (not stored in FLAC) and a sample-rate fallback.
            val flac = com.stash.data.download.lossless.amz.FlacStreamInfo.read(localFile)
            Log.d(TAG, "resolved id=${track.id} origin=$ORIGIN asin=${meta.asin} -> ${localFile.name} " +
                "(${flac?.bitsPerSample ?: "?"}bit/${flac?.sampleRateHz ?: amz.sampleRateHz ?: "?"}Hz)")
            StreamUrl(
                url = "file://${localFile.absolutePath}",
                // Local decrypted file — never expires from the caller's
                // caching standpoint (auth/signing don't apply to a local path).
                expiresAtMs = Long.MAX_VALUE,
                codec = amz.codec ?: "flac",
                bitsPerSample = flac?.bitsPerSample,
                sampleRateHz = flac?.sampleRateHz ?: amz.sampleRateHz,
                bitrateKbps = amz.bitrateBps?.let { it / 1000 },
                coverArtUrl = meta.coverCdn ?: meta.cover,
                origin = ORIGIN,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "resolve failed id=${track.id}", e)
            null
        }
    }

    companion object {
        const val ORIGIN = "amz"
        private const val TAG = "AmzStreamResolver"
    }
}
