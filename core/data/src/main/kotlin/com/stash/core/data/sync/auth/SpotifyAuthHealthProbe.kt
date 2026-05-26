package com.stash.core.data.sync.auth

import com.stash.data.spotify.SpotifyApiClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpotifyAuthHealthProbe @Inject constructor(
    @Suppress("unused") private val api: SpotifyApiClient,
) : AuthHealthProbe {
    override val source: AuthSource = AuthSource.SPOTIFY

    /**
     * Always reports healthy (false = not expired) until a proper HTTP-based
     * Spotify auth probe is wired.
     *
     * Why: the original implementation used `api.getCurrentUserProfile() == null`
     * as the expired signal, but `getCurrentUserProfile()` is a local cache
     * lookup (`tokenManager.getSpotifyUsername()`), NOT an HTTP call. Users with
     * incomplete stored metadata — legacy auth flows or migration drift —
     * return null even with valid cookies, causing the probe to wrongly block
     * sync. See issue (TODO: file follow-up) for the proper fix: add
     * `SpotifyApiClient.isAuthHealthy()` that hits a cheap authenticated
     * GraphQL endpoint and returns 3-state Valid/Invalid/Unknown.
     *
     * Real Spotify auth failures during a sync still get caught — the per-row
     * `DownloadFailureClassifier` (Task 7) maps 401/403 responses to
     * `AUTH_EXPIRED` and surfaces them in the Failed Downloads viewer.
     */
    override suspend fun isExpired(): Boolean {
        // Keep `api` field referenced so Hilt doesn't strip the injection;
        // the placeholder will go away when isAuthHealthy() is added.
        return false
    }
}
