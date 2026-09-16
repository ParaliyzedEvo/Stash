package com.stash.core.data.sync.workers

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit test for [shouldEnqueueForDownload] — the enqueue-side gate. It reads two
 * things and nothing else: the mode, and the playlist's own switch.
 *
 * Until 2026-09-16 it also read the playlist TYPE and refused every DAILY_MIX
 * (#368: auto-enabled mixes once pulled thousands of tracks nobody asked for).
 * The owner's rule now: in Download mode a switched-on row downloads, mixes
 * included. The #368 protection moved to where it belongs — discovered mixes
 * start switched OFF ([defaultSyncEnabled]) and this gate says no while they
 * stay that way. [DiffWorkerMixNoDownloadTest] proves the worker consults it.
 */
class ShouldEnqueueForDownloadTest {
    @Test fun `offline and switched on downloads`() {
        assertThat(shouldEnqueueForDownload(streamingMode = false, syncEnabled = true)).isTrue()
    }

    @Test fun `offline and switched off never downloads`() {
        assertThat(shouldEnqueueForDownload(streamingMode = false, syncEnabled = false)).isFalse()
    }

    @Test fun `online never enqueues, switched on or not`() {
        assertThat(shouldEnqueueForDownload(streamingMode = true, syncEnabled = true)).isFalse()
        assertThat(shouldEnqueueForDownload(streamingMode = true, syncEnabled = false)).isFalse()
    }
}
