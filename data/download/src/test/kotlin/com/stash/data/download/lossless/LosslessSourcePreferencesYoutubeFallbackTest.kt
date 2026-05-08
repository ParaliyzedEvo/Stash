package com.stash.data.download.lossless

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DataStore-backed unit tests for the new `youtubeFallbackEnabled` pref.
 *
 * Follows the EqStoreTest pattern (Robolectric + ApplicationProvider) since
 * `data:download` had no prior DataStore-pref test and the EqStore tests are
 * the established convention for hitting `preferencesDataStore` from JVM
 * unit tests in this project.
 */
@RunWith(RobolectricTestRunner::class)
class LosslessSourcePreferencesYoutubeFallbackTest {

    private lateinit var context: Context
    private lateinit var prefs: LosslessSourcePreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Wipe any prior DataStore state from earlier test runs.
        context.filesDir.resolve("datastore/lossless_source_preferences.preferences_pb")
            .delete()
        // `relaxed = true` (not `relaxUnitFun`) — `requeueWaitingForLossless()`
        // returns Int, not Unit, so the more permissive relaxed mode is needed.
        prefs = LosslessSourcePreferences(context, mockk(relaxed = true))
    }

    @Test
    fun `youtubeFallbackEnabled defaults to false`() = runTest {
        assertFalse(prefs.youtubeFallbackEnabledNow())
    }

    @Test
    fun `setYoutubeFallbackEnabled persists round-trip`() = runTest {
        prefs.setYoutubeFallbackEnabled(true)
        assertTrue(prefs.youtubeFallbackEnabledNow())
        prefs.setYoutubeFallbackEnabled(false)
        assertFalse(prefs.youtubeFallbackEnabledNow())
    }
}
