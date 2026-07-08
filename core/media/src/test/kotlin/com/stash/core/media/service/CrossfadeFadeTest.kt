package com.stash.core.media.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossfadeFadeTest {
    @Test fun `equal power endpoints`() {
        val (out0, in0) = equalPowerVolumes(0f)
        assertEquals(1f, out0, 0.001f); assertEquals(0f, in0, 0.001f)
        val (out1, in1) = equalPowerVolumes(1f)
        assertEquals(0f, out1, 0.001f); assertEquals(1f, in1, 0.001f)
    }

    @Test fun `equal power is constant power`() {
        for (t in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val (o, i) = equalPowerVolumes(t)
            assertEquals("power at t=$t", 1f, o * o + i * i, 0.001f)
        }
    }

    @Test fun `arm only when all conditions hold`() {
        val base = ArmInputs(
            enabled = true, repeatOne = false, hasResolvedNext = true,
            remainingMs = 5000, trackDurationMs = 200_000, crossfadeMs = 6000,
        )
        assertTrue(shouldArm(base))
        assertFalse(shouldArm(base.copy(enabled = false)))
        assertFalse(shouldArm(base.copy(repeatOne = true)))
        assertFalse(shouldArm(base.copy(hasResolvedNext = false)))
        assertFalse("remaining > duration", shouldArm(base.copy(remainingMs = 7000)))
        assertFalse("track too short", shouldArm(base.copy(trackDurationMs = 10_000)))
    }
}
