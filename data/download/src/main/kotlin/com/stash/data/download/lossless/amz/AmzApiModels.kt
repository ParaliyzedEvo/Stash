package com.stash.data.download.lossless.amz

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire models for the amz.squid.wtf JSON API (an Amazon Music proxy).
 *
 * We model only the fields the matcher and resolver consume; everything
 * else (lyrics, composer, copyright, label, track_number, …) falls
 * through under the lenient parser's `ignoreUnknownKeys = true` and
 * disappears silently. Anything not guaranteed by the live API is
 * nullable or defaulted so a wire-shape drift degrades to safe
 * zero/null values rather than failing deserialisation — what we want
 * for a third-party reverse-engineered API.
 *
 * NOTE: the `/api/track` response has NO duration field — none is
 * modelled here. Duration must be obtained post-download via the
 * audio extractor, not from this API.
 */

// ── Search (`POST /api/search`) ──────────────────────────────────────────

@Serializable
data class AmzSearchResponse(
    val trackList: List<AmzSearchItem> = emptyList(),
)

@Serializable
data class AmzSearchItem(
    val asin: String,
    val title: String,
    val primaryArtistName: String? = null,
    val artistName: String? = null,
    val albumArtistName: String? = null,
    val album: AmzSearchAlbum? = null,
)

@Serializable
data class AmzSearchAlbum(
    val title: String? = null,
    val image: String? = null,
)

// ── Track (`POST /api/track`) ────────────────────────────────────────────

@Serializable
data class AmzTrackResponse(
    val metadata: AmzTrackMeta? = null,
    val drm: AmzDrm? = null,
    val stream: AmzStream? = null,
)

/**
 * Per-track DRM material. amz serves CENC AES-CTR-encrypted CMAF, but hands
 * the client the symmetric AES-128 [key] (32 hex chars) in the SAME
 * `/api/track` response — decryption is plain `ffmpeg -decryption_key`, NOT
 * Widevine/EME. Dropping this object (the original `ignoreUnknownKeys` bug) is
 * what left us saving the encrypted box with no way to decrypt it.
 */
@Serializable
data class AmzDrm(
    val key: String? = null,
)

/**
 * Encrypted-CMAF stream descriptor from `/api/track`. [url] is typically a
 * site-relative path (`/api/stream?asin=…&tier=…`) resolved against the API
 * origin by [AmzApiClient]. The numeric fields are advisory (the canonical
 * values come from the post-decrypt audio probe).
 */
@Serializable
data class AmzStream(
    val url: String? = null,
    val codec: String? = null,
    val sampleRate: Int? = null,
    val bitrate: Int? = null,
    val representationId: String? = null,
)

/**
 * Resolved result of `POST /api/track`: the track [meta] plus the
 * [decryptionKey] and encrypted-CMAF [streamUrl] required to fetch and decrypt
 * the audio. Both may be null for a malformed/old response shape, in which
 * case the caller degrades (no decrypt possible). [streamUrl] is absolute
 * (resolved against the API origin).
 */
data class AmzTrack(
    val meta: AmzTrackMeta,
    val decryptionKey: String?,
    val streamUrl: String?,
    val codec: String?,
    /** Stated stream bitrate in bits-per-second (FLAC has none in STREAMINFO). */
    val bitrateBps: Int? = null,
    /** Stated sample rate in Hz (advisory; the decrypted FLAC is authoritative). */
    val sampleRateHz: Int? = null,
)

/**
 * Full per-track metadata. `cover`/`coverCdn` are the same Amazon CDN
 * art URL in captured responses; prefer [coverCdn] when present. There
 * is deliberately NO duration field — the API does not return one.
 */
@Serializable
data class AmzTrackMeta(
    val asin: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    @SerialName("album_artist") val albumArtist: String? = null,
    val cover: String? = null,
    @SerialName("cover_cdn") val coverCdn: String? = null,
    val isrc: String? = null,
    @SerialName("is_explicit") val isExplicit: Boolean = false,
)
