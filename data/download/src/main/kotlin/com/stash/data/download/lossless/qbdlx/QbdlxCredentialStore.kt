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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import android.util.Log

/** One anonymized pool token for the Settings picker. `token` is the id only, never shown. */
data class QbdlxTokenChoice(
    val label: String,
    val token: String,
    val country: String,
    val live: Boolean,
)

/** A user-connected Qobuz account: its token plus the app_id/secret it was minted under. */
data class QbdlxLoginCredential(val token: String, val appId: String, val appSecret: String)

/** A parsed pool member: token, ISO-2 country, and the app_id it must be signed under. */
private data class PoolEntry(val token: String, val country: String, val appId: String)

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
 * Also the signing authority ([QbdlxSigningResolver]): each pool token is tagged
 * with the app_id it was minted under, a user-connected account carries its own
 * pair, and [signingFor] hands the client the right (app_id, app_secret) so a
 * token is never signed with a mismatched secret (which silently downgrades it to
 * a 30-second preview).
 */
@Singleton
class QbdlxCredentialStore @Inject constructor(
    @ApplicationContext private val context: Context,
    poolProvider: QbdlxPoolProvider,
    private val remotePool: QbdlxRemotePool,
) : QbdlxSigningResolver {
    private val pastedTokenKey = stringPreferencesKey("pasted_token")

    // ── Signing credentials (app_id → app_secret) ───────────────────────────
    // Read from BuildConfig directly and exposed as internal vars so tests can
    // override without a constructor param (an @Inject constructor can't carry
    // defaults). Catalog calls carry no token at all and run under the web
    // player's own id (QbdlxApiClient.catalogAppId); these pairs exist purely so
    // [signingFor] can sign getFileUrl with the app_id its token was minted
    // under. QBDLX_APP_SECRETS is a "appId:secret,
    // appId:secret" map (primary first) that build.gradle composes; empty in an
    // older build just leaves the single primary pair, which stays valid.
    internal var primaryAppId: String = com.stash.data.download.BuildConfig.QBDLX_APP_ID
    internal var primaryAppSecret: String = com.stash.data.download.BuildConfig.QBDLX_APP_SECRET
    internal var appSecretsRaw: String = com.stash.data.download.BuildConfig.QBDLX_APP_SECRETS

    /**
     * app_id → app_secret for every credential pair we can sign with. The primary
     * pair is always present (so a token with no explicit app_id, or one whose
     * app_id we don't have a secret for, still signs under the primary). Cheap to
     * rebuild each call — the map is a handful of entries.
     */
    private fun appSecretMap(): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        map[primaryAppId] = primaryAppSecret
        appSecretsRaw.split(",").forEach { pair ->
            val i = pair.indexOf(':')
            if (i > 0) {
                val appId = pair.take(i).trim()
                val secret = pair.substring(i + 1).trim()
                if (appId.isNotEmpty() && secret.isNotEmpty()) map[appId] = secret
            }
        }
        return map
    }

    /**
     * The (app_id, app_secret) to sign [token]'s requests with:
     *  1. a user-connected account signs with its own stored pair;
     *  2. a pool token signs with the app_id it's tagged with → that app_id's
     *     secret (or the primary secret if we don't have that app_id's);
     *  3. anything unknown falls back to the primary pair.
     */
    override suspend fun signingFor(token: String): QbdlxSigning {
        loginCredential()?.let { if (it.token == token) return QbdlxSigning(it.appId, it.appSecret) }
        val appId = poolAppId(token) ?: primaryAppId
        // Use the tag's app_id only if we actually have its secret; otherwise fall
        // back to the full primary PAIR (never a tagged-app_id / primary-secret mix,
        // which is exactly the mismatch that yields previews).
        val secret = appSecretMap()[appId] ?: return QbdlxSigning(primaryAppId, primaryAppSecret)
        return QbdlxSigning(appId, secret)
    }

    private fun poolAppId(token: String): String? = pool().firstOrNull { it.token == token }?.appId

    // ── User-connected account (bring-your-own Qobuz) ───────────────────────
    private val loginTokenKey = stringPreferencesKey("login_token")
    private val loginAppIdKey = stringPreferencesKey("login_app_id")
    private val loginAppSecretKey = stringPreferencesKey("login_app_secret")
    private val loginEmailKey = stringPreferencesKey("login_email")

    @Volatile private var cachedLogin: QbdlxLoginCredential? = null
    @Volatile private var loginLoaded = false

    /** The connected account's email, for a "Connected as …" label. Null when none. */
    suspend fun connectedEmail(): String? =
        context.qbdlxCredentialsDataStore.data.first()[loginEmailKey]?.takeIf { it.isNotBlank() }

    /**
     * Live view of "a connected account exists" for the availability predicates.
     * A pasted token awaiting migration counts — it is user-owned and the
     * migration runs on the first [loginCredential].
     */
    val hasLogin: Flow<Boolean> =
        context.qbdlxCredentialsDataStore.data.map { p ->
            val t = p[loginTokenKey]
            val a = p[loginAppIdKey]
            val s = p[loginAppSecretKey]
            (!t.isNullOrBlank() && !a.isNullOrBlank() && !s.isNullOrBlank()) ||
                !p[pastedTokenKey].isNullOrBlank()
        }
            // DataStore re-emits the whole Preferences on every unrelated write
            // (pool cache, pinned token), and a read error must not terminate the
            // combine this feeds for the rest of the process — fail closed instead.
            .distinctUntilChanged()
            .catch { emit(false) }

    /** A connected account exists and is not inside a dead-cooldown. */
    suspend fun loginLive(): Boolean = loginCredential()?.let { !isDead(it.token) } ?: false

    /** The user-connected account, or null. Cached in memory after the first read. */
    suspend fun loginCredential(): QbdlxLoginCredential? {
        if (!loginLoaded) {
            val p = runCatching { context.qbdlxCredentialsDataStore.data.first() }.getOrNull()
            val t = p?.get(loginTokenKey)
            val a = p?.get(loginAppIdKey)
            val s = p?.get(loginAppSecretKey)
            cachedLogin = if (!t.isNullOrBlank() && !a.isNullOrBlank() && !s.isNullOrBlank())
                QbdlxLoginCredential(t, a, s) else null
            loginLoaded = true
            if (cachedLogin == null) {
                try {
                    migratePastedToken()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // This runs inside a bare viewModelScope.launch (allDead()); an
                    // edit{} failure here must not take the process down.
                    Log.w(TAG, "pasted-token migration failed: ${e.javaClass.simpleName}")
                }
            }
        }
        return cachedLogin
    }

    /**
     * One-shot upgrade path: a user who pasted a token before the pool left the
     * app keeps working. `pasted_token` is a lone string that always signed under
     * the primary BuildConfig pair (see [signingFor]'s fallback), so that pair is
     * what the migrated credential stores. Runs only when no login exists.
     */
    private suspend fun migratePastedToken() {
        val pasted = pastedToken() ?: return
        if (primaryAppId.isBlank() || primaryAppSecret.isBlank()) {
            Log.i(TAG, "pasted token not migrated: no primary signing pair")
            return
        }
        Log.i(TAG, "migrating pasted token into the connected-account slot")
        setUserCredential(pasted, primaryAppId, primaryAppSecret, email = null)
        // Only drop the value we actually migrated — a token pasted concurrently
        // must not be swallowed by this cleanup.
        context.qbdlxCredentialsDataStore.edit { if (it[pastedTokenKey] == pasted) it.remove(pastedTokenKey) }
    }

    /** Persist a connected account (token + the app_id/secret it was minted under). */
    suspend fun setUserCredential(token: String, appId: String, appSecret: String, email: String? = null) {
        recordAlive(token)
        context.qbdlxCredentialsDataStore.edit {
            it[loginTokenKey] = token; it[loginAppIdKey] = appId; it[loginAppSecretKey] = appSecret
            if (email.isNullOrBlank()) it.remove(loginEmailKey) else it[loginEmailKey] = email
        }
        cachedLogin = QbdlxLoginCredential(token, appId, appSecret)
        loginLoaded = true
    }

    /** Disconnect the account. */
    suspend fun clearUserCredential() {
        context.qbdlxCredentialsDataStore.edit {
            it.remove(loginTokenKey); it.remove(loginAppIdKey); it.remove(loginAppSecretKey); it.remove(loginEmailKey)
        }
        cachedLogin = null
        loginLoaded = true
    }

    /** Runtime-refreshed pool, cached so a cold start doesn't wait on the network. */
    private val cachedPoolKey = stringPreferencesKey("cached_pool")

    /**
     * Test seam: the raw `token:country,token:country` pool. Defaults to the
     * decrypted BuildConfig blob (via [QbdlxPoolProvider]); tests override it.
     */
    internal var poolRaw: String = poolProvider.rawPool()

    /** Injectable clock (epoch ms) for the dead-token cooldown; overridable in tests. */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    /** Guards the one-time cached-pool read; the cache only needs loading once. */
    private var cacheLoaded = false

    /** Epoch ms of the last refresh ATTEMPT (success or not) — rate-limits retries. */
    private var lastRefreshAttempt = 0L

    /**
     * Loads the runtime-refreshed pool from disk, once per process.
     *
     * A cached pool WINS over the bundled one: it is strictly fresher, and the
     * bundled pool is the thing that goes stale. Falls back silently — an empty
     * or missing cache just leaves the BuildConfig pool in place.
     */
    private suspend fun ensureCacheLoaded() {
        if (cacheLoaded) return
        cacheLoaded = true
        val cached = runCatching {
            context.qbdlxCredentialsDataStore.data.first()[cachedPoolKey]
        }.getOrNull()
        if (!cached.isNullOrBlank()) poolRaw = cached
    }

    /**
     * Refreshes the pool from the shared endpoint when — and only when — every
     * token we have is dead.
     *
     * This is the exact failure it exists for: tokens rotate roughly monthly, a
     * shipped build's baked pool eventually goes 100% dead, and lossless silently
     * stops working until a new release. Refreshing on exhaustion self-heals that
     * without a release.
     *
     * Deliberately NOT a periodic/background refresh: it runs only when we are
     * already failing, so the happy path pays nothing and there is no hard
     * runtime dependency on a hobbyist webhook. Attempts are rate-limited to one
     * per [REFRESH_MIN_INTERVAL_MS] so a genuinely dead pool cannot spin.
     */
    private suspend fun refreshIfExhausted() {
        // "Every token is dead" alone is NOT a reachable condition on a real pool:
        // a resolve probes only a bounded number of tokens before falling through,
        // and marks expire after DEAD_COOLDOWN_MS, so a 17-token pool never showed
        // fully-dead and this fetch never fired — the device sat on a rotted cache
        // while the live token waited on the webhook (2026-08-15). A run of auth
        // failures with no success in between says the same thing and is reachable
        // at any pool size.
        if (pool().any { !isDead(it.token) } && authFailureStreak < REFRESH_FAILURE_STREAK) return
        val now = clock()
        if (lastRefreshAttempt != 0L && now - lastRefreshAttempt < REFRESH_MIN_INTERVAL_MS) return
        lastRefreshAttempt = now

        // Instrumented deliberately, at Info so it survives a release build.
        // Every previous credential bug here was diagnosed by logging the actual
        // decision rather than reasoning about it, and a silent self-healing path
        // is impossible to confirm from the outside.
        val before = pool().size
        val fetched = remotePool.fetch()?.trim().orEmpty()
        if (fetched.isEmpty()) {
            Log.i(TAG, "pool exhausted ($before token(s)); refresh returned nothing")
            return
        }
        if (fetched == poolRaw) {
            Log.i(TAG, "pool exhausted ($before token(s)); refresh returned the same pool")
            return
        }

        // Everything in the pool we are REPLACING belongs to the generation that just
        // failed us, including the tokens this process never got around to probing.
        // Stamping them (putIfAbsent, so a real failure time is never overwritten)
        // orders selection: tokens new in this fetch (still unstamped) → old ones we
        // never probed → ones we watched fail. Without it a cold start ties the new
        // token with every unprobed old one and picks by hashCode, so the token the
        // refresh was fetched FOR could sit behind a dozen known-rotten ones.
        pool().forEach { lastFailedAt.putIfAbsent(it.token, PRIOR_GENERATION_STAMP) }

        poolRaw = fetched
        Log.i(TAG, "pool refreshed: $before -> ${pool().size} token(s)")
        // A fresh pool deserves a clean slate: dead flags describe the OLD tokens,
        // and a re-added token should not inherit a cooldown from its last life.
        // [lastFailedAt] is deliberately KEPT so tokens new in this fetch (no entry)
        // are probed before the ones we already know failed.
        deadUntil.clear()
        activePrimary = null
        authFailureStreak = 0
        runCatching {
            context.qbdlxCredentialsDataStore.edit { it[cachedPoolKey] = fetched }
        }
    }

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
     * Token → epoch-ms of its last auth failure. Drives selection order: a token we
     * have never probed sorts ahead of one we know failed, and among failed ones the
     * oldest failure goes first, so successive resolves work DOWN the pool.
     *
     * Pool-era ordering. QbdlxQobuzSource no longer rotates tokens at all — the
     * resolve path takes ONE file-url attempt through QbdlxFileUrlRouter — so the
     * only token that gets marked today is the user's own login, for which
     * [deadUntil] is a plain [DEAD_COOLDOWN_MS] cooldown before it is retried.
     * This ordering still governs the legacy pool, and stays because a pool that
     * probed only the head of the list never reached a live token further down
     * (device-verified 2026-08-15 against a 17-token pool).
     *
     * Deliberately SURVIVES a pool refresh: a freshly-added token has no entry here,
     * so it sorts first and is probed immediately instead of queueing behind the
     * known-dead tokens it was fetched to replace.
     */
    private val lastFailedAt = ConcurrentHashMap<String, Long>()

    /**
     * Consecutive auth failures with no successful use in between; reset by
     * [recordAlive]. See [REFRESH_FAILURE_STREAK] for why this, and not "every token
     * is dead", is what triggers a pool refresh.
     */
    @Volatile
    private var authFailureStreak = 0

    /**
     * Sticky primary: the token we keep using until it dies (replaces round-robin).
     * @Volatile for visibility only — two concurrent resolves both picking a live
     * token is benign (last write wins, no corruption). Nulled on markDead of the
     * primary so the next call advances. In-memory (per process).
     */
    @Volatile
    private var activePrimary: String? = null

    /**
     * Parsed pool. Each entry is `token:country[:appId]`:
     *  - `token`            → primary app_id, no country
     *  - `token:country`    → primary app_id (the legacy/bundled shape)
     *  - `token:country:appId` → tagged app_id (the remote pool, which spans
     *    more than one app_id — the tag is what lets us sign each token correctly
     *    instead of dropping the ones we couldn't sign).
     * Countries are ISO-2 and app_ids are 9-digit, so the tail is unambiguous;
     * the token (never containing ':') is whatever is left in front.
     */
    private fun pool(): List<PoolEntry> =
        poolRaw.split(",")
            .mapNotNull { entry ->
                val e = entry.trim().ifEmpty { return@mapNotNull null }
                val parts = e.split(":")
                when (parts.size) {
                    1 -> PoolEntry(parts[0], "", primaryAppId)
                    2 -> PoolEntry(parts[0], parts[1], primaryAppId)
                    else -> PoolEntry(
                        token = parts.dropLast(2).joinToString(":"),
                        country = parts[parts.size - 2],
                        appId = parts.last(),
                    )
                }
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
        pool().sortedWith(compareBy({ it.token.hashCode() }, { it.token }))
            .mapIndexed { i, entry ->
                QbdlxTokenChoice(
                    label = "Token ${i + 1}",
                    token = entry.token,
                    country = entry.country,
                    live = !isDead(entry.token),
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
        ensureCacheLoaded()
        // A user-connected account is the user's OWN paid subscription — it wins
        // over the shared pool and the anonymous paste, since it's the one token
        // guaranteed to be signed correctly (its app_id/secret are stored with it).
        loginCredential()?.let { if (!isDead(it.token)) return it.token }
        pastedToken()?.let { if (!isDead(it)) return it }
        // Only reached when the pasted token is absent/dead. Costs nothing unless
        // the whole pool is exhausted, which is precisely when it can help.
        refreshIfExhausted()
        pinnedToken()?.let { p ->
            if (!isDead(p) && pool().any { it.token == p }) return p
        }
        activePrimary?.let { if (!isDead(it)) return it }
        // Never-probed tokens first (no [lastFailedAt] entry → 0), then oldest failure
        // first, with the historical canonical order as the tiebreak so a healthy pool
        // still picks the same token on every device.
        val next = pool().map { it.token }
            .filter { !isDead(it) }
            .sortedWith(compareBy({ lastFailedAt[it] ?: 0L }, { it.hashCode() }, { it }))
            .firstOrNull() ?: return null
        activePrimary = next
        return next
    }

    /**
     * Live tokens to try for a region-locked track: country-matched first, then
     * the rest, capped at [MAX_REGION_TRIES].
     */
    suspend fun tokensForRegion(country: String?): List<String> {
        val live = pool().filter { !isDead(it.token) }
        val sorted = if (country.isNullOrBlank()) {
            live
        } else {
            live.sortedByDescending { it.country.equals(country, ignoreCase = true) }
        }
        return sorted.map { it.token }.take(MAX_REGION_TRIES)
    }

    /** Mark [token] dead for the cooldown window (auth failure). Auto-retried after. */
    fun markDead(token: String) {
        val now = clock()
        deadUntil[token] = now + DEAD_COOLDOWN_MS
        lastFailedAt[token] = now
        authFailureStreak++
        if (token == activePrimary) activePrimary = null
    }

    /** Clear a token's dead flag (a successful call, or a fresh paste). */
    fun recordAlive(token: String) {
        deadUntil.remove(token)
        lastFailedAt.remove(token)
        authFailureStreak = 0
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
        // This store is a process-wide singleton whose lazy login load is cached
        // by the FIRST availability/resolve check — by the time the user pastes,
        // that cache holds its null and nothing would ever migrate the new value.
        if (!t.isNullOrEmpty()) loginLoaded = false // re-arm: the next loginCredential() re-reads and migrates the paste
    }

    /**
     * True when there is NO usable token: none configured at all (no bundled
     * pool, no paste), or every configured one is currently dead. ZERO production
     * callers now — `LosslessAvailability` gates the source and drives the Settings
     * badge; this is kept for the pool-era tests, and Plan C deletes it with the
     * pool. A tokenless build MUST surface the paste prompt — an earlier "empty
     * pool isn't expired" guard here returned false instead, which hid the
     * v0.9.65–v0.9.68 blank BuildConfig credentials as silent per-track no_results.
     */
    suspend fun allDead(): Boolean {
        ensureCacheLoaded()
        // A live connected account is a usable credential all on its own.
        loginCredential()?.let { if (!isDead(it.token)) return false }
        val pasted = pastedToken()
        // Lets a fully-dead build heal itself: refreshing here means qbdlx can
        // come back without the user doing anything.
        refreshIfExhausted()
        val poolTokens = pool().map { it.token }
        if (poolTokens.isEmpty() && pasted == null) return true // no credentials at all
        pasted?.let { if (!isDead(it)) return false }
        return poolTokens.all { isDead(it) }
    }

    /** Test-only: wipe persisted pasted/login state + in-memory dead flags. */
    internal suspend fun clearPersistedForTest() {
        deadUntil.clear()
        cachedLogin = null
        loginLoaded = false
        context.qbdlxCredentialsDataStore.edit { it.clear() }
    }

    companion object {
        private const val TAG = "QbdlxPool"

        const val MAX_REGION_TRIES = 3

        // Dead-token cooldown before a token is retried (circuit-breaker style).
        // 60s, deliberately SHORT: a cooled login is one fewer file-url path
        // (LosslessAvailability.fileUrlAvailableNow, which the source's isEnabled
        // and isEnabledForStreaming now gate on; allDead() has no production
        // caller left — it is kept for the pool-era tests), so a
        // TRANSIENT failure (a preview/522/timeout on the shared account under
        // the download burst) that trips a mark-dead must not kill qbdlx for
        // long. 60s recovers fast; a genuinely-dead token just re-marks, costing
        // one doomed attempt per minute (negligible). Was 10min — far too long a
        // total blackout for a transient ("completely dead" until it aged out).
        const val DEAD_COOLDOWN_MS = 60_000L

        /**
         * Minimum gap between runtime pool-refresh ATTEMPTS. Only fires while
         * the pool is fully exhausted, so this bounds a genuinely-dead pool to
         * one webhook call every 15 minutes rather than one per resolve.
         */
        const val REFRESH_MIN_INTERVAL_MS = 15 * 60_000L

        /**
         * Consecutive auth failures (no success in between) that trigger a pool
         * refresh. [REFRESH_MIN_INTERVAL_MS] still bounds the resulting webhook
         * calls, so a looser trigger costs nothing.
         */
        const val REFRESH_FAILURE_STREAK = 6

        /**
         * Sentinel [lastFailedAt] value for tokens carried over from a pool we just
         * replaced. Older than any real failure timestamp but newer than "never
         * probed" (absent → 0), which is exactly the priority we want: brand-new
         * tokens, then untried carry-overs, then tokens we watched fail.
         */
        private const val PRIOR_GENERATION_STAMP = 1L
    }
}
