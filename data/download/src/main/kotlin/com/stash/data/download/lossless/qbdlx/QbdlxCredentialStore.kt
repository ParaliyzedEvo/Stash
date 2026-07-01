package com.stash.data.download.lossless.qbdlx

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stash.data.download.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Its own preferences DataStore (mirrors
 * [com.stash.data.download.lossless.arcod.ArcodCredentialStore]) so the qbdlx
 * token state lives apart from the cross-source
 * [com.stash.data.download.lossless.LosslessSourcePreferences] schema.
 */
private val Context.qbdlxCredentialsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "qbdlx_creds",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Manages the qbdlx Qobuz token pool: a bundled set of `user_auth_token:country`
 * pairs (from [BuildConfig.QBDLX_TOKEN_POOL]) plus an optional user-pasted token
 * that takes priority and serves as the refresh path when the bundled pool ages
 * out (~monthly).
 *
 * Responsibilities:
 *  - [activeToken]: the token to use now — pasted (if live) first, else
 *    round-robin across the live (non-dead) pool tokens to spread load (the
 *    breaker is per-source, but Qobuz ban-risk is per-account, so we don't pin
 *    one token). Null when nothing is live.
 *  - [tokensForRegion]: ordered live tokens for a region-locked retry,
 *    country-matched first, bounded at [MAX_REGION_TRIES] so one locked track
 *    can't fan out across every account.
 *  - [markDead]/[recordAlive]: persist a token as dead (auth-failed) / clear it,
 *    so a cold start doesn't re-probe dead tokens.
 *  - [allDead]: true when the pasted token (if any) is dead AND every pool token
 *    is dead — drives the Settings "expired, paste a fresh token" surface.
 *
 * `app_id` + `app_secret` are constant and read directly from BuildConfig by the
 * client/signer; only the rotating tokens are managed here.
 */
@Singleton
class QbdlxCredentialStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val pastedTokenKey = stringPreferencesKey("pasted_token")

    /**
     * Test seam: the raw `token:country,token:country` pool. Defaults to the
     * bundled BuildConfig value; tests override it with a known pool so they
     * don't depend on BuildConfig.
     */
    internal var poolRaw: String = BuildConfig.QBDLX_TOKEN_POOL

    /** Injectable clock (epoch ms) for the dead-token cooldown; overridable in tests. */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    /** Round-robin cursor across live pool tokens. In-memory (per process). */
    private val rrIndex = AtomicInteger(0)

    /**
     * Token → epoch-ms until which it is considered dead. IN-MEMORY and
     * TIME-BOXED (circuit-breaker style), deliberately NOT persisted: a single
     * transient auth failure (a cold-start network blip, or a 401 from the same
     * shared token being used concurrently across apps/the website) must NOT
     * permanently disable a token. It's skipped for [DEAD_COOLDOWN_MS] then
     * auto-retried; a genuinely-dead token just re-marks. A process restart also
     * clears it. This replaces an earlier persisted, permanent dead-set that
     * left the whole pool stuck on one transient 401 ("token expired" forever).
     */
    private val deadUntil = ConcurrentHashMap<String, Long>()

    /** Parsed pool: (token, ISO-2 country). Split on the LAST ':' defensively. */
    private fun pool(): List<Pair<String, String>> =
        poolRaw.split(",")
            .mapNotNull { entry ->
                val e = entry.trim().ifEmpty { return@mapNotNull null }
                val i = e.lastIndexOf(':')
                if (i <= 0) e to "" else e.substring(0, i) to e.substring(i + 1)
            }

    /** True when [token] is within its dead cooldown. Cleans up expired entries. */
    private fun isDead(token: String): Boolean {
        val until = deadUntil[token] ?: return false
        if (clock() < until) return true
        deadUntil.remove(token) // cooldown elapsed — give it another chance
        return false
    }

    private suspend fun pastedToken(): String? =
        context.qbdlxCredentialsDataStore.data.first()[pastedTokenKey]?.takeIf { it.isNotBlank() }

    /** The token to use now: pasted (if live) first, else round-robin over live pool tokens. */
    suspend fun activeToken(): String? {
        pastedToken()?.let { if (!isDead(it)) return it }
        val live = pool().map { it.first }.filter { !isDead(it) }
        if (live.isEmpty()) return null
        return live[(rrIndex.getAndIncrement() % live.size + live.size) % live.size]
    }

    /**
     * Live tokens to try for a region-locked track: country-matched first, then
     * the rest, capped at [MAX_REGION_TRIES].
     */
    suspend fun tokensForRegion(country: String?): List<String> {
        val live = pool().filter { !isDead(it.first) }
        val sorted = if (country.isNullOrBlank()) {
            live
        } else {
            live.sortedByDescending { it.second.equals(country, ignoreCase = true) }
        }
        return sorted.map { it.first }.take(MAX_REGION_TRIES)
    }

    /** Mark [token] dead for the cooldown window (auth failure). Auto-retried after. */
    fun markDead(token: String) {
        deadUntil[token] = clock() + DEAD_COOLDOWN_MS
    }

    /** Clear a token's dead flag (a successful call, or a fresh paste). */
    fun recordAlive(token: String) {
        deadUntil.remove(token)
    }

    /**
     * Set (or clear, with null) the user-pasted token. Clears any dead flag on
     * the pasted value so pasting a token (the "expired — paste a fresh one"
     * recovery) always gives it a clean chance, even if that same string was
     * previously marked dead.
     */
    suspend fun setPastedToken(token: String?) {
        val t = token?.trim()
        if (!t.isNullOrEmpty()) recordAlive(t)
        context.qbdlxCredentialsDataStore.edit { prefs ->
            if (t.isNullOrEmpty()) prefs.remove(pastedTokenKey) else prefs[pastedTokenKey] = t
        }
    }

    /**
     * True when there IS at least one credential (pasted or bundled) AND every
     * one of them is currently dead — i.e. "expired, paste a fresh one". An
     * EMPTY pool with no pasted token is NOT "expired" (there's nothing to
     * expire); `pool().all{}` would vacuously return true, so guard it.
     */
    suspend fun allDead(): Boolean {
        val pasted = pastedToken()
        val poolTokens = pool().map { it.first }
        val total = poolTokens.size + (if (pasted != null) 1 else 0)
        if (total == 0) return false // no credentials at all — not "expired"
        pasted?.let { if (!isDead(it)) return false }
        return poolTokens.all { isDead(it) }
    }

    /** Test-only: wipe persisted pasted state + in-memory dead flags. */
    internal suspend fun clearPersistedForTest() {
        deadUntil.clear()
        context.qbdlxCredentialsDataStore.edit { it.clear() }
    }

    companion object {
        const val MAX_REGION_TRIES = 3

        // Dead-token cooldown before a token is retried (circuit-breaker style).
        // 60s, deliberately SHORT: a dead token blacks out BOTH download and
        // streaming (isEnabled + isEnabledForStreaming gate on allDead), so a
        // TRANSIENT failure (a preview/522/timeout on the shared account under
        // the download burst) that trips a mark-dead must not kill qbdlx for
        // long. 60s recovers fast; a genuinely-dead token just re-marks, costing
        // one doomed attempt per minute (negligible). Was 10min — far too long a
        // total blackout for a transient ("completely dead" until it aged out).
        const val DEAD_COOLDOWN_MS = 60_000L
    }
}
