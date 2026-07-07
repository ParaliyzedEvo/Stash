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
    private val streamingPreference: com.stash.core.data.prefs.StreamingPreference =
        mockk {
            // Normal use: all force-only toggles off so the full chain is consulted.
            coEvery { isForceQbdlxOnly() } returns false
            coEvery { isForceArcodOnly() } returns false
            coEvery { isForceAmzOnly() } returns false
        }

    private val query = TrackQuery(artist = "A", title = "B")

    private fun registry(sources: Set<LosslessSource>) =
        LosslessSourceRegistry(sources, prefs, healthGate, streamingPreference)

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
        // Use active (non-parked) sources so the normal chain reaches them.
        val amzResult = flacResult("amz")
        val qbdlx = fakeSource("qbdlx_qobuz", flacResult("qbdlx_qobuz"))
        val amz = fakeSource("amz", amzResult)
        coEvery { healthGate.isDegraded("qbdlx_qobuz") } returns true
        coEvery { healthGate.isDegraded("amz") } returns false

        // priorityOrder empty → registration order; ensure qbdlx is tried first.
        val registry = registry(linkedSetOf(qbdlx, amz))
        val result = registry.resolve(query)

        assertThat(result).isEqualTo(amzResult)
        coVerify(exactly = 0) { qbdlx.resolve(any()) } // skipped before resolving
        coVerify(exactly = 1) { amz.resolve(any()) }
    }

    @Test
    fun `default priority places amz last among lossless`() = runTest {
        // DEFAULT_PRIORITY = [squid_qobuz, kennyy_qobuz, amz]; verify the
        // registry honours it and amz lands after both Qobuz proxies.
        coEvery { prefs.priorityOrderNow() } returns
            LosslessSourcePreferences.DEFAULT_PRIORITY

        val amz = fakeSource("amz", flacResult("amz"))
        val kennyy = fakeSource("kennyy_qobuz", flacResult("kennyy_qobuz"))
        val squid = fakeSource("squid_qobuz", flacResult("squid_qobuz"))

        // Pass them in a deliberately scrambled set so ordering can only
        // come from the priority list, not registration order.
        val registry = registry(linkedSetOf(amz, kennyy, squid))

        val orderedIds = registry.orderedSources().map { it.id }
        assertThat(orderedIds).containsExactly("squid_qobuz", "kennyy_qobuz", "amz").inOrder()
    }

    @Test
    fun `qbdlx is tried ahead of amz in the normal chain`() = runTest {
        coEvery { prefs.priorityOrderNow() } returns
            LosslessSourcePreferences.DEFAULT_PRIORITY
        coEvery { prefs.minQualityNow() } returns LosslessSourcePreferences.MinQuality.ANY
        coEvery { healthGate.isDegraded(any()) } returns false

        val qbdlxResult = flacResult("qbdlx_qobuz")
        // Both would match; qbdlx must win because it's ranked first (fast,
        // no decrypt) — amz is never consulted.
        val qbdlx = fakeSource("qbdlx_qobuz", qbdlxResult)
        val amz = fakeSource("amz", flacResult("amz"))

        val registry = registry(linkedSetOf(amz, qbdlx)) // scrambled on purpose
        val result = registry.resolve(query)

        assertThat(result).isEqualTo(qbdlxResult)
        coVerify(exactly = 1) { qbdlx.resolve(any()) }
        coVerify(exactly = 0) { amz.resolve(any()) }
    }

    @Test
    fun `parked qobuz proxies are skipped and amz serves the normal chain`() = runTest {
        coEvery { prefs.priorityOrderNow() } returns
            LosslessSourcePreferences.DEFAULT_PRIORITY
        coEvery { prefs.minQualityNow() } returns LosslessSourcePreferences.MinQuality.ANY
        coEvery { healthGate.isDegraded(any()) } returns false

        val amzResult = flacResult("amz")
        // squid + kennyy are parked (PARKED_SOURCE_IDS): the normal resolve
        // chain must skip them entirely, never even calling resolve().
        val squid = fakeSource("squid_qobuz", flacResult("squid_qobuz"))
        val kennyy = fakeSource("kennyy_qobuz", flacResult("kennyy_qobuz"))
        val amz = fakeSource("amz", amzResult)

        val registry = registry(linkedSetOf(squid, kennyy, amz))
        val result = registry.resolve(query)

        assertThat(result).isEqualTo(amzResult)
        coVerify(exactly = 0) { squid.resolve(any()) } // parked
        coVerify(exactly = 0) { kennyy.resolve(any()) } // parked
        coVerify(exactly = 1) { amz.resolve(any()) }
    }

    @Test
    fun `no source resolves when all are degraded`() = runTest {
        acceptAnyQuality()
        val kennyy = fakeSource("kennyy_qobuz", flacResult("kennyy_qobuz"))
        val squid = fakeSource("squid_qobuz", flacResult("squid_qobuz"))
        coEvery { healthGate.isDegraded(any()) } returns true

        val registry = registry(linkedSetOf(kennyy, squid))

        assertThat(registry.resolve(query)).isNull()
        coVerify(exactly = 0) { kennyy.resolve(any()) }
        coVerify(exactly = 0) { squid.resolve(any()) }
    }

    @Test
    fun `force-amz-only resolves via amz and never consults the qobuz proxies`() = runTest {
        coEvery { streamingPreference.isForceAmzOnly() } returns true
        coEvery { prefs.priorityOrderNow() } returns
            LosslessSourcePreferences.DEFAULT_PRIORITY
        coEvery { prefs.minQualityNow() } returns LosslessSourcePreferences.MinQuality.ANY
        coEvery { healthGate.isDegraded(any()) } returns false

        val amzResult = flacResult("amz")
        val squid = fakeSource("squid_qobuz", flacResult("squid_qobuz"))
        val kennyy = fakeSource("kennyy_qobuz", flacResult("kennyy_qobuz"))
        val amz = fakeSource("amz", amzResult)

        val registry = registry(linkedSetOf(squid, kennyy, amz))
        val result = registry.resolve(query)

        assertThat(result).isEqualTo(amzResult)
        coVerify(exactly = 1) { amz.resolve(any()) }
        coVerify(exactly = 0) { squid.resolve(any()) }
        coVerify(exactly = 0) { kennyy.resolve(any()) }
    }
}
