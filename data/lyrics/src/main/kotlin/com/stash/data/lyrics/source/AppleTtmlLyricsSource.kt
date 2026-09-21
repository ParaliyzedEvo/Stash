package com.stash.data.lyrics.source

import android.util.Log
import com.stash.data.lyrics.parser.TtmlParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Word-synced lyrics: iTunes Search (title/artist -> Apple Music song id) then
 * lyrics.paxsenix.org `apple-music/lyrics?ttml=true`. Miss = null, transport/HTTP failure = throws
 * (same contract as [LyricsRepository.walkSources] expects).
 */
class AppleTtmlLyricsSource(
    client: OkHttpClient,
    private val appVersion: String,
    private val lyricsBaseUrl: String = DEFAULT_LYRICS_BASE_URL,
    private val searchBaseUrl: String = DEFAULT_SEARCH_BASE_URL,
) : LyricsSource {

    override val id = SOURCE_ID 
    override val displayName = "Apple Music (word-synced)"

    private val http = client.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()

    override suspend fun resolve(query: LyricsQuery): LyricsResult? = withContext(Dispatchers.IO) {
        val songId = findSongId(query) ?: return@withContext null
        val ttml = fetchTtml(songId) ?: return@withContext null
        val parsed = TtmlParser.parse(ttml)?.takeIf { it.lines.isNotEmpty() } ?: return@withContext null
        LyricsResult(
            sourceId = id,
            plainText = parsed.toPlainText(),
            syncedLrc = parsed.toLrc(),
            instrumental = false,
            language = null,
            sourceLyricsId = songId,
            ttml = ttml,
        )
    }

    private fun findSongId(query: LyricsQuery): String? {
        val url = searchBaseUrl.toHttpUrl().newBuilder()
            .addPathSegment("search")
            .addQueryParameter("term", "${query.title} ${query.artist}")
            .addQueryParameter("media", "music")
            .addQueryParameter("entity", "song")
            .addQueryParameter("limit", "10")
            .build()
        val body = get(url) ?: return null
        val results = JSONObject(body).optJSONArray("results") ?: return null
        val candidates = (0 until results.length()).mapNotNull { i ->
            val o = results.optJSONObject(i) ?: return@mapNotNull null
            val trackId = o.optLong("trackId", 0L).takeIf { it > 0 } ?: return@mapNotNull null
            Candidate(
                id = trackId.toString(),
                title = o.optString("trackName"),
                artist = o.optString("artistName"),
                durationMs = o.optLong("trackTimeMillis", 0L),
            )
        }
        return pickBest(query, candidates)?.id
    }

    private fun fetchTtml(songId: String): String? {
        val url = lyricsBaseUrl.toHttpUrl().newBuilder()
            .addPathSegments("apple-music/lyrics")
            .addQueryParameter("id", songId)
            .addQueryParameter("ttml", "true")
            .build()
        val body = get(url)?.trim() ?: return null
        if (!body.startsWith("<")) {
            Log.d(TAG, "Non-XML response for apple id $songId; treating as miss")
            return null
        }
        return body
    }

    /** Body on 2xx, null on 404, throws on anything else. */
    private fun get(url: HttpUrl): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Stash/$appVersion (Android)")
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} from ${url.host}")
            return response.body?.string()
        }
    }

    private data class Candidate(val id: String, val title: String, val artist: String, val durationMs: Long)

    private fun normalize(s: String): String = s.lowercase(Locale.ROOT)
        .replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "")   // (feat. x) [Remastered]
        .replace(Regex("""\s+-\s+.*$"""), "")             // " - Remastered 2011"
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .trim()

    private fun pickBest(query: LyricsQuery, candidates: List<Candidate>): Candidate? {
        val wantTitle = normalize(query.title)
        val wantArtists = listOfNotNull(query.albumArtist, query.artist)
            .map(::normalize).filter { it.isNotEmpty() }
        val wantMs = query.durationMs ?: 0L

        return candidates.mapNotNull { c ->
            val t = normalize(c.title)
            val titleScore = when {
                t == wantTitle -> 3
                t.contains(wantTitle) || wantTitle.contains(t) -> 1
                else -> 0
            }
            if (titleScore == 0) return@mapNotNull null
            val a = normalize(c.artist)
            if (wantArtists.none { a.contains(it) || it.contains(a) }) return@mapNotNull null
            var score = titleScore
            if (wantMs > 0 && c.durationMs > 0) {
                val diff = abs(wantMs - c.durationMs)
                if (diff > 8_000) return@mapNotNull null
                score += if (diff <= 3_000) 2 else 1
            }
            c to score
        }.maxByOrNull { it.second }?.first
    }

    companion object {
        const val SOURCE_ID = "apple-ttml"
        const val DEFAULT_LYRICS_BASE_URL = "https://lyrics.paxsenix.org"
        const val DEFAULT_SEARCH_BASE_URL = "https://itunes.apple.com"
        private const val TAG = "AppleTtmlLyricsSource"
    }
}