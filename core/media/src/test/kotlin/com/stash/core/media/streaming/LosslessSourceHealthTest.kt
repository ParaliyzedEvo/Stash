package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Pins [LosslessSourceHealth] — the signal behind the Home "No lossless right
 * now" banner.
 *
 * The banner exists because the shipped qbdlx token pool died at scale
 * (community reports of "FLAC down for two days") while every lossless source
 * a user could own sat unconnected behind a Settings chevron. "Down" here must mean
 * a PATTERN — many consecutive resolves where qbdlx produced nothing —
 * not a single miss, because one miss is routinely just a track qbdlx's
 * catalog doesn't carry.
 */
class LosslessSourceHealthTest {

    private val health = LosslessSourceHealth()

    @Test
    fun `starts healthy`() = runTest {
        assertThat(health.qbdlxLooksDown.first()).isFalse()
    }

    @Test
    fun `a few misses are just catalog gaps, not an outage`() = runTest {
        repeat(LosslessSourceHealth.QBDLX_DOWN_THRESHOLD - 1) { health.recordQbdlxMiss() }

        assertThat(health.qbdlxLooksDown.first()).isFalse()
    }

    @Test
    fun `threshold consecutive misses reads as down`() = runTest {
        repeat(LosslessSourceHealth.QBDLX_DOWN_THRESHOLD) { health.recordQbdlxMiss() }

        assertThat(health.qbdlxLooksDown.first()).isTrue()
    }

    @Test
    fun `a single serve resets the streak`() = runTest {
        repeat(LosslessSourceHealth.QBDLX_DOWN_THRESHOLD) { health.recordQbdlxMiss() }
        health.recordQbdlxServed()

        assertThat(health.qbdlxLooksDown.first()).isFalse()

        // And the streak restarts from zero, not from the old count.
        health.recordQbdlxMiss()
        assertThat(health.qbdlxLooksDown.first()).isFalse()
    }

    @Test
    fun `stays down while misses continue past the threshold`() = runTest {
        repeat(LosslessSourceHealth.QBDLX_DOWN_THRESHOLD * 3) { health.recordQbdlxMiss() }

        assertThat(health.qbdlxLooksDown.first()).isTrue()
    }
}
