package com.stash.feature.search

import com.stash.data.ytmusic.model.AlbumSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlbumFocusTest {
    private fun album(t: String) = AlbumSummary(id = t, title = t, artist = "", thumbnailUrl = null, year = null)
    private val albums = listOf(album("m b v"), album("EPs 1988-1991"))
    private val singles = listOf(album("you made me realise"), album("Feed Me With Your Kiss"))

    @Test fun `matches album case and space insensitively`() {
        val r = findAlbumFocus("M B V", albums, singles)
        assertEquals(AlbumShelf.ALBUMS, r?.shelf); assertEquals(0, r?.index)
    }

    @Test fun `collapses internal and edge whitespace before matching`() {
        // Pins norm's \s+ collapse: fails if norm only did trim().lowercase().
        val r = findAlbumFocus("  M   B   V  ", albums, singles)
        assertEquals(AlbumShelf.ALBUMS, r?.shelf); assertEquals(0, r?.index)
    }

    @Test fun `matches single when not in albums`() {
        val r = findAlbumFocus("you made me realise", albums, singles)
        assertEquals(AlbumShelf.SINGLES, r?.shelf); assertEquals(0, r?.index)
    }

    @Test fun `null focus returns null`() = assertNull(findAlbumFocus(null, albums, singles))

    @Test fun `no match returns null`() = assertNull(findAlbumFocus("Loveless", albums, singles))
}
