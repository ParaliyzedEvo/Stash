package com.stash.core.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Test

class CrossfadePreferencesClampTest {
    @Test fun `duration clamps below floor to 1000ms`() {
        assertEquals(1000L, clampCrossfadeMs(0L))
        assertEquals(1000L, clampCrossfadeMs(500L))
    }
    @Test fun `duration clamps above ceiling to 12000ms`() {
        assertEquals(12000L, clampCrossfadeMs(99999L))
    }
    @Test fun `duration in range is unchanged`() {
        assertEquals(6000L, clampCrossfadeMs(6000L))
    }
}
