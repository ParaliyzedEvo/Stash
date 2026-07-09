package com.stash.core.data.cache

import com.stash.core.data.discography.QobuzAlbumFetcher
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.AlbumDetail
import com.stash.data.ytmusic.model.AlbumSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Covers [AlbumCache]'s source routing (Task 10): a QOBUZ album loads via the
 * [QobuzAlbumFetcher] and never touches the YT API, and vice-versa. The
 * `"$source:$id"` keying keeps a numeric Qobuz id from colliding with a YT
 * browseId.
 */
class AlbumCacheRoutingTest {

    private fun detail(id: String, title: String) = AlbumDetail(
        id = id, title = title, artist = "A", artistId = null,
        thumbnailUrl = null, year = null, tracks = emptyList(), moreByArtist = emptyList(),
    )

    @Test
    fun `qobuz source routes to the fetcher, not the YT api`() = runTest {
        val api = mock<YTMusicApiClient>()
        val fetcher = mock<QobuzAlbumFetcher>()
        whenever(fetcher.getAlbum(eq("123"))).thenReturn(detail("123", "Loveless"))
        val cache = AlbumCache(api, fetcher)

        val result = cache.get("123", AlbumSource.QOBUZ)

        assertEquals("Loveless", result.title)
        verify(fetcher).getAlbum(eq("123"))
        verifyNoInteractions(api)
    }

    @Test
    fun `youtube source routes to the YT api, not the fetcher`() = runTest {
        val api = mock<YTMusicApiClient>()
        val fetcher = mock<QobuzAlbumFetcher>()
        whenever(api.getAlbum(eq("MPRE1"))).thenReturn(detail("MPRE1", "YT Album"))
        val cache = AlbumCache(api, fetcher)

        val result = cache.get("MPRE1", AlbumSource.YOUTUBE)

        assertEquals("YT Album", result.title)
        verify(api).getAlbum(eq("MPRE1"))
        verifyNoInteractions(fetcher)
    }

    @Test
    fun `same id under different sources are cached separately`() = runTest {
        // A numeric id could in principle appear as both a YT browseId and a
        // Qobuz album id; the composite key must keep them distinct.
        val api = mock<YTMusicApiClient>()
        val fetcher = mock<QobuzAlbumFetcher>()
        whenever(api.getAlbum(eq("42"))).thenReturn(detail("42", "yt"))
        whenever(fetcher.getAlbum(eq("42"))).thenReturn(detail("42", "qobuz"))
        val cache = AlbumCache(api, fetcher)

        assertEquals("yt", cache.get("42", AlbumSource.YOUTUBE).title)
        assertEquals("qobuz", cache.get("42", AlbumSource.QOBUZ).title)
        verify(api).getAlbum(eq("42"))
        verify(fetcher).getAlbum(eq("42"))
    }
}
