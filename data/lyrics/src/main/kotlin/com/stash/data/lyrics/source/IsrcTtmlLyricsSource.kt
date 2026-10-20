package com.stash.data.lyrics.source

import android.util.Log
import com.stash.data.lyrics.parser.TtmlParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Static-file TTML hosts keyed by ISRC. 404 = not there; other non-2xx = failure (track stays retryable). */
class IsrcTtmlLyricsSource(
    client: OkHttpClient,
    appVersionName: String,
    override val id: String,
    override val displayName: String,
    private val urlTemplate: String, // contains the literal {ISRC}
    private val isrcResolver: IsrcResolver,
    lyricsDns: Dns = LyricsDns(),
) : LyricsSource {

    private val userAgent = "Stash/$appVersionName (Android)"
    private val interactiveHttp = client.newBuilder().dns(lyricsDns)
        .callTimeout(INTERACTIVE_TIMEOUT_S, TimeUnit.SECONDS).build()
    private val backgroundHttp = client.newBuilder().dns(lyricsDns)
        .callTimeout(BACKGROUND_TIMEOUT_S, TimeUnit.SECONDS).build()

    override suspend fun resolve(query: LyricsQuery): LyricsResult? {
        val known = cleanIsrc(query.isrc)
        if (known != null) fetch(known, query.interactive)?.let { return it }
        val resolved = cleanIsrc(isrcResolver.resolve(query))
        return if (resolved != null && resolved != known) fetch(resolved, query.interactive) else null
    }

    private suspend fun fetch(isrc: String, interactive: Boolean): LyricsResult? = withContext(Dispatchers.IO) {
        val url = urlTemplate.replace("{ISRC}", isrc).toHttpUrl()
        val http = if (interactive) interactiveHttp else backgroundHttp
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()

        val body = http.newCall(request).execute().use { r ->
            if (r.code == 404) return@withContext null
            if (!r.isSuccessful) throw AppleTtmlLyricsSource.HttpStatusException(r.code, url.host)
            r.body?.string()
        }?.trim()?.removePrefix("\uFEFF")

        val ttml = body?.takeIf { it.startsWith("<tt") } ?: return@withContext null
        val parsed = TtmlParser.parse(ttml)?.takeIf { it.lines.isNotEmpty() } ?: return@withContext null

        Log.d(TAG, "$id hit for $isrc -> ${parsed.lines.size} lines")
        LyricsResult(
            sourceId = id,
            plainText = parsed.toPlainText(),
            syncedLrc = parsed.toLrc(),
            instrumental = false,
            language = null,
            sourceLyricsId = isrc,
            ttml = ttml,
        )
    }

    private fun cleanIsrc(raw: String?): String? = raw
        ?.uppercase(Locale.ROOT)
        ?.filter { it.isLetterOrDigit() }
        ?.takeIf { it.length == 12 }

    companion object {
        private const val TAG = "IsrcTtmlLyricsSource"
        private const val INTERACTIVE_TIMEOUT_S = 3L
        private const val BACKGROUND_TIMEOUT_S = 10L
        const val LRC_RED_ID = "lrc-red"
        const val BINIMUM_ID = "binimum"
        const val LRC_RED_URL = "https://lrc.red/s/{ISRC}.ttml"
        const val BINIMUM_URL = "https://lyrics-storage.binimum.org/{ISRC}.ttml"
    }
}