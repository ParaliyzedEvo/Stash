package com.stash.data.download.lossless.amz

import android.util.Log
import com.stash.data.download.lossless.AggregatorRateLimiter
import com.stash.data.download.lossless.AudioFormat
import com.stash.data.download.lossless.LosslessSource
import com.stash.data.download.lossless.RateLimitState
import com.stash.data.download.lossless.SourceResult
import com.stash.data.download.lossless.TrackQuery
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * [LosslessSource] backed by the Amazon Music catalog via the public
 * `amz.squid.wtf/api` proxy. Searches for the requested track by
 * artist + title text (Amazon search indexes neither ISRC nor duration —
 * see Requirement 2 / [AmzMatcher]), scores candidates with [AmzMatcher],
 * then fetches the per-track `/api/track` metadata and resolves a stream
 * URL for the best match.
 *
 * Auth is the `x-captcha-token` header, attached automatically by the
 * already-registered [AmzCaptchaInterceptor] on the shared OkHttpClient —
 * this source never touches the token, and [SourceResult.downloadHeaders]
 * is therefore empty (the token rides the interceptor on the file fetch
 * too; Requirement 3).
 *
 * Like the Qobuz proxies, this is structurally one paid account serving an
 * unbounded user base, so every network call is gated behind
 * [AggregatorRateLimiter] for [id] ("amz", configured conservatively in the
 * limiter's init block).
 *
 * **No assumed bit-depth (Requirement 5):** the `/api/track` response has no
 * duration and no reliable bit-depth/sample-rate; the codec is FLAC but the
 * numeric fields are left 0, and the canonical values are filled by the
 * post-download audio probe.
 */
@Singleton
class AmzSource @Inject constructor(
    private val client: AmzApiClient,
    private val rateLimiter: AggregatorRateLimiter,
) : LosslessSource {

    override val id: String = SOURCE_ID

    override val displayName: String = "Amazon Music (amz.squid.wtf)"

    /** Usable unless the breaker has tripped on repeated failures. */
    override suspend fun isEnabled(): Boolean = !rateLimiter.stateOf(id).isCircuitBroken

    override suspend fun rateLimitState(): RateLimitState = rateLimiter.stateOf(id)

    override suspend fun resolve(query: TrackQuery): SourceResult? {
        if (!isEnabled()) return null
        // Acquire a token; bail cleanly if the source is circuit-broken or
        // the bucket can't be satisfied.
        if (!rateLimiter.acquire(id)) return null

        return try {
            // Requirement 2: text search ONLY — never query.searchTerms() / ISRC;
            // Amazon search indexes neither ISRC nor duration.
            val searchText = "${query.artist} ${query.title}".trim()
            Log.d(TAG, "resolve attempt artist='${query.artist}' title='${query.title}' isrc=${query.isrc ?: "none"}")

            val candidates = client.search(searchText)
            val match = AmzMatcher.best(query, candidates)
            if (match == null) {
                Log.d(TAG, "no_match for '$searchText' (${candidates.size} candidates)")
                rateLimiter.reportFailure(id)
                return null
            }

            val track = client.track(match.item.asin)
            if (track == null) {
                Log.d(TAG, "track() returned no metadata for asin=${match.item.asin}")
                rateLimiter.reportFailure(id)
                return null
            }
            val meta = track.meta

            // ISRC confirmation BOOSTS confidence when both sides agree, but a
            // mismatch is NOT a rejection — Amazon may legitimately carry a
            // different master. On mismatch (or missing ISRC) we keep the
            // matcher's text-derived confidence.
            val queryIsrc = query.isrc?.takeIf { it.isNotBlank() }
            val metaIsrc = meta.isrc?.takeIf { it.isNotBlank() }
            val confidence =
                if (queryIsrc != null && metaIsrc != null && metaIsrc.equals(queryIsrc, ignoreCase = true)) {
                    0.95f
                } else {
                    match.confidence
                }

            rateLimiter.reportSuccess(id)
            val result = SourceResult(
                sourceId = id,
                // Encrypted-CMAF URL from /api/track (the authoritative stream
                // descriptor); fall back to the built URL only if absent.
                downloadUrl = track.streamUrl ?: client.streamUrl(meta.asin),
                // Captcha token rides the shared-client interceptor (Requirement 3).
                downloadHeaders = emptyMap(),
                // Requirement 5: FLAC codec, no assumed bit-depth/sample-rate —
                // the post-download probe fills the real numeric values.
                format = AudioFormat(
                    codec = "flac",
                    bitrateKbps = 0,
                    sampleRateHz = 0,
                    bitsPerSample = 0,
                ),
                confidence = confidence,
                sourceTrackId = meta.asin,
                coverArtUrl = meta.coverCdn ?: meta.cover,
                // amz serves encrypted CMAF; the downloader decrypts with this
                // per-track AES-128 key after fetching (null → nothing to do).
                decryptionKey = track.decryptionKey,
            )
            Log.d(TAG, "resolved '${query.title}' asin=${meta.asin} confidence=$confidence")
            result
        } catch (e: AmzRateLimitedException) {
            rateLimiter.reportRateLimited(id)
            null
        } catch (e: CancellationException) {
            // Re-throw BEFORE the generic catch so coroutine cancellation
            // isn't swallowed as a failure (Requirement 6).
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "resolve failed: ${e.javaClass.simpleName}: ${e.message}", e)
            rateLimiter.reportFailure(id)
            null
        }
    }

    companion object {
        const val SOURCE_ID = "amz"
        private const val TAG = "AmzSource"
    }
}
