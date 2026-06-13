package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Task 8 — verifies the `includeStreamable: Boolean` parameter added to
 * every library-content DAO query. Each query must:
 *  - Return only `is_downloaded = 1` rows when `includeStreamable = false`.
 *  - Return downloaded + streamable rows when `includeStreamable = true`.
 *  - NEVER return rows that are checked-but-unavailable (`is_downloaded = 0
 *    AND is_streamable = 0 AND is_streamable_checked_at IS NOT NULL`).
 *  - NEVER return un-checked rows (`is_streamable_checked_at IS NULL`).
 *
 * The predicate `(is_downloaded = 1 OR (:includeStreamable AND is_streamable = 1))`
 * handles all four states correctly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TrackDaoStreamableTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: TrackDao
    private lateinit var playlistDao: PlaylistDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StashDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        dao = db.trackDao()
        playlistDao = db.playlistDao()
    }

    @After fun tearDown() { db.close() }

    // ── getAllAlbums ────────────────────────────────────────────────────

    @Test fun `getAllAlbums excludes streamable by default`() = runTest {
        insertDownloaded(id = 1L, album = "Album A", artist = "Drake")
        insertStreamableOnly(id = 2L, album = "Album B", artist = "Drake")

        val albums = dao.getAllAlbums(includeStreamable = false).first()

        assertEquals(listOf("Album A"), albums.map { it.album })
    }

    @Test fun `getAllAlbums includes streamable when flagged`() = runTest {
        insertDownloaded(id = 1L, album = "Album A", artist = "Drake")
        insertStreamableOnly(id = 2L, album = "Album B", artist = "Drake")

        val albums = dao.getAllAlbums(includeStreamable = true).first()

        assertEquals(setOf("Album A", "Album B"), albums.map { it.album }.toSet())
    }

    @Test fun `getAllAlbums excludes unavailable even when streamable true`() = runTest {
        insertDownloaded(id = 1L, album = "Album A", artist = "Drake")
        insertUnavailable(id = 2L, album = "Album C", artist = "Drake")

        val albums = dao.getAllAlbums(includeStreamable = true).first()

        assertFalse("Album C unavailable must not appear", albums.any { it.album == "Album C" })
    }

    @Test fun `getAllAlbums excludes unchecked even when streamable true`() = runTest {
        insertDownloaded(id = 1L, album = "Album A", artist = "Drake")
        insertUnchecked(id = 2L, album = "Album D", artist = "Drake")

        val albums = dao.getAllAlbums(includeStreamable = true).first()

        assertFalse("Album D unchecked must not appear", albums.any { it.album == "Album D" })
    }

    // ── getAllArtists ───────────────────────────────────────────────────

    @Test fun `getAllArtists excludes streamable by default`() = runTest {
        insertDownloaded(id = 1L, album = "Album A", artist = "Drake")
        insertStreamableOnly(id = 2L, album = "Album B", artist = "Future")

        val artists = dao.getAllArtists(includeStreamable = false).first()

        assertEquals(listOf("Drake"), artists.map { it.artist })
    }

    @Test fun `getAllArtists includes streamable when flagged`() = runTest {
        insertDownloaded(id = 1L, album = "Album A", artist = "Drake")
        insertStreamableOnly(id = 2L, album = "Album B", artist = "Future")

        val artists = dao.getAllArtists(includeStreamable = true).first()

        assertEquals(setOf("Drake", "Future"), artists.map { it.artist }.toSet())
    }

    @Test fun `getAllArtists excludes unavailable even when streamable true`() = runTest {
        insertDownloaded(id = 1L, album = "Album A", artist = "Drake")
        insertUnavailable(id = 2L, album = "Album C", artist = "Travis")

        val artists = dao.getAllArtists(includeStreamable = true).first()

        assertFalse("Travis unavailable must not appear", artists.any { it.artist == "Travis" })
    }

    // ── getByPlaylist ───────────────────────────────────────────────────

    @Test fun `getByPlaylist excludes streamable by default`() = runTest {
        val downloaded = insertDownloaded(id = 1L, album = "A", artist = "Drake")
        val streamable = insertStreamableOnly(id = 2L, album = "B", artist = "Drake")
        val playlistId = playlistDao.insert(customPlaylist())
        playlistDao.insertCrossRef(crossRef(playlistId, downloaded, position = 0))
        playlistDao.insertCrossRef(crossRef(playlistId, streamable, position = 1))

        val tracks = dao.getByPlaylist(playlistId, includeStreamable = false).first()

        assertEquals(listOf(1L), tracks.map { it.id })
    }

    @Test fun `getByPlaylist includes streamable when flagged`() = runTest {
        val downloaded = insertDownloaded(id = 1L, album = "A", artist = "Drake")
        val streamable = insertStreamableOnly(id = 2L, album = "B", artist = "Drake")
        val playlistId = playlistDao.insert(customPlaylist())
        playlistDao.insertCrossRef(crossRef(playlistId, downloaded, position = 0))
        playlistDao.insertCrossRef(crossRef(playlistId, streamable, position = 1))

        val tracks = dao.getByPlaylist(playlistId, includeStreamable = true).first()

        assertEquals(setOf(1L, 2L), tracks.map { it.id }.toSet())
    }

    @Test fun `getByPlaylist excludes unavailable even when streamable true`() = runTest {
        val downloaded = insertDownloaded(id = 1L, album = "A", artist = "Drake")
        val unavailable = insertUnavailable(id = 2L, album = "C", artist = "Drake")
        val playlistId = playlistDao.insert(customPlaylist())
        playlistDao.insertCrossRef(crossRef(playlistId, downloaded, position = 0))
        playlistDao.insertCrossRef(crossRef(playlistId, unavailable, position = 1))

        val tracks = dao.getByPlaylist(playlistId, includeStreamable = true).first()

        assertEquals(listOf(1L), tracks.map { it.id })
    }

    // ── getByPlaylist: STASH_MIX offline exemption ──────────────────────
    // Stash Mixes are an inherently online discovery surface. Their
    // stream-only tracks must stay visible even when the Online/Offline
    // preference is OFF (includeStreamable = false), so the full mix shows
    // in Offline mode instead of collapsing to the few downloaded tracks.

    @Test fun `getByPlaylist STASH_MIX includes streamable even when flag is off`() = runTest {
        val downloaded = insertDownloaded(id = 1L, album = "A", artist = "Drake")
        val streamable = insertStreamableOnly(id = 2L, album = "B", artist = "Drake")
        val playlistId = playlistDao.insert(stashMixPlaylist())
        playlistDao.insertCrossRef(crossRef(playlistId, downloaded, position = 0))
        playlistDao.insertCrossRef(crossRef(playlistId, streamable, position = 1))

        val tracks = dao.getByPlaylist(playlistId, includeStreamable = false).first()

        assertEquals(setOf(1L, 2L), tracks.map { it.id }.toSet())
    }

    @Test fun `getByPlaylist STASH_MIX still excludes unavailable and unchecked when flag is off`() = runTest {
        val downloaded = insertDownloaded(id = 1L, album = "A", artist = "Drake")
        val unavailable = insertUnavailable(id = 2L, album = "C", artist = "Drake")
        val unchecked = insertUnchecked(id = 3L, album = "D", artist = "Drake")
        val playlistId = playlistDao.insert(stashMixPlaylist())
        playlistDao.insertCrossRef(crossRef(playlistId, downloaded, position = 0))
        playlistDao.insertCrossRef(crossRef(playlistId, unavailable, position = 1))
        playlistDao.insertCrossRef(crossRef(playlistId, unchecked, position = 2))

        val tracks = dao.getByPlaylist(playlistId, includeStreamable = false).first()

        assertEquals(listOf(1L), tracks.map { it.id })
    }

    @Test fun `getByPlaylist non-mix still excludes streamable when flag is off`() = runTest {
        // Control: the exemption is scoped to STASH_MIX. A CUSTOM playlist
        // must still collapse to downloaded-only in Offline mode.
        val downloaded = insertDownloaded(id = 1L, album = "A", artist = "Drake")
        val streamable = insertStreamableOnly(id = 2L, album = "B", artist = "Drake")
        val playlistId = playlistDao.insert(customPlaylist())
        playlistDao.insertCrossRef(crossRef(playlistId, downloaded, position = 0))
        playlistDao.insertCrossRef(crossRef(playlistId, streamable, position = 1))

        val tracks = dao.getByPlaylist(playlistId, includeStreamable = false).first()

        assertEquals(listOf(1L), tracks.map { it.id })
    }

    // ── getTotalCount ───────────────────────────────────────────────────

    @Test fun `getTotalCount excludes streamable by default`() = runTest {
        insertDownloaded(id = 1L, album = "A", artist = "Drake")
        insertStreamableOnly(id = 2L, album = "B", artist = "Drake")

        val count = dao.getTotalCount(includeStreamable = false).first()

        assertEquals(1, count)
    }

    @Test fun `getTotalCount includes streamable when flagged`() = runTest {
        insertDownloaded(id = 1L, album = "A", artist = "Drake")
        insertStreamableOnly(id = 2L, album = "B", artist = "Drake")

        val count = dao.getTotalCount(includeStreamable = true).first()

        assertEquals(2, count)
    }

    @Test fun `getTotalCount excludes unavailable even when streamable true`() = runTest {
        insertDownloaded(id = 1L, album = "A", artist = "Drake")
        insertUnavailable(id = 2L, album = "C", artist = "Drake")
        insertUnchecked(id = 3L, album = "D", artist = "Drake")

        val count = dao.getTotalCount(includeStreamable = true).first()

        assertEquals(1, count)
    }

    // ── FTS search ──────────────────────────────────────────────────────

    @Test fun `search excludes streamable by default`() = runTest {
        insertDownloaded(id = 1L, album = "A", artist = "Drake", title = "Hotline Bling")
        insertStreamableOnly(id = 2L, album = "B", artist = "Drake", title = "Hotline Two")

        val tracks = dao.search(query = "Hotline*", includeStreamable = false).first()

        assertEquals(listOf(1L), tracks.map { it.id })
    }

    @Test fun `search includes streamable when flagged`() = runTest {
        insertDownloaded(id = 1L, album = "A", artist = "Drake", title = "Hotline Bling")
        insertStreamableOnly(id = 2L, album = "B", artist = "Drake", title = "Hotline Two")

        val tracks = dao.search(query = "Hotline*", includeStreamable = true).first()

        assertEquals(setOf(1L, 2L), tracks.map { it.id }.toSet())
    }

    @Test fun `search excludes unavailable even when streamable true`() = runTest {
        insertDownloaded(id = 1L, album = "A", artist = "Drake", title = "Hotline Bling")
        insertUnavailable(id = 2L, album = "C", artist = "Drake", title = "Hotline Three")

        val tracks = dao.search(query = "Hotline*", includeStreamable = true).first()

        assertEquals(listOf(1L), tracks.map { it.id })
    }

    // ── PlaylistDao.getAllVisible ───────────────────────────────────────

    @Test fun `getAllVisible escape-hatch excludes streamable by default`() = runTest {
        // Playlist with sync_enabled = 0 — only visible if it has a downloaded
        // track. A streamable-only track must NOT keep it visible when the
        // streaming flag is off.
        val streamableTrack = insertStreamableOnly(id = 1L, album = "A", artist = "Drake")
        val playlistId = playlistDao.insert(syncDisabledPlaylist())
        playlistDao.insertCrossRef(crossRef(playlistId, streamableTrack, position = 0))

        val visible = playlistDao.getAllVisible(includeStreamable = false).first()

        assertTrue("sync_enabled=0 with only streamable tracks must be hidden", visible.isEmpty())
    }

    @Test fun `getAllVisible escape-hatch includes streamable when flagged`() = runTest {
        val streamableTrack = insertStreamableOnly(id = 1L, album = "A", artist = "Drake")
        val playlistId = playlistDao.insert(syncDisabledPlaylist())
        playlistDao.insertCrossRef(crossRef(playlistId, streamableTrack, position = 0))

        val visible = playlistDao.getAllVisible(includeStreamable = true).first()

        assertEquals(listOf(playlistId), visible.map { it.id })
    }

    @Test fun `getAllVisible always shows sync-enabled playlists`() = runTest {
        // sync_enabled = 1 visibility doesn't depend on tracks — visible
        // regardless of includeStreamable.
        val playlistId = playlistDao.insert(syncEnabledPlaylist())

        val visibleFalse = playlistDao.getAllVisible(includeStreamable = false).first()
        val visibleTrue = playlistDao.getAllVisible(includeStreamable = true).first()

        assertEquals(listOf(playlistId), visibleFalse.map { it.id })
        assertEquals(listOf(playlistId), visibleTrue.map { it.id })
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private suspend fun insertDownloaded(
        id: Long,
        album: String,
        artist: String,
        title: String = "Title $id",
    ): Long {
        dao.insert(
            TrackEntity(
                id = id,
                title = title,
                artist = artist,
                album = album,
                canonicalTitle = title.lowercase(),
                canonicalArtist = artist.lowercase(),
                isDownloaded = true,
                isStreamable = false,
                isStreamableCheckedAt = null,
            )
        )
        return id
    }

    private suspend fun insertStreamableOnly(
        id: Long,
        album: String,
        artist: String,
        title: String = "Title $id",
    ): Long {
        dao.insert(
            TrackEntity(
                id = id,
                title = title,
                artist = artist,
                album = album,
                canonicalTitle = title.lowercase(),
                canonicalArtist = artist.lowercase(),
                isDownloaded = false,
                isStreamable = true,
                isStreamableCheckedAt = 1_000_000L,
            )
        )
        return id
    }

    private suspend fun insertUnavailable(
        id: Long,
        album: String,
        artist: String,
        title: String = "Title $id",
    ): Long {
        // is_downloaded = 0, is_streamable = 0, checked_at != null
        dao.insert(
            TrackEntity(
                id = id,
                title = title,
                artist = artist,
                album = album,
                canonicalTitle = title.lowercase(),
                canonicalArtist = artist.lowercase(),
                isDownloaded = false,
                isStreamable = false,
                isStreamableCheckedAt = 1_000_000L,
            )
        )
        return id
    }

    private suspend fun insertUnchecked(
        id: Long,
        album: String,
        artist: String,
        title: String = "Title $id",
    ): Long {
        // is_downloaded = 0, is_streamable = 0, checked_at = null
        dao.insert(
            TrackEntity(
                id = id,
                title = title,
                artist = artist,
                album = album,
                canonicalTitle = title.lowercase(),
                canonicalArtist = artist.lowercase(),
                isDownloaded = false,
                isStreamable = false,
                isStreamableCheckedAt = null,
            )
        )
        return id
    }

    private fun customPlaylist() = PlaylistEntity(
        name = "Test Playlist",
        source = MusicSource.BOTH,
        sourceId = "test_playlist_${System.nanoTime()}",
        type = PlaylistType.CUSTOM,
        trackCount = 0,
        syncEnabled = true,
        isActive = true,
    )

    private fun stashMixPlaylist() = PlaylistEntity(
        name = "Daily Discover",
        source = MusicSource.BOTH,
        sourceId = "stash_mix_${System.nanoTime()}",
        type = PlaylistType.STASH_MIX,
        trackCount = 0,
        syncEnabled = true,
        isActive = true,
    )

    private fun syncDisabledPlaylist() = PlaylistEntity(
        name = "Sync-Disabled",
        source = MusicSource.SPOTIFY,
        sourceId = "sync_disabled_${System.nanoTime()}",
        type = PlaylistType.CUSTOM,
        trackCount = 0,
        syncEnabled = false,
        isActive = true,
    )

    private fun syncEnabledPlaylist() = PlaylistEntity(
        name = "Sync-Enabled",
        source = MusicSource.SPOTIFY,
        sourceId = "sync_enabled_${System.nanoTime()}",
        type = PlaylistType.CUSTOM,
        trackCount = 0,
        syncEnabled = true,
        isActive = true,
    )

    private fun crossRef(playlistId: Long, trackId: Long, position: Int) =
        PlaylistTrackCrossRef(
            playlistId = playlistId,
            trackId = trackId,
            position = position,
            addedAt = java.time.Instant.EPOCH,
        )
}
