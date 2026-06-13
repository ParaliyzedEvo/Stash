package com.stash.data.download.lossless

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for [TrackQuery.spotifyUri] threading and the
 * [TrackQuery.spotifyTrackUrl] derivation antra's `/api/resolve` consumes.
 *
 * antra resolves an `https://open.spotify.com/track/<id>` URL. Spotify URIs
 * arrive in a few shapes (the `spotify:track:<id>` URI, a bare id, or the
 * already-open URL), so the extension normalises all three and returns null
 * for anything that isn't a *track* (so albums/playlists/episodes don't get
 * mis-routed to antra).
 */
class TrackQuerySpotifyUriTest {

    @Test fun `converts spotify track uri to open url`() {
        val q = TrackQuery(artist = "A", title = "B", spotifyUri = "spotify:track:xyz")
        assertThat(q.spotifyTrackUrl()).isEqualTo("https://open.spotify.com/track/xyz")
    }

    @Test fun `converts bare track id to open url`() {
        val q = TrackQuery(artist = "A", title = "B", spotifyUri = "xyz123")
        assertThat(q.spotifyTrackUrl()).isEqualTo("https://open.spotify.com/track/xyz123")
    }

    @Test fun `passes an open spotify url through`() {
        val url = "https://open.spotify.com/track/abc?si=foo"
        val q = TrackQuery(artist = "A", title = "B", spotifyUri = url)
        assertThat(q.spotifyTrackUrl()).isEqualTo("https://open.spotify.com/track/abc")
    }

    @Test fun `null when spotifyUri absent`() {
        assertThat(TrackQuery(artist = "A", title = "B").spotifyTrackUrl()).isNull()
    }

    @Test fun `null when spotifyUri is blank`() {
        assertThat(TrackQuery(artist = "A", title = "B", spotifyUri = "   ").spotifyTrackUrl()).isNull()
    }

    @Test fun `null for non-track spotify uri`() {
        val q = TrackQuery(artist = "A", title = "B", spotifyUri = "spotify:album:xyz")
        assertThat(q.spotifyTrackUrl()).isNull()
    }
}
