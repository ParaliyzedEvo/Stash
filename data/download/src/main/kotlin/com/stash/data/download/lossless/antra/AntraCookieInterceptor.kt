package com.stash.data.download.lossless.antra

import android.util.Log
import com.stash.data.download.lossless.LosslessSourcePreferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches antra's auth cookie (`antra_session`, plus `cf_clearance` when
 * one exists) and the browser-style fingerprint headers to every
 * `antra.hoshi.cfd` request, when the user has connected an antra account.
 * Other hosts pass through untouched, so installing this on the antra
 * OkHttp client is a no-op for anything that isn't antra.
 *
 * **Auth model (verified on-device 2026-06-09, Task 10):** the HttpOnly
 * `antra_session` cookie alone authenticates. `cf_clearance` only exists
 * while Cloudflare is actively challenging — it's replayed when present but
 * never required. The browser fingerprint (User-Agent + sec-ch-ua* client
 * hints, shared with `AntraConnectScreen` via [AntraFingerprint]) keeps the
 * replay consistent with the WebView that harvested the session. This
 * cookie-replay (Approach A) passed the full lifecycle including the
 * `/download` binary fetch with zero Cloudflare 403s, so no WebView
 * request-proxy (Approach B) is needed.
 *
 * Cookie values are held in-memory (volatile) and refreshed reactively from
 * [LosslessSourcePreferences]'s flows — reading DataStore inside an OkHttp
 * interceptor would block the dispatcher on every request, so the in-memory
 * cache keeps the hot path synchronous. Mirrors
 * [com.stash.data.download.lossless.squid.SquidWtfCaptchaInterceptor].
 */
@Singleton
class AntraCookieInterceptor @Inject constructor(
    prefs: LosslessSourcePreferences,
) : Interceptor {

    @Volatile private var session: String? = null
    @Volatile private var cfClearance: String? = null

    /**
     * Host this interceptor decorates. Defaults to the real antra host;
     * tests point it at a [okhttp3.mockwebserver.MockWebServer] authority
     * (which runs on 127.0.0.1) to exercise the host-match path.
     */
    @Volatile internal var hostOverride: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Eager seed: the first OkHttp call may fire before the flows emit,
        // so block once at construction. Subsequent updates flow in via the
        // launchIn collectors. Construction is at first @Inject use, well
        // before any antra request, so the brief block is fine.
        runBlocking {
            session = prefs.antraSessionCookieNow()
            cfClearance = prefs.antraCfClearanceNow()
        }
        Log.d(TAG, "init: ${if (isConnected()) "connected" else "not connected"}")
        prefs.antraSessionCookie.onEach { session = it }.launchIn(scope)
        prefs.antraCfClearance.onEach { cfClearance = it }.launchIn(scope)
    }

    // antra_session alone authenticates; cf_clearance is optional (see kdoc).
    private fun isConnected() = !session.isNullOrBlank()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val targetHost = hostOverride ?: ANTRA_HOST
        if (request.url.host != targetHost) return chain.proceed(request)

        val s = session
        if (s.isNullOrBlank()) {
            Log.w(TAG, "not connected; passing ${request.url.encodedPath} through")
            return chain.proceed(request)
        }
        // antra_session alone authenticates; cf_clearance is appended only
        // when Cloudflare actually issued one (usually absent).
        val c = cfClearance
        val antraCookies = buildString {
            append("$SESSION_COOKIE=$s")
            if (!c.isNullOrBlank()) append("; $CF_CLEARANCE_COOKIE=$c")
        }
        val existing = request.header("Cookie")
        val mergedCookie = if (existing.isNullOrBlank()) antraCookies else "$existing; $antraCookies"

        val decorated = request.newBuilder()
            .header("Cookie", mergedCookie)
            // Browser fingerprint — must match the WebView that minted
            // cf_clearance, or Cloudflare rejects the request.
            .header("User-Agent", AntraFingerprint.USER_AGENT)
            .header("Origin", ANTRA_ORIGIN)
            .header("Referer", "$ANTRA_ORIGIN/")
            .header("sec-ch-ua", AntraFingerprint.SEC_CH_UA)
            .header("sec-ch-ua-mobile", "?1")
            .header("sec-ch-ua-platform", "\"Android\"")
            .build()

        Log.d(TAG, "attaching antra cookies to ${request.url.encodedPath}")
        return chain.proceed(decorated)
    }

    internal companion object {
        const val TAG = "AntraCookie"
        const val ANTRA_HOST = "antra.hoshi.cfd"
        const val ANTRA_ORIGIN = "https://antra.hoshi.cfd"
        // antra's login cookie is `antra_session` (verified on-device).
        const val SESSION_COOKIE = "antra_session"
        const val CF_CLEARANCE_COOKIE = "cf_clearance"
        // UA / sec-ch-ua live in the shared [AntraFingerprint] so the
        // connect WebView and this replay stay byte-identical.
    }
}
