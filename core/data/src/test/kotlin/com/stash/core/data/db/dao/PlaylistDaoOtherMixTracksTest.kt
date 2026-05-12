package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PlaylistDaoOtherMixTracksTest {

    private lateinit var db: StashDatabase
    private lateinit var playlistDao: PlaylistDao
    private lateinit var trackDao: TrackDao

    @Before fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StashDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        playlistDao = db.playlistDao()
        trackDao = db.trackDao()
    }

    @After fun tearDown() { db.close() }

    @Test fun `returns DISTINCT track ids across multiple playlists`() = runTest {
        trackDao.insert(track(1L))
        trackDao.insert(track(2L))
        trackDao.insert(track(3L))

        val playlistA = playlistDao.insert(stashMixPlaylist(name = "Mix A"))
        val playlistB = playlistDao.insert(stashMixPlaylist(name = "Mix B"))
        playlistDao.insertCrossRef(crossRef(playlistA, trackId = 1L, position = 0))
        playlistDao.insertCrossRef(crossRef(playlistA, trackId = 2L, position = 1))
        playlistDao.insertCrossRef(crossRef(playlistB, trackId = 2L, position = 0))
        playlistDao.insertCrossRef(crossRef(playlistB, trackId = 3L, position = 1))

        val result = playlistDao.getTrackIdsForPlaylists(listOf(playlistA, playlistB))

        assertEquals(setOf(1L, 2L, 3L), result.toSet())
        assertEquals("expected DISTINCT — track 2 once", 3, result.size)
    }

    @Test fun `empty input returns empty list`() = runTest {
        val result = playlistDao.getTrackIdsForPlaylists(emptyList())
        assertTrue("expected empty, got $result", result.isEmpty())
    }

    @Test fun `filters by the given playlist ids — tracks in OTHER playlists are not returned`() = runTest {
        trackDao.insert(track(1L))
        trackDao.insert(track(2L))

        val included = playlistDao.insert(stashMixPlaylist(name = "Included"))
        val excluded = playlistDao.insert(stashMixPlaylist(name = "Excluded"))
        playlistDao.insertCrossRef(crossRef(included, trackId = 1L, position = 0))
        playlistDao.insertCrossRef(crossRef(excluded, trackId = 2L, position = 0))

        val result = playlistDao.getTrackIdsForPlaylists(listOf(included))

        assertEquals(listOf(1L), result)
    }

    private fun track(id: Long) = TrackEntity(
        id = id,
        title = "Track $id",
        artist = "Artist $id",
        canonicalTitle = "track $id",
        canonicalArtist = "artist $id",
        isDownloaded = true,
    )

    private fun stashMixPlaylist(name: String) = PlaylistEntity(
        name = name,
        source = MusicSource.BOTH,
        sourceId = "stash_mix_$name",
        type = PlaylistType.STASH_MIX,
        trackCount = 0,
        syncEnabled = true,
        isActive = true,
    )

    private fun crossRef(playlistId: Long, trackId: Long, position: Int) =
        PlaylistTrackCrossRef(
            playlistId = playlistId,
            trackId = trackId,
            position = position,
            addedAt = Instant.EPOCH,
        )
}
