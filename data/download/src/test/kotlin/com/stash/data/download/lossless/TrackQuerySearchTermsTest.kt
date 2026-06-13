package com.stash.data.download.lossless

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [TrackQuery.searchTerms] decides what we actually send to a lossless proxy.
 *
 * The bug it fixes: tracks are stored with the FULL comma-joined artist credit
 * (e.g. "¥$, Kanye West, Ty Dolla $ign"), and sending that verbatim makes the
 * proxy return the *featured* artists' popular songs instead of the real track,
 * so every candidate scores 0 and the download "fails". The fallback retries
 * with just the primary artist ("¥$"). The full-credit term is tried FIRST so
 * single artists whose NAME contains a comma still match.
 */
class TrackQuerySearchTermsTest {

    @Test fun `multi-artist credit yields full then primary-artist fallback`() {
        val terms = TrackQuery(artist = "¥\$, Kanye West, Ty Dolla \$ign", title = "STARS").searchTerms()
        assertEquals(
            listOf("¥\$, Kanye West, Ty Dolla \$ign STARS", "¥\$ STARS"),
            terms,
        )
    }

    @Test fun `single artist yields only the full term`() {
        assertEquals(
            listOf("Radiohead Karma Police"),
            TrackQuery(artist = "Radiohead", title = "Karma Police").searchTerms(),
        )
    }

    @Test fun `single artist whose name contains a comma is not split`() {
        // "Tyler, The Creator" / "Earth, Wind & Fire" are ONE artist — the
        // primary-before-comma fallback ("Tyler") differs, so it's offered as
        // a second attempt, but the full credit is tried first (and matches).
        val terms = TrackQuery(artist = "Tyler, The Creator", title = "EARFQUAKE").searchTerms()
        assertEquals("Tyler, The Creator EARFQUAKE", terms.first())
    }

    @Test fun `isrc when present is used alone`() {
        assertEquals(
            listOf("USRC12345678"),
            TrackQuery(artist = "¥\$, Kanye West", title = "STARS", isrc = "USRC12345678").searchTerms(),
        )
    }

    @Test fun `blank isrc is ignored`() {
        assertEquals(
            listOf("Radiohead Karma Police"),
            TrackQuery(artist = "Radiohead", title = "Karma Police", isrc = " ").searchTerms(),
        )
    }
}
