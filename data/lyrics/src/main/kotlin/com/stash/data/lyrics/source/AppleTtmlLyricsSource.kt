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
import org.json.JSONTokener
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
        val songId = findSongId(query)
        if (songId == null) {
            Log.d(TAG, "no Apple match for \"${query.title}\" - ${query.artist}")
            return@withContext null
        }
        val ttml = fetchTtml(songId)
        if (ttml == null) {
            Log.d(TAG, "apple id $songId (\"${query.title}\") has no TTML")
            return@withContext null
        }
        val parsed = TtmlParser.parse(ttml)?.takeIf { it.lines.isNotEmpty() }
        if (parsed == null) {
            Log.w(TAG, "apple id $songId (\"${query.title}\") returned TTML but it didn't parse")
            return@withContext null
        }
        Log.d(TAG, "apple id $songId (\"${query.title}\") -> ${parsed.lines.size} lines")
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
        Log.d(
            TAG,
            "iTunes search \"${query.title}\" - ${query.artist}: " +
                candidates.joinToString { "${it.id}:${it.title}/${it.artist}(${it.durationMs}ms)" }
                    .ifEmpty { "0 results" },
        )
        return pickBest(query, candidates)?.id
    }

    private fun fetchTtml(songId: String): String? {
        val url = lyricsBaseUrl.toHttpUrl().newBuilder()
            .addPathSegments("apple-music/lyrics")
            .addQueryParameter("id", songId)
            .addQueryParameter("ttml", "true")
            .build()
        val raw = get(url) ?: return null
        // BOM_CHAR isn't whitespace to .trim(), so a UTF-8-BOM'd XML body would otherwise
        // wrongly fail the startsWith("<") check below.
        val body = raw.trim().removePrefix(BOM_CHAR)

        if (body.startsWith("<")) return body

        // The spec says ttml=true returns raw XML, but this proxy family (see e.g. the
        // api.paxsenix.org sibling, which returns JSON unconditionally) may still wrap it.
        // Try to pull an XML string out of a JSON envelope before giving up.
        extractTtmlFromJson(body)?.let { return it }

        Log.d(
            TAG,
            "Non-XML, non-JSON-wrapped response for apple id $songId " +
                "(${body.length} chars): \"${body.take(200)}\"",
        )
        return null
    }

    /** Looks for a string value that is itself XML, one level deep in a JSON object/array. */
    private fun extractTtmlFromJson(body: String): String? {
        val root = runCatching { JSONTokener(body).nextValue() }.getOrNull() ?: return null

        fun fromObject(o: JSONObject): String? {
            // paxsenix's envelope (confirmed shape: {type, content, source, cached_at}) uses "type"
            // to say what "content" actually is; skip it outright when it explicitly isn't TTML,
            // rather than relying on the leading-"<" check alone to reject an LRC/plain body.
            val type = (o.opt("type") as? String)?.trim()?.uppercase()
            if (type != null && type != "TTML") return null
            for (key in TTML_JSON_KEYS) {
                val v = o.opt(key) as? String ?: continue
                val trimmed = v.trim()
                if (trimmed.startsWith("<")) return trimmed
            }
            return null
        }

        return when (root) {
            is JSONObject -> fromObject(root)
                ?: (root.opt("data") as? JSONObject)?.let(::fromObject)
                ?: (root.opt("result") as? JSONObject)?.let(::fromObject)
            is org.json.JSONArray -> (0 until root.length())
                .mapNotNull { root.opt(it) as? JSONObject }
                .firstNotNullOfOrNull(::fromObject)
            else -> null
        }
    }

    /**
     * Body on 2xx, null on 404, throws on anything else.
     *
     * Retries ONCE on a transport failure (any [IOException] — connection refused, TLS handshake
     * failure, timeout). A pooled OkHttp connection that some VPN configurations silently drop
     * while idle looks fine to OkHttp until the next write, at which point it fails mid-handshake
     * rather than as a clean "couldn't connect" — a single retry gets a fresh connection and almost
     * always succeeds when that's the cause. A second failure is presumed real and propagates.
     */
    private fun get(url: HttpUrl): String? {
        val request = Request.Builder().url(url).header("User-Agent", "Stash (Android)").build()
        var lastError: IOException? = null
        repeat(2) { attempt ->
            try {
                http.newCall(request).execute().use { response ->
                    if (response.code == 404) return null
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code} from ${url.host}")
                    return response.body?.string()
                }
            } catch (e: IOException) {
                lastError = e
                if (attempt == 0) {
                    Log.d(TAG, "Transport failure on ${url.host}, retrying once: ${e.message}")
                    Thread.sleep(250)
                }
            }
        }
        throw lastError!!
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
        private const val BOM_CHAR = "\uFEFF"
        private val TTML_JSON_KEYS = listOf("ttml", "data", "lyrics", "content", "xml")
    }
}