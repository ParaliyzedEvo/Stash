package com.stash.feature.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the Manage-playlists segment chips (All / Synced / Off).
 *
 * Issue #373: the chips filtered ONLY the "Your playlists" section. The Liked
 * row and every mix row got the text query but never the segment, so tapping
 * "Off" left rows with an ON switch on screen and the filter read as broken.
 *
 * The chips name a SYNC state, so they apply to exactly the rows whose switch
 * IS sync state — Liked and custom playlists. A mix row's switch is
 * `hideFromHome` ("shown on Home"), a different axis entirely, so a sync-state
 * segment can only honestly show mix rows under All.
 */
class ManageSegmentFilterTest {

    @Test fun allKeepsBothStates() {
        assertTrue(matchesSegment(ManageSegment.ALL, syncEnabled = true))
        assertTrue(matchesSegment(ManageSegment.ALL, syncEnabled = false))
    }

    @Test fun syncedKeepsOnlyEnabled() {
        assertTrue(matchesSegment(ManageSegment.SYNCED, syncEnabled = true))
        assertFalse(matchesSegment(ManageSegment.SYNCED, syncEnabled = false))
    }

    /** The reported bug: an enabled row must NOT survive the Off chip. */
    @Test fun offKeepsOnlyDisabled() {
        assertTrue(matchesSegment(ManageSegment.OFF, syncEnabled = false))
        assertFalse(matchesSegment(ManageSegment.OFF, syncEnabled = true))
    }

    // ── Mix rows: the same axis as every other row ────────────────────────────
    //
    // A mix row's switch used to be "shown on Home", so the sync chips had
    // nothing honest to say about it and mixes showed under All only. Since
    // 2026-09-16 the switch IS sync state (a switched-on mix downloads in
    // Download mode), so mixes filter exactly like playlists — matchesSegment,
    // pinned above. A separate mix rule would be a second source of truth.

    @Test fun mixRowsFilterLikePlaylists() {
        assertTrue(matchesSegment(ManageSegment.ALL, syncEnabled = false))
        assertTrue(matchesSegment(ManageSegment.SYNCED, syncEnabled = true))
        assertFalse(matchesSegment(ManageSegment.SYNCED, syncEnabled = false))
        assertTrue(matchesSegment(ManageSegment.OFF, syncEnabled = false))
    }

    /**
     * Every segment must be decidable — a chip that falls through to "show
     * everything" is how #373 looked to the reporter in the first place.
     */
    @Test fun everySegmentIsDecided() {
        for (segment in ManageSegment.entries) {
            val enabled = matchesSegment(segment, syncEnabled = true)
            val disabled = matchesSegment(segment, syncEnabled = false)
            if (segment != ManageSegment.ALL) {
                assertFalse("$segment must exclude one of the two states", enabled && disabled)
            }
        }
    }
}
