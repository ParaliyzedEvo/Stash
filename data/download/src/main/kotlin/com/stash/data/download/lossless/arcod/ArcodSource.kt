package com.stash.data.download.lossless.arcod

import android.util.Log
import com.stash.data.download.lossless.AggregatorRateLimiter
import com.stash.data.download.lossless.AudioFormat
import com.stash.data.download.lossless.LosslessSource
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.RateLimitState
import com.stash.data.download.lossless.SourceResult
import com.stash.data.download.lossless.TrackQuery
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * [LosslessSource] backed by the Qobuz catalog via the ARCOD (arcod.xyz)
 * Qobuz-DL proxy. Resolves a FLAC URL two ways: the **fast single-GET stream
 * endpoint** ([ArcodClient.streamUrl] — same full Range-capable FLAC the
 * streaming path uses, no server-side render) is tried first; the legacy
 * **job-render lifecycle** (enqueue → poll → URL) is the fallback for builds
 * where the private stream base isn't configured. Both run under the shared
 * [ArcodJobGate] (one ARCOD op app-wide) to protect the operator's account.
 *
 * Auth is a per-user Supabase session ([ArcodCredentialStore]); the Bearer
 * token is attached by [ArcodAuthInterceptor] inside [ArcodClient], so the
 * resolved download URL ([SourceResult.downloadUrl]) is served by an open
 * host that needs no headers.
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
    private val losslessPrefs: LosslessSourcePreferences,
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

            // 3. Acquire a FLAC URL under the shared ArcodJobGate (at most ONE
            // ARCOD op in flight app-wide so a sync's parallel workers can't
            // hammer the operator's single Qobuz account). Prefer the FAST
            // single-GET stream endpoint — same full Range-capable FLAC the
            // streaming path uses, with NO server-side render/poll. Fall back to
            // the legacy job-render lifecycle only when the single GET can't run
            // (stream base unconfigured) or returns nothing.
            val resolved = jobGate.withJob {
                resolveViaStream(item) ?: resolveViaJob(query, item)
            } ?: return null

            SourceResult(
                sourceId = id,
                downloadUrl = resolved.url,
                // The FLAC host serves the file openly (no auth) — no headers.
                downloadHeaders = emptyMap(),
                // Codec is FLAC; the real bit-depth/sample-rate is filled by the
                // post-download probe (AudioDurationExtractor), so leave 0s here.
                format = AudioFormat(codec = "flac", bitrateKbps = 0, sampleRateHz = 0, bitsPerSample = 0),
                confidence = match.confidence,
                sourceTrackId = resolved.trackId,
                coverArtUrl = item.album?.image?.large,
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

    /**
     * Fast path: one stream-URL GET (no job render/poll), at the user's
     * configured download quality tier. Returns null — so the caller falls back
     * to [resolveViaJob] — when the stream base is unconfigured or the GET
     * fails. Mirrors [com.stash.core.media.streaming.ArcodStreamResolver], which
     * needs only the matched track id (never the album id).
     */
    private suspend fun resolveViaStream(item: ArcodTrackItem): ResolvedDownload? {
        val code = losslessPrefs.qualityTierNow().qobuzCode
        val stream = client.streamUrl(item.id, code) ?: return null
        rateLimiter.reportSuccess(id)
        Log.d(TAG, "resolved via single-GET stream track=${item.id} quality=$code")
        return ResolvedDownload(stream.url, item.id.toString())
    }

    /**
     * Fallback: the legacy job-render lifecycle (create → poll → url). Needs the
     * album id as the job-create key; catalog rows occasionally omit it, in
     * which case the track simply can't be enqueued (treated like a no-match —
     * not a source failure).
     */
    private suspend fun resolveViaJob(query: TrackQuery, item: ArcodTrackItem): ResolvedDownload? {
        val album = item.album
        val albumId = album?.id
        if (album == null || albumId == null) {
            Log.d(TAG, "no_album_id for track ${item.id} '${item.title}'")
            return null
        }
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
        val job = client.createJob(request) ?: run {
            Log.d(TAG, "createJob returned null for track ${item.id}")
            rateLimiter.reportFailure(id)
            return null
        }
        val completed = client.pollStatus(job.id) ?: run {
            Log.d(TAG, "pollStatus returned null for job ${job.id}")
            rateLimiter.reportFailure(id)
            return null
        }
        val dl = client.downloadUrlFrom(completed) ?: run {
            Log.d(TAG, "completed job ${job.id} had no download url")
            rateLimiter.reportFailure(id)
            return null
        }
        rateLimiter.reportSuccess(id)
        return ResolvedDownload(dl, job.id)
    }

    /** A resolved FLAC URL + its source-side id (track id or job id, logging only). */
    private data class ResolvedDownload(val url: String, val trackId: String)

    companion object {
        const val SOURCE_ID = "arcod"
        private const val TAG = "ArcodSource"
    }
}
