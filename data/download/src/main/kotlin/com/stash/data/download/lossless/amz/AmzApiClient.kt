package com.stash.data.download.lossless.amz

import android.util.Log
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * HTTP client for the public `amz.squid.wtf/api` proxy — an Amazon Music
 * source. Mirrors [com.stash.data.download.lossless.qobuz.QobuzApiClient]
 * in style (lenient kotlinx.serialization, withContext(IO), MockWebServer
 * test seam), but talks JSON-body POSTs instead of query-param GETs.
 *
 * Auth is an `x-captcha-token` header attached automatically by
 * [AmzCaptchaInterceptor], which is registered on the shared
 * [OkHttpClient] (Hilt). This client therefore just injects that shared
 * client and makes requests — the token rides along; this client does
 * NOT handle the captcha token itself.
 *
 * Failure model differs from search/track endpoints:
 *  - HTTP 429 → throw [AmzRateLimitedException] so the aggregator's rate
 *    limiter can apply a source-specific backoff (mirrors the special
 *    meaning Qobuz gives 429 via `QobuzApiException.status == 429`).
 *  - Any other non-2xx, parse failure, or empty body → degrade to an
 *    empty result (`emptyList()` / `null`) so a flaky third-party source
 *    is "skip and try the next" rather than fatal.
 */
@Singleton
class AmzApiClient @Inject constructor(
    private val client: OkHttpClient,
) {

    /**
     * Test seam: tests assign a MockWebServer URL before calling any
     * endpoint. Production paths leave this on the default. Kept off the
     * constructor signature so mixing `@Inject` with a default-valued
     * parameter doesn't generate two JVM constructors (Hilt rejects an
     * ambiguous injection site).
     */
    internal var baseUrl: String = DEFAULT_BASE_URL

    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Search the Amazon Music catalog for tracks. Returns the parsed
     * `trackList`, or an empty list on any non-429 failure.
     *
     * @throws AmzRateLimitedException on HTTP 429.
     */
    suspend fun search(query: String, limit: Int = 25): List<AmzSearchItem> =
        withContext(Dispatchers.IO) {
            val body = buildString {
                append("{\"query\":")
                append(jsonString(query))
                append(",\"country\":\"US\",\"content_type\":\"TRACK\",\"limit\":")
                append(limit)
                append("}")
            }
            val raw = post("$baseUrl/search", body) ?: return@withContext emptyList()
            runCatching { json.decodeFromString<AmzSearchResponse>(raw).trackList }
                .getOrElse { e ->
                    Log.w(TAG, "search parse failed", e)
                    emptyList()
                }
        }

    /**
     * Resolve a track ASIN to its [AmzTrack]: full metadata plus the DRM
     * decryption key and encrypted-CMAF stream URL needed to fetch and decrypt
     * the audio (`ffmpeg -decryption_key`). Returns null on any non-429 failure
     * (HTTP error, parse failure, empty metadata). [AmzTrack.decryptionKey] /
     * [AmzTrack.streamUrl] may be null if the response omits them.
     *
     * @throws AmzRateLimitedException on HTTP 429.
     */
    suspend fun track(asin: String, tier: String = DEFAULT_TIER): AmzTrack? = withContext(Dispatchers.IO) {
        val body = buildString {
            append("{\"asin\":")
            append(jsonString(asin))
            append(",\"tier\":")
            append(jsonString(tier))
            append(",\"country\":\"US\"}")
        }
        val raw = post("$baseUrl/track", body) ?: return@withContext null
        runCatching {
            val resp = json.decodeFromString<AmzTrackResponse>(raw)
            val meta = resp.metadata ?: return@runCatching null
            AmzTrack(
                meta = meta,
                decryptionKey = resp.drm?.key?.takeIf { it.isNotBlank() },
                streamUrl = resp.stream?.url?.let { resolveStreamUrl(it) },
                codec = resp.stream?.codec,
                bitrateBps = resp.stream?.bitrate?.takeIf { it > 0 },
                sampleRateHz = resp.stream?.sampleRate?.takeIf { it > 0 },
            )
        }.getOrElse { e ->
            Log.w(TAG, "track parse failed", e)
            null
        }
    }

    /**
     * Resolve a (usually site-relative) `stream.url` against the API origin
     * into an absolute URL. Relative paths like `/api/stream?asin=…` resolve
     * against [baseUrl]'s host; an already-absolute URL passes through.
     * Returns null if [raw] is blank or unresolvable.
     */
    private fun resolveStreamUrl(raw: String): String? {
        if (raw.isBlank()) return null
        return runCatching { baseUrl.toHttpUrl().resolve(raw)?.toString() }
            .getOrNull()
            ?: raw.takeIf { it.startsWith("http", ignoreCase = true) }
    }

    /**
     * Build the direct stream URL for an ASIN. The shared client's
     * captcha interceptor will attach `x-captcha-token` when this URL is
     * fetched. The ASIN is URL-encoded defensively (ASINs are normally
     * `[A-Z0-9]{10}`, but never trust the upstream shape).
     */
    fun streamUrl(asin: String, tier: String = DEFAULT_TIER): String {
        val encoded = URLEncoder.encode(asin, "UTF-8")
        val encodedTier = URLEncoder.encode(tier, "UTF-8")
        return "$baseUrl/stream?asin=$encoded&country=US&tier=$encodedTier"
    }

    /** JSON-encode [value] to a quoted, escaped JSON string literal. */
    private fun jsonString(value: String): String =
        json.encodeToString(String.serializer(), value)

    /**
     * POST [body] as JSON to [url]. Returns the response body on 2xx,
     * null on any other non-429 outcome (incl. IO failure). Re-throws
     * [CancellationException] so coroutine cancellation isn't swallowed,
     * and [AmzRateLimitedException] for HTTP 429.
     */
    private fun post(url: String, body: String): String? = try {
        client.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Referer", REFERER)
                .post(body.toRequestBody(JSON_MT))
                .build(),
        ).execute().use { response ->
            when {
                response.code == 429 -> throw AmzRateLimitedException()
                response.isSuccessful -> response.body?.string()
                else -> {
                    Log.w(TAG, "POST $url -> HTTP ${response.code}")
                    null
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: AmzRateLimitedException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "POST $url failed", e)
        null
    }

    companion object {
        const val DEFAULT_BASE_URL: String = "https://amz.squid.wtf/api"

        /**
         * Wire `tier` used when a caller doesn't specify one. `best` asks the
         * proxy for the highest available master (native max quality per track).
         */
        const val DEFAULT_TIER: String = "best"
        private const val TAG = "AmzApiClient"
        // Same UA/Referer the captcha mint uses, so search/track/stream
        // present a consistent browser-like identity to the proxy.
        private const val UA =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36"
        private const val REFERER = "https://amz.squid.wtf/"
        private val JSON_MT = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * Thrown when amz.squid.wtf replies HTTP 429 (Too Many Requests). Kept
 * distinct from a generic failure so the aggregator's rate limiter can
 * apply a source-specific backoff rather than tripping the circuit
 * breaker. Mirrors the special meaning Qobuz gives a 429 via
 * `QobuzApiException.status == 429`.
 */
class AmzRateLimitedException : RuntimeException("amz.squid.wtf 429: rate limited")
