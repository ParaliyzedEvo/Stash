package com.stash.feature.home

import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.tipjar.TipJarRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.model.MusicSource
import com.stash.core.model.Playlist
import com.stash.core.model.PlaylistType
import com.stash.core.model.Track
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking

/**
 * The Home-screen play paths must honour the Online/Offline toggle via
 * [com.stash.core.media.streaming.queuePlayableTracks] (already wired into
 * PlaylistDetailViewModel).
 *
 * Offline mode (streaming preference OFF) enqueues downloaded-only — the
 * player's offline master-gate would silently skip stream-only tracks, so
 * connectivity is irrelevant. Streaming ON enqueues every track.
 *
 * Mirrors the harness in :feature:library's MixOfflineTapGuardTest —
 * mockito-kotlin, StandardTestDispatcher, mock collaborators.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelPlaybackTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val downloaded = Track(
        id = 1L, title = "Local", artist = "A",
        isStreamable = true, isDownloaded = true,
        filePath = "/m/1.opus",
    )
    private val streamOnly = Track(
        id = 42L, title = "Cloud", artist = "A",
        isStreamable = true, isDownloaded = false, filePath = null,
    )

    @Test
    fun `playPlaylist offline mode + mix enqueues only downloaded`() = runTest {
        val playlist = playlist(id = 7L, type = PlaylistType.STASH_MIX)
        val playerRepo = mock<PlayerRepository>()
        val vm = buildVm(
            playlist = playlist,
            tracks = listOf(downloaded, streamOnly),
            playerRepository = playerRepo,
            streamingEnabled = false,
        )

        vm.playPlaylist(playlist)
        runCurrent()

        val queueCaptor = argumentCaptor<List<Track>>()
        verifyBlocking(playerRepo) { setQueue(queueCaptor.capture(), any()) }
        assertThat(queueCaptor.firstValue.map { it.id }).containsExactly(1L)
    }

    @Test
    fun `playPlaylist streaming on + mix enqueues downloaded and stream-only`() = runTest {
        val playlist = playlist(id = 7L, type = PlaylistType.STASH_MIX)
        val playerRepo = mock<PlayerRepository>()
        val vm = buildVm(
            playlist = playlist,
            tracks = listOf(downloaded, streamOnly),
            playerRepository = playerRepo,
            streamingEnabled = true,
        )

        vm.playPlaylist(playlist)
        runCurrent()

        val queueCaptor = argumentCaptor<List<Track>>()
        verifyBlocking(playerRepo) { setQueue(queueCaptor.capture(), any()) }
        assertThat(queueCaptor.firstValue.map { it.id }).containsExactly(1L, 42L)
    }

    @Test
    fun `addPlaylistToQueue offline mode + mix adds only downloaded`() = runTest {
        val playlist = playlist(id = 7L, type = PlaylistType.STASH_MIX)
        val playerRepo = mock<PlayerRepository>()
        val vm = buildVm(
            playlist = playlist,
            tracks = listOf(downloaded, streamOnly),
            playerRepository = playerRepo,
            streamingEnabled = false,
        )

        vm.addPlaylistToQueue(playlist)
        runCurrent()

        val trackCaptor = argumentCaptor<Track>()
        verifyBlocking(playerRepo, times(1)) { addToQueue(trackCaptor.capture()) }
        assertThat(trackCaptor.allValues.map { it.id }).containsExactly(1L)
    }

    @Test
    fun `addPlaylistToQueue streaming on + mix adds downloaded and stream-only`() = runTest {
        val playlist = playlist(id = 7L, type = PlaylistType.STASH_MIX)
        val playerRepo = mock<PlayerRepository>()
        val vm = buildVm(
            playlist = playlist,
            tracks = listOf(downloaded, streamOnly),
            playerRepository = playerRepo,
            streamingEnabled = true,
        )

        vm.addPlaylistToQueue(playlist)
        runCurrent()

        val trackCaptor = argumentCaptor<Track>()
        verifyBlocking(playerRepo, times(2)) { addToQueue(trackCaptor.capture()) }
        assertThat(trackCaptor.allValues.map { it.id }).containsExactly(1L, 42L)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun playlist(id: Long, type: PlaylistType) = Playlist(
        id = id,
        name = "P",
        source = MusicSource.SPOTIFY,
        type = type,
    )

    private fun buildVm(
        playlist: Playlist,
        tracks: List<Track>,
        playerRepository: PlayerRepository,
        streamingEnabled: Boolean,
    ): HomeViewModel {
        val musicRepo = mock<MusicRepository> {
            on { getTracksByPlaylist(playlist.id) } doReturn flowOf(tracks)
        }
        val streamingPreference = mock<StreamingPreference> {
            onBlocking { current() } doReturn streamingEnabled
        }
        // init {} block reads isStale() (suspend, primitive Boolean) — stub it
        // so the cold-start warm-up coroutine doesn't NPE on an unboxed null.
        val tipJar = mock<TipJarRepository> {
            onBlocking { isStale() } doReturn false
        }
        val networkManager = mock<com.stash.core.media.streaming.NetworkAwareStreamingManager> {
            on { messages } doReturn kotlinx.coroutines.flow.MutableSharedFlow<String>()
        }
        return HomeViewModel(
            musicRepository = musicRepo,
            playerRepository = playerRepository,
            lastFmSessionPreference = mock(),
            lastFmCredentials = mock(),
            listeningEventDao = mock(),
            losslessPrefs = mock(),
            settingsDeepLinkController = mock(),
            tipJarRepository = tipJar,
            recipeDao = mock(),
            discoveryQueueDao = mock(),
            downloadQueueDao = mock(),
            qobuzSource = mock(),
            aggregatorRateLimiter = mock(),
            downloadNetworkPreference = mock(),
            streamingPreference = streamingPreference,
            metadataBackfillState = mock(),
            lyricsBackfillState = mock(),
            networkAwareStreamingManager = networkManager,
            syncScheduler = mock(),
            context = mock(),
        )
    }
}
