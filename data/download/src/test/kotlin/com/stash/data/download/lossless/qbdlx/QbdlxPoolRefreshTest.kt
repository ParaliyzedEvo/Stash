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
import org.robolectric.annotation.Config

/**
 * Runtime pool refresh — the fix for "a shipped build's tokens rot".
 *
 * Qobuz tokens rotate roughly monthly and the pool is baked into the APK at
 * build time, so three separate releases have gone 100% dead in the wild with no
 * recovery short of shipping again. The store now re-fetches the shared pool
 * when every token it holds is dead.
 *
 * The rules being pinned here are as much about what it must NOT do: no fetching
 * on the happy path, no unbounded retries, and never let a failing endpoint make
 * things worse than not having one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class QbdlxPoolRefreshTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setUp() = runBlocking {
        QbdlxCredentialStore(ctx, { "" }, QbdlxRemotePool { null }).clearPersistedForTest()
    }

    private fun store(pool: String, remote: QbdlxRemotePool) =
        QbdlxCredentialStore(ctx, { "" }, remote).also { it.poolRaw = pool }

    @Test fun `a fully dead pool is replaced by the freshly fetched one`() = runTest {
        var fetches = 0
        val s = store("dead:FR", QbdlxRemotePool { fetches++; "fresh:FR" })
        s.markDead("dead")

        assertThat(s.activeToken()).isEqualTo("fresh")
        assertThat(fetches).isEqualTo(1)
    }

    @Test fun `a live pool never triggers a fetch`() = runTest {
        // The happy path must cost nothing — no network on every resolve.
        var fetches = 0
        val s = store("live:FR", QbdlxRemotePool { fetches++; "other:GB" })

        assertThat(s.activeToken()).isEqualTo("live")
        assertThat(fetches).isEqualTo(0)
    }

    @Test fun `a failing endpoint leaves the existing pool alone`() = runTest {
        // An unreachable webhook must never be worse than not having one.
        val s = store("dead:FR", QbdlxRemotePool { null })
        s.markDead("dead")

        assertThat(s.activeToken()).isNull()   // still dead, but nothing corrupted
        s.recordAlive("dead")
        assertThat(s.activeToken()).isEqualTo("dead")
    }

    @Test fun `refresh attempts are rate limited while the pool stays dead`() = runTest {
        var fetches = 0
        val s = store("dead:FR", QbdlxRemotePool { fetches++; null })
        var now = 1_000_000L
        s.clock = { now }
        s.markDead("dead")

        repeat(5) { s.activeToken() }
        assertThat(fetches).isEqualTo(1)

        // Still inside the window — no second call.
        now += QbdlxCredentialStore.REFRESH_MIN_INTERVAL_MS - 1
        s.markDead("dead")
        s.activeToken()
        assertThat(fetches).isEqualTo(1)

        // Window elapsed — allowed to try again.
        now += 2
        s.markDead("dead")
        s.activeToken()
        assertThat(fetches).isEqualTo(2)
    }

    @Test fun `a refreshed pool survives a restart via the cache`() = runTest {
        val s = store("dead:FR", QbdlxRemotePool { "fresh:FR" })
        s.markDead("dead")
        assertThat(s.activeToken()).isEqualTo("fresh")

        // New instance = new process. The bundled pool is the stale one again,
        // but the cache should win, with no second fetch needed.
        var fetches = 0
        val restarted = QbdlxCredentialStore(ctx, { "dead:FR" }, QbdlxRemotePool { fetches++; null })
        assertThat(restarted.activeToken()).isEqualTo("fresh")
        assertThat(fetches).isEqualTo(0)
    }

    /**
     * REGRESSION (device-verified 2026-08-15, #qbdlx-stale-pool).
     *
     * Every other test in this class uses a ONE-token pool, where "every token is
     * dead" is trivially reachable. The real pool is 17, and one resolve only ever
     * probes MAX_TOKEN_ATTEMPTS (6) of them before falling through — so with dead
     * marks expiring after DEAD_COOLDOWN_MS, the pool was never observed fully dead
     * and the self-heal fetch NEVER fired. On device that left a rotted 17-token
     * cache in place while the one live token sat on the webhook, unreachable.
     *
     * A budget's worth of consecutive auth failures with no success in between is
     * the same "our credentials are gone" signal, and unlike "all dead" it is
     * reachable at any pool size.
     */
    @Test fun `a pool bigger than one resolve's attempt budget still refreshes`() = runTest {
        var fetches = 0
        val pool = (1..17).joinToString(",") { "t$it:FR" }
        val s = store(pool, QbdlxRemotePool { fetches++; "fresh:FR" })

        // One resolve's worth of auth failures: 6 of 17 dead, 11 still look "live".
        repeat(6) { i -> s.markDead("t${i + 1}") }

        assertThat(s.activeToken()).isEqualTo("fresh")
        assertThat(fetches).isEqualTo(1)
    }

    /**
     * Successive resolves must work DOWN the pool instead of restarting at the same
     * head. Dead marks expire in 60s while real listening puts minutes between
     * tracks, so a canonical-order-only pick re-probed the same first tokens forever
     * and a live token further down the list could never be reached.
     */
    @Test fun `successive resolves probe further into the pool, not the same head`() = runTest {
        val s = store((1..12).joinToString(",") { "t$it:FR" }, QbdlxRemotePool { null })
        var now = 1_000_000L
        s.clock = { now }

        val firstPass = buildSet {
            repeat(6) {
                val t = s.activeToken()!!
                add(t)
                s.markDead(t)
            }
        }

        // Cooldown elapses — every token is selectable again.
        now += QbdlxCredentialStore.DEAD_COOLDOWN_MS + 1

        val secondPass = buildSet {
            repeat(6) {
                val t = s.activeToken()!!
                add(t)
                s.markDead(t)
            }
        }

        assertThat(secondPass).containsNoneIn(firstPass)
    }

    /**
     * After a refresh, a token we have never probed must be tried BEFORE the ones
     * we already know failed — otherwise the freshly-added live token sits behind a
     * queue of known-dead tokens and takes several more resolves to reach.
     */
    @Test fun `a newly added token is probed before every token of the pool it replaced`() = runTest {
        // 12 stale tokens, but the refresh trigger fires after 6 failures — so 6 were
        // never probed and look no worse than the fresh token on a cold start. The
        // token the refresh was fetched FOR still has to go first.
        //
        // "zzzzz" is chosen to sort LAST in the old canonical (hashCode) order, so
        // this cannot pass by ordering luck.
        val stale = (1..12).joinToString(",") { "t$it:FR" }
        val s = store(stale, QbdlxRemotePool { "$stale,zzzzz:FR" })
        repeat(6) { i -> s.markDead("t${i + 1}") }

        assertThat(s.activeToken()).isEqualTo("zzzzz")
    }

    @Test fun `a dead pool stops reporting allDead once refreshed`() = runTest {
        // allDead() gates the source off AND drives the "paste a token" badge, so
        // it has to recover on its own too — not just activeToken().
        val s = store("dead:FR", QbdlxRemotePool { "fresh:FR" })
        s.markDead("dead")

        assertThat(s.allDead()).isFalse()
    }

    // ---- pool parsing (pure, no network) ----

    @Test fun `parsePool keeps every signable app_id, tags it, and dedupes`() {
        val body = """
            [
              {"token":"t1","country":"FR","app_id":"798273057"},
              {"token":"t1","country":"FR","app_id":"798273057"},
              {"token":"t2","country":"GB","app_id":"798273057"},
              {"token":"other","country":"US","app_id":"312369995"},
              {"token":"","country":"NO","app_id":"798273057"},
              {"token":"t3","country":"","app_id":"798273057"}
            ]
        """.trimIndent()

        // Both app_ids are now signable (we bundle both secrets), so the
        // second-app token is KEPT and tagged — the whole point of #1.
        val pool = HttpQbdlxRemotePool.parsePool(
            body,
            primaryAppId = "798273057",
            knownAppIds = setOf("798273057", "312369995"),
        )
        assertThat(pool).isEqualTo("t1:FR:798273057,t2:GB:798273057,other:US:312369995")
    }

    @Test fun `parsePool drops rows for app_ids we cannot sign`() {
        val body = """
            [
              {"token":"t1","country":"FR","app_id":"798273057"},
              {"token":"unsignable","country":"US","app_id":"999999999"}
            ]
        """.trimIndent()
        val pool = HttpQbdlxRemotePool.parsePool(
            body,
            primaryAppId = "798273057",
            knownAppIds = setOf("798273057"),
        )
        assertThat(pool).isEqualTo("t1:FR:798273057")
    }

    @Test fun `parsePool treats a missing app_id as the primary app`() {
        val body = """[{"token":"t1","country":"FR"}]"""
        val pool = HttpQbdlxRemotePool.parsePool(
            body,
            primaryAppId = "798273057",
            knownAppIds = setOf("798273057"),
        )
        assertThat(pool).isEqualTo("t1:FR:798273057")
    }

    @Test fun `parsePool returns empty for junk rather than throwing`() {
        assertThat(HttpQbdlxRemotePool.parsePool("not json", knownAppIds = setOf("798273057"))).isEmpty()
        assertThat(HttpQbdlxRemotePool.parsePool("{}", knownAppIds = setOf("798273057"))).isEmpty()
        assertThat(HttpQbdlxRemotePool.parsePool("[]", knownAppIds = setOf("798273057"))).isEmpty()
    }
}
