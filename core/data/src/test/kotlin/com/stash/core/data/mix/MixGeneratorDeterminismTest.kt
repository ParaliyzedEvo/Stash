package com.stash.core.data.mix

import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.dao.DiscoveryQueueDao
import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.dao.TrackSkipEventDao
import com.stash.core.data.db.dao.TrackTagDao
import com.stash.core.data.db.entity.StashMixRecipeEntity
import com.stash.core.data.db.entity.TrackEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Regression test for the multi-genre Stash Mix "repopulate with different
 * tracks" bug. Root cause: [MixGenerator.generate]'s score jitter used the
 * global (unseeded) `Random`, so the library slice was re-ordered on every
 * refresh. Recipes with a fractional library slot (any `discoveryRatio < 1.0`
 * — which is every MULTI-genre custom mix) therefore produced a different
 * `finalOrderedIds` each refresh, which defeated
 * `StashMixRefreshWorker.materializeMix`'s idempotency short-circuit and made
 * the runaway refresh loop visibly clear+reinsert the mix on every pass.
 *
 * The fix seeds the jitter deterministically per recipe + day, so two
 * refreshes of the same recipe on the same day produce byte-identical
 * orderings and the short-circuit fires.
 */
class MixGeneratorDeterminismTest {

    private val trackDao: TrackDao = mockk()
    private val trackTagDao: TrackTagDao = mockk(relaxed = true)
    private val listeningEventDao: ListeningEventDao = mockk(relaxed = true)
    private val discoveryQueueDao: DiscoveryQueueDao = mockk(relaxed = true)
    private val blocklistGuard: BlocklistGuard = mockk(relaxed = true)
    private val trackSkipEventDao: TrackSkipEventDao = mockk(relaxed = true)
    private lateinit var generator: MixGenerator

    @Before fun setUp() {
        generator = MixGenerator(
            trackDao,
            trackTagDao,
            listeningEventDao,
            discoveryQueueDao,
            blocklistGuard,
            trackSkipEventDao,
        )
        coEvery { listeningEventDao.getPlayCountsSinceWithLatest(any()) } returns emptyList()
        coEvery { listeningEventDao.getTrackIdsPlayedSince(any()) } returns emptyList()
        coEvery { trackTagDao.getByTrack(any()) } returns emptyList()
    }

    @Test fun `generate yields identical ordering across calls for the same recipe and day`() = runTest {
        // 50 candidates, all equal affinity, so the only thing that decides
        // the ordering is the score jitter. With an unseeded Random the two
        // calls disagree (the bug); with a per-recipe+day seed they match.
        coEvery { trackDao.getAllDownloaded() } returns (1L..50L).map { stubTrack(it) }
        val recipe = stubRecipe(discoveryRatio = 0.5f, targetLength = 50)

        val first = generator.generate(recipe).map { it.id }
        val second = generator.generate(recipe).map { it.id }

        assertEquals(
            "same recipe on the same day must produce an identical ordering so the " +
                "materializeMix idempotency short-circuit can fire (no churn)",
            first,
            second,
        )
    }

    private fun stubRecipe(discoveryRatio: Float, targetLength: Int) = StashMixRecipeEntity(
        id = 7L,
        name = "Test",
        discoveryRatio = discoveryRatio,
        targetLength = targetLength,
        freshnessWindowDays = 0,
    )

    private fun stubTrack(id: Long) = TrackEntity(
        id = id,
        title = "Track $id",
        artist = "Artist $id",
        canonicalTitle = "track $id",
        canonicalArtist = "artist $id",
        isDownloaded = true,
    )
}
