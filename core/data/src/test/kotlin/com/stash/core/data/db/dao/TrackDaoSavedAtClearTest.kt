package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.TrackEntity
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
 * v0.9.52 like-mirroring: symmetric un-like must clear the
 * `*_saved_at` dedup columns so a future re-heart re-fires the
 * external write. Each clear touches ONLY its own column.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TrackDaoSavedAtClearTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: TrackDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StashDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.trackDao()
    }

    @After fun tearDown() { db.close() }

    @Test fun clearSpotifySaved_nullsOnlySpotifyColumn() = runTest {
        dao.insert(track(id = 1L, spotifySavedAt = 111L, ytMusicSavedAt = 222L))

        dao.clearSpotifySaved(1L)

        val row = dao.getById(1L)!!
        assertNull(row.spotifySavedAt)
        assertEquals(222L, row.ytMusicSavedAt)
    }

    @Test fun clearYtMusicSaved_nullsOnlyYtColumn() = runTest {
        dao.insert(track(id = 1L, spotifySavedAt = 111L, ytMusicSavedAt = 222L))

        dao.clearYtMusicSaved(1L)

        val row = dao.getById(1L)!!
        assertEquals(111L, row.spotifySavedAt)
        assertNull(row.ytMusicSavedAt)
    }

    @Test fun clears_areNoOpsOnOtherRows() = runTest {
        dao.insert(track(id = 1L, spotifySavedAt = 111L, ytMusicSavedAt = 222L))
        dao.insert(track(id = 2L, spotifySavedAt = 333L, ytMusicSavedAt = 444L))

        dao.clearSpotifySaved(1L)
        dao.clearYtMusicSaved(1L)

        val other = dao.getById(2L)!!
        assertEquals(333L, other.spotifySavedAt)
        assertEquals(444L, other.ytMusicSavedAt)
    }

    private fun track(
        id: Long,
        spotifySavedAt: Long? = null,
        ytMusicSavedAt: Long? = null,
    ) = TrackEntity(
        id = id,
        title = "Title $id",
        artist = "Artist $id",
        canonicalTitle = "title $id",
        canonicalArtist = "artist $id",
        spotifySavedAt = spotifySavedAt,
        ytMusicSavedAt = ytMusicSavedAt,
    )
}
