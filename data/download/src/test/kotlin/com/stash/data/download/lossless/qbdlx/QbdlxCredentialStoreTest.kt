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
    private fun store(pool: String) = QbdlxCredentialStore(ctx) { "" }.also { it.poolRaw = pool }

    @Before
    fun setUp() {
        runBlocking { QbdlxCredentialStore(ctx) { "" }.clearPersistedForTest() }
    }

    @Test
    fun `pasted token takes priority over pool`() = runTest {
        val s = store("a:FR,b:GB"); s.setPastedToken("pasted")
        assertThat(s.activeToken()).isEqualTo("pasted")
    }

    @Test
    fun `markDead skips dead token within cooldown`() = runTest {
        val s = store("a:FR,b:GB"); s.markDead("a")
        repeat(4) { assertThat(s.activeToken()).isEqualTo("b") }
    }

    @Test
    fun `activeToken is sticky - same token until it dies, then advances`() = runTest {
        val s = store("a:FR,b:GB")
        val first = s.activeToken()
        assertThat(s.activeToken()).isEqualTo(first)   // sticky: no rotation
        assertThat(s.activeToken()).isEqualTo(first)
        s.markDead(first!!)
        val second = s.activeToken()
        assertThat(second).isNotEqualTo(first)          // advanced to the other live token
        assertThat(s.activeToken()).isEqualTo(second)   // sticky on the new primary
    }

    @Test
    fun `a token recovers as a candidate after its cooldown elapses`() = runTest {
        var now = 1_000L
        val s = store("a:FR,b:GB").also { it.clock = { now } }
        val primary = s.activeToken()                   // canonical-first live token
        s.markDead(primary!!)
        val other = s.activeToken()
        assertThat(other).isNotEqualTo(primary)         // advanced; primary in cooldown
        now += QbdlxCredentialStore.DEAD_COOLDOWN_MS + 1
        s.markDead(other!!)                            // now-primary dies; original cooldown elapsed
        assertThat(s.activeToken()).isEqualTo(primary)  // original live again → reused
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

    @Test
    fun `empty pool with no paste is allDead so the tokenless state surfaces`() = runTest {
        val s = store("")
        assertThat(s.allDead()).isTrue() // no credentials → badge + source gated off
        assertThat(s.activeToken()).isNull()
        s.setPastedToken("p")
        assertThat(s.allDead()).isFalse() // paste is the recovery path
    }

    @Test
    fun `pasted beats pinned beats sticky-auto`() = runTest {
        val s = store("a:FR,b:GB")
        s.setPinnedToken("b")
        assertThat(s.activeToken()).isEqualTo("b")      // pinned over auto
        s.setPastedToken("p")
        assertThat(s.activeToken()).isEqualTo("p")      // pasted over pinned
    }

    @Test
    fun `dead pinned token advances to auto`() = runTest {
        val s = store("a:FR,b:GB")
        s.setPinnedToken("a"); s.markDead("a")
        assertThat(s.activeToken()).isEqualTo("b")      // pinned dead → auto picks live
    }

    @Test
    fun `pin to a token not in the pool is ignored`() = runTest {
        val s = store("a:FR,b:GB")
        s.setPinnedToken("ghost")
        assertThat(s.activeToken()).isAnyOf("a", "b")   // stale pin ignored, auto used
    }

    @Test
    fun `poolForPicker labels stable under input reordering, live reflects deadUntil`() = runTest {
        val s1 = store("a:FR,b:GB")
        val s2 = store("b:GB,a:FR")                     // reversed input
        assertThat(s1.poolForPicker().map { it.label to it.token })
            .isEqualTo(s2.poolForPicker().map { it.label to it.token })
        s1.markDead("a")
        assertThat(s1.poolForPicker().first { it.token == "a" }.live).isFalse()
        assertThat(s1.poolForPicker().first { it.token == "b" }.live).isTrue()
    }

    @Test
    fun `poolForPicker is empty for an empty pool`() = runTest {
        assertThat(store("").poolForPicker()).isEmpty()
    }
}
