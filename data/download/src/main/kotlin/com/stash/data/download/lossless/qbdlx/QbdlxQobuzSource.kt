package com.stash.data.download.lossless.qbdlx

import android.util.Log
import com.stash.data.download.lossless.AggregatorRateLimiter
import com.stash.data.download.lossless.AudioFormat
import com.stash.data.download.lossless.LosslessSource
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.RateLimitState
import com.stash.data.download.lossless.SourceResult
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.qobuz.QobuzCandidateMatcher
import com.stash.data.download.lossless.searchTerms
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * [LosslessSource] backed by the Qobuz catalog via the DIRECT Qobuz API
 * (MD5-signed requests + a rotating `X-User-Auth-Token` pool), as opposed to
 * the squid.wtf proxy that [com.stash.data.download.lossless.qobuz.QobuzSource]
 * uses. Searches, scores candidates with the shared [QobuzCandidateMatcher],
 * and resolves the best match to a signed Hi-Res FLAC URL.
 *
 * Token health is per-account (Qobuz bans accounts, not IPs), so this source
 * rotates across a pool ([QbdlxCredentialStore]) and persists dead tokens. The
 * [AggregatorRateLimiter] breaker is a per-source health signal — a dead token
 * (auth failure / `UserUnauthenticated` preview) must NOT trip it (it's a
 * credential problem, not a service-down problem), so those paths rotate
 * without reporting a failure.
 *
 * Mirrors [QobuzSource]'s resolve / resolveImmediate split: background
 * [resolve] respects the rate limiter + breaker; user-initiated
 * [resolveImmediate] (streaming) bypasses both but still reports outcomes so
 * the breaker state stays accurate.
 */
@Singleton
class QbdlxQobuzSource @Inject constructor(
    private val apiClient: QbdlxApiClient,
    private val credentialStore: QbdlxCredentialStore,
    private val rateLimiter: AggregatorRateLimiter,
    private val losslessPrefs: LosslessSourcePreferences,
) : LosslessSource {

    override val id: String = SOURCE_ID

    override val displayName: String = "Direct Qobuz"

    override suspend fun isEnabled(): Boolean =
        losslessPrefs.qbdlxEnabledNow() &&
            !rateLimiter.stateOf(id).isCircuitBroken &&
            !credentialStore.allDead()

    /**
     * Streaming-only gate: same toggle + pool check as [isEnabled] but
     * WITHOUT the breaker — a user stream tap bypasses the breaker (see
     * [resolveImmediate]), so gating enablement on it would be inconsistent.
     * A disabled toggle still blocks streaming.
     */
    suspend fun isEnabledForStreaming(): Boolean =
        losslessPrefs.qbdlxEnabledNow() && !credentialStore.allDead()

    override suspend fun resolve(query: TrackQuery): SourceResult? {
        if (!isEnabled()) return null
        return resolveInternal(query, bypassRateLimit = false, requestedQuality = null)
    }

    /**
     * User-initiated immediate resolve for the streaming path. Skips the
     * token bucket AND the breaker (mirrors [QobuzSource.resolveImmediate]).
     */
    suspend fun resolveImmediate(
        query: TrackQuery,
        requestedQuality: Int? = null,
    ): SourceResult? {
        if (!isEnabledForStreaming()) return null
        return resolveInternal(query, bypassRateLimit = true, requestedQuality = requestedQuality)
    }

    override suspend fun rateLimitState(): RateLimitState = rateLimiter.stateOf(id)

    // ── Internals ───────────────────────────────────────────────────────

    private suspend fun resolveInternal(
        query: TrackQuery,
        bypassRateLimit: Boolean,
        requestedQuality: Int?,
    ): SourceResult? {
        val (track, conf, token) = search(query, bypassRateLimit) ?: return null
        // Honor the user's quality tier on the download path (CD/Hi-Res/Max →
        // qobuzCode 6/7/27), mirroring QobuzSource. The stream path passes an
        // explicit requestedQuality (the streaming tier); only fall back to the
        // download tier preference when none was given.
        val formatId = requestedQuality ?: losslessPrefs.qualityTierNow().qobuzCode
        return resolveFile(track, conf, token, formatId, bypassRateLimit)
    }

    /**
     * Search + match. Returns the best candidate, its confidence, and the live
     * token that found it. Rotates on auth failure (dead token), bounded by the
     * live pool (each tried token is recorded; a repeat or an exhausted pool
     * ends the loop). Null when no token is live or nothing crosses threshold.
     */
    private suspend fun search(
        query: TrackQuery,
        bypassRateLimit: Boolean,
    ): Triple<QbdlxTrack, Float, String>? {
        var token = credentialStore.activeToken() ?: return null
        val tried = mutableSetOf<String>()
        var guard = 0
        while (guard++ < MAX_TOKEN_ATTEMPTS) {
            tried += token
            try {
                for (term in query.searchTerms()) {
                    val candidates = callLimited(bypassRateLimit) {
                        apiClient.search(term, token)
                    } ?: continue // api error / 429 / acquire-denied (already reported)
                    val match = candidates
                        .map { it to confidence(query, it) }
                        .filter { it.second >= QobuzCandidateMatcher.MIN_CONFIDENCE }
                        .maxByOrNull { it.second }
                    if (match != null) return Triple(match.first, match.second, token)
                }
                return null // searched all terms, no match (search itself succeeded)
            } catch (e: QbdlxAuthException) {
                // Dead token, not a health failure: mark + rotate, don't trip breaker.
                Log.w(TAG, "search auth-failed (${e.status}); marking token dead + rotating")
                credentialStore.markDead(token)
                token = credentialStore.activeToken()?.takeUnless { it in tried } ?: return null
            }
        }
        return null
    }

    /**
     * Resolve [track] to a signed FLAC URL, rotating tokens on death/region
     * lock. TokenDead → markDead + next live token (sticky-advance); RegionLocked →
     * iterate [QbdlxCredentialStore.tokensForRegion] (bounded). Bounded by the
     * tried-set + [MAX_TOKEN_ATTEMPTS].
     */
    private suspend fun resolveFile(
        track: QbdlxTrack,
        conf: Float,
        startToken: String,
        formatId: Int,
        bypassRateLimit: Boolean,
    ): SourceResult? {
        val tried = mutableSetOf<String>()
        var token: String? = startToken
        var guard = 0
        while (token != null && guard++ < MAX_TOKEN_ATTEMPTS) {
            if (!tried.add(token)) {
                token = credentialStore.activeToken()?.takeUnless { it in tried }
                continue
            }
            val outcome = resolveOnce(track, token, formatId, bypassRateLimit)
            when (outcome) {
                is Outcome.Resolved -> {
                    credentialStore.recordAlive(token)
                    return build(track, conf, outcome.ok)
                }
                Outcome.Dead -> {
                    credentialStore.markDead(token)
                    token = credentialStore.activeToken()?.takeUnless { it in tried }
                }
                // RegionLocked: the token is fine, the track just isn't licensed
                // for it — try region-matched tokens, don't mark anything dead.
                Outcome.Region -> return resolveRegion(track, conf, tried, formatId, bypassRateLimit)
                Outcome.Abort -> return null // rate-limit/api error, already reported
            }
        }
        return null
    }

    private suspend fun resolveRegion(
        track: QbdlxTrack,
        conf: Float,
        tried: MutableSet<String>,
        formatId: Int,
        bypassRateLimit: Boolean,
    ): SourceResult? {
        // TrackQuery has no country today → null just yields bounded live tokens.
        for (rt in credentialStore.tokensForRegion(null)) {
            if (!tried.add(rt)) continue
            when (val outcome = resolveOnce(track, rt, formatId, bypassRateLimit)) {
                is Outcome.Resolved -> {
                    credentialStore.recordAlive(rt)
                    return build(track, conf, outcome.ok)
                }
                Outcome.Dead -> credentialStore.markDead(rt)
                Outcome.Region -> Unit // still locked on this token, try the next
                Outcome.Abort -> return null
            }
        }
        return null
    }

    /** One getFileUrl attempt, translating exceptions + classification into an [Outcome]. */
    private suspend fun resolveOnce(
        track: QbdlxTrack,
        token: String,
        formatId: Int,
        bypassRateLimit: Boolean,
    ): Outcome {
        val result = try {
            callLimited(bypassRateLimit) { apiClient.getFileUrl(track.id, formatId, token) }
        } catch (e: QbdlxAuthException) {
            // 401 on getFileUrl is the same signal as a UserUnauthenticated body.
            return Outcome.Dead
        } ?: return Outcome.Abort
        return when (result) {
            is QbdlxResolveResult.Ok -> Outcome.Resolved(result)
            QbdlxResolveResult.TokenDead -> Outcome.Dead
            QbdlxResolveResult.RegionLocked -> Outcome.Region
        }
    }

    private sealed interface Outcome {
        data class Resolved(val ok: QbdlxResolveResult.Ok) : Outcome
        object Dead : Outcome
        object Region : Outcome
        object Abort : Outcome
    }

    private fun build(track: QbdlxTrack, conf: Float, ok: QbdlxResolveResult.Ok): SourceResult {
        val img = track.album?.image
        val art = img?.large ?: img?.thumbnail ?: img?.small
        return SourceResult(
            sourceId = id,
            downloadUrl = ok.url,
            downloadHeaders = emptyMap(),
            format = AudioFormat(
                codec = ok.codec,
                bitrateKbps = 0, // FLAC is VBR; canonical value comes post-download.
                sampleRateHz = ok.sampleRateHz,
                bitsPerSample = ok.bitDepth,
            ),
            confidence = conf,
            sourceTrackId = track.id.toString(),
            coverArtUrl = art,
        )
    }

    private fun confidence(query: TrackQuery, candidate: QbdlxTrack): Float =
        QobuzCandidateMatcher.confidence(
            query = query,
            candTitle = candidate.title,
            candArtist = candidate.performer?.name.orEmpty(),
            candIsrc = candidate.isrc,
            candDurationSec = candidate.duration,
            candStreamable = candidate.streamable,
        )

    /**
     * Wraps an API call with rate-limiter bookkeeping (mirrors
     * [QobuzSource.callLimited]). Returns null on rate-limit denial / api
     * error (already reported). [QbdlxAuthException] is RETHROWN — token
     * rotation is the caller's concern and a dead token must not trip the
     * breaker.
     */
    private suspend fun <T> callLimited(
        bypassRateLimit: Boolean,
        block: suspend () -> T,
    ): T? {
        if (!bypassRateLimit && !rateLimiter.acquire(id)) return null
        return try {
            block().also { rateLimiter.reportSuccess(id) }
        } catch (e: QbdlxAuthException) {
            throw e // rotation concern; do NOT report (not a health failure)
        } catch (e: CancellationException) {
            throw e // never swallow cancellation as a failure
        } catch (e: QbdlxApiException) {
            if (e.status == 429) rateLimiter.reportRateLimited(id) else rateLimiter.reportFailure(id)
            Log.w(TAG, "qbdlx api call failed status=${e.status}")
            null
        } catch (e: Exception) {
            rateLimiter.reportFailure(id)
            Log.w(TAG, "qbdlx call threw ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    companion object {
        const val SOURCE_ID = "qbdlx_qobuz"
        private const val TAG = "QbdlxSource" // no "Qobuz" — keeps the source out of shared logcat diagnostics

        /**
         * Hard ceiling on token rotations per phase. The tried-set is the real
         * terminator (a finite pool exhausts); this just bounds a misbehaving
         * credential store from spinning the retry loop.
         * ponytail: fixed cap; tried-set already guarantees termination.
         */
        private const val MAX_TOKEN_ATTEMPTS = 6
    }
}
