package com.stash.core.media.streaming

import android.util.Log
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.arcod.ArcodClient
import com.stash.data.download.lossless.arcod.ArcodJobRequest
import com.stash.data.download.lossless.arcod.ArcodMatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * Stream-URL resolver backed by `dl.arcod.xyz` via [ArcodClient].
 *
 * ARCOD wraps the official Qobuz catalog behind a Supabase-authenticated job
 * queue: search → create-job → poll-until-completed → open download URL. Unlike
 * the kennyy/squid proxies (which hand back a signed Akamai URL with an `etsp`
 * expiry baked in), ARCOD renders the FLAC on demand and returns a short-lived,
 * open, Range-capable link that plays through the default media-source factory
 * with no custom data source.
 *
 * Because the job-render round-trip is slow (search + create + poll), this
 * resolver sits LAST among the lossless sources — after kennyy and squid, before
 * the YouTube lossy fallback. The registry only reaches it when both faster
 * Qobuz proxies miss.
 *
 * Returns null when:
 *  - ARCOD's catalog has no confident match for the track ([ArcodMatcher.best]).
 *  - The matched track has no album id (can't build a job request).
 *  - Job creation, polling, or the completed download URL fails / times out.
 *
 * The returned URL has no parseable expiry, so a conservative ~280s TTL is used
 * (the proxy's links are short-lived; re-resolving is cheap relative to a 403).
 */
@Singleton
class ArcodStreamResolver @Inject constructor(
    private val client: ArcodClient,
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
            val albumId = item.album?.id ?: run {
                Log.d(TAG, "no_album_id id=${track.id} trackId=${item.id}")
                return null
            }
            val request = ArcodJobRequest(
                albumId = albumId,
                trackId = item.id,
                albumTitle = item.album?.title ?: "",
                artistName = item.performer?.name ?: item.album?.artist?.name ?: query.artist,
                artistId = item.album?.artist?.id ?: item.performer?.id ?: 0L,
                coverUrl = item.album?.image?.large ?: "",
                releaseDate = item.album?.releaseDate ?: "",
                tracksCount = item.album?.tracksCount ?: 1,
            )
            val job = client.createJob(request) ?: run {
                Log.d(TAG, "createJob_null id=${track.id} trackId=${item.id}")
                return null
            }
            val completed = client.pollStatus(job.id) ?: run {
                Log.d(TAG, "poll_failed id=${track.id} jobId=${job.id}")
                return null
            }
            val url = client.downloadUrlFrom(completed) ?: run {
                Log.d(TAG, "no_url id=${track.id} jobId=${job.id}")
                return null
            }
            Log.d(TAG, "resolved id=${track.id} origin=$ORIGIN")
            StreamUrl(
                url = url,
                expiresAtMs = System.currentTimeMillis() + URL_TTL_MS,
                codec = "flac",
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
        private const val URL_TTL_MS = 280_000L
    }
}
