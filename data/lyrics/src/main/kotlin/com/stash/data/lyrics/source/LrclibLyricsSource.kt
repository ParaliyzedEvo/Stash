package com.stash.data.lyrics.source

import com.stash.core.common.AppVersionProvider
import com.stash.data.lyrics.di.LrclibBaseUrl
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LrclibLyricsSource @Inject constructor(
    okHttpClient: OkHttpClient,
    private val appVersion: AppVersionProvider,
    // Qualified so Hilt can resolve the SingletonComponent String binding
    // without colliding with other module-level @Provides String. Default
    // value is preserved for unit-test construction (see LrclibLyricsSourceTest)
    // — Hilt overrides it with the `@Provides @LrclibBaseUrl` value in
    // LyricsModule when the class is constructed through the graph.
    @LrclibBaseUrl private val baseUrl: String = DEFAULT_BASE_URL,
) : LyricsSource {

    override val id: String = "lrclib"
    override val displayName: String = "LRCLIB"

    // lrclib.net can be slow (seconds per call). Bound each call so a hung
    // request can't stall the lyrics sheet near the shared client's 30s ceiling;
    // shares the pool/dispatcher/TLS.
    private val client: OkHttpClient = okHttpClient.newBuilder()
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * Returns null ONLY on a definitive miss (LRCLIB answered and has no
     * lyrics: get 404, empty search, or all candidates gate-rejected).
     * Transport errors, non-404 HTTP statuses (429/5xx), and parse failures
     * THROW instead — the repository must not confuse "couldn't ask" with
     * "asked, no lyrics". Conflating the two permanently miss-stamped ~72%
     * of the library's "No lyrics found" tracks (2026-07-05 forensics).
     */
    override suspend fun resolve(query: LyricsQuery): LyricsResult? = withContext(Dispatchers.IO) {
        // One exact get (artist + title + duration, NO album), then a fuzzy search
        // fallback. We dropped the old 11-rung duration ladder: with lrclib at
        // seconds-per-call it cost ~60s on a miss, and `/api/search` already
        // tolerates ±5s duration drift, so a single get + search (≤2 calls) gets
        // the same hit rate far faster. Album is omitted because album_name
        // strictness 404s the get on any mismatch (the streamed-track common case).
        query.durationMs?.let { ms ->
            val sec = (ms / 1000).toInt()
            if (sec > 0) tryGet(query, sec)?.let { return@withContext it }
        }
        return@withContext trySearch(query)
    }

    private fun tryGet(query: LyricsQuery, durationSec: Int): LyricsResult? {
        val url = "${baseUrl.trimEnd('/')}/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", query.title)
            .addQueryParameter("artist_name", query.artist)
            .addQueryParameter("duration", durationSec.toString())
            .build()
        val req = Request.Builder().url(url).header("User-Agent", userAgent()).get().build()
        client.newCall(req).execute().use { response ->
            // 404 is lrclib's documented "no such track" answer -> definitive miss.
            if (response.code == HTTP_NOT_FOUND) return null
            if (!response.isSuccessful) throw IOException("lrclib get HTTP ${response.code}")
            val body = response.body?.string() ?: throw IOException("lrclib get empty body")
            val dto = JSON.decodeFromString<LrclibGetResponse>(body)
            return LyricsResult(
                sourceId = id,
                plainText = dto.plainLyrics,
                syncedLrc = dto.syncedLyrics,
                instrumental = dto.instrumental,
                language = null,
                sourceLyricsId = dto.id.toString(),
            )
        }
    }

    private fun trySearch(query: LyricsQuery): LyricsResult? {
        val url = "${baseUrl.trimEnd('/')}/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", "${query.artist} ${query.title}")
            .build()
        val req = Request.Builder().url(url).header("User-Agent", userAgent()).get().build()
        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) throw IOException("lrclib search HTTP ${response.code}")
            val body = response.body?.string() ?: throw IOException("lrclib search empty body")
            val list = JSON.decodeFromString<List<LrclibGetResponse>>(body)
            if (list.isEmpty()) return null
            return pickBestSearchHit(query, list)?.let { dto ->
                LyricsResult(
                    sourceId = id,
                    plainText = dto.plainLyrics,
                    syncedLrc = dto.syncedLyrics,
                    instrumental = dto.instrumental,
                    language = null,
                    sourceLyricsId = dto.id.toString(),
                )
            }
        }
    }

    private fun pickBestSearchHit(query: LyricsQuery, hits: List<LrclibGetResponse>): LrclibGetResponse? {
        val target = "${query.artist} ${query.title}".lowercase()
        val baseSec = query.durationMs?.let { (it / 1000).toInt() }
        return hits
            .filter { hit ->
                if (baseSec == null) true
                else hit.duration?.let { kotlin.math.abs(it - baseSec) <= 5 } ?: true
            }
            .maxByOrNull { hit ->
                val candidate = "${hit.artistName.orEmpty()} ${hit.trackName.orEmpty()}".lowercase()
                jaroWinkler(target, candidate)
            }
            ?.takeIf { hit ->
                val candidate = "${hit.artistName.orEmpty()} ${hit.trackName.orEmpty()}".lowercase()
                jaroWinkler(target, candidate) >= 0.85
            }
    }

    private fun userAgent(): String =
        "Stash/${appVersion.versionName} (https://github.com/rawnaldclark/Stash)"

    companion object {
        const val DEFAULT_BASE_URL = "https://lrclib.net/"

        private const val HTTP_NOT_FOUND = 404

        // Per-call ceiling. lrclib was observed at 4-14s/call; 20s bounds a true
        // hang without cutting a legitimately slow response.
        private const val CALL_TIMEOUT_SECONDS = 20L

        private val JSON = Json { ignoreUnknownKeys = true }
    }
}

internal fun jaroWinkler(s1: String, s2: String): Double {
    if (s1 == s2) return 1.0
    if (s1.isEmpty() || s2.isEmpty()) return 0.0
    val matchDistance = (maxOf(s1.length, s2.length) / 2) - 1
    val s1Matches = BooleanArray(s1.length)
    val s2Matches = BooleanArray(s2.length)
    var matches = 0
    for (i in s1.indices) {
        val start = maxOf(0, i - matchDistance)
        val end = minOf(i + matchDistance + 1, s2.length)
        for (j in start until end) {
            if (s2Matches[j]) continue
            if (s1[i] != s2[j]) continue
            s1Matches[i] = true
            s2Matches[j] = true
            matches++
            break
        }
    }
    if (matches == 0) return 0.0
    var transpositions = 0
    var k = 0
    for (i in s1.indices) {
        if (!s1Matches[i]) continue
        while (!s2Matches[k]) k++
        if (s1[i] != s2[k]) transpositions++
        k++
    }
    val m = matches.toDouble()
    val jaro = (m / s1.length + m / s2.length + (m - transpositions / 2.0) / m) / 3.0
    // Winkler boost
    var prefix = 0
    while (prefix < 4 && prefix < s1.length && prefix < s2.length && s1[prefix] == s2[prefix]) prefix++
    return jaro + prefix * 0.1 * (1 - jaro)
}
