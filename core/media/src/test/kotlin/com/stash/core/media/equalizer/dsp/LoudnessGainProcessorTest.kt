// LoudnessGainProcessorTest.kt
package com.stash.core.media.equalizer.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import com.google.common.truth.Truth.assertThat
import com.stash.core.media.equalizer.LoudnessController
import com.stash.core.media.equalizer.LoudnessState
import com.stash.core.media.equalizer.LoudnessStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

@OptIn(ExperimentalCoroutinesApi::class)
class LoudnessGainProcessorTest {

  private lateinit var store: LoudnessStore
  private lateinit var controller: LoudnessController

  private val sampleRate = 44100
  private val rampSamples = sampleRate * 15 / 1000  // 661

  @Before fun setUp() {
    Dispatchers.setMain(StandardTestDispatcher())
    store = mockk(relaxed = true)
    // Default: enabled with 0 dB gain. Individual tests override via setCurrentTrackGain.
    coEvery { store.read() } returns LoudnessState(enabled = true, currentTrackGainDb = 0f, currentTargetGainDb = 0f)
    controller = LoudnessController(store)
    runBlocking { controller.awaitInit() }
  }

  @After fun tearDown() { Dispatchers.resetMain() }

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

  private fun makeProcessor(): LoudnessGainProcessor {
    val p = LoudnessGainProcessor(controller)
    p.configure(AudioFormat(sampleRate, 1, C.ENCODING_PCM_16BIT))
    p.flush()
    return p
  }

  // ----- Tests -----

  @Test(expected = UnhandledAudioFormatException::class)
  fun nonPcm16Input_throwsUnhandledFormat() {
    val p = LoudnessGainProcessor(controller)
    p.configure(AudioFormat(sampleRate, 1, C.ENCODING_PCM_FLOAT))
  }

  @Test fun disabled_passesSamplesThroughUnchanged() {
    // Re-init controller with disabled state and non-zero gain (proving gain wouldn't apply).
    coEvery { store.read() } returns
        LoudnessState(enabled = false, currentTrackGainDb = 6f, currentTargetGainDb = 6f)
    controller = LoudnessController(store)
    runBlocking { controller.awaitInit() }

    val p = makeProcessor()
    val inSamples = shortArrayOf(0x1000, -0x0800, 0x2000, -0x4000)
    p.queueInput(pcm16Buffer(inSamples))
    val outSamples = readAll(p.getOutput())
    assertThat(outSamples.toList())
        .containsExactly(0x1000.toShort(), (-0x0800).toShort(), 0x2000.toShort(), (-0x4000).toShort())
        .inOrder()
  }

  @Test fun positiveGain_amplifiesSamples() {
    controller.setCurrentTrackGain(6.0205999f)  // ~ 2× linear
    val p = makeProcessor()

    // Drain the ramp by feeding well more than rampSamples worth of input.
    val total = rampSamples + 200
    val inSamples = ShortArray(total) { 1000 }
    p.queueInput(pcm16Buffer(inSamples))
    val outSamples = readAll(p.getOutput())

    // Sample taken from well past the ramp should be ~ 2 × 1000 = 2000.
    val tailSample = outSamples[outSamples.size - 1].toInt()
    assertThat(tailSample).isWithin(2).of(2000)
  }

  @Test fun negativeGain_attenuatesSamples() {
    controller.setCurrentTrackGain(-6.0205999f)  // ~ 0.5× linear
    val p = makeProcessor()

    val total = rampSamples + 200
    val inSamples = ShortArray(total) { 2000 }
    p.queueInput(pcm16Buffer(inSamples))
    val outSamples = readAll(p.getOutput())

    val tailSample = outSamples[outSamples.size - 1].toInt()
    assertThat(tailSample).isWithin(2).of(1000)
  }

  @Test fun gainChange_rampsOverConfiguredSamples() {
    // Initial gain 0 dB (×1.0). Feed a buffer to settle, then bump to +12 dB (×~3.98).
    controller.setCurrentTrackGain(0f)
    val p = makeProcessor()
    val warmup = ShortArray(100) { 1000 }
    p.queueInput(pcm16Buffer(warmup))
    p.getOutput()  // drain

    // Now request the gain change. The new gain takes effect on the next queueInput call.
    controller.setCurrentTrackGain(12f)
    val ramp = ShortArray(rampSamples + 50) { 1000 }
    p.queueInput(pcm16Buffer(ramp))
    val outSamples = readAll(p.getOutput())

    val targetLinear = Math.pow(10.0, 12.0 / 20.0).toFloat()  // ~3.981
    // currentLinearGain advances by step per sample, starting at 1.0.
    // step = (target - 1.0) / rampSamples. After k samples, gain = 1.0 + k*step.
    val step = (targetLinear - 1f) / rampSamples
    val checkPoints = intArrayOf(rampSamples / 4, rampSamples / 2, (rampSamples * 3) / 4)
    for (k in checkPoints) {
      // Output[k] corresponds to a sample multiplied by the gain *at that step*.
      // Sample index k means gain has advanced k times before the multiply on iter k+1, so
      // expected output ≈ 1000 * (1.0 + k*step) — let tolerance absorb the off-by-one.
      val expected = 1000f * (1f + k * step)
      assertThat(outSamples[k].toInt().toFloat()).isWithin(expected * 0.05f + 5f).of(expected)
    }

    // After the ramp, output should be ~ 3981.
    val tail = outSamples[outSamples.size - 1].toInt()
    assertThat(tail).isWithin(10).of((1000f * targetLinear).toInt())
  }

  @Test fun extremePositiveGain_doesNotOverflow() {
    controller.setCurrentTrackGain(24f)  // ~ 15.85× linear
    val p = makeProcessor()

    val total = rampSamples + 200
    val inSamples = ShortArray(total) { 30000 }
    p.queueInput(pcm16Buffer(inSamples))
    val outSamples = readAll(p.getOutput())

    // The clamp must hold for all samples after the ramp completes.
    for (i in (rampSamples + 50) until outSamples.size) {
      assertThat(outSamples[i]).isEqualTo(Short.MAX_VALUE)
    }
    // And no negative-overflow sentinels:
    for (s in outSamples) assertThat(s).isAtLeast(0)
  }
}
