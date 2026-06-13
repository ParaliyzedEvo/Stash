package com.stash.core.media.streaming

import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.qobuz.QobuzSource
import com.stash.data.download.lossless.squid.NativeSquidCaptchaSolver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SquidCookieAutoRefresherTest {

    @Test
    fun doesNotRefresh_whenKennyyHealthy() = runTest {
        val solver: NativeSquidCaptchaSolver = mockk(relaxed = true)
        val prefs: LosslessSourcePreferences = mockk(relaxed = true) {
            coEvery { captchaCookieSetAtMs } returns flowOf(System.currentTimeMillis() - 30 * 60_000L)
        }
        val monitor = KennyyHealthMonitor()  // starts healthy
        val qobuzSource: QobuzSource = mockk(relaxed = true)

        val refresher = SquidCookieAutoRefresher(solver, monitor, prefs, qobuzSource, this)
        refresher.start()
        advanceTimeBy(60_000L)
        runCurrent()

        coVerify(exactly = 0) { solver.solve() }
        refresher.stop()
    }

    @Test
    fun refreshesImmediately_whenKennyyUnhealthyAndCookieStale() = runTest {
        val solver: NativeSquidCaptchaSolver = mockk(relaxed = true) {
            coEvery { solve() } returns "new-cookie-value"
        }
        val prefs: LosslessSourcePreferences = mockk(relaxed = true) {
            coEvery { captchaCookieSetAtMs } returns flowOf(0L)  // never set
        }
        val monitor = KennyyHealthMonitor()
        repeat(3) { monitor.recordFailure() }  // mark unhealthy
        val qobuzSource: QobuzSource = mockk(relaxed = true)

        val refresher = SquidCookieAutoRefresher(solver, monitor, prefs, qobuzSource, this)
        refresher.start()
        runCurrent()

        coVerify(exactly = 1) { solver.solve() }
        coVerify(exactly = 1) { prefs.setCaptchaCookieValue("new-cookie-value") }
        coVerify(exactly = 1) { qobuzSource.clearLastKnownBad() }
        refresher.stop()
    }

    @Test
    fun retriesWithBackoff_doesNotPermanentlyHalt_onRepeatedSolveFailure() = runTest {
        val solver: NativeSquidCaptchaSolver = mockk {
            coEvery { solve() } returnsMany listOf(null, null, "good-cookie")
        }
        val prefs: LosslessSourcePreferences = mockk(relaxed = true) {
            coEvery { captchaCookieSetAtMs } returns flowOf(0L)
        }
        val monitor = KennyyHealthMonitor()
        repeat(3) { monitor.recordFailure() } // unhealthy
        val qobuzSource: QobuzSource = mockk(relaxed = true)

        val refresher = SquidCookieAutoRefresher(solver, monitor, prefs, qobuzSource, this)
        refresher.start()
        runCurrent()                          // attempt 1 -> null
        advanceTimeBy(60_001L); runCurrent()  // backoff 1 elapses -> attempt 2 -> null
        advanceTimeBy(120_001L); runCurrent() // backoff 2 elapses -> attempt 3 -> success

        coVerify(exactly = 3) { solver.solve() }
        coVerify(exactly = 1) { prefs.setCaptchaCookieValue("good-cookie") }
        refresher.stop()
    }

    @Test
    fun backoffCapsAtMax_andNeverBusySpins_onLongOutage() = runTest {
        val solver: NativeSquidCaptchaSolver = mockk {
            // 5 failures then success - exercises rungs 60s,120s,240s,300s(cap),300s(cap)
            coEvery { solve() } returnsMany listOf(null, null, null, null, null, "ok")
        }
        val prefs: LosslessSourcePreferences = mockk(relaxed = true) {
            coEvery { captchaCookieSetAtMs } returns flowOf(0L)
        }
        val monitor = KennyyHealthMonitor()
        repeat(3) { monitor.recordFailure() } // unhealthy
        val qobuzSource: QobuzSource = mockk(relaxed = true)

        val refresher = SquidCookieAutoRefresher(solver, monitor, prefs, qobuzSource, this)
        refresher.start()
        runCurrent()                            // attempt 1 (null) -> wait 60s
        advanceTimeBy(60_001L); runCurrent()    // attempt 2 (null) -> wait 120s
        advanceTimeBy(120_001L); runCurrent()   // attempt 3 (null) -> wait 240s
        advanceTimeBy(240_001L); runCurrent()   // attempt 4 (null) -> wait 300s (cap)
        advanceTimeBy(300_001L); runCurrent()   // attempt 5 (null) -> wait 300s (cap)
        advanceTimeBy(300_001L); runCurrent()   // attempt 6 (success)

        coVerify(exactly = 6) { solver.solve() }
        coVerify(exactly = 1) { prefs.setCaptchaCookieValue("ok") }
        refresher.stop()
    }
}
