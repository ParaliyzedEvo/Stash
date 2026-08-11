package com.stash.core.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #421 — strict-FLAC deferrals were invisible: they contributed nothing to
 * the Downloading phase, so a sync whose queue was all deferrals sat at
 * "Downloading 0/5" with a frozen bar until it silently ended. Deferred
 * tracks are RESOLVED work (the decision is "wait for a lossless source"),
 * so they must advance the phase's progress exactly like downloads and
 * failures do.
 */
class SyncPhaseDeferredTest {

    @Test
    fun `deferred tracks advance the downloading progress`() {
        // 0 downloaded, 5 deferred of 5 total: the phase is DONE, not stuck at its base.
        val allDeferred = SyncPhase.Downloading(downloaded = 0, total = 5, deferred = 5)
        assertEquals(0.95f, allDeferred.progress, 0.0001f)

        // Half resolved (1 downloaded + 1 deferred of 4) = base + half the span.
        val mixed = SyncPhase.Downloading(downloaded = 1, total = 4, deferred = 1)
        assertEquals(0.30f + 0.65f * 0.5f, mixed.progress, 0.0001f)
    }

    @Test
    fun `progress without deferrals is unchanged`() {
        val half = SyncPhase.Downloading(downloaded = 2, total = 4)
        assertEquals(0.30f + 0.65f * 0.5f, half.progress, 0.0001f)

        val none = SyncPhase.Downloading(downloaded = 0, total = 0)
        assertEquals(0.30f, none.progress, 0.0001f)
    }
}
