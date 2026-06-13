package com.stash.core.data.sync.workers

import android.content.Context
import androidx.work.WorkerParameters
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.dao.DiscoveryQueueDao
import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.StashMixRecipeDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.dao.TrackSkipEventDao
import com.stash.core.data.db.entity.StashMixRecipeEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmCredentials
import com.stash.core.data.lastfm.LastFmSessionPreference
import com.stash.core.data.mix.MixGenerator
import com.stash.core.data.mix.MixSeedGenerator
import com.stash.core.data.mix.TagPoolBuilder
import com.stash.core.data.prefs.DownloadNetworkPreference
import com.stash.core.data.sync.TrackMatcher
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end MockK test for [StashMixRefreshWorker]'s cross-mix dedup
 * orchestration. Verifies that when multiple builtin recipes run in
 * priority order, no track id appears in two playlists.
 */
class StashMixRefreshWorkerDedupTest {

    private val appContext: Context = mockk(relaxed = true)
    private val workerParams: WorkerParameters = mockk(relaxed = true)
    private val recipeDao: StashMixRecipeDao = mockk(relaxed = true)
    private val playlistDao: PlaylistDao = mockk(relaxed = true)
    private val discoveryQueueDao: DiscoveryQueueDao = mockk(relaxed = true)
    private val listeningEventDao: ListeningEventDao = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk(relaxed = true)
    private val mixGenerator: MixGenerator = mockk()
    private val seedGenerator: MixSeedGenerator = mockk(relaxed = true)
    private val lastFmApiClient: LastFmApiClient = mockk(relaxed = true)
    private val lastFmCredentials: LastFmCredentials = mockk {
        coEvery { isConfigured } returns false  // skip persona fetch + discovery seeding
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

    private fun newWorker() = StashMixRefreshWorker(
        appContext, workerParams,
        recipeDao, playlistDao, discoveryQueueDao, listeningEventDao,
        trackDao, mixGenerator, seedGenerator, lastFmApiClient,
        lastFmCredentials, sessionPreference, blocklistGuard,
        trackSkipEventDao, tagPoolBuilder, trackMatcher, downloadNetworkPreference,
    )

    @Test fun `excludeIds accumulates across recipes — no track appears in two playlists`() = runTest {
        // Three active recipes in priority order: First Listen, Deep Cuts, Daily Discover.
        val firstListen = recipe(id = 1L, name = "First Listen", ratio = 1.0f)
        val deepCuts = recipe(id = 2L, name = "Deep Cuts", ratio = 0.85f)
        val dailyDiscover = recipe(id = 3L, name = "Daily Discover", ratio = 0.85f)
        coEvery { recipeDao.getActive() } returns listOf(dailyDiscover, deepCuts, firstListen) // unsorted

        // Capture excludeIds passed to each generate() call.
        val excludeCaptureFirstListen = slot<Set<Long>>()
        val excludeCaptureDeepCuts = slot<Set<Long>>()
        val excludeCaptureDailyDiscover = slot<Set<Long>>()
        coEvery { mixGenerator.generate(firstListen, capture(excludeCaptureFirstListen)) } returns emptyList()
        coEvery { mixGenerator.generate(deepCuts, capture(excludeCaptureDeepCuts)) } returns listOf(track(10L), track(11L))
        coEvery { mixGenerator.generate(dailyDiscover, capture(excludeCaptureDailyDiscover)) } returns listOf(track(20L), track(21L))

        // First Listen brings 5 discovery survivors; Deep Cuts brings 6 (10, 11 are also library); Daily Discover 7 (1 is in First Listen).
        // v0.9.37: materializeMix now pulls survivors via PlaylistDao.getStreamableOrDoneTrackIdsForRecipe
        // (downloaded OR streamable), so the stub moves from DiscoveryQueueDao to PlaylistDao.
        coEvery { playlistDao.getStreamableOrDoneTrackIdsForRecipe(firstListen.id) } returns listOf(1L, 2L, 3L, 4L, 5L)
        coEvery { playlistDao.getStreamableOrDoneTrackIdsForRecipe(deepCuts.id) } returns listOf(6L, 7L, 8L, 9L, 10L, 11L)
        coEvery { playlistDao.getStreamableOrDoneTrackIdsForRecipe(dailyDiscover.id) } returns listOf(1L, 12L, 13L, 14L, 15L, 16L, 17L)

        coEvery { playlistDao.getById(any()) } returns null
        coEvery { playlistDao.insert(any()) } returnsMany listOf(100L, 200L, 300L)

        newWorker().doWork()

        // First Listen runs first (priority 1) — exclude set is empty.
        assertTrue("first listen excludeIds should start empty", excludeCaptureFirstListen.captured.isEmpty())

        // Deep Cuts runs second — exclude set has First Listen's 5 discovery ids.
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L), excludeCaptureDeepCuts.captured)

        // Daily Discover runs third — exclude set has First Listen's {1..5} + Deep Cuts's library {10, 11} + Deep Cuts's NEW survivors {6, 7, 8, 9} (10/11 are in librarySet so filtered there, not by excludeIds).
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L), excludeCaptureDailyDiscover.captured)
    }

    private fun recipe(id: Long, name: String, ratio: Float) = StashMixRecipeEntity(
        id = id, name = name,
        discoveryRatio = ratio, targetLength = 40,
        isBuiltin = true, isActive = true,
    )

    private fun track(id: Long) = TrackEntity(
        id = id, title = "Track $id", artist = "Artist $id",
        canonicalTitle = "track $id", canonicalArtist = "artist $id",
        isDownloaded = true,
    )
}
