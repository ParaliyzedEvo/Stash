package com.stash.data.download.lossless.arcod

import android.util.Log
import com.stash.data.download.lossless.AggregatorRateLimiter
import com.stash.data.download.lossless.AudioFormat
import com.stash.data.download.lossless.LosslessSource
import com.stash.data.download.lossless.RateLimitState
import com.stash.data.download.lossless.SourceResult
import com.stash.data.download.lossless.TrackQuery
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * [LosslessSource] backed by the Qobuz catalog via the ARCOD (arcod.xyz)
 * Qobuz-DL proxy. Unlike [com.stash.data.download.lossless.qobuz.QobuzSource]
 * (squid.wtf, which signs a CDN URL synchronously), ARCOD is a *job-based*
 * proxy: a download is enqueued, polled to completion, and only then yields a
 * short-lived open `.flac` URL.
 *
 * Auth is a per-user Supabase session ([ArcodCredentialStore]); the Bearer
 * token is attached by [ArcodAuthInterceptor] inside [ArcodClient], so the
 * download URL itself ([SourceResult.downloadUrl]) is served by an open
 * `dl.arcod.xyz` host that needs no headers.
 *
 * Every network call is gated behind [AggregatorRateLimiter] for [id]. ARCOD
 * runs on one operator-paid Qobuz account, so the conservative `"arcod"`
 * config (1 token / 2s, burst 2) is the load-bearing safeguard against Stash
 * accidentally getting the operator's account banned.
 */
@Singleton
class ArcodSource @Inject constructor(
    private val client: ArcodClient,
    private val credentialStore: ArcodCredentialStore,
    private val rateLimiter: AggregatorRateLimiter,
    private val jobGate: ArcodJobGate,
) : LosslessSource {

    override val id: String = SOURCE_ID

    override val displayName: String = "ARCOD (Qobuz lossless)"

    /**
     * Usable only when the user has connected an ARCOD session AND the source
     * isn't currently circuit-broken from repeated failures.
     */
    override suspend fun isEnabled(): Boolean {
        val connected = credentialStore.isConnected()
        val broken = rateLimiter.stateOf(id).isCircuitBroken
        Log.d(TAG, "isEnabled: connected=$connected circuitBroken=$broken")
        return connected && !broken
    }

    override suspend fun rateLimitState(): RateLimitState = rateLimiter.stateOf(id)

    override suspend fun resolve(query: TrackQuery): SourceResult? {
        if (!isEnabled()) return null
        if (!rateLimiter.acquire(id)) return null

        return try {
            // 1. Search the proxied Qobuz catalog. ARCOD's get-music takes a
            // free-text query — NEVER the ISRC (its index doesn't key on it)
            // and NEVER TrackQuery.searchTerms() (which would emit the ISRC).
            val items = client.search("${query.artist} ${query.title}".trim())

            // 2. Score and pick the best candidate (real ArcodMatcher).
            // A catalog no-match is NOT a source failure: ARCOD is the
            // 3rd-string source (only reached when kennyy+squid both miss), so
            // it sees miss-prone tracks. Counting misses toward the breaker
            // would self-disable ARCOD for tracks it CAN serve. Mirror
            // QobuzSource: silent `return null`, reserve reportFailure for
            // genuine API/network failures.
            val match = ArcodMatcher.best(query, items) ?: run {
                Log.d(TAG, "no_match artist='${query.artist}' title='${query.title}'")
                return null
            }
            val item = match.item

            // 3. The album id is the job-create key. Catalog rows occasionally
            // omit it; without it we can't enqueue, so fail over cleanly.
            val album = item.album
            val albumId = album?.id
            if (album == null || albumId == null) {
                // Like a no-match: the catalog row simply can't be enqueued.
                // Not a source failure — don't count it toward the breaker.
                Log.d(TAG, "no_album_id for track ${item.id} '${item.title}'")
                return null
            }

            // 4. Enqueue the FLAC render job.
            val request = ArcodJobRequest(
                albumId = albumId,
                trackId = item.id.toString(),
                albumTitle = album.title ?: "",
                artistName = item.performer?.name ?: album.artist?.name ?: query.artist,
                artistId = (album.artist?.id ?: item.performer?.id ?: 0L).toString(),
                coverUrl = album.image?.large ?: "",
                releaseDate = album.releaseDate ?: "",
                tracksCount = album.tracksCount ?: 1,
            )

            // The create→poll→url render lifecycle runs under the shared
            // ArcodJobGate so at most ONE ARCOD job is in flight app-wide (a
            // library sync's parallel download workers would otherwise fire
            // dozens at once and blow the operator's hourly cap).
            val resolved = jobGate.withJob {
                val job = client.createJob(request) ?: run {
                    Log.d(TAG, "createJob returned null for track ${item.id}")
                    rateLimiter.reportFailure(id)
                    return@withJob null
                }
                val completed = client.pollStatus(job.id) ?: run {
                    Log.d(TAG, "pollStatus returned null for job ${job.id}")
                    rateLimiter.reportFailure(id)
                    return@withJob null
                }
                val dl = client.downloadUrlFrom(completed) ?: run {
                    Log.d(TAG, "completed job ${job.id} had no download url")
                    rateLimiter.reportFailure(id)
                    return@withJob null
                }
                rateLimiter.reportSuccess(id)
                ResolvedJob(dl, job.id)
            } ?: return null

            SourceResult(
                sourceId = id,
                downloadUrl = resolved.url,
                // dl.arcod.xyz serves the file openly (no auth) — no headers.
                downloadHeaders = emptyMap(),
                // Codec is FLAC; the real bit-depth/sample-rate is filled by the
                // post-download probe (AudioDurationExtractor), so leave 0s here.
                format = AudioFormat(codec = "flac", bitrateKbps = 0, sampleRateHz = 0, bitsPerSample = 0),
                confidence = match.confidence,
                sourceTrackId = resolved.jobId,
                coverArtUrl = album.image?.large,
            )
        } catch (e: ArcodRateLimitedException) {
            // Genuine over-rate — let the limiter apply the configured backoff
            // (and, after enough 429s, trip the breaker). Fail over for now.
            rateLimiter.reportRateLimited(id)
            null
        } catch (e: CancellationException) {
            // Cooperative cancellation must propagate, never be swallowed as a
            // source failure (otherwise WorkManager cancellation poisons health).
            throw e
        } catch (e: Exception) {
            rateLimiter.reportFailure(id)
            Log.w(TAG, "resolve failed: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }

    /** The completed-job outcome carried out of the [ArcodJobGate] block. */
    private data class ResolvedJob(val url: String, val jobId: String)

    companion object {
        const val SOURCE_ID = "arcod"
        private const val TAG = "ArcodSource"
    }
}
