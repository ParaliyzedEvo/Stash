// LoudnessGainProcessor.kt
package com.stash.core.media.equalizer.dsp

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.stash.core.media.equalizer.LoudnessController
import java.nio.ByteBuffer
import kotlin.math.pow

/**
 * Per-buffer linear-gain stage driven by [LoudnessController.state].
 *
 * Reads `currentTrackGainDb` once per buffer (DSP threads can't suspend, so we
 * sample the StateFlow with `.value`) and applies it to PCM_16BIT samples.
 *
 * Track transitions push a new target gain via the Player.Listener path; this
 * processor smooths the jump across a ~15 ms ramp (≈661 samples @ 44.1 kHz,
 * ≈720 @ 48 kHz) so listeners don't hear a click. The ramp is sample-counted,
 * not time-based, which keeps the math deterministic and trivially unit-
 * testable.
 *
 * When `state.enabled == false` the gain target snaps to 1.0× and the ramp
 * runs as usual — flipping the toggle does *not* introduce a click because we
 * never bypass the multiply mid-buffer.
 */
@OptIn(UnstableApi::class)
class LoudnessGainProcessor(
  private val controller: LoudnessController,
) : BaseAudioProcessor() {

  private var currentLinearGain = 1f
  private var targetLinearGain = 1f
  private var rampSamplesRemaining = 0
  private var sampleRateHz = 44_100

  override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
    if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT)
      throw UnhandledAudioFormatException(inputAudioFormat)
    sampleRateHz = inputAudioFormat.sampleRate
    return inputAudioFormat
  }

  override fun queueInput(inputBuffer: ByteBuffer) {
    val state = controller.state.value
    val desiredLinear =
      if (state.enabled) 10f.pow(state.currentTrackGainDb / 20f) else 1f

    if (desiredLinear != targetLinearGain) {
      targetLinearGain = desiredLinear
      rampSamplesRemaining =
        (sampleRateHz * RAMP_MS / 1000).coerceAtLeast(1)
    }

    val step =
      if (rampSamplesRemaining > 0)
        (targetLinearGain - currentLinearGain) / rampSamplesRemaining
      else 0f

    val out = replaceOutputBuffer(inputBuffer.remaining())
    while (inputBuffer.remaining() >= 2) {
      val s = inputBuffer.short.toInt()
      val gained = (s * currentLinearGain).toInt()
        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
      out.putShort(gained.toShort())
      if (rampSamplesRemaining > 0) {
        currentLinearGain += step
        rampSamplesRemaining--
        if (rampSamplesRemaining == 0) currentLinearGain = targetLinearGain
      }
    }
    out.flip()
  }

  private companion object {
    const val RAMP_MS = 15
  }
}
