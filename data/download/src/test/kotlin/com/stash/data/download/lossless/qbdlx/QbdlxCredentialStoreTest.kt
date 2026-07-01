package com.stash.data.download.lossless.qbdlx

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
 * DataStore-backed unit tests for [QbdlxCredentialStore].
 *
 * Mirrors [com.stash.data.download.lossless.arcod.ArcodCredentialStoreTest]
 * (Robolectric + ApplicationProvider + a real temp DataStore). The
 * preferencesDataStore delegate is a single per-process instance, so the
 * persisted pasted/dead state leaks between tests unless wiped — clear it in
 * @Before so each test starts from a clean store. The pool is injected via the
 * [QbdlxCredentialStore.poolRaw] seam so the tests don't depend on BuildConfig.
 */
@RunWith(RobolectricTestRunner::class)
class QbdlxCredentialStoreTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private fun store(pool: String) = QbdlxCredentialStore(ctx).also { it.poolRaw = pool }

    @Before
    fun setUp() {
        runBlocking { QbdlxCredentialStore(ctx).clearPersistedForTest() }
    }

    @Test
    fun `pasted token takes priority over pool`() = runTest {
        val s = store("a:FR,b:GB"); s.setPastedToken("pasted")
        assertThat(s.activeToken()).isEqualTo("pasted")
    }

    @Test
    fun `round-robin advances across live pool tokens`() = runTest {
        val s = store("a:FR,b:GB")
        val first = s.activeToken(); val second = s.activeToken()
        assertThat(setOf(first, second)).isEqualTo(setOf("a", "b"))
    }

    @Test
    fun `markDead skips dead token within cooldown`() = runTest {
        val s = store("a:FR,b:GB"); s.markDead("a")
        repeat(4) { assertThat(s.activeToken()).isEqualTo("b") }
    }

    @Test
    fun `dead token is retried after the cooldown elapses`() = runTest {
        var now = 1_000L
        val s = store("a:FR,b:GB").also { it.clock = { now } }
        s.markDead("a")
        assertThat(s.activeToken()).isEqualTo("b") // a is dead
        now += QbdlxCredentialStore.DEAD_COOLDOWN_MS + 1 // cooldown elapsed
        // a is live again → round-robin includes it
        val seen = (0..3).map { s.activeToken() }.toSet()
        assertThat(seen).contains("a")
    }

    @Test
    fun `pasting a token clears its dead flag (recovery path)`() = runTest {
        val s = store("a:FR,b:GB")
        s.markDead("a"); s.markDead("b")
        assertThat(s.allDead()).isTrue()
        // Pasting the SAME string that was marked dead must give it a clean chance.
        s.setPastedToken("a")
        assertThat(s.allDead()).isFalse()
        assertThat(s.activeToken()).isEqualTo("a")
    }

    @Test
    fun `tokensForRegion country-first and capped at 3`() = runTest {
        val s = store("a:FR,b:GB,c:US,d:DE")
        assertThat(s.tokensForRegion("GB").first()).isEqualTo("b")
        assertThat(s.tokensForRegion("GB").size).isAtMost(3)
    }

    @Test
    fun `allDead only when pasted and all pool dead`() = runTest {
        val s = store("a:FR,b:GB")
        s.markDead("a"); s.markDead("b"); assertThat(s.allDead()).isTrue()
        s.setPastedToken("p"); assertThat(s.allDead()).isFalse()
        s.markDead("p"); assertThat(s.allDead()).isTrue()
    }

    @Test
    fun `recordAlive clears dead flag`() = runTest {
        val s = store("a:FR,b:GB"); s.markDead("a"); s.recordAlive("a")
        assertThat(s.allDead()).isFalse()
    }
}
