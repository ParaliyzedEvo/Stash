package com.stash.data.download.lossless

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * ⚠️ Same build-config trap as `StreamSourceRegistryTest` in :core:media —
 * [LosslessSourceRegistry.resolve] drops `arcod` unless
 * `BuildConfig.ARCOD_CONFIGURED`, fed from local.properties. A maintainer box
 * has the key; CI does not. So any assertion that arcod IS used passes locally
 * and fails in CI; guard it with the flag.
 *
 * qbdlx is no longer build-gated; it self-gates on LosslessAvailability, so the
 * qbdlx expectations below are unconditional.
 *
 * Assertions that a source is NOT used are safe unguarded — an unconfigured
 * build filters it out, which satisfies them for a different reason. Force-X
 * toggles deliberately bypass the filter (see the registry), so those tests are
 * safe too.
 *
 * Tests that need a source guaranteed to be consulted (neither parked nor
 * build-gated) use a synthetic id (`"lucida"`) — the registry only special-cases
 * the known parked/gated ids, so an unrecognised id always survives the filter.
 */
class LosslessSourceRegistryTest {

    private val prefs: LosslessSourcePreferences = mockk()
    private val healthGate: LosslessSourceHealthGate = mockk()
    private val streamingPreference: com.stash.core.data.prefs.StreamingPreference =
        mockk {
            // Normal use: all force-only toggles off so the full chain is consulted.
            coEvery { isForceQbdlxOnly() } returns false
            coEvery { isForceArcodOnly() } returns false
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
        val lucidaResult = flacResult("lucida")
        val qbdlx = fakeSource("qbdlx_qobuz", flacResult("qbdlx_qobuz"))
        val lucida = fakeSource("lucida", lucidaResult)
        coEvery { healthGate.isDegraded("qbdlx_qobuz") } returns true
        coEvery { healthGate.isDegraded("lucida") } returns false

        // priorityOrder empty → registration order; ensure qbdlx is tried first.
        val registry = registry(linkedSetOf(qbdlx, lucida))
        val result = registry.resolve(query)

        assertThat(result).isEqualTo(lucidaResult)
        coVerify(exactly = 0) { qbdlx.resolve(any()) } // skipped before resolving
        coVerify(exactly = 1) { lucida.resolve(any()) }
    }

    @Test
    fun `qbdlx is tried ahead of lower-priority sources in the normal chain`() = runTest {
        coEvery { prefs.priorityOrderNow() } returns
            LosslessSourcePreferences.DEFAULT_PRIORITY
        coEvery { prefs.minQualityNow() } returns LosslessSourcePreferences.MinQuality.ANY
        coEvery { healthGate.isDegraded(any()) } returns false

        val qbdlxResult = flacResult("qbdlx_qobuz")
        // Both would match; qbdlx must win because it's ranked first (fast,
        // no proxy) — arcod (ranked last) is never consulted.
        val qbdlx = fakeSource("qbdlx_qobuz", qbdlxResult)
        val arcod = fakeSource("arcod", flacResult("arcod"))

        val registry = registry(linkedSetOf(arcod, qbdlx)) // scrambled on purpose
        val result = registry.resolve(query)

        assertThat(result).isEqualTo(qbdlxResult)
        coVerify(exactly = 1) { qbdlx.resolve(any()) }
        coVerify(exactly = 0) { arcod.resolve(any()) }
    }

    @Test
    fun `parked qobuz proxies are skipped and an active source serves the normal chain`() = runTest {
        coEvery { prefs.priorityOrderNow() } returns
            LosslessSourcePreferences.DEFAULT_PRIORITY
        coEvery { prefs.minQualityNow() } returns LosslessSourcePreferences.MinQuality.ANY
        coEvery { healthGate.isDegraded(any()) } returns false

        val lucidaResult = flacResult("lucida")
        // squid + kennyy are parked (PARKED_SOURCE_IDS): the normal resolve
        // chain must skip them entirely, never even calling resolve().
        val squid = fakeSource("squid_qobuz", flacResult("squid_qobuz"))
        val kennyy = fakeSource("kennyy_qobuz", flacResult("kennyy_qobuz"))
        val lucida = fakeSource("lucida", lucidaResult)

        val registry = registry(linkedSetOf(squid, kennyy, lucida))
        val result = registry.resolve(query)

        assertThat(result).isEqualTo(lucidaResult)
        coVerify(exactly = 0) { squid.resolve(any()) } // parked
        coVerify(exactly = 0) { kennyy.resolve(any()) } // parked
        coVerify(exactly = 1) { lucida.resolve(any()) }
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
    fun `arcod is ordered last after the qobuz proxies under default priority`() = runTest {
        coEvery { prefs.priorityOrderNow() } returns LosslessSourcePreferences.DEFAULT_PRIORITY

        // Register sources out of order to prove the priority list (not the
        // Set's iteration order) drives ranking.
        val arcod = fakeSource("arcod", flacResult("arcod"))
        val squid = fakeSource("squid_qobuz", flacResult("squid_qobuz"))
        val kennyy = fakeSource("kennyy_qobuz", flacResult("kennyy_qobuz"))

        val registry = registry(linkedSetOf(arcod, kennyy, squid))

        val orderedIds = registry.orderedSources().map { it.id }
        assertThat(orderedIds)
            .containsExactly("squid_qobuz", "kennyy_qobuz", "arcod").inOrder()
        // arcod is the final lossless source the chain tries before YouTube.
        assertThat(orderedIds.last()).isEqualTo("arcod")
    }

    @Test
    fun `force-arcod-only resolves via arcod and never consults the qobuz proxies`() = runTest {
        coEvery { streamingPreference.isForceArcodOnly() } returns true
        coEvery { prefs.priorityOrderNow() } returns
            LosslessSourcePreferences.DEFAULT_PRIORITY
        coEvery { prefs.minQualityNow() } returns LosslessSourcePreferences.MinQuality.ANY
        coEvery { healthGate.isDegraded(any()) } returns false

        val arcodResult = flacResult("arcod")
        val squid = fakeSource("squid_qobuz", flacResult("squid_qobuz"))
        val kennyy = fakeSource("kennyy_qobuz", flacResult("kennyy_qobuz"))
        val arcod = fakeSource("arcod", arcodResult)

        val registry = registry(linkedSetOf(squid, kennyy, arcod))
        val result = registry.resolve(query)

        assertThat(result).isEqualTo(arcodResult)
        coVerify(exactly = 1) { arcod.resolve(any()) }
        coVerify(exactly = 0) { squid.resolve(any()) }
        coVerify(exactly = 0) { kennyy.resolve(any()) }
    }
}
