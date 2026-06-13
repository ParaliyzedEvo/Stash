package com.stash.data.download.lossless.antra

import android.util.Log
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * HTTP client for the antra.hoshi.cfd job-based lossless API.
 *
 * antra resolves a streaming-service track URL to a real lossless file from
 * its own multi-source backend. Unlike squid.wtf's single-shot download
 * URL, antra is **job-based**: you `resolve` the URL for metadata, `createJob`
 * to enqueue a download (which spends quota), poll `/status` until the job
 * is terminal, then GET `/download` for the FLAC bytes.
 *
 * All calls run on [Dispatchers.IO]. Auth (cookies + browser fingerprint)
 * is attached transparently by [AntraCookieInterceptor] on [httpClient].
 *
 * **Error contract:**
 *  - Cloudflare `403` → throw [AntraCloudflareException] so callers can
 *    `markStale()` the credentials and fall through to the next source.
 *  - Any other non-2xx → `me`/`resolve`/`createJob` return `null`;
 *    `pollStatus` returns a synthesized `status="error"`.
 *
 * Mirrors [com.stash.data.download.lossless.qobuz.QobuzApiClient]'s
 * test-seam style: [httpClient] / [baseUrl] / [json] / [pollIntervalMs] are
 * `internal var`s so tests can point them at a MockWebServer and shrink the
 * poll cadence without touching the injection site.
 */
@Singleton
class AntraClient @Inject constructor(
    sharedClient: OkHttpClient,
    cookieInterceptor: AntraCookieInterceptor,
) {

    /**
     * Derived client carrying the antra cookie/header interceptor. Built off
     * the shared pool (like QobuzApiClient) so antra's auth never leaks onto
     * unrelated calls. `internal var` so tests swap in a MockWebServer-bound
     * client without standing up the interceptor's DataStore-reading init.
     */
    internal var httpClient: OkHttpClient =
        sharedClient.newBuilder().addInterceptor(cookieInterceptor).build()

    /** Test seam — MockWebServer base in tests, antra origin in production. */
    internal var baseUrl: String = ANTRA_BASE

    /** Test seam. */
    internal var json: Json = AntraJson

    /** Poll cadence; shrunk to ~0 in tests. */
    internal var pollIntervalMs: Long = POLL_INTERVAL_MS

    /** Poll ceiling before giving up. */
    internal var pollTimeoutMs: Long = POLL_TIMEOUT_MS

    /** `GET /api/auth/me` — auth confirmation + remaining quota. Null if unauthenticated/non-2xx. */
    suspend fun me(): AntraMe? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/api/auth/me").get().build()
        httpClient.newCall(request).execute().use { resp ->
            guardCloudflare(resp)
            if (!resp.isSuccessful) return@withContext null
            parse(resp, AntraMe.serializer())
        }
    }

    /** `POST /api/resolve` — release/track metadata for [spotifyUrl]. Null on non-2xx. */
    suspend fun resolve(spotifyUrl: String): AntraResolve? = withContext(Dispatchers.IO) {
        val body = json.encodeToString(
            ResolveRequest.serializer(),
            ResolveRequest(url = spotifyUrl, format = ANTRA_FORMAT),
        )
        val request = Request.Builder()
            .url("$baseUrl/api/resolve")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        httpClient.newCall(request).execute().use { resp ->
            guardCloudflare(resp)
            if (!resp.isSuccessful) return@withContext null
            parse(resp, AntraResolve.serializer())
        }
    }

    /** `POST /api/jobs` — enqueue a download for tracks `[startIndex, endIndex)`. Decrements quota. */
    suspend fun createJob(spotifyUrl: String, startIndex: Int, endIndex: Int): AntraJobCreated? =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(
                JobRequest.serializer(),
                JobRequest(
                    url = spotifyUrl,
                    format = ANTRA_FORMAT,
                    start_index = startIndex,
                    end_index = endIndex,
                ),
            )
            val request = Request.Builder()
                .url("$baseUrl/api/jobs")
                .post(body.toRequestBody(JSON_MEDIA))
                .build()
            httpClient.newCall(request).execute().use { resp ->
                guardCloudflare(resp)
                guardRateLimited(resp)
                if (!resp.isSuccessful) return@withContext null
                parse(resp, AntraJobCreated.serializer())
            }
        }

    /**
     * Poll `GET /api/jobs/<id>/status` every [pollIntervalMs] until the job
     * reaches a terminal state ([TERMINAL_STATES]) or [pollTimeoutMs]
     * elapses. On timeout / repeated failure, returns a synthesized
     * `status="error"` so callers always get a non-null terminal verdict.
     */
    suspend fun pollStatus(jobId: String): AntraJobStatus = withContext(Dispatchers.IO) {
        val maxPolls = (pollTimeoutMs / pollIntervalMs.coerceAtLeast(1)).toInt().coerceAtLeast(1)
        var polls = 0
        while (polls < maxPolls) {
            val status = fetchStatus(jobId)
            if (status == null) {
                return@withContext errorStatus(jobId, "status fetch failed")
            }
            if (status.status in TERMINAL_STATES) {
                return@withContext status
            }
            delay(pollIntervalMs)
            polls++
        }
        errorStatus(jobId, "poll timeout after ${pollTimeoutMs}ms")
    }

    /** The bytes URL for a completed job. Fetched with a plain GET (cookies via interceptor). */
    fun downloadUrl(jobId: String): String = "$baseUrl/api/jobs/$jobId/download"

    /**
     * Streams a completed job's FLAC bytes to [dest]. The cookie/header auth
     * is attached by [AntraCookieInterceptor] on [httpClient]. Returns true
     * on a 2xx with bytes written; false on non-2xx. Throws
     * [AntraCloudflareException] on a Cloudflare `403`. Used by the stream
     * resolver to cache the file for local playback (no cookie in ExoPlayer).
     */
    suspend fun downloadTo(jobId: String, dest: File): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(downloadUrl(jobId)).get().build()
        httpClient.newCall(request).execute().use { resp ->
            guardCloudflare(resp)
            if (!resp.isSuccessful) return@withContext false
            val body = resp.body ?: return@withContext false
            dest.outputStream().use { out -> body.byteStream().copyTo(out) }
            true
        }
    }

    // ── Internals ───────────────────────────────────────────────────────

    private fun fetchStatus(jobId: String): AntraJobStatus? {
        val request = Request.Builder().url("$baseUrl/api/jobs/$jobId/status").get().build()
        httpClient.newCall(request).execute().use { resp ->
            guardCloudflare(resp)
            if (!resp.isSuccessful) return null
            return parse(resp, AntraJobStatus.serializer())
        }
    }

    /** Throws [AntraCloudflareException] when antra is behind a Cloudflare block (`403`). */
    private fun guardCloudflare(resp: Response) {
        if (resp.code == 403) {
            Log.w(TAG, "Cloudflare 403 on ${resp.request.url.encodedPath} (cf-mitigated=${resp.header("cf-mitigated")})")
            throw AntraCloudflareException()
        }
    }

    /**
     * Throws [AntraRateLimitedException] on a `429` so the caller can treat a
     * concurrent-job collision as a transient backoff (not a hard failure
     * that trips the circuit breaker). antra returns 429 when a second job is
     * created while one is still running — its single-concurrent-job limit.
     */
    private fun guardRateLimited(resp: Response) {
        if (resp.code == 429) {
            Log.i(TAG, "429 on ${resp.request.url.encodedPath} — antra job slot busy")
            throw AntraRateLimitedException()
        }
    }

    private fun <T> parse(resp: Response, serializer: DeserializationStrategy<T>): T? {
        val body = resp.body?.string().orEmpty()
        return runCatching { json.decodeFromString(serializer, body) }
            .onFailure { Log.w(TAG, "parse failed: ${it.message}") }
            .getOrNull()
    }

    private fun errorStatus(jobId: String, error: String) =
        AntraJobStatus(job_id = jobId, status = "error", error = error)

    @Serializable
    private data class ResolveRequest(val url: String, val format: String)

    @Serializable
    private data class JobRequest(
        val url: String,
        val format: String,
        val start_index: Int,
        val end_index: Int,
    )

    internal companion object {
        const val TAG = "AntraClient"
        const val ANTRA_BASE = "https://antra.hoshi.cfd"
        const val ANTRA_FORMAT = "lossless-24"
        const val POLL_INTERVAL_MS = 2_000L
        const val POLL_TIMEOUT_MS = 180_000L
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        val TERMINAL_STATES = setOf("complete", "failed", "error")
    }
}

/**
 * Thrown when antra returns a Cloudflare `403`. Distinct from a generic
 * failure so [AntraSource] / [AntraStreamResolver] can `markStale()` the
 * credentials (the cookie-replay no longer satisfies Cloudflare) and prompt
 * the user to reconnect, rather than silently treating it as "no match".
 */
class AntraCloudflareException :
    RuntimeException("antra returned a Cloudflare 403 — credentials stale or fingerprint mismatch")

/**
 * Thrown when antra returns `429 Too Many Requests` — almost always its
 * single-concurrent-job limit (a second `POST /api/jobs` while one is still
 * running). Distinct from a generic failure so [AntraSource] can report it
 * as a rate-limit backoff rather than a circuit-breaker-tripping failure:
 * a few parallel sync workers colliding must not brick antra for 30 minutes.
 */
class AntraRateLimitedException :
    RuntimeException("antra returned 429 — job slot busy (single concurrent job)")
