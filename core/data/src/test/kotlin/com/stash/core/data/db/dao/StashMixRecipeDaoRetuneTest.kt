package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.StashMixRecipeEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [StashMixRecipeDao.retuneBuiltin] — the in-place builtin-recipe
 * retune used by one-shot tuning migrations. Covers the v0.9.20 expansion
 * (affinityBias + seedStrategy) and the v0.9.40 tag-engine expansion
 * (moodKeysCsv + tagSampleDepth), i.e. all seven tunable fields in one UPDATE.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class StashMixRecipeDaoRetuneTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: StashMixRecipeDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StashDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        dao = db.stashMixRecipeDao()
    }

    @After fun tearDown() { db.close() }

    @Test fun `retuneBuiltin updates all 5 tunable fields including affinityBias and seedStrategy`() = runTest {
        dao.insert(
            StashMixRecipeEntity(
                name = "Daily Discover",
                discoveryRatio = 0.4f,
                freshnessWindowDays = 7,
                targetLength = 50,
                affinityBias = 0.3f,
                seedStrategy = "ARTIST_SIMILAR",
                isBuiltin = true,
            )
        )

        val updated = dao.retuneBuiltin(
            name = "Daily Discover",
            discoveryRatio = 0.85f,
            freshnessWindowDays = 7,
            targetLength = 40,
            affinityBias = 0.0f,
            seedStrategy = "ARTIST_SIMILAR",
            moodKeysCsv = "",
            tagSampleDepth = 0,
        )

        assertEquals(1, updated)
        val after = dao.getActive().single()
        assertEquals(0.85f, after.discoveryRatio)
        assertEquals(40, after.targetLength)
        assertEquals(0.0f, after.affinityBias)
        assertEquals("ARTIST_SIMILAR", after.seedStrategy)
        assertEquals(7, after.freshnessWindowDays)
    }

    @Test fun `retuneBuiltin is idempotent — second call with same values returns 1 with no state change`() = runTest {
        dao.insert(
            StashMixRecipeEntity(
                name = "Deep Cuts",
                discoveryRatio = 0.85f,
                freshnessWindowDays = 90,
                targetLength = 40,
                affinityBias = 0.0f,
                seedStrategy = "TRACK_SIMILAR",
                isBuiltin = true,
            )
        )

        val first = dao.retuneBuiltin("Deep Cuts", 0.85f, 90, 40, 0.0f, "TRACK_SIMILAR", "", 0)
        val second = dao.retuneBuiltin("Deep Cuts", 0.85f, 90, 40, 0.0f, "TRACK_SIMILAR", "", 0)

        assertEquals(1, first)
        assertEquals(1, second) // SQLite UPDATE rowcount is rows-matched, not rows-changed
        val after = dao.getActive().single()
        assertEquals("TRACK_SIMILAR", after.seedStrategy)
    }

    @Test fun `retuneBuiltin repoints Deep Cuts to TAG_GRAPH with sample depth`() = runTest {
        dao.insert(
            StashMixRecipeEntity(
                name = "Deep Cuts", seedStrategy = "TRACK_SIMILAR",
                discoveryRatio = 0.85f, freshnessWindowDays = 90, targetLength = 40,
                isBuiltin = true, tagSampleDepth = 0,
            )
        )
        dao.retuneBuiltin(
            name = "Deep Cuts", discoveryRatio = 0.85f, freshnessWindowDays = 90,
            targetLength = 40, affinityBias = 0.0f, seedStrategy = "TAG_GRAPH",
            moodKeysCsv = "", tagSampleDepth = 15,
        )
        val row = dao.getActive().first { it.name == "Deep Cuts" }
        assertEquals("TAG_GRAPH", row.seedStrategy)
        assertEquals(15, row.tagSampleDepth)
    }

    @Test fun `retuneBuiltin does not touch non-builtin recipes with the same name`() = runTest {
        dao.insert(
            StashMixRecipeEntity(
                name = "Daily Discover",
                discoveryRatio = 0.4f,
                affinityBias = 0.3f,
                seedStrategy = "ARTIST_SIMILAR",
                isBuiltin = false, // user-created with same name
            )
        )

        val updated = dao.retuneBuiltin("Daily Discover", 0.85f, 7, 40, 0.0f, "ARTIST_SIMILAR", "", 0)

        assertEquals(0, updated)
        val after = dao.getActive().single()
        assertEquals(0.4f, after.discoveryRatio) // untouched
        assertEquals(0.3f, after.affinityBias)
    }
}
