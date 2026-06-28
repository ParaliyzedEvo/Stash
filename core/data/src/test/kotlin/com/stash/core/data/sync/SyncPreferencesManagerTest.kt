package com.stash.core.data.sync

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import com.stash.core.model.SyncMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
        // an in-memory cache that survives the delete — so also clear() the live
        // store so each test genuinely starts from an empty store.
        ctx.preferencesDataStoreFile("sync_preferences").delete()
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
        mgr.setYoutubeSyncMode(SyncMode.ACCUMULATE)
        assertEquals(SyncMode.ACCUMULATE, mgr.youtubeSyncMode.first())
    }
}
