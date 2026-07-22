package com.stash.core.data.sync.workers

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.SyncHistoryDao
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.RemotePlaylistSnapshotEntity
import com.stash.core.data.db.entity.RemoteTrackSnapshotEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.SyncPreferencesManager
import com.stash.core.data.sync.SyncStateManager
import com.stash.core.data.sync.TrackMatcher
import com.stash.core.model.DownloadStatus
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.core.model.SyncMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Covers the correctness-sensitive paths in DiffWorker's batched rewrite:
 * REFRESH must not resurrect soft-deleted tracks, ACCUMULATE must preserve
 * addedAt, and the bulk identity match must honor spotifyUri > youtubeId >
 * canonical priority when a snapshot could match multiple candidates.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DiffWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: StashDatabase

    private val remoteSnapshotDao = mockk<com.stash.core.data.db.dao.RemoteSnapshotDao>()
    private val downloadQueueDao = mockk<DownloadQueueDao>(relaxed = true)
    private val syncHistoryDao = mockk<SyncHistoryDao>(relaxed = true)
    private val syncStateManager = mockk<SyncStateManager>(relaxed = true)
    private val musicRepository = mockk<MusicRepository>(relaxed = true)
    private val syncPreferencesManager = mockk<SyncPreferencesManager>()
    private val blocklistGuard = mockk<BlocklistGuard>()
    private val streamingPreference = mockk<StreamingPreference>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        coEvery { blocklistGuard.isBlocked(any(), any(), any(), any()) } returns false
        coEvery { streamingPreference.current() } returns false
        every { syncPreferencesManager.spotifySyncMode } returns flowOf(SyncMode.REFRESH)
        every { syncPreferencesManager.youtubeSyncMode } returns flowOf(SyncMode.REFRESH)
    }

    @After
    fun tearDown() { db.close() }

    // ── REFRESH must not resurrect a soft-deleted track ──────────────────

    @Test
    fun `REFRESH does not resurrect a soft-deleted track that reappears in the snapshot`() = runBlocking {
        val track = TrackEntity(
            title = "Runaway", artist = "Kanye West",
            canonicalTitle = "runaway", canonicalArtist = "kanye west",
            spotifyUri = "spotify:track:abc",
        )
        val trackId = db.trackDao().insert(track)

        val playlist = PlaylistEntity(
            name = "Liked Songs", source = MusicSource.SPOTIFY,
            sourceId = "spotify:collection:tracks", type = PlaylistType.LIKED_SONGS,
            syncEnabled = true,
        )
        val playlistId = db.playlistDao().insert(playlist)

        // User previously removed this track — tombstone row.
        db.playlistDao().insertCrossRef(
            PlaylistTrackCrossRef(
                playlistId = playlistId, trackId = trackId, position = 0,
                addedAt = Instant.parse("2025-01-01T00:00:00Z"),
                removedAt = Instant.parse("2025-06-01T00:00:00Z"),
            )
        )

        every { syncPreferencesManager.spotifySyncMode } returns flowOf(SyncMode.REFRESH)

        val playlistSnapshot = RemotePlaylistSnapshotEntity(
            id = 1L, syncId = 1L, source = MusicSource.SPOTIFY,
            sourcePlaylistId = "spotify:collection:tracks",
            playlistName = "Liked Songs", playlistType = PlaylistType.LIKED_SONGS,
        )
        coEvery { remoteSnapshotDao.getPlaylistSnapshotsBySyncId(1L) } returns listOf(playlistSnapshot)
        coEvery { remoteSnapshotDao.getTrackSnapshotsByPlaylistId(1L) } returns listOf(
            RemoteTrackSnapshotEntity(
                syncId = 1L, snapshotPlaylistId = 1L,
                title = "Runaway", artist = "Kanye West",
                spotifyUri = "spotify:track:abc", position = 0,
            )
        )

        val result = buildWorker().doWork()
        assertTrue("diff must succeed", result is androidx.work.ListenableWorker.Result.Success)

        val crossRefs = db.playlistDao().getCrossRefsForPlaylist(playlistId)
        assertEquals("expected exactly one cross-ref row (the tombstone)", 1, crossRefs.size)
        assertTrue("tombstone must stay soft-deleted, not resurrected", crossRefs.single().removedAt != null)

        val visibleTracks = db.playlistDao().getTracksForPlaylist(playlistId)
        assertTrue("soft-deleted track must not appear in visible playlist tracks", visibleTracks.isEmpty())
    }

    // ── ACCUMULATE preserves addedAt on re-stamped rows ───────────────────

    @Test
    fun `ACCUMULATE preserves original addedAt when a track is re-synced`() = runBlocking {
        val track = TrackEntity(
            title = "Borderline", artist = "Tame Impala",
            canonicalTitle = "borderline", canonicalArtist = "tame impala",
            spotifyUri = "spotify:track:xyz",
        )
        val trackId = db.trackDao().insert(track)

        val playlist = PlaylistEntity(
            name = "Discover Weekly", source = MusicSource.SPOTIFY,
            sourceId = "spotify:playlist:dw", type = PlaylistType.DAILY_MIX,
            syncEnabled = true,
        )
        val playlistId = db.playlistDao().insert(playlist)

        val originalAddedAt = Instant.parse("2024-03-01T00:00:00Z")
        db.playlistDao().insertCrossRef(
            PlaylistTrackCrossRef(
                playlistId = playlistId, trackId = trackId, position = 3,
                addedAt = originalAddedAt, removedAt = null,
            )
        )

        every { syncPreferencesManager.spotifySyncMode } returns flowOf(SyncMode.ACCUMULATE)

        val playlistSnapshot = RemotePlaylistSnapshotEntity(
            id = 2L, syncId = 1L, source = MusicSource.SPOTIFY,
            sourcePlaylistId = "spotify:playlist:dw",
            playlistName = "Discover Weekly", playlistType = PlaylistType.DAILY_MIX,
        )
        coEvery { remoteSnapshotDao.getPlaylistSnapshotsBySyncId(1L) } returns listOf(playlistSnapshot)
        coEvery { remoteSnapshotDao.getTrackSnapshotsByPlaylistId(2L) } returns listOf(
            RemoteTrackSnapshotEntity(
                syncId = 1L, snapshotPlaylistId = 2L,
                title = "Borderline", artist = "Tame Impala",
                spotifyUri = "spotify:track:xyz", position = 0,
            )
        )

        val result = buildWorker().doWork()
        assertTrue("diff must succeed", result is androidx.work.ListenableWorker.Result.Success)

        val crossRef = db.playlistDao().getCrossRef(playlistId, trackId)
        assertEquals(originalAddedAt, crossRef?.addedAt)
    }

    // ── Batch identity match priority: spotifyUri > youtubeId > canonical ─

    @Test
    fun `matches by spotifyUri even when youtubeId and canonical also match different rows`() = runBlocking {
        val trackDao = db.trackDao()
        val bySpotify = trackDao.insert(
            TrackEntity(title = "T1", artist = "A1", spotifyUri = "spotify:track:winner",
                canonicalTitle = "unrelated1", canonicalArtist = "unrelated1")
        )
        trackDao.insert(
            TrackEntity(title = "T2", artist = "A2", youtubeId = "yt-loser",
                canonicalTitle = "unrelated2", canonicalArtist = "unrelated2")
        )
        trackDao.insert(
            TrackEntity(title = "SongA", artist = "ArtistA",
                canonicalTitle = "songa", canonicalArtist = "artista")
        )

        val playlist = PlaylistEntity(
            name = "P", source = MusicSource.SPOTIFY, sourceId = "spotify:playlist:p",
            syncEnabled = true,
        )
        val playlistId = db.playlistDao().insert(playlist)

        val playlistSnapshot = RemotePlaylistSnapshotEntity(
            id = 3L, syncId = 1L, source = MusicSource.SPOTIFY,
            sourcePlaylistId = "spotify:playlist:p", playlistName = "P",
        )
        coEvery { remoteSnapshotDao.getPlaylistSnapshotsBySyncId(1L) } returns listOf(playlistSnapshot)
        coEvery { remoteSnapshotDao.getTrackSnapshotsByPlaylistId(3L) } returns listOf(
            RemoteTrackSnapshotEntity(
                syncId = 1L, snapshotPlaylistId = 3L,
                title = "SongA", artist = "ArtistA",
                spotifyUri = "spotify:track:winner", youtubeId = "yt-loser", position = 0,
            )
        )
        every { syncPreferencesManager.spotifySyncMode } returns flowOf(SyncMode.ACCUMULATE)

        val result = buildWorker().doWork()
        assertTrue("diff must succeed", result is androidx.work.ListenableWorker.Result.Success)

        val crossRefs = db.playlistDao().getCrossRefsForPlaylist(playlistId)
        assertEquals(1, crossRefs.size)
        assertEquals("spotifyUri match must win", bySpotify, crossRefs.single().trackId)
        assertEquals("no new track should be inserted — matched existing", 3, trackDao.getAllForIntegrityScan().size)
    }

    @Test
    fun `duplicate Spotify URI in one custom playlist reuses a valid track row`() = runBlocking {
        db.playlistDao().insert(
            PlaylistEntity(
                name = "Duplicates",
                source = MusicSource.SPOTIFY,
                sourceId = "spotify:playlist:duplicates",
                type = PlaylistType.CUSTOM,
                syncEnabled = true,
            )
        )
        val playlistSnapshot = RemotePlaylistSnapshotEntity(
            id = 4L,
            syncId = 1L,
            source = MusicSource.SPOTIFY,
            sourcePlaylistId = "spotify:playlist:duplicates",
            playlistName = "Duplicates",
            playlistType = PlaylistType.CUSTOM,
        )
        val duplicateSnapshots = listOf(
            RemoteTrackSnapshotEntity(
                syncId = 1L,
                snapshotPlaylistId = 4L,
                title = "Repeat",
                artist = "Artist",
                spotifyUri = "spotify:track:repeat",
                position = 0,
            ),
            RemoteTrackSnapshotEntity(
                syncId = 1L,
                snapshotPlaylistId = 4L,
                title = "Repeat",
                artist = "Artist",
                spotifyUri = "spotify:track:repeat",
                position = 7,
            ),
        )
        coEvery { remoteSnapshotDao.getPlaylistSnapshotsBySyncId(1L) } returns listOf(playlistSnapshot)
        coEvery { remoteSnapshotDao.getTrackSnapshotsByPlaylistId(4L) } returns duplicateSnapshots
        coEvery { streamingPreference.current() } returns true

        val result = buildWorker().doWork()

        assertTrue("duplicate playlist entries must not fail the sync", result is androidx.work.ListenableWorker.Result.Success)
        val output = (result as androidx.work.ListenableWorker.Result.Success).outputData
        assertEquals(1L, output.getLong(DiffWorker.KEY_SYNC_ID, -1L))
        assertEquals(1, output.getInt(DiffWorker.KEY_NEW_TRACKS, -1))
        assertEquals(1, output.getInt(DiffWorker.KEY_PLAYLISTS_CHECKED, -1))
        val playlist = requireNotNull(db.playlistDao().findBySourceId("spotify:playlist:duplicates"))
        val tracks = db.trackDao().getAllForIntegrityScan()
        val crossRefs = db.playlistDao().getCrossRefsForPlaylist(playlist.id)
        assertEquals("duplicate identity must create one track row", 1, tracks.size)
        assertEquals("local membership model stores one row per track", 1, crossRefs.size)
        assertEquals(tracks.single().id, crossRefs.single().trackId)
        assertEquals("last occurrence keeps the pre-batching position semantics", 7, crossRefs.single().position)
        assertNull(crossRefs.single().removedAt)
        assertEquals("spotify:track:repeat", tracks.single().spotifyUri)
        assertEquals("remote occurrence count remains visible in metadata", 2, playlist.trackCount)
    }

    @Test
    fun `duplicate YouTube ID queues one valid track offline`() = runBlocking {
        db.playlistDao().insert(
            PlaylistEntity(
                name = "YouTube Duplicates",
                source = MusicSource.YOUTUBE,
                sourceId = "youtube:playlist:duplicates",
                type = PlaylistType.CUSTOM,
                syncEnabled = true,
            )
        )
        val playlistSnapshot = RemotePlaylistSnapshotEntity(
            id = 5L,
            syncId = 1L,
            source = MusicSource.YOUTUBE,
            sourcePlaylistId = "youtube:playlist:duplicates",
            playlistName = "YouTube Duplicates",
            playlistType = PlaylistType.CUSTOM,
        )
        val duplicateSnapshots = listOf(
            RemoteTrackSnapshotEntity(
                syncId = 1L,
                snapshotPlaylistId = 5L,
                title = "Repeat",
                artist = "Artist",
                youtubeId = "repeat-video-id",
                position = 0,
            ),
            RemoteTrackSnapshotEntity(
                syncId = 1L,
                snapshotPlaylistId = 5L,
                title = "Repeat",
                artist = "Artist",
                youtubeId = "repeat-video-id",
                position = 7,
            ),
        )
        coEvery { remoteSnapshotDao.getPlaylistSnapshotsBySyncId(1L) } returns listOf(playlistSnapshot)
        coEvery { remoteSnapshotDao.getTrackSnapshotsByPlaylistId(5L) } returns duplicateSnapshots

        val result = buildWorker().doWork()

        assertTrue("duplicate YouTube entries must not fail the sync", result is androidx.work.ListenableWorker.Result.Success)
        val output = (result as androidx.work.ListenableWorker.Result.Success).outputData
        assertEquals(1, output.getInt(DiffWorker.KEY_NEW_TRACKS, -1))
        val playlist = requireNotNull(db.playlistDao().findBySourceId("youtube:playlist:duplicates"))
        val tracks = db.trackDao().getAllForIntegrityScan()
        val crossRefs = db.playlistDao().getCrossRefsForPlaylist(playlist.id)
        assertEquals(1, tracks.size)
        assertEquals("repeat-video-id", tracks.single().youtubeId)
        assertEquals(1, crossRefs.size)
        assertEquals(tracks.single().id, crossRefs.single().trackId)
        assertEquals(7, crossRefs.single().position)
        assertEquals(2, playlist.trackCount)
        coVerify(exactly = 1) {
            downloadQueueDao.insertAll(
                match { entries ->
                    entries.size == 1 &&
                        entries.single().trackId == tracks.single().id &&
                        entries.single().youtubeUrl ==
                        "https://music.youtube.com/watch?v=repeat-video-id"
                }
            )
        }
    }

    @Test
    fun `distinct YouTube IDs with the same canonical metadata stay separate`() = runBlocking {
        db.playlistDao().insert(
            PlaylistEntity(
                name = "Strong IDs",
                source = MusicSource.YOUTUBE,
                sourceId = "youtube:playlist:strong-ids",
                type = PlaylistType.CUSTOM,
                syncEnabled = true,
            )
        )
        val playlistSnapshot = RemotePlaylistSnapshotEntity(
            id = 6L,
            syncId = 1L,
            source = MusicSource.YOUTUBE,
            sourcePlaylistId = "youtube:playlist:strong-ids",
            playlistName = "Strong IDs",
            playlistType = PlaylistType.CUSTOM,
        )
        val snapshots = listOf(
            RemoteTrackSnapshotEntity(
                syncId = 1L,
                snapshotPlaylistId = 6L,
                title = "Same Song",
                artist = "Artist",
                youtubeId = "video-one",
                position = 0,
            ),
            RemoteTrackSnapshotEntity(
                syncId = 1L,
                snapshotPlaylistId = 6L,
                title = "Same Song",
                artist = "Artist",
                youtubeId = "video-two",
                position = 1,
            ),
        )
        coEvery { remoteSnapshotDao.getPlaylistSnapshotsBySyncId(1L) } returns listOf(playlistSnapshot)
        coEvery { remoteSnapshotDao.getTrackSnapshotsByPlaylistId(6L) } returns snapshots
        coEvery { streamingPreference.current() } returns true

        val result = buildWorker().doWork()

        assertTrue(result is androidx.work.ListenableWorker.Result.Success)
        assertEquals(2, db.trackDao().getAllForIntegrityScan().size)
        assertEquals(
            setOf("video-one", "video-two"),
            db.trackDao().getAllForIntegrityScan().mapNotNull { it.youtubeId }.toSet(),
        )
        val playlist = requireNotNull(db.playlistDao().findBySourceId("youtube:playlist:strong-ids"))
        assertEquals(2, db.playlistDao().getCrossRefsForPlaylist(playlist.id).size)
    }

    @Test
    fun `existing canonical match reserves its first YouTube identity`() = runBlocking {
        val existingId = db.trackDao().insert(
            TrackEntity(
                title = "Same Song",
                artist = "Artist",
                canonicalTitle = "same song",
                canonicalArtist = "artist",
                source = MusicSource.SPOTIFY,
                spotifyUri = "spotify:track:existing",
            )
        )
        db.playlistDao().insert(
            PlaylistEntity(
                name = "Identity Backfill",
                source = MusicSource.YOUTUBE,
                sourceId = "youtube:playlist:identity-backfill",
                type = PlaylistType.CUSTOM,
                syncEnabled = true,
            )
        )
        val playlistSnapshot = RemotePlaylistSnapshotEntity(
            id = 7L,
            syncId = 1L,
            source = MusicSource.YOUTUBE,
            sourcePlaylistId = "youtube:playlist:identity-backfill",
            playlistName = "Identity Backfill",
            playlistType = PlaylistType.CUSTOM,
        )
        val snapshots = listOf(
            RemoteTrackSnapshotEntity(
                syncId = 1L,
                snapshotPlaylistId = 7L,
                title = "Same Song",
                artist = "Artist",
                youtubeId = "video-one",
                position = 0,
            ),
            RemoteTrackSnapshotEntity(
                syncId = 1L,
                snapshotPlaylistId = 7L,
                title = "Same Song",
                artist = "Artist",
                youtubeId = "video-two",
                position = 1,
            ),
        )
        coEvery { remoteSnapshotDao.getPlaylistSnapshotsBySyncId(1L) } returns listOf(playlistSnapshot)
        coEvery { remoteSnapshotDao.getTrackSnapshotsByPlaylistId(7L) } returns snapshots
        coEvery { streamingPreference.current() } returns true

        val result = buildWorker().doWork()

        assertTrue(result is androidx.work.ListenableWorker.Result.Success)
        assertEquals("video-one", db.trackDao().getById(existingId)?.youtubeId)
        assertEquals(
            setOf("video-one", "video-two"),
            db.trackDao().getAllForIntegrityScan().mapNotNull { it.youtubeId }.toSet(),
        )
        val playlist = requireNotNull(db.playlistDao().findBySourceId("youtube:playlist:identity-backfill"))
        assertEquals(2, db.playlistDao().getCrossRefsForPlaylist(playlist.id).size)
    }

    @Test
    fun `duplicate existing track keeps the last nonblank artwork`() = runBlocking {
        val trackId = db.trackDao().insert(
            TrackEntity(
                title = "Artwork",
                artist = "Artist",
                canonicalTitle = "artwork",
                canonicalArtist = "artist",
                source = MusicSource.SPOTIFY,
                spotifyUri = "spotify:track:artwork",
                albumArtUrl = "https://cdn.example/old.jpg",
            )
        )
        db.playlistDao().insert(
            PlaylistEntity(
                name = "Artwork Order",
                source = MusicSource.SPOTIFY,
                sourceId = "spotify:playlist:artwork-order",
                type = PlaylistType.CUSTOM,
                syncEnabled = true,
            )
        )
        val playlistSnapshot = RemotePlaylistSnapshotEntity(
            id = 8L,
            syncId = 1L,
            source = MusicSource.SPOTIFY,
            sourcePlaylistId = "spotify:playlist:artwork-order",
            playlistName = "Artwork Order",
            playlistType = PlaylistType.CUSTOM,
        )
        val snapshots = listOf(
            RemoteTrackSnapshotEntity(
                syncId = 1L,
                snapshotPlaylistId = 8L,
                title = "Artwork",
                artist = "Artist",
                spotifyUri = "spotify:track:artwork",
                albumArtUrl = "https://cdn.example/first.jpg",
                position = 0,
            ),
            RemoteTrackSnapshotEntity(
                syncId = 1L,
                snapshotPlaylistId = 8L,
                title = "Artwork",
                artist = "Artist",
                spotifyUri = "spotify:track:artwork",
                albumArtUrl = "https://cdn.example/last.jpg",
                position = 1,
            ),
        )
        coEvery { remoteSnapshotDao.getPlaylistSnapshotsBySyncId(1L) } returns listOf(playlistSnapshot)
        coEvery { remoteSnapshotDao.getTrackSnapshotsByPlaylistId(8L) } returns snapshots
        coEvery { streamingPreference.current() } returns true

        val result = buildWorker().doWork()

        assertTrue(result is androidx.work.ListenableWorker.Result.Success)
        assertEquals("https://cdn.example/last.jpg", db.trackDao().getById(trackId)?.albumArtUrl)
    }

    @Test
    fun `blank provider IDs do not merge distinct canonical tracks`() = runBlocking {
        db.playlistDao().insert(
            PlaylistEntity(
                name = "Blank IDs",
                source = MusicSource.SPOTIFY,
                sourceId = "spotify:playlist:blank-ids",
                type = PlaylistType.CUSTOM,
                syncEnabled = true,
            )
        )
        val playlistSnapshot = RemotePlaylistSnapshotEntity(
            id = 9L,
            syncId = 1L,
            source = MusicSource.SPOTIFY,
            sourcePlaylistId = "spotify:playlist:blank-ids",
            playlistName = "Blank IDs",
            playlistType = PlaylistType.CUSTOM,
        )
        val snapshots = listOf(
            RemoteTrackSnapshotEntity(
                syncId = 1L,
                snapshotPlaylistId = 9L,
                title = "First",
                artist = "One",
                spotifyUri = "",
                position = 0,
            ),
            RemoteTrackSnapshotEntity(
                syncId = 1L,
                snapshotPlaylistId = 9L,
                title = "Second",
                artist = "Two",
                spotifyUri = "",
                position = 1,
            ),
        )
        coEvery { remoteSnapshotDao.getPlaylistSnapshotsBySyncId(1L) } returns listOf(playlistSnapshot)
        coEvery { remoteSnapshotDao.getTrackSnapshotsByPlaylistId(9L) } returns snapshots
        coEvery { streamingPreference.current() } returns true

        val result = buildWorker().doWork()

        assertTrue(result is androidx.work.ListenableWorker.Result.Success)
        assertEquals(2, db.trackDao().getAllForIntegrityScan().size)
        val playlist = requireNotNull(db.playlistDao().findBySourceId("spotify:playlist:blank-ids"))
        assertEquals(2, db.playlistDao().getCrossRefsForPlaylist(playlist.id).size)
    }

    @Test
    fun `LIKED_SONGS backfills missing art from the snapshot`() = runBlocking {
        assertEquals(
            "https://cdn.example/new-cover.jpg",
            syncLikedPlaylistArt(existingArt = "  ", snapshotArt = "https://cdn.example/new-cover.jpg"),
        )
    }

    @Test
    fun `LIKED_SONGS preserves an existing nonblank cover`() = runBlocking {
        assertEquals(
            "https://cdn.example/established-cover.jpg",
            syncLikedPlaylistArt(
                existingArt = "https://cdn.example/established-cover.jpg",
                snapshotArt = "https://cdn.example/new-cover.jpg",
            ),
        )
    }

    private suspend fun syncLikedPlaylistArt(existingArt: String?, snapshotArt: String?): String? {
        val playlistId = db.playlistDao().insert(
            PlaylistEntity(
                name = "Liked Songs",
                source = MusicSource.SPOTIFY,
                sourceId = "spotify_liked_songs",
                type = PlaylistType.LIKED_SONGS,
                artUrl = existingArt,
                syncEnabled = true,
            )
        )
        val snapshot = RemotePlaylistSnapshotEntity(
            id = 4L,
            syncId = 1L,
            source = MusicSource.SPOTIFY,
            sourcePlaylistId = "spotify_liked_songs",
            playlistName = "Liked Songs",
            playlistType = PlaylistType.LIKED_SONGS,
            artUrl = snapshotArt,
        )
        coEvery { remoteSnapshotDao.getPlaylistSnapshotsBySyncId(1L) } returns listOf(snapshot)
        coEvery { remoteSnapshotDao.getTrackSnapshotsByPlaylistId(4L) } returns emptyList()

        val result = buildWorker().doWork()

        assertTrue("diff must succeed", result is androidx.work.ListenableWorker.Result.Success)
        return db.playlistDao().getById(playlistId)?.artUrl
    }

    // ── #343: REFRESH must never mirror-clear against unreliable fetches ──

    private suspend fun seedSyncedPlaylist(
        snapshotId: String? = "old-snap",
    ): Pair<Long, Long> {
        val trackId = db.trackDao().insert(
            TrackEntity(
                title = "Keeper", artist = "Artist",
                canonicalTitle = "keeper", canonicalArtist = "artist",
                spotifyUri = "spotify:track:keeper",
            )
        )
        val playlistId = db.playlistDao().insert(
            PlaylistEntity(
                name = "Jams", source = MusicSource.SPOTIFY,
                sourceId = "pl1", type = PlaylistType.CUSTOM,
                syncEnabled = true,
            )
        )
        db.playlistDao().insertCrossRef(
            PlaylistTrackCrossRef(playlistId = playlistId, trackId = trackId, position = 0)
        )
        snapshotId?.let { db.playlistDao().updateSnapshotId(playlistId, it) }
        db.playlistDao().updateTrackCount(playlistId, 1)
        return playlistId to trackId
    }

    private fun stubSnapshot(
        partial: Boolean,
        listedTrackCount: Int,
        trackSnapshots: List<RemoteTrackSnapshotEntity> = emptyList(),
    ) {
        val snapshot = RemotePlaylistSnapshotEntity(
            id = 4L, syncId = 1L, source = MusicSource.SPOTIFY,
            sourcePlaylistId = "pl1", playlistName = "Jams",
            playlistType = PlaylistType.CUSTOM, trackCount = listedTrackCount,
            partial = partial, snapshotId = "new-snap",
        )
        coEvery { remoteSnapshotDao.getPlaylistSnapshotsBySyncId(1L) } returns listOf(snapshot)
        coEvery { remoteSnapshotDao.getTrackSnapshotsByPlaylistId(4L) } returns trackSnapshots
    }

    private suspend fun activeTrackCount(playlistId: Long): Int =
        db.playlistDao().getCrossRefsForPlaylist(playlistId).count { it.removedAt == null }

    @Test
    fun `REFRESH keeps local tracks when the snapshot is marked partial`() = runBlocking {
        val (playlistId, _) = seedSyncedPlaylist()
        stubSnapshot(partial = true, listedTrackCount = 12)

        val result = buildWorker().doWork()

        assertTrue("diff must succeed", result is androidx.work.ListenableWorker.Result.Success)
        assertEquals("local track survives a partial fetch", 1, activeTrackCount(playlistId))
    }

    @Test
    fun `REFRESH keeps local tracks when the snapshot is empty but the listing claims tracks`() = runBlocking {
        val (playlistId, _) = seedSyncedPlaylist()
        stubSnapshot(partial = false, listedTrackCount = 12)

        val result = buildWorker().doWork()

        assertTrue(result is androidx.work.ListenableWorker.Result.Success)
        assertEquals("unmarked failure shape must not clear", 1, activeTrackCount(playlistId))
    }

    @Test
    fun `REFRESH mirrors a genuinely emptied playlist`() = runBlocking {
        val (playlistId, _) = seedSyncedPlaylist()
        stubSnapshot(partial = false, listedTrackCount = 0)

        val result = buildWorker().doWork()

        assertTrue(result is androidx.work.ListenableWorker.Result.Success)
        assertEquals("clean empty fetch mirrors the empty remote", 0, activeTrackCount(playlistId))
    }

    @Test
    fun `partial fetch does not advance snapshot id or track count`() = runBlocking {
        val (playlistId, _) = seedSyncedPlaylist(snapshotId = "old-snap")
        stubSnapshot(partial = true, listedTrackCount = 12)

        val result = buildWorker().doWork()

        assertTrue(result is androidx.work.ListenableWorker.Result.Success)
        assertEquals(
            "snapshot id must stay stale so the next sync re-diffs this playlist",
            "old-snap", db.playlistDao().getSnapshotId(playlistId),
        )
        assertEquals("track count must not lie over retained tracks", 1, db.playlistDao().getById(playlistId)?.trackCount)
    }

    @Test
    fun `partial fetch still merges the additions it did return`() = runBlocking {
        val (playlistId, _) = seedSyncedPlaylist()
        stubSnapshot(
            partial = true, listedTrackCount = 12,
            trackSnapshots = listOf(
                RemoteTrackSnapshotEntity(
                    id = 9L, syncId = 1L, snapshotPlaylistId = 4L,
                    title = "Newcomer", artist = "Artist",
                    durationMs = 200_000, spotifyUri = "spotify:track:new",
                    position = 0,
                )
            ),
        )

        val result = buildWorker().doWork()

        assertTrue(result is androidx.work.ListenableWorker.Result.Success)
        assertEquals("existing track kept AND the fetched addition merged", 2, activeTrackCount(playlistId))
    }

    private fun buildWorker(): DiffWorker = TestListenableWorkerBuilder<DiffWorker>(context)
        .setInputData(workDataOf(PlaylistFetchWorker.KEY_SYNC_ID to 1L))
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ) = DiffWorker(
                appContext, workerParameters,
                database = db,
                remoteSnapshotDao = remoteSnapshotDao,
                trackDao = db.trackDao(),
                playlistDao = db.playlistDao(),
                downloadQueueDao = downloadQueueDao,
                syncHistoryDao = syncHistoryDao,
                trackMatcher = TrackMatcher(),
                syncStateManager = syncStateManager,
                musicRepository = musicRepository,
                syncPreferencesManager = syncPreferencesManager,
                blocklistGuard = blocklistGuard,
                streamingPreference = streamingPreference,
            )
        })
        .build()
}
