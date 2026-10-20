package com.stash.data.lyrics.source

import com.stash.core.common.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/** title/artist -> ISRC via lyrics-api.binimum.org. Answers (including "no match") are remembered for the process. */
@Singleton
class IsrcResolver @Inject constructor(
    client: OkHttpClient,
    private val clock: Clock,
) {
    private val interactiveHttp = client.newBuilder().callTimeout(3, TimeUnit.SECONDS).build()
    private val backgroundHttp = client.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()

    private val cache = object : LinkedHashMap<String, String?>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>?) = size > 64
    }

    /** After a transport failure, don't re-ask for a minute. */
    @Volatile private var failedUntilMs = 0L
    @Volatile private var lastFailure: IOException? = null

    /** Null = no confident match. Throws on a transport/HTTP failure (not remembered, so it stays retryable). */
    suspend fun resolve(query: LyricsQuery): String? {
        val key = "${query.title}|${query.artist}|${query.durationMs?.div(1000)}".lowercase(Locale.ROOT)
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }
        if (clock.now() < failedUntilMs) throw lastFailure ?: IOException("ISRC lookup paused")

        val isrc = try {
            withContext(Dispatchers.IO) { lookup(query) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            lastFailure = e
            failedUntilMs = clock.now() + 60_000L
            throw e
        }
        synchronized(cache) { cache[key] = isrc }
        return isrc
    }

    private fun lookup(query: LyricsQuery): String? {
        val url = API_URL.toHttpUrl().newBuilder()
            .addQueryParameter("track", query.title)
            .addQueryParameter("artist", query.artist)
            .build()
        val request = Request.Builder().url(url).build()
        val http = if (query.interactive) interactiveHttp else backgroundHttp
        val body = http.newCall(request).execute().use { r ->
            if (r.code == 404) return null
            if (!r.isSuccessful) throw AppleTtmlLyricsSource.HttpStatusException(r.code, url.host)
            r.body?.string()
        } ?: return null

        val results = JSONObject(body).optJSONArray("results") ?: return null
        val wantTitle = norm(query.title)
        val wantArtist = norm(query.artist)
        val wantSec = (query.durationMs ?: 0L) / 1000

        var best: Pair<String, Int>? = null
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            val isrc = o.optString("isrc").trim().uppercase(Locale.ROOT).takeIf { it.length == 12 } ?: continue
            val t = norm(o.optString("track_name"))
            val titleScore = when {
                t == wantTitle -> 3
                t.isNotEmpty() && (t.contains(wantTitle) || wantTitle.contains(t)) -> 1
                else -> 0
            }
            if (titleScore == 0) continue
            val a = norm(o.optString("artist_name"))
            if (a.isEmpty() || !(a.contains(wantArtist) || wantArtist.contains(a))) continue
            var score = titleScore
            val dur = o.optLong("duration", 0L)
            if (wantSec > 0 && dur > 0) {
                val diff = abs(wantSec - dur)
                if (diff > 5) continue
                score += if (diff <= 2) 2 else 1
            }
            if (best == null || score > best.second) best = isrc to score
        }
        return best?.first
    }

    private fun norm(s: String) = s.lowercase(Locale.ROOT)
        .replace(PAREN, "")
        .replace(DASH_SUFFIX, "")
        .replace(NON_WORD, " ")
        .trim()

    private companion object {
        const val API_URL = "https://lyrics-api.binimum.org/"
        val PAREN = Regex("""\s*[(\[][^)\]]*[)\]]""")
        val DASH_SUFFIX = Regex("""\s+-\s+.*$""")
        val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
    }
}