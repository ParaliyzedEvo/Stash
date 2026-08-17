package com.stash.feature.home

import com.stash.core.model.MusicSource
import com.stash.core.model.Playlist
import com.stash.core.model.PlaylistType
import org.junit.Assert.assertEquals
import org.junit.Test

class YourPlaylistsRailTest {

    private fun playlist(
        id: Long,
        type: PlaylistType = PlaylistType.CUSTOM,
        pinnedToHomeAt: Long? = null,
        name: String = "P$id",
    ) = Playlist(
        id = id, name = name, source = MusicSource.SPOTIFY, type = type,
        pinnedToHomeAt = pinnedToHomeAt,
    )

    @Test
    fun `only pinned non-mix playlists survive, ordered by pin time`() {
        val rail = yourPlaylistsRail(
            listOf(
                playlist(1, pinnedToHomeAt = 300),
                playlist(2, pinnedToHomeAt = null),                       // not pinned
                playlist(3, PlaylistType.DAILY_MIX, pinnedToHomeAt = 50), // mix: excluded even if stamped
                playlist(4, PlaylistType.STASH_MIX, pinnedToHomeAt = 60), // mix: excluded
                playlist(5, PlaylistType.STASH_LIKED, pinnedToHomeAt = 100),
                playlist(6, pinnedToHomeAt = 200),
            ),
        )
        assertEquals(listOf(5L, 6L, 1L), rail.map { it.id })
    }

    @Test
    fun `empty in, empty out`() {
        assertEquals(emptyList<Playlist>(), yourPlaylistsRail(emptyList()))
    }
}
