package com.stash.feature.search

import com.stash.data.ytmusic.model.AlbumSummary

enum class AlbumShelf { ALBUMS, SINGLES }

/** Where a focused album lives on the profile: which shelf and which card. */
data class AlbumFocusTarget(val shelf: AlbumShelf, val index: Int)

private fun norm(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ")

/**
 * Locate [focusAlbum] in the profile's shelves. Albums win over Singles/EPs
 * when both contain a title match. Case-, edge-, and internal-whitespace-
 * insensitive ("m b v" == "M B V"). Null when the album isn't present (e.g.
 * missing from YouTube's catalog) — the caller then just lands on the top.
 */
fun findAlbumFocus(
    focusAlbum: String?,
    albums: List<AlbumSummary>,
    singles: List<AlbumSummary>,
): AlbumFocusTarget? {
    val target = focusAlbum?.let(::norm)?.takeIf { it.isNotEmpty() } ?: return null
    albums.indexOfFirst { norm(it.title) == target }
        .takeIf { it >= 0 }?.let { return AlbumFocusTarget(AlbumShelf.ALBUMS, it) }
    singles.indexOfFirst { norm(it.title) == target }
        .takeIf { it >= 0 }?.let { return AlbumFocusTarget(AlbumShelf.SINGLES, it) }
    return null
}
