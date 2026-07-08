package com.stash.core.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [shouldPersistPosition] — the gate that keeps updateState's per-event
 * DataStore position writes down to meaningful changes only.
 */
class PositionPersistGateTest {

    @Test fun `track change always persists`() {
        assertTrue(shouldPersistPosition(trackChanged = true, indexChanged = false, pauseEdge = false, positionDeltaMs = 0L))
    }

    @Test fun `index change always persists`() {
        assertTrue(shouldPersistPosition(trackChanged = false, indexChanged = true, pauseEdge = false, positionDeltaMs = 0L))
    }

    @Test fun `pause edge always persists`() {
        assertTrue(shouldPersistPosition(trackChanged = false, indexChanged = false, pauseEdge = true, positionDeltaMs = 100L))
    }

    @Test fun `same position re-report is suppressed`() {
        assertFalse(shouldPersistPosition(trackChanged = false, indexChanged = false, pauseEdge = false, positionDeltaMs = 0L))
        assertFalse(shouldPersistPosition(trackChanged = false, indexChanged = false, pauseEdge = false, positionDeltaMs = POSITION_PERSIST_MIN_DELTA_MS - 1))
    }

    @Test fun `drift past threshold persists, either direction`() {
        assertTrue(shouldPersistPosition(trackChanged = false, indexChanged = false, pauseEdge = false, positionDeltaMs = POSITION_PERSIST_MIN_DELTA_MS))
        assertTrue(shouldPersistPosition(trackChanged = false, indexChanged = false, pauseEdge = false, positionDeltaMs = -POSITION_PERSIST_MIN_DELTA_MS))
    }
}
