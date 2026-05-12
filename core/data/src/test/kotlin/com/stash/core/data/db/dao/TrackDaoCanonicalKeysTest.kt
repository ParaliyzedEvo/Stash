package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.TrackEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [TrackDao.getLibraryCanonicalKeys] — the v0.9.20 method
 * used by [StashMixRefreshWorker]'s discovery pre-filter to drop
 * Last.fm candidates that would dedup to library content.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TrackDaoCanonicalKeysTest {

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

    @Test fun `returns canonical keys only for downloaded tracks`() = runTest {
        dao.insert(trackEntity(id = 1L, canonicalArtist = "tame impala", canonicalTitle = "borderline", isDownloaded = true))
        dao.insert(trackEntity(id = 2L, canonicalArtist = "tame impala", canonicalTitle = "the less i know the better", isDownloaded = true))
        dao.insert(trackEntity(id = 3L, canonicalArtist = "stub artist", canonicalTitle = "stub title", isDownloaded = false))

        val result = dao.getLibraryCanonicalKeys()

        assertEquals(setOf("tame impala|borderline", "tame impala|the less i know the better"), result.toSet())
        assertFalse("stub track must be excluded", "stub artist|stub title" in result)
    }

    @Test fun `returns DISTINCT keys — duplicate canonical-pairs collapse to one row`() = runTest {
        dao.insert(trackEntity(id = 1L, canonicalArtist = "kanye west", canonicalTitle = "runaway", isDownloaded = true))
        dao.insert(trackEntity(id = 2L, canonicalArtist = "kanye west", canonicalTitle = "runaway", isDownloaded = true))

        val result = dao.getLibraryCanonicalKeys()

        assertEquals(listOf("kanye west|runaway"), result)
    }

    @Test fun `empty library returns empty list`() = runTest {
        val result = dao.getLibraryCanonicalKeys()
        assertEquals(emptyList<String>(), result)
    }

    private fun trackEntity(
        id: Long,
        canonicalArtist: String,
        canonicalTitle: String,
        isDownloaded: Boolean,
    ) = TrackEntity(
        id = id,
        title = "Title $id",
        artist = "Artist $id",
        canonicalTitle = canonicalTitle,
        canonicalArtist = canonicalArtist,
        isDownloaded = isDownloaded,
    )
}
