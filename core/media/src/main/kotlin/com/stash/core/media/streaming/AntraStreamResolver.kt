package com.stash.core.media.streaming

import android.content.Context
import android.util.Log
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.antra.AntraClient
import com.stash.data.download.lossless.antra.AntraCloudflareException
import com.stash.data.download.lossless.antra.AntraCredentialStore
import com.stash.data.download.lossless.antra.AntraJobGate
import com.stash.data.download.lossless.antra.AntraRateLimitedException
import com.stash.data.download.lossless.resolvedSpotifyTrackUrl
import com.stash.data.download.lossless.spotifyresolve.SpotifyUriResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Stream-side adapter for antra. Unlike the Qobuz proxies (which return a
 * signed CDN URL the player streams directly), antra has no streamable URL —
 * a download job produces a file. So "streaming" here means **fetch the FLAC
 * to an evictable cache file and play that local file**. This also avoids
 * putting cookies into ExoPlayer and dodges the "file exists only after the
 * job finishes" race.
 *
 * Crucially, the cache is keyed by track id: a repeat play of an
 * already-fetched track is a **cache hit that spends no quota** (no job is
 * enqueued). Only a cache miss runs job→poll→download.
 *
 * Returns null (registry fails over to YouTube) when: the track has no
 * Spotify URI, antra isn't connected, quota is exhausted, the job never
 * completes, or the whole flow exceeds [STREAM_TIMEOUT_MS]. On a Cloudflare
 * `403` the credentials are marked stale. Registered after kennyy/squid and
 * before youtube in [StreamSourceRegistry].
 */
@Singleton
class AntraStreamResolver internal constructor(
    private val client: AntraClient,
    private val store: AntraCredentialStore,
    private val cacheRoot: File,
    private val jobGate: AntraJobGate,
    private val spotifyUriResolver: SpotifyUriResolver,
) {

    @Inject constructor(
        @ApplicationContext context: Context,
        client: AntraClient,
        store: AntraCredentialStore,
        jobGate: AntraJobGate,
        spotifyUriResolver: SpotifyUriResolver,
    ) : this(client, store, context.cacheDir, jobGate, spotifyUriResolver)

    /**
     * In-flight resolves keyed by track id. The cache file is written only
     * after the job-gate releases, so two concurrent callers for the same
     * uncached track (the next-track prefetch + a foreground tap, or two
     * racing prefetches) would each see no cache, create their own job, and
     * spend a separate single — observed on-device as 3 jobs for one track.
     * The first caller owns the work and shares its result through this map;
     * the rest join. Guarded by [inFlightMutex] for atomic get-or-create.
     */
    private val inFlight = HashMap<Long, CompletableDeferred<StreamUrl?>>()
    private val inFlightMutex = Mutex()

    suspend fun resolve(track: TrackEntity): StreamUrl? {
        val spotifyUrl = TrackQuery(
            artist = track.artist,
            title = track.title,
            spotifyUri = track.spotifyUri,
            trackId = track.id,
            durationMs = track.durationMs,
            album = track.album,
        ).resolvedSpotifyTrackUrl(spotifyUriResolver) ?: return null
        if (!store.isConnected()) return null

        val cacheFile = cacheFileFor(track.id)
        if (cacheFile.exists() && cacheFile.length() > 0) {
            Log.d(TAG, "cache hit id=${track.id} (no quota spend)")
            return streamUrlFor(cacheFile)
        }

        // Coalesce concurrent resolves of the same track onto one job.
        val (shared, isOwner) = inFlightMutex.withLock {
            inFlight[track.id]?.let { it to false }
                ?: CompletableDeferred<StreamUrl?>().also { inFlight[track.id] = it } to true
        }
        if (!isOwner) {
            Log.d(TAG, "join in-flight resolve id=${track.id} (no extra quota spend)")
            return shared.await()
        }

        return try {
            val result = fetchToCache(track, spotifyUrl, cacheFile)
            shared.complete(result)
            result
        } catch (e: Throwable) {
            // Propagate to joiners as a clean miss; the owner still throws so
            // its own catch chain below maps the failure to null per type.
            shared.complete(null)
            throw e
        } finally {
            inFlightMutex.withLock { inFlight.remove(track.id) }
        }
    }

    /**
     * Owner-only: the actual job→poll→download→cache flow, with the same
     * failure-to-null mapping as before. Split out so [resolve] can wrap it
     * in the in-flight-coalescing guard.
     */
    private suspend fun fetchToCache(
        track: TrackEntity,
        spotifyUrl: String,
        cacheFile: File,
    ): StreamUrl? {
        return try {
            withTimeout(STREAM_TIMEOUT_MS) {
                // The job portion runs under the shared gate so a stream
                // never collides (429) with a parallel download job or a
                // prefetch. Released before the /download fetch, which
                // creates no new job and needs no slot.
                val jobId = jobGate.withJob {
                    val me = client.me()
                    if (me == null || me.singles_left <= 0) {
                        Log.d(TAG, "skip id=${track.id} (${if (me == null) "no auth" else "quota=0"})")
                        return@withJob null
                    }
                    val created = client.createJob(spotifyUrl, startIndex = 0, endIndex = 1)
                        ?: return@withJob null
                    val status = client.pollStatus(created.job_id)
                    if (status.status != STATUS_COMPLETE) {
                        Log.d(TAG, "job ${created.job_id} terminal=${status.status}")
                        return@withJob null
                    }
                    created.job_id
                } ?: return@withTimeout null

                // Fetch to a .part file, then atomically promote so a
                // partial download is never seen as a valid cache entry.
                cacheFile.parentFile?.mkdirs()
                val tmp = File(cacheFile.path + ".part")
                val ok = client.downloadTo(jobId, tmp)
                if (!ok || tmp.length() == 0L) {
                    tmp.delete()
                    return@withTimeout null
                }
                if (!tmp.renameTo(cacheFile)) {
                    // Fall back to a copy if rename fails (rare cross-fs case).
                    tmp.copyTo(cacheFile, overwrite = true)
                    tmp.delete()
                }
                Log.d(TAG, "fetched+cached id=${track.id} via job $jobId")
                streamUrlFor(cacheFile)
            }
        } catch (e: AntraCloudflareException) {
            Log.w(TAG, "Cloudflare 403 — marking antra credentials stale", e)
            store.markStale()
            null
        } catch (e: AntraRateLimitedException) {
            // Job slot busy (concurrent job elsewhere) — fail over cleanly.
            Log.i(TAG, "429 job-slot busy id=${track.id} — failing over")
            null
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "timeout id=${track.id} after ${STREAM_TIMEOUT_MS}ms")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "resolve threw id=${track.id}: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }

    private fun cacheFileFor(trackId: Long): File = File(File(cacheRoot, CACHE_SUBDIR), "$trackId.flac")

    private fun streamUrlFor(file: File): StreamUrl = StreamUrl(
        // file:// URI for ExoPlayer. On Android the absolute path starts
        // with "/", so this yields the canonical "file:///…" triple-slash
        // form MediaItem.fromUri expects.
        url = "file://" + file.absolutePath,
        // Local file never expires; use Long.MAX_VALUE so the URL cache
        // treats it as permanently valid (it's dropped when the cache file
        // is evicted).
        expiresAtMs = Long.MAX_VALUE,
        codec = "flac",
        bitsPerSample = 24,
        origin = ORIGIN,
    )

    private companion object {
        const val TAG = "AntraStreamResolver"
        const val ORIGIN = "antra"
        const val STATUS_COMPLETE = "complete"
        const val CACHE_SUBDIR = "antra"
        const val STREAM_TIMEOUT_MS = 180_000L
    }
}
