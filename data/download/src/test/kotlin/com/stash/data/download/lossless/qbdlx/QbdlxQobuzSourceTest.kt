package com.stash.data.download.lossless.qbdlx

import com.google.common.truth.Truth.assertThat
import com.stash.data.download.lossless.AggregatorRateLimiter
import com.stash.data.download.lossless.LosslessAvailability
import com.stash.data.download.lossless.LosslessQualityTier
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.RateLimitState
import com.stash.data.download.lossless.TrackQuery
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * [QbdlxQobuzSource] after the pool: catalog search is tokenless and the file URL
 * comes from [QbdlxFileUrlRouter]. The real QobuzCandidateMatcher scores real
 * [QbdlxTrack]s so matching runs end-to-end.
 */
class QbdlxQobuzSourceTest {
    private val apiClient: QbdlxApiClient = mockk()
    private val router: QbdlxFileUrlRouter = mockk()
    private val availability: LosslessAvailability = mockk()
    private val rateLimiter: AggregatorRateLimiter = mockk(relaxUnitFun = true)
    private val prefs: LosslessSourcePreferences = mockk()
    private fun source() = QbdlxQobuzSource(apiClient, router, availability, rateLimiter, prefs)
    private val sid = QbdlxQobuzSource.SOURCE_ID
    private val notBroken = RateLimitState(2.0, 0L, isCircuitBroken = false, msUntilUnblock = 0L, recentFailures = 0)
    private val query = TrackQuery(artist = "John Frusciante", title = "Murderers", isrc = "USWB10003085", durationMs = 160_000)
    private fun candidate(id: Long = 42) = QbdlxTrack(
        id = id, title = "Murderers", isrc = "USWB10003085", duration = 160, streamable = true,
        performer = QbdlxPerformer("John Frusciante"), maximumBitDepth = 16, maximumSamplingRate = 44.1f,
        album = QbdlxAlbum(QbdlxImage(large = "https://art/large.jpg")),
    )
    private fun ok(url: String = "https://cdn/file?fmt=27") = QbdlxResolveResult.Ok(url, "flac", 24, 96_000)

    private fun enabledAndAcquired() {
        coEvery { availability.qbdlxEnabledNow() } returns true
        coEvery { availability.fileUrlAvailableNow() } returns true
        coEvery { prefs.qualityTierNow() } returns LosslessQualityTier.MAX
        coEvery { rateLimiter.stateOf(sid) } returns notBroken
        coEvery { rateLimiter.acquire(sid) } returns true
    }

    @Test fun `match yields SourceResult with the response format`() = runTest {
        enabledAndAcquired()
        coEvery { apiClient.search(any()) } returns listOf(candidate())
        coEvery { router.getFileUrl(42, 27) } returns ok()
        val r = source().resolve(query)!!
        assertThat(r.sourceId).isEqualTo("qbdlx_qobuz")
        assertThat(r.downloadUrl).isEqualTo("https://cdn/file?fmt=27")
        assertThat(r.confidence).isEqualTo(0.95f) // ISRC match → QobuzCandidateMatcher's 0.95 short-circuit
        assertThat(r.format.bitsPerSample).isEqualTo(24)
        assertThat(r.format.sampleRateHz).isEqualTo(96_000)
        assertThat(r.coverArtUrl).isEqualTo("https://art/large.jpg")
        coVerify { rateLimiter.reportSuccess(sid) }
    }

    @Test fun `no file-url path available - returns null with ZERO catalog calls`() = runTest {
        enabledAndAcquired()
        coEvery { availability.fileUrlAvailableNow() } returns false
        assertThat(source().resolve(query)).isNull()
        coVerify(exactly = 0) { apiClient.search(any()) }
        coVerify(exactly = 0) { router.getFileUrl(any(), any()) }
    }

    @Test fun `disabled when availability says no`() = runTest {
        coEvery { availability.qbdlxEnabledNow() } returns false
        coEvery { rateLimiter.stateOf(sid) } returns notBroken
        assertThat(source().isEnabled()).isFalse()
        assertThat(source().isEnabledForStreaming()).isFalse()
    }

    @Test fun `TokenDead and RegionLocked both yield null (next rung)`() = runTest {
        enabledAndAcquired()
        coEvery { apiClient.search(any()) } returns listOf(candidate())
        coEvery { router.getFileUrl(42, 27) } returns QbdlxResolveResult.TokenDead
        assertThat(source().resolve(query)).isNull()
        coEvery { router.getFileUrl(42, 27) } returns QbdlxResolveResult.RegionLocked
        assertThat(source().resolve(query)).isNull()
    }

    @Test fun `router null (all bases cooled mid-resolve) yields null without a breaker failure`() = runTest {
        enabledAndAcquired()
        coEvery { apiClient.search(any()) } returns listOf(candidate())
        coEvery { router.getFileUrl(42, 27) } returns null
        assertThat(source().resolve(query)).isNull()
        coVerify(exactly = 0) { rateLimiter.reportFailure(sid) }
    }

    @Test fun `catalog 401 after self-heal is a plain miss, not a breaker failure`() = runTest {
        enabledAndAcquired()
        coEvery { apiClient.search(any()) } throws QbdlxAuthException(401)
        assertThat(source().resolve(query)).isNull()
        coVerify(exactly = 0) { rateLimiter.reportFailure(sid) }
    }

    @Test fun `streaming tier is honoured on resolveImmediate`() = runTest {
        enabledAndAcquired()
        coEvery { apiClient.search(any()) } returns listOf(candidate())
        coEvery { router.getFileUrl(42, 6) } returns ok()
        assertThat(source().resolveImmediate(query, requestedQuality = 6)).isNotNull()
        coVerify(exactly = 0) { rateLimiter.acquire(sid) }
    }

    @Test fun `no candidate over threshold - null, search reported success`() = runTest {
        enabledAndAcquired()
        coEvery { apiClient.search(any()) } returns listOf(candidate().copy(title = "Completely Different", isrc = null))
        assertThat(source().resolve(query)).isNull()
        coVerify(exactly = 0) { router.getFileUrl(any(), any()) }
        coVerify { rateLimiter.reportSuccess(sid) }
    }

    @Test fun `429 reports rate limited not failure`() = runTest {
        enabledAndAcquired()
        coEvery { apiClient.search(any()) } throws QbdlxApiException(429, "Too Many Requests")
        assertThat(source().resolve(query)).isNull()
        coVerify { rateLimiter.reportRateLimited(sid) }
        coVerify(exactly = 0) { rateLimiter.reportFailure(sid) }
    }

    @Test fun `cancellation propagates and is not swallowed as a failure`() = runTest {
        enabledAndAcquired()
        coEvery { apiClient.search(any()) } throws CancellationException("cancelled")
        var propagated = false
        try { source().resolve(query) } catch (e: CancellationException) { propagated = true }
        assertThat(propagated).isTrue()
        coVerify(exactly = 0) { rateLimiter.reportFailure(sid) }
    }

    @Test fun `resolveImmediate succeeds even when circuit broken and bypasses acquire`() = runTest {
        enabledAndAcquired()
        coEvery { rateLimiter.stateOf(sid) } returns
            RateLimitState(0.0, 0L, isCircuitBroken = true, msUntilUnblock = 60_000L, recentFailures = 5)
        coEvery { apiClient.search(any()) } returns listOf(candidate())
        coEvery { router.getFileUrl(42, 27) } returns ok()
        assertThat(source().resolveImmediate(query)).isNotNull()
        coVerify(exactly = 0) { rateLimiter.acquire(any()) }
        coVerify { rateLimiter.reportSuccess(sid) }
    }

    @Test fun `second search term is tried when the first misses`() = runTest {
        enabledAndAcquired()
        val q = query.copy(isrc = null, artist = "John Frusciante, Josh Klinghoffer") // → two search terms
        coEvery { apiClient.search(match { it.contains(",") }) } returns emptyList()
        coEvery { apiClient.search(match { !it.contains(",") }) } returns listOf(candidate())
        coEvery { router.getFileUrl(42, 27) } returns ok()
        assertThat(source().resolve(q)).isNotNull()
        coVerify(exactly = 2) { apiClient.search(any()) }
    }

    @Test fun `isEnabled is false while the breaker is open even when configured`() = runTest {
        coEvery { availability.qbdlxEnabledNow() } returns true
        coEvery { rateLimiter.stateOf(sid) } returns notBroken.copy(isCircuitBroken = true)
        assertThat(source().isEnabled()).isFalse()
        assertThat(source().isEnabledForStreaming()).isTrue()
    }
}
