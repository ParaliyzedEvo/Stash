package com.stash.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private const val MIN_DURATION_MS = 1000L
private const val MAX_DURATION_MS = 12000L
private const val DEFAULT_DURATION_MS = 6000L

/** Clamps a crossfade duration to the supported 1000..12000 ms range. */
fun clampCrossfadeMs(value: Long): Long = value.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)

/** Singleton DataStore for crossfade preferences, separate from other preference stores. */
private val Context.crossfadeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "crossfade_preferences",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Persists the user's crossfade toggle and fade duration via DataStore. */
@Singleton
class CrossfadePreferencesManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : CrossfadePreference {
    private val enabledKey = booleanPreferencesKey("crossfade_enabled")
    private val durationKey = longPreferencesKey("crossfade_duration_ms")

    /** Emits whether crossfade is enabled, defaulting to `false`. */
    override val enabled: Flow<Boolean> = context.crossfadeDataStore.data.map { prefs ->
        prefs[enabledKey] ?: false
    }

    /** Emits the fade duration in ms, defaulting to 6000 and clamped to the supported range. */
    override val durationMs: Flow<Long> = context.crossfadeDataStore.data.map { prefs ->
        clampCrossfadeMs(prefs[durationKey] ?: DEFAULT_DURATION_MS)
    }

    /** Persists the crossfade on/off toggle. */
    override suspend fun setEnabled(value: Boolean) {
        context.crossfadeDataStore.edit { prefs ->
            prefs[enabledKey] = value
        }
    }

    /** Persists the fade duration, clamped to the supported range. */
    override suspend fun setDurationMs(value: Long) {
        context.crossfadeDataStore.edit { prefs ->
            prefs[durationKey] = clampCrossfadeMs(value)
        }
    }
}
