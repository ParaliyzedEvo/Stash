package com.stash.core.media.actions

import com.google.common.truth.Truth.assertThat
import com.stash.core.media.preview.PreviewPlayer
import com.stash.core.media.preview.PreviewState
import com.stash.core.model.MusicSource
import com.stash.core.model.Track
import com.stash.core.model.TrackItem
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

// ponytail: turbine isn't a :core:media test dep — collect userMessages into a
// list on backgroundScope instead of adding a dependency.
@OptIn(ExperimentalCoroutinesApi::class)
class TrackActionsDelegateQueueActionsTest {

    private val playerRepository: com.stash.core.media.PlayerRepository = mockk(relaxed = true)
    private val musicRepository: com.stash.core.data.repository.MusicRepository = mockk(relaxed = true)
    private val blocklistGuard: com.stash.core.data.blocklist.BlocklistGuard = mockk(relaxed = true)
    private val trackDao: com.stash.core.data.db.dao.TrackDao = mockk(relaxed = true)

    // Real flows, not relaxed stubs: bindToScope collects playerErrors, and a
    // relaxed mock's `collect` (returns Nothing) throws, killing the scope.
    private val previewPlayer: PreviewPlayer = mockk(relaxed = true) {
        every { previewState } returns MutableStateFlow(PreviewState.Idle)
        every { playerErrors } returns MutableSharedFlow()
    }

    private fun delegate() = TrackActionsDelegate(
        previewPlayer = previewPlayer,
        searchPreviewMediaSource = mockk(relaxed = true),
        previewUrlExtractor = mockk(relaxed = true),
        previewUrlCache = mockk(relaxed = true),
        trackDao = trackDao,
        searchDownloadCoordinator = mockk(relaxed = true),
        playerRepository = playerRepository,
        streamingPreference = mockk(relaxed = true),
        musicRepository = musicRepository,
        blocklistGuard = blocklistGuard,
    )

    private val item = TrackItem(
        videoId = "abc123", title = "Song", artist = "Artist",
        durationSeconds = 200.0, thumbnailUrl = "http://art",
    )

    @Test
    fun `addToQueue maps TrackItem and calls repo with streamable Track`() = runTest {
        val d = delegate().apply { bindToScope(backgroundScope) }
        val slot = slot<Track>()
        coEvery { playerRepository.addToQueue(capture(slot)) } returns Unit

        val messages = mutableListOf<String>()
        backgroundScope.launch { d.userMessages.collect { messages.add(it) } }
        runCurrent() // collector must be subscribed before the emit (replay=0)

        d.addToQueue(item)
        // runCurrent (not advanceUntilIdle) — the delegate runs on backgroundScope
        // and advanceUntilIdle stops once only background tasks remain.
        runCurrent()

        assertThat(messages).containsExactly("Added to queue")
        coVerify(exactly = 1) { playerRepository.addToQueue(any<Track>()) }
        assertThat(slot.captured.youtubeId).isEqualTo("abc123")
        assertThat(slot.captured.isStreamable).isTrue()
        assertThat(slot.captured.source).isEqualTo(MusicSource.YOUTUBE)
        assertThat(slot.captured.id).isEqualTo("abc123".hashCode().toLong())
    }

    @Test
    fun `playNext calls addNext and emits message`() = runTest {
        val d = delegate().apply { bindToScope(backgroundScope) }

        val messages = mutableListOf<String>()
        backgroundScope.launch { d.userMessages.collect { messages.add(it) } }
        runCurrent()

        d.playNext(item)
        runCurrent()

        assertThat(messages).containsExactly("Playing next")
        coVerify(exactly = 1) { playerRepository.addNext(any<Track>()) }
    }
}
