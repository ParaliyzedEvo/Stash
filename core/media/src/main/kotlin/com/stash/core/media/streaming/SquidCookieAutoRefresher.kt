package com.stash.core.media.streaming

import android.util.Log
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.qobuz.QobuzSource
import com.stash.data.download.lossless.squid.NativeSquidCaptchaSolver
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Background refresher for the squid.wtf captcha cookie. Active only
 * when Kennyy is unhealthy (so we don't burn ALTCHA solves we won't
 * use) and the app is in the ProcessLifecycle STARTED window (caller
 * controls via [start] / [stop]).
 *
 * Cadence derived from cookie age: refreshes at age+25min. The
 * 25-min window leaves a 5-min safety margin against squid.wtf's
 * 30-min sliding cookie expiry.
 *
 * Failure handling: on each solve failure the refresh loop backs off
 * exponentially (60s, 120s, 240s, ...) capped at 5min, and keeps
 * retrying for the rest of the session. It never permanently halts;
 * the loop exits only when Kennyy recovers (health flips healthy) or
 * when [stop] cancels the job.
 *
 * Placement note: lives in :core:media (not :data:download) because
 * it depends on KennyyHealthMonitor (own module) AND
 * LosslessSourcePreferences + NativeSquidCaptchaSolver (:data:download).
 * The module graph is :core:media -> :data:download (one-way), so this
 * is the only module that can see all three types.
 */
@Singleton
class SquidCookieAutoRefresher(
    private val solver: NativeSquidCaptchaSolver,
    private val healthMonitor: KennyyHealthMonitor,
    private val prefs: LosslessSourcePreferences,
    private val qobuzSource: QobuzSource,
    private val scope: CoroutineScope,
) {
    /**
     * Hilt-injectable constructor. Dagger does NOT honour Kotlin default
     * parameter values on `@Inject` constructors (it sees the parameter
     * as a required binding regardless), so the production scope is
     * constructed explicitly here. Tests use the five-arg primary
     * constructor to pass a `TestScope` directly.
     */
    @Inject
    constructor(
        solver: NativeSquidCaptchaSolver,
        healthMonitor: KennyyHealthMonitor,
        prefs: LosslessSourcePreferences,
        qobuzSource: QobuzSource,
    ) : this(
        solver = solver,
        healthMonitor = healthMonitor,
        prefs = prefs,
        qobuzSource = qobuzSource,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    )

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        Log.d(TAG, "start")
        job = scope.launch {
            combine(
                healthMonitor.isHealthy,
                prefs.captchaCookieSetAtMs,
            ) { healthy, setAtMs -> healthy to setAtMs }
                .collect { (healthy, setAtMs) ->
                    if (healthy) {
                        Log.d(TAG, "Kennyy healthy - sleeping")
                        return@collect
                    }
                    val age = System.currentTimeMillis() - setAtMs
                    if (setAtMs == 0L || age >= COOKIE_REFRESH_AGE_MS) {
                        refresh()
                    } else {
                        val waitMs = COOKIE_REFRESH_AGE_MS - age
                        Log.d(TAG, "cookie is fresh - sleeping ${waitMs}ms")
                        delay(waitMs)
                        refresh()
                    }
                }
        }
    }

    fun stop() {
        Log.d(TAG, "stop")
        job?.cancel()
        job = null
    }

    private suspend fun refresh() {
        // Keep attempting while Kennyy is unhealthy; back off on failure; never
        // permanently halt for the session. Returns on success or when Kennyy
        // recovers (health flips healthy). Cancelled cleanly by stop().
        var consecutive = 0
        while (!healthMonitor.isHealthy.value) {
            Log.d(TAG, "refresh attempt")
            val newCookie = solver.solve()
            if (newCookie != null) {
                prefs.setCaptchaCookieValue(newCookie)
                qobuzSource.clearLastKnownBad()
                Log.d(TAG, "refresh success")
                return
            }
            consecutive += 1
            // Short-circuit past the cap point so the shift can never overflow.
            // BASE_RETRY_MS doubling hits/exceeds MAX_RETRY_MS at consecutive=4
            // (480s -> capped 300s), so from consecutive>=5 use the cap directly.
            val backoff = if (consecutive >= 5) {
                MAX_RETRY_MS
            } else {
                minOf(BASE_RETRY_MS shl (consecutive - 1), MAX_RETRY_MS)
            }
            Log.w(TAG, "refresh failure ($consecutive) - retrying in ${backoff}ms")
            delay(backoff)
        }
    }

    private companion object {
        const val TAG = "SquidAutoRefresher"
        const val COOKIE_REFRESH_AGE_MS = 25 * 60_000L
        const val BASE_RETRY_MS = 60_000L   // 60s, doubles each failure
        const val MAX_RETRY_MS = 300_000L   // cap at 5min
    }
}
