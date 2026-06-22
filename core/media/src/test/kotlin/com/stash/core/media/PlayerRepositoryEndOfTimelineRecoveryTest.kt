package com.stash.core.media

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * When the bounded background fill / rolling buffer can't keep the timeline
 * ahead of playback (slow or failing resolves during a Qobuz outage), the
 * player naturally runs off the end of the short timeline into STATE_ENDED.
 * Without recovery it stops there permanently — the "playback stops after a
 * few tracks" bug. Recovery must resolve-and-continue the LOGICAL next track
 * instead of stopping, as long as the queue has more tracks and the user
 * isn't in a repeat mode.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerRepositoryEndOfTimelineRecoveryTest {

    private val playbackStateStore: PlaybackStateStore = mockk(relaxed = true)
    private val musicRepository: MusicRepository = mockk {
        every { trackDeletions } returns MutableSharedFlow()
    }
    private val streamingPreference: StreamingPreference = mockk(relaxed = true)
    private val streamResolver: StreamSourceRegistry = mockk(relaxed = true)
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

    private fun queue(vararg ids: Long) {
        repo.currentQueueTracks = ids.map { Track(id = it, title = "t$it", artist = "a") }
    }

    @Test
    fun `recovers to the next logical track when the timeline runs dry mid-queue`() = runTest {
        queue(10, 11, 12)
        every { controller.repeatMode } returns Player.REPEAT_MODE_OFF
        every { controller.currentMediaItem } returns currentItem(10) // logical index 0

        repo.maybeRecoverFromEnd(controller)

        // navigateToLogical sets pendingNavIndex synchronously before launching.
        assertThat(repo.pendingNavIndex).isEqualTo(1)
    }

    @Test
    fun `does not recover when the current track is genuinely the last in the queue`() = runTest {
        queue(10, 11, 12)
        every { controller.repeatMode } returns Player.REPEAT_MODE_OFF
        every { controller.currentMediaItem } returns currentItem(12) // last logical index

        repo.maybeRecoverFromEnd(controller)

        assertThat(repo.pendingNavIndex).isNull()
    }

    @Test
    fun `does not fight repeat mode`() = runTest {
        queue(10, 11, 12)
        every { controller.repeatMode } returns Player.REPEAT_MODE_ONE
        every { controller.currentMediaItem } returns currentItem(10)

        repo.maybeRecoverFromEnd(controller)

        assertThat(repo.pendingNavIndex).isNull()
    }
}
