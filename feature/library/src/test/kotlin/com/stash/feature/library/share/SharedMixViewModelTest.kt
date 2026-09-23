package com.stash.feature.library.share

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.entity.SharedMixEntity
import com.stash.core.data.share.ShareResult
import com.stash.core.data.share.SharedMixDocument
import com.stash.core.data.share.SharedMixRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.model.PlaybackSource
import com.stash.core.model.Track
import com.stash.core.model.share.SharedTrack
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharedMixViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<SharedMixRepository>(relaxed = true)
    private val player = mockk<PlayerRepository>(relaxed = true)
    private val doc = SharedMixDocument(id = "Kx7Qa2pL", version = 1, name = "Ambient", sharedBy = "Rawn", tracks = listOf(SharedTrack("T", "A")))

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }
    private fun vm() = SharedMixViewModel(SavedStateHandle(mapOf("shareId" to "Kx7Qa2pL")), repo, player)

    @Test fun `loads the doc and knows when it's already followed`() = runTest(dispatcher) {
        coEvery { repo.fetch("Kx7Qa2pL") } returns ShareResult.Ok(doc)
        coEvery { repo.byShareId("Kx7Qa2pL") } returns SharedMixEntity(9, "Kx7Qa2pL", SharedMixEntity.ROLE_FOLLOWER, name = "Ambient")
        val vm = vm(); advanceUntilIdle()
        val s = vm.state.value as SharedMixUiState.Loaded
        assertThat(s.doc.name).isEqualTo("Ambient")
        assertThat(s.followedPlaylistId).isEqualTo(9L)
        assertThat(s.isOwnMix).isFalse()
    }

    @Test fun `an owner row marks the mix as your own`() = runTest(dispatcher) {
        coEvery { repo.fetch(any()) } returns ShareResult.Ok(doc)
        coEvery { repo.byShareId(any()) } returns SharedMixEntity(3, "Kx7Qa2pL", SharedMixEntity.ROLE_OWNER, name = "Ambient")
        val vm = vm(); advanceUntilIdle()
        val s = vm.state.value as SharedMixUiState.Loaded
        assertThat(s.isOwnMix).isTrue()
        assertThat(s.followedPlaylistId).isNull()
    }

    @Test fun `gone, missing, newer format and offline map to the spec messages`() = runTest(dispatcher) {
        coEvery { repo.fetch(any()) } returns ShareResult.Gone
        val gone = vm(); advanceUntilIdle()
        assertThat((gone.state.value as SharedMixUiState.Error).message).isEqualTo("This mix is no longer shared.")
        coEvery { repo.fetch(any()) } returns ShareResult.NotFound
        val missing = vm(); advanceUntilIdle()
        assertThat((missing.state.value as SharedMixUiState.Error).message).isEqualTo("This link doesn't point to a mix.")
        coEvery { repo.fetch(any()) } returns ShareResult.Ok(doc.copy(v = 2))
        val newer = vm(); advanceUntilIdle()
        assertThat((newer.state.value as SharedMixUiState.Error).message).isEqualTo("Update Stash to open this mix.")
        coEvery { repo.fetch(any()) } returns ShareResult.Failed("io")
        val offline = vm(); advanceUntilIdle()
        assertThat((offline.state.value as SharedMixUiState.Error).retryable).isTrue()
    }

    @Test fun `follow persists and reports the new playlist`() = runTest(dispatcher) {
        coEvery { repo.fetch(any()) } returns ShareResult.Ok(doc)
        coEvery { repo.byShareId(any()) } returns null
        coEvery { repo.follow(doc) } returns 42L
        val vm = vm(); advanceUntilIdle()
        var opened: Long? = null
        vm.follow { opened = it }; advanceUntilIdle()
        assertThat(opened).isEqualTo(42L)
        coVerify { repo.follow(doc) }
    }

    @Test fun `a failing follow shows a message and leaves the screen usable`() = runTest(dispatcher) {
        coEvery { repo.fetch(any()) } returns ShareResult.Ok(doc)
        coEvery { repo.byShareId(any()) } returns null
        coEvery { repo.follow(doc) } throws IllegalStateException("constraint")
        val vm = vm(); advanceUntilIdle()
        var opened: Long? = null
        vm.follow { opened = it }; advanceUntilIdle()
        val s = vm.state.value as SharedMixUiState.Loaded
        assertThat(opened).isNull()
        assertThat(s.busy).isFalse()
        assertThat(s.followedPlaylistId).isNull()
        assertThat(s.message).isNotNull()
    }

    @Test fun `play from a followed mix plays from that playlist`() = runTest(dispatcher) {
        val tracks = listOf(mockk<Track>())
        coEvery { repo.fetch(any()) } returns ShareResult.Ok(doc)
        coEvery { repo.byShareId(any()) } returns SharedMixEntity(9, "Kx7Qa2pL", SharedMixEntity.ROLE_FOLLOWER, name = "Ambient")
        coEvery { repo.tracksFor(doc) } returns tracks
        val vm = vm(); advanceUntilIdle()
        vm.play(); advanceUntilIdle()
        coVerify { player.setQueue(tracks, 0, PlaybackSource.Playlist(9, "Ambient")) }
    }

    @Test fun `play from an unfollowed mix keeps the default source`() = runTest(dispatcher) {
        val tracks = listOf(mockk<Track>())
        coEvery { repo.fetch(any()) } returns ShareResult.Ok(doc)
        coEvery { repo.byShareId(any()) } returns null
        coEvery { repo.tracksFor(doc) } returns tracks
        val vm = vm(); advanceUntilIdle()
        vm.play(); advanceUntilIdle()
        coVerify { player.setQueue(tracks, 0, PlaybackSource.Unknown) }
    }

    @Test fun `unfollow removes the playlist and clears followedPlaylistId`() = runTest(dispatcher) {
        coEvery { repo.fetch(any()) } returns ShareResult.Ok(doc)
        coEvery { repo.byShareId(any()) } returns SharedMixEntity(9, "Kx7Qa2pL", SharedMixEntity.ROLE_FOLLOWER, name = "Ambient")
        val vm = vm(); advanceUntilIdle()
        vm.unfollow(); advanceUntilIdle()
        coVerify { repo.unfollow(9) }
        assertThat((vm.state.value as SharedMixUiState.Loaded).followedPlaylistId).isNull()
    }
}
