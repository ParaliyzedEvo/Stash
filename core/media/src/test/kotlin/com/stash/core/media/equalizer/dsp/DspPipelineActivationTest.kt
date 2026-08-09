// DspPipelineActivationTest.kt
package com.stash.core.media.equalizer.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import com.google.common.collect.ImmutableList
import com.google.common.truth.Truth.assertThat
import com.stash.core.media.equalizer.EqController
import com.stash.core.media.equalizer.EqState
import com.stash.core.media.equalizer.LoudnessController
import com.stash.core.media.equalizer.LoudnessState
import com.stash.core.media.equalizer.dspActivation
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test

/**
 * Integration pin against the REAL Media3 [AudioProcessingPipeline]: inactive
 * processors must be dropped from the pipeline, and activation changes must be
 * picked up by a bare `flush()` — no `configure()` — because that is exactly
 * what `DefaultAudioSink` does when it re-applies the chain mid-stream
 * (`setupAudioProcessors()` after a drain). If this test breaks on a Media3
 * upgrade, the flush-on-toggle mechanism broke with it.
 */
class DspPipelineActivationTest {

  private val eqFlow = MutableStateFlow(EqState())                       // defaults: everything off
  private val loudFlow = MutableStateFlow(LoudnessState(enabled = false))

  private fun buildPipeline(): AudioProcessingPipeline {
    val eq = mockk<EqController>().also { every { it.state } returns eqFlow }
    val loud = mockk<LoudnessController>().also { every { it.state } returns loudFlow }
    // Same order and limiter gating as StashRenderersFactory.buildAudioProcessors.
    val processors = ImmutableList.of<AudioProcessor>(
      PreampProcessor(eq),
      EqProcessor(eq),
      BassShelfProcessor(eq),
      LoudnessGainProcessor(loud),
      SoftClipLimiterProcessor { dspActivation(eqFlow.value, loudFlow.value).limiter },
    )
    return AudioProcessingPipeline(processors)
  }

  private fun configureAndFlush(pipeline: AudioProcessingPipeline) {
    pipeline.configure(AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
    pipeline.flush(AudioProcessor.StreamMetadata.DEFAULT)
  }

  @Test fun `pipeline is not operational when every stage is off`() {
    val pipeline = buildPipeline()
    configureAndFlush(pipeline)
    assertThat(pipeline.isOperational).isFalse()
  }

  @Test fun `enabling eq mid-stream joins the pipeline on a bare flush`() {
    val pipeline = buildPipeline()
    configureAndFlush(pipeline)
    assertThat(pipeline.isOperational).isFalse()

    eqFlow.value = EqState(enabled = true, gainsDb = floatArrayOf(6f, 0f, 0f, 0f, 0f))
    pipeline.flush(AudioProcessor.StreamMetadata.DEFAULT)  // no configure()
    assertThat(pipeline.isOperational).isTrue()
  }

  @Test fun `disabling eq mid-stream empties the pipeline on a bare flush`() {
    eqFlow.value = EqState(enabled = true, gainsDb = floatArrayOf(6f, 0f, 0f, 0f, 0f))
    val pipeline = buildPipeline()
    configureAndFlush(pipeline)
    assertThat(pipeline.isOperational).isTrue()

    eqFlow.value = EqState()
    pipeline.flush(AudioProcessor.StreamMetadata.DEFAULT)
    assertThat(pipeline.isOperational).isFalse()
  }

  @Test fun `loudness on keeps the pipeline operational with eq off`() {
    loudFlow.value = LoudnessState(enabled = true)
    val pipeline = buildPipeline()
    configureAndFlush(pipeline)
    assertThat(pipeline.isOperational).isTrue()
  }
}
