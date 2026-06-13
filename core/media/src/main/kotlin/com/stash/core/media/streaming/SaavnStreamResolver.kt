package com.stash.core.media.streaming

import android.util.Base64
import android.util.Log
import com.stash.core.data.db.entity.TrackEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Streaming resolver that queries the JioSaavn API for audio stream URLs.
 * Covers Indian music that isn't in the Qobuz catalog and may not be on
 * YouTube Music (or fails to resolve via InnerTube/yt-dlp).
 *
 * ## API endpoints used
 *
 * 1. **Search** — `search.getResults` on `www.jiosaavn.com/api.php`:
 *    Returns song metadata including `encrypted_media_url`.
 *
 * 2. **Media URL decryption** — The `encrypted_media_url` is encrypted
 *    with DES-ECB using the well-known key `38346591`. After decryption,
 *    the URL contains a quality suffix (`_96`, `_160`, `_320`) that we
 *    upgrade to `_320` for the best available quality (320 kbps AAC).
 *
 * ## Quality
 *
 * JioSaavn serves AAC at three tiers: 96, 160, 320 kbps. The `320kbps`
 * field in the API response indicates whether the track has a 320k
 * version available. We always request 320k and let the CDN downgrade
 * if unavailable. Origin is stamped as `"saavn"` so the UI can display
 * "via Saavn (AAC 320k)".
 *
 * ## Returns null when
 *
 * - The search returns no results for the artist/title query.
 * - The top result's `encrypted_media_url` is absent or empty.
 * - Decryption fails (should never happen with the correct key).
 * - The network call fails or times out.
 */
@Singleton
class SaavnStreamResolver @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun resolve(track: TrackEntity): StreamUrl? {
        val artist = track.artist.trim()
        val title = track.title.trim()
        if (artist.isBlank() && title.isBlank()) return null

        val query = "$artist $title".trim()
        val t0 = System.currentTimeMillis()
        Log.d(TAG, "resolve attempt id=${track.id} query='$query'")

        val result = withTimeoutOrNull(TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                searchAndResolve(query, track)
            }
        }

        val dt = System.currentTimeMillis() - t0
        if (result != null) {
            Log.d(TAG, "resolved id=${track.id} dt=${dt}ms origin=$ORIGIN")
            Log.d("LATDIAG", "saavn-resolver: hit dt=${dt}ms id=${track.id} query='$query'")
        } else {
            Log.d(TAG, "no result id=${track.id} dt=${dt}ms query='$query'")
            Log.d("LATDIAG", "saavn-resolver: miss dt=${dt}ms id=${track.id} query='$query'")
        }

        return result
    }

    private fun searchAndResolve(query: String, track: TrackEntity): StreamUrl? {
        val url = "https://www.jiosaavn.com/api.php".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("__call", "search.getResults")
            ?.addQueryParameter("p", "1")
            ?.addQueryParameter("q", query)
            ?.addQueryParameter("_format", "json")
            ?.addQueryParameter("_marker", "0")
            ?.addQueryParameter("ctx", "wap6dot0")
            ?.addQueryParameter("n", "5")
            ?.build() ?: return null

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        val responseBody = try {
            okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "search HTTP ${resp.code}")
                    return null
                }
                resp.body?.string()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "search failed: ${e.message}")
            return null
        }

        if (responseBody.isNullOrBlank()) return null

        val root = try {
            json.parseToJsonElement(responseBody).jsonObject
        } catch (e: Exception) {
            Log.w(TAG, "search parse failed: ${e.message}")
            return null
        }

        val results = root["results"]?.jsonArray ?: return null
        if (results.isEmpty()) {
            Log.d(TAG, "no search results for '$query'")
            return null
        }

        // Pick the best match — first result that passes both title validation
        // and duration proximity. v0.9.x fix: prior versions only checked
        // duration, which let JioSaavn return completely unrelated tracks
        // (e.g. "KAMIJO — BASTILLE" for "Marshmello ft. Bastille - Happier")
        // that happened to share a similar duration.
        val requestWords = significantWords(track.title)
        for (result in results) {
            val song = result.jsonObject
            val encryptedUrl = song["encrypted_media_url"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() } ?: continue

            val songTitle = song["song"]?.jsonPrimitive?.contentOrNull ?: ""
            val songArtist = song["primary_artists"]?.jsonPrimitive?.contentOrNull ?: ""

            // Title/artist word-overlap gate: at least one significant word
            // from the requested title must appear in the candidate's title
            // or artist, case-insensitive. This catches gross mismatches
            // where JioSaavn returns a totally different song.
            val candidateWords = significantWords(songTitle) + significantWords(songArtist)
            val overlap = requestWords.count { it in candidateWords }
            if (requestWords.isNotEmpty() && overlap == 0) {
                Log.d(TAG, "skipping '$songArtist — $songTitle' — zero title word overlap")
                continue
            }

            // Check duration proximity if available
            val durationSec = song["duration"]?.jsonPrimitive?.contentOrNull
                ?.toIntOrNull()
            if (durationSec != null && track.durationMs > 0) {
                val candidateMs = durationSec * 1000L
                val drift = kotlin.math.abs(track.durationMs - candidateMs).toDouble() /
                    track.durationMs.toDouble()
                if (drift > 0.30) {
                    Log.d(TAG, "skipping '$songTitle' — duration drift ${(drift * 100).toInt()}%")
                    continue
                }
            }

            val decryptedUrl = decryptMediaUrl(encryptedUrl) ?: continue

            // Upgrade quality to 320kbps
            val highQualityUrl = decryptedUrl
                .replace("_96.mp4", "_320.mp4")
                .replace("_96.m4a", "_320.m4a")
                .replace("_160.mp4", "_320.mp4")
                .replace("_160.m4a", "_320.m4a")

            // Check if 320kbps is available
            val has320 = song["320kbps"]?.jsonPrimitive?.contentOrNull == "true"
            val finalUrl = if (has320) highQualityUrl else decryptedUrl

            Log.d(TAG, "matched: '$songArtist — $songTitle' has320=$has320")

            // Cover art — upgrade to 500x500
            val coverUrl = song["image"]?.jsonPrimitive?.contentOrNull
                ?.replace("150x150", "500x500")
                ?.replace("50x50", "500x500")

            return StreamUrl(
                url = finalUrl,
                // JioSaavn CDN URLs don't have explicit expiry; use 1 hour
                expiresAtMs = System.currentTimeMillis() + DEFAULT_TTL_MS,
                codec = "aac",
                bitsPerSample = null,
                sampleRateHz = null,
                bitrateKbps = if (has320) 320 else 96,
                coverArtUrl = coverUrl,
                origin = ORIGIN,
            )
        }

        return null
    }

    /**
     * Decrypts the JioSaavn `encrypted_media_url` using DES-ECB with the
     * well-known key `38346591`. The encrypted URL is Base64-encoded
     * ciphertext; after decryption it's a plain HTTP URL to the CDN.
     */
    private fun decryptMediaUrl(encryptedUrl: String): String? {
        return try {
            val keyBytes = DES_KEY.toByteArray(Charsets.UTF_8)
            val secretKey = SecretKeySpec(keyBytes, "DES")
            val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)
            val encrypted = Base64.decode(encryptedUrl, Base64.DEFAULT)
            val decrypted = cipher.doFinal(encrypted)
            String(decrypted, Charsets.UTF_8).trim()
        } catch (e: Exception) {
            Log.w(TAG, "DES decryption failed: ${e.message}")
            null
        }
    }

    /**
     * Tokenizes a title/artist string into a set of lower-cased words,
     * dropping noise tokens shorter than 3 chars ("ft", "by", "a", etc.).
     * Used to compute word-overlap between the requested track and the
     * JioSaavn candidate.
     */
    private fun significantWords(text: String): Set<String> {
        return text.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(" ")
            .filter { it.length >= 3 }
            .toSet()
    }

    companion object {
        private const val TAG = "SaavnStreamResolver"
        const val ORIGIN = "saavn"

        /** Well-known DES key for JioSaavn encrypted_media_url decryption. */
        private const val DES_KEY = "38346591"

        /** Total budget for the resolve call. */
        private const val TIMEOUT_MS = 8_000L

        /** Fallback TTL for stream URLs without explicit expiry. */
        private const val DEFAULT_TTL_MS = 60 * 60 * 1000L

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
