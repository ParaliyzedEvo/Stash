package com.stash.core.data.discography
import com.stash.data.ytmusic.model.AlbumSummary

/** Result of merging Qobuz albums into a YT discography. */
data class MergedDiscography(val albums: List<AlbumSummary>, val singles: List<AlbumSummary>)

/**
 * Supplements a YT-sourced discography with Qobuz albums. The impl (in
 * data:download, where the Qobuz client + matcher live) does the artist match,
 * fetch, and merge; core:data only calls this and caches the result.
 *
 * Best-effort: any failure/timeout/no-confident-match returns the YT lists
 * UNCHANGED. Never throws to the caller.
 */
interface DiscographySupplement {
    suspend fun mergeInto(
        artistName: String,
        ytAlbums: List<AlbumSummary>,
        ytSingles: List<AlbumSummary>,
    ): MergedDiscography
}
