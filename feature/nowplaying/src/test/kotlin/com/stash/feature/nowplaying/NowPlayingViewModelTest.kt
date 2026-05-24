package com.stash.feature.nowplaying

import android.content.Context
import app.cash.turbine.test
import com.stash.core.data.lossless.LosslessUpgrader
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.social.stash.StashLikedPlaylistRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.model.PlayerState
import com.stash.core.model.Track
import com.stash.core.model.UpgradeResult
import com.stash.data.lyrics.LyricsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * v0.9.18 Task 3 — first tests in :feature:nowplaying.
 *
 * Two test classes, two concerns:
 *  - [NowPlayingViewModelSnackbarCopyTest] pins the pure
 *    [snackbarCopyFor] helper, which maps every UpgradeResult variant
 *    to its user-facing snackbar string.
 *  - [NowPlayingViewModelFindInFlacTest] exercises the
 *    [NowPlayingViewModel.findInFlacForCurrentTrack] action end-to-end,
 *    asserting the "Looking for FLAC…" → result-snackbar emission
 *    sequence and the no-op-when-already-FLAC guard.
 *
 * Snackbar emissions go through [NowPlayingViewModel.userMessages],
 * a `MutableSharedFlow<String>` with `extraBufferCapacity = 1` and
 * `BufferOverflow.DROP_OLDEST`. Two `tryEmit` calls in a row therefore
 * race the collector — without an active subscriber draining the
 * buffer between emits, the first message is dropped. Turbine's
 * `awaitItem()` parks on the channel between emits and gives us the
 * full transcript.
 */
class NowPlayingViewModelSnackbarCopyTest {

    @Test fun `Upgraded maps to "Upgraded to FLAC"`() {
        assertEquals("Upgraded to FLAC", snackbarCopyFor(UpgradeResult.Upgraded))
    }

    @Test fun `NoMatch maps to "No lossless match found"`() {
        assertEquals("No lossless match found", snackbarCopyFor(UpgradeResult.NoMatch))
    }

    @Test fun `Error maps to "Couldn't check lossless sources"`() {
        assertEquals("Couldn't check lossless sources", snackbarCopyFor(UpgradeResult.Error))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingViewModelFindInFlacTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val playerStateFlow = MutableStateFlow(PlayerState())
    private val positionFlow = MutableStateFlow(0L)

    private val playerRepository: PlayerRepository = mockk(relaxed = true) {
        every { playerState } returns playerStateFlow
        every { currentPosition } returns positionFlow
    }
    private val musicRepository: MusicRepository = mockk(relaxed = true) {
        every { observeTrackById(any()) } returns flowOf(null)
        every { getUserCreatedPlaylists() } returns flowOf(emptyList())
    }
    private val stashLikedRepository: StashLikedPlaylistRepository = mockk(relaxed = true)
    private val upgrader: LosslessUpgrader = mockk()
    // v0.9.36 Task 12 — Now Playing now depends on the lyrics repository
    // (for the lyrics sheet) and an application Context (used as a
    // sink for WorkManager.getInstance on the priority-fetch path).
    // These tests don't exercise either surface, so a relaxed mock for
    // both is enough to satisfy the constructor.
    private val lyricsRepository: LyricsRepository = mockk(relaxed = true)
    private val appContext: Context = mockk(relaxed = true)

    private fun newViewModel(): NowPlayingViewModel = NowPlayingViewModel(
        playerRepository = playerRepository,
        musicRepository = musicRepository,
        stashLikedRepository = stashLikedRepository,
        losslessUpgrader = upgrader,
        lyricsRepository = lyricsRepository,
        appContext = appContext,
    )

    private val nonFlacTrack = Track(
        id = 42L,
        title = "song",
        artist = "artist",
        fileFormat = "opus",
    )
    private val flacTrack = nonFlacTrack.copy(id = 99L, fileFormat = "flac")

    /**
     * The action's body emits "Looking for FLAC…", calls `upgradeToLossless`,
     * then emits the result. Because [NowPlayingViewModel.userMessages] is a
     * `MutableSharedFlow` with `extraBufferCapacity = 1` and `DROP_OLDEST`,
     * these two `tryEmit` calls must be separated by a real suspension —
     * otherwise the second one races the collector and overwrites the first.
     *
     * In production this naturally happens: `LosslessUpgrader.upgradeToLossless`
     * does network I/O and DB work, so the coroutine yields well before the
     * second emit. In the JVM test we stub the call with `coAnswers { yield() }`
     * to reproduce that yield without modeling the full pipeline.
     */
    private fun stubUpgrade(result: UpgradeResult) {
        coEvery { upgrader.upgradeToLossless(any()) } coAnswers {
            yield()
            result
        }
    }

    @Test fun `findInFlacForCurrentTrack happy path emits looking then upgraded`() = runTest(dispatcher) {
        stubUpgrade(UpgradeResult.Upgraded)
        playerStateFlow.value = playerStateFlow.value.copy(currentTrack = nonFlacTrack)
        val vm = newViewModel()
        advanceUntilIdle()

        vm.userMessages.test {
            vm.findInFlacForCurrentTrack()
            assertEquals("Looking for FLAC\u2026", awaitItem())
            assertEquals("Upgraded to FLAC", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `findInFlacForCurrentTrack no-match path`() = runTest(dispatcher) {
        stubUpgrade(UpgradeResult.NoMatch)
        playerStateFlow.value = playerStateFlow.value.copy(currentTrack = nonFlacTrack)
        val vm = newViewModel()
        advanceUntilIdle()

        vm.userMessages.test {
            vm.findInFlacForCurrentTrack()
            assertEquals("Looking for FLAC\u2026", awaitItem())
            assertEquals("No lossless match found", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `findInFlacForCurrentTrack error path`() = runTest(dispatcher) {
        stubUpgrade(UpgradeResult.Error)
        playerStateFlow.value = playerStateFlow.value.copy(currentTrack = nonFlacTrack)
        val vm = newViewModel()
        advanceUntilIdle()

        vm.userMessages.test {
            vm.findInFlacForCurrentTrack()
            assertEquals("Looking for FLAC\u2026", awaitItem())
            assertEquals("Couldn't check lossless sources", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `findInFlacForCurrentTrack no-op when current track is FLAC`() = runTest(dispatcher) {
        playerStateFlow.value = playerStateFlow.value.copy(currentTrack = flacTrack)
        val vm = newViewModel()
        advanceUntilIdle()

        vm.userMessages.test {
            vm.findInFlacForCurrentTrack()
            advanceUntilIdle()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 0) { upgrader.upgradeToLossless(any()) }
    }
}
