package com.stash.data.download.lossless.qbdlx

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DataStore-backed unit tests for [QbdlxCredentialStore] — now a store for ONE
 * credential: the user's own connected Qobuz account.
 *
 * Mirrors [com.stash.data.download.lossless.arcod.ArcodCredentialStoreTest]
 * (Robolectric + ApplicationProvider + a real temp DataStore). The
 * preferencesDataStore delegate is a single per-process instance, so persisted
 * login/pasted state leaks between tests unless wiped — clear it in @Before so
 * each test starts from a clean store. The live Qobuz web scrape is injected via
 * the [QobuzWebCredentials] seam, so nothing here touches the network.
 */
@RunWith(RobolectricTestRunner::class)
class QbdlxCredentialStoreTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    private fun store(creds: QobuzWebCreds? = QobuzWebCreds("712109809", "web-secret")) =
        QbdlxCredentialStore(ctx) { creds }

    @Before
    fun setUp() {
        runBlocking { store().clearPersistedForTest() }
    }

    @Test
    fun `hasLogin reflects the connected account`() = runTest {
        val s = store()
        assertThat(s.hasLogin.first()).isFalse()
        s.setUserCredential("tok", "798273057", "sec", email = "me@x")
        assertThat(s.hasLogin.first()).isTrue()
        assertThat(s.loginLive()).isTrue()
        assertThat(s.connectedEmail()).isEqualTo("me@x")
        s.markDead("tok")
        assertThat(s.loginLive()).isFalse()
        s.clearUserCredential()
        assertThat(s.hasLogin.first()).isFalse()
        assertThat(s.connectedEmail()).isNull()
    }

    @Test
    fun `hasLogin is true for a pasted token awaiting migration`() = runTest {
        val s = store()
        assertThat(s.hasLogin.first()).isFalse()
        s.setPastedToken("legacy")
        assertThat(s.hasLogin.first()).isTrue()
    }

    @Test
    fun `recordAlive clears the dead flag`() = runTest {
        val s = store()
        s.setUserCredential("tok", "798273057", "sec", email = "me@x")
        s.markDead("tok")
        assertThat(s.loginLive()).isFalse()
        s.recordAlive("tok")
        assertThat(s.loginLive()).isTrue()
    }

    @Test
    fun `a cooled login is retried once DEAD_COOLDOWN_MS has elapsed`() = runTest {
        var now = 1_000L
        val s = store().also { it.clock = { now } }
        s.setUserCredential("tok", "798273057", "sec", email = "me@x")
        s.markDead("tok")
        assertThat(s.loginLive()).isFalse()
        now += QbdlxCredentialStore.DEAD_COOLDOWN_MS + 1
        assertThat(s.loginLive()).isTrue()   // transient 401 must not disconnect anyone
    }

    /**
     * The whole point of [QbdlxCredentialStore.signingFor]: a Qobuz token only
     * returns full FLAC when signed with the app_id/secret it was minted under,
     * so the connected account's OWN stored pair is what signs its requests.
     */
    @Test
    fun `a connected account signs with its own stored pair`() = runTest {
        val s = store()
        s.setUserCredential("myAccount", "712109809", "589be88e4538daea11f509d29e4a23b1", email = "me@x")
        val signing = s.signingFor("myAccount")
        assertThat(signing.appId).isEqualTo("712109809")
        assertThat(signing.appSecret).isEqualTo("589be88e4538daea11f509d29e4a23b1")
    }

    /**
     * The shipped pool cached its raw `token:country,…` string — plaintext
     * third-party Qobuz tokens — under `cached_pool`, and pinned one of them under
     * `pinned_token`. Deleting the pool's CODE left both on every upgrading
     * device, written by nothing, read by nothing and removed by nothing. The
     * first load has to take them off the disk. Keys are spelled out literally
     * here on purpose: this asserts against what is actually in the file.
     */
    @Test
    fun `the removed pool's cached tokens are purged from disk on the first load`() = runTest {
        val poolKey = stringPreferencesKey("cached_pool")
        val pinnedKey = stringPreferencesKey("pinned_token")
        val s = store()
        s.dataStoreForTest.edit { it[poolKey] = "tok-a:FR,tok-b:US"; it[pinnedKey] = "tok-a" }

        assertThat(s.loginCredential()).isNull()

        val raw = s.dataStoreForTest.data.first()
        assertThat(raw.contains(poolKey)).isFalse()
        assertThat(raw.contains(pinnedKey)).isFalse()
    }

    @Test
    fun `a pasted token is migrated using the scraped web pair`() = runTest {
        store().setPastedToken("pasted-tok")
        val s = store()
        assertThat(s.loginCredential())
            .isEqualTo(QbdlxLoginCredential("pasted-tok", "712109809", "web-secret"))
        assertThat(s.connectedEmail()).isNull()
        s.clearUserCredential()
        assertThat(store().loginCredential()).isNull()   // the pasted key was consumed
    }

    @Test
    fun `migration is skipped and the pasted token kept when the scrape fails`() = runTest {
        store().setPastedToken("pasted-tok")
        assertThat(store(creds = null).loginCredential()).isNull()
        assertThat(store().hasLogin.first()).isTrue()    // key survives for the next attempt
    }

    @Test
    fun `a token pasted after the store has loaded is migrated on the next read`() = runTest {
        val s = store()
        assertThat(s.loginCredential()).isNull()          // loginLoaded = true, nothing to migrate
        s.setPastedToken("late-paste")
        assertThat(s.loginCredential()?.token).isEqualTo("late-paste")
        assertThat(s.loginLive()).isTrue()
    }

    @Test
    fun `rejectLogin clears a migrated pasted token but only cools a real account`() = runTest {
        val s = store()
        s.setUserCredential("tok", "712109809", "web-secret", email = null)   // no email = migrated paste
        s.rejectLogin("tok")
        assertThat(s.hasLogin.first()).isFalse()                // terminal: nothing to re-mint from

        s.setUserCredential("tok2", "712109809", "web-secret", email = "me@x")
        s.rejectLogin("tok2")
        assertThat(s.hasLogin.first()).isTrue()                 // still connected…
        assertThat(s.loginLive()).isFalse()                     // …just cooling
    }
}
