package com.stash.data.download.lossless.lucida

import android.util.Log
import com.stash.data.download.lossless.AggregatorRateLimiter
import com.stash.data.download.lossless.AudioFormat
import com.stash.data.download.lossless.LosslessSource
import com.stash.data.download.lossless.LosslessSourceHealthGate
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.RateLimitState
import com.stash.data.download.lossless.SourceResult
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.searchTerms
import com.stash.data.download.lossless.kennyy.KennyyApiClient
import com.stash.data.download.lossless.qobuz.QobuzTrack
import com.stash.data.download.lossless.qobuz.QobuzSource
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class LucidaSource @Inject constructor(
    private val apiClient: LucidaApiClient,
    private val kennyyApiClient: KennyyApiClient, // To search Qobuz catalog
    private val rateLimiter: AggregatorRateLimiter,
    private val healthGate: LosslessSourceHealthGate,
) : LosslessSource {

    override val id: String = SOURCE_ID
    override val displayName: String = "Lucida.to (FLAC)"

    override suspend fun isEnabled(): Boolean {
        return !rateLimiter.stateOf(id).isCircuitBroken
    }

    override suspend fun resolve(query: TrackQuery): SourceResult? =
        resolveInternal(query, bypassRateLimit = false)

    suspend fun resolveImmediate(query: TrackQuery): SourceResult? =
        resolveInternal(query, bypassRateLimit = true)

    private suspend fun resolveInternal(query: TrackQuery, bypassRateLimit: Boolean): SourceResult? {
        // Early exit: if the circuit breaker is open, skip the entire resolve
        // including the expensive Qobuz catalog search that precedes the actual
        // Lucida download call. Without this, every track wasted 1-3 kennyy
        // search calls and ~500ms before hitting the breaker in callLimited.
        if (!bypassRateLimit && rateLimiter.stateOf(id).isCircuitBroken) {
            Log.d(TAG, "Lucida circuit open — skipping resolve for '${query.title}'")
            return null
        }
        Log.d(TAG, "Lucida resolve attempt artist='${query.artist}' title='${query.title}'")

        // 1. Search Qobuz catalog using the public kennyyApiClient to get a track ID.
        // We use the same searchTerms fallback logic as KennyySource.
        var foundTrack: QobuzTrack? = null
        var bestConfidence = 0f
        
        for (term in query.searchTerms()) {
            val searchData = runCatching { kennyyApiClient.search(term) }.getOrNull() ?: continue
            val candidates = searchData.tracks?.items.orEmpty()
            if (candidates.isEmpty()) continue

            // Score candidates
            val scored = candidates.map { it to confidence(query, it) }
            val match = scored.filter { it.second >= MIN_CONFIDENCE }.maxByOrNull { it.second }
            if (match != null) {
                foundTrack = match.first
                bestConfidence = match.second
                break
            }
        }

        val qobuzTrack = foundTrack ?: run {
            Log.d(TAG, "No Qobuz match found for Lucida resolution")
            return null
        }

        // 2. Format a Qobuz track URL for Lucida to fetch.
        val qobuzUrl = "https://open.qobuz.com/track/${qobuzTrack.id}"

        // 3. Resolve using Lucida.
        return callLimited(bypassRateLimit) {
            val pageData = apiClient.resolvePageData(qobuzUrl) ?: return@callLimited null
            
            // The url to request can be the one returned from Lucida, or fallback to the one we constructed
            val targetUrl = pageData.info?.url ?: qobuzUrl

            val downloadTask = apiClient.requestDownload(
                trackUrl = targetUrl,
                token = pageData.token,
                tokenExpiry = pageData.tokenExpiry
            ) ?: return@callLimited null

            if (downloadTask.error != null) {
                Log.w(TAG, "Lucida download request error: ${downloadTask.error}")
                return@callLimited null
            }

            val server = downloadTask.server ?: return@callLimited null
            val handoff = downloadTask.handoff ?: return@callLimited null

            // Poll for status
            var completed = false
            var attempts = 0
            while (!completed && attempts < 30) {
                delay(1000)
                attempts++
                val status = apiClient.checkStatus(server, handoff) ?: continue
                if (status.status == "completed") {
                    completed = true
                    break
                } else if (status.status.contains("failed") || status.status.contains("error")) {
                    Log.w(TAG, "Lucida download task failed in status polling: ${status.status}")
                    return@callLimited null
                }
            }

            if (!completed) {
                Log.w(TAG, "Lucida download task timed out in status polling")
                return@callLimited null
            }

            val downloadUrl = "https://$server.lucida.to/api/fetch/request/$handoff/download"
            
            // Map Qobuz track image
            val albumImage = qobuzTrack.album?.image
            val artUrl = albumImage?.large ?: albumImage?.thumbnail ?: albumImage?.small

            SourceResult(
                sourceId = id,
                downloadUrl = downloadUrl,
                downloadHeaders = emptyMap(),
                format = AudioFormat(
                    codec = "flac",
                    bitrateKbps = 0,
                    sampleRateHz = (qobuzTrack.maximumSamplingRate * 1000f).toInt(),
                    bitsPerSample = qobuzTrack.maximumBitDepth
                ),
                confidence = bestConfidence,
                sourceTrackId = qobuzTrack.id.toString(),
                coverArtUrl = artUrl
            )
        }
    }

    private suspend fun <T> callLimited(bypassRateLimit: Boolean, block: suspend () -> T?): T? {
        if (!bypassRateLimit) {
            if (!rateLimiter.acquire(id)) return null
        }
        return try {
            val result = block()
            if (result != null) {
                rateLimiter.reportSuccess(id)
            } else {
                rateLimiter.reportFailure(id)
            }
            result
        } catch (e: Exception) {
            rateLimiter.reportFailure(id)
            Log.w(TAG, "Lucida call failed", e)
            null
        }
    }

    private fun confidence(query: TrackQuery, candidate: QobuzTrack): Float {
        if (!candidate.streamable) return 0f

        val queryIsrc = query.isrc?.takeIf { it.isNotBlank() }
        val candidateIsrc = candidate.isrc?.takeIf { it.isNotBlank() }
        if (queryIsrc != null && candidateIsrc != null &&
            queryIsrc.equals(candidateIsrc, ignoreCase = true)
        ) {
            return 0.95f
        }

        val titleSim = QobuzSource.jaccard(
            QobuzSource.normalize(query.title),
            QobuzSource.normalize(candidate.title),
        )
        val artistSim = QobuzSource.artistSimilarity(
            QobuzSource.normalize(query.artist),
            QobuzSource.normalize(candidate.performer?.name.orEmpty()),
        )

        val durationFactor: Float = run {
            val queryMs = query.durationMs ?: return@run 1.0f
            if (queryMs <= 0 || candidate.duration <= 0) return@run 1.0f
            val candidateMs = candidate.duration * 1000L
            val drift = abs(queryMs - candidateMs).toDouble() / queryMs.toDouble()
            when {
                drift < 0.05 -> 1.0f
                drift < 0.10 -> 0.85f
                drift < 0.20 -> 0.6f
                else -> 0.3f
            }
        }

        val nameScore = titleSim * artistSim
        return if (nameScore >= 0.9f) {
            nameScore
        } else {
            nameScore * durationFactor
        }
    }

    override suspend fun rateLimitState(): RateLimitState = rateLimiter.stateOf(id)

    companion object {
        const val SOURCE_ID = "lucida"
        private const val TAG = "LucidaSource"
        private const val MIN_CONFIDENCE = 0.5f
    }
}
