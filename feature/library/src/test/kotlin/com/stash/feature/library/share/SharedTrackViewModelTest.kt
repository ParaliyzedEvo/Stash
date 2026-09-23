package com.stash.feature.library.share

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.social.LikeCoordinator
import com.stash.core.media.PlayerRepository
import com.stash.core.model.Track
import com.stash.core.model.share.ShareLinks
import com.stash.core.model.share.SharedTrack
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharedTrackViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val music = mockk<MusicRepository>(relaxed = true)
    private val player = mockk<PlayerRepository>(relaxed = true)
    private val likes = mockk<LikeCoordinator>(relaxed = true)
    private val link = ShareLinks.trackUrl(SharedTrack("Teardrop", "Massive Attack"))

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }
    private fun vm() = SharedTrackViewModel(SavedStateHandle(mapOf("link" to link)), music, player, likes)

    @Test fun `the track comes from the route link`() {
        assertThat(vm().track?.title).isEqualTo("Teardrop")
    }

    @Test fun `play shows an existing like and a double tap runs once`() = runTest(dispatcher) {
        val saved = mockk<Track> { every { stashLikedAt } returns 1L }
        coEvery { music.ensureTrackPersisted(any()) } returns 7L
        every { music.observeTrackById(7L) } returns flowOf(saved)
        val vm = vm()
        vm.play(); vm.play(); advanceUntilIdle()
        coVerify(exactly = 1) { music.ensureTrackPersisted(any()) }
        coVerify(exactly = 1) { player.setQueue(listOf(saved), 0, any()) }
        assertThat(vm.liked.value).isTrue()
    }
}
