package com.stash.core.data.prefs

import kotlinx.coroutines.flow.Flow

/**
 * Abstraction for reading and writing the user's crossfade preference: an
 * on/off toggle and a fade duration in milliseconds.
 *
 * Lives in `:core:data` so feature modules can inject it without depending on
 * a concrete preferences implementation. The DataStore-backed implementation
 * is [CrossfadePreferencesManager] and is bound via Hilt.
 */
interface CrossfadePreference {

    /** Emits whether crossfade is enabled, defaulting to `false`. */
    val enabled: Flow<Boolean>

    /** Emits the fade duration in ms, defaulting to 6000 and clamped to 1000..12000. */
    val durationMs: Flow<Long>

    /** Persists the crossfade on/off toggle. */
    suspend fun setEnabled(value: Boolean)

    /** Persists the fade duration, clamped to 1000..12000 via [clampCrossfadeMs]. */
    suspend fun setDurationMs(value: Long)
}
