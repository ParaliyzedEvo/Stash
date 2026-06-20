package com.stash.data.download.lossless.amz

import com.google.common.truth.Truth.assertThat
import com.stash.data.download.lossless.AggregatorRateLimiter
import com.stash.data.download.lossless.RateLimitState
import com.stash.data.download.lossless.TrackQuery
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Unit tests for [AmzSource]. The [AmzApiClient] and [AggregatorRateLimiter]
 * are MockK'd; [AmzMatcher] is the real pure object, fed real
 * [AmzSearchItem]s so the confidence/threshold behaviour is exercised
 * end-to-end rather than stubbed.
 */
class AmzSourceTest {

    private val client: AmzApiClient = mockk()
    private val rateLimiter: AggregatorRateLimiter = mockk(relaxUnitFun = true)

    private fun source() = AmzSource(client, rateLimiter)

    private val query = TrackQuery(
        artist = "Daft Punk",
        title = "Get Lucky",
        isrc = "USQX91300108",
    )

    /** A search item that scores well above AmzMatcher's 0.5 threshold. */
    private fun candidate(asin: String = "B00ABCDEFG") = AmzSearchItem(
        asin = asin,
        title = "Get Lucky",
        primaryArtistName = "Daft Punk",
    )

    private fun meta(asin: String = "B00ABCDEFG", isrc: String? = "USQX91300108") = AmzTrackMeta(
        asin = asin,
        title = "Get Lucky",
        artist = "Daft Punk",
        album = "Random Access Memories",
        cover = "https://art/small.jpg",
        coverCdn = "https://art/cdn-large.jpg",
        isrc = isrc,
    )

    /** Wraps [meta] in an [AmzTrack] carrying a DRM key + encrypted stream URL. */
    private fun amzTrack(
        asin: String = "B00ABCDEFG",
        isrc: String? = "USQX91300108",
        streamUrl: String? = "https://amz/stream?asin=B00ABCDEFG",
    ) = AmzTrack(
        meta = meta(asin, isrc),
        decryptionKey = "8164fe2db5ebd498c8265b3e873462c1",
        streamUrl = streamUrl,
        codec = "flac",
    )

    private fun enabledAndAcquired() {
        coEvery { rateLimiter.stateOf(AmzSource.SOURCE_ID) } returns
            RateLimitState(2.0, 0L, isCircuitBroken = false, msUntilUnblock = 0L, recentFailures = 0)
        coEvery { rateLimiter.acquire(AmzSource.SOURCE_ID) } returns true
    }

    @Test
    fun `circuit-broken source returns null without searching`() = runTest {
        coEvery { rateLimiter.stateOf(AmzSource.SOURCE_ID) } returns
            RateLimitState(0.0, 0L, isCircuitBroken = true, msUntilUnblock = 60_000L, recentFailures = 5)

        val result = source().resolve(query)

        assertThat(result).isNull()
        coVerify(exactly = 0) { client.search(any(), any()) }
    }

    @Test
    fun `acquire denied returns null without searching`() = runTest {
        coEvery { rateLimiter.stateOf(AmzSource.SOURCE_ID) } returns
            RateLimitState(0.0, 0L, isCircuitBroken = false, msUntilUnblock = 0L, recentFailures = 0)
        coEvery { rateLimiter.acquire(AmzSource.SOURCE_ID) } returns false

        val result = source().resolve(query)

        assertThat(result).isNull()
        coVerify(exactly = 0) { client.search(any(), any()) }
    }

    @Test
    fun `no credible match returns null and reports failure`() = runTest {
        enabledAndAcquired()
        // Wrong artist + wrong title → matcher rejects below threshold.
        coEvery { client.search(any(), any()) } returns
            listOf(AmzSearchItem(asin = "X", title = "Totally Different", primaryArtistName = "Someone Else"))

        val result = source().resolve(query)

        assertThat(result).isNull()
        coVerify { rateLimiter.reportFailure(AmzSource.SOURCE_ID) }
        coVerify(exactly = 0) { client.track(any()) }
    }

    @Test
    fun `empty candidates returns null and reports failure`() = runTest {
        enabledAndAcquired()
        coEvery { client.search(any(), any()) } returns emptyList()

        val result = source().resolve(query)

        assertThat(result).isNull()
        coVerify { rateLimiter.reportFailure(AmzSource.SOURCE_ID) }
    }

    @Test
    fun `track metadata null returns null and reports failure`() = runTest {
        enabledAndAcquired()
        coEvery { client.search(any(), any()) } returns listOf(candidate())
        coEvery { client.track("B00ABCDEFG") } returns null

        val result = source().resolve(query)

        assertThat(result).isNull()
        coVerify { rateLimiter.reportFailure(AmzSource.SOURCE_ID) }
    }

    @Test
    fun `happy path with ISRC match yields 0_95 confidence and a result`() = runTest {
        enabledAndAcquired()
        coEvery { client.search(any(), any()) } returns listOf(candidate())
        coEvery { client.track("B00ABCDEFG") } returns amzTrack(isrc = "USQX91300108")

        val result = source().resolve(query)

        assertThat(result).isNotNull()
        assertThat(result!!.confidence).isEqualTo(0.95f)
        assertThat(result.sourceId).isEqualTo("amz")
        assertThat(result.downloadUrl).isEqualTo("https://amz/stream?asin=B00ABCDEFG")
        assertThat(result.downloadHeaders).isEmpty()
        assertThat(result.format.codec).isEqualTo("flac")
        assertThat(result.format.bitrateKbps).isEqualTo(0)
        assertThat(result.format.sampleRateHz).isEqualTo(0)
        assertThat(result.format.bitsPerSample).isEqualTo(0)
        assertThat(result.sourceTrackId).isEqualTo("B00ABCDEFG")
        // coverCdn preferred over cover.
        assertThat(result.coverArtUrl).isEqualTo("https://art/cdn-large.jpg")
        coVerify { rateLimiter.reportSuccess(AmzSource.SOURCE_ID) }
    }

    @Test
    fun `happy path without ISRC keeps matcher confidence not 0_95`() = runTest {
        enabledAndAcquired()
        val noIsrcQuery = TrackQuery(artist = "Daft Punk", title = "Get Lucky", isrc = null)
        coEvery { client.search(any(), any()) } returns listOf(candidate())
        coEvery { client.track("B00ABCDEFG") } returns amzTrack(isrc = null)

        val result = source().resolve(noIsrcQuery)

        assertThat(result).isNotNull()
        // The matcher's text-derived confidence — exact title+artist match → 1.0,
        // never the ISRC-confirmed 0.95 boost.
        assertThat(result!!.confidence).isNotEqualTo(0.95f)
        coVerify { rateLimiter.reportSuccess(AmzSource.SOURCE_ID) }
    }

    @Test
    fun `ISRC mismatch is not rejected and keeps matcher confidence`() = runTest {
        enabledAndAcquired()
        coEvery { client.search(any(), any()) } returns listOf(candidate())
        coEvery { client.track("B00ABCDEFG") } returns amzTrack(isrc = "GBUM71029604") // different master

        val result = source().resolve(query)

        assertThat(result).isNotNull()
        assertThat(result!!.confidence).isNotEqualTo(0.95f)
        coVerify { rateLimiter.reportSuccess(AmzSource.SOURCE_ID) }
    }

    @Test
    fun `rate-limited exception reports rate limited and returns null`() = runTest {
        enabledAndAcquired()
        coEvery { client.search(any(), any()) } throws AmzRateLimitedException()

        val result = source().resolve(query)

        assertThat(result).isNull()
        coVerify { rateLimiter.reportRateLimited(AmzSource.SOURCE_ID) }
        coVerify(exactly = 0) { rateLimiter.reportFailure(AmzSource.SOURCE_ID) }
    }

    @Test
    fun `cancellation propagates and is not swallowed as a failure`() = runTest {
        enabledAndAcquired()
        coEvery { client.search(any(), any()) } throws CancellationException("cancelled")

        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { source().resolve(query) }
        }
        coVerify(exactly = 0) { rateLimiter.reportFailure(AmzSource.SOURCE_ID) }
    }
}
