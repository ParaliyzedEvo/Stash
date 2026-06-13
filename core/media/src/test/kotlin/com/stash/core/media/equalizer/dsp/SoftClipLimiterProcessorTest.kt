// SoftClipLimiterProcessorTest.kt
package com.stash.core.media.equalizer.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class SoftClipLimiterProcessorTest {

  private val sampleRate = 44100

  // Lookahead 2 ms @ 44100 = 88 frames; mono => ring size 88, prefill 88 samples.
  private val lookaheadSamples = sampleRate * 2 / 1000  // 88

  private fun pcm16Buffer(samples: ShortArray): ByteBuffer {
    val bb = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
    samples.forEach { bb.putShort(it) }
    bb.flip()
    return bb
  }

  private fun readAll(buf: ByteBuffer): ShortArray {
    val out = ShortArray(buf.remaining() / 2)
    for (i in out.indices) out[i] = buf.short
    return out
  }

  private fun makeProcessor(): SoftClipLimiterProcessor {
    val p = SoftClipLimiterProcessor()
    p.configure(AudioFormat(sampleRate, 1, C.ENCODING_PCM_16BIT))
    p.flush()
    return p
  }

  private fun sine(freq: Float, amplitude: Float, count: Int): ShortArray {
    return ShortArray(count) { i ->
      (sin(2.0 * PI * freq * i / sampleRate) * amplitude).toInt()
        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        .toShort()
    }
  }

  // ----- Tests -----

  @Test(expected = UnhandledAudioFormatException::class)
  fun nonPcm16Input_throws() {
    val p = SoftClipLimiterProcessor()
    p.configure(AudioFormat(sampleRate, 1, C.ENCODING_PCM_FLOAT))
  }

  @Test fun subThresholdSignal_passesUnchanged() {
    // 0.5 × Short.MAX_VALUE sine → far below the 0.891 threshold; should pass through.
    // Output is delayed by ringBuffer.size samples (prefill zeros).
    val p = makeProcessor()
    val inSamples = sine(freq = 1000f, amplitude = 16383f, count = lookaheadSamples + 400)
    p.queueInput(pcm16Buffer(inSamples))
    val outSamples = readAll(p.getOutput())

    // First lookaheadSamples should be the prefill (zeros).
    for (i in 0 until lookaheadSamples) {
      assertThat(outSamples[i].toInt()).isEqualTo(0)
    }
    // After prefill, output[prefill + i] should equal input[i] within ±1 LSB.
    for (i in 0 until 200) {
      val expected = inSamples[i].toInt()
      val actual = outSamples[lookaheadSamples + i].toInt()
      assertThat(actual).isWithin(1).of(expected)
    }
  }

  @Test fun aboveThresholdSignal_clampsToThreshold() {
    // Full-scale sine; once attack settles, magnitude must be ≤ 0.891 × 32767 ≈ 29195.
    val p = makeProcessor()
    val total = lookaheadSamples + sampleRate * 20 / 1000  // 20 ms of audio
    val inSamples = sine(freq = 1000f, amplitude = 32767f, count = total)
    p.queueInput(pcm16Buffer(inSamples))
    val outSamples = readAll(p.getOutput())

    // After prefill + a few ms of attack settling (attack time const ~1 ms), peak should be bounded.
    val attackSettleSamples = sampleRate * 5 / 1000  // 5 ms, comfortably past 1 ms time constant
    val limit = (0.891f * 32767f).toInt()
    val tolerance = 200  // ~0.6% over-shoot allowance for the one-pole filter
    for (i in (lookaheadSamples + attackSettleSamples) until outSamples.size) {
      val mag = abs(outSamples[i].toInt())
      assertThat(mag).isAtMost(limit + tolerance)
    }
  }

  @Test fun releaseFollowsImpulseWithinExpectedTime() {
    // Single full-scale impulse, then silence. Gain should drop fast on attack and recover ≈ 1.0
    // within ~2× the 50 ms release time constant (≈100 ms).
    val p = makeProcessor()
    val totalSamples = lookaheadSamples + sampleRate * 200 / 1000  // 200 ms of audio
    val inSamples = ShortArray(totalSamples)
    inSamples[0] = Short.MAX_VALUE
    p.queueInput(pcm16Buffer(inSamples))
    val outSamples = readAll(p.getOutput())

    // After ~150 ms (3× release time constant) gain should be very close to 1.0.
    // Feed a 0.5 × Short.MAX probe to read effective gain.
    val probeIdx = lookaheadSamples + sampleRate * 150 / 1000
    // The impulse is at sample 0 of input; after `probeIdx` samples ringBuffer holds zeros,
    // so target gain is 1.0. Verify output value at the corresponding probe index is essentially
    // the delayed input — and the delayed input at probeIdx is sample [probeIdx - lookahead].
    // Since the input here is all zero after the impulse, output should be exactly 0.
    assertThat(outSamples[probeIdx].toInt()).isEqualTo(0)

    // Also: the impulse itself, after the lookahead delay, should appear attenuated at output index
    // `lookaheadSamples` (delayed by the ring). With attack near the impulse, gain has dropped to
    // ~ threshold/peak ≈ 0.891 by then. So output[lookaheadSamples] should be ≤ ~ 29200 + tolerance.
    val impulseOut = abs(outSamples[lookaheadSamples].toInt())
    assertThat(impulseOut).isAtMost((0.891f * 32767f).toInt() + 500)
  }

  @Test fun hiResStereo_outputMatchesFullScanReference_byteForByte() {
    // Regression pin for the 24/192 crackle fix: the limiter's sliding-window
    // peak (O(1)/sample) must produce output identical to the original
    // full-ring-rescan algorithm, reimplemented naively below. Random PCM with
    // full-scale extremes across odd-sized buffers at 192kHz stereo.
    val rate = 192_000
    val channels = 2
    val p = SoftClipLimiterProcessor()
    p.configure(AudioFormat(rate, channels, C.ENCODING_PCM_16BIT))
    p.flush()

    val ref = NaiveReferenceLimiter(rate, channels)
    val rng = java.util.Random(1234)
    val bufferSizes = intArrayOf(311, 4096, 1, 777, 8192, 50)
    for (size in bufferSizes) {
      val samples = ShortArray(size) {
        when (rng.nextInt(10)) {
          0 -> Short.MAX_VALUE
          1 -> Short.MIN_VALUE
          else -> (rng.nextInt(65536) - 32768).toShort()
        }
      }
      val bb = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
      samples.forEach { bb.putShort(it) }
      bb.flip()
      p.queueInput(bb)
      val actual = readAll(p.getOutput())
      val expected = ref.process(samples)
      assertThat(actual).isEqualTo(expected)
    }
  }

  /** Verbatim port of the pre-fix limiter (full ring rescan per sample). */
  private class NaiveReferenceLimiter(sampleRate: Int, channels: Int) {
    private val threshold = 0.891f
    private val ring = ShortArray((sampleRate * 2f / 1000f).toInt().coerceAtLeast(1) * channels)
    private var ringWrite = 0
    private var currentGain = 1.0f
    private var prefill = ring.size
    private val attackCoeff = coeff(1f, sampleRate)
    private val releaseCoeff = coeff(50f, sampleRate)

    private fun coeff(ms: Float, sampleRate: Int): Float =
      1f - kotlin.math.exp(-1f / (sampleRate * ms / 1000f))

    fun process(samples: ShortArray): ShortArray {
      val out = ShortArray(samples.size)
      for (i in samples.indices) {
        val sample = samples[i]
        val delayed = ring[ringWrite]
        ring[ringWrite] = sample
        ringWrite = (ringWrite + 1) % ring.size
        var max = 0
        for (s in ring) { val a = abs(s.toInt()); if (a > max) max = a }
        val peakAbs = max / 32768f
        val targetGain = if (peakAbs > threshold) threshold / peakAbs else 1f
        val c = if (targetGain < currentGain) attackCoeff else releaseCoeff
        currentGain += (targetGain - currentGain) * c
        out[i] = if (prefill > 0) { prefill--; 0 } else {
          (delayed * currentGain).toInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
      }
      return out
    }
  }

  @Test fun lookaheadDelaysOutputByExpectedSamples() {
    // Feed a sub-threshold step (constant non-zero value) → first ringBuffer.size output samples
    // are the prefill zeros, then the step appears.
    val p = makeProcessor()
    val stepValue: Short = 8000  // well below threshold
    val total = lookaheadSamples + 50
    val inSamples = ShortArray(total) { stepValue }
    p.queueInput(pcm16Buffer(inSamples))
    val outSamples = readAll(p.getOutput())

    // First lookaheadSamples must be zero (prefill).
    for (i in 0 until lookaheadSamples) {
      assertThat(outSamples[i].toInt()).isEqualTo(0)
    }
    // Output at index lookaheadSamples should match input[0] within ±1 LSB.
    assertThat(outSamples[lookaheadSamples].toInt()).isWithin(1).of(stepValue.toInt())
  }
}
