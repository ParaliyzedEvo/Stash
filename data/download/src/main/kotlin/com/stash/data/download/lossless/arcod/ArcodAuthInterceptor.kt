package com.stash.data.download.lossless.arcod

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches the arcod.xyz Supabase Bearer access token to ARCOD API calls and
 * keeps it fresh.
 *
 * Installed ONLY on a derived OkHttp client inside `ArcodClient` — never the
 * shared client — so it cannot affect other hosts. The [HOSTS] guard is
 * belt-and-suspenders: even if it were ever installed broadly, only arcod hosts
 * are touched. It also never intercepts the refresher's own Supabase call, which
 * runs on the plain shared client and is therefore already isolated.
 *
 * Behaviour:
 * - Reads the current session; if the access token is expired (or within
 *   [SKEW_MS] of expiry) it refreshes proactively before the request goes out.
 * - If a request still comes back `401` (token revoked server-side before its
 *   stated expiry), it refreshes once and retries exactly once — no loop.
 * - With no usable credentials it proceeds unauthenticated and lets the call
 *   401, so the lossless source cleanly fails over to another provider.
 *
 * [runBlocking] is acceptable here: OkHttp interceptors run on a dedicated
 * network dispatch thread (not the main thread), and the suspend work is a
 * fast DataStore read plus an occasional network refresh that the call would
 * otherwise block on anyway.
 */
@Singleton
class ArcodAuthInterceptor @Inject constructor(
    private val store: ArcodCredentialStore,
    private val refresher: ArcodTokenRefresher,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (req.url.host !in HOSTS) return chain.proceed(req) // belt-and-suspenders
        val token = runBlocking { currentValidToken() }
        if (token == null) return chain.proceed(req) // no creds -> let it 401, source fails over
        val resp = chain.proceed(req.newBuilder().header("Authorization", "Bearer $token").build())
        if (resp.code != 401) return resp
        // stale token -> refresh once + retry once
        resp.close()
        val fresh = runBlocking { refresher.refresh() } ?: return chain.proceed(req)
        return chain.proceed(req.newBuilder().header("Authorization", "Bearer $fresh").build())
    }

    /** Valid (unexpired) access token, refreshing first if expired/near-expiry. */
    private suspend fun currentValidToken(): String? {
        val s = store.session() ?: return null
        val expired = s.expiresAtMs <= System.currentTimeMillis() + SKEW_MS
        return if (expired) refresher.refresh() else s.accessToken
    }

    private companion object {
        val HOSTS = setOf("arcod.xyz", "api.arcod.xyz")
        const val SKEW_MS = 30_000L
    }
}
