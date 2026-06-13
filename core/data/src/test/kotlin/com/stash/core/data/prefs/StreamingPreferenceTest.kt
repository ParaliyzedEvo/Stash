package com.stash.core.data.prefs

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Unit tests for [StreamingPreference].
 *
 * The preference wraps a dedicated DataStore for the online-streaming
 * engine toggle, cellular-allow toggle, and stream-quality tier. Each
 * test deletes the underlying file so runs stay isolated.
 */
@RunWith(RobolectricTestRunner::class)
class StreamingPreferenceTest {

    private lateinit var context: Context
    private lateinit var prefs: StreamingPreference
    private lateinit var file: File

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        file = context.preferencesDataStoreFile("streaming_preference")
        // Make sure no stale file from a prior run leaks in.
        if (file.exists()) file.delete()
        prefs = StreamingPreference(context)
    }

    @After fun tearDown() {
        if (file.exists()) file.delete()
    }

    @Test fun enabled_defaultsToFalse() = runTest {
        assertFalse(prefs.enabled.first())
    }

    @Test fun enabled_roundTrips() = runTest {
        prefs.setEnabled(true)
        assertTrue(prefs.enabled.first())
        prefs.setEnabled(false)
        assertFalse(prefs.enabled.first())
    }

    @Test fun streamOnCellular_defaultsToFalse() = runTest {
        assertFalse(prefs.streamOnCellular.first())
    }

    @Test fun streamOnCellular_roundTrips() = runTest {
        prefs.setStreamOnCellular(true)
        assertTrue(prefs.streamOnCellular.first())
        prefs.setStreamOnCellular(false)
        assertFalse(prefs.streamOnCellular.first())
    }

    @Test fun streamQuality_defaultsToLossless() = runTest {
        assertEquals(StreamQualityTier.LOSSLESS, prefs.streamQuality.first())
    }

    @Test fun streamQuality_roundTrips() = runTest {
        prefs.setStreamQuality(StreamQualityTier.HIGH_QUALITY_LOSSY)
        assertEquals(StreamQualityTier.HIGH_QUALITY_LOSSY, prefs.streamQuality.first())
        prefs.setStreamQuality(StreamQualityTier.LOSSLESS)
        assertEquals(StreamQualityTier.LOSSLESS, prefs.streamQuality.first())
    }

    @Test fun forceYouTubeFallback_defaultsToFalse() = runTest {
        assertFalse(prefs.forceYouTubeFallback.first())
        assertFalse(prefs.isForceYouTubeFallback())
    }

    @Test fun forceYouTubeFallback_roundTrips() = runTest {
        prefs.setForceYouTubeFallback(true)
        assertTrue(prefs.forceYouTubeFallback.first())
        assertTrue(prefs.isForceYouTubeFallback())
        prefs.setForceYouTubeFallback(false)
        assertFalse(prefs.forceYouTubeFallback.first())
        assertFalse(prefs.isForceYouTubeFallback())
    }

    @Test fun current_returnsLatestValue() = runTest {
        assertFalse(prefs.current())
        prefs.setEnabled(true)
        assertTrue(prefs.current())
        prefs.setEnabled(false)
        assertFalse(prefs.current())
    }
}
