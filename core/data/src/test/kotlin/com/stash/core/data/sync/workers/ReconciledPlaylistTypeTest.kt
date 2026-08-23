package com.stash.core.data.sync.workers

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.PlaylistType
import org.junit.Test

/**
 * Unit test for [reconciledPlaylistType] — the write-once-no-more rule for
 * `playlists.type`.
 *
 * Issue #437: a Spotify playlist first seen by the home-feed mix pass was
 * inserted as DAILY_MIX, and `type` was never written again. Once the library
 * walk reported the same `source_id` as a saved CUSTOM playlist, the row
 * stayed DAILY_MIX forever — invisible on every CUSTOM
 * surface (the Sync tab's "n/n PLAYLISTS" count and the Library Playlists
 * grid both filter on `type`). Five playlists fetched, one shown.
 *
 * The rule is deliberately ONE-WAY. Reconciling in both directions would make
 * the type flap every run for a Spotify-owned playlist the user has saved:
 * with auto-mix discovery on, the home feed claims the id and snapshots it
 * DAILY_MIX; with it off, the library walk claims it and snapshots it CUSTOM.
 * A saved library playlist wins, so the row settles instead of oscillating —
 * and with it the row's download eligibility (shouldEnqueueForDownload
 * excludes DAILY_MIX) and its home-vs-library placement.
 */
class ReconciledPlaylistTypeTest {

    @Test fun `a mix the user has saved becomes a playlist`() {
        assertThat(reconciledPlaylistType(PlaylistType.DAILY_MIX, PlaylistType.CUSTOM))
            .isEqualTo(PlaylistType.CUSTOM)
    }

    @Test fun `a saved playlist is never demoted back to a mix`() {
        assertThat(reconciledPlaylistType(PlaylistType.CUSTOM, PlaylistType.DAILY_MIX)).isNull()
    }

    @Test fun `an unchanged type is not rewritten`() {
        for (type in PlaylistType.entries) {
            assertThat(reconciledPlaylistType(type, type)).isNull()
        }
    }

    // Local-only types are owned by Stash, never by a remote snapshot. A
    // sourceId collision must not turn the user's own liked songs or their
    // one-off downloads holder into a synced playlist.
    @Test fun `local-only types are never reconciled`() {
        val localOnly = listOf(
            PlaylistType.STASH_MIX,
            PlaylistType.STASH_LIKED,
            PlaylistType.DOWNLOADS_MIX,
        )
        for (type in localOnly) {
            assertThat(reconciledPlaylistType(type, PlaylistType.CUSTOM)).isNull()
            assertThat(reconciledPlaylistType(type, PlaylistType.DAILY_MIX)).isNull()
            assertThat(reconciledPlaylistType(PlaylistType.DAILY_MIX, type)).isNull()
            assertThat(reconciledPlaylistType(PlaylistType.CUSTOM, type)).isNull()
        }
    }

    // LIKED_SONGS is a per-source singleton with a synthetic sourceId; nothing
    // in a library walk should ever re-type it, in either direction.
    @Test fun `liked songs is left alone`() {
        assertThat(reconciledPlaylistType(PlaylistType.LIKED_SONGS, PlaylistType.CUSTOM)).isNull()
        assertThat(reconciledPlaylistType(PlaylistType.LIKED_SONGS, PlaylistType.DAILY_MIX)).isNull()
        assertThat(reconciledPlaylistType(PlaylistType.DAILY_MIX, PlaylistType.LIKED_SONGS)).isNull()
        assertThat(reconciledPlaylistType(PlaylistType.CUSTOM, PlaylistType.LIKED_SONGS)).isNull()
    }
}
