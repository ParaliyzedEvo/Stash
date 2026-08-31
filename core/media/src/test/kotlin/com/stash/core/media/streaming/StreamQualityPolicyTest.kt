package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import com.stash.data.download.lossless.LosslessQualityTier
import com.stash.data.download.prefs.StreamingQualityPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class StreamQualityPolicyTest {

    private val connectivity = mockk<ConnectivityMonitor>()
    private val prefs = mockk<StreamingQualityPreferences>()
    private val policy = StreamQualityPolicy(connectivity, prefs)

    private fun setup(
        cellular: Boolean,
        wifi: LosslessQualityTier = LosslessQualityTier.MAX,
        cell: LosslessQualityTier = LosslessQualityTier.CD,
        saveData: Boolean = false,
    ) {
        every { connectivity.isCellular() } returns cellular
        coEvery { prefs.wifiTierNow() } returns wifi
        coEvery { prefs.cellularTierNow() } returns cell
        coEvery { prefs.saveDataNow() } returns saveData
    }

    @Test fun `wifi uses wifi tier`() = runTest {
        setup(cellular = false, wifi = LosslessQualityTier.MAX)
        assertThat(policy.streamingTier()).isEqualTo(LosslessQualityTier.MAX)
    }

    @Test fun `cellular uses cellular tier`() = runTest {
        setup(cellular = true, cell = LosslessQualityTier.CD)
        assertThat(policy.streamingTier()).isEqualTo(LosslessQualityTier.CD)
    }

    /**
     * Save Data must reach the LOSSY floor, not merely the lowest lossless tier.
     *
     * These asserted CD before, which is still FLAC (~28 MB / 4 min), so the
     * setting saved a user on a metered plan essentially nothing. Every lossless
     * provider serves FLAC at CD. Qobuz format_id 5 (MP3 320, ~10 MB) is the real floor,
     * verified live against the API.
     */
    @Test fun `save data drops to the lossy floor on wifi`() = runTest {
        setup(cellular = false, wifi = LosslessQualityTier.MAX, saveData = true)
        assertThat(policy.streamingTier()).isEqualTo(LosslessQualityTier.DATA_SAVER)
    }

    @Test fun `save data drops to the lossy floor on cellular`() = runTest {
        setup(cellular = true, cell = LosslessQualityTier.HI_RES, saveData = true)
        assertThat(policy.streamingTier()).isEqualTo(LosslessQualityTier.DATA_SAVER)
    }

    /** The floor is what actually gets requested from Qobuz: 5 = MP3 320. */
    @Test fun `the lossy floor requests qobuz format 5`() {
        assertThat(LosslessQualityTier.DATA_SAVER.qobuzCode).isEqualTo(5)
    }

    /** Save Data overrides an explicit high tier rather than being averaged with it. */
    @Test fun `save data overrides the user's chosen tier`() = runTest {
        setup(cellular = false, wifi = LosslessQualityTier.MAX, saveData = true)
        assertThat(policy.streamingTier()).isNotEqualTo(LosslessQualityTier.MAX)
    }
}
