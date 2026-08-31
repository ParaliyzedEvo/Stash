package com.stash.data.download.lossless.qbdlx

import android.content.Context
import android.util.Log
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A user-connected Qobuz account: its token plus the app_id/secret it was minted under. */
data class QbdlxLoginCredential(val token: String, val appId: String, val appSecret: String)

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
 * The user's OWN connected Qobuz account — the only Qobuz credential Stash
 * holds. There is no bundled token pool and no bundled app_secret: the account
 * is connected through [QobuzAccountConnector], which mints the token under the
 * live-scraped web app_id/secret ([QobuzWebCredentials]) and stores that pair
 * alongside it.
 *
 * Responsibilities:
 *  - [hasLogin] / [loginLive] / [loginCredential]: is there a credential, and is
 *    it usable right now. These gate the source via
 *    [com.stash.data.download.lossless.LosslessAvailability].
 *  - [setUserCredential] / [clearUserCredential] / [connectedEmail]: connect,
 *    disconnect, and label the account in Settings.
 *  - [markDead] / [recordAlive] / [rejectLogin]: a [DEAD_COOLDOWN_MS]
 *    circuit-breaker so one transient 401 does not blank out lossless — or, for a
 *    migrated pasted token that cannot be re-minted, a clean disconnect.
 *  - [signingFor] (the [QbdlxSigningResolver]): hands the client the account's
 *    own (app_id, app_secret), because a Qobuz token signed with a mismatched
 *    secret silently downgrades to a 30-second preview.
 *
 * Also carries the one-shot migration for a token pasted back when the pool
 * still shipped — see [migratePastedToken].
 */
@Singleton
class QbdlxCredentialStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val webCreds: QobuzWebCredentials,
) : QbdlxSigningResolver {

    // ── User-connected account (bring-your-own Qobuz) ───────────────────────
    private val loginTokenKey = stringPreferencesKey("login_token")
    private val loginAppIdKey = stringPreferencesKey("login_app_id")
    private val loginAppSecretKey = stringPreferencesKey("login_app_secret")
    private val loginEmailKey = stringPreferencesKey("login_email")
    private val pastedTokenKey = stringPreferencesKey("pasted_token")

    // Keys of the removed shipped token pool. They exist ONLY to be deleted from
    // devices that upgraded past it; nothing writes them any more, and both can go
    // once upgrades from <= v0.9.100 stop mattering.
    private val STALE_POOL_KEY = stringPreferencesKey("cached_pool")     // held plaintext third-party tokens
    private val STALE_PINNED_KEY = stringPreferencesKey("pinned_token")  // one of those tokens

    @Volatile private var cachedLogin: QbdlxLoginCredential? = null
    @Volatile private var loginLoaded = false

    /**
     * Single-flights the one-time load in [loginCredential]. Without it the
     * check-then-act around [loginLoaded] lets every concurrent caller (one per
     * resolve via [com.stash.data.download.lossless.LosslessAvailability], and Home
     * and downloads fire several at once) run [migratePastedToken]'s live scrape.
     * Same shape as [QbdlxApiClient]'s heal mutex.
     */
    private val loadMutex = Mutex()

    /** Injectable clock (epoch ms) for the dead-credential cooldown; overridable in tests. */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    /** The connected account's email, for a "Connected as …" label. Null when none. */
    suspend fun connectedEmail(): String? =
        context.qbdlxCredentialsDataStore.data.first()[loginEmailKey]?.takeIf { it.isNotBlank() }

    /**
     * The connected account's email, or null — including for a MIGRATED pasted
     * token, which has none. Settings labels the account with it, so it must never
     * be what "is an account connected?" keys on: see [hasLogin].
     *
     * Do NOT reimplement [connectedEmail] on top of this flow: its `catch { emit(null) }`
     * turns a transient DataStore error into `null`, which [rejectLogin] reads as
     * "a migrated token" and would DISCONNECT A PAYING ACCOUNT. That function must
     * keep throwing — a label that briefly reads wrong is survivable; a wiped
     * credential is not.
     */
    val connectedEmailFlow: Flow<String?> =
        context.qbdlxCredentialsDataStore.data.map { p -> p[loginEmailKey]?.takeIf { it.isNotBlank() } }
            // Same reasoning as hasLogin: dedupe DataStore's re-emit on every
            // unrelated write, and fail closed so one read error cannot terminate
            // the combine this feeds for the rest of the process.
            .distinctUntilChanged()
            .catch { emit(null) }

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
            // DataStore re-emits the whole Preferences on every unrelated write,
            // and a read error must not terminate the combine this feeds for the
            // rest of the process — fail closed instead.
            .distinctUntilChanged()
            .catch { emit(false) }

    /** A connected account exists and is not inside a dead-cooldown. */
    suspend fun loginLive(): Boolean = loginCredential()?.let { !isDead(it.token) } ?: false

    /** The user-connected account, or null. Cached in memory after the first read. */
    suspend fun loginCredential(): QbdlxLoginCredential? {
        if (loginLoaded) return cachedLogin           // steady state stays lock-free
        loadMutex.withLock {
            if (loginLoaded) return cachedLogin       // lost the race — the winner already loaded
            val p = runCatching { context.qbdlxCredentialsDataStore.data.first() }.getOrNull()

            // The pool left the app; its cached tokens must leave the device too.
            purgeRetiredPoolKeys(p)

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
                    // This can run inside a bare launch{}; an edit{} or scrape
                    // failure here must not take the process down.
                    Log.w(TAG, "pasted-token migration failed: ${e.javaClass.simpleName}")
                }
            }
        }
        return cachedLogin
    }

    /**
     * Delete the removed pool's cached plaintext third-party tokens from disk.
     *
     * Called from two places, deliberately: [loginCredential] (so a resolve always
     * lands on a purged store) and once per install at startup (StashApplication),
     * because a user with lossless off never triggers a resolve and would otherwise
     * keep the tokens forever. Idempotent and non-throwing — a failed edit just
     * leaves the keys for the next call.
     *
     * The startup caller is in another Gradle module, so this entry point is public
     * and takes no [Preferences] — datastore is an `implementation` dep here.
     */
    suspend fun purgeRetiredPoolKeys() = purgeRetiredPoolKeys(
        runCatching { context.qbdlxCredentialsDataStore.data.first() }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull(),
    )

    /** [p] is the Preferences the caller already read, so the resolve path skips a second read. */
    private suspend fun purgeRetiredPoolKeys(p: Preferences?) {
        if (p?.contains(STALE_POOL_KEY) != true && p?.contains(STALE_PINNED_KEY) != true) return
        runCatching {
            context.qbdlxCredentialsDataStore.edit { it.remove(STALE_POOL_KEY); it.remove(STALE_PINNED_KEY) }
        }
            // Only claim the purge when it actually happened — logging success over a
            // failed edit would assert that plaintext tokens are gone while they remain.
            .onSuccess { Log.i(TAG, "purged cached pool credentials left over from the shipped token pool") }
            .onFailure { if (it is CancellationException) throw it }
    }

    /**
     * One-shot upgrade path for a token pasted before the pool left the app. The
     * pair is scraped live ([QobuzWebCredentials]) rather than bundled — this app
     * ships no Qobuz app_secret. A failed scrape leaves `pasted_token` in place so
     * the next launch can still migrate it — this load is cached for the life of
     * the process, so nothing retries before then.
     */
    private suspend fun migratePastedToken() {
        val pasted = pastedToken() ?: return
        val creds = webCreds.fetch() ?: run {
            Log.i(TAG, "pasted token not migrated: web credentials unavailable — will retry on next launch")
            return
        }
        Log.i(TAG, "migrating pasted token into the connected-account slot")
        setUserCredential(pasted, creds.appId, creds.appSecret, email = null)
        // Only drop the value we actually migrated — a token pasted concurrently
        // must not be swallowed by this cleanup.
        context.qbdlxCredentialsDataStore.edit { if (it[pastedTokenKey] == pasted) it.remove(pastedTokenKey) }
    }

    /**
     * Persist a connected account (token + the app_id/secret it was minted under).
     *
     * [email] is REQUIRED, with no default: it is what [rejectLogin] keys on, so
     * passing null classifies the credential as disposable — disconnected on the
     * first 401. A real logged-in account must pass its email; only the pasted-token
     * migration, which has nothing to re-mint from, passes null. Defaulting it would
     * make the destructive case the silent one.
     */
    suspend fun setUserCredential(token: String, appId: String, appSecret: String, email: String?) {
        recordAlive(token)
        context.qbdlxCredentialsDataStore.edit {
            it[loginTokenKey] = token; it[loginAppIdKey] = appId; it[loginAppSecretKey] = appSecret
            if (email.isNullOrBlank()) it.remove(loginEmailKey) else it[loginEmailKey] = email
        }
        cachedLogin = QbdlxLoginCredential(token, appId, appSecret)
        loginLoaded = true
    }

    /** Disconnect the account. Also drops any pasted token, so a disconnect sticks. */
    suspend fun clearUserCredential() {
        context.qbdlxCredentialsDataStore.edit {
            it.remove(loginTokenKey); it.remove(loginAppIdKey); it.remove(loginAppSecretKey); it.remove(loginEmailKey)
            // Without this, a migration whose cleanup edit failed would re-migrate,
            // re-reject and re-clear on every process start, forever.
            it.remove(pastedTokenKey)
        }
        cachedLogin = null
        loginLoaded = true
    }

    /**
     * The (app_id, app_secret) to sign [token]'s requests with. Only the
     * connected account has one — it is stored with the token precisely so the
     * pair can never drift from what the token was minted under.
     */
    override suspend fun signingFor(token: String): QbdlxSigning {
        loginCredential()?.let { if (it.token == token) return QbdlxSigning(it.appId, it.appSecret) }
        // Reachable: QbdlxFileUrlRouter reads the login, then the client re-reads it
        // here, so a disconnect or account swap in between orphans this token. Throw
        // rather than sign with an empty pair — the router's QbdlxAuthException catch
        // reaches the same TokenDead outcome without spending a round-trip on a
        // request that could only log a misleading "auth 401".
        Log.w(TAG, "signingFor called for a token that is not the connected account")
        throw QbdlxAuthException(401, "signing pair no longer matches the connected account")
    }

    // ── Dead-credential circuit breaker ─────────────────────────────────────

    /**
     * Token → epoch-ms until which it is considered dead. IN-MEMORY and
     * TIME-BOXED (circuit-breaker style), deliberately NOT persisted: a single
     * transient auth failure (a cold-start network blip, or a 401 from the same
     * account being used concurrently across apps/the website) must NOT
     * permanently disable it. It is skipped for [DEAD_COOLDOWN_MS] then
     * auto-retried; a genuinely-dead token just re-marks. A process restart also
     * clears it. This replaces an earlier persisted, permanent dead-set that left
     * the credential stuck on one transient 401 ("token expired" forever).
     */
    private val deadUntil = ConcurrentHashMap<String, Long>()

    /** True when [token] is within its dead cooldown. Cleans up expired entries. */
    private fun isDead(token: String): Boolean {
        val until = deadUntil[token] ?: return false
        if (clock() < until) return true
        deadUntil.remove(token) // cooldown elapsed — give it another chance
        return false
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
     * A connected account's token was rejected. A real account (it has an email)
     * gets the deliberate [DEAD_COOLDOWN_MS] cooldown — a transient 401 must not
     * disconnect someone. A MIGRATED pasted token (no email) has nothing to re-mint
     * from and was signed with a scraped pair Qobuz may simply refuse, so rejection
     * is terminal: clear it, and let Settings say "not configured" truthfully.
     *
     * Runs inside [QbdlxQobuzSource]'s `callLimited`, whose generic catch would turn
     * any throw here into a health failure against the catalog breaker — a dead
     * credential must not trip it. So both store touches are guarded, and an
     * unreadable store fails CLOSED to [markDead]: never disconnect a paying account
     * because a DataStore read blew up.
     */
    suspend fun rejectLogin(token: String) {
        val migrated = runCatching { connectedEmail() == null }
            .onFailure { if (it is CancellationException) throw it }
            .getOrDefault(false)
        if (migrated) {
            runCatching { clearUserCredential() }.onFailure { if (it is CancellationException) throw it }
        } else {
            markDead(token)
        }
    }

    // ── Legacy pasted token (migration only) ────────────────────────────────

    private suspend fun pastedToken(): String? =
        context.qbdlxCredentialsDataStore.data.first()[pastedTokenKey]?.takeIf { it.isNotBlank() }

    /**
     * Set (or clear, with null) the legacy user-pasted token. No production caller
     * left — the paste field went with the pool; this exists so
     * [migratePastedToken] stays testable.
     */
    internal suspend fun setPastedToken(token: String?) {
        val t = token?.trim()
        if (!t.isNullOrEmpty()) recordAlive(t)
        context.qbdlxCredentialsDataStore.edit { prefs ->
            if (t.isNullOrEmpty()) prefs.remove(pastedTokenKey) else prefs[pastedTokenKey] = t
        }
        // This store is a process-wide singleton whose lazy login load is cached by
        // the FIRST availability/resolve check — without re-arming, that cache holds
        // its null and nothing would ever migrate the new value.
        if (!t.isNullOrEmpty()) loginLoaded = false // re-arm: the next loginCredential() re-reads and migrates
    }

    /** Test-only: the backing store — the delegate above is file-private, so tests seed/read raw keys through here. */
    internal val dataStoreForTest: DataStore<Preferences> get() = context.qbdlxCredentialsDataStore

    /** Test-only: wipe persisted pasted/login state + in-memory dead flags. */
    internal suspend fun clearPersistedForTest() {
        deadUntil.clear()
        cachedLogin = null
        loginLoaded = false
        context.qbdlxCredentialsDataStore.edit { it.clear() }
    }

    companion object {
        private const val TAG = "QbdlxCreds"

        // Dead-credential cooldown before the account is retried (circuit-breaker
        // style). 60s, deliberately SHORT: a cooled login is one fewer file-url path
        // (LosslessAvailability.fileUrlAvailableNow, which the source's isEnabled and
        // isEnabledForStreaming gate on), so a TRANSIENT failure (a preview/522/
        // timeout under a download burst) that trips a mark-dead must not kill qbdlx
        // for long. 60s recovers fast; a genuinely-dead token just re-marks, costing
        // one doomed attempt per minute (negligible). Was 10min — far too long a
        // total blackout for a transient.
        const val DEAD_COOLDOWN_MS = 60_000L
    }
}
