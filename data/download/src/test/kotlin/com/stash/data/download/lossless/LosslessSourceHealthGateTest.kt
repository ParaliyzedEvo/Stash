package com.stash.data.download.lossless

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LosslessSourceHealthGateTest {

    private var now = 1_000_000L
    private fun gate() = LosslessSourceHealthGate(nowMs = { now })

    @Test
    fun `fresh source is not degraded`() {
        assertThat(gate().isDegraded("kennyy_qobuz")).isFalse()
    }

    @Test
    fun `recordDegraded marks source degraded within cooldown`() {
        val g = gate()
        g.recordDegraded("kennyy_qobuz")
        assertThat(g.isDegraded("kennyy_qobuz")).isTrue()
    }

    @Test
    fun `source recovers after cooldown lapses`() {
        val g = gate()
        g.recordDegraded("kennyy_qobuz")
        now += LosslessSourceHealthGate.COOLDOWN_MS + 1
        assertThat(g.isDegraded("kennyy_qobuz")).isFalse()
    }

    @Test
    fun `still degraded at the instant before cooldown ends`() {
        val g = gate()
        g.recordDegraded("kennyy_qobuz")
        now += LosslessSourceHealthGate.COOLDOWN_MS - 1
        assertThat(g.isDegraded("kennyy_qobuz")).isTrue()
    }

    @Test
    fun `degrading one source does NOT gate another (independence)`() {
        val g = gate()
        g.recordDegraded("kennyy_qobuz")
        assertThat(g.isDegraded("kennyy_qobuz")).isTrue()
        assertThat(g.isDegraded("squid_qobuz")).isFalse()
    }

    @Test
    fun `re-degrading extends the cooldown from the new instant`() {
        val g = gate()
        g.recordDegraded("kennyy_qobuz")
        now += LosslessSourceHealthGate.COOLDOWN_MS - 10
        g.recordDegraded("kennyy_qobuz") // re-degraded — window restarts
        now += 20 // past the ORIGINAL window, before the new one
        assertThat(g.isDegraded("kennyy_qobuz")).isTrue()
    }
}
