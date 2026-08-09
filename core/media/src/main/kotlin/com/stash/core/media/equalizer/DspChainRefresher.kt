// DspChainRefresher.kt
package com.stash.core.media.equalizer

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import androidx.media3.exoplayer.audio.AudioSink
import com.stash.core.media.equalizer.dsp.BassShelfProcessor
import com.stash.core.media.equalizer.dsp.EqProcessor
import com.stash.core.media.equalizer.dsp.LoudnessGainProcessor
import com.stash.core.media.equalizer.dsp.PreampProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Which DSP stages would be in the audio pipeline for a given pref state.
 *
 * Each flag mirrors the matching processor's `isActive()` gate (the
 * `wouldBeActive` companions are the single source of truth). Used two ways:
 * as the change signal for [watchDspActivation] — only membership *changes*
 * warrant a pipeline re-apply, slider moves within an active stage do not —
 * and as the limiter-needed input in `StashRenderersFactory`.
 */
data class DspActivation(
    val preamp: Boolean,
    val eq: Boolean,
    val bass: Boolean,
    val loudness: Boolean,
) {
    /** The limiter guards upstream gain; it rides along with any active stage. */
    val limiter: Boolean get() = preamp || eq || bass || loudness
}

fun dspActivation(eq: EqState, loudness: LoudnessState) = DspActivation(
    preamp = PreampProcessor.wouldBeActive(eq),
    eq = EqProcessor.wouldBeActive(eq),
    bass = BassShelfProcessor.wouldBeActive(eq),
    loudness = LoudnessGainProcessor.wouldBeActive(loudness),
)

/**
 * Re-applies [player]'s audio-processor chain whenever a DSP stage crosses its
 * active/inactive boundary, so a pref toggle takes effect mid-track even
 * though inactive processors are dropped from the pipeline entirely.
 *
 * Mechanism: a same-value `setSkipSilenceEnabled` is Media3's documented
 * "drain, then re-apply the processor chain" trigger — `DefaultAudioSink`
 * unconditionally schedules an after-drain `setupAudioProcessors()`, which
 * re-runs `AudioProcessingPipeline.flush()` and re-evaluates every
 * processor's `isActive()`. Gapless: pending audio is drained, not dropped.
 * The call is delivered via a [PlayerMessage] because the sink must only be
 * touched on the playback thread (the same route MediaCodecAudioRenderer
 * uses for MSG_SET_SKIP_SILENCE_ENABLED).
 *
 * Must be called on [player]'s application thread; [scope] must dispatch on
 * that same thread (the playback service's main scope).
 */
@OptIn(UnstableApi::class)
fun watchDspActivation(
    scope: CoroutineScope,
    eqController: EqController,
    loudnessController: LoudnessController,
    player: ExoPlayer,
    sink: AudioSink,
): Job = scope.launch {
    combine(eqController.state, loudnessController.state, ::dspActivation)
        .distinctUntilChanged()
        .drop(1) // the pipeline built at configure time already matches the initial state
        .collect { activation ->
            // runCatching: the service may release the player while a toggle is
            // in flight; a refresh on a dead player is a no-op, not a crash.
            runCatching {
                player.createMessage(
                    PlayerMessage.Target { _, _ ->
                        sink.setSkipSilenceEnabled(sink.getSkipSilenceEnabled())
                    }
                ).send()
                Log.d(TAG, "pipeline re-apply scheduled: $activation")
            }.onFailure { Log.w(TAG, "pipeline re-apply failed", it) }
        }
}

private const val TAG = "DspChain"
