package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.DiscoveryQueueEntity
import com.stash.core.data.db.entity.StashMixRecipeEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [DiscoveryQueueDao.getDoneTrackIdsForRecipe] — specifically the
 * v0.9.19 follow-up cap fix that bounds the result at `:limit` ordered by
 * `completed_at DESC` so newest-DONE survivors win when materializeMix
 * trims to the recipe's discovery slot count.
 *
 * Note: [DiscoveryQueueEntity] has a foreign key to
 * [StashMixRecipeEntity], so each test seeds a recipe row up front. There
 * is no FK from `discovery_queue.track_id` to `tracks.id`, so the tests
 * insert arbitrary track ids without seeding parent track rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DiscoveryQueueDaoCapTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: DiscoveryQueueDao
    private var recipeId: Long = 0L

    @Before fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StashDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        dao = db.discoveryQueueDao()
        // FK: discovery_queue.recipe_id -> stash_mix_recipes.id (CASCADE).
        // Seed a single recipe row that all tests share.
        recipeId = db.stashMixRecipeDao().insert(
            StashMixRecipeEntity(name = "Test Recipe", isBuiltin = false)
        )
    }

    @After fun tearDown() { db.close() }

    @Test fun `respects limit when DONE rows exceed it`() = runTest {
        // Insert 5 DONE rows with distinct track_ids and increasing completedAt.
        for (i in 1..5) {
            dao.insertIfNew(doneRow(recipeId, trackId = i.toLong(), completedAt = 1000L * i))
        }

        val result = dao.getDoneTrackIdsForRecipe(recipeId, limit = 3)

        assertEquals(3, result.size)
    }

    @Test fun `orders by completed_at DESC (newest-DONE first)`() = runTest {
        dao.insertIfNew(doneRow(recipeId, trackId = 1L, completedAt = 1000L))   // older
        dao.insertIfNew(doneRow(recipeId, trackId = 2L, completedAt = 5000L))   // newer

        val result = dao.getDoneTrackIdsForRecipe(recipeId, limit = 99)

        assertEquals(listOf(2L, 1L), result)
    }

    @Test fun `filters status to DONE only`() = runTest {
        dao.insertIfNew(doneRow(recipeId, trackId = 1L, completedAt = 1000L))
        dao.insertIfNew(pendingRow(recipeId, trackId = 2L))   // PENDING — must NOT appear

        val result = dao.getDoneTrackIdsForRecipe(recipeId, limit = 99)

        assertEquals(listOf(1L), result)
    }

    @Test fun `excludes rows with null track_id even when status is DONE`() = runTest {
        // Transient state: a worker may set status=DONE just before linking the
        // canonical track_id. Such a row must NOT consume a cap slot.
        dao.insertIfNew(doneRow(recipeId, trackId = 1L, completedAt = 1000L))
        dao.insertIfNew(doneRow(recipeId, trackId = null, completedAt = 2000L))

        val result = dao.getDoneTrackIdsForRecipe(recipeId, limit = 99)

        assertEquals(listOf(1L), result)
    }

    @Test fun `limit zero returns empty list`() = runTest {
        dao.insertIfNew(doneRow(recipeId, trackId = 1L, completedAt = 1000L))

        val result = dao.getDoneTrackIdsForRecipe(recipeId, limit = 0)

        assertTrue("expected empty, got $result", result.isEmpty())
    }

    private fun doneRow(recipeId: Long, trackId: Long?, completedAt: Long?) = DiscoveryQueueEntity(
        recipeId = recipeId,
        artist = "Artist",
        title = "Title $trackId",
        seedArtist = "Seed",
        status = DiscoveryQueueEntity.STATUS_DONE,
        trackId = trackId,
        queuedAt = 0L,
        completedAt = completedAt,
        errorMessage = null,
    )

    private fun pendingRow(recipeId: Long, trackId: Long?) = DiscoveryQueueEntity(
        recipeId = recipeId,
        artist = "Artist",
        title = "Title $trackId",
        seedArtist = "Seed",
        status = DiscoveryQueueEntity.STATUS_PENDING,
        trackId = trackId,
        queuedAt = 0L,
        completedAt = null,
        errorMessage = null,
    )
}
