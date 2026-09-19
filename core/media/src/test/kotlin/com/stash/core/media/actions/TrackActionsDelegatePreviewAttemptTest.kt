package com.stash.core.media.actions

import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.source.MediaSource
import com.stash.core.media.PlayerRepository
import com.stash.core.media.preview.PreviewPlayer
import com.stash.core.media.preview.PreviewState
import com.stash.core.media.preview.SearchPreviewMediaSource
import com.stash.core.model.TrackItem
import com.stash.data.download.preview.PreviewUrlExtractor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertNull
import org.junit.Test

/** Ensures the singleton preview error flow cannot cross screen/attempt owners. */
@OptIn(ExperimentalCoroutinesApi::class)
class TrackActionsDelegatePreviewAttemptTest {

    private var requestSequence = 0L
    private var currentRequestId = 0L
    private var attemptSequence = 40L
    private val previewPlayer: PreviewPlayer = mockk(relaxed = true) {
        every { previewState } returns MutableStateFlow(PreviewState.Idle)
        every { playerErrors } returns MutableSharedFlow()
        every { claimRequest() } answers {
            currentRequestId = ++requestSequence
            currentRequestId
        }
        every { isRequestCurrent(any()) } answers {
            firstArg<Long?>() == currentRequestId
        }
        every { cancelRequest(any()) } answers {
            if (firstArg<Long?>() == currentRequestId) currentRequestId = 0L
            Unit
        }
        every { playIfClaimed(any(), any(), any(), any()) } answers {
            if (firstArg<Long>() != currentRequestId) {
                null
            } else {
                val attemptId = ++attemptSequence
                arg<(Long) -> Unit>(3).invoke(attemptId)
                attemptId
            }
        }
        every { playUrlIfClaimed(any(), any(), any(), any()) } answers {
            if (firstArg<Long>() != currentRequestId) {
                null
            } else {
                val attemptId = ++attemptSequence
                arg<(Long) -> Unit>(3).invoke(attemptId)
                attemptId
            }
        }
    }
    private val mediaSourceFactory: SearchPreviewMediaSource = mockk()
    private val extractor: PreviewUrlExtractor = mockk(relaxed = true)
    private val streamingPreference: com.stash.core.data.prefs.StreamingPreference = mockk()

    private fun delegate() = TrackActionsDelegate(
        previewPlayer = previewPlayer,
        searchPreviewMediaSource = mediaSourceFactory,
        previewUrlExtractor = extractor,
        previewUrlCache = mockk(relaxed = true),
        trackDao = mockk(relaxed = true),
        searchDownloadCoordinator = mockk(relaxed = true),
        playerRepository = mockk<PlayerRepository>(relaxed = true),
        streamingPreference = streamingPreference,
        musicRepository = mockk(relaxed = true),
        blocklistGuard = mockk(relaxed = true),
    )

    private fun ioError() = PlaybackException(
        "preview URL rejected",
        IOException("HTTP 403"),
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
    )

    @Test
    fun `only the exact owned playback attempt may trigger yt-dlp retry`() = runTest {
        val track = mockk<TrackItem> { every { videoId } returns "same-video" }
        val mediaSource = mockk<MediaSource>()
        coEvery { streamingPreference.current() } returns false
        coEvery { mediaSourceFactory.create(track) } returns mediaSource
        coEvery { extractor.extractViaYtDlpForRetry("same-video") } returns "https://retry"
        val delegate = delegate()
        delegate.bindToScope(backgroundScope)

        delegate.previewTrack(track)
        runCurrent()
        verify(exactly = 1) {
            previewPlayer.playIfClaimed(any(), "same-video", mediaSource, any())
        }

        delegate.onPreviewError("same-video", attemptId = 99L, error = ioError())
        runCurrent()
        coVerify(exactly = 0) { extractor.extractViaYtDlpForRetry(any()) }

        delegate.onPreviewError("same-video", attemptId = 41L, error = ioError())
        runCurrent()
        coVerify(exactly = 1) { extractor.extractViaYtDlpForRetry("same-video") }
    }

    @Test
    fun `a superseded suspended source load cannot revive stale playback`() = runTest {
        val first = mockk<TrackItem> { every { videoId } returns "first" }
        val second = mockk<TrackItem> { every { videoId } returns "second" }
        val firstSource = mockk<MediaSource>()
        val secondSource = mockk<MediaSource>()
        val firstGate = CompletableDeferred<Unit>()
        coEvery { streamingPreference.current() } returns false
        coEvery { mediaSourceFactory.create(first) } coAnswers {
            withContext(NonCancellable) { firstGate.await() }
            firstSource
        }
        coEvery { mediaSourceFactory.create(second) } returns secondSource
        val delegate = delegate()
        delegate.bindToScope(backgroundScope)

        delegate.previewTrack(first)
        runCurrent()
        delegate.previewTrack(second)
        runCurrent()
        firstGate.complete(Unit)
        runCurrent()

        verify(exactly = 0) {
            previewPlayer.playIfClaimed(any(), "first", firstSource, any())
        }
        verify(exactly = 1) {
            previewPlayer.playIfClaimed(any(), "second", secondSource, any())
        }
    }

    @Test
    fun `a newer owner claim rejects another owners suspended source load`() = runTest {
        val first = mockk<TrackItem> { every { videoId } returns "first" }
        val second = mockk<TrackItem> { every { videoId } returns "second" }
        val firstSource = mockk<MediaSource>()
        val secondSource = mockk<MediaSource>()
        val firstGate = CompletableDeferred<Unit>()
        coEvery { streamingPreference.current() } returns false
        coEvery { mediaSourceFactory.create(first) } coAnswers {
            firstGate.await()
            firstSource
        }
        coEvery { mediaSourceFactory.create(second) } returns secondSource
        val firstOwner = delegate()
        val secondOwner = delegate()
        firstOwner.bindToScope(backgroundScope)
        secondOwner.bindToScope(backgroundScope)

        firstOwner.previewTrack(first)
        runCurrent()
        secondOwner.previewTrack(second)
        runCurrent()
        firstGate.complete(Unit)
        runCurrent()

        verify(exactly = 1) {
            previewPlayer.playIfClaimed(1L, "first", firstSource, any())
        }
        verify(exactly = 1) {
            previewPlayer.playIfClaimed(2L, "second", secondSource, any())
        }
        assertNull(firstOwner.previewLoadingId.value)
    }

    @Test
    fun `a newer owner claim rejects another owners suspended retry`() = runTest {
        val first = mockk<TrackItem> { every { videoId } returns "first" }
        val second = mockk<TrackItem> { every { videoId } returns "second" }
        val firstSource = mockk<MediaSource>()
        val secondSource = mockk<MediaSource>()
        val retryGate = CompletableDeferred<Unit>()
        coEvery { streamingPreference.current() } returns false
        coEvery { mediaSourceFactory.create(first) } returns firstSource
        coEvery { mediaSourceFactory.create(second) } returns secondSource
        coEvery { extractor.extractViaYtDlpForRetry("first") } coAnswers {
            withContext(NonCancellable) { retryGate.await() }
            "https://stale-retry"
        }
        val firstOwner = delegate()
        val secondOwner = delegate()
        firstOwner.bindToScope(backgroundScope)
        secondOwner.bindToScope(backgroundScope)

        firstOwner.previewTrack(first)
        runCurrent()
        firstOwner.onPreviewError("first", attemptId = 41L, error = ioError())
        runCurrent()
        secondOwner.previewTrack(second)
        runCurrent()
        retryGate.complete(Unit)
        runCurrent()

        coVerify(exactly = 1) { extractor.extractViaYtDlpForRetry("first") }
        verify(exactly = 1) {
            previewPlayer.playUrlIfClaimed(1L, "first", "https://stale-retry", any())
        }
        verify(exactly = 1) {
            previewPlayer.playIfClaimed(2L, "second", secondSource, any())
        }
        assertNull(firstOwner.previewLoadingId.value)
    }
}
