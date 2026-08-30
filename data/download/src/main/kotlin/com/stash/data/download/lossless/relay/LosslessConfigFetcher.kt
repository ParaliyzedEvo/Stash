package com.stash.data.download.lossless.relay

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stash.data.download.BuildConfig
import com.stash.data.download.lossless.LosslessSourcePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** One relay base from the signed config; lower [priority] is tried first. */
@Serializable
data class RelayEntry(val base: String, val priority: Int = 1)

@Serializable
data class LosslessConfig(
    val v: Int = 1,
    val relays: List<RelayEntry> = emptyList(),
    @SerialName("updated_at") val updatedAt: Long = 0,
)

private val Context.losslessRelayConfigDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "lossless_relay_config",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * The runtime relay list. The APK ships NO relay hostname: this fetches
 * `<configUrl>` + `<configUrl>.sig` (ECDSA P-256 over the exact JSON bytes),
 * verifies against the baked-in public key, caches the JSON, and exposes
 * [relays] sorted by priority. Invalid signature / network failure → the cached
 * copy stays; no cache → no relays. Both BuildConfig values empty → disabled.
 */
@Singleton
class LosslessConfigFetcher @Inject constructor(
    @ApplicationContext private val context: Context,
    sharedClient: OkHttpClient,
) {
    internal var configUrl: String = BuildConfig.LOSSLESS_CONFIG_URL
    internal var publicKeyB64: String = BuildConfig.LOSSLESS_CONFIG_PUBKEY
    internal var httpClient: OkHttpClient = sharedClient
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonKey = stringPreferencesKey("config_json")

    private val _relays = MutableStateFlow<List<RelayEntry>>(emptyList())
    val relays: StateFlow<List<RelayEntry>> = _relays.asStateFlow()

    val enabled: Boolean get() = configUrl.isNotBlank() && publicKeyB64.isNotBlank()

    /** Populate [relays] from the cached JSON, if any. Cheap; call before the first resolve. */
    suspend fun loadCached() {
        val cached = ioCatching { context.losslessRelayConfigDataStore.data.first()[jsonKey] } ?: return
        parse(cached)?.let { _relays.value = it }
    }

    /** Fetch + verify + apply. Returns true only when a fresh, valid config was applied. Never throws. */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext false
        val body = ioCatching { getBytes(configUrl) } ?: return@withContext false
        val sig = ioCatching { String(getBytes("$configUrl.sig")).trim() } ?: return@withContext false
        // Verified over the bytes exactly as fetched — never a re-serialisation.
        if (!verify(body, sig)) {
            Log.w(TAG, "lossless.json signature invalid — keeping the cached copy")
            return@withContext false
        }
        val text = String(body)
        val parsed = parse(text) ?: return@withContext false
        _relays.value = parsed
        ioCatching { context.losslessRelayConfigDataStore.edit { it[jsonKey] = text } }
        Log.i(TAG, "lossless config applied: ${parsed.size} relay(s)")
        true
    }

    /** Load the cache, then refresh now and every [REFRESH_INTERVAL_MS]. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            loadCached()
            while (true) {
                refresh()
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    internal fun verify(bytes: ByteArray, sigB64: String): Boolean = runCatching {
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyB64)))
        Signature.getInstance("SHA256withECDSA").run { initVerify(pub); update(bytes); verify(Base64.getDecoder().decode(sigB64)) }
    }.getOrDefault(false)

    private fun parse(text: String): List<RelayEntry>? = runCatching {
        json.decodeFromString<LosslessConfig>(text).relays
            .mapNotNull { e -> LosslessSourcePreferences.normaliseEndpoint(e.base)?.let { RelayEntry(it, e.priority) } }
            .sortedBy { it.priority }
    }.getOrNull()

    private fun getBytes(url: String): ByteArray {
        val req = Request.Builder().url(url).header("Accept", "*/*").get().build()
        httpClient.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return r.body?.bytes() ?: ByteArray(0)
        }
    }

    /**
     * Fetching config must never crash the app, so anything short of a
     * cancellation degrades to null — but a cancelled scope has to stay
     * cancelled (same rule as `HomeDiscoveryRepositoryImpl.cached`).
     */
    private inline fun <T> ioCatching(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "lossless config I/O failed (${e.javaClass.simpleName})")
        null
    }

    internal suspend fun clearForTest() {
        _relays.value = emptyList()
        context.losslessRelayConfigDataStore.edit { it.clear() }
    }

    private companion object {
        const val TAG = "LosslessConfig"
        const val REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}
