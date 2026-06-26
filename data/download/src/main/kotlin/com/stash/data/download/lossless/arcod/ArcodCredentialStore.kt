package com.stash.data.download.lossless.arcod

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The Supabase auth session persisted for arcod.xyz: the JWT access token used
 * as a Bearer credential on every API call, the refresh token used to mint a
 * new access token when the old one expires, and the wall-clock epoch-millis at
 * which the access token expires.
 */
data class ArcodSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMs: Long,
)

/**
 * Its own preferences DataStore so the arcod credentials live apart from the
 * cross-source [com.stash.data.download.lossless.LosslessSourcePreferences]
 * schema — they're a distinct concern (a per-user OAuth session) with a
 * different lifecycle (minted/refreshed/cleared on auth events).
 */
private val Context.arcodCredentialsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "arcod_credentials",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * DataStore-backed store for the arcod.xyz Supabase session.
 *
 * ARCOD authenticates with a Supabase session (JWT) obtained via Google OAuth.
 * This store persists that session so the resolver/interceptor can attach the
 * Bearer token to requests and refresh it when it expires.
 *
 * A session is considered present only when both the access and refresh tokens
 * are non-blank — a half-written session (e.g. an access token with no refresh
 * token to renew it) is treated as no session at all.
 */
@Singleton
class ArcodCredentialStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val accessTokenKey = stringPreferencesKey("arcod_access_token")
    private val refreshTokenKey = stringPreferencesKey("arcod_refresh_token")
    private val expiresAtMsKey = longPreferencesKey("arcod_expires_at_ms")

    /** Emits the current access token, or null when absent/blank. */
    val accessToken: Flow<String?> = context.arcodCredentialsDataStore.data.map { prefs ->
        prefs[accessTokenKey]?.takeIf { it.isNotBlank() }
    }

    /** One-shot read of the current access token (null when absent/blank). */
    suspend fun accessTokenNow(): String? = accessToken.first()

    /** True when a non-blank access token is present. */
    suspend fun isConnected(): Boolean = accessTokenNow() != null

    /**
     * The full session, or null when either token is absent/blank. Both the
     * access and refresh tokens must be present — without a refresh token the
     * session can't survive its first expiry, so it's not a usable session.
     */
    suspend fun session(): ArcodSession? {
        val prefs = context.arcodCredentialsDataStore.data.first()
        val access = prefs[accessTokenKey]?.takeIf { it.isNotBlank() } ?: return null
        val refresh = prefs[refreshTokenKey]?.takeIf { it.isNotBlank() } ?: return null
        val expiresAt = prefs[expiresAtMsKey] ?: 0L
        return ArcodSession(accessToken = access, refreshToken = refresh, expiresAtMs = expiresAt)
    }

    /** Persists a freshly-minted or refreshed Supabase session. */
    suspend fun save(accessToken: String, refreshToken: String, expiresAtMs: Long) {
        context.arcodCredentialsDataStore.edit { prefs ->
            prefs[accessTokenKey] = accessToken
            prefs[refreshTokenKey] = refreshToken
            prefs[expiresAtMsKey] = expiresAtMs
        }
    }

    /**
     * Clears the persisted session. Called when the session can no longer be
     * refreshed (e.g. refresh token rejected) and the user must reconnect.
     */
    suspend fun markStale() {
        context.arcodCredentialsDataStore.edit { prefs ->
            prefs.remove(accessTokenKey)
            prefs.remove(refreshTokenKey)
            prefs.remove(expiresAtMsKey)
        }
    }
}
