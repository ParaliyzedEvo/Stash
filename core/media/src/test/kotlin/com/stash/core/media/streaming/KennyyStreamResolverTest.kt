package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.download.lossless.AudioFormat
import com.stash.data.download.lossless.LosslessQualityTier
import com.stash.data.download.lossless.LosslessSourceHealthGate
import com.stash.data.download.lossless.SourceResult
import com.stash.data.download.lossless.kennyy.KennyySource
import com.stash.data.download.lossless.qobuz.QobuzQuality
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KennyyStreamResolverTest {

    private val fakeKennyy: KennyySource = mockk(relaxed = true)
    private val monitor: KennyyHealthMonitor = mockk(relaxed = true) {
        every { isHealthy } returns MutableStateFlow(true) // healthy -> gate lets resolve through
    }
    // Content-health gate: default not-degraded so existing tests exercise
    // the resolve path; the degraded case is covered explicitly below.
    private fun gate(degraded: Boolean = false): LosslessSourceHealthGate =
        mockk { every { isDegraded(any()) } returns degraded }
    private val policy: StreamQualityPolicy = mockk {
        coEvery { streamingTier() } returns LosslessQualityTier.MAX
    }
    private val resolver = KennyyStreamResolver(fakeKennyy, monitor, gate(), policy)

    @Test
    fun resolve_returnsNullWhenKennyyHasNoMatch() = runTest {
        coEvery { fakeKennyy.resolveImmediate(any(), any()) } returns null

        val result = resolver.resolve(stubTrack())

        assertThat(result).isNull()
    }

    @Test
    fun resolve_parsesEtspIntoExpiresAtMs() = runTest {
        coEvery { fakeKennyy.resolveImmediate(any(), any()) } returns stubSourceResult(
            downloadUrl = "https://streaming-qobuz-std.akamaized.net/file?uid=1&etsp=1778893323&hmac=abc",
        )

        val result = resolver.resolve(stubTrack())

        assertThat(result).isNotNull()
        assertThat(result!!.url).contains("streaming-qobuz-std")
        assertThat(result.expiresAtMs).isEqualTo(1_778_893_323_000L)
    }

    @Test
    fun resolve_returnsNullWhenEtspMissing() = runTest {
        // Defensive: a URL with no etsp can't be safely refreshed; treat as null.
        coEvery { fakeKennyy.resolveImmediate(any(), any()) } returns stubSourceResult(
            downloadUrl = "https://streaming-qobuz-std.akamaized.net/file?uid=1&hmac=abc",
        )

        val result = resolver.resolve(stubTrack())

        assertThat(result).isNull()
    }

    @Test
    fun resolve_returnsNullWhenEtspNotInteger() = runTest {
        coEvery { fakeKennyy.resolveImmediate(any(), any()) } returns stubSourceResult(
            downloadUrl = "https://streaming-qobuz-std.akamaized.net/file?uid=1&etsp=garbage&hmac=abc",
        )

        val result = resolver.resolve(stubTrack())

        assertThat(result).isNull()
    }

    @Test
    fun resolve_recordsSuccess_whenUrlValid() = runTest {
        coEvery { fakeKennyy.resolveImmediate(any(), any()) } returns stubSourceResult(
            downloadUrl = "https://streaming-qobuz-std.akamaized.net/file?uid=1&etsp=1778893323&hmac=abc",
        )
        resolver.resolve(stubTrack())
        verify { monitor.recordSuccess() }
        verify(exactly = 0) { monitor.recordFailure() }
        verify(exactly = 0) { monitor.recordNoMatch() }
    }

    @Test
    fun resolve_recordsFailure_whenSourceFlagsNetworkFailure() = runTest {
        coEvery { fakeKennyy.resolveImmediate(any(), any()) } returns null
        every { fakeKennyy.lastResolveFailedNetwork } returns true
        resolver.resolve(stubTrack())
        verify { monitor.recordFailure() }
        verify(exactly = 0) { monitor.recordSuccess() }
        verify(exactly = 0) { monitor.recordNoMatch() }
    }

    @Test
    fun resolve_recordsNoMatch_whenSourceReturnsNullWithoutNetworkFailure() = runTest {
        coEvery { fakeKennyy.resolveImmediate(any(), any()) } returns null
        every { fakeKennyy.lastResolveFailedNetwork } returns false
        resolver.resolve(stubTrack())
        verify { monitor.recordNoMatch() }
        verify(exactly = 0) { monitor.recordSuccess() }
        verify(exactly = 0) { monitor.recordFailure() }
    }

    @Test
    fun resolve_returnsNullWithoutNetwork_whenKennyyUnhealthy() = runTest {
        val source: KennyySource = mockk(relaxed = true)
        val monitor = KennyyHealthMonitor()
        repeat(3) { monitor.recordFailure() } // unhealthy
        val resolver = KennyyStreamResolver(source, monitor, gate(), policy)
        val result = resolver.resolve(track(id = 1L))
        assertNull(result)
        coVerify(exactly = 0) { source.resolveImmediate(any(), any()) } // never hit the network
    }

    @Test
    fun resolve_timesOutAsFailure_whenKennyyHangs() = runTest {
        val source: KennyySource = mockk(relaxed = true) {
            coEvery { resolveImmediate(any(), any()) } coAnswers { delay(60_000); null }
        }
        val monitor: KennyyHealthMonitor = mockk(relaxed = true) {
            every { isHealthy } returns MutableStateFlow(true) // healthy -> gate lets it through
        }
        val resolver = KennyyStreamResolver(source, monitor, gate(), policy)
        val result = resolver.resolve(track(1L))
        assertNull(result)
        verify(exactly = 1) { monitor.recordFailure() } // the hang was classified as a failure
    }

    @Test
    fun resolve_returnsNullWithoutNetwork_whenContentDegraded() = runTest {
        val source: KennyySource = mockk(relaxed = true)
        val healthyMonitor: KennyyHealthMonitor = mockk(relaxed = true) {
            every { isHealthy } returns MutableStateFlow(true) // healthy -> prove the GATE blocks it
        }
        val resolver = KennyyStreamResolver(source, healthyMonitor, gate(degraded = true), policy)
        val result = resolver.resolve(track(1L))
        assertNull(result)
        coVerify(exactly = 0) { source.resolveImmediate(any(), any()) } // skipped before the network
    }

    @Test
    fun `passes the policy tier code to resolveImmediate`() = runTest {
        coEvery { policy.streamingTier() } returns LosslessQualityTier.CD
        coEvery { fakeKennyy.resolveImmediate(any(), any()) } returns stubSourceResult(
            downloadUrl = "https://cdn/x.flac?etsp=9999999999",
        )

        resolver.resolve(stubTrack())

        coVerify { fakeKennyy.resolveImmediate(any(), QobuzQuality.FLAC_CD) }
    }

    private fun track(id: Long): TrackEntity = stubTrack().copy(id = id)

    private fun stubTrack(): TrackEntity = TrackEntity(
        id = 42L,
        title = "Some Song",
        artist = "Some Artist",
        album = "Some Album",
        durationMs = 210_000L,
        isrc = "USRC17607839",
    )

    private fun stubSourceResult(downloadUrl: String): SourceResult = SourceResult(
        sourceId = KennyySource.SOURCE_ID,
        downloadUrl = downloadUrl,
        downloadHeaders = emptyMap(),
        format = AudioFormat(codec = "flac", bitrateKbps = 0),
        confidence = 0.95f,
        sourceTrackId = "1234",
        coverArtUrl = null,
    )
}
