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

    @Test fun `save data forces CD on wifi`() = runTest {
        setup(cellular = false, wifi = LosslessQualityTier.MAX, saveData = true)
        assertThat(policy.streamingTier()).isEqualTo(LosslessQualityTier.CD)
    }

    @Test fun `save data forces CD on cellular`() = runTest {
        setup(cellular = true, cell = LosslessQualityTier.HI_RES, saveData = true)
        assertThat(policy.streamingTier()).isEqualTo(LosslessQualityTier.CD)
    }
}
