package com.stash.core.data.prefs

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Like-mirroring opt-in prefs. Both MUST default to false: defaulting
 * Spotify on (like the vestigial heart_default_spotify does) would
 * silently start external writes for every existing user on update.
 */
@RunWith(RobolectricTestRunner::class)
class LikePreferencesMirrorTest {

    private lateinit var context: Context
    private lateinit var prefs: LikePreferences
    private lateinit var file: File

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        file = context.preferencesDataStoreFile("like_preferences")
        if (file.exists()) file.delete()
        prefs = LikePreferences(context)
    }

    @After fun tearDown() {
        if (file.exists()) file.delete()
    }

    @Test fun mirrorLikesSpotify_defaultsToFalse() = runTest {
        assertFalse(prefs.mirrorLikesSpotify.first())
        assertFalse(prefs.mirrorLikesSpotifyNow())
    }

    @Test fun mirrorLikesYtMusic_defaultsToFalse() = runTest {
        assertFalse(prefs.mirrorLikesYtMusic.first())
        assertFalse(prefs.mirrorLikesYtMusicNow())
    }

    @Test fun mirrorLikesSpotify_roundTrips() = runTest {
        prefs.setMirrorLikesSpotify(true)
        assertTrue(prefs.mirrorLikesSpotify.first())
        prefs.setMirrorLikesSpotify(false)
        assertFalse(prefs.mirrorLikesSpotify.first())
    }

    @Test fun mirrorLikesYtMusic_roundTrips() = runTest {
        prefs.setMirrorLikesYtMusic(true)
        assertTrue(prefs.mirrorLikesYtMusicNow())
        // Reset so the shared process-singleton DataStore doesn't leak a
        // cached `true` into the order-independent default tests.
        prefs.setMirrorLikesYtMusic(false)
    }

    @Test fun mirrorPrefs_doNotDisturbExistingHeartDefaults() = runTest {
        prefs.setMirrorLikesSpotify(true)
        // Vestigial prefs keep their legacy defaults (stash=true, spotify=true, yt=false).
        assertTrue(prefs.heartDefaultSpotifyNow())
        assertFalse(prefs.heartDefaultYtMusicNow())
        // Reset so the cached value doesn't leak into the default tests.
        prefs.setMirrorLikesSpotify(false)
    }
}
