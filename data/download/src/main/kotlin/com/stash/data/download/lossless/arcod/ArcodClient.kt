package com.stash.data.download.lossless.arcod

import android.util.Log
import com.stash.data.download.BuildConfig
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * HTTP client for the ARCOD (arcod.xyz) Qobuz-DL proxy.
 *
 * ARCOD wraps the official Qobuz catalog behind the operator's own credentials
 * and a Supabase-authenticated job queue. The flow is:
 *  1. [search] — open `get-music`, no auth, returns Qobuz track items.
 *  2. [createJob] — `POST /v2/downloads` enqueues a FLAC render.
 *  3. [pollStatus] — `GET /v2/downloads/<id>` until `completed`; the completed
 *     job carries a short-lived, open, Range-capable [ArcodJob.downloadUrl].
 *
 * Follows the [com.stash.data.download.lossless.qobuz.QobuzApiClient] pattern: a
 * *derived* OkHttp client re-uses the shared pool/dispatcher/TLS but installs
 * the host-scoped [ArcodAuthInterceptor], so the Supabase Bearer token is only
 * ever attached to arcod hosts and never leaks onto other calls. The Bearer is
 * added by that interceptor — this client only sets the browser-y headers the
 * arcod web app sends.
 */
@Singleton
class ArcodClient @Inject constructor(
    sharedClient: OkHttpClient,
    authInterceptor: ArcodAuthInterceptor,
) {

    private val httpClient: OkHttpClient =
        sharedClient.newBuilder().addInterceptor(authInterceptor).build()

    /** Test seam: tests point this at a MockWebServer URL. */
    internal var baseUrl = "https://arcod.xyz/api"

    /**
     * Base URL (host + path) of the operator's **private** stream endpoint,
     * injected at build time via [BuildConfig.ARCOD_STREAM_BASE] (sourced from
     * `local.properties`/env) so it is never committed to the public repo. Blank
     * when unconfigured — [streamUrl] then no-ops and the caller fails over.
     * A separate host from [baseUrl]'s catalog/download API. Test seam: tests
     * point this at a MockWebServer URL.
     */
    internal var streamBaseUrl = BuildConfig.ARCOD_STREAM_BASE

    /**
     * Search the proxied Qobuz catalog. Non-2xx (other than 429) or a parse
     * failure yields an empty list so the caller cleanly fails over.
     */
    suspend fun search(query: String): List<ArcodTrackItem> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val request = arcodRequest("$baseUrl/get-music?q=$encoded&offset=0").get().build()
        try {
            httpClient.newCall(request).execute().use { response ->
                if (response.code == 429) throw ArcodRateLimitedException()
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string().orEmpty()
                val parsed = ArcodJson.decodeFromString<ArcodSearchResponse>(body)
                parsed.data?.tracks?.items ?: emptyList()
            }
        } catch (e: ArcodRateLimitedException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Enqueue a download job. 429 throws [ArcodRateLimitedException]; any other
     * failure (non-2xx or parse error) returns null.
     */
    suspend fun createJob(request: ArcodJobRequest): ArcodJob? = withContext(Dispatchers.IO) {
        val body = ArcodJson.encodeToString(request).toRequestBody(JSON_MEDIA_TYPE)
        val httpRequest = arcodRequest("$baseUrl/v2/downloads").post(body).build()
        try {
            httpClient.newCall(httpRequest).execute().use { response ->
                if (response.code == 429) throw ArcodRateLimitedException()
                if (!response.isSuccessful) return@withContext null
                val responseBody = response.body?.string().orEmpty()
                ArcodJson.decodeFromString<ArcodJob>(responseBody)
            }
        } catch (e: ArcodRateLimitedException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Poll a job until it reports `completed` (→ return it) or `error` /
     * non-null [ArcodJob.error] (→ null). Returns null once [timeoutMs] elapses
     * without completion. 429 throws; cancellation propagates.
     */
    suspend fun pollStatus(
        jobId: String,
        timeoutMs: Long = 60_000L,
        intervalMs: Long = 1_500L,
    ): ArcodJob? = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val request = arcodRequest("$baseUrl/v2/downloads/$jobId").get().build()
            val job = try {
                httpClient.newCall(request).execute().use { response ->
                    if (response.code == 429) throw ArcodRateLimitedException()
                    if (!response.isSuccessful) {
                        Log.d(TAG, "poll $jobId http ${response.code} (not successful)")
                        return@use null
                    }
                    val body = response.body?.string().orEmpty()
                    ArcodJson.decodeFromString<ArcodJob>(body)
                }
            } catch (e: ArcodRateLimitedException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "poll $jobId parse/io error: ${e.javaClass.simpleName} ${e.message}")
                null
            }

            if (job != null) {
                if (job.status == "completed") return@withContext job
                if (job.status == "error" || job.error != null) {
                    Log.d(TAG, "poll $jobId terminal status='${job.status}' error='${job.error}'")
                    return@withContext null
                }
            }

            if (System.currentTimeMillis() >= deadline) return@withContext null
            delay(intervalMs)
            if (System.currentTimeMillis() >= deadline) return@withContext null
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    /** The open, short-lived download URL carried by a completed job. */
    fun downloadUrlFrom(job: ArcodJob): String? = job.downloadUrl

    /**
     * Resolve a single playable stream URL for a Qobuz [trackId] at the given
     * Qobuz [quality] format_id (6=CD, 7=hi-res, 27=max). One GET — no job
     * render/poll. 429 throws [ArcodRateLimitedException]; any other failure
     * (non-2xx or unparseable body) returns null so the caller fails over.
     */
    suspend fun streamUrl(trackId: Long, quality: Int): ArcodStreamResult? =
        withContext(Dispatchers.IO) {
            // Unconfigured build (no private base injected) → skip cleanly so the
            // registry fails over instead of building an invalid relative URL.
            if (streamBaseUrl.isBlank()) {
                Log.d(TAG, "stream base not configured — skipping arcod stream")
                return@withContext null
            }
            val request = arcodRequest("$streamBaseUrl/$trackId?quality=$quality")
                .get().build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    if (response.code == 429) throw ArcodRateLimitedException()
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body?.string().orEmpty()
                    parseStreamResult(body) ?: run {
                        // First-run shape probe: the dev gave no sample body, so
                        // log the raw payload once when nothing parsed.
                        Log.d(TAG, "stream parse miss trackId=$trackId body='${body.take(200)}'")
                        null
                    }
                }
            } catch (e: ArcodRateLimitedException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

    /**
     * Tolerant parse of the stream GET body. Accepts (a) a bare URL as the whole
     * body, or (b) JSON with the URL at the top level OR one level under `data`
     * (arcod's usual `{success,data:{…}}` envelope), in any of the `url` /
     * `streamUrl` / `downloadUrl` keys, plus an optional `expiresIn`.
     */
    private fun parseStreamResult(body: String): ArcodStreamResult? {
        val trimmed = body.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return ArcodStreamResult(url = trimmed)
        }
        return try {
            val root = ArcodJson.parseToJsonElement(trimmed).jsonObject
            val obj = (root["data"] as? JsonObject) ?: root
            val url = (obj["url"] ?: obj["streamUrl"] ?: obj["downloadUrl"])
                ?.jsonPrimitive?.contentOrNull ?: return null
            val expiresIn = (obj["expiresIn"] ?: root["expiresIn"])
                ?.jsonPrimitive?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
            ArcodStreamResult(url = url, expiresInSec = expiresIn)
        } catch (e: Exception) {
            null
        }
    }

    /** Browser-y headers the arcod web app sends; Bearer is added by the interceptor. */
    private fun arcodRequest(url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Origin", "https://arcod.xyz")
            .header("Referer", "https://arcod.xyz/")

    private companion object {
        const val TAG = "ArcodClient"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}

/**
 * Thrown when an ARCOD endpoint returns HTTP 429. Kept distinct so the
 * lossless rate limiter can back off ARCOD specifically rather than treating
 * it as a generic source failure.
 */
class ArcodRateLimitedException : RuntimeException("arcod rate limited (HTTP 429)")
