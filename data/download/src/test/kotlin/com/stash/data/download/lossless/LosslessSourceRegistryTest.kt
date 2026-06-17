package com.stash.data.download.lossless

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LosslessSourceRegistryTest {

    private val prefs: LosslessSourcePreferences = mockk()
    private val healthGate: LosslessSourceHealthGate = mockk()

    private val query = TrackQuery(artist = "A", title = "B")

    private fun flacResult(srcId: String) = SourceResult(
        sourceId = srcId,
        downloadUrl = "https://cdn/$srcId.flac",
        downloadHeaders = emptyMap(),
        format = AudioFormat(codec = "flac", bitrateKbps = 0, sampleRateHz = 44_100, bitsPerSample = 16),
        confidence = 0.9f,
        sourceTrackId = "1",
        coverArtUrl = null,
    )

    private fun fakeSource(srcId: String, result: SourceResult?): LosslessSource =
        mockk {
            every { id } returns srcId
            coEvery { isEnabled() } returns true
            coEvery { resolve(any()) } returns result
            coEvery { rateLimitState() } returns RateLimitState(3.0, 0L, false, 0L, 0)
        }

    private fun acceptAnyQuality() {
        coEvery { prefs.priorityOrderNow() } returns emptyList()
        coEvery { prefs.minQualityNow() } returns LosslessSourcePreferences.MinQuality.ANY
    }

    @Test
    fun `degraded source is skipped without resolving and the next source wins`() = runTest {
        acceptAnyQuality()
        val squidResult = flacResult("squid_qobuz")
        val kennyy = fakeSource("kennyy_qobuz", flacResult("kennyy_qobuz"))
        val squid = fakeSource("squid_qobuz", squidResult)
        coEvery { healthGate.isDegraded("kennyy_qobuz") } returns true
        coEvery { healthGate.isDegraded("squid_qobuz") } returns false

        // priorityOrder empty → registration order; ensure kennyy is tried first.
        val registry = LosslessSourceRegistry(linkedSetOf(kennyy, squid), prefs, healthGate)
        val result = registry.resolve(query)

        assertThat(result).isEqualTo(squidResult)
        coVerify(exactly = 0) { kennyy.resolve(any()) } // skipped before resolving
        coVerify(exactly = 1) { squid.resolve(any()) }
    }

    @Test
    fun `no source resolves when all are degraded`() = runTest {
        acceptAnyQuality()
        val kennyy = fakeSource("kennyy_qobuz", flacResult("kennyy_qobuz"))
        val squid = fakeSource("squid_qobuz", flacResult("squid_qobuz"))
        coEvery { healthGate.isDegraded(any()) } returns true

        val registry = LosslessSourceRegistry(linkedSetOf(kennyy, squid), prefs, healthGate)

        assertThat(registry.resolve(query)).isNull()
        coVerify(exactly = 0) { kennyy.resolve(any()) }
        coVerify(exactly = 0) { squid.resolve(any()) }
    }
}
