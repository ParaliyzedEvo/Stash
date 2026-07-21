package com.stash.feature.library

import androidx.lifecycle.SavedStateHandle
import com.stash.core.data.db.dao.DiscoveryQueueDao
import com.stash.core.data.db.dao.StashMixRecipeDao
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.model.PlayerState
import com.stash.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Pins the batch-action contract added in Task 5 of the multi-select redesign:
 * each batch method wraps the existing single-track path — Queue uses the batch
 * [PlayerRepository.addToQueue] overload (single call), Play Next loops
 * [PlayerRepository.addNext], and download/remove/save/delete loop the
 * per-id [MusicRepository] calls.
 *
 * Mirrors the harness in [LikedSongsDetailViewModelTest]: StandardTestDispatcher
 * + Dispatchers.setMain/resetMain, runTest{}, mockito-kotlin, runCurrent().
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun playSelectedNext_loops_addNext_per_track() = runTest {
        val playerRepo = playerRepoMock()
        val vm = buildVm(playerRepository = playerRepo)
        val tracks = listOf(track(1L), track(2L), track(3L))

        vm.playSelectedNext(tracks)
        runCurrent()

        tracks.forEach { t -> verify(playerRepo).addNext(t) }
    }

    @Test
    fun addSelectedToQueue_uses_batch_overload() = runTest {
        val playerRepo = playerRepoMock()
        val vm = buildVm(playerRepository = playerRepo)
        val tracks = listOf(track(1L), track(2L))

        vm.addSelectedToQueue(tracks)
        runCurrent()

        // batch overload, single call
        verify(playerRepo).addToQueue(tracks)
    }

    @Test
    fun downloadSelected_queues_each_id() = runTest {
        val musicRepo = musicRepoMock()
        val vm = buildVm(musicRepository = musicRepo)
        val ids = listOf(1L, 2L, 3L)

        val messages = collectMessages(vm)
        vm.downloadSelected(ids)
        runCurrent()

        ids.forEach { id -> verify(musicRepo).queueDownload(id) }
        assertEquals(listOf("Queued 3 songs for download."), messages)
    }

    @Test
    fun removeDownloadsForSelected_removes_each_id() = runTest {
        val musicRepo = musicRepoMock()
        val vm = buildVm(musicRepository = musicRepo)
        val ids = listOf(1L, 2L)

        val messages = collectMessages(vm)
        vm.removeDownloadsForSelected(ids)
        runCurrent()

        ids.forEach { id -> verify(musicRepo).removeDownload(id) }
        assertEquals(listOf("Removed downloads for 2 songs."), messages)
    }

    @Test
    fun saveSelectedToPlaylist_adds_each_id_to_target() = runTest {
        val musicRepo = musicRepoMock()
        val vm = buildVm(musicRepository = musicRepo)
        val ids = listOf(1L, 2L)
        val targetPlaylistId = 99L

        vm.saveSelectedToPlaylist(ids, targetPlaylistId)
        runCurrent()

        ids.forEach { id -> verify(musicRepo).addTrackToPlaylist(id, targetPlaylistId) }
    }

    @Test
    fun createPlaylistAndAddTracks_creates_once_then_adds_each_to_new_id() = runTest {
        val musicRepo = musicRepoMock()
        val newPlaylistId = 99L
        whenever(musicRepo.createPlaylist(eq("My Mix"))).thenReturn(newPlaylistId)
        val vm = buildVm(musicRepository = musicRepo)
        val ids = listOf(1L, 2L, 3L)

        vm.createPlaylistAndAddTracks("My Mix", ids)
        runCurrent()

        // The playlist is created exactly once with the given name…
        verify(musicRepo).createPlaylist("My Mix")
        // …and every selected track is added to the freshly-created playlist id.
        ids.forEach { id -> verify(musicRepo).addTrackToPlaylist(id, newPlaylistId) }
    }

    @Test
    fun deleteSelected_removes_each_from_playlist_and_emits_rollup() = runTest {
        val musicRepo = musicRepoMock()
        val vm = buildVm(musicRepository = musicRepo)
        val tracks = listOf(track(1L), track(2L), track(3L))

        val messages = collectMessages(vm)
        vm.deleteSelected(tracks, alsoBlacklist = false)
        runCurrent()

        tracks.forEach { t ->
            verify(musicRepo).removeTrackFromPlaylistAndMaybeDelete(
                trackId = eq(t.id),
                fromPlaylistId = any(),
                alsoBlacklist = eq(false),
            )
        }
        assertEquals(listOf("Removed 3 songs."), messages)
    }

    @Test
    fun deleteSelected_with_blacklist_emits_blocked_rollup() = runTest {
        val musicRepo = musicRepoMock()
        // Every cascade reports a blacklisted destroy.
        whenever(
            musicRepo.removeTrackFromPlaylistAndMaybeDelete(any(), any(), eq(true)),
        ).thenReturn(
            MusicRepository.CascadeRemovalSummary(
                deleted = 1, keptProtected = 0, keptElsewhere = 0, blacklisted = 1,
            ),
        )
        val vm = buildVm(musicRepository = musicRepo)
        val tracks = listOf(track(1L), track(2L))

        val messages = collectMessages(vm)
        vm.deleteSelected(tracks, alsoBlacklist = true)
        runCurrent()

        tracks.forEach { t ->
            verify(musicRepo).removeTrackFromPlaylistAndMaybeDelete(
                trackId = eq(t.id),
                fromPlaylistId = any(),
                alsoBlacklist = eq(true),
            )
        }
        assertEquals(listOf("Removed 2 songs. Blocked from future syncs."), messages)
    }

    @Test
    fun downloadSelected_isolates_per_item_failure() = runTest {
        val musicRepo = musicRepoMock()
        // Second item throws; first and third must still be attempted.
        whenever(musicRepo.queueDownload(eq(2L)))
            .thenThrow(RuntimeException("boom"))
        val vm = buildVm(musicRepository = musicRepo)
        val ids = listOf(1L, 2L, 3L)

        val messages = collectMessages(vm)
        vm.downloadSelected(ids)
        runCurrent()

        // All three repo calls happened despite the middle one throwing.
        verify(musicRepo).queueDownload(1L)
        verify(musicRepo).queueDownload(2L)
        verify(musicRepo).queueDownload(3L)
        // Roll-up reflects only the two that succeeded.
        assertEquals(listOf("Queued 2 songs for download."), messages)
    }

    @Test
    fun downloadSelected_counts_only_true_queue_results() = runTest {
        val musicRepo = musicRepoMock()
        whenever(musicRepo.queueDownload(eq(2L))).thenReturn(false)
        val vm = buildVm(musicRepository = musicRepo)

        val messages = collectMessages(vm)
        vm.downloadSelected(listOf(1L, 2L, 3L))
        runCurrent()

        assertEquals(listOf("Queued 2 songs for download."), messages)
    }

    @Test
    fun queueDownload_reports_when_single_item_was_not_queued() = runTest {
        val musicRepo = musicRepoMock()
        whenever(musicRepo.queueDownload(eq(1L))).thenReturn(false)
        val vm = buildVm(musicRepository = musicRepo)

        val messages = collectMessages(vm)
        vm.queueDownload(1L)
        runCurrent()

        assertEquals(listOf("Couldn't queue download."), messages)
    }

    @Test
    fun downloadSelected_rethrows_cancellation_and_aborts_batch() = runTest {
        val musicRepo = musicRepoMock()
        // The FIRST item's repo call is cancelled. The batch methods re-throw
        // CancellationException (instead of swallowing it as a per-item failure),
        // so the loop must abort: later items are never attempted and no roll-up
        // is emitted. A swallowed cancellation would continue the loop and emit
        // "Queued N songs for download." — this test pins the re-throw branch.
        whenever(musicRepo.queueDownload(eq(1L)))
            .thenThrow(kotlinx.coroutines.CancellationException("cancelled"))
        val vm = buildVm(musicRepository = musicRepo)
        val ids = listOf(1L, 2L, 3L)

        val messages = collectMessages(vm)
        vm.downloadSelected(ids)
        runCurrent()

        // First item was attempted (and cancelled); the later items were NOT,
        // proving cancellation propagated rather than being absorbed.
        verify(musicRepo).queueDownload(1L)
        verify(musicRepo, org.mockito.kotlin.never()).queueDownload(2L)
        verify(musicRepo, org.mockito.kotlin.never()).queueDownload(3L)
        // No roll-up message: the batch aborted before any emit.
        assertEquals(emptyList<String>(), messages)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun track(id: Long) = Track(id = id, title = "Track $id", artist = "Artist")

    /**
     * Collects [PlaylistDetailViewModel.userMessages] into a list for the life
     * of the test. Mirrors the flow-collection harness in
     * [LikedSongsDetailViewModelTest] / [MixOfflineTapGuardTest].
     */
    private fun kotlinx.coroutines.test.TestScope.collectMessages(
        vm: PlaylistDetailViewModel,
    ): List<String> {
        val messages = mutableListOf<String>()
        backgroundScope.launch { vm.userMessages.collect { messages.add(it) } }
        runCurrent()
        return messages
    }

    private fun playerRepoMock(): PlayerRepository = mock {
        on { playerState } doReturn MutableStateFlow(PlayerState())
    }

    private fun musicRepoMock(): MusicRepository = mock {
        on { getTracksByPlaylist(any()) } doReturn flowOf(emptyList())
        on { getUserCreatedPlaylists() } doReturn flowOf(emptyList())
        onBlocking { queueDownload(any()) } doReturn true
        onBlocking {
            removeTrackFromPlaylistAndMaybeDelete(any(), any(), any())
        } doReturn MusicRepository.CascadeRemovalSummary(
            deleted = 0, keptProtected = 0, keptElsewhere = 0, blacklisted = 0,
        )
    }

    /**
     * Builds a [PlaylistDetailViewModel] for tests. Collaborators default to
     * plain mocks with the minimum stubs needed for the VM's eager
     * `userPlaylists` field and `init`/`stateIn` flows to start up without
     * NPEs. Tests pass in their own configured repo mock when they verify
     * against it.
     */
    private fun buildVm(
        playerRepository: PlayerRepository = playerRepoMock(),
        musicRepository: MusicRepository = musicRepoMock(),
        playlistImageHelper: PlaylistImageHelper = mock(),
        streamingPreference: StreamingPreference = mock {
            onBlocking { current() } doReturn true
        },
        connectivityMonitor: ConnectivityMonitor = mock(),
        recipeDao: StashMixRecipeDao = mock {
            on { observeAll() } doReturn flowOf(emptyList())
        },
        discoveryQueueDao: DiscoveryQueueDao = mock {
            on { observeNonFailedCountsByRecipe() } doReturn flowOf(emptyList())
        },
        savedStateHandle: SavedStateHandle = SavedStateHandle(mapOf("playlistId" to 1L)),
    ): PlaylistDetailViewModel = PlaylistDetailViewModel(
        savedStateHandle = savedStateHandle,
        musicRepository = musicRepository,
        playerRepository = playerRepository,
        playlistImageHelper = playlistImageHelper,
        streamingPreference = streamingPreference,
        connectivityMonitor = connectivityMonitor,
        recipeDao = recipeDao,
        discoveryQueueDao = discoveryQueueDao,
    )
}
