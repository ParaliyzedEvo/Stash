package com.stash.core.media

/**
 * How [PlayerRepositoryImpl]'s `onPlayerError` should treat a playback
 * failure on the current item.
 */
enum class PlaybackErrorPolicy {
    /**
     * A genuinely local item (downloaded file) failed to decode — a corrupt
     * file or codec edge case. Per-track: skip it and move on. Does NOT arm
     * the streaming cascade (one bad download isn't a backend outage).
     */
    LOCAL_SKIP,

    /**
     * A streamed item failed — whether a 403/network drop OR a `200 OK` that
     * served empty/garbage bytes (surfaced as ERROR_CODE_PARSING_CONTAINER_MALFORMED).
     * Both are stream-source failures, so the error goes through
     * [StreamErrorCascadeGuard]: recover (skip) until the threshold, then HALT.
     * This is what stops a degraded source from machine-gunning the queue.
     */
    STREAMING_CASCADE,
}

/** URI schemes that identify an on-disk/local item (not a network stream). */
private val LOCAL_SCHEMES = setOf("file", "content", "android.resource", "rawresource", "asset")

/**
 * Decides the recovery policy for a playback error from the current item's
 * URI scheme.
 *
 * Only definitively-local schemes are per-track skips. Everything else —
 * `http(s)` streams AND a `null` scheme (a streaming-only item that reached
 * the player with no resolved URI) — is treated as a streaming-source failure
 * so it counts toward the cascade guard and can halt. Defaulting the unknown/
 * null case to [PlaybackErrorPolicy.STREAMING_CASCADE] is deliberate: it
 * guarantees a URI-less or unexpectedly-schemed item can never re-trigger the
 * unbounded skip-storm this fix exists to kill.
 */
fun classifyPlaybackError(uriScheme: String?): PlaybackErrorPolicy =
    if (uriScheme?.lowercase() in LOCAL_SCHEMES) PlaybackErrorPolicy.LOCAL_SKIP
    else PlaybackErrorPolicy.STREAMING_CASCADE
