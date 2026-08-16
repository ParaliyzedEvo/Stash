package com.stash.core.media

/**
 * Decides how [PlayerRepositoryImpl] reacts to a streamed-track playback error.
 *
 * Two behaviours in one small state machine:
 *
 *  1. **Retry the same track first.** A streamed track that hits a transient
 *     error — a mobile-network stall, a dropped CDN connection, an expired URL —
 *     used to skip straight to the next track on the FIRST error. That is the
 *     "songs randomly cut off and skip" cluster (#420/401/334/266/209): one blip
 *     on a cellular connection = a lost track. The first error for a given item
 *     now returns [Verdict.RetrySameItem] so the player re-prepares it in place
 *     (position preserved); only if it keeps failing do we fall through to skip.
 *     Bounded to ONE retry per item so a genuinely-dead URL cannot loop.
 *
 *  2. **Bound the skip cascade.** Once retries are exhausted, skips accumulate;
 *     after [threshold] of them with no successful playback or user transport in
 *     between, return [Verdict.Halt] instead of silently draining the queue.
 *
 * Not thread-safe by itself — caller must invoke from the single MediaController
 * callback thread (which is what we already do).
 */
class StreamErrorCascadeGuard(
    private val threshold: Int = 3,
) {
    init { require(threshold >= 1) { "threshold must be >= 1, got $threshold" } }

    private var consecutiveErrors: Int = 0

    /**
     * The item that has already spent its one same-track retry. Kept across
     * [onPlaybackStarted] on purpose: a track that reaches READY and then errors
     * again immediately must NOT re-retry (that is the infinite-retry trap), only
     * a deliberate [onUserTransport] rearms it.
     */
    private var retriedItemKey: String? = null

    sealed class Verdict {
        /** Re-prepare the CURRENT item in place before considering a skip. */
        data object RetrySameItem : Verdict()
        /** Skip to the next item. */
        data object Recover : Verdict()
        /** Stop skipping and halt — the backend is cascading. */
        data class Halt(val consecutiveErrors: Int) : Verdict()
    }

    /**
     * @param itemKey a stable identity for the failing item (its mediaId). The
     *   first error for a not-yet-retried item retries it; `null` (no stable id)
     *   skips retry and counts immediately, preserving the pre-retry behaviour.
     */
    fun onError(itemKey: String?): Verdict {
        if (itemKey != null && itemKey != retriedItemKey) {
            retriedItemKey = itemKey
            return Verdict.RetrySameItem
        }
        consecutiveErrors += 1
        return if (consecutiveErrors >= threshold) Verdict.Halt(consecutiveErrors) else Verdict.Recover
    }

    /** A track actually started playing — backend is alive; reset the skip count. */
    fun onPlaybackStarted() {
        consecutiveErrors = 0
    }

    /** User did something deliberate (next/prev/seek/play) — rearm everything. */
    fun onUserTransport() {
        consecutiveErrors = 0
        retriedItemKey = null
    }
}
