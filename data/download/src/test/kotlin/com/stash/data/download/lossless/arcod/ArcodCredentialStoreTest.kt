package com.stash.data.download.lossless.arcod

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DataStore-backed unit tests for [ArcodCredentialStore].
 *
 * Follows the LosslessSourcePreferences test convention (Robolectric +
 * ApplicationProvider), wiping the store's own preferences_pb between runs so
 * tests start from a clean DataStore.
 */
@RunWith(RobolectricTestRunner::class)
class ArcodCredentialStoreTest {

    private lateinit var context: Context
    private lateinit var store: ArcodCredentialStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = ArcodCredentialStore(context)
        // The preferencesDataStore delegate is a single per-process instance, so
        // deleting the backing file doesn't reset the in-memory cache between
        // tests. Clear through the store itself to guarantee a clean slate.
        runBlocking { store.markStale() }
    }

    @Test
    fun `save persists session round-trip`() = runTest {
        store.save(accessToken = "access-123", refreshToken = "refresh-456", expiresAtMs = 789L)

        assertThat(store.isConnected()).isTrue()
        assertThat(store.accessTokenNow()).isEqualTo("access-123")

        val session = store.session()
        assertThat(session).isNotNull()
        assertThat(session!!.accessToken).isEqualTo("access-123")
        assertThat(session.refreshToken).isEqualTo("refresh-456")
        assertThat(session.expiresAtMs).isEqualTo(789L)
    }

    @Test
    fun `markStale clears the session`() = runTest {
        store.save(accessToken = "access-123", refreshToken = "refresh-456", expiresAtMs = 789L)

        store.markStale()

        assertThat(store.isConnected()).isFalse()
        assertThat(store.session()).isNull()
        assertThat(store.accessTokenNow()).isNull()
    }

    @Test
    fun `fresh store is not connected`() = runTest {
        assertThat(store.isConnected()).isFalse()
        assertThat(store.session()).isNull()
        assertThat(store.accessTokenNow()).isNull()
    }
}
