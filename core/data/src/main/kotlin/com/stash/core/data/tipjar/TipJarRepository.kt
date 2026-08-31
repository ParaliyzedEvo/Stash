package com.stash.core.data.tipjar

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * v0.9.13: Fetches the public supporters JSON, caches it, and exposes
 * the parsed [TipJarState] via [state].
 *
 * Lifecycle:
 *  - Constructor initialises [state] with [TipJarState.EMPTY] AND a
 *    bundled fallback (the original `HOME_SUPPORTERS` 3-row list)
 *    so the UI never shows a blank pill on first launch.
 *  - On [refresh], an HTTP GET against the configured URL replaces
 *    the in-memory state and persists the raw JSON string to
 *    DataStore (`last_payload`), keyed by fetch timestamp.
 *  - On cold-start, [state] is seeded from the persisted JSON before
 *    any network call.
 *  - Failure modes: timeouts, malformed JSON, 404s — all silently
 *    fall back to the previous good state. The pill never disappears.
 *
 * Refresh policy is set by callers (typically `HomeViewModel`):
 * `refresh()` is called on Home foreground if the cache is stale
 * (>15 min). Background refresh is intentionally absent — there's no
 * point burning battery for a vanity surface.
 */
private val Context.tipJarDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "tip_jar_cache",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

@Singleton
class TipJarRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
    @SupportersJsonUrl private val supportersUrl: String,
) {
    private val payloadKey = stringPreferencesKey("payload_json")
    private val fetchedAtKey = longPreferencesKey("fetched_at_ms")

    private val _state = MutableStateFlow(TipJarState.EMPTY)
    val state: StateFlow<TipJarState> = _state.asStateFlow()

    /** Last successful fetch wallclock — gates [shouldRefresh]. */
    val lastFetchedAtMs: Flow<Long> = context.tipJarDataStore.data
        .map { prefs -> prefs[fetchedAtKey] ?: 0L }

    /**
     * Hydrate from disk + bundled fallback. Call once at app startup
     * (e.g. from `StashApplication` or the first VM that touches
     * the repo) so the pill has data on first composition.
     */
    suspend fun warmUp() {
        val cached = context.tipJarDataStore.data.first()[payloadKey]
        val parsed = cached?.let { runCatching { JSON.decodeFromString<TipJarPayload>(it) }.getOrNull() }
        if (parsed != null) {
            _state.value = parsed.toState()
        } else {
            _state.value = BUNDLED_FALLBACK.toState()
        }
    }

    /**
     * Force-refresh from the network. No-op on failure — state stays
     * at the last good value, no exceptions propagate to callers.
     * Returns true on success, false on any failure (caller can
     * surface a snackbar if desired; HomeViewModel ignores the
     * return value and trusts the Flow to update).
     */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        // No supporters host configured (BuildConfig missing) — nothing to fetch.
        // OkHttp's .url("") throws IllegalArgumentException, so bail first.
        if (supportersUrl.isBlank()) return@withContext false
        val req = Request.Builder()
            .url(supportersUrl)
            .header("Cache-Control", "no-cache")
            .build()
        val raw = try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "tip-jar fetch HTTP ${resp.code}")
                    return@withContext false
                }
                resp.body?.string()
            }
        } catch (e: IOException) {
            Log.w(TAG, "tip-jar fetch network failed: ${e.message}")
            return@withContext false
        } ?: return@withContext false

        val parsed = runCatching { JSON.decodeFromString<TipJarPayload>(raw) }
            .onFailure { Log.w(TAG, "tip-jar JSON parse failed", it) }
            .getOrNull() ?: return@withContext false

        _state.value = parsed.toState()
        context.tipJarDataStore.edit { prefs ->
            prefs[payloadKey] = raw
            prefs[fetchedAtKey] = System.currentTimeMillis()
        }
        true
    }

    /**
     * Returns true if the cache is older than [staleAfterMs] (default
     * 15 min) and a network refresh should be triggered. Callers
     * combine this with their lifecycle hook (e.g. Home foreground).
     */
    suspend fun isStale(staleAfterMs: Long = 15 * 60_000L): Boolean {
        val last = lastFetchedAtMs.first()
        return last == 0L || (System.currentTimeMillis() - last) > staleAfterMs
    }

    companion object {
        private const val TAG = "TipJarRepository"

        private val JSON = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        /**
         * Always-available fallback so first-launch (no cache, no
         * network) still renders. Keep this in sync with the
         * production JSON's seed values; if they drift, fresh
         * installs see slightly stale data until the first network
         * refresh succeeds — non-critical.
         */
        // Mirrors docs/supporters.json so cold-start (no network, no cache)
        // still renders something. Update whenever the JSON changes.
        private val BUNDLED_FALLBACK = TipJarPayload(
            supporters = listOf(
                Supporter("Cedric", 10, "Just downloaded Stash to replace Spotify. This is amazing bro. Thanks for your work."),
                Supporter("Slowcab", 5, "Amazing work! Keep sticking it to the man!"),
                Supporter("RucaNebas", 5, "Awesome application! I hope continuous improvement and support"),
            ),
        )
    }
}

/**
 * Hilt qualifier so [TipJarRepository]'s constructor parameter is
 * unambiguous: the URL is provided by the app module via
 * `BuildConfig.SUPPORTERS_JSON_URL` and binding it requires a
 * qualifier to distinguish from the empty `String` default.
 */
@javax.inject.Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SupportersJsonUrl
