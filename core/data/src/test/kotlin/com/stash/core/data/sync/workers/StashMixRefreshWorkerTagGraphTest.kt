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
import com.stash.core.data.lastfm.LastFmTopTrack
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
import org.junit.Assert.assertTrue
import org.junit.Test

class StashMixRefreshWorkerTagGraphTest {

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

    @Test fun `TAG_GRAPH recipe seeds from its own genre tags via TagPoolBuilder`() = runTest {
        val recipe = StashMixRecipeEntity(
            id = 7, name = "Late Night Jazz", includeTagsCsv = "jazz", moodKeysCsv = "",
            discoveryRatio = 0.85f, targetLength = 40, seedStrategy = "TAG_GRAPH",
            isBuiltin = false, isActive = true,
        )
        coEvery { recipeDao.getById(7L) } returns recipe
        coEvery { recipeDao.getActive() } returns listOf(recipe)
        // Non-relaxed trackMatcher: stub the canonicalization the tag-seeded
        // pre-filter calls (mirrors StashMixRefreshWorkerSeedFilterTest). Without
        // this, canonicalKey() throws and the runCatching in doWork swallows it,
        // so queueDiscoveryCandidates is never reached.
        coEvery { trackMatcher.canonicalArtist(any()) } answers { firstArg<String>().lowercase() }
        coEvery { trackMatcher.canonicalTitle(any()) } answers { firstArg<String>().lowercase() }
        coEvery { lastFmApiClient.getTagTopTracks("jazz", any()) } returns Result.success(
            (1..30).map { LastFmTopTrack("Artist$it", "Jazz$it", 100 - it) },
        )
        coEvery { lastFmApiClient.getTagTopTracks(neq("jazz"), any()) } returns Result.success(emptyList())
        coEvery { trackDao.getLibraryCanonicalKeys() } returns emptyList()
        coEvery { trackSkipEventDao.getEarlySkipBannedCanonicalKeys(any(), any(), any()) } returns emptyList()

        val queued = slot<List<MixGenerator.DiscoveryCandidate>>()
        coEvery { mixGenerator.queueDiscoveryCandidates(recipe, capture(queued)) } returns Unit

        newWorker(recipeId = 7L).doWork()

        assertTrue(queued.isCaptured)
        assertTrue(queued.captured.isNotEmpty())
        assertTrue(queued.captured.all { it.seedArtist.startsWith("tag:jazz") })
        coVerify(exactly = 0) { seedGenerator.generate(any(), any(), any(), any(), any()) }
    }
}
