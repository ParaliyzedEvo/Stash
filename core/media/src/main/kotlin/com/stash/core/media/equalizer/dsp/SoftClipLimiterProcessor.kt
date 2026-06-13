// SoftClipLimiterProcessor.kt
package com.stash.core.media.equalizer.dsp

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp

/**
 * Lookahead sample-peak limiter (one-pole attack/release) sitting after the
 * loudness-gain stage. Catches the occasional positive-gain transient that
 * sneaks past true-peak headroom and clamps the output at ~ -1 dBFS so the
 * Android mixer never sees a 0 dBFS overshoot.
 *
 * - Threshold: 0.891 (≈ −1 dBFS).
 * - Lookahead: 2 ms → ring of `sampleRate * 2 / 1000` frames per channel.
 * - Attack:  1 ms one-pole time constant.
 * - Release: 50 ms one-pole time constant.
 *
 * The ring buffer introduces a deterministic delay equal to its frame count;
 * for the first `ringBuffer.size` output samples we emit zeros (prefill) so
 * the limiter has visibility on the upcoming peak before any real audio
 * leaves the processor.
 *
 * Pure DSP — no controller, no Hilt. Constructor is parameterless.
 */
@OptIn(UnstableApi::class)
class SoftClipLimiterProcessor : BaseAudioProcessor() {

  private lateinit var ringBuffer: ShortArray
  private lateinit var windowMax: SlidingWindowMax
  private var ringWrite = 0
  private var currentGain = 1.0f
  private var attackCoeff = 0f
  private var releaseCoeff = 0f
  private var prefillSamples = 0

  override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
    if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT)
      throw UnhandledAudioFormatException(inputAudioFormat)
    val frames = (inputAudioFormat.sampleRate * LOOKAHEAD_MS / 1000f).toInt().coerceAtLeast(1)
    ringBuffer = ShortArray(frames * inputAudioFormat.channelCount)
    windowMax = SlidingWindowMax(ringBuffer.size)
    attackCoeff = expCoeff(ATTACK_MS, inputAudioFormat.sampleRate)
    releaseCoeff = expCoeff(RELEASE_MS, inputAudioFormat.sampleRate)
    ringWrite = 0
    currentGain = 1f
    prefillSamples = ringBuffer.size
    return inputAudioFormat
  }

  override fun queueInput(inputBuffer: ByteBuffer) {
    val out = replaceOutputBuffer(inputBuffer.remaining())
    while (inputBuffer.remaining() >= 2) {
      val sample = inputBuffer.short

      // Read the oldest sample BEFORE overwriting it. In a single-write-pointer
      // ring buffer, `ringWrite` always indexes the oldest entry (the slot
      // we're about to overwrite). Reading first preserves the lookahead delay
      // and avoids losing the first ringBuffer.size samples of input.
      val delayed = ringBuffer[ringWrite]
      ringBuffer[ringWrite] = sample
      ringWrite = (ringWrite + 1) % ringBuffer.size

      // O(1) sliding max instead of rescanning the whole ring per sample —
      // the rescan was O(sampleRate²) overall and starved the playback
      // thread at 192kHz (audible underrun crackle on hi-res FLAC).
      windowMax.push(abs(sample.toInt()))
      val peakAbs = windowMax.max() / 32768f
      val targetGain = if (peakAbs > THRESHOLD) THRESHOLD / peakAbs else 1f
      val coeff = if (targetGain < currentGain) attackCoeff else releaseCoeff
      currentGain += (targetGain - currentGain) * coeff

      if (prefillSamples > 0) {
        prefillSamples--
        out.putShort(0)
      } else {
        val limited = (delayed * currentGain).toInt()
          .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        out.putShort(limited.toShort())
      }
    }
    out.flip()
  }

  private fun expCoeff(ms: Float, sampleRate: Int): Float =
    1f - exp(-1f / (sampleRate * ms / 1000f))

  private companion object {
    const val THRESHOLD = 0.891f
    const val ATTACK_MS = 1f
    const val RELEASE_MS = 50f
    const val LOOKAHEAD_MS = 2f
  }
}
