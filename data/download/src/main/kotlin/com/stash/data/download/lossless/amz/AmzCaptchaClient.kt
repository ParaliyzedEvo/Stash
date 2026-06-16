package com.stash.data.download.lossless.amz

import android.util.Log
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Mints an `x-captcha-token` for amz.squid.wtf: GET /api/captcha/challenge →
 * [AltchaSolver] PoW → POST /api/captcha/verify → {token}.
 *
 * Uses the BARE shared OkHttpClient (no AmzCaptchaInterceptor) — and the
 * interceptor also bypasses the /api/captcha/ path — so minting never recurses.
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
    private val bareClient: OkHttpClient,
) {
    internal var baseUrl: String = "https://amz.squid.wtf/api"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun mint(): String? = withContext(Dispatchers.IO) {
        try {
            val challengeRaw = get("$baseUrl/captcha/challenge") ?: return@withContext null
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
            val verifyRaw = post("$baseUrl/captcha/verify", """{"payload":"$payloadB64"}""")
                ?: return@withContext null
            json.parseToJsonElement(verifyRaw).jsonObject["token"]?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            Log.w(TAG, "mint failed", e)
            null
        }
    }

    private fun get(url: String): String? = bareClient.newCall(
        Request.Builder().url(url).header("User-Agent", UA).header("Referer", REFERER).get().build()
    ).execute().use { if (it.isSuccessful) it.body?.string() else null }

    private fun post(url: String, body: String): String? = bareClient.newCall(
        Request.Builder().url(url).header("User-Agent", UA).header("Referer", REFERER)
            .post(body.toRequestBody(JSON_MT)).build()
    ).execute().use { if (it.isSuccessful) it.body?.string() else null }

    private companion object {
        const val TAG = "AmzCaptchaClient"
        const val UA = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36"
        const val REFERER = "https://amz.squid.wtf/"
        val JSON_MT = "application/json; charset=utf-8".toMediaType()
    }
}
