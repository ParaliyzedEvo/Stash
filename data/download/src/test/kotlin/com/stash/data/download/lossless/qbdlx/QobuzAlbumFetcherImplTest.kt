package com.stash.data.download.lossless.qbdlx

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [QobuzAlbumFetcherImpl]. [QbdlxApiClient] + [QbdlxCredentialStore]
 * are MockK'd; the mocked getAlbum returns the real parsed `album_loveless.json`
 * fixture so the Qobuz→AlbumDetail mapping runs against a faithful response.
 */
class QobuzAlbumFetcherImplTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private fun fixture(n: String) = javaClass.classLoader!!.getResourceAsStream("qbdlx/$n")!!.reader().readText()

    private val apiClient: QbdlxApiClient = mockk()
    private val credentialStore: QbdlxCredentialStore = mockk()

    private fun fetcher() = QobuzAlbumFetcherImpl(apiClient, credentialStore)

    @Test
    fun `maps qobuz album to AlbumDetail with blank videoIds and real durations`() = runTest {
        val album = json.decodeFromString<QbdlxAlbumDetailResponse>(fixture("album_loveless.json"))
        coEvery { credentialStore.activeToken() } returns "tok"
        coEvery { apiClient.getAlbum("123", "tok") } returns album

        val detail = fetcher().getAlbum("123")

        assertEquals("loveless", detail.title.lowercase())
        assertTrue(detail.tracks.isNotEmpty())
        assertTrue(detail.tracks.all { it.videoId == "" })
        assertTrue(detail.tracks.all { it.durationSeconds > 0 })
        assertTrue(detail.moreByArtist.isEmpty())
    }

    @Test
    fun `throws when no live token`() {
        coEvery { credentialStore.activeToken() } returns null

        assertThrows(IllegalStateException::class.java) {
            runBlocking { fetcher().getAlbum("123") }
        }
    }
}
