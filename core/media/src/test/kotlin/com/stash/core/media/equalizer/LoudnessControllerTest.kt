// LoudnessControllerTest.kt
package com.stash.core.media.equalizer

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoudnessControllerTest {
  private val store = mockk<LoudnessStore>(relaxed = true)

  // Shared between Dispatchers.Main and runTest so the controller's async
  // init (init { scope.launch { ... } } on Main) is advanced by the same
  // scheduler the test drives — otherwise awaitInit() would never complete.
  private val dispatcher = StandardTestDispatcher()

  @OptIn(ExperimentalCoroutinesApi::class)
  @Before fun setUp() { Dispatchers.setMain(dispatcher) }

  @OptIn(ExperimentalCoroutinesApi::class)
  @After fun tearDown() { Dispatchers.resetMain() }

  @Test fun `init restores state from store after awaitInit`() = runTest(dispatcher) {
    coEvery { store.read() } returns LoudnessState(enabled = false, targetLufs = -11f)
    val ctrl = LoudnessController(store)
    ctrl.awaitInit()
    assertThat(ctrl.state.value.enabled).isFalse()
    assertThat(ctrl.state.value.targetLufs).isEqualTo(-11f)
  }

  @Test fun `setEnabled updates the state flow`() = runTest(dispatcher) {
    coEvery { store.read() } returns LoudnessState()
    val ctrl = LoudnessController(store)
    ctrl.awaitInit()
    ctrl.setEnabled(false)
    assertThat(ctrl.state.value.enabled).isFalse()
  }

  @Test fun `setCurrentTrackGain updates both current and target gain`() = runTest(dispatcher) {
    coEvery { store.read() } returns LoudnessState()
    val ctrl = LoudnessController(store)
    ctrl.awaitInit()
    ctrl.setCurrentTrackGain(6f)
    assertThat(ctrl.state.value.currentTrackGainDb).isEqualTo(6f)
    assertThat(ctrl.state.value.currentTargetGainDb).isEqualTo(6f)
  }

  @Test fun `setEnabled persists after debounce window`() = runTest(dispatcher) {
    coEvery { store.read() } returns LoudnessState()
    val ctrl = LoudnessController(store)
    ctrl.awaitInit()
    ctrl.setEnabled(false)
    advanceTimeBy(250)
    coVerify { store.write(match { it.enabled == false }) }
  }

  @Test fun `flush forces immediate persist regardless of debounce`() = runTest(dispatcher) {
    coEvery { store.read() } returns LoudnessState()
    val ctrl = LoudnessController(store)
    ctrl.awaitInit()
    ctrl.setEnabled(false)
    ctrl.flush()
    coVerify { store.write(any()) }
  }
}
