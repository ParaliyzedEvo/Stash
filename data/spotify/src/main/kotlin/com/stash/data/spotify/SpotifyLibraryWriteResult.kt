package com.stash.data.spotify

/**
 * Outcome of a library add/remove mutation ([SpotifyApiClient.addToLibrary] /
 * [SpotifyApiClient.removeFromLibrary]).
 *
 * A sealed result rather than thrown exceptions because this module
 * (`data:spotify`) can't reference `core:data`'s exception types without a
 * dependency cycle. The caller in `core:data`
 * (`SpotifyLibraryApiClient`) maps these onto its existing
 * `SpotifyRateLimitException` / `SpotifyAuthException` / `SpotifyApiException`
 * so the like-mirroring pacing contract stays unchanged.
 */
sealed interface SpotifyLibraryWriteResult {
    /** The mutation was accepted (or was a harmless no-op — idempotent). */
    data object Success : SpotifyLibraryWriteResult

    /** 429 — the coordinator honours [retryAfterSeconds] before the next burst. */
    data class RateLimited(val retryAfterSeconds: Int?) : SpotifyLibraryWriteResult

    /** 401 — the access token was rejected (already force-refreshed by the client). */
    data object AuthFailed : SpotifyLibraryWriteResult

    /** Any other failure — [message] is best-effort diagnostics. */
    data class Failed(val message: String) : SpotifyLibraryWriteResult
}
