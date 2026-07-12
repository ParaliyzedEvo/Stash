package com.stash.data.download.lossless.qbdlx

import kotlinx.serialization.Serializable

// Read-only catalog metadata DTOs (artist search, artist albums, album detail).
// Reuses QbdlxPerformer/QbdlxImage from QbdlxQobuzModels.kt — fields match the fixtures.

// ── catalog/search?type=artists ────────────────────────────────────────
@Serializable
data class QbdlxArtistSearchResponse(val artists: QbdlxArtistList = QbdlxArtistList())

@Serializable
data class QbdlxArtistList(val items: List<QbdlxArtistItem> = emptyList())

@Serializable
data class QbdlxArtistItem(val id: Long = 0, val name: String = "")

// ── artist/get?extra=albums ────────────────────────────────────────────
@Serializable
data class QbdlxArtistAlbumsResponse(val albums: QbdlxAlbumList = QbdlxAlbumList())

@Serializable
data class QbdlxAlbumList(val items: List<QbdlxAlbumItem> = emptyList())

@Serializable
data class QbdlxAlbumItem(
    val id: String = "",                          // string slug, e.g. "qf6qfzou4fwrb"
    val title: String = "",
    val artist: QbdlxPerformer? = null,           // object with { name }
    val image: QbdlxImage? = null,
    val release_date_original: String? = null,    // "YYYY-MM-DD"
    val tracks_count: Int = 0,
    val release_type: String? = null,             // absent in albums list fixture
)

// ── album/get ──────────────────────────────────────────────────────────
@Serializable
data class QbdlxAlbumDetailResponse(
    val id: String = "",
    val title: String = "",
    val artist: QbdlxPerformer? = null,
    val image: QbdlxImage? = null,
    val release_date_original: String? = null,
    val tracks: QbdlxAlbumTrackList = QbdlxAlbumTrackList(),
)

@Serializable
data class QbdlxAlbumTrackList(val items: List<QbdlxAlbumTrackItem> = emptyList())

@Serializable
data class QbdlxAlbumTrackItem(
    val id: Long = 0,                             // numeric track id
    val title: String = "",
    val performer: QbdlxPerformer? = null,
    val duration: Int = 0,                        // seconds
)
