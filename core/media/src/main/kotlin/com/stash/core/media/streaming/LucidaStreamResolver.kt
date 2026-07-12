package com.stash.core.media.streaming

import android.util.Log
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.download.lossless.LosslessSourceHealthGate
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.lucida.LucidaSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

@Singleton
class LucidaStreamResolver @Inject constructor(
    private val source: LucidaSource,
    private val healthGate: LosslessSourceHealthGate,
) {
    suspend fun resolve(track: TrackEntity): StreamUrl? {
        if (healthGate.isDegraded(LucidaSource.SOURCE_ID)) {
            Log.d(TAG, "skip id=${track.id} (lucida content-degraded)")
            return null
        }
        
        Log.d(TAG, "resolve attempt id=${track.id} title='${track.title}'")
        val query = TrackQuery(
            artist = track.artist,
            title = track.title,
            album = track.album.takeIf { it.isNotBlank() },
            isrc = track.isrc?.takeIf { it.isNotBlank() },
            durationMs = track.durationMs,
        )

        val result = try {
            withTimeout(STREAM_RESOLVE_TIMEOUT_MS) { source.resolve(query, bypassRateLimit = true) }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "timeout id=${track.id} after ${STREAM_RESOLVE_TIMEOUT_MS}ms")
            null
        } catch (e: Exception) {
            Log.w(TAG, "error resolving lucida stream id=${track.id}", e)
            null
        }

        if (result == null) {
            return null
        }

        // Expiry set to 30 minutes in the future since the resolved download URL is temporary.
        val expiresAtMs = System.currentTimeMillis() + 30 * 60 * 1000L

        return StreamUrl(
            url = result.downloadUrl,
            expiresAtMs = expiresAtMs,
            codec = result.format.codec.takeIf { it.isNotBlank() },
            bitsPerSample = result.format.bitsPerSample.takeIf { it > 0 },
            sampleRateHz = result.format.sampleRateHz.takeIf { it > 0 },
            bitrateKbps = result.format.bitrateKbps.takeIf { it > 0 },
            coverArtUrl = result.coverArtUrl?.takeIf { it.isNotBlank() },
            origin = ORIGIN,
        )
    }

    private companion object {
        const val TAG = "LucidaStreamResolver"
        const val ORIGIN = "lucida"
        const val STREAM_RESOLVE_TIMEOUT_MS = 15_000L // Polling can take a few seconds
    }
}
