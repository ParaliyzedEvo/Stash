package com.stash.data.download.lossless.qbdlx

class QbdlxCatalogParseTest {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private fun fixture(n: String) = javaClass.classLoader!!.getResourceAsStream("qbdlx/$n")!!.reader().readText()

    @org.junit.Test fun `artist search parses items`() {
        val r = json.decodeFromString<QbdlxArtistSearchResponse>(fixture("artist_search_mbv.json"))
        org.junit.Assert.assertTrue(r.artists.items.any { it.name.contains("bloody", true) })
    }

    @org.junit.Test fun `artist albums include Loveless and Isnt Anything`() {
        val r = json.decodeFromString<QbdlxArtistAlbumsResponse>(fixture("artist_albums_mbv.json"))
        // normalize unicode apostrophe (U+2019) to ASCII, then strip, so "Isn't Anything" matches faithfully.
        val titles = r.albums.items.map { it.title.lowercase().replace('’', '\'') }
        org.junit.Assert.assertTrue(titles.any { it.contains("loveless") })
        org.junit.Assert.assertTrue(titles.any { it.contains("isn't anything") || it.replace("'", "").contains("isnt anything") })
    }

    @org.junit.Test fun `album detail parses tracks with ids and durations`() {
        val r = json.decodeFromString<QbdlxAlbumDetailResponse>(fixture("album_loveless.json"))
        org.junit.Assert.assertTrue(r.tracks.items.isNotEmpty())
        org.junit.Assert.assertTrue(r.tracks.items.all { it.id > 0 && it.duration > 0 })
    }
}
