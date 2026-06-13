package com.stash.core.media.streaming

import android.util.Log
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.kennyy.KennyySource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Sole caller of Kennyy while it is unhealthy. The play path
 * ([KennyyStreamResolver]) skips Kennyy when [KennyyHealthMonitor.isHealthy]
 * is false, so this probe is the ONLY mechanism that can flip health back to
 * healthy — therefore the loop must be unbrickable (every iteration catches
 * any Throwable and records a failure; CancellationException is re-thrown so
 * [stop] still cancels cleanly).
 *
 * The first loop iteration is an immediate cold-start ground-truth probe (runs
 * even though health defaults to healthy). We idle ONLY after a probe that
 * SUCCEEDED while health is up — never on the failure ramp (which would
 * deadlock the cold-start case waiting for a flip nothing else can produce).
 */
@Singleton
class KennyyHealthProbe(
    private val source: KennyySource,
    private val healthMonitor: KennyyHealthMonitor,
    private val scope: CoroutineScope,
) {
    @Inject
    constructor(
        source: KennyySource,
        healthMonitor: KennyyHealthMonitor,
    ) : this(source, healthMonitor, CoroutineScope(SupervisorJob() + Dispatchers.Main))

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        Log.d(TAG, "start")
        job = scope.launch {
            while (true) {
                val ok = probeOnce()
                if (ok && healthMonitor.isHealthy.value) {
                    healthMonitor.isHealthy.first { !it } // idle until health drops
                } else {
                    // Ramp DOWN fast (probe failing but health still says healthy → converge to
                    // the truth before the user's first play); once unhealthy, poll for recovery
                    // on the slower cadence so we don't hammer a dead proxy.
                    delay(if (healthMonitor.isHealthy.value) RAMP_INTERVAL_MS else PROBE_INTERVAL_MS)
                }
            }
        }
    }

    fun stop() {
        Log.d(TAG, "stop")
        job?.cancel()
        job = null
    }

    /** One probe iteration. Returns true iff Kennyy responded with a match.
     *  MUST NOT let any non-cancellation Throwable escape. Catch ORDER is
     *  load-bearing (Timeout first, then Cancellation re-throw, then Throwable). */
    private suspend fun probeOnce(): Boolean {
        return try {
            val result = withTimeout(PROBE_TIMEOUT_MS) { source.resolveImmediate(PROBE_QUERY) }
            if (result != null) {
                healthMonitor.recordSuccess(); Log.d(TAG, "probe ok"); true
            } else {
                healthMonitor.recordFailure(); Log.d(TAG, "probe miss/fail"); false
            }
        } catch (e: TimeoutCancellationException) {
            healthMonitor.recordFailure(); Log.d(TAG, "probe timeout"); false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            healthMonitor.recordFailure(); Log.w(TAG, "probe threw — recorded failure", e); false
        }
    }

    private companion object {
        const val TAG = "KennyyHealthProbe"
        const val PROBE_INTERVAL_MS = 45_000L
        const val RAMP_INTERVAL_MS = 2_000L
        const val PROBE_TIMEOUT_MS = 8_000L
        // Hardcoded always-in-catalog track — any null result is a proxy anomaly.
        val PROBE_QUERY = TrackQuery(
            artist = "Daft Punk",
            title = "Get Lucky",
            album = null,
            isrc = null,
            durationMs = 0L,
        )
    }
}
