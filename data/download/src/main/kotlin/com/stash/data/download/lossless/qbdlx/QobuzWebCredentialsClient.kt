package com.stash.data.download.lossless.qbdlx

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Qobuz web-player app credentials (the pair its own site signs requests with). */
data class QobuzWebCreds(val appId: String, val appSecret: String)

/** The live-scraped Qobuz web app_id/secret. A seam so the credential store is testable without OkHttp. */
fun interface QobuzWebCredentials {
    suspend fun fetch(): QobuzWebCreds?
}

/**
 * Scrapes the LIVE Qobuz web-player `app_id` + `app_secret` from its public
 * JS bundle — the same pair `open.qobuz.com` uses.
 *
 * Why scrape instead of bundling: a user-connected account's token must be signed
 * with the app_id it was minted under. Minting it ourselves via THIS pair means
 * the signing pair always matches the token, so the account keeps returning FLAC
 * even if Qobuz rotates the web creds (we just re-scrape). It's the self-healing
 * half — a bundled pair would eventually rot the same way the token pool does.
 *
 * Best-effort: any failure returns null and the caller surfaces "couldn't reach
 * Qobuz, try again". [extractCreds] is pure so the parse is tested without a network.
 */
@Singleton
class QobuzWebCredentialsClient @Inject constructor(
    sharedClient: OkHttpClient,
) {
    internal var httpClient: OkHttpClient = sharedClient
    internal var homeUrl: String = HOME
    internal var origin: String = ORIGIN

    /** Scrape the current (app_id, app_secret), or null if unavailable. */
    suspend fun fetch(): QobuzWebCreds? = withContext(Dispatchers.IO) {
        runCatching {
            val html = get(homeUrl)
            // The home page references several JS chunks; the creds live in one of
            // them. Try each until one matches rather than hard-coding a filename
            // (Qobuz renames the bundle on every deploy).
            val paths = BUNDLE_RE.findAll(html).map { it.value }.distinct().toList()
            for (path in paths) {
                val js = runCatching { get(origin + path) }.getOrNull() ?: continue
                extractCreds(js)?.let { return@runCatching it }
            }
            null
        }.getOrNull()
    }

    /** Pull the first `app_id:"…",app_secret:"…"` pair out of a JS bundle. */
    internal fun extractCreds(js: String): QobuzWebCreds? {
        val m = CREDS_RE.find(js) ?: return null
        return QobuzWebCreds(appId = m.groupValues[1], appSecret = m.groupValues[2])
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Accept", "*/*")
            .get().build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} for $url")
            return resp.body?.string().orEmpty()
        }
    }

    private companion object {
        const val ORIGIN = "https://open.qobuz.com"
        const val HOME = "$ORIGIN/"
        val BUNDLE_RE = Regex("""/resources/[^"']+\.js""")
        val CREDS_RE = Regex("""app_id:"(\d{9})",app_secret:"([a-f0-9]{32})"""")
        const val UA = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36"
    }
}
