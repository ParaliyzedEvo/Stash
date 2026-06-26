package com.stash.data.download.lossless.arcod

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes ARCOD job lifecycles to ONE at a time, across BOTH the download
 * source ([ArcodSource]) and the stream resolver
 * ([com.stash.core.media.streaming.ArcodStreamResolver]).
 *
 * ARCOD runs a single operator-paid Qobuz account with a ~50 download/hour cap,
 * and each "download" is a server-side render job (create → poll → url). Without
 * this gate, a library sync (hundreds of queued tracks, 8 parallel download
 * workers) or a playlist tap (queue-wide stream background-fill) fires dozens of
 * render jobs at once — instantly blowing the hourly cap, hammering the
 * operator, and (pre-fix) tripping our circuit breaker so every track fell
 * through to yt-dlp (verified on-device 2026-06-16).
 *
 * A single shared [Mutex] means at most one ARCOD render job is in flight at any
 * moment app-wide; everything else waits its turn. Combined with the
 * [AggregatorRateLimiter] `"arcod"` token bucket (request rate) this keeps Stash
 * a polite single-stream client against the proxy.
 *
 * Hold the lock across the whole create→poll lifecycle (not individual HTTP
 * calls) so a second track can't start its job until the first finishes.
 */
@Singleton
class ArcodJobGate @Inject constructor() {
    private val mutex = Mutex()

    /** Run [block] as the sole in-flight ARCOD job; others wait. */
    suspend fun <T> withJob(block: suspend () -> T): T = mutex.withLock { block() }
}
