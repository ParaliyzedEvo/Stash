package com.stash.core.media.streaming

import com.stash.data.download.lossless.kennyy.KennyySource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KennyyHealthProbeTest {

    @Test
    fun probesImmediatelyOnStart_andRecordsFailure() = runTest {
        val source: KennyySource = mockk { coEvery { resolveImmediate(any()) } returns null }
        val monitor: KennyyHealthMonitor = mockk(relaxed = true) {
            every { isHealthy } returns MutableStateFlow(false)
        }
        val probe = KennyyHealthProbe(source, monitor, this)
        probe.start()
        runCurrent()
        coVerify(atLeast = 1) { source.resolveImmediate(any()) }
        verify(atLeast = 1) { monitor.recordFailure() }
        probe.stop()
    }

    @Test
    fun pollsOnInterval_whileUnhealthy() = runTest {
        val source: KennyySource = mockk { coEvery { resolveImmediate(any()) } returns null }
        val monitor: KennyyHealthMonitor = mockk(relaxed = true) {
            every { isHealthy } returns MutableStateFlow(false)
        }
        val probe = KennyyHealthProbe(source, monitor, this)
        probe.start(); runCurrent()            // probe #1, then delay(45s)
        coVerify(exactly = 1) { source.resolveImmediate(any()) }
        advanceTimeBy(45_001L); runCurrent()   // probe #2
        coVerify(exactly = 2) { source.resolveImmediate(any()) }
        advanceTimeBy(45_001L); runCurrent()   // probe #3
        coVerify(exactly = 3) { source.resolveImmediate(any()) }
        probe.stop()
    }

    @Test
    fun idlesAfterSuccess_whileHealthy() = runTest {
        val source: KennyySource = mockk { coEvery { resolveImmediate(any()) } returns mockk(relaxed = true) }
        val monitor: KennyyHealthMonitor = mockk(relaxed = true) {
            every { isHealthy } returns MutableStateFlow(true)
        }
        val probe = KennyyHealthProbe(source, monitor, this)
        probe.start(); runCurrent()            // probe #1 succeeds + healthy -> idle
        advanceTimeBy(200_000L); runCurrent()  // still idling: no further probes
        coVerify(exactly = 1) { source.resolveImmediate(any()) }
        probe.stop()
    }

    @Test
    fun loopSurvivesAThrow_andKeepsProbing() = runTest {
        val source: KennyySource = mockk {
            coEvery { resolveImmediate(any()) } throws RuntimeException("boom") andThen null
        }
        val monitor: KennyyHealthMonitor = mockk(relaxed = true) {
            every { isHealthy } returns MutableStateFlow(false)
        }
        val probe = KennyyHealthProbe(source, monitor, this)
        probe.start(); runCurrent()           // iteration 1 throws -> caught as failure, loop lives
        advanceTimeBy(45_001L); runCurrent()  // iteration 2 runs (proves loop survived)
        coVerify(atLeast = 2) { source.resolveImmediate(any()) }
        verify(atLeast = 1) { monitor.recordFailure() }
        probe.stop()
    }

    @Test
    fun coldStartWithDeadKennyy_convergesToUnhealthyQuickly() = runTest {
        val source: KennyySource = mockk { coEvery { resolveImmediate(any()) } returns null }
        val monitor = KennyyHealthMonitor() // real, starts healthy
        val probe = KennyyHealthProbe(source, monitor, this)
        probe.start(); runCurrent()          // probe #1 -> [F], still healthy, delay 2s
        assertTrue(monitor.isHealthy.value)  // not flipped yet after 1 failure
        advanceTimeBy(2_001L); runCurrent()  // probe #2 -> [F,F], still healthy, delay 2s
        advanceTimeBy(2_001L); runCurrent()  // probe #3 -> [F,F,F] -> UNHEALTHY
        assertFalse(monitor.isHealthy.value) // converged within ~4s (not 90s)
        probe.stop()
    }

    @Test
    fun wakesFromIdle_whenHealthDropsAgain() = runTest {
        val health = MutableStateFlow(true)
        val source: KennyySource = mockk { coEvery { resolveImmediate(any()) } returns mockk(relaxed = true) }
        val monitor: KennyyHealthMonitor = mockk(relaxed = true) { every { isHealthy } returns health }
        val probe = KennyyHealthProbe(source, monitor, this)
        probe.start(); runCurrent()           // probe #1 succeeds + healthy -> idle on first { !it }
        coVerify(exactly = 1) { source.resolveImmediate(any()) }
        health.value = false                  // Kennyy dies mid-session -> idle must wake
        runCurrent()
        coVerify(atLeast = 2) { source.resolveImmediate(any()) } // probed again after waking
        probe.stop()
    }

    @Test
    fun stop_cancelsTheLoop() = runTest {
        val source: KennyySource = mockk { coEvery { resolveImmediate(any()) } returns null }
        val monitor: KennyyHealthMonitor = mockk(relaxed = true) {
            every { isHealthy } returns MutableStateFlow(false)
        }
        val probe = KennyyHealthProbe(source, monitor, this)
        probe.start(); runCurrent()            // probe #1, then suspended in delay(45s)
        probe.stop()                           // cancels the loop job mid-delay
        advanceTimeBy(200_000L); runCurrent()  // no probe fires after cancellation
        coVerify(exactly = 1) { source.resolveImmediate(any()) }
    }
}
