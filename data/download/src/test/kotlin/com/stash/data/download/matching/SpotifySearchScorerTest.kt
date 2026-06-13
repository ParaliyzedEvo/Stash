package com.stash.data.download.matching

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.sync.TrackMatcher
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.spotify.SpotifyTrackCandidate
import org.junit.Test

/**
 * Contract tests for [SpotifySearchScorer] — the bulletproof core of the
 * Spotify-URI resolver. The scorer's job is to accept the ONE candidate that
 * is safe to route to antra, or return null. A missed match is fine; a wrong
 * match (live/remaster/cover/sped-up/karaoke/alt-mix) is unacceptable.
 */
class SpotifySearchScorerTest {

    private val scorer = SpotifySearchScorer(TrackMatcher())

    private fun track(
        title: String,
        artist: String,
        durMs: Long?,
    ) = TrackQuery(artist = artist, title = title, durationMs = durMs)

    private fun cand(
        name: String,
        artists: List<String>,
        durMs: Long,
        id: String = "id",
    ) = SpotifyTrackCandidate(
        id = id,
        name = name,
        artists = artists,
        albumName = "Album",
        durationMs = durMs,
        isrc = null,
        explicit = false,
    )

    @Test
    fun `accepts exact title and artist within duration tolerance`() {
        val t = track(title = "Song", artist = "Artist", durMs = 200_000)
        val c = cand(name = "Song", artists = listOf("Artist"), durMs = 201_000)

        val decision = scorer.pick(t, listOf(c))

        assertThat(decision.accepted).isEqualTo(c)
    }

    @Test
    fun `rejects live within 4s via raw-title version veto`() {
        // titleSim is ~1.0 because canonicalTitle strips "- Live", so ONLY the
        // raw-title version veto can save this. SAME duration.
        val t = track(title = "Foxey Lady", artist = "Jimi Hendrix", durMs = 200_000)
        val c = cand(name = "Foxey Lady (Live)", artists = listOf("Jimi Hendrix"), durMs = 200_000)

        val decision = scorer.pick(t, listOf(c))

        assertThat(decision.accepted).isNull()
    }

    @Test
    fun `rejects live with big duration delta`() {
        val t = track(title = "Song", artist = "Artist", durMs = 200_000)
        val c = cand(name = "Song (Live)", artists = listOf("Artist"), durMs = 240_000)

        assertThat(scorer.pick(t, listOf(c)).accepted).isNull()
    }

    @Test
    fun `rejects remaster`() {
        val t = track(title = "Song", artist = "Artist", durMs = 200_000)
        // Parenthetical form so canonicalTitle strips it and titleSim ~1.0 —
        // forcing the raw-title veto (not the title gate) to do the rejecting.
        val c = cand(name = "Song (2011 Remaster)", artists = listOf("Artist"), durMs = 200_000)

        assertThat(scorer.pick(t, listOf(c)).accepted).isNull()
    }

    @Test
    fun `rejects cover by different artist`() {
        val t = track(title = "Song", artist = "Artist", durMs = 200_000)
        val c = cand(name = "Song", artists = listOf("Some Cover Band"), durMs = 200_000)

        assertThat(scorer.pick(t, listOf(c)).accepted).isNull()
    }

    @Test
    fun `rejects sped up`() {
        // Same duration so ONLY the raw-title veto (not the duration gate) rejects.
        val t = track(title = "Song", artist = "Artist", durMs = 200_000)
        val c = cand(name = "Song (Sped Up)", artists = listOf("Artist"), durMs = 200_000)

        assertThat(scorer.pick(t, listOf(c)).accepted).isNull()
    }

    @Test
    fun `rejects karaoke`() {
        val t = track(title = "Song", artist = "Artist", durMs = 200_000)
        val c = cand(name = "Song (Karaoke)", artists = listOf("Artist"), durMs = 200_000)

        assertThat(scorer.pick(t, listOf(c)).accepted).isNull()
    }

    @Test
    fun `rejects instrumental`() {
        val t = track(title = "Song", artist = "Artist", durMs = 200_000)
        val c = cand(name = "Song (Instrumental)", artists = listOf("Artist"), durMs = 200_000)

        assertThat(scorer.pick(t, listOf(c)).accepted).isNull()
    }

    @Test
    fun `rejects different song with low title similarity`() {
        val t = track(title = "Purple Haze", artist = "Jimi Hendrix", durMs = 200_000)
        val c = cand(name = "Crosstown Traffic", artists = listOf("Jimi Hendrix"), durMs = 200_000)

        assertThat(scorer.pick(t, listOf(c)).accepted).isNull()
    }

    @Test
    fun `rejects extended mix`() {
        val t = track(title = "Song", artist = "Artist", durMs = 200_000)
        // Parenthetical + same duration so the raw-title veto does the rejecting.
        val c = cand(name = "Song (Extended Mix)", artists = listOf("Artist"), durMs = 200_000)

        assertThat(scorer.pick(t, listOf(c)).accepted).isNull()
    }

    @Test
    fun `accepts multi-artist in different order`() {
        val t = track(title = "Song", artist = "Alpha, Bravo", durMs = 200_000)
        val c = cand(name = "Song", artists = listOf("Bravo", "Alpha"), durMs = 200_000)

        assertThat(scorer.pick(t, listOf(c)).accepted).isEqualTo(c)
    }

    @Test
    fun `accepts feat in title validated via artists array`() {
        // canonicalTitle strips "(feat. Xenon)"; Xenon is validated via artists[].
        val t = track(title = "Song (feat. Xenon)", artist = "Primary", durMs = 200_000)
        val c = cand(name = "Song", artists = listOf("Primary", "Xenon"), durMs = 200_000)

        assertThat(scorer.pick(t, listOf(c)).accepted).isEqualTo(c)
    }

    @Test
    fun `rejects when our duration unknown`() {
        val t = track(title = "Song", artist = "Artist", durMs = null)
        val c = cand(name = "Song", artists = listOf("Artist"), durMs = 200_000)

        assertThat(scorer.pick(t, listOf(c)).accepted).isNull()
    }

    @Test
    fun `accepts when version token present in both`() {
        // Symmetric: both titles have "live" so there is no conflict.
        val t = track(title = "Song (Live)", artist = "Artist", durMs = 200_000)
        val c = cand(name = "Song (Live)", artists = listOf("Artist"), durMs = 200_000)

        assertThat(scorer.pick(t, listOf(c)).accepted).isEqualTo(c)
    }

    @Test
    fun `abstains when near-tied passers are different recordings`() {
        // Same title, near-identical duration, but DIFFERENT artist line-ups —
        // could be an alt take / collab version. Genuinely ambiguous.
        val t = track(title = "Song", artist = "Artist", durMs = 200_000)
        val a = cand(name = "Song", artists = listOf("Artist"), durMs = 200_000, id = "a")
        val b = cand(name = "Song", artists = listOf("Artist", "Guest"), durMs = 201_000, id = "b")

        val decision = scorer.pick(t, listOf(a, b))

        assertThat(decision.accepted).isNull()
        assertThat(decision.reason).isEqualTo("ambiguous")
    }

    @Test
    fun `accepts identical recording duplicated across compilations`() {
        // Device-confirmed 2026-06-10 ("Stairway to Heaven - Remaster" on
        // Led Zeppelin IV Deluxe AND Remaster editions): the same master
        // licensed onto multiple albums is ONE recording — duplicates must
        // not trigger the ambiguity abstain.
        val t = track(title = "Song", artist = "Artist", durMs = 200_000)
        val a = cand(name = "Song", artists = listOf("Artist"), durMs = 200_000, id = "a")
        val b = cand(name = "Song", artists = listOf("Artist"), durMs = 200_000, id = "b")

        val decision = scorer.pick(t, listOf(a, b))

        assertThat(decision.accepted).isNotNull()
        assertThat(decision.reason).isEqualTo("accepted")
    }

    @Test
    fun `accepts same master with sub-second drift across editions`() {
        // Device-confirmed ("That's All I Ask" x3 albums, 80ms apart): tiny
        // edition-to-edition duration drift is still the same recording.
        val t = track(title = "Song", artist = "Artist", durMs = 149_000)
        val a = cand(name = "Song", artists = listOf("Artist"), durMs = 147_906, id = "a")
        val b = cand(name = "Song", artists = listOf("Artist"), durMs = 147_986, id = "b")
        val c = cand(name = "Song", artists = listOf("Artist"), durMs = 147_906, id = "c")

        val decision = scorer.pick(t, listOf(a, b, c))

        assertThat(decision.accepted).isNotNull()
    }

    @Test
    fun `accepts video-padded duration delta up to 10s`() {
        // Device-confirmed ("Hurricane (Official Video)" 190s vs album 181.2s):
        // music-video rips pad intro/outro around the same audio.
        val t = track(title = "Song (Official Video)", artist = "Artist", durMs = 190_000)
        val c = cand(name = "Song", artists = listOf("Artist"), durMs = 181_153)

        assertThat(scorer.pick(t, listOf(c)).accepted).isEqualTo(c)
    }

    @Test
    fun `rejects duration delta over 10s`() {
        val t = track(title = "Song", artist = "Artist", durMs = 200_000)
        val c = cand(name = "Song", artists = listOf("Artist"), durMs = 188_999)

        assertThat(scorer.pick(t, listOf(c)).accepted).isNull()
    }
}
