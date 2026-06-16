package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.download.lossless.arcod.ArcodAlbum
import com.stash.data.download.lossless.arcod.ArcodClient
import com.stash.data.download.lossless.arcod.ArcodImage
import com.stash.data.download.lossless.arcod.ArcodJob
import com.stash.data.download.lossless.arcod.ArcodNamed
import com.stash.data.download.lossless.arcod.ArcodTrackItem
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ArcodStreamResolverTest {

    private val client: ArcodClient = mockk()

    private fun resolver() = ArcodStreamResolver(client)

    private fun stubTrack(): TrackEntity = TrackEntity(
        id = 7L,
        title = "Some Song",
        artist = "Some Artist",
        album = "Some Album",
        durationMs = 210_000L,
        isrc = "USRC17607839",
    )

    /** A catalog item that ArcodMatcher (real object) will confidently match. */
    private fun matchingItem(): ArcodTrackItem = ArcodTrackItem(
        id = 8767428L,
        title = "Some Song",
        isrc = "USRC17607839",
        duration = 210, // seconds — within the matcher's 5s duration guard
        performer = ArcodNamed(name = "Some Artist", id = 99L),
        album = ArcodAlbum(
            id = "0093624804567",
            title = "Some Album",
            artist = ArcodNamed(name = "Some Artist", id = 42L),
            image = ArcodImage(large = "https://arcod.xyz/cover.jpg"),
            releaseDate = "2020-01-01",
            tracksCount = 12,
        ),
    )

    private fun completedJob(url: String) = ArcodJob(
        id = "job-1",
        status = "completed",
        downloadUrl = url,
    )

    @Test
    fun resolve_happyPath_returnsArcodStreamUrl() = runTest {
        val url = "https://dl.arcod.xyz/files/abc.flac?token=xyz"
        coEvery { client.search(any()) } returns listOf(matchingItem())
        coEvery { client.createJob(any()) } returns ArcodJob(id = "job-1", status = "queued")
        coEvery { client.pollStatus(any(), any(), any()) } returns completedJob(url)
        every { client.downloadUrlFrom(any()) } returns url

        val before = System.currentTimeMillis()
        val result = resolver().resolve(stubTrack())
        val after = System.currentTimeMillis()

        assertThat(result).isNotNull()
        assertThat(result!!.origin).isEqualTo("arcod")
        assertThat(result.url).isEqualTo(url)
        assertThat(result.codec).isEqualTo("flac")
        assertThat(result.coverArtUrl).isEqualTo("https://arcod.xyz/cover.jpg")
        assertThat(result.expiresAtMs).isAtLeast(before + 280_000L)
        assertThat(result.expiresAtMs).isAtMost(after + 280_000L)
    }

    @Test
    fun resolve_returnsNull_whenNoMatch() = runTest {
        coEvery { client.search(any()) } returns emptyList()

        val result = resolver().resolve(stubTrack())

        assertThat(result).isNull()
    }

    @Test
    fun resolve_returnsNull_whenCreateJobNull() = runTest {
        coEvery { client.search(any()) } returns listOf(matchingItem())
        coEvery { client.createJob(any()) } returns null

        val result = resolver().resolve(stubTrack())

        assertThat(result).isNull()
    }

    @Test
    fun resolve_returnsNull_whenPollStatusNull() = runTest {
        coEvery { client.search(any()) } returns listOf(matchingItem())
        coEvery { client.createJob(any()) } returns ArcodJob(id = "job-1", status = "queued")
        coEvery { client.pollStatus(any(), any(), any()) } returns null

        val result = resolver().resolve(stubTrack())

        assertThat(result).isNull()
    }
}
