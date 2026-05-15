// LoudnessController.kt
package com.stash.core.media.equalizer

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The single writer of [LoudnessState]. UI emits user-driven changes here
 * (enable toggle); the per-track gain follows the Player.Listener path and
 * lands via [setCurrentTrackGain]. AudioProcessors read [state] on every
 * buffer.
 *
 * Construction performs a synchronous (`runBlocking`) read from disk so the
 * controller is fully restored before any AudioProcessor is built — the Hilt
 * graph guarantees ordering by declaring controller as a constructor
 * dependency of the processors. Mirrors [EqController]'s init pattern to
 * avoid the same boot-time race.
 *
 * User-driven persistence is debounced 200 ms so slider drags don't flood
 * DataStore; [flush] is called from app `onPause` to force an immediate
 * write.
 */
@Singleton
class LoudnessController @Inject constructor(
  private val store: LoudnessStore,
) {
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val _state = MutableStateFlow(LoudnessState())
  val state: StateFlow<LoudnessState> = _state.asStateFlow()

  private var pendingWrite: Job? = null
  @Volatile private var initDone = false

  init {
    runBlocking {
      _state.value = store.read()
      initDone = true
    }
  }

  /** Test helper. */
  internal suspend fun awaitInit() {
    while (!initDone) delay(1)
  }

  fun setEnabled(enabled: Boolean) = update { it.copy(enabled = enabled) }

  /**
   * Push the per-track gain calculated for the now-playing item. Updates both
   * `currentTrackGainDb` and `currentTargetGainDb` so the DSP layer can read a
   * single source of truth; the smoothing ramp inside
   * `LoudnessGainProcessor` follows the target.
   */
  fun setCurrentTrackGain(gainDb: Float) = update {
    it.copy(currentTrackGainDb = gainDb, currentTargetGainDb = gainDb)
  }

  /** Force an immediate persist — call from app pause/stop. */
  suspend fun flush() {
    pendingWrite?.cancel()
    store.write(_state.value)
  }

  private fun update(transform: (LoudnessState) -> LoudnessState) {
    _state.value = transform(_state.value)
    pendingWrite?.cancel()
    pendingWrite = scope.launch {
      delay(DEBOUNCE_MS)
      store.write(_state.value)
    }
  }

  companion object {
    private const val DEBOUNCE_MS = 200L
  }
}
