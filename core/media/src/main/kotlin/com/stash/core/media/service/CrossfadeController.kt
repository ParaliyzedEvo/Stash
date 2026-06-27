package com.stash.core.media.service

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.stash.core.data.prefs.CrossfadePreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Drives an equal-power crossfade on **automatic** track-end → next-track
 * transitions only ("A owns the queue, B fades the incoming in").
 *
 * Player A ([playerA]) is the existing service player and stays the sole
 * MediaSession/queue owner. Player B is a transient, controller-invisible
 * second [ExoPlayer], built lazily via [buildPlayerB] on first fade and pooled
 * thereafter. When a fade arms, A keeps playing the **outgoing** track
 * untouched (its volume ramps down — no decoder hand-off on the audible tail,
 * and Now Playing stays on the outgoing) while B plays the pre-buffered
 * **incoming** track from 0 and ramps up. At the end, A advances the queue onto
 * the incoming and seeks to where B is (Now Playing switches here); a short
 * micro-fade B→A masks the decoders' position drift, then B returns to the
 * pool with A at full volume on the new track.
 *
 * Every condition is re-checked each [onProgress] tick via the pure
 * [shouldArm]; the actual ramp uses the pure [equalPowerVolumes]. Any manual
 * transport ([cancelFade]) or unmet condition degrades to today's hard cut.
 *
 * All player access happens on [scope] (the service main scope); B operations
 * are guarded by the [fading] flag.
 */
@OptIn(UnstableApi::class)
class CrossfadeController(
    private val playerA: ExoPlayer,
    private val buildPlayerB: () -> ExoPlayer,
    crossfadePreference: CrossfadePreference,
    private val scope: CoroutineScope,
) {
    @Volatile private var enabled = false
    @Volatile private var durationMs = 6000L

    private var playerB: ExoPlayer? = null
    private var fadeJob: Job? = null

    @Volatile private var fading = false

    /**
     * Set just before the controller's own [Player.seekToNextMediaItem] so the
     * service's transition listener can tell that SEEK apart from a user skip
     * (both produce `MEDIA_ITEM_TRANSITION_REASON_SEEK`). Consumed once.
     */
    @Volatile private var selfSeek = false

    @Volatile private var lastArmLogMs = 0L

    init {
        scope.launch { crossfadePreference.enabled.collect { enabled = it; android.util.Log.i("Crossfade", "enabled<-$it") } }
        scope.launch { crossfadePreference.durationMs.collect { durationMs = it; android.util.Log.i("Crossfade", "durationMs<-$it") } }
    }

    /** True while a fade was started by this controller's own next-seek. Consumed once. */
    fun consumeSelfSeek(): Boolean {
        val v = selfSeek
        selfSeek = false
        return v
    }

    /**
     * Position-poll hook. Builds [ArmInputs] from the live player state and
     * starts the fade when [shouldArm] holds. No-ops cheaply when disabled or
     * already fading, so it is safe to call on every tick.
     */
    fun onProgress(positionMs: Long, durationMs: Long, hasResolvedNext: Boolean, repeatMode: Int) {
        // TEMP instrumentation: log the arm decision near track end.
        if (durationMs > 0) {
            val remaining = durationMs - positionMs
            if (remaining in 0..20_000) {
                val now = System.currentTimeMillis()
                if (now - lastArmLogMs > 900) {
                    lastArmLogMs = now
                    android.util.Log.i(
                        "Crossfade",
                        "onProgress enabled=$enabled fading=$fading remaining=$remaining dur=$durationMs hasNext=$hasResolvedNext repeat=$repeatMode xfadeMs=${this.durationMs}",
                    )
                }
            }
        }
        if (fading || !enabled) return
        if (durationMs <= 0) return
        val inputs = ArmInputs(
            enabled = enabled,
            repeatOne = repeatMode == Player.REPEAT_MODE_ONE,
            hasResolvedNext = hasResolvedNext,
            remainingMs = durationMs - positionMs,
            trackDurationMs = durationMs,
            crossfadeMs = this.durationMs,
        )
        if (shouldArm(inputs)) startFade()
    }

    private fun startFade() {
        val nextIndex = playerA.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return
        val incoming = runCatching { playerA.getMediaItemAt(nextIndex) }.getOrNull() ?: return
        fading = true
        fadeJob = scope.launch {
            val b = playerB ?: buildPlayerB().also { playerB = it }
            // Prime B on the INCOMING from 0 and buffer it before starting, so
            // the fade-in is clean. The OUTGOING stays on A untouched — no
            // decoder hand-off on the audible tail — and A keeps owning the
            // MediaSession, so Now Playing stays on the outgoing for the whole
            // fade and only switches to the incoming at the hand-off below.
            b.volume = 0f
            b.setMediaItem(incoming)
            b.seekTo(0)
            b.playWhenReady = false
            b.prepare()
            if (!awaitReady(b, READY_TIMEOUT_MS)) {
                // B couldn't buffer in time → degrade to a normal hard cut.
                b.stop(); b.clearMediaItems()
                fading = false
                return@launch
            }
            // Fade length tracks the outgoing's remaining time (now that B has
            // buffered), minus a margin so A doesn't reach its natural end (and
            // auto-advance on its own) mid-hand-off.
            val remaining = playerA.duration - playerA.currentPosition
            val fadeMs = minOf(durationMs, remaining - HANDOFF_MARGIN_MS)
            if (fadeMs < MIN_FADE_MS) {
                b.stop(); b.clearMediaItems()
                fading = false
                return@launch
            }
            android.util.Log.i("Crossfade", "fire fadeMs=$fadeMs remaining=$remaining incoming=${incoming.mediaId} stateB=${b.playbackState}")
            b.playWhenReady = true
            // Equal-power overlap: A (outgoing) ramps down, B (incoming) up.
            var elapsed = 0L
            while (elapsed < fadeMs) {
                val (out, inc) = equalPowerVolumes(elapsed.toFloat() / fadeMs)
                playerA.volume = out
                b.volume = inc
                if (elapsed % 1000L < STEP_MS) {
                    android.util.Log.i(
                        "Crossfade",
                        "t=$elapsed Avol=$out stateA=${playerA.playbackState} isPlayA=${playerA.isPlaying} | Bvol=$inc stateB=${b.playbackState} isPlayB=${b.isPlaying}",
                    )
                }
                delay(STEP_MS)
                elapsed += STEP_MS
            }
            playerA.volume = 0f
            b.volume = 1f
            // Hand back to A: advance the queue onto the incoming (Now Playing
            // switches here — the fade is done) and seek A to where B is, kept
            // silent until it has buffered. A short micro-fade B→A then masks
            // the small position drift between the two decoders.
            val handoffPos = b.currentPosition
            selfSeek = true
            playerA.seekToNextMediaItem()
            playerA.seekTo(handoffPos)
            playerA.volume = 0f
            awaitReady(playerA, READY_TIMEOUT_MS)
            android.util.Log.i("Crossfade", "handoff pos=$handoffPos stateA=${playerA.playbackState} item=${playerA.currentMediaItem?.mediaId}")
            var m = 0L
            while (m < MICRO_FADE_MS) {
                val (bv, av) = equalPowerVolumes(m.toFloat() / MICRO_FADE_MS)
                b.volume = bv
                playerA.volume = av
                delay(STEP_MS)
                m += STEP_MS
            }
            playerA.volume = 1f
            b.stop(); b.clearMediaItems()
            fading = false
            android.util.Log.i("Crossfade", "done")
        }
    }

    /** Polls B for [Player.STATE_READY] up to [timeoutMs]; true if it got there. */
    private suspend fun awaitReady(b: ExoPlayer, timeoutMs: Long): Boolean {
        var waited = 0L
        while (b.playbackState != Player.STATE_READY && waited < timeoutMs) {
            delay(READY_POLL_MS)
            waited += READY_POLL_MS
        }
        return b.playbackState == Player.STATE_READY
    }

    /** Cancels any pending/in-flight fade and restores A to full volume. */
    fun cancelFade() {
        fadeJob?.cancel()
        fadeJob = null
        playerB?.let { it.stop(); it.clearMediaItems() }
        playerA.volume = 1f
        fading = false
    }

    /** Propagate A's pause to B while fading. */
    fun onPause() {
        if (fading) playerB?.playWhenReady = false
    }

    /** Propagate A's resume to B while fading. */
    fun onResume() {
        if (fading) playerB?.playWhenReady = true
    }

    /** Release the pooled B. Call from the service's player-release path. */
    fun release() {
        fadeJob?.cancel()
        fadeJob = null
        playerB?.release()
        playerB = null
    }

    private companion object {
        const val STEP_MS = 50L
        const val READY_POLL_MS = 20L

        /** Max wait for a player to buffer to STATE_READY before a fade/hand-off. */
        const val READY_TIMEOUT_MS = 2000L

        /** Outgoing slack left when the main ramp ends, so A can hand off before its natural end. */
        const val HANDOFF_MARGIN_MS = 500L

        /** Below this much usable outgoing tail, skip the fade (hard cut). */
        const val MIN_FADE_MS = 800L

        /** Short B→A blend on the incoming to mask the two decoders' position drift. */
        const val MICRO_FADE_MS = 150L
    }
}
