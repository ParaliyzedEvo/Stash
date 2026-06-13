package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.SpotifyResolutionEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SpotifyResolutionDaoTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: SpotifyResolutionDao

    @Before fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StashDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        dao = db.spotifyResolutionDao()
    }

    @After fun tearDown() { db.close() }

    @Test fun `upsert then get round-trips MATCHED row`() = runTest {
        val row = matched(trackId = 1L, uri = "spotify:track:abc123")
        dao.upsert(row)

        val fetched = dao.get(1L)
        assertEquals("spotify:track:abc123", fetched?.spotifyUri)
        assertEquals("MATCHED", fetched?.status)
        assertEquals("USISRC123", fetched?.matchedIsrc)
        assertEquals(0.97f, fetched?.titleSim)
        assertEquals(2, fetched?.durDeltaSec)

        // unknown trackId returns null
        assertNull(dao.get(999L))
    }

    @Test fun `upsert replaces existing row by trackId (PK upsert)`() = runTest {
        dao.upsert(noMatch(trackId = 7L))
        assertEquals("NO_MATCH", dao.get(7L)?.status)

        dao.upsert(matched(trackId = 7L, uri = "spotify:track:winner"))

        val fetched = dao.get(7L)
        assertEquals("latest upsert wins", "MATCHED", fetched?.status)
        assertEquals("spotify:track:winner", fetched?.spotifyUri)
    }

    @Test fun `deleteByTrackIds removes only listed rows`() = runTest {
        dao.upsert(matched(trackId = 1L, uri = "spotify:track:a"))
        dao.upsert(matched(trackId = 2L, uri = "spotify:track:b"))
        dao.upsert(matched(trackId = 3L, uri = "spotify:track:c"))

        dao.deleteByTrackIds(listOf(1L, 3L))

        assertNull(dao.get(1L))
        assertNull(dao.get(3L))
        assertEquals("spotify:track:b", dao.get(2L)?.spotifyUri)
    }

    @Test fun `migration 31 to 32 creates spotify_resolution table`() = runTest {
        // In-memory DB opened at the latest version (32). Assert the table
        // exists. The project has no MigrationTestHelper-based test to mirror,
        // so this sqlite_master check on the built DB is the chosen approach.
        val cursor = db.openHelper.writableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='spotify_resolution'"
        )
        cursor.use {
            assertTrue("spotify_resolution table must exist", it.moveToFirst())
            assertEquals("spotify_resolution", it.getString(0))
        }
    }

    // ---- helpers ----

    private fun matched(trackId: Long, uri: String) = SpotifyResolutionEntity(
        trackId = trackId,
        status = "MATCHED",
        spotifyUri = uri,
        matchedIsrc = "USISRC123",
        titleSim = 0.97f,
        durDeltaSec = 2,
        resolvedAtMs = 1_000L,
        expiresAtMs = 2_000L,
    )

    private fun noMatch(trackId: Long) = SpotifyResolutionEntity(
        trackId = trackId,
        status = "NO_MATCH",
        spotifyUri = null,
        matchedIsrc = null,
        titleSim = null,
        durDeltaSec = null,
        resolvedAtMs = 1_000L,
        expiresAtMs = 2_000L,
    )
}
