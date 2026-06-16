package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.download.lossless.amz.AmzApiClient
import com.stash.data.download.lossless.amz.AmzSearchItem
import com.stash.data.download.lossless.amz.AmzTrackMeta
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AmzStreamResolverTest {

    private val client: AmzApiClient = mockk()

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

    @Test
    fun resolve_returnsStreamUrl_onHappyPath() = runTest {
        coEvery { client.search(any(), any()) } returns listOf(searchItem())
        coEvery { client.track("B00ASIN001") } returns trackMeta()
        every { client.streamUrl("B00ASIN001") } returns "https://amz.squid.wtf/api/stream?asin=B00ASIN001"
        val resolver = AmzStreamResolver(client)

        val result = resolver.resolve(stubTrack())

        assertThat(result).isNotNull()
        assertThat(result!!.origin).isEqualTo("amz")
        assertThat(result.url).isEqualTo("https://amz.squid.wtf/api/stream?asin=B00ASIN001")
        assertThat(result.codec).isEqualTo("flac")
        assertThat(result.coverArtUrl).isEqualTo("https://cdn.example/cover_cdn.jpg")
        assertThat(result.expiresAtMs).isEqualTo(Long.MAX_VALUE)
    }

    @Test
    fun resolve_fallsBackToCover_whenCoverCdnNull() = runTest {
        coEvery { client.search(any(), any()) } returns listOf(searchItem())
        coEvery { client.track("B00ASIN001") } returns trackMeta(coverCdn = null)
        every { client.streamUrl("B00ASIN001") } returns "https://amz.squid.wtf/api/stream?asin=B00ASIN001"
        val resolver = AmzStreamResolver(client)

        val result = resolver.resolve(stubTrack())

        assertThat(result!!.coverArtUrl).isEqualTo("https://amazon.example/cover.jpg")
    }

    @Test
    fun resolve_returnsNull_whenNoCandidates() = runTest {
        coEvery { client.search(any(), any()) } returns emptyList()
        val resolver = AmzStreamResolver(client)

        assertThat(resolver.resolve(stubTrack())).isNull()
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
        val resolver = AmzStreamResolver(client)

        assertThat(resolver.resolve(stubTrack())).isNull()
    }

    @Test
    fun resolve_returnsNull_whenTrackMetaNull() = runTest {
        coEvery { client.search(any(), any()) } returns listOf(searchItem())
        coEvery { client.track("B00ASIN001") } returns null
        val resolver = AmzStreamResolver(client)

        assertThat(resolver.resolve(stubTrack())).isNull()
    }
}
