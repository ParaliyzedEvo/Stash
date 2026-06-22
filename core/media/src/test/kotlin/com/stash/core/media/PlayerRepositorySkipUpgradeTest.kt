package com.stash.core.media

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
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Skip must ALWAYS advance — never a no-op. When the timeline can advance
 * (the next item is materialized), it seeks instantly (respecting shuffle).
 * When the timeline can't advance — the frontier was reached because the
 * background fill couldn't pre-resolve the next streaming track — it routes
 * the LOGICAL next through the resolve-with-spinner path instead of doing
 * nothing.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerRepositorySkipUpgradeTest {

    private val playbackStateStore: PlaybackStateStore = mockk(relaxed = true)
    private val musicRepository: MusicRepository = mockk {
        every { trackDeletions } returns MutableSharedFlow()
    }
    private val streamingPreference: StreamingPreference = mockk(relaxed = true)
    private val streamResolver: StreamSourceRegistry = mockk()
    private val streamUrlCache: StreamUrlCache = mockk(relaxUnitFun = true)
    private val connectivity: ConnectivityMonitor = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk(relaxed = true)
    private val controller: MediaController = mockk(relaxed = true)

    private lateinit var repo: PlayerRepositoryImpl

    @Before
    fun setUp() {
        repo = PlayerRepositoryImpl(
            context = ApplicationProvider.getApplicationContext(),
            playbackStateStore = playbackStateStore,
            musicRepository = musicRepository,
            streamingPreference = streamingPreference,
            streamResolver = streamResolver,
            streamUrlCache = streamUrlCache,
            connectivity = connectivity,
            trackDao = trackDao,
            playbackResumer = PlaybackResumer(playbackStateStore, trackDao),
        )
        repo.controllerDeferred = controller
    }

    private fun currentItem(trackId: Long) = MediaItem.Builder()
        .setMediaId(trackId.toString())
        .setUri("https://x/$trackId")
        .setMediaMetadata(
            MediaMetadata.Builder().setExtras(
                Bundle().apply { putLong(EXTRA_TRACK_ID, trackId) },
            ).build(),
        )
        .build()

    @Test
    fun `skipNext seeks instantly when the timeline can advance`() = runTest {
        every { controller.hasNextMediaItem() } returns true

        repo.skipNext()

        verify { controller.seekToNextMediaItem() }
    }

    @Test
    fun `skipPrevious seeks instantly when the timeline can go back`() = runTest {
        every { controller.hasPreviousMediaItem() } returns true

        repo.skipPrevious()

        verify { controller.seekToPreviousMediaItem() }
    }

    @Test
    fun `rapid skipNext at the frontier advances the pending target without seeking`() = runTest {
        // Timeline can't advance (frontier). Each tap must move ONE further
        // from the pending target — not re-target the same next track — and
        // must never fall back to a no-op seek.
        every { controller.hasNextMediaItem() } returns false
        repo.currentQueueTracks = (10L..13L).map { Track(id = it, title = "t$it", artist = "a") }
        every { controller.currentMediaItem } returns currentItem(10) // logical index 0

        repo.skipNext()
        assertThat(repo.pendingNavIndex).isEqualTo(1)
        repo.skipNext()
        assertThat(repo.pendingNavIndex).isEqualTo(2)

        verify(exactly = 0) { controller.seekToNextMediaItem() }
    }
}
