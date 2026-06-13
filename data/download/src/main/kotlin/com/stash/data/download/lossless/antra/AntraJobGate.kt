package com.stash.data.download.lossless.antra

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes antra job lifecycles to one at a time.
 *
 * antra enforces a single concurrent job server-side — a second
 * `POST /api/jobs` issued while one is still running returns HTTP 429.
 * Both the download path ([AntraSource]) and the stream path
 * (`AntraStreamResolver`) run job lifecycles, and the download pipeline
 * fires up to 8 workers in parallel. Without a gate those workers collide
 * on antra's single slot, get 429'd, and trip the rate limiter's circuit
 * breaker — bricking antra for 30 minutes after the first track.
 *
 * One shared `@Singleton` [Mutex] makes every antra job wait for the
 * previous one to finish, so jobs run cleanly one-after-another. The gate
 * is held only across the *job* portion (create → poll to terminal); the
 * subsequent `/download` byte fetch creates no new job and runs outside it.
 */
@Singleton
class AntraJobGate @Inject constructor() {
    private val mutex = Mutex()

    /** Runs [block] with exclusive access to antra's single job slot. */
    suspend fun <T> withJob(block: suspend () -> T): T = mutex.withLock { block() }
}
