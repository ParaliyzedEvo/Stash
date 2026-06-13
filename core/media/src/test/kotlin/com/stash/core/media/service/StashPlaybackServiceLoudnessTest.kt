// StashPlaybackServiceLoudnessTest.kt
package com.stash.core.media.service

import androidx.media3.common.MediaItem
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.social.LikeCoordinator
import com.stash.core.media.equalizer.EqController
import com.stash.core.media.equalizer.LoudnessController
import com.stash.core.media.streaming.PrefetchOrchestrator
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Verifies that [StashPlaybackService.onTrackTransitionForLoudness] pulls the
 * incoming track's measured loudness from the DAO and pushes the computed
 * per-track gain into [LoudnessController]. The full [Player.Listener]
 * callback delegates to this hook on every `onMediaItemTransition` event,
 * so testing the hook covers the production wiring without booting a
 * full [androidx.media3.session.MediaSessionService] / [ExoPlayer].
 */
@RunWith(RobolectricTestRunner::class)
class StashPlaybackServiceLoudnessTest {

    private val trackDao = mockk<TrackDao>(relaxed = true)
    private val loudnessController = mockk<LoudnessController>(relaxed = true)
    private val eqController = mockk<EqController>(relaxed = true)
    private val likeCoordinator = mockk<LikeCoordinator>(relaxed = true)
    private val prefetchOrchestrator = mockk<PrefetchOrchestrator>(relaxed = true)

    private fun newService(): StashPlaybackService {
        // Robolectric attaches a base context so @AndroidEntryPoint's
        // generated init doesn't trip; we never call onCreate so no
        // MediaSession / ExoPlayer is constructed.
        val service = Robolectric.buildService(StashPlaybackService::class.java).get()
        service.trackDao = trackDao
        service.loudnessController = loudnessController
        service.eqController = eqController
        service.likeCoordinator = likeCoordinator
        service.prefetchOrchestrator = prefetchOrchestrator
        return service
    }

    @Test
    fun `onTrackTransition pushes computed gain for a measured track`() = runTest {
        // -20 LUFS + target -14 LUFS = +6 dB raw, peak -3 dBFS leaves
        // -1 - (-3) = +2 dB of headroom which dominates. So the
        // expected pushed gain is +2f.
        val track = TrackEntity(
            id = 42L,
            title = "t",
            artist = "a",
            loudnessLufs = -20f,
            truePeakDbfs = -3f,
        )
        coEvery { trackDao.getById(42L) } returns track

        val service = newService()
        service.onTrackTransitionForLoudness(MediaItem.Builder().setMediaId("42").build())

        // serviceScope is Dispatchers.Main.immediate, so the launch body
        // runs inline on the calling thread — verification is sync.
        verify { loudnessController.setCurrentTrackGain(2f) }
    }

    @Test
    fun `onTrackTransition pushes zero gain for an unmeasured track`() = runTest {
        // Null loudness => computeGain returns 0f (bypass).
        val track = TrackEntity(id = 7L, title = "t", artist = "a")
        coEvery { trackDao.getById(7L) } returns track

        val service = newService()
        service.onTrackTransitionForLoudness(MediaItem.Builder().setMediaId("7").build())

        verify { loudnessController.setCurrentTrackGain(0f) }
    }

    @Test
    fun `onTrackTransition is a no-op for a non-numeric mediaId`() = runTest {
        val service = newService()
        service.onTrackTransitionForLoudness(MediaItem.Builder().setMediaId("not-a-long").build())

        verify(exactly = 0) { loudnessController.setCurrentTrackGain(any()) }
    }

    @Test
    fun `onTrackTransition is a no-op for a null MediaItem`() = runTest {
        val service = newService()
        service.onTrackTransitionForLoudness(null)

        verify(exactly = 0) { loudnessController.setCurrentTrackGain(any()) }
    }

    @Test
    fun `onTrackTransition is a no-op when the DAO has no row`() = runTest {
        coEvery { trackDao.getById(99L) } returns null

        val service = newService()
        service.onTrackTransitionForLoudness(MediaItem.Builder().setMediaId("99").build())

        verify(exactly = 0) { loudnessController.setCurrentTrackGain(any()) }
    }
}
