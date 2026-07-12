package com.stash.core.data.discography
import com.stash.data.ytmusic.model.AlbumSummary

/** Returns the YT discography unchanged. Used as ArtistCache's primary-ctor
 *  default (and in tests/previews). The real DiscographySupplement is the only
 *  Hilt binding, so production always injects it via the @Inject secondary ctor. */
class NoopDiscographySupplement : DiscographySupplement {
    override suspend fun mergeInto(
        artistName: String,
        ytAlbums: List<AlbumSummary>,
        ytSingles: List<AlbumSummary>,
    ) = MergedDiscography(ytAlbums, ytSingles)
}
