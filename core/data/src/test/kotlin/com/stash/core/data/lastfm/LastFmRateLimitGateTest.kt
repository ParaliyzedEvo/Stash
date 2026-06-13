package com.stash.core.data.lastfm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-key breaker that stops the app from hammering a Last.fm key that has
 * already returned error 29. Each key has its own cooldown so a throttled key
 * can be skipped while the rest of the pool keeps serving. Once a key's
 * cooldown elapses it reopens automatically; a success resets its backoff.
 */
class LastFmRateLimitGateTest {

    private val base = LastFmRateLimitGate.BASE_COOLDOWN_MS
    private val max = LastFmRateLimitGate.MAX_COOLDOWN_MS

    @Test
    fun `a fresh gate is closed for any key`() {
        val gate = LastFmRateLimitGate()
        assertFalse(gate.isOpen(key = "A", nowMs = 0L))
    }

    @Test
    fun `a rate-limit hit opens only that key for the base cooldown`() {
        val gate = LastFmRateLimitGate()
        gate.recordRateLimited(key = "A", nowMs = 1_000L)

        assertTrue(gate.isOpen(key = "A", nowMs = 1_000L))
        assertTrue(gate.isOpen(key = "A", nowMs = 1_000L + base - 1))
        assertFalse(gate.isOpen(key = "A", nowMs = 1_000L + base))
        // A different key in the pool is unaffected.
        assertFalse(gate.isOpen(key = "B", nowMs = 1_000L))
    }

    @Test
    fun `consecutive hits on a key back off exponentially, independently per key`() {
        val gate = LastFmRateLimitGate()
        gate.recordRateLimited(key = "A", nowMs = 0L) // A -> base
        gate.recordRateLimited(key = "A", nowMs = 0L) // A -> base*2
        assertTrue(gate.isOpen(key = "A", nowMs = base * 2 - 1))
        assertFalse(gate.isOpen(key = "A", nowMs = base * 2))

        // B's first hit is still only the base cooldown — backoff is per-key.
        gate.recordRateLimited(key = "B", nowMs = 0L)
        assertTrue(gate.isOpen(key = "B", nowMs = base - 1))
        assertFalse(gate.isOpen(key = "B", nowMs = base))
    }

    @Test
    fun `backoff is capped at the maximum cooldown`() {
        val gate = LastFmRateLimitGate()
        repeat(20) { gate.recordRateLimited(key = "A", nowMs = 0L) }
        assertTrue(gate.isOpen(key = "A", nowMs = max - 1))
        assertFalse(gate.isOpen(key = "A", nowMs = max))
    }

    @Test
    fun `a success closes that key and resets its backoff`() {
        val gate = LastFmRateLimitGate()
        gate.recordRateLimited(key = "A", nowMs = 0L)
        gate.recordRateLimited(key = "A", nowMs = 0L) // escalate to base*2

        gate.recordSuccess(key = "A")
        assertFalse(gate.isOpen(key = "A", nowMs = 0L))

        // Reset: next single hit is back to the base cooldown.
        gate.recordRateLimited(key = "A", nowMs = 0L)
        assertTrue(gate.isOpen(key = "A", nowMs = base - 1))
        assertFalse(gate.isOpen(key = "A", nowMs = base))
    }
}
