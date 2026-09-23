package com.stash.feature.library.share

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.entity.SharedMixEntity
import com.stash.core.data.share.SharePreference
import com.stash.core.data.share.ShareResult
import com.stash.core.data.share.SharedMixRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
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
class ShareMixViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<SharedMixRepository>(relaxed = true)
    private val pref = mockk<SharePreference>(relaxed = true)
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `not shared yet offers the form, create stores the name and shows the link`() = runTest(dispatcher) {
        coEvery { repo.observe(5) } returns flowOf(null)
        coEvery { pref.displayName() } returns "Rawn"
        coEvery { repo.share(5, "Sleep", "Rawn", true) } returns ShareResult.Ok("https://x/m/Kx7Qa2pL")
        val vm = ShareMixViewModel(repo, pref); vm.bind(5, "Ambient"); advanceUntilIdle()
        assertThat((vm.state.value as ShareMixUiState.NotShared).displayName).isEqualTo("Rawn")
        vm.create("Sleep", "Rawn", autoUpdate = true); advanceUntilIdle()
        coVerify { pref.setDisplayName("Rawn") }
        coVerify { repo.share(5, "Sleep", "Rawn", true) }
    }

    @Test fun `an owned share shows its link, a followed one shows the original link read-only`() = runTest(dispatcher) {
        coEvery { repo.observe(5) } returns flowOf(SharedMixEntity(5, "Kx7Qa2pL", SharedMixEntity.ROLE_OWNER, name = "Sleep", editKey = "k"))
        val owned = ShareMixViewModel(repo, pref); owned.bind(5, "Ambient"); advanceUntilIdle()
        val s = owned.state.value as ShareMixUiState.Shared
        assertThat(s.url).endsWith("/m/Kx7Qa2pL"); assertThat(s.canManage).isTrue()
        coEvery { repo.observe(6) } returns flowOf(SharedMixEntity(6, "BBBBBBBB", SharedMixEntity.ROLE_FOLLOWER, name = "F"))
        val followed = ShareMixViewModel(repo, pref); followed.bind(6, "F"); advanceUntilIdle()
        assertThat((followed.state.value as ShareMixUiState.Shared).canManage).isFalse()
    }

    @Test fun `a create that throws shows an error instead of crashing`() = runTest(dispatcher) {
        coEvery { repo.observe(5) } returns flowOf(null)
        coEvery { repo.share(any(), any(), any(), any()) } throws IOException("disk")
        val vm = ShareMixViewModel(repo, pref); vm.bind(5, "Ambient"); advanceUntilIdle()
        vm.create("Sleep", null, autoUpdate = true); advanceUntilIdle()
        val s = vm.state.value as ShareMixUiState.NotShared
        assertThat(s.working).isFalse(); assertThat(s.error).isNotNull()
    }

    @Test fun `a failed stop sharing keeps the link and shows an error`() = runTest(dispatcher) {
        coEvery { repo.observe(5) } returns flowOf(SharedMixEntity(5, "Kx7Qa2pL", SharedMixEntity.ROLE_OWNER, name = "Sleep", editKey = "k"))
        coEvery { repo.stopSharing(5) } returns false
        val vm = ShareMixViewModel(repo, pref); vm.bind(5, "Ambient"); advanceUntilIdle()
        var done = false
        vm.stopSharing { done = true }; advanceUntilIdle()
        assertThat(done).isFalse()
        assertThat((vm.state.value as ShareMixUiState.Shared).error).isNotNull()
    }
}
