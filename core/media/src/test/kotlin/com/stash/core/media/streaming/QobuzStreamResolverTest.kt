package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.download.lossless.AudioFormat
import com.stash.data.download.lossless.LosslessSourceHealthGate
import com.stash.data.download.lossless.SourceResult
import com.stash.data.download.lossless.qobuz.QobuzSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class QobuzStreamResolverTest {

    private val source: QobuzSource = mockk(relaxed = true)

    private fun gate(degraded: Boolean = false): LosslessSourceHealthGate =
        mockk { every { isDegraded(any()) } returns degraded }

    private fun stubTrack(): TrackEntity = TrackEntity(
        id = 7L,
        title = "Some Song",
        artist = "Some Artist",
        album = "Some Album",
        durationMs = 210_000L,
        isrc = "USRC17607839",
    )

    private fun stubSourceResult(downloadUrl: String): SourceResult = SourceResult(
        sourceId = QobuzSource.SOURCE_ID,
        downloadUrl = downloadUrl,
        downloadHeaders = emptyMap(),
        format = AudioFormat(codec = "flac", bitrateKbps = 0),
        confidence = 0.95f,
        sourceTrackId = "1234",
        coverArtUrl = null,
    )

    @Test
    fun resolve_returnsNullWithoutNetwork_whenContentDegraded() = runTest {
        coEvery { source.isEnabledForStreaming() } returns true
        val resolver = QobuzStreamResolver(source, gate(degraded = true))

        val result = resolver.resolve(stubTrack())

        assertThat(result).isNull()
        coVerify(exactly = 0) { source.resolveImmediate(any()) } // skipped before the network
    }

    @Test
    fun resolve_returnsStreamUrl_whenHealthyAndEnabled() = runTest {
        coEvery { source.isEnabledForStreaming() } returns true
        coEvery { source.resolveImmediate(any()) } returns stubSourceResult(
            downloadUrl = "https://streaming-qobuz-std.akamaized.net/file?uid=1&etsp=1778893323&hmac=abc",
        )
        val resolver = QobuzStreamResolver(source, gate(degraded = false))

        val result = resolver.resolve(stubTrack())

        assertThat(result).isNotNull()
        assertThat(result!!.expiresAtMs).isEqualTo(1_778_893_323_000L)
    }
}
