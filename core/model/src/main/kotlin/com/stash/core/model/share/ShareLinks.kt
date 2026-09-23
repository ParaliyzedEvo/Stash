package com.stash.core.model.share

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Where share links live. One place to change when a custom domain is added (spec §1). */
object ShareConfig {
    const val BASE_URL = "https://stash-share.rawnaldclark.workers.dev"
    val HOSTS: Set<String> = setOf("stash-share.rawnaldclark.workers.dev")
}

object ShareLinks {
    sealed interface Parsed {
        data class Mix(val shareId: String) : Parsed
        data class Track(val track: SharedTrack) : Parsed
    }

    private val ID = Regex("^[A-Za-z0-9]{8}$")

    fun mixUrl(shareId: String): String = "${ShareConfig.BASE_URL}/m/$shareId"

    fun trackUrl(t: SharedTrack): String = buildString {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        append(ShareConfig.BASE_URL).append("/t?t=").append(enc(t.title)).append("&a=").append(enc(t.artist))
        t.album?.let { append("&al=").append(enc(it)) }
        t.durationMs?.let { append("&d=").append(it) }
        t.isrc?.let { append("&isrc=").append(enc(it)) }
        t.spotifyId?.let { append("&sp=").append(enc(it)) }
        t.youtubeId?.let { append("&yt=").append(enc(it)) }
    }

    /** A share link (https mix/track, or the legacy `stash://track`), or null when it isn't one. */
    fun parse(link: String?): Parsed? {
        val uri = runCatching { URI(link ?: return null) }.getOrNull() ?: return null
        val q = query(uri.rawQuery)
        return when {
            uri.scheme == "https" && uri.host in ShareConfig.HOSTS -> when {
                uri.path.startsWith("/m/") -> uri.path.removePrefix("/m/").takeIf { ID.matches(it) }?.let { Parsed.Mix(it) }
                uri.path == "/t" -> trackFrom(q["t"], q["a"], q["al"], q["d"], q["isrc"], q["sp"], q["yt"])
                else -> null
            }
            uri.scheme == "stash" && uri.host == "track" ->
                trackFrom(q["t"], q["a"], null, null, null, spotifyTrackId(q["s"]), q["y"])
            else -> null
        }
    }

    private fun trackFrom(t: String?, a: String?, al: String?, d: String?, isrc: String?, sp: String?, yt: String?): Parsed? {
        if (t.isNullOrBlank() || a.isNullOrBlank()) return null
        return Parsed.Track(
            SharedTrack(t, a, al?.ifBlank { null }, d?.toLongOrNull()?.takeIf { it > 0 },
                isrc?.ifBlank { null }, sp?.ifBlank { null }, yt?.ifBlank { null }),
        )
    }

    private fun query(raw: String?): Map<String, String> =
        raw.orEmpty().split('&').filter { '=' in it }.associate { part ->
            val (k, v) = part.split('=', limit = 2)
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }
}
