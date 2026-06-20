package com.stash.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Dedicated DataStore for the online-streaming engine toggle, cellular
 *  allow-toggle, and stream-quality tier. */
private val Context.streamingDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "streaming_preference",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Stream-quality tier. Persisted as the enum's `name` string. */
enum class StreamQualityTier { LOSSLESS, HIGH_QUALITY_LOSSY }

/**
 * User-facing preferences for the online-streaming engine:
 *
 *  - [enabled] — master toggle. When `false`, the app stays in pure
 *    download-and-play mode (current behavior). When `true`, the
 *    streaming source factory is wired into the player.
 *  - [streamOnCellular] — whether streaming is allowed on a metered
 *    network. Default `false` so users don't burn data unintentionally.
 *  - [streamQuality] — preferred lossless vs high-quality-lossy tier
 *    used when resolving stream URLs.
 *
 * Default is `enabled = false` — preserves current download-only behavior
 * for the existing install base. The user opts in to streaming.
 */
@Singleton
class StreamingPreference @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val enabledKey = booleanPreferencesKey("streaming_enabled")
    private val cellularKey = booleanPreferencesKey("streaming_on_cellular")
    private val qualityKey = stringPreferencesKey("streaming_quality_tier")
    private val forceYouTubeFallbackKey = booleanPreferencesKey("force_youtube_fallback")
    private val forceAmzOnlyKey = booleanPreferencesKey("force_amz_only")
    // Retained only so [purgeRetiredKeys] can delete it from existing
    // installs; the antra source was removed (see fix/remove-antra).
    private val forceAntraOnlyKey = booleanPreferencesKey("force_antra_only")

    val enabled: Flow<Boolean> = context.streamingDataStore.data.map { prefs ->
        prefs[enabledKey] ?: true
    }

    val streamOnCellular: Flow<Boolean> = context.streamingDataStore.data.map { prefs ->
        prefs[cellularKey] ?: false
    }

    val streamQuality: Flow<StreamQualityTier> = context.streamingDataStore.data.map { prefs ->
        runCatching { StreamQualityTier.valueOf(prefs[qualityKey] ?: "") }
            .getOrDefault(StreamQualityTier.LOSSLESS)
    }

    /**
     * Test-only toggle. When `true`, [StreamSourceRegistry] skips Kennyy
     * and Squid and resolves every streaming track via the YouTube
     * fallback resolver only — used to reproduce the lossless-down path
     * on demand. Default `false` (normal use).
     */
    val forceYouTubeFallback: Flow<Boolean> = context.streamingDataStore.data.map { prefs ->
        prefs[forceYouTubeFallbackKey] ?: false
    }

    /**
     * Test-only toggle. When `true`, BOTH the streaming registry
     * ([StreamSourceRegistry]) and the lossless-download registry
     * ([LosslessSourceRegistry]) route through the amz (Amazon Music)
     * source ONLY — Kennyy, Squid, and YouTube are removed from play so a
     * track either resolves via amz or fails visibly. Used to exercise the
     * amz source on demand (it normally ranks last and is hard to trigger).
     * Default `false` (normal use).
     */
    val forceAmzOnly: Flow<Boolean> = context.streamingDataStore.data.map { prefs ->
        prefs[forceAmzOnlyKey] ?: false
    }

    suspend fun current(): Boolean = enabled.first()

    suspend fun isForceYouTubeFallback(): Boolean = forceYouTubeFallback.first()

    suspend fun isForceAmzOnly(): Boolean = forceAmzOnly.first()

    suspend fun setEnabled(value: Boolean) {
        context.streamingDataStore.edit { it[enabledKey] = value }
    }

    suspend fun setStreamOnCellular(value: Boolean) {
        context.streamingDataStore.edit { it[cellularKey] = value }
    }

    suspend fun setForceYouTubeFallback(value: Boolean) {
        context.streamingDataStore.edit { it[forceYouTubeFallbackKey] = value }
    }

    suspend fun setForceAmzOnly(value: Boolean) {
        context.streamingDataStore.edit { it[forceAmzOnlyKey] = value }
    }

    suspend fun setStreamQuality(tier: StreamQualityTier) {
        context.streamingDataStore.edit { it[qualityKey] = tier.name }
    }

    /**
     * One-shot cleanup for the removed antra source: deletes the retired
     * `force_antra_only` toggle from existing installs. Called once at
     * startup (see StashApplication). No-op when the key is already absent.
     */
    suspend fun purgeRetiredKeys() {
        context.streamingDataStore.edit { it.remove(forceAntraOnlyKey) }
    }
}
