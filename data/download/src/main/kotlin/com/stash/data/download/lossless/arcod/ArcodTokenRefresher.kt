package com.stash.data.download.lossless.arcod

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Refreshes the arcod.xyz Supabase access token.
 *
 * ARCOD authenticates with a Supabase session: a short-lived (~1h) JWT access
 * token plus a long-lived refresh token. When the access token expires, a new
 * one is minted by POSTing the refresh token to Supabase's
 * `/auth/v1/token?grant_type=refresh_token` endpoint.
 *
 * Crucially this uses the **plain shared [OkHttpClient]**, not the
 * Bearer-bearing client the rest of the source uses: the refresh call does NOT
 * authenticate with the (possibly-expired) access token. It authenticates with
 * the public `apikey` (the project's anon key) plus the refresh token in the
 * body. Routing it through the auth interceptor would attach a stale Bearer and
 * could trigger a refresh recursion, so it must bypass it.
 *
 * Refreshes are serialized through a [Mutex] so concurrent callers (e.g. a
 * burst of API calls all hitting a 401 at once) don't race each other into the
 * store. The mutex keeps the store write coherent; callers serialize behind it.
 */
@Singleton
class ArcodTokenRefresher @Inject constructor(
    private val sharedClient: OkHttpClient,
    private val store: ArcodCredentialStore,
) {
    /**
     * Test seam: tests point this at a MockWebServer. Production leaves it on
     * the real Supabase project URL. Kept off the constructor so mixing
     * `@Inject` with a default-valued parameter doesn't generate an ambiguous
     * second JVM constructor that Hilt would reject.
     */
    internal var supabaseUrl = "https://fnlghyzwyoklfqyhqlav.supabase.co"

    private val mutex = Mutex()

    /**
     * Refreshes the access token via Supabase.
     *
     * @return the new access token, or null when there's nothing to refresh, or
     *   when the refresh fails (in which case the store is marked stale so the
     *   user is prompted to reconnect). Single-flight: serialized via [mutex].
     */
    suspend fun refresh(): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val refreshToken = store.session()?.refreshToken ?: return@withLock null

            try {
                val body = """{"refresh_token":"$refreshToken"}"""
                    .toRequestBody(JSON_MEDIA_TYPE)
                val request = Request.Builder()
                    .url("$supabaseUrl/auth/v1/token?grant_type=refresh_token")
                    .header("apikey", ANON_KEY)
                    .header("Authorization", "Bearer $ANON_KEY")
                    .header("Content-Type", "application/json")
                    .post(body)
                    .build()

                sharedClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        store.markStale()
                        return@withLock null
                    }
                    val payload = response.body?.string()
                        ?: run {
                            store.markStale()
                            return@withLock null
                        }
                    val parsed = JSON.decodeFromString<RefreshResponse>(payload)
                    val expiresAtMs = System.currentTimeMillis() + parsed.expiresIn * 1000L
                    store.save(
                        accessToken = parsed.accessToken,
                        refreshToken = parsed.refreshToken,
                        expiresAtMs = expiresAtMs,
                    )
                    parsed.accessToken
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                store.markStale()
                null
            }
        }
    }

    @Serializable
    private data class RefreshResponse(
        @kotlinx.serialization.SerialName("access_token") val accessToken: String,
        @kotlinx.serialization.SerialName("refresh_token") val refreshToken: String,
        @kotlinx.serialization.SerialName("expires_in") val expiresIn: Long,
    )

    companion object {
        // Public Supabase anon key (role=anon, project ref fnlghyzwyoklfqyhqlav). Public by design.
        const val ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImZubGdoeXp3eW9rbGZxeWhxbGF2Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzQxMDExODAsImV4cCI6MjA4OTY3NzE4MH0.9J1-JK1jJYunBM6bF-_MLR5UvhDV4BibXordTOzH2_0"

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
