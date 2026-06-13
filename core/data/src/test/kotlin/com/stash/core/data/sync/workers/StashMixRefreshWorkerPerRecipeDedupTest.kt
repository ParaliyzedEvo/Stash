package com.stash.core.data.sync.workers

import android.content.Context
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.dao.DiscoveryQueueDao
import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.StashMixRecipeDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.dao.TrackSkipEventDao
import com.stash.core.data.db.entity.StashMixRecipeEntity
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmCredentials
import com.stash.core.data.lastfm.LastFmSessionPreference
import com.stash.core.data.mix.MixGenerator
import com.stash.core.data.mix.MixSeedGenerator
import com.stash.core.data.mix.TagPoolBuilder
import com.stash.core.data.prefs.DownloadNetworkPreference
import com.stash.core.data.sync.TrackMatcher
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MockK end-to-end test for [StashMixRefreshWorker]'s v0.9.20 single-
 * recipe dedup. When `KEY_RECIPE_ID` is set, the worker pre-populates
 * `excludeIds` from the OTHER builtin mixes' current playlist contents
 * so a manual long-press refresh doesn't produce overlap with the
 * other mixes.
 */
class StashMixRefreshWorkerPerRecipeDedupTest {

    private val appContext: Context = mockk(relaxed = true)
    private val recipeDao: StashMixRecipeDao = mockk(relaxed = true)
    private val playlistDao: PlaylistDao = mockk(relaxed = true)
    private val discoveryQueueDao: DiscoveryQueueDao = mockk(relaxed = true)
    private val listeningEventDao: ListeningEventDao = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk(relaxed = true)
    private val mixGenerator: MixGenerator = mockk()
    private val seedGenerator: MixSeedGenerator = mockk(relaxed = true)
    private val lastFmApiClient: LastFmApiClient = mockk(relaxed = true)
    private val lastFmCredentials: LastFmCredentials = mockk {
        coEvery { isConfigured } returns false
    }
    private val sessionPreference: LastFmSessionPreference = mockk {
        coEvery { session } returns flowOf(null)
    }
    private val blocklistGuard: BlocklistGuard = mockk {
        coEvery { isBlockedByTrackId(any()) } returns false
    }
    private val trackSkipEventDao: TrackSkipEventDao = mockk(relaxed = true)
    private val trackMatcher: TrackMatcher = mockk(relaxed = true)
    private val downloadNetworkPreference: DownloadNetworkPreference = mockk(relaxed = true)
    private val tagPoolBuilder = TagPoolBuilder(lastFmApiClient)

    private fun newWorker(recipeId: Long): StashMixRefreshWorker {
        val params: WorkerParameters = mockk(relaxed = true) {
            coEvery { inputData } returns workDataOf(
                StashMixRefreshWorker.KEY_RECIPE_ID to recipeId,
            )
        }
        return StashMixRefreshWorker(
            appContext, params,
            recipeDao, playlistDao, discoveryQueueDao, listeningEventDao,
            trackDao, mixGenerator, seedGenerator, lastFmApiClient,
            lastFmCredentials, sessionPreference, blocklistGuard,
            trackSkipEventDao, tagPoolBuilder, trackMatcher, downloadNetworkPreference,
        )
    }

    @Test fun `single-recipe path pre-populates excludeIds from other mixes' playlist contents`() = runTest {
        val targetRecipe = recipe(id = 1L, name = "Daily Discover", playlistId = 100L)
        val otherRecipe1 = recipe(id = 2L, name = "Deep Cuts", playlistId = 200L)
        val otherRecipe2 = recipe(id = 3L, name = "First Listen", playlistId = 300L)

        coEvery { recipeDao.getById(1L) } returns targetRecipe
        coEvery { recipeDao.getActive() } returns listOf(targetRecipe, otherRecipe1, otherRecipe2)
        coEvery {
            playlistDao.getTrackIdsForPlaylists(match { it.toSet() == setOf(200L, 300L) })
        } returns listOf(50L, 51L, 52L)
        coEvery { playlistDao.getById(any()) } returns null
        coEvery { playlistDao.insert(any()) } returns 999L

        val excludeCapture = slot<Set<Long>>()
        coEvery { mixGenerator.generate(targetRecipe, capture(excludeCapture)) } returns emptyList()
        coEvery {
            playlistDao.getStreamableOrDoneTrackIdsForRecipe(any())
        } returns emptyList()

        newWorker(recipeId = 1L).doWork()

        assertEquals(
            "single-recipe path must pre-populate excludeIds from other mixes",
            setOf(50L, 51L, 52L),
            excludeCapture.captured,
        )
    }

    @Test fun `single-recipe path skips lookup when no other mixes have playlist ids`() = runTest {
        val targetRecipe = recipe(id = 1L, name = "Daily Discover", playlistId = 100L)
        val otherRecipe = recipe(id = 2L, name = "Deep Cuts", playlistId = null)

        coEvery { recipeDao.getById(1L) } returns targetRecipe
        coEvery { recipeDao.getActive() } returns listOf(targetRecipe, otherRecipe)
        coEvery { playlistDao.getById(any()) } returns null
        coEvery { playlistDao.insert(any()) } returns 999L

        val excludeCapture = slot<Set<Long>>()
        coEvery { mixGenerator.generate(targetRecipe, capture(excludeCapture)) } returns emptyList()
        coEvery {
            playlistDao.getStreamableOrDoneTrackIdsForRecipe(any())
        } returns emptyList()

        newWorker(recipeId = 1L).doWork()

        assertTrue("expected empty excludeIds when no other mixes have playlists", excludeCapture.captured.isEmpty())
        coVerify(exactly = 0) { playlistDao.getTrackIdsForPlaylists(any()) }
    }

    private fun recipe(id: Long, name: String, playlistId: Long?) = StashMixRecipeEntity(
        id = id,
        name = name,
        discoveryRatio = 0.85f,
        targetLength = 40,
        playlistId = playlistId,
        isBuiltin = true,
        isActive = true,
    )
}
