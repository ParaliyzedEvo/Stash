package com.stash.data.download.lossless.arcod

import com.stash.data.download.lossless.TrackQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcodMatcherTest {

    private fun item(
        id: Long = 1L,
        title: String,
        performer: String? = null,
        albumArtist: String? = null,
        isrc: String? = null,
        duration: Int? = null,
    ) = ArcodTrackItem(
        id = id,
        title = title,
        isrc = isrc,
        duration = duration,
        performer = performer?.let { ArcodNamed(name = it) },
        album = albumArtist?.let {
            ArcodAlbum(artist = ArcodNamed(name = it))
        },
    )

    @Test
    fun `exact artist and title match is returned with high confidence`() {
        val query = TrackQuery(artist = "Daft Punk", title = "Get Lucky")
        val items = listOf(item(title = "Get Lucky", performer = "Daft Punk"))

        val match = ArcodMatcher.best(query, items)

        assertNotNull(match)
        assertEquals(items[0], match!!.item)
        assertTrue("confidence should be high, was ${match.confidence}", match.confidence >= 0.9f)
    }

    @Test
    fun `wrong artist and title returns null`() {
        val query = TrackQuery(artist = "Daft Punk", title = "Get Lucky")
        val items = listOf(item(title = "Bohemian Rhapsody", performer = "Queen"))

        assertNull(ArcodMatcher.best(query, items))
    }

    @Test
    fun `feat and bracket content in candidate title is tolerated`() {
        val query = TrackQuery(artist = "Daft Punk", title = "Get Lucky")
        val items = listOf(
            item(
                title = "Get Lucky (feat. Pharrell Williams) [Radio Edit]",
                performer = "Daft Punk",
            ),
        )

        val match = ArcodMatcher.best(query, items)

        assertNotNull(match)
        assertTrue(match!!.confidence >= 0.5f)
    }

    @Test
    fun `featured artist in candidate performer name is tolerated`() {
        val query = TrackQuery(artist = "Daft Punk", title = "Get Lucky")
        val items = listOf(
            item(title = "Get Lucky", performer = "Daft Punk feat. Pharrell Williams"),
        )

        val match = ArcodMatcher.best(query, items)

        assertNotNull(match)
        assertTrue(match!!.confidence >= 0.5f)
    }

    @Test
    fun `best of several candidates wins, not the first`() {
        val query = TrackQuery(artist = "Daft Punk", title = "Get Lucky")
        val items = listOf(
            item(id = 1, title = "Lucky Star", performer = "Madonna"),
            item(id = 2, title = "Get Lucky", performer = "Daft Punk"),
            item(id = 3, title = "Lucky", performer = "Daft Punk"),
        )

        val match = ArcodMatcher.best(query, items)

        assertNotNull(match)
        assertEquals(2L, match!!.item.id)
    }

    @Test
    fun `dramatically different duration (live cut) sinks below the bar`() {
        // Query is 4:00; the same-title/artist candidate is a 12:00 live cut
        // (>20% drift → 0.3 factor → 1.0*1.0*0.3 = 0.3 < 0.5).
        val query = TrackQuery(
            artist = "Daft Punk",
            title = "Get Lucky",
            durationMs = 240_000L,
        )
        val items = listOf(
            item(id = 1, title = "Get Lucky", performer = "Daft Punk", duration = 720),
        )

        assertNull(ArcodMatcher.best(query, items))
    }

    @Test
    fun `moderate cross-source duration drift is still accepted`() {
        // Regression (on-device 2026-06-16): a synced track's YouTube/Spotify
        // duration routinely differs from the Qobuz master by 10-20s. With the
        // old 5s absolute hard-guard this rejected exact artist+title matches
        // → no_match → yt-dlp fallthrough. 18s on a 4:00 track = 7.5% drift →
        // 0.85 factor → 1.0*1.0*0.85 = 0.85 ≥ 0.5 → accepted.
        val query = TrackQuery(
            artist = "The Smashing Pumpkins",
            title = "1979",
            durationMs = 240_000L,
        )
        val items = listOf(
            item(id = 1, title = "1979", performer = "The Smashing Pumpkins", duration = 258),
        )

        val match = ArcodMatcher.best(query, items)
        assertNotNull(match)
        assertEquals(1L, match!!.item.id)
    }

    @Test
    fun `candidate within 5s duration is accepted`() {
        val query = TrackQuery(
            artist = "Daft Punk",
            title = "Get Lucky",
            durationMs = 240_000L,
        )
        val items = listOf(
            item(id = 1, title = "Get Lucky", performer = "Daft Punk", duration = 243),
        )

        val match = ArcodMatcher.best(query, items)
        assertNotNull(match)
        assertEquals(1L, match!!.item.id)
    }

    @Test
    fun `dramatically-off candidate is skipped but a good one still wins`() {
        val query = TrackQuery(
            artist = "Daft Punk",
            title = "Get Lucky",
            durationMs = 240_000L,
        )
        val items = listOf(
            item(id = 1, title = "Get Lucky", performer = "Daft Punk", duration = 720),
            item(id = 2, title = "Get Lucky", performer = "Daft Punk", duration = 241),
        )

        val match = ArcodMatcher.best(query, items)
        assertNotNull(match)
        assertEquals(2L, match!!.item.id)
    }

    @Test
    fun `matching isrc yields confidence at least 0_95`() {
        val query = TrackQuery(
            artist = "Daft Punk",
            title = "Get Lucky",
            isrc = "USQX91300106",
        )
        val items = listOf(
            // Deliberately weak text match so only the ISRC boost lifts it.
            item(title = "Lucky", performer = "DP", isrc = "usqx91300106"),
        )

        val match = ArcodMatcher.best(query, items)

        assertNotNull(match)
        assertTrue(
            "confidence should be >= 0.95, was ${match!!.confidence}",
            match.confidence >= 0.95f,
        )
    }

    @Test
    fun `album artist is used when performer is absent`() {
        val query = TrackQuery(artist = "Daft Punk", title = "Get Lucky")
        val items = listOf(item(title = "Get Lucky", albumArtist = "Daft Punk"))

        val match = ArcodMatcher.best(query, items)

        assertNotNull(match)
        assertTrue(match!!.confidence >= 0.5f)
    }
}
