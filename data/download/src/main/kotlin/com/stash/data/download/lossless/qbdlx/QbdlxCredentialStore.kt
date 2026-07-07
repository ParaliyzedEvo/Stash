package com.stash.data.download.lossless.qbdlx

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/** One anonymized pool token for the Settings picker. `token` is the id only, never shown. */
data class QbdlxTokenChoice(
    val label: String,
    val token: String,
    val country: String,
    val live: Boolean,
)

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
 *  - [activeToken]: the token to use now — sticky, not round-robin. Priority:
 *    pasted (if live) > pinned pool token (if live and still a pool member) >
 *    the sticky primary (if still live) > else the first live token in canonical
 *    order, which becomes the new sticky primary. One token carries load until it
 *    dies, then we advance. Null when nothing is live.
 *  - [tokensForRegion]: ordered live tokens for a region-locked retry,
 *    country-matched first, bounded at [MAX_REGION_TRIES] so one locked track
 *    can't fan out across every account.
 *  - [markDead]/[recordAlive]: persist a token as dead (auth-failed) / clear it,
 *    so a cold start doesn't re-probe dead tokens.
 *  - [allDead]: true when there's no usable token (none configured, or all
 *    currently dead) — drives the Settings "paste a token" surface and gates
 *    the source off.
 *
 * `app_id` + `app_secret` are constant and read directly from BuildConfig by the
 * client/signer; only the rotating tokens are managed here.
 */
@Singleton
class QbdlxCredentialStore @Inject constructor(
    @ApplicationContext private val context: Context,
    poolProvider: QbdlxPoolProvider,
) {
    private val pastedTokenKey = stringPreferencesKey("pasted_token")

    /**
     * Test seam: the raw `token:country,token:country` pool. Defaults to the
     * decrypted BuildConfig blob (via [QbdlxPoolProvider]); tests override it.
     */
    internal var poolRaw: String = poolProvider.rawPool()

    /** Injectable clock (epoch ms) for the dead-token cooldown; overridable in tests. */
    internal var clock: () -> Long = { System.currentTimeMillis() }

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

    /**
     * Sticky primary: the token we keep using until it dies (replaces round-robin).
     * @Volatile for visibility only — two concurrent resolves both picking a live
     * token is benign (last write wins, no corruption). Nulled on markDead of the
     * primary so the next call advances. In-memory (per process).
     */
    @Volatile
    private var activePrimary: String? = null

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

    private val pinnedTokenKey = stringPreferencesKey("pinned_token")

    /** The picker-pinned pool token, or null for Auto. */
    suspend fun pinnedToken(): String? =
        context.qbdlxCredentialsDataStore.data.first()[pinnedTokenKey]?.takeIf { it.isNotBlank() }

    /** Pin a pool token for the Settings picker, or clear (null) for Auto. */
    suspend fun setPinnedToken(token: String?) {
        val t = token?.trim()
        context.qbdlxCredentialsDataStore.edit { prefs ->
            if (t.isNullOrEmpty()) prefs.remove(pinnedTokenKey) else prefs[pinnedTokenKey] = t
        }
    }

    /**
     * The pool as anonymized picker choices, in stable canonical order
     * (by token hash, so "Token 2" is the same account across pool refreshes —
     * NOT array position, and createdAt is dropped in the build-time flatten).
     * The raw token is the id behind the label only; it never becomes UI text.
     * [live] is a point-in-time hint (isDead at compute time), not a live flow.
     */
    suspend fun poolForPicker(): List<QbdlxTokenChoice> =
        pool().sortedWith(compareBy({ it.first.hashCode() }, { it.first }))
            .mapIndexed { i, (token, country) ->
                QbdlxTokenChoice(
                    label = "Token ${i + 1}",
                    token = token,
                    country = country,
                    live = !isDead(token),
                )
            }

    /**
     * The token to use now (sticky, not round-robin):
     *   1. pasted token if live (the user's own / monthly-refresh path — wins);
     *   2. pinned pool token if live AND still a member of the current pool
     *      (a pin to a since-removed token is ignored → falls through to auto);
     *   3. the sticky [activePrimary] if still live;
     *   4. else the first live token in canonical order → pinned as the new primary.
     * Null when nothing is live.
     */
    suspend fun activeToken(): String? {
        pastedToken()?.let { if (!isDead(it)) return it }
        pinnedToken()?.let { p ->
            if (!isDead(p) && pool().any { it.first == p }) return p
        }
        activePrimary?.let { if (!isDead(it)) return it }
        val next = pool().map { it.first }
            .filter { !isDead(it) }
            .sortedWith(compareBy({ it.hashCode() }, { it }))
            .firstOrNull() ?: return null
        activePrimary = next
        return next
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
        if (token == activePrimary) activePrimary = null
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
     * True when there is NO usable token: none configured at all (no bundled
     * pool, no paste), or every configured one is currently dead. Drives the
     * Settings "paste a token" badge AND gates the source off entirely via
     * isEnabled/isEnabledForStreaming. A tokenless build MUST surface the paste
     * prompt and drop out of the chain — an earlier "empty pool isn't expired"
     * guard here returned false instead, which hid the v0.9.65–v0.9.68 blank
     * BuildConfig credentials as silent per-track no_results.
     */
    suspend fun allDead(): Boolean {
        val pasted = pastedToken()
        val poolTokens = pool().map { it.first }
        if (poolTokens.isEmpty() && pasted == null) return true // no credentials at all
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
