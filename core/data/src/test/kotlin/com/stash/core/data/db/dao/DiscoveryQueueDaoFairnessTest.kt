package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.DiscoveryQueueEntity
import com.stash.core.data.db.entity.StashMixRecipeEntity
import com.stash.core.data.db.entity.TrackEntity
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
 * Locks in the v0.9.38 fairness + cap-counting fixes for
 * [DiscoveryQueueDao]. Two changes are under test here:
 *
 *  1. **Cap counts stream-only DONE rows.** The v0.9.37 stream-only seam
 *     stopped writing `is_downloaded = 1` for discovery survivors, which
 *     left [DiscoveryQueueDao.countRecentCompletedForRecipe] +
 *     [DiscoveryQueueDao.findRecipesAtWeeklyCap] returning 0 for every
 *     recipe. The per-recipe weekly cap stopped engaging entirely.
 *
 *  2. **Round-robin fetch helpers.** [DiscoveryQueueDao.getRecipesWithPending]
 *     + [DiscoveryQueueDao.getPendingForRecipe] let the worker schedule
 *     a fair quota per recipe instead of letting one recipe's FIFO
 *     backlog monopolise the drain window.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DiscoveryQueueDaoFairnessTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: DiscoveryQueueDao
    private var recipeA: Long = 0L
    private var recipeB: Long = 0L
    private var recipeC: Long = 0L

    @Before fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StashDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        dao = db.discoveryQueueDao()
        recipeA = db.stashMixRecipeDao().insert(
            StashMixRecipeEntity(name = "Recipe A", isBuiltin = false)
        )
        recipeB = db.stashMixRecipeDao().insert(
            StashMixRecipeEntity(name = "Recipe B", isBuiltin = false)
        )
        recipeC = db.stashMixRecipeDao().insert(
            StashMixRecipeEntity(name = "Recipe C", isBuiltin = false)
        )
    }

    @After fun tearDown() { db.close() }

    // ── cap-counts-streamonly (the v0.9.37 regression) ──────────────────

    @Test fun `countRecent counts stream-only DONE rows post-v0937 seam`() = runTest {
        // Three DONE survivors for recipeA — all stream-only stubs, as
        // StashDiscoveryWorker creates them in v0.9.37+.
        for (i in 1..3) {
            db.trackDao().insert(
                streamOnlyStub(id = i.toLong())
            )
            dao.insertIfNew(doneRow(recipeA, trackId = i.toLong(), completedAt = 1_000L * i))
        }

        val count = dao.countRecentCompletedForRecipe(recipeA, sinceMillis = 0L)

        assertEquals(
            "stream-only DONE rows must count toward the cap, otherwise " +
                "one recipe monopolises the drain queue forever",
            3, count,
        )
    }

    @Test fun `countRecent still counts downloaded DONE rows`() = runTest {
        // Regression guard: pre-v0.9.37 downloaded rows must still count.
        db.trackDao().insert(downloadedTrack(id = 1L))
        dao.insertIfNew(doneRow(recipeA, trackId = 1L, completedAt = 1_000L))

        assertEquals(1, dao.countRecentCompletedForRecipe(recipeA, sinceMillis = 0L))
    }

    @Test fun `countRecent excludes DONE rows older than sinceMillis`() = runTest {
        db.trackDao().insert(streamOnlyStub(id = 1L))
        db.trackDao().insert(streamOnlyStub(id = 2L))
        dao.insertIfNew(doneRow(recipeA, trackId = 1L, completedAt = 500L))   // before window
        dao.insertIfNew(doneRow(recipeA, trackId = 2L, completedAt = 2_000L)) // inside window

        assertEquals(1, dao.countRecentCompletedForRecipe(recipeA, sinceMillis = 1_000L))
    }

    @Test fun `findRecipesAtCap flags recipes whose stream-only DONE rows hit the cap`() = runTest {
        // recipeA: 3 stream-only DONE — at-cap when cap = 3.
        // recipeB: 1 stream-only DONE — not at cap.
        for (i in 1..3) {
            db.trackDao().insert(streamOnlyStub(id = i.toLong()))
            dao.insertIfNew(doneRow(recipeA, trackId = i.toLong(), completedAt = 1_000L))
        }
        db.trackDao().insert(streamOnlyStub(id = 10L))
        dao.insertIfNew(doneRow(recipeB, trackId = 10L, completedAt = 1_000L))

        val capped = dao.findRecipesAtWeeklyCap(sinceMillis = 0L, cap = 3)

        assertEquals(listOf(recipeA), capped)
    }

    // ── round-robin scheduling helpers ──────────────────────────────────

    @Test fun `getRecipesWithPending returns distinct ids that have PENDING`() = runTest {
        // recipeA: 2 pending, 1 done; recipeB: 1 pending; recipeC: 0 pending (only DONE).
        db.trackDao().insert(streamOnlyStub(id = 1L))
        db.trackDao().insert(streamOnlyStub(id = 2L))
        dao.insertIfNew(pendingRow(recipeA, trackId = null, queuedAt = 100L))
        dao.insertIfNew(pendingRow(recipeA, trackId = null, queuedAt = 200L))
        dao.insertIfNew(doneRow(recipeA, trackId = 1L, completedAt = 50L))
        dao.insertIfNew(pendingRow(recipeB, trackId = null, queuedAt = 100L))
        dao.insertIfNew(doneRow(recipeC, trackId = 2L, completedAt = 50L))

        // Empty `IN ()` is illegal SQL → caller must pass a sentinel.
        val recipes = dao.getRecipesWithPending(listOf(-1L))

        assertEquals(setOf(recipeA, recipeB), recipes.toSet())
    }

    @Test fun `getRecipesWithPending honours cappedRecipeIds`() = runTest {
        dao.insertIfNew(pendingRow(recipeA, trackId = null, queuedAt = 100L))
        dao.insertIfNew(pendingRow(recipeB, trackId = null, queuedAt = 100L))

        // recipeA capped → excluded from the active set.
        val recipes = dao.getRecipesWithPending(listOf(recipeA))

        assertEquals(listOf(recipeB), recipes)
    }

    @Test fun `getPendingForRecipe returns up to limit oldest-first`() = runTest {
        // 5 PENDING for recipeA across spread queued_at timestamps.
        for (i in 1..5) {
            dao.insertIfNew(pendingRow(recipeA, trackId = null, queuedAt = i * 100L))
        }
        // recipeB rows must not leak into recipeA's result.
        dao.insertIfNew(pendingRow(recipeB, trackId = null, queuedAt = 1L))

        val pending = dao.getPendingForRecipe(recipeA, limit = 3)

        assertEquals(3, pending.size)
        // FIFO within a recipe: oldest queued_at first.
        assertTrue(pending[0].queuedAt < pending[1].queuedAt)
        assertTrue(pending[1].queuedAt < pending[2].queuedAt)
        assertEquals(recipeA, pending[0].recipeId)
    }

    @Test fun `getPendingForRecipe ignores non-PENDING rows`() = runTest {
        db.trackDao().insert(streamOnlyStub(id = 1L))
        dao.insertIfNew(doneRow(recipeA, trackId = 1L, completedAt = 1L))   // not PENDING
        dao.insertIfNew(pendingRow(recipeA, trackId = null, queuedAt = 2L))

        val pending = dao.getPendingForRecipe(recipeA, limit = 99)

        assertEquals(1, pending.size)
        assertEquals(DiscoveryQueueEntity.STATUS_PENDING, pending.single().status)
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun streamOnlyStub(id: Long) = TrackEntity(
        id = id,
        title = "Track $id",
        artist = "Artist $id",
        canonicalTitle = "track $id",
        canonicalArtist = "artist $id",
        isDownloaded = false,
        isStreamable = true,
    )

    private fun downloadedTrack(id: Long) = TrackEntity(
        id = id,
        title = "Track $id",
        artist = "Artist $id",
        canonicalTitle = "track $id",
        canonicalArtist = "artist $id",
        isDownloaded = true,
        isStreamable = false,
    )

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

    private fun pendingRow(recipeId: Long, trackId: Long?, queuedAt: Long) = DiscoveryQueueEntity(
        recipeId = recipeId,
        artist = "Artist",
        title = "Title $trackId",
        seedArtist = "Seed",
        status = DiscoveryQueueEntity.STATUS_PENDING,
        trackId = trackId,
        queuedAt = queuedAt,
        completedAt = null,
        errorMessage = null,
    )
}
