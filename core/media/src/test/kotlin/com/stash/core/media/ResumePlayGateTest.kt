package com.stash.core.media

import com.google.common.truth.Truth.assertThat
import com.stash.core.media.ResumePlayGate.Action
import org.junit.Test

/**
 * Unit tests for [ResumePlayGate] — the testable extract of the
 * "force playback to start after a Bluetooth / Android Auto resumption"
 * latch wired into [com.stash.core.media.service.StashPlaybackService].
 *
 * Covers the warm-process bug (queue restored but stayed paused) and the
 * reboot-safety rule (boot-time notification population must never play).
 */
class ResumePlayGateTest {

    @Test
    fun armedPlayRequest_idlePlayer_preparesThenPlays_once() {
        val gate = ResumePlayGate()
        gate.arm(isForPlayback = true)

        // Restored queue lands on an idle player → prepare + play.
        assertThat(gate.onTimelineChanged(windowCount = 3, isIdle = true))
            .isEqualTo(Action.PREPARE_THEN_PLAY)

        // One-shot: a later timeline change (e.g. queue edit) does nothing.
        assertThat(gate.onTimelineChanged(windowCount = 3, isIdle = true))
            .isEqualTo(Action.NONE)
    }

    @Test
    fun armedPlayRequest_nonIdlePlayer_justPlays() {
        val gate = ResumePlayGate()
        gate.arm(isForPlayback = true)

        assertThat(gate.onTimelineChanged(windowCount = 1, isIdle = false))
            .isEqualTo(Action.PLAY)
    }

    @Test
    fun notForPlayback_neverPlays_evenWhenQueueLands() {
        // Boot-time notification population: items land on the player but
        // the device must NOT start playing on its own.
        val gate = ResumePlayGate()
        gate.arm(isForPlayback = false)

        assertThat(gate.onTimelineChanged(windowCount = 5, isIdle = true))
            .isEqualTo(Action.NONE)
    }

    @Test
    fun emptyIntermediateTimeline_keepsGateArmed() {
        val gate = ResumePlayGate()
        gate.arm(isForPlayback = true)

        // An empty timeline change before the queue lands must not consume
        // the latch — otherwise the real queue would arrive disarmed.
        assertThat(gate.onTimelineChanged(windowCount = 0, isIdle = true))
            .isEqualTo(Action.NONE)
        assertThat(gate.onTimelineChanged(windowCount = 4, isIdle = true))
            .isEqualTo(Action.PREPARE_THEN_PLAY)
    }

    @Test
    fun neverArmed_doesNothing() {
        // Normal in-app setQueue: the gate is never armed, so a timeline
        // change must not hijack playback.
        val gate = ResumePlayGate()

        assertThat(gate.onTimelineChanged(windowCount = 2, isIdle = true))
            .isEqualTo(Action.NONE)
    }

    @Test
    fun rearmingAfterDisarm_works() {
        val gate = ResumePlayGate()

        // A false (boot) arm followed by a real play arm must play.
        gate.arm(isForPlayback = false)
        gate.arm(isForPlayback = true)

        assertThat(gate.onTimelineChanged(windowCount = 1, isIdle = false))
            .isEqualTo(Action.PLAY)
    }
}
