package com.stash.core.data.listenbrainz

import android.util.Log
import com.stash.core.data.listen.Listen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HTTP client for the ListenBrainz submission API.
 *
 * ListenBrainz is MetaBrainz's open scrobbling service — the same act of recording
 * what you played, but the resulting data is public and exportable rather than
 * locked in a private silo, which is why users keep asking for it alongside
 * Last.fm rather than instead of it.
 *
 * Auth is a single user token pasted from `listenbrainz.org/profile`, sent as
 * `Authorization: Token <token>`. No OAuth dance, no session handshake, no signed
 * request — which makes this by far the cheapest of the integrations to get right.
 *
 * Submission shape:
 * ```
 * POST /1/submit-listens
 * { "listen_type": "single",
 *   "payload": [{ "listened_at": 1785365937,
 *                 "track_metadata": { "artist_name": …, "track_name": …,
 *                                     "release_name": …,
 *                                     "additional_info": { … } } }] }
 * ```
 *
 * `listened_at` is **seconds**, not millis — the single most likely thing to get
 * silently wrong here, since a millis value is accepted as a timestamp far in the
 * future rather than rejected.
 */
@Singleton
class ListenBrainzApiClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {

    /**
     * Test seam: tests assign a MockWebServer URL before calling any endpoint.
     * Production paths leave this on the default. Kept off the constructor
     * signature because mixing `@Inject` with a default-valued parameter
     * generates two JVM constructors and Hilt rejects the ambiguous injection
     * site.
     */
    internal var baseUrl: String = DEFAULT_BASE_URL

    /** Outcome shaped so the sink can map it to the drain loop's retry semantics. */
    sealed interface Response {
        data object Accepted : Response

        /** 4xx — bad token or bad payload. Retrying unchanged will not help. */
        data class Refused(val code: Int, val message: String?) : Response

        /** 5xx, timeout, offline. The listens are fine; the service is not. */
        data class Unavailable(val message: String?) : Response
    }

    /**
     * Submits finished listens. `listen_type` is `single` for one and `import` for
     * several — ListenBrainz rejects a multi-item payload sent as `single`, which is
     * the second easy mistake after the timestamp unit.
     */
    suspend fun submitListens(token: String, listens: List<Listen>): Response {
        if (listens.isEmpty()) return Response.Accepted
        val body = buildJsonObject {
            put("listen_type", if (listens.size == 1) "single" else "import")
            putJsonArray("payload") {
                listens.forEach { add(trackMetadata(it, includeTimestamp = true)) }
            }
        }
        return post(token, body)
    }

    /**
     * "Currently playing" ping. Carries no `listened_at` by definition — a
     * now-playing listen has not finished, so it has no completion time, and
     * including one is an error rather than a harmless extra field.
     */
    suspend fun submitPlayingNow(token: String, listen: Listen): Response {
        val body = buildJsonObject {
            put("listen_type", "playing_now")
            putJsonArray("payload") { add(trackMetadata(listen, includeTimestamp = false)) }
        }
        return post(token, body)
    }

    /**
     * Verifies a pasted token before we store it, so a typo fails at paste time.
     *
     * **The status code is not the answer here.** `/1/validate-token` replies
     * `HTTP 200` with `{"code":200,"message":"Token invalid.","valid":false}` for a
     * bad token — it reports on the token, it does not reject the request. Checking
     * only `isSuccessful` therefore accepts literally any string, which is exactly
     * what shipped until an on-device test connected with "not-a-real-token-12345".
     * The `valid` field is the only thing that actually answers the question.
     */
    suspend fun validateToken(token: String): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/1/validate-token")
            .header("Authorization", "Token ${token.trim()}")
            .get()
            .build()
        runCatching {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use false
                val body = response.body?.string().orEmpty()
                // Fail closed on an unreadable body: storing a token we could not
                // confirm is how you get a connection that never scrobbles.
                runCatching {
                    Json.parseToJsonElement(body).jsonObject["valid"]?.jsonPrimitive?.content == "true"
                }.getOrDefault(false)
            }
        }.getOrElse {
            Log.d(TAG, "token validation failed: ${it.message}")
            false
        }
    }

    private fun trackMetadata(listen: Listen, includeTimestamp: Boolean): JsonObject =
        buildJsonObject {
            // Seconds, not millis. ListenBrainz accepts a millis value happily and
            // files the listen tens of thousands of years from now.
            if (includeTimestamp) put("listened_at", listen.startedAtMs / 1000)
            putJsonObject("track_metadata") {
                put("artist_name", listen.artist)
                put("track_name", listen.title)
                listen.album?.let { put("release_name", it) }
                putJsonObject("additional_info") {
                    put("media_player", "Stash")
                    put("submission_client", "Stash")
                    if (listen.durationMs > 0) put("duration_ms", listen.durationMs)
                }
            }
        }

    private suspend fun post(token: String, body: JsonObject): Response =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl/1/submit-listens")
                .header("Authorization", "Token ${token.trim()}")
                .post(body.toString().toRequestBody(JSON))
                .build()
            runCatching {
                okHttpClient.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> Response.Accepted
                        response.code in 400..499 -> Response.Refused(
                            response.code,
                            response.body?.string()?.take(200),
                        )
                        else -> Response.Unavailable("HTTP ${response.code}")
                    }
                }
            }.getOrElse { t ->
                if (t is kotlinx.coroutines.CancellationException) throw t
                Response.Unavailable(t.message)
            }
        }

    private companion object {
        private const val TAG = "ListenBrainz"
        private const val DEFAULT_BASE_URL = "https://api.listenbrainz.org"
        private val JSON = "application/json".toMediaType()
    }
}
