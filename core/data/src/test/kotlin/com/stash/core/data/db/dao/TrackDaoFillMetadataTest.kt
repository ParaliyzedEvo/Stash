package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.TrackEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for [TrackDao.fillMissingMetadata]'s album handling.
 *
 * Bug: the match pipeline (`DownloadManager.persistMatchMetadata`) passes
 * `album = null` whenever the matched YouTube/UGC result has no album. The
 * original query was `SET album = CASE WHEN album IS NULL OR album = ''
 * THEN :album ELSE album END`, so a row whose album is already `''` (every
 * Stash-Discover stub and most Spotify rows) tried to `SET album = NULL`,
 * crashing with `NOT NULL constraint failed: tracks.album` and dropping the
 * whole metadata write (art included).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TrackDaoFillMetadataTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: TrackDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StashDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        dao = db.trackDao()
    }

    @After fun tearDown() { db.close() }

    private suspend fun insertBlankAlbumTrack(id: Long = 1L): Long {
        val rowId = dao.insert(
            TrackEntity(
                id = id,
                title = "Some Song",
                artist = "Some Artist",
                album = "",
                canonicalTitle = "some song",
                canonicalArtist = "some artist",
            ),
        )
        return rowId
    }

    @Test
    fun `fillMissingMetadata with null album on a blank-album row does not crash`() = runTest {
        val id = insertBlankAlbumTrack()

        // Must not throw NOT NULL constraint failed: tracks.album
        dao.fillMissingMetadata(
            trackId = id,
            album = null,
            albumArtUrl = "https://example.com/art.jpg",
            durationMs = 200_000L,
            youtubeId = "abc123",
        )

        val row = dao.getById(id)!!
        // Album stays the blank sentinel (we had nothing better to write)…
        assertEquals("", row.album)
        // …and the other fields still landed (the write was NOT rolled back).
        assertEquals("https://example.com/art.jpg", row.albumArtUrl)
        assertEquals("abc123", row.youtubeId)
        assertEquals(200_000L, row.durationMs)
    }

    @Test
    fun `fillMissingMetadata still fills album when a non-null value is supplied`() = runTest {
        val id = insertBlankAlbumTrack()

        dao.fillMissingMetadata(
            trackId = id,
            album = "Real Album",
            albumArtUrl = null,
            durationMs = 0L,
            youtubeId = null,
        )

        assertEquals("Real Album", dao.getById(id)!!.album)
    }

    @Test
    fun `fillMissingMetadata never overwrites an existing album`() = runTest {
        val id = dao.insert(
            TrackEntity(
                id = 2L,
                title = "T",
                artist = "A",
                album = "Existing Album",
                canonicalTitle = "t",
                canonicalArtist = "a",
            ),
        )

        dao.fillMissingMetadata(
            trackId = id,
            album = "Different Album",
            albumArtUrl = null,
            durationMs = 0L,
            youtubeId = null,
        )

        assertEquals("Existing Album", dao.getById(id)!!.album)
    }
}
