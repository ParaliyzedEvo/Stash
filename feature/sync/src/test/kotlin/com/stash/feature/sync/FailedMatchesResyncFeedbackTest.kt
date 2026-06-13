package com.stash.feature.sync

import com.stash.core.data.db.dao.UnmatchedTrackView
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.preview.PreviewPlayer
import com.stash.data.download.DownloadExecutor
import com.stash.data.download.files.FileOrganizer
import com.stash.data.download.files.SwapCoordinator
import com.stash.data.download.matching.HybridSearchExecutor
import com.stash.data.download.prefs.QualityPreferencesManager
import com.stash.data.download.preview.PreviewUrlExtractor
import com.stash.data.download.ytdlp.YtDlpSearchResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #143 — "resync button does nothing". A resync that finds no candidates is
 * indistinguishable from a dead button unless it tells the user the pass
 * completed. These tests pin the completion feedback.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FailedMatchesResyncFeedbackTest {

    private val musicRepository: MusicRepository = mockk(relaxed = true)
    private val previewPlayer: PreviewPlayer = mockk(relaxed = true)
    private val previewUrlExtractor: PreviewUrlExtractor = mockk(relaxed = true)
    private val searchExecutor: HybridSearchExecutor = mockk(relaxed = true)
    private val downloadExecutor: DownloadExecutor = mockk(relaxed = true)
    private val fileOrganizer: FileOrganizer = mockk(relaxed = true)
    private val qualityPrefs: QualityPreferencesManager = mockk(relaxed = true)
    private val trackDao = mockk<com.stash.core.data.db.dao.TrackDao>(relaxed = true)
    private val downloadQueueDao = mockk<com.stash.core.data.db.dao.DownloadQueueDao>(relaxed = true)
    private val swapCoordinator: SwapCoordinator = mockk(relaxed = true)
    private val blocklistGuard = mockk<com.stash.core.data.blocklist.BlocklistGuard>(relaxed = true)

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun unmatched() = UnmatchedTrackView(
        id = 1L, trackId = 1L, title = "Title", artist = "Artist",
        albumArtUrl = null, createdAt = 0L, rejectedVideoId = null,
        searchQuery = "Artist - Title",
    )

    private fun makeVm(
        tracks: List<UnmatchedTrackView> = listOf(unmatched()),
    ): FailedMatchesViewModel {
        every { musicRepository.getUnmatchedTracks() } returns flowOf(tracks)
        every { musicRepository.getFlaggedTracks() } returns flowOf(emptyList())
        return FailedMatchesViewModel(
            musicRepository, previewPlayer, previewUrlExtractor, searchExecutor,
            downloadExecutor, fileOrganizer, qualityPrefs, trackDao,
            downloadQueueDao, swapCoordinator, blocklistGuard,
            mockk(relaxed = true) { every { acceptDownloadOrDelete(any()) } returns true },
        )
    }

    @Test fun `resync with zero results tells the user no matches were found`() = runTest {
        coEvery { searchExecutor.search(any(), any()) } returns emptyList()
        val vm = makeVm()
        val messages = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.userMessages.collect { messages.add(it) } }

        vm.resync()
        advanceUntilIdle()

        assertTrue(
            "expected a 'no matches' completion message, got $messages",
            messages.any { it.contains("no", ignoreCase = true) && it.contains("match", ignoreCase = true) },
        )
    }

    @Test fun `resync that finds a candidate reports how many replacements it found`() = runTest {
        coEvery { searchExecutor.search(any(), any()) } returns
            listOf(YtDlpSearchResult(id = "vid1", title = "Title"))
        val vm = makeVm()
        val messages = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.userMessages.collect { messages.add(it) } }

        vm.resync()
        advanceUntilIdle()

        assertTrue(
            "expected a 'found N' completion message, got $messages",
            messages.any { it.contains("1") && it.contains("replacement", ignoreCase = true) },
        )
    }

    @Test fun `resync falls through to full-YouTube search when YT Music has no usable match`() = runTest {
        // #19/#143: some tracks exist on YouTube but not YouTube Music. When the
        // InnerTube (YT Music) pass yields nothing usable, resync must broaden to
        // a yt-dlp full-YouTube search instead of giving up.
        coEvery { searchExecutor.search(any(), any()) } returns emptyList()
        coEvery { searchExecutor.searchYtDlpDirect(any(), any()) } returns
            listOf(YtDlpSearchResult(id = "ytOnly", title = "Title"))
        val vm = makeVm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        vm.resync()
        advanceUntilIdle()

        assertEquals(
            "expected the full-YouTube result to become the candidate",
            "ytOnly",
            vm.uiState.value.resyncCandidates[1L]?.videoId,
        )
    }

    @Test fun `resync derives query from artist and title when the stored search query is blank`() = runTest {
        // Real bug: auto-requeued tracks (TrackDownloadWorker) get an empty
        // download_queue.search_query. Resync trusted that blank string and
        // searched for "" -> InnerTube empty -> yt-dlp throws "must not be
        // blank" -> "can't find a match", even though artist+title are known.
        val blankQueryTrack = UnmatchedTrackView(
            id = 1L, trackId = 1L, title = "In the Aeroplane Over the Sea",
            artist = "Neutral Milk Hotel", albumArtUrl = null, createdAt = 0L,
            rejectedVideoId = null, searchQuery = "",
        )
        val queries = mutableListOf<String>()
        coEvery { searchExecutor.search(capture(queries), any()) } returns
            listOf(YtDlpSearchResult(id = "vid1", title = "x"))
        val vm = makeVm(listOf(blankQueryTrack))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        vm.resync()
        advanceUntilIdle()

        assertTrue(
            "resync must search by artist+title, not the blank stored query; queries=$queries",
            queries.any { it == "Neutral Milk Hotel - In the Aeroplane Over the Sea" },
        )
    }
}
