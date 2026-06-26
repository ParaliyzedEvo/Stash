package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.download.lossless.LosslessQualityTier
import com.stash.data.download.lossless.arcod.ArcodAlbum
import com.stash.data.download.lossless.arcod.ArcodClient
import com.stash.data.download.lossless.arcod.ArcodImage
import com.stash.data.download.lossless.arcod.ArcodNamed
import com.stash.data.download.lossless.arcod.ArcodStreamResult
import com.stash.data.download.lossless.arcod.ArcodTrackItem
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ArcodStreamResolverTest {

    private val client: ArcodClient = mockk()
    private val qualityPolicy: StreamQualityPolicy = mockk()

    private fun resolver() = ArcodStreamResolver(client, qualityPolicy)

    private fun stubTrack(): TrackEntity = TrackEntity(
        id = 7L,
        title = "Some Song",
        artist = "Some Artist",
        album = "Some Album",
        durationMs = 210_000L,
        isrc = "USRC17607839",
    )

    /** A catalog item that ArcodMatcher (real object) will confidently match. */
    private fun matchingItem(albumId: String? = "0093624804567"): ArcodTrackItem = ArcodTrackItem(
        id = 8767428L,
        title = "Some Song",
        isrc = "USRC17607839",
        duration = 210, // seconds — within the matcher's duration guard
        maxBitDepth = 24,
        maxSamplingRate = 88.2,
        performer = ArcodNamed(name = "Some Artist", id = 99L),
        album = ArcodAlbum(
            id = albumId,
            title = "Some Album",
            artist = ArcodNamed(name = "Some Artist", id = 42L),
            image = ArcodImage(large = "https://arcod.xyz/cover.jpg"),
            releaseDate = "2020-01-01",
            tracksCount = 12,
        ),
    )

    @Test
    fun resolve_happyPath_threadsQualityCode_andReturnsArcodStreamUrl() = runTest {
        val url = "https://dl.arcod.xyz/stream/abc.flac?token=xyz"
        coEvery { client.search(any()) } returns listOf(matchingItem())
        coEvery { qualityPolicy.streamingTier() } returns LosslessQualityTier.MAX // qobuzCode 27
        coEvery { client.streamUrl(any(), any()) } returns ArcodStreamResult(url)

        val before = System.currentTimeMillis()
        val result = resolver().resolve(stubTrack())
        val after = System.currentTimeMillis()

        coVerify { client.streamUrl(8767428L, 27) }
        assertThat(result).isNotNull()
        assertThat(result!!.origin).isEqualTo("arcod")
        assertThat(result.url).isEqualTo(url)
        assertThat(result.codec).isEqualTo("flac")
        // Delivered quality derived from the matched item's catalog maximums
        // (24/88.2) clamped to the MAX (27) tier ceiling — no clamp needed here.
        assertThat(result.bitsPerSample).isEqualTo(24)
        assertThat(result.sampleRateHz).isEqualTo(88_200)
        assertThat(result.coverArtUrl).isEqualTo("https://arcod.xyz/cover.jpg")
        // No expiresIn -> conservative 280s default TTL.
        assertThat(result.expiresAtMs).isAtLeast(before + 280_000L)
        assertThat(result.expiresAtMs).isAtMost(after + 280_000L)
    }

    @Test
    fun resolve_cdTier_sendsQobuzCode6() = runTest {
        coEvery { client.search(any()) } returns listOf(matchingItem())
        coEvery { qualityPolicy.streamingTier() } returns LosslessQualityTier.CD // qobuzCode 6
        coEvery { client.streamUrl(any(), any()) } returns ArcodStreamResult("https://x/y.flac")

        val result = resolver().resolve(stubTrack())

        coVerify { client.streamUrl(8767428L, 6) }
        // CD (6) clamps the 24/88.2 master down to 16/44.1.
        assertThat(result!!.bitsPerSample).isEqualTo(16)
        assertThat(result.sampleRateHz).isEqualTo(44_100)
    }

    @Test
    fun resolve_usesExpiresIn_forTtl_whenPresent() = runTest {
        coEvery { client.search(any()) } returns listOf(matchingItem())
        coEvery { qualityPolicy.streamingTier() } returns LosslessQualityTier.MAX
        coEvery { client.streamUrl(any(), any()) } returns
            ArcodStreamResult("https://x/y.flac", expiresInSec = 120)

        val before = System.currentTimeMillis()
        val result = resolver().resolve(stubTrack())
        val after = System.currentTimeMillis()

        // 120s lifetime minus a 20s safety margin = ~100s TTL.
        assertThat(result!!.expiresAtMs).isAtLeast(before + 100_000L)
        assertThat(result.expiresAtMs).isAtMost(after + 100_000L)
    }

    @Test
    fun resolve_tinyExpiresIn_flooredToShortTtl_notDefault() = runTest {
        coEvery { client.search(any()) } returns listOf(matchingItem())
        coEvery { qualityPolicy.streamingTier() } returns LosslessQualityTier.MAX
        coEvery { client.streamUrl(any(), any()) } returns
            ArcodStreamResult("https://x/y.flac", expiresInSec = 5)

        val before = System.currentTimeMillis()
        val result = resolver().resolve(stubTrack())
        val after = System.currentTimeMillis()

        // 5s expiry floors to MIN_TTL_MS (5s) — NOT the 280s default.
        assertThat(result!!.expiresAtMs).isAtLeast(before + 5_000L)
        assertThat(result.expiresAtMs).isAtMost(after + 5_000L)
    }

    @Test
    fun resolve_resolvesEvenWhenAlbumIdMissing() = runTest {
        coEvery { client.search(any()) } returns listOf(matchingItem(albumId = null))
        coEvery { qualityPolicy.streamingTier() } returns LosslessQualityTier.MAX
        coEvery { client.streamUrl(any(), any()) } returns ArcodStreamResult("https://x/y.flac")

        val result = resolver().resolve(stubTrack())

        assertThat(result).isNotNull()
        coVerify { client.streamUrl(8767428L, 27) }
    }

    @Test
    fun resolve_returnsNull_whenNoMatch() = runTest {
        coEvery { client.search(any()) } returns emptyList()
        coEvery { qualityPolicy.streamingTier() } returns LosslessQualityTier.MAX

        assertThat(resolver().resolve(stubTrack())).isNull()
    }

    @Test
    fun resolve_returnsNull_whenStreamUrlNull() = runTest {
        coEvery { client.search(any()) } returns listOf(matchingItem())
        coEvery { qualityPolicy.streamingTier() } returns LosslessQualityTier.MAX
        coEvery { client.streamUrl(any(), any()) } returns null

        assertThat(resolver().resolve(stubTrack())).isNull()
    }
}
