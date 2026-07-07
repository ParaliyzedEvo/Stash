package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.download.lossless.AudioFormat
import com.stash.data.download.lossless.LosslessQualityTier
import com.stash.data.download.lossless.SourceResult
import com.stash.data.download.lossless.qbdlx.QbdlxQobuzSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class QbdlxStreamResolverTest {

    private val source: QbdlxQobuzSource = mockk(relaxed = true)
    private val policy: StreamQualityPolicy = mockk {
        coEvery { streamingTier() } returns LosslessQualityTier.MAX
    }

    private fun stubTrack(): TrackEntity = TrackEntity(
        id = 7L,
        title = "Some Song",
        artist = "Some Artist",
        album = "Some Album",
        durationMs = 210_000L,
        isrc = "USRC17607839",
    )

    private fun stubSourceResult(downloadUrl: String): SourceResult = SourceResult(
        sourceId = QbdlxQobuzSource.SOURCE_ID,
        downloadUrl = downloadUrl,
        downloadHeaders = emptyMap(),
        format = AudioFormat(codec = "flac", bitrateKbps = 0),
        confidence = 0.95f,
        sourceTrackId = "1234",
        coverArtUrl = null,
    )

    @Test
    fun resolve_returnsNull_whenNotEnabledForStreaming() = runTest {
        coEvery { source.isEnabledForStreaming() } returns false
        val resolver = QbdlxStreamResolver(source, policy)

        val result = resolver.resolve(stubTrack())

        assertThat(result).isNull()
        coVerify(exactly = 0) { source.resolveImmediate(any(), any()) } // skipped before the network
    }

    @Test
    fun resolve_delegatesAndWrapsStreamUrl_withQbdlxOrigin() = runTest {
        coEvery { source.isEnabledForStreaming() } returns true
        coEvery { source.resolveImmediate(any(), any()) } returns stubSourceResult(
            downloadUrl = "https://streaming-qobuz-std.akamaized.net/file?uid=1&etsp=1778893323&hmac=abc",
        )
        val resolver = QbdlxStreamResolver(source, policy)

        val result = resolver.resolve(stubTrack())

        assertThat(result).isNotNull()
        assertThat(result!!.origin).isEqualTo("qbdlx")
        assertThat(result.expiresAtMs).isEqualTo(1_778_893_323_000L)
        assertThat(result.codec).isEqualTo("flac")
    }

    @Test
    fun resolve_returnsNull_whenNoEtsp() = runTest {
        coEvery { source.isEnabledForStreaming() } returns true
        coEvery { source.resolveImmediate(any(), any()) } returns stubSourceResult(
            downloadUrl = "https://cdn/x.flac?uid=1&hmac=abc", // no etsp → un-cacheable
        )
        val resolver = QbdlxStreamResolver(source, policy)

        val result = resolver.resolve(stubTrack())

        assertThat(result).isNull()
    }

    @Test
    fun `passes the policy tier code to resolveImmediate`() = runTest {
        coEvery { policy.streamingTier() } returns LosslessQualityTier.CD
        coEvery { source.isEnabledForStreaming() } returns true
        coEvery { source.resolveImmediate(any(), any()) } returns stubSourceResult(
            downloadUrl = "https://cdn/x.flac?etsp=9999999999",
        )
        val resolver = QbdlxStreamResolver(source, policy)

        resolver.resolve(stubTrack())

        coVerify { source.resolveImmediate(any(), LosslessQualityTier.CD.qobuzCode) }
    }
}
