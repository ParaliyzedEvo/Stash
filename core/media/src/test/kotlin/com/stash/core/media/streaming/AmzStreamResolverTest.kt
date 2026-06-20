package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.download.lossless.amz.AmzApiClient
import com.stash.data.download.lossless.amz.AmzSearchItem
import com.stash.data.download.lossless.amz.AmzStreamFileProvider
import com.stash.data.download.lossless.amz.AmzTrack
import com.stash.data.download.lossless.amz.AmzTrackMeta
import io.mockk.coEvery
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AmzStreamResolverTest {

    @get:Rule val tmp = TemporaryFolder()

    private val client: AmzApiClient = mockk()
    private val fileProvider: AmzStreamFileProvider = mockk()
    private val qualityPolicy: StreamQualityPolicy = mockk {
        // Default: Wi-Fi Hi-Res tier (amz "hd") unless a test overrides.
        coEvery { streamingTier() } returns com.stash.data.download.lossless.LosslessQualityTier.HI_RES
    }

    private fun resolver() = AmzStreamResolver(client, fileProvider, qualityPolicy)

    /**
     * Writes a real (header-only) FLAC file whose STREAMINFO encodes
     * [sampleRate]/[bits] so the resolver can read back the true quality.
     */
    private fun flacFile(sampleRate: Int = 96000, bits: Int = 24, channels: Int = 2): File {
        val h = ByteArray(8 + 34)
        h[0] = 'f'.code.toByte(); h[1] = 'L'.code.toByte(); h[2] = 'a'.code.toByte(); h[3] = 'C'.code.toByte()
        h[4] = 0x00; h[5] = 0x00; h[6] = 0x00; h[7] = 0x22
        h[18] = ((sampleRate ushr 12) and 0xFF).toByte()
        h[19] = ((sampleRate ushr 4) and 0xFF).toByte()
        val ch = (channels - 1) and 0x07
        val b = (bits - 1) and 0x1F
        h[20] = (((sampleRate and 0x0F) shl 4) or (ch shl 1) or ((b ushr 4) and 0x01)).toByte()
        h[21] = ((b and 0x0F) shl 4).toByte()
        return File(tmp.root, "B00ASIN001.flac").apply { writeBytes(h) }
    }

    /** Default: provider decrypts to a real crafted FLAC for any asin. */
    private fun stubDecryptTo(file: File = flacFile()) {
        coEvery { fileProvider.resolveLocalFile(any(), any(), any()) } returns file
    }

    private fun stubTrack(): TrackEntity = TrackEntity(
        id = 7L,
        title = "Some Song",
        artist = "Some Artist",
        album = "Some Album",
        durationMs = 210_000L,
        isrc = "USRC17607839",
    )

    private fun searchItem(asin: String = "B00ASIN001"): AmzSearchItem = AmzSearchItem(
        asin = asin,
        title = "Some Song",
        primaryArtistName = "Some Artist",
    )

    private fun trackMeta(
        asin: String = "B00ASIN001",
        coverCdn: String? = "https://cdn.example/cover_cdn.jpg",
        cover: String? = "https://amazon.example/cover.jpg",
    ): AmzTrackMeta = AmzTrackMeta(
        asin = asin,
        title = "Some Song",
        artist = "Some Artist",
        album = "Some Album",
        coverCdn = coverCdn,
        cover = cover,
    )

    private fun amzTrack(
        asin: String = "B00ASIN001",
        coverCdn: String? = "https://cdn.example/cover_cdn.jpg",
        cover: String? = "https://amazon.example/cover.jpg",
        streamUrl: String? = "https://amz.squid.wtf/api/stream?asin=B00ASIN001",
    ): AmzTrack = AmzTrack(
        meta = trackMeta(asin, coverCdn, cover),
        decryptionKey = "8164fe2db5ebd498c8265b3e873462c1",
        streamUrl = streamUrl,
        codec = "flac",
        bitrateBps = 3_332_448,
        sampleRateHz = 96000,
    )

    @Test
    fun resolve_returnsDecryptedLocalFileUrl_onHappyPath() = runTest {
        coEvery { client.search(any(), any()) } returns listOf(searchItem())
        coEvery { client.track("B00ASIN001", any()) } returns amzTrack()
        val decrypted = flacFile(sampleRate = 96000, bits = 24)
        stubDecryptTo(decrypted)

        val result = resolver().resolve(stubTrack())

        assertThat(result).isNotNull()
        assertThat(result!!.origin).isEqualTo("amz")
        // amz can't be progressively streamed — the resolver decrypts to a
        // local cache file and returns a file:// URL (plays via localFactory).
        // absolutePath keeps this assertion host-platform-agnostic.
        assertThat(result.url).isEqualTo("file://${decrypted.absolutePath}")
        assertThat(result.codec).isEqualTo("flac")
        assertThat(result.coverArtUrl).isEqualTo("https://cdn.example/cover_cdn.jpg")
        assertThat(result.expiresAtMs).isEqualTo(Long.MAX_VALUE)
        // Real quality read from the decrypted FLAC's STREAMINFO (the API
        // reports bitDepth=null) so Now Playing shows "24-bit / 96 kHz", not
        // a bare "FLAC". Bitrate comes from the /api/track stream object.
        assertThat(result.bitsPerSample).isEqualTo(24)
        assertThat(result.sampleRateHz).isEqualTo(96000)
        assertThat(result.bitrateKbps).isEqualTo(3332)
    }

    @Test
    fun resolve_passesEncryptedUrlAndKeyToProvider() = runTest {
        coEvery { client.search(any(), any()) } returns listOf(searchItem())
        coEvery { client.track("B00ASIN001", any()) } returns amzTrack(
            streamUrl = "https://amz.squid.wtf/api/stream?asin=B00ASIN001&tier=best",
        )
        stubDecryptTo()

        resolver().resolve(stubTrack())

        io.mockk.coVerify {
            fileProvider.resolveLocalFile(
                "B00ASIN001",
                "https://amz.squid.wtf/api/stream?asin=B00ASIN001&tier=best",
                "8164fe2db5ebd498c8265b3e873462c1",
            )
        }
    }

    @Test
    fun resolve_requestsTheStreamingPolicyTier() = runTest {
        // Save Data / cellular → CD tier, which maps to amz "high" (data saver).
        coEvery { qualityPolicy.streamingTier() } returns
            com.stash.data.download.lossless.LosslessQualityTier.CD
        coEvery { client.search(any(), any()) } returns listOf(searchItem())
        coEvery { client.track("B00ASIN001", "high") } returns amzTrack()
        stubDecryptTo()

        resolver().resolve(stubTrack())

        io.mockk.coVerify { client.track("B00ASIN001", "high") }
    }

    @Test
    fun resolve_fallsBackToCover_whenCoverCdnNull() = runTest {
        coEvery { client.search(any(), any()) } returns listOf(searchItem())
        coEvery { client.track("B00ASIN001", any()) } returns amzTrack(coverCdn = null)
        stubDecryptTo()

        val result = resolver().resolve(stubTrack())

        assertThat(result!!.coverArtUrl).isEqualTo("https://amazon.example/cover.jpg")
    }

    @Test
    fun resolve_returnsNull_whenNoDecryptionKey() = runTest {
        coEvery { client.search(any(), any()) } returns listOf(searchItem())
        coEvery { client.track("B00ASIN001", any()) } returns AmzTrack(
            meta = trackMeta(),
            decryptionKey = null, // can't decrypt → unplayable
            streamUrl = "https://amz.squid.wtf/api/stream?asin=B00ASIN001",
            codec = "flac",
        )

        assertThat(resolver().resolve(stubTrack())).isNull()
    }

    @Test
    fun resolve_returnsNull_whenProviderFails() = runTest {
        coEvery { client.search(any(), any()) } returns listOf(searchItem())
        coEvery { client.track("B00ASIN001", any()) } returns amzTrack()
        coEvery { fileProvider.resolveLocalFile(any(), any(), any()) } returns null

        assertThat(resolver().resolve(stubTrack())).isNull()
    }

    @Test
    fun resolve_returnsNull_whenNoCandidates() = runTest {
        coEvery { client.search(any(), any()) } returns emptyList()

        assertThat(resolver().resolve(stubTrack())).isNull()
    }

    @Test
    fun resolve_returnsNull_whenMatcherMisses() = runTest {
        // A candidate with a totally unrelated title/artist won't clear the
        // matcher's confidence threshold.
        coEvery { client.search(any(), any()) } returns listOf(
            AmzSearchItem(
                asin = "B00OTHER",
                title = "Completely Different Track",
                primaryArtistName = "Unrelated Band",
            ),
        )

        assertThat(resolver().resolve(stubTrack())).isNull()
    }

    @Test
    fun resolve_returnsNull_whenTrackMetaNull() = runTest {
        coEvery { client.search(any(), any()) } returns listOf(searchItem())
        coEvery { client.track("B00ASIN001", any()) } returns null

        assertThat(resolver().resolve(stubTrack())).isNull()
    }
}
