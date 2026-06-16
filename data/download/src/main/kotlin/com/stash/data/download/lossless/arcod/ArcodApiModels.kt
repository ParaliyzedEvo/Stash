package com.stash.data.download.lossless.arcod

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Lenient Json instance for the ARCOD (Qobuz-DL proxy) API.
 *
 * ARCOD re-publishes Qobuz catalog JSON, which carries far more fields than
 * Stash models, so [Json.ignoreUnknownKeys] is mandatory. [Json.isLenient]
 * tolerates the proxy's occasional unquoted/relaxed values.
 */
internal val ArcodJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    // The job-create POST body must carry every field the proxy expects,
    // including ones left at their defaults (quality, format, …), so default
    // values are emitted rather than omitted.
    encodeDefaults = true
}

// ── get-music search ────────────────────────────────────────────────────────

@Serializable
data class ArcodSearchResponse(
    val success: Boolean = false,
    val data: ArcodSearchData? = null,
)

@Serializable
data class ArcodSearchData(
    val tracks: ArcodTrackList? = null,
)

@Serializable
data class ArcodTrackList(
    val items: List<ArcodTrackItem> = emptyList(),
)

/**
 * A single track from the Qobuz catalog. The track `id` arrives as a JSON
 * number (e.g. `8767428`) so it's a [Long]; the *album* id is a string
 * (e.g. `"0093624804567"`) — see [ArcodAlbum.id].
 */
@Serializable
data class ArcodTrackItem(
    val id: Long,
    val title: String,
    val isrc: String? = null,
    val duration: Int? = null,
    @SerialName("maximum_bit_depth") val maxBitDepth: Int? = null,
    val performer: ArcodNamed? = null,
    val album: ArcodAlbum? = null,
)

@Serializable
data class ArcodNamed(
    val name: String? = null,
    val id: Long? = null,
)

@Serializable
data class ArcodAlbum(
    val id: String? = null,
    val title: String? = null,
    val artist: ArcodNamed? = null,
    val image: ArcodImage? = null,
    @SerialName("release_date_original") val releaseDate: String? = null,
    @SerialName("tracks_count") val tracksCount: Int? = null,
)

@Serializable
data class ArcodImage(
    val large: String? = null,
    val small: String? = null,
)

// ── job lifecycle ─────────────────────────────────────────────────────────────

/**
 * A download job. Created via `POST /api/v2/downloads`, then polled at
 * `GET /api/v2/downloads/<id>` until [status] is `completed`, at which point
 * [downloadUrl] carries an open, Range-capable, short-lived link to a clear
 * `.flac`.
 */
@Serializable
data class ArcodJob(
    val id: String,
    val status: String,
    val progress: Int = 0,
    val error: String? = null,
    val fileName: String? = null,
    val fileSize: Long? = null,
    val downloadUrl: String? = null,
)

@Serializable
data class ArcodUrlResponse(
    val downloadUrl: String,
    val fileName: String? = null,
    val expiresIn: Int? = null,
)

/**
 * Body for `POST /api/v2/downloads`. Defaults mirror the values the arcod.xyz
 * web client sends for a standard single-track FLAC download.
 */
@Serializable
data class ArcodJobRequest(
    val albumId: String,
    val trackId: Long,
    val albumTitle: String,
    val artistName: String,
    val artistId: Long,
    val coverUrl: String,
    val releaseDate: String,
    val tracksCount: Int = 1,
    val quality: Int = 27,
    val format: String = "FLAC",
    val bitrate: Int = 320,
    val embedLyrics: Boolean = false,
    val lyricsMode: String = "none",
    val downloadBooklet: Boolean = false,
    val attachCover: Boolean = false,
    val zipName: String = "{artists} - {name}",
    val trackName: String = "{track} - {name}",
)
