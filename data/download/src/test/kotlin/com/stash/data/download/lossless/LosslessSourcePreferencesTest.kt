package com.stash.data.download.lossless

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.DownloadQueueDao
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LosslessSourcePreferencesTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = LosslessSourcePreferences(ctx, mockk<DownloadQueueDao>(relaxed = true))

    /** The preferencesDataStore delegate is process-wide; start each test clean (codebase convention). */
    @Before fun clear() = runBlocking {
        prefs.setCustomLosslessEndpoint(null)
        prefs.setArcodRescueDismissed(false)
        prefs.setLosslessOfflineDismissed(false)
    }

    @Test fun `custom endpoint is null by default, normalised on set, cleared on blank`() = runTest {
        assertThat(prefs.customLosslessEndpoint.first()).isNull()
        prefs.setCustomLosslessEndpoint("  https://relay.example.org/  ")
        assertThat(prefs.customLosslessEndpointNow()).isEqualTo("https://relay.example.org")
        prefs.setCustomLosslessEndpoint("http://insecure.example") // not https -> rejected
        assertThat(prefs.customLosslessEndpointNow()).isNull()
        prefs.setCustomLosslessEndpoint("https://relay.example.org")
        prefs.setCustomLosslessEndpoint("   ")
        assertThat(prefs.customLosslessEndpointNow()).isNull()
    }

    /**
     * The new banner says something different from the retired ARCOD one, so
     * someone who dismissed that is still owed this one once. Two keys, never
     * shared — the ViewModel tests verify the CALL, not which key it writes,
     * so only this catches a copy-pasted key.
     */
    @Test fun `dismissing the old ARCOD banner does not dismiss the new one`() = runTest {
        prefs.setArcodRescueDismissed(true)
        assertThat(prefs.losslessOfflineDismissed.first()).isFalse()
        prefs.setLosslessOfflineDismissed(true)
        assertThat(prefs.losslessOfflineDismissed.first()).isTrue()
    }
}
