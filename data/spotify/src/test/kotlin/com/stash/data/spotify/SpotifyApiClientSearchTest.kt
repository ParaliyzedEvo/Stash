package com.stash.data.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser-contract tests for [parseSearchTracks], the pure function backing
 * [SpotifyApiClient.searchTracks].
 *
 * The Spotify Web API `/v1/search?type=track` response wraps results under
 * `{ "tracks": { "items": [ ...track objects... ] } }`. Unlike the playlist
 * endpoint, each item IS the track object directly (no `item.track` wrapper).
 * The parser must pull id, name, album.name -> albumName, duration_ms ->
 * durationMs, explicit, ordered artists[].name, and external_ids.isrc (often
 * absent on /search results) into [SpotifyTrackCandidate]s.
 */
class SpotifyApiClientSearchTest {

    private fun loadFixture(name: String): String =
        this::class.java.classLoader!!
            .getResourceAsStream("fixtures/$name")!!
            .bufferedReader()
            .use { it.readText() }

    @Test
    fun `parses search results into candidates with isrc explicit and fields`() {
        val body = loadFixture("webapi_search_tracks.json")

        val candidates = parseSearchTracks(body)

        assertEquals(3, candidates.size)

        val smooth = candidates[0]
        assertEquals("2bCQHF9gdG5BNDVuEIEnNk", smooth.id)
        assertEquals("Smooth Criminal - 2012 Remaster", smooth.name)
        assertEquals("Bad 25th Anniversary", smooth.albumName)
        assertEquals(257766L, smooth.durationMs)
        assertFalse(smooth.explicit)
        assertEquals("USSM11200115", smooth.isrc)
        assertEquals(listOf("Michael Jackson"), smooth.artists)
    }

    @Test
    fun `multiple artists preserve order with primary first`() {
        val body = loadFixture("webapi_search_tracks.json")

        val collab = parseSearchTracks(body)[1]

        assertEquals(listOf("Primary Artist", "Featured Artist"), collab.artists)
        assertTrue("explicit:true in JSON must parse as explicit", collab.explicit)
        assertEquals("USUG11900001", collab.isrc)
    }

    @Test
    fun `missing external_ids yields null isrc`() {
        val body = loadFixture("webapi_search_tracks.json")

        val legacy = parseSearchTracks(body)[2]

        assertNull(
            "track JSON with no external_ids must yield null ISRC, not a blank string",
            legacy.isrc,
        )
        assertFalse(legacy.explicit)
    }

    @Test
    fun `empty items list yields empty candidate list`() {
        val body = loadFixture("webapi_search_tracks_empty.json")

        assertTrue(parseSearchTracks(body).isEmpty())
    }
}
