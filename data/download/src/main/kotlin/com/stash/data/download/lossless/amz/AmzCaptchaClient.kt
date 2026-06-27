package com.stash.data.download.lossless.amz

import android.util.Log
import dagger.Lazy
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Mints an `x-captcha-token` for amz.squid.wtf.
 *
 * Since v0.9.55 amz gates the captcha behind a **page session** (anti-bot
 * tightening — our token mint was returning HTTP 403 "Invalid or expired page
 * session"). The browser establishes that session on page load, so we mirror it:
 *  1. GET `/` (origin root) → captures the `amz_web_sess` cookie (Set-Cookie)
 *     and scrapes the page's `window.__AMZ_WEB.n` **webNonce** from the HTML.
 *  2. GET `/api/captcha/challenge` (carrying the cookie) → [AmzAltchaSolver] PoW.
 *  3. POST `/api/captcha/verify` (cookie + `{payload, webNonce}`) → {token}.
 *
 * The `amz_web_sess` cookie is also required on the downstream data calls
 * (search/track/stream) — the token is bound to it server-side — so it is
 * exposed via [sessionCookie] for [AmzCaptchaInterceptor] to replay.
 *
 * Uses the BARE shared OkHttpClient (no AmzCaptchaInterceptor) — the interceptor
 * bypasses both the `/api/captcha` paths and the non-`/api` root GET, so minting
 * never recurses.
 *
 * Requirement 1: amz omits `expiresAt`; we re-embed amz's raw `parameters`
 * JSON verbatim (only appending the solution) so the server HMAC `signature`
 * still verifies. Do NOT reuse NativeSquidCaptchaSolver's fixed-field builder
 * (it injects `expiresAt`, which would corrupt amz's canonical bytes).
 *
 * The PoW itself is [AmzAltchaSolver] (real PBKDF2-HMAC-SHA256) — NOT squid's
 * `AltchaSolver`. amz's challenge `algorithm` is `"PBKDF2/SHA-256"`, a different
 * derivation; squid's iterated-truncated-SHA256 never matches amz's keyPrefix.
 *
 * Base64 here is `java.util.Base64` (not `android.util.Base64`): minSdk is 26
 * so it's available on-device, and unlike the Android stub it actually encodes
 * under plain JVM unit tests. Output is NO_WRAP-equivalent (no line breaks).
 */
@Singleton
class AmzCaptchaClient @Inject constructor(
    // dagger.Lazy (NOT a direct OkHttpClient) breaks a DI init cycle: the shared
    // OkHttpClient is built from the Set<Interceptor> multibinding, which
    // constructs AmzCaptchaInterceptor → AmzCaptchaClient. Injecting the client
    // directly would make AmzCaptchaClient depend on the very client being
    // constructed (Dagger rejects this at hiltJavaCompile). Lazy.get() defers
    // resolution to mint() time, by which point the singleton is fully built.
    // It IS the interceptor-bearing shared client, but the host-scoped
    // interceptor bypasses /api/captcha AND the root "/" GET so these mint calls
    // carry no token and never recurse.
    private val clientLazy: Lazy<OkHttpClient>,
) {
    internal var baseUrl: String = "https://amz.squid.wtf/api"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * The `amz_web_sess=<value>` cookie pair from the most recent [mint], or null
     * before the first successful mint. [AmzCaptchaInterceptor] replays it on the
     * downstream data calls (the token alone is rejected without it). Only [mint]
     * writes it.
     */
    @Volatile
    var sessionCookie: String? = null
        private set

    suspend fun mint(): String? = withContext(Dispatchers.IO) {
        try {
            val webNonce = loadSession() ?: return@withContext null
            val cookie = sessionCookie

            val challengeRaw = get("$baseUrl/captcha/challenge", cookie) ?: return@withContext null
            val root = json.parseToJsonElement(challengeRaw).jsonObject
            val paramsObj = root["parameters"]?.jsonObject ?: return@withContext null
            val paramsRaw = paramsObj.toString() // raw substring, byte-for-byte, for the verify echo
            val signature = root["signature"]?.jsonPrimitive?.contentOrNull ?: return@withContext null

            val p = paramsObj
            val startMs = System.currentTimeMillis()
            val sol = AmzAltchaSolver.solve(
                nonceHex = p["nonce"]!!.jsonPrimitive.content,
                saltHex = p["salt"]!!.jsonPrimitive.content,
                keyPrefixHex = p["keyPrefix"]!!.jsonPrimitive.content,
                cost = p["cost"]!!.jsonPrimitive.int,
                keyLength = p["keyLength"]!!.jsonPrimitive.int,
            )
            val tookMs = System.currentTimeMillis() - startMs
            val solutionJson =
                """{"counter":${sol.counter},"derivedKey":"${sol.derivedKey}","time":$tookMs}"""
            val payloadJson =
                """{"challenge":{"parameters":$paramsRaw,"signature":"$signature"},"solution":$solutionJson}"""
            val payloadB64 = Base64.getEncoder().encodeToString(payloadJson.toByteArray())
            // verify body now carries the page-session webNonce alongside the PoW
            // payload. ponytail: webNonce is a server-issued base64url token
            // (`b1.<ts>.<id>.<sig>`, no JSON-special chars), so plain interpolation
            // is safe — no escaper needed.
            val verifyBody = """{"payload":"$payloadB64","webNonce":"$webNonce"}"""
            val verifyRaw = post("$baseUrl/captcha/verify", verifyBody, cookie)
                ?: return@withContext null
            json.parseToJsonElement(verifyRaw).jsonObject["token"]?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            Log.w(TAG, "mint failed", e)
            null
        }
    }

    /**
     * Establish the page session: GET the origin root, capture the
     * `amz_web_sess` cookie into [sessionCookie], and return the page's
     * `window.__AMZ_WEB.n` webNonce. Null if the root fails, sets no session
     * cookie, or carries no nonce (any of which means verify would 403).
     */
    private fun loadSession(): String? {
        val rootUrl = baseUrl.toHttpUrl().resolve("/")?.toString() ?: return null
        return clientLazy.get().newCall(
            Request.Builder().url(rootUrl).header("User-Agent", UA).header("Referer", REFERER).get().build(),
        ).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val sess = resp.headers("Set-Cookie")
                .firstOrNull { it.startsWith("$SESSION_COOKIE_NAME=") }
                ?.substringBefore(";")
            val html = resp.body?.string().orEmpty()
            val nonce = WEB_NONCE_RE.find(html)?.groupValues?.getOrNull(1)
            if (sess == null || nonce.isNullOrBlank()) return null
            sessionCookie = sess
            nonce
        }
    }

    private fun get(url: String, cookie: String? = null): String? = clientLazy.get().newCall(
        Request.Builder().url(url).header("User-Agent", UA).header("Referer", REFERER)
            .also { b -> cookie?.let { b.header("Cookie", it) } }
            .get().build(),
    ).execute().use { if (it.isSuccessful) it.body?.string() else null }

    private fun post(url: String, body: String, cookie: String? = null): String? = clientLazy.get().newCall(
        Request.Builder().url(url).header("User-Agent", UA).header("Referer", REFERER)
            .also { b -> cookie?.let { b.header("Cookie", it) } }
            .post(body.toRequestBody(JSON_MT)).build(),
    ).execute().use { if (it.isSuccessful) it.body?.string() else null }

    private companion object {
        const val TAG = "AmzCaptchaClient"
        const val UA = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36"
        const val REFERER = "https://amz.squid.wtf/"
        const val SESSION_COOKIE_NAME = "amz_web_sess"
        // The page embeds `window.__AMZ_WEB={"n":"<webNonce>"}`; grab the n value.
        val WEB_NONCE_RE = Regex("__AMZ_WEB=\\{\"n\":\"([^\"]+)\"")
        val JSON_MT = "application/json; charset=utf-8".toMediaType()
    }
}
