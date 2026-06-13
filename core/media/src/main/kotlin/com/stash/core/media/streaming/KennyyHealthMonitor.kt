package com.stash.core.media.streaming

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sliding-window health monitor for the Kennyy proxy. The
 * SquidCookieAutoRefresher consults [isHealthy] to decide whether
 * to keep the Squid cookie warm in the background.
 *
 * "Failure" means a proxy-level distress signal (timeout, 5xx, 4xx).
 * "No match" — track legitimately absent from Qobuz catalog — does NOT
 * count as a failure since it's a per-track miss, not a proxy outage.
 *
 * State is transient (process-lifetime). After a restart we start
 * healthy and let the next ~5 resolves re-establish reality.
 *
 * **Recovery.** Once [isHealthy] flips to `false`, all streaming-tap
 * calls skip kennyy (via [KennyyStreamResolver]) — which means no
 * successes can ever be recorded, creating a permanent dead-lock. To
 * break it, [isHealthy] checks whether [RECOVERY_MS] has elapsed since
 * the last recorded failure. If so, the window is cleared and the
 * monitor resets to healthy, allowing the next resolve to probe kennyy
 * and either confirm recovery or re-trip the breaker.
 */
@Singleton
class KennyyHealthMonitor @Inject constructor() {

    private enum class Outcome { Success, Failure }

    private val window = ArrayDeque<Outcome>(WINDOW_SIZE)
    private val _isHealthy = MutableStateFlow(true)

    /** Epoch millis of the most recent [Outcome.Failure] recording. */
    @Volatile
    private var lastFailureMs: Long = 0L

    /**
     * `true` when kennyy is considered operational. Reads also apply the
     * time-based recovery: if the monitor is unhealthy but [RECOVERY_MS]
     * have passed since the last failure, the window resets automatically
     * and the monitor goes back to healthy.
     */
    val isHealthy: StateFlow<Boolean> = _isHealthy.asStateFlow()

    /**
     * Call BEFORE reading [isHealthy] in a hot path that skips kennyy
     * when unhealthy. Applies the time-based recovery if applicable.
     */
    @Synchronized
    fun checkRecovery() {
        if (!_isHealthy.value && lastFailureMs > 0L) {
            val elapsed = System.currentTimeMillis() - lastFailureMs
            if (elapsed >= RECOVERY_MS) {
                window.clear()
                _isHealthy.value = true
            }
        }
    }

    @Synchronized
    fun recordSuccess() = record(Outcome.Success)

    @Synchronized
    fun recordFailure() {
        lastFailureMs = System.currentTimeMillis()
        record(Outcome.Failure)
    }

    /** No match for the track in Qobuz catalog. Not a proxy distress signal. */
    fun recordNoMatch() { /* intentionally no-op */ }

    private fun record(outcome: Outcome) {
        window.addLast(outcome)
        if (window.size > WINDOW_SIZE) window.removeFirst()
        val failures = window.count { it == Outcome.Failure }
        _isHealthy.value = failures < FAIL_THRESHOLD
    }

    private companion object {
        const val WINDOW_SIZE = 5
        const val FAIL_THRESHOLD = 3
        /**
         * Cooldown after which an unhealthy monitor resets. 30 seconds is
         * short enough that transient outages recover quickly, long enough
         * that a sustained 502 flood doesn't hammer the proxy every 30s
         * with 5 wasted round-trips before re-tripping.
         */
        const val RECOVERY_MS = 30_000L
    }
}
