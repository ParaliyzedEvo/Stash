package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.model.MusicSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Home "Your playlists" pin is a targeted single-column UPDATE so sync
 * can't clobber it. This pins both directions of that write: setting the
 * timestamp and clearing it back to NULL (un-pin).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PlaylistDaoPinnedToHomeTest {

    private lateinit var db: StashDatabase
    private lateinit var playlistDao: PlaylistDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StashDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        playlistDao = db.playlistDao()
    }

    @After fun tearDown() { db.close() }

    @Test fun `setPinnedToHome round-trips and clears`() = runTest {
        val id = playlistDao.insert(
            PlaylistEntity(name = "Gym", source = MusicSource.SPOTIFY, sourceId = "sp1"),
        )
        // A second row proves the UPDATE is targeted, not a broadcast write.
        val otherId = playlistDao.insert(
            PlaylistEntity(name = "Roadtrip", source = MusicSource.SPOTIFY, sourceId = "sp2"),
        )

        playlistDao.setPinnedToHome(id, 1723900000000L)
        assertEquals(
            1723900000000L,
            playlistDao.getAllActive().first().single { it.id == id }.pinnedToHomeAt,
        )
        assertNull(playlistDao.getAllActive().first().single { it.id == otherId }.pinnedToHomeAt)

        playlistDao.setPinnedToHome(id, null)
        assertNull(playlistDao.getAllActive().first().single { it.id == id }.pinnedToHomeAt)
    }
}
