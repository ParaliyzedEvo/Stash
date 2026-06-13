package com.stash.data.spotify

/** One track from GET /v1/search?type=track. */
data class SpotifyTrackCandidate(
    val id: String,             // bare id -> "spotify:track:$id"
    val name: String,
    val artists: List<String>,  // artists[].name, primary first
    val albumName: String,
    val durationMs: Long,
    val isrc: String?,          // external_ids.isrc (often null on /search)
    val explicit: Boolean,
)

/** Thrown on Spotify 429 so callers can back off (vs a genuine no-match). */
class SpotifyRateLimitException(val retryAfterSeconds: Long?) :
    Exception("Spotify rate limited (Retry-After=${retryAfterSeconds}s)")
