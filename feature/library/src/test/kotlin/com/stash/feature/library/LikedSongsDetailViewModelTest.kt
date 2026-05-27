package com.stash.feature.library

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.model.MusicSource
import com.stash.core.model.PlayerState
import com.stash.core.model.Playlist
import com.stash.core.model.PlaylistType
import com.stash.core.model.Track
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock

/**
 * Pins the contract introduced in Task 9 of the extract-coalescing redesign:
 * [LikedSongsDetailViewModel.tappedTrackId] emits the tapped track ID before
 * [PlayerRepository.setQueue] is awaited, and clears back to `null` after
 * it returns. The screen layer reads this StateFlow to render an instant
 * spinner without waiting for the YouTube-stream resolver to finish.
 *
 * Uses mockito-kotlin to match the in-repo pattern from
 * `feature/search/.../SearchViewModelTest` — same harness, same idioms.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LikedSongsDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }


    @Test
    fun `tappedTrackId emits on playTrack and clears after setQueue returns`() = runTest {
        val gate = CompletableDeferred<Unit>()
        // A single track in a single playlist so that the VM's tracksFlow ->
        // uiState pipeline ends up with a non-empty `tracks` list. Without
        // this, `playTrack`'s `playable.isEmpty()` early-return short-circuits
        // before setQueue is invoked, and the suspend-on-gate part of the
        // spinner contract never gets exercised.
        val track = Track(id = 42L, title = "Hit", artist = "Artist")
        val playlist = Playlist(
            id = 1L, name = "Liked", source = MusicSource.SPOTIFY, type = PlaylistType.LIKED_SONGS,
        )
        val musicRepo = mock<MusicRepository> {
            on { getPlaylistsByType(any()) } doReturn flowOf(listOf(playlist))
            on { getTracksByPlaylist(playlist.id) } doReturn flowOf(listOf(track))
            on { getUserCreatedPlaylists() } doReturn flowOf(emptyList())
        }
        val playerRepo = mock<PlayerRepository> {
            on { playerState } doReturn MutableStateFlow(PlayerState())
            onBlocking { setQueue(any(), any()) } doSuspendableAnswer { gate.await() }
        }
        val vm = buildVm(playerRepository = playerRepo, musicRepository = musicRepo)

        val emissions = mutableListOf<Long?>()
        val collectJob = backgroundScope.launch {
            vm.tappedTrackId.collect { emissions.add(it) }
        }
        // Drive uiState's `stateIn` so `uiState.value.tracks` becomes
        // populated before playTrack reads it. Without this, WhileSubscribed
        // would never have kicked off the collector and the snapshot stays
        // at the initial empty value.
        val uiSub = backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()

        vm.playTrack(trackId = 42L)
        runCurrent()
        assertThat(emissions).contains(42L)

        gate.complete(Unit)
        runCurrent()
        assertThat(emissions.last()).isNull()

        collectJob.cancel()
        uiSub.cancel()
    }

    @Test
    fun `liked songs with duplicate titles and artists are merged and deduplicated`() = runTest {
        val spotifyTrack = Track(
            id = 42L,
            title = "Hit (Remix)",
            artist = "Aditya",
            source = MusicSource.SPOTIFY,
            isDownloaded = false
        )
        val ytTrack = Track(
            id = 43L,
            title = "Hit - Remix",
            artist = "Aditya",
            source = MusicSource.YOUTUBE,
            isDownloaded = true
        )

        val spotifyPlaylist = Playlist(
            id = 1L, name = "Liked", source = MusicSource.SPOTIFY, type = PlaylistType.LIKED_SONGS,
        )
        val ytPlaylist = Playlist(
            id = 2L, name = "Liked", source = MusicSource.YOUTUBE, type = PlaylistType.LIKED_SONGS,
        )

        val musicRepo = mock<MusicRepository> {
            on { getPlaylistsByType(any()) } doReturn flowOf(listOf(spotifyPlaylist, ytPlaylist))
            on { getTracksByPlaylist(spotifyPlaylist.id) } doReturn flowOf(listOf(spotifyTrack))
            on { getTracksByPlaylist(ytPlaylist.id) } doReturn flowOf(listOf(ytTrack))
            on { getUserCreatedPlaylists() } doReturn flowOf(emptyList())
        }

        val vm = buildVm(musicRepository = musicRepo)

        val uiSub = backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()

        val tracks = vm.uiState.value.tracks
        assertThat(tracks).hasSize(1)
        assertThat(tracks[0].id).isEqualTo(43L)

        uiSub.cancel()
    }

    // ------------------------------------------------------------------
    // Helpers

    // ------------------------------------------------------------------

    /**
     * Builds a [LikedSongsDetailViewModel] for tests. All collaborators
     * default to plain mocks with the minimum stubs needed for the VM's
     * `init`/`stateIn` flows to start up without NPEs. Tests that care
     * about a specific collaborator (here: [PlayerRepository]) pass in
     * their own configured mock.
     */
    private fun buildVm(
        playerRepository: PlayerRepository = mock {
            on { playerState } doReturn MutableStateFlow(PlayerState())
        },
        musicRepository: MusicRepository = mock {
            on { getPlaylistsByType(any()) } doReturn flowOf(emptyList())
            on { getUserCreatedPlaylists() } doReturn flowOf(emptyList())
        },
        streamingPreference: StreamingPreference = mock {
            onBlocking { current() } doReturn true
        },
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ): LikedSongsDetailViewModel = LikedSongsDetailViewModel(
        savedStateHandle = savedStateHandle,
        musicRepository = musicRepository,
        playerRepository = playerRepository,
        streamingPreference = streamingPreference,
    )
}
