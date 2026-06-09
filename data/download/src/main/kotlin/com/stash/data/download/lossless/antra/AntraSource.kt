package com.stash.data.download.lossless.antra

import android.util.Log
import com.stash.data.download.lossless.AggregatorRateLimiter
import com.stash.data.download.lossless.AudioFormat
import com.stash.data.download.lossless.LosslessSource
import com.stash.data.download.lossless.RateLimitState
import com.stash.data.download.lossless.SourceResult
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.spotifyTrackUrl
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * [LosslessSource] backed by antra.hoshi.cfd — an independent, per-user
 * lossless source that resolves a Spotify track URL to a real FLAC from its
 * own multi-source backend (structurally independent of the shared Qobuz
 * proxies, so it engages exactly when kennyy/squid miss).
 *
 * antra is **job-based and quota-gated**, unlike the single-shot Qobuz
 * proxies. A successful resolve therefore does real work (and spends one of
 * the user's `singles_left`): confirm auth+quota via `me()`, sanity-check
 * the URL with `resolve()`, enqueue a single-track job, poll until it's
 * `complete`, then hand [DownloadManager] the job's `/download` URL plus the
 * auth cookie (the bytes fetch needs it too). The existing duration backstop
 * validates the downloaded file.
 *
 * **Gating** (any fail → null, registry fails over cleanly):
 *  - not connected (no harvested cookies),
 *  - track has no Spotify URI (search-tab/YouTube-pathway downloads),
 *  - quota exhausted (`singles_left <= 0`),
 *  - the job never reaches `complete`.
 *
 * On a Cloudflare `403` ([AntraCloudflareException]) the cookie-replay no
 * longer satisfies Cloudflare, so we [AntraCredentialStore.markStale] (the
 * Settings UI surfaces a reconnect prompt) and fail over.
 */
@Singleton
class AntraSource @Inject constructor(
    private val client: AntraClient,
    private val credentialStore: AntraCredentialStore,
    private val rateLimiter: AggregatorRateLimiter,
) : LosslessSource {

    override val id: String = SOURCE_ID

    override val displayName: String = "antra (lossless)"

    /** Usable only when an antra account is connected and not circuit-broken. */
    override suspend fun isEnabled(): Boolean =
        credentialStore.isConnected() && !rateLimiter.stateOf(id).isCircuitBroken

    override suspend fun resolve(query: TrackQuery): SourceResult? {
        if (!isEnabled()) return null
        val spotifyUrl = query.spotifyTrackUrl() ?: return null

        if (!rateLimiter.acquire(id)) return null
        return try {
            // 1. Auth + quota. A null me() means the session is dead; 0
            //    singles means nothing to spend — either way, skip.
            val me = client.me()
            if (me == null || me.singles_left <= 0) {
                Log.d(TAG, "skip: ${if (me == null) "no auth" else "quota=0"}")
                rateLimiter.reportFailure(id)
                return null
            }

            // 2. Sanity-resolve the URL (also yields cover art).
            val resolved = client.resolve(spotifyUrl)
            if (resolved == null) {
                rateLimiter.reportFailure(id)
                return null
            }

            // 3. Enqueue a single-track job and poll to a terminal state.
            val created = client.createJob(spotifyUrl, startIndex = 0, endIndex = 1)
            if (created == null) {
                rateLimiter.reportFailure(id)
                return null
            }
            val status = client.pollStatus(created.job_id)
            if (status.status != STATUS_COMPLETE) {
                Log.d(TAG, "job ${created.job_id} terminal=${status.status} error=${status.error}")
                rateLimiter.reportFailure(id)
                return null
            }

            val cookie = credentialStore.cookieHeader()
            if (cookie == null) {
                // Creds were cleared mid-resolve — can't fetch the bytes.
                rateLimiter.reportFailure(id)
                return null
            }

            rateLimiter.reportSuccess(id)
            SourceResult(
                sourceId = id,
                downloadUrl = client.downloadUrl(created.job_id),
                downloadHeaders = mapOf("Cookie" to cookie),
                format = AudioFormat(
                    codec = "flac",
                    bitrateKbps = 0,
                    sampleRateHz = 0,
                    bitsPerSample = 24,
                ),
                confidence = if (query.isrc != null) 0.95f else 0.85f,
                sourceTrackId = created.job_id,
                coverArtUrl = resolved.artwork_url,
            ).also {
                Log.d(TAG, "resolved '${query.title}' via job ${created.job_id}")
            }
        } catch (e: AntraCloudflareException) {
            // Cookie-replay no longer satisfies Cloudflare — mark stale so
            // the user is prompted to reconnect, and fail over.
            Log.w(TAG, "Cloudflare 403 — marking antra credentials stale", e)
            credentialStore.markStale()
            rateLimiter.reportFailure(id)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "resolve threw: ${e.javaClass.simpleName}: ${e.message}", e)
            rateLimiter.reportFailure(id)
            null
        }
    }

    override suspend fun rateLimitState(): RateLimitState = rateLimiter.stateOf(id)

    companion object {
        const val SOURCE_ID = "antra"
        private const val TAG = "AntraSource"
        private const val STATUS_COMPLETE = "complete"
    }
}
