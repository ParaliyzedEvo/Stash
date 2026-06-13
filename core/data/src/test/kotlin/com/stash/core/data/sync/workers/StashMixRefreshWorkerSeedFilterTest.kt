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
import com.stash.core.data.mix.MixSeedStrategy
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
import org.junit.Test

class StashMixRefreshWorkerSeedFilterTest {

    private val appContext: Context = mockk(relaxed = true)
    private val recipeDao: StashMixRecipeDao = mockk(relaxed = true)
    private val playlistDao: PlaylistDao = mockk(relaxed = true)
    private val discoveryQueueDao: DiscoveryQueueDao = mockk(relaxed = true)
    private val listeningEventDao: ListeningEventDao = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk(relaxed = true)
    private val mixGenerator: MixGenerator = mockk(relaxed = true)
    private val seedGenerator: MixSeedGenerator = mockk()
    private val lastFmApiClient: LastFmApiClient = mockk(relaxed = true)
    private val lastFmCredentials: LastFmCredentials = mockk {
        coEvery { isConfigured } returns true
    }
    private val sessionPreference: LastFmSessionPreference = mockk {
        coEvery { session } returns flowOf(null)
    }
    private val blocklistGuard: BlocklistGuard = mockk(relaxed = true)
    private val trackSkipEventDao: TrackSkipEventDao = mockk(relaxed = true)
    private val trackMatcher: TrackMatcher = mockk()
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

    @Test fun `filters library-canonical-keys + skip-banned-keys before queueing`() = runTest {
        val recipe = recipe(id = 1L, name = "Daily Discover", seedStrategy = "ARTIST_SIMILAR")
        coEvery { recipeDao.getById(1L) } returns recipe
        coEvery { recipeDao.getActive() } returns listOf(recipe)
        // Deliberate simplification — real TrackMatcher splits on ,;&/ then sorts;
        // the test exercises FILTER LOGIC, not canonicalization.
        coEvery { trackMatcher.canonicalArtist(any()) } answers { firstArg<String>().lowercase() }
        coEvery { trackMatcher.canonicalTitle(any()) } answers { firstArg<String>().lowercase() }

        val candidates = listOf(
            candidate("In Library 1", "Track A"),
            candidate("In Library 2", "Track B"),
            candidate("Banned Artist", "Banned Song"),
            candidate("New Artist 1", "New Track 1"),
            candidate("New Artist 2", "New Track 2"),
        )
        coEvery {
            seedGenerator.generate(MixSeedStrategy.ARTIST_SIMILAR, any(), any(), any(), any())
        } returns candidates

        coEvery { trackDao.getLibraryCanonicalKeys() } returns listOf(
            "in library 1|track a",
            "in library 2|track b",
        )
        coEvery {
            trackSkipEventDao.getEarlySkipBannedCanonicalKeys(any(), any(), any())
        } returns listOf("banned artist|banned song")

        coEvery {
            seedGenerator.generate(MixSeedStrategy.TAG_GRAPH, any(), any(), any(), any())
        } returns emptyList()

        val queuedSlot = slot<List<MixGenerator.DiscoveryCandidate>>()
        coEvery { mixGenerator.queueDiscoveryCandidates(recipe, capture(queuedSlot)) } returns Unit

        newWorker(recipeId = 1L).doWork()

        val keys = queuedSlot.captured.map { "${it.artist.lowercase()}|${it.title.lowercase()}" }.toSet()
        assertEquals(setOf("new artist 1|new track 1", "new artist 2|new track 2"), keys)
    }

    @Test fun `falls back to TAG_GRAPH when filtered pool is below floor`() = runTest {
        val recipe = recipe(id = 1L, name = "Daily Discover", seedStrategy = "ARTIST_SIMILAR")
        coEvery { recipeDao.getById(1L) } returns recipe
        coEvery { recipeDao.getActive() } returns listOf(recipe)
        coEvery { trackMatcher.canonicalArtist(any()) } answers { firstArg<String>().lowercase() }
        coEvery { trackMatcher.canonicalTitle(any()) } answers { firstArg<String>().lowercase() }

        val primary = listOf(
            candidate("LibA", "TA"),
            candidate("LibB", "TB"),
            candidate("LibC", "TC"),
        )
        coEvery { seedGenerator.generate(MixSeedStrategy.ARTIST_SIMILAR, any(), any(), any(), any()) } returns primary
        coEvery { trackDao.getLibraryCanonicalKeys() } returns listOf("liba|ta", "libb|tb", "libc|tc")
        coEvery { trackSkipEventDao.getEarlySkipBannedCanonicalKeys(any(), any(), any()) } returns emptyList()

        val fallback = (1..25).map { candidate("Fallback Artist $it", "Fallback Title $it") }
        coEvery { seedGenerator.generate(MixSeedStrategy.TAG_GRAPH, any(), any(), any(), any()) } returns fallback

        val queuedSlot = slot<List<MixGenerator.DiscoveryCandidate>>()
        coEvery { mixGenerator.queueDiscoveryCandidates(recipe, capture(queuedSlot)) } returns Unit

        newWorker(recipeId = 1L).doWork()

        assertEquals(25, queuedSlot.captured.size)
        coVerify { seedGenerator.generate(MixSeedStrategy.TAG_GRAPH, any(), any(), any(), any()) }
    }

    @Test fun `does NOT queue when all candidates filter out and fallback also empty`() = runTest {
        val recipe = recipe(id = 1L, name = "Daily Discover", seedStrategy = "ARTIST_SIMILAR")
        coEvery { recipeDao.getById(1L) } returns recipe
        coEvery { recipeDao.getActive() } returns listOf(recipe)
        coEvery { trackMatcher.canonicalArtist(any()) } answers { firstArg<String>().lowercase() }
        coEvery { trackMatcher.canonicalTitle(any()) } answers { firstArg<String>().lowercase() }

        coEvery {
            seedGenerator.generate(MixSeedStrategy.ARTIST_SIMILAR, any(), any(), any(), any())
        } returns listOf(candidate("Only", "Match"))
        coEvery { trackDao.getLibraryCanonicalKeys() } returns listOf("only|match")
        coEvery { trackSkipEventDao.getEarlySkipBannedCanonicalKeys(any(), any(), any()) } returns emptyList()
        coEvery { seedGenerator.generate(MixSeedStrategy.TAG_GRAPH, any(), any(), any(), any()) } returns emptyList()

        newWorker(recipeId = 1L).doWork()

        coVerify(exactly = 0) { mixGenerator.queueDiscoveryCandidates(any(), any()) }
    }

    private fun recipe(id: Long, name: String, seedStrategy: String) = StashMixRecipeEntity(
        id = id, name = name,
        discoveryRatio = 0.85f, targetLength = 40,
        seedStrategy = seedStrategy,
        isBuiltin = true, isActive = true,
    )

    private fun candidate(artist: String, title: String) = MixGenerator.DiscoveryCandidate(
        artist = artist, title = title, seedArtist = "test",
    )
}
