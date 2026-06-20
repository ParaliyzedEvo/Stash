package com.stash.data.download.lossless.amz

import com.google.common.truth.Truth.assertThat
import com.stash.data.download.lossless.TrackQuery
import org.junit.Test

/**
 * Unit tests for [AmzMatcher] — the pure best-candidate picker for
 * Amazon Music search results.
 *
 * The matcher scores candidates on artist + title text similarity only
 * (Amazon search results carry neither ISRC nor duration; ISRC
 * confirmation happens later in AmzSource via /api/track). Tests cover:
 *
 *  - Exact artist + title match scores high and is returned.
 *  - A clearly-wrong candidate set returns null (below threshold).
 *  - Featured-artist credit tolerance ("Kanye West feat. …" still matches
 *    a "Kanye West" query).
 *  - The BEST candidate wins among several plausible options, not the first.
 *  - Bracketed-junk tolerance ("[feat. …]" / "[Explicit]" suffixes).
 */
class AmzMatcherTest {

    private fun query(
        artist: String,
        title: String,
        isrc: String? = null,
    ) = TrackQuery(artist = artist, title = title, isrc = isrc)

    private fun item(
        asin: String,
        title: String,
        primaryArtistName: String? = null,
        artistName: String? = null,
        albumArtistName: String? = null,
    ) = AmzSearchItem(
        asin = asin,
        title = title,
        primaryArtistName = primaryArtistName,
        artistName = artistName,
        albumArtistName = albumArtistName,
    )

    @Test
    fun `exact artist and title match is returned with high confidence`() {
        val candidates = listOf(
            item("B001", "Some Other Song", primaryArtistName = "Drake"),
            item("B002", "Ghost Town", primaryArtistName = "Kanye West"),
        )

        val match = AmzMatcher.best(query("Kanye West", "Ghost Town"), candidates)

        assertThat(match).isNotNull()
        assertThat(match!!.item.asin).isEqualTo("B002")
        assertThat(match.confidence).isGreaterThan(0.8f)
    }

    @Test
    fun `clearly wrong candidates return null`() {
        val candidates = listOf(
            item("B001", "Hello", primaryArtistName = "Adele"),
            item("B002", "Bohemian Rhapsody", primaryArtistName = "Queen"),
        )

        val match = AmzMatcher.best(query("Kanye West", "Ghost Town"), candidates)

        assertThat(match).isNull()
    }

    @Test
    fun `featured artist credit on candidate still matches a bare artist query`() {
        val candidates = listOf(
            item(
                "B010",
                "Ghost Town",
                primaryArtistName = "Kanye West feat. PARTYNEXTDOOR",
            ),
        )

        val match = AmzMatcher.best(query("Kanye West", "Ghost Town"), candidates)

        assertThat(match).isNotNull()
        assertThat(match!!.item.asin).isEqualTo("B010")
    }

    @Test
    fun `best of several plausible candidates wins, not the first`() {
        val candidates = listOf(
            // Plausible but imperfect: extra word in title.
            item("B100", "Ghost Town Reprise", primaryArtistName = "Kanye West"),
            // Exact.
            item("B101", "Ghost Town", primaryArtistName = "Kanye West"),
            // Plausible but wrong artist.
            item("B102", "Ghost Town", primaryArtistName = "Benson Boone"),
        )

        val match = AmzMatcher.best(query("Kanye West", "Ghost Town"), candidates)

        assertThat(match).isNotNull()
        assertThat(match!!.item.asin).isEqualTo("B101")
    }

    @Test
    fun `bracketed junk in candidate title is tolerated`() {
        val candidates = listOf(
            item(
                "B200",
                "Ghost Town [feat. PARTYNEXTDOOR] [Explicit]",
                primaryArtistName = "Kanye West",
            ),
        )

        val match = AmzMatcher.best(query("Kanye West", "Ghost Town"), candidates)

        assertThat(match).isNotNull()
        assertThat(match!!.item.asin).isEqualTo("B200")
    }

    @Test
    fun `falls back to artistName then albumArtistName for artist comparison`() {
        val candidates = listOf(
            item(
                "B300",
                "Ghost Town",
                primaryArtistName = null,
                artistName = null,
                albumArtistName = "Kanye West",
            ),
        )

        val match = AmzMatcher.best(query("Kanye West", "Ghost Town"), candidates)

        assertThat(match).isNotNull()
        assertThat(match!!.item.asin).isEqualTo("B300")
    }

    @Test
    fun `empty candidate list returns null`() {
        assertThat(AmzMatcher.best(query("Kanye West", "Ghost Town"), emptyList())).isNull()
    }

    @Test
    fun `confidence is clamped within unit interval`() {
        val candidates = listOf(item("B400", "Ghost Town", primaryArtistName = "Kanye West"))

        val match = AmzMatcher.best(query("Kanye West", "Ghost Town"), candidates)

        assertThat(match).isNotNull()
        assertThat(match!!.confidence).isAtLeast(0f)
        assertThat(match.confidence).isAtMost(1f)
    }
}
