package com.stash.core.data.sync

import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.stash.core.model.SyncMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncPreferencesManagerTest {
    private lateinit var mgr: SyncPreferencesManager

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        // The sync_preferences DataStore is a per-process delegate that leaks
        // across tests. Deleting the backing file isn't enough — DataStore keeps
        // an in-memory cache that survives the delete — so clear() the live
        // store so each test genuinely starts from an empty store.
        runBlocking { ctx.syncPrefsDataStore.edit { it.clear() } }
        mgr = SyncPreferencesManager(ctx)
    }

    @Test fun `default mode is ACCUMULATE for both sources on a fresh store`() = runTest {
        assertEquals(SyncMode.ACCUMULATE, mgr.spotifySyncMode.first())
        assertEquals(SyncMode.ACCUMULATE, mgr.youtubeSyncMode.first())
    }

    @Test fun `explicit REFRESH choice is preserved`() = runTest {
        mgr.setSpotifySyncMode(SyncMode.REFRESH)
        assertEquals(SyncMode.REFRESH, mgr.spotifySyncMode.first())
    }

    @Test fun `explicit ACCUMULATE choice is preserved`() = runTest {
        mgr.setYoutubeSyncMode(SyncMode.REFRESH)       // off the default
        mgr.setYoutubeSyncMode(SyncMode.ACCUMULATE)    // overwrite back
        assertEquals(SyncMode.ACCUMULATE, mgr.youtubeSyncMode.first())
    }

    @Test fun `legacy global sync_mode migrates into Spotify and is not overridden by the new default`() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.syncPrefsDataStore.edit { it[androidx.datastore.preferences.core.stringPreferencesKey("sync_mode")] = "REFRESH" }
        assertEquals(SyncMode.REFRESH, mgr.spotifySyncMode.first())
    }

    @Test fun `anyAccumulate true on fresh store (default ACCUMULATE)`() = runTest {
        assertTrue(mgr.anyAccumulate())
    }

    @Test fun `anyAccumulate false only when both sources are REFRESH`() = runTest {
        mgr.setSpotifySyncMode(SyncMode.REFRESH)
        mgr.setYoutubeSyncMode(SyncMode.REFRESH)
        assertFalse(mgr.anyAccumulate())
    }

    @Test fun `anyAccumulate true when one source accumulates`() = runTest {
        mgr.setSpotifySyncMode(SyncMode.REFRESH)
        mgr.setYoutubeSyncMode(SyncMode.ACCUMULATE)
        assertTrue(mgr.anyAccumulate())
    }
}
