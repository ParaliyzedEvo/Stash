package com.stash.core.media

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_ID
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.media.streaming.StreamSourceRegistry
import com.stash.core.media.streaming.StreamUrlCache
import com.stash.core.model.Track
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the tap-while-playing display behavior of [PlayerRepositoryImpl.setQueue].
 *
 * The bug: tapping track B while track A was playing/loaded immediately
 * swapped the mini player to B with `isBuffering = true` — which renders as
 * a DISABLED spinner in place of the play/pause button. For a slow resolve
 * (antra jobs take 60–120 s) the user lost all control of the still-playing
 * track A for the whole resolve window.
 *
 * Wanted: the displayed state only switches to B when B actually starts.
 * The optimistic "show tapped track + spinner" emit is still correct when
 * NOTHING is loaded (mini player hidden — the spinner is the only feedback
 * that the tap registered), so that path is pinned here too.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerRepositorySetQueueGateTest {

    private val playbackStateStore: PlaybackStateStore = mockk(relaxed = true)
    private val musicRepository: MusicRepository = mockk {
        every { trackDeletions } returns MutableSharedFlow()
    }
    private val streamingPreference: StreamingPreference = mockk()
    private val streamResolver: StreamSourceRegistry = mockk()
    private val streamUrlCache: StreamUrlCache = mockk(relaxUnitFun = true)
    private val connectivity: ConnectivityMonitor = mockk()
    private val trackDao: TrackDao = mockk()
    private val castStateHolder: CastStateHolder = CastStateHolder()

    private lateinit var repo: PlayerRepositoryImpl

    private val trackB = Track(
        id = 2L,
        title = "Tapped Track",
        artist = "Artist B",
        isDownloaded = false,
        isStreamable = true,
    )

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        repo = PlayerRepositoryImpl(
            context = context,
            playbackStateStore = playbackStateStore,
            musicRepository = musicRepository,
            streamingPreference = streamingPreference,
            streamResolver = streamResolver,
            streamUrlCache = streamUrlCache,
            connectivity = connectivity,
            trackDao = trackDao,
            playbackResumer = PlaybackResumer(playbackStateStore, trackDao),
            castStateHolder = castStateHolder,
        )
        repo.filePathExistsOnDisk = { true }

        coEvery { streamingPreference.current() } returns true
        every { streamingPreference.streamOnCellular } returns flowOf(true)
        every { connectivity.isConnected() } returns true
        every { connectivity.isCellular() } returns false
        every { streamUrlCache.get(any()) } returns null
        coEvery { trackDao.getById(any()) } returns null
        // Resolve never completes within the test window — simulates a slow
        // resolve (e.g. yt-dlp extraction) holding the tapped track in limbo.
        coEvery { streamResolver.resolve(any(), any(), any()) } coAnswers {
            delay(600_000)
            null
        }
    }

    private fun mediaItemWithTrackId(id: Long): MediaItem =
        MediaItem.Builder()
            .setMediaId(id.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setExtras(Bundle().apply { putLong(EXTRA_TRACK_ID, id) })
                    .build()
            )
            .build()

    private fun controllerWithCurrentItem(item: MediaItem?): MediaController =
        mockk(relaxed = true) {
            every { currentMediaItem } returns item
            every { mediaItemCount } returns if (item == null) 0 else 1
            every { currentMediaItemIndex } returns 0
            every { isPlaying } returns (item != null)
        }

    @Test
    fun `tap while another track is loaded - displayed state is not hijacked during resolve`() = runTest {
        repo.controllerDeferred = controllerWithCurrentItem(mediaItemWithTrackId(1L))

        val job = launch { repo.setQueue(listOf(trackB), 0) }
        advanceTimeBy(5_000) // resolve still in flight

        val state = repo.playerState.value
        // The tapped track must NOT replace the current display...
        assertThat(state.currentTrack?.id).isNotEqualTo(trackB.id)
        // ...and the play/pause button must stay usable (no spinner lock-out).
        assertThat(state.isBuffering).isFalse()

        job.cancel()
    }

    @Test
    fun `tap with nothing loaded - optimistic loading display is kept`() = runTest {
        repo.controllerDeferred = controllerWithCurrentItem(null)

        val job = launch { repo.setQueue(listOf(trackB), 0) }
        advanceTimeBy(5_000)

        val state = repo.playerState.value
        // Idle player: the tapped track + spinner IS the feedback that the
        // tap registered. Keep it.
        assertThat(state.currentTrack?.id).isEqualTo(trackB.id)
        assertThat(state.isBuffering).isTrue()

        job.cancel()
    }
}
