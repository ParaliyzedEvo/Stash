package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PlaylistDaoDownloadsMixLinkTest {

    private lateinit var db: StashDatabase
    private lateinit var playlistDao: PlaylistDao
    private lateinit var trackDao: TrackDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StashDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        playlistDao = db.playlistDao()
        trackDao = db.trackDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `strict link reactivates a soft-deleted downloads-mix membership`() = runTest {
        trackDao.insert(track())
        val playlist = downloadsMix()

        val playlistId = playlistDao.ensurePlaylistAndActiveCrossRef(playlist, TRACK_ID)
        val first = playlistDao.getCrossRef(playlistId, TRACK_ID)
        assertNotNull(first)
        assertNull(first?.removedAt)
        assertTrue(first?.locallyAdded == true)
        val reseededId = playlistDao.ensurePlaylist(playlist)
        assertEquals(playlistId, reseededId)
        assertNull(playlistDao.getCrossRef(playlistId, TRACK_ID)?.removedAt)


        playlistDao.softDeleteTrackFromPlaylist(playlistId, TRACK_ID)
        assertNotNull(playlistDao.getCrossRef(playlistId, TRACK_ID)?.removedAt)

        val restoredPlaylistId = playlistDao.ensurePlaylistAndActiveCrossRef(playlist, TRACK_ID)
        val restored = playlistDao.getCrossRef(restoredPlaylistId, TRACK_ID)

        assertEquals(playlistId, restoredPlaylistId)
        assertNull(restored?.removedAt)
        assertTrue(restored?.locallyAdded == true)
        assertEquals(playlistId, playlistDao.findBySourceId(DOWNLOADS_SOURCE_ID)?.id)
    }

    private fun track() = TrackEntity(
        id = TRACK_ID,
        title = "OPUS track",
        artist = "Artist",
        canonicalTitle = "opus track",
        canonicalArtist = "artist",
        youtubeId = "video-id",
        source = MusicSource.YOUTUBE,
    )

    private fun downloadsMix() = PlaylistEntity(
        name = "Your Downloads",
        source = MusicSource.BOTH,
        sourceId = DOWNLOADS_SOURCE_ID,
        type = PlaylistType.DOWNLOADS_MIX,
        syncEnabled = false,
    )

    private companion object {
        const val TRACK_ID = 99L
        const val DOWNLOADS_SOURCE_ID = "stash_downloads_mix"
    }
}
