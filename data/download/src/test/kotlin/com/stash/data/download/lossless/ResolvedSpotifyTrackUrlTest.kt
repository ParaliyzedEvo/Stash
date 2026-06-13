package com.stash.data.download.lossless

import com.google.common.truth.Truth.assertThat
import com.stash.data.download.lossless.spotifyresolve.SpotifyUriResolver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Tests for [TrackQuery.resolvedSpotifyTrackUrl] — the suspend extension the
 * two antra call sites use to obtain a Spotify track URL: the existing one
 * derived from [TrackQuery.spotifyUri] when present, otherwise a resolver
 * lookup keyed by [TrackQuery.trackId]. The resolver is only consulted when
 * there is no usable existing URL AND a trackId to key the resolution on.
 */
class ResolvedSpotifyTrackUrlTest {

    private val resolver = mockk<SpotifyUriResolver>()

    @Test fun `returns existing url when spotifyUri present, never calls resolver`() = runTest {
        val q = TrackQuery(artist = "A", title = "B", spotifyUri = "spotify:track:xyz", trackId = 42L)

        assertThat(q.resolvedSpotifyTrackUrl(resolver))
            .isEqualTo("https://open.spotify.com/track/xyz")
        coVerify(exactly = 0) { resolver.resolveUrl(any(), any()) }
    }

    @Test fun `returns resolver url when spotifyUri null and trackId present`() = runTest {
        val q = TrackQuery(artist = "A", title = "B", spotifyUri = null, trackId = 42L)
        coEvery { resolver.resolveUrl(42L, any()) } returns "https://open.spotify.com/track/x"

        assertThat(q.resolvedSpotifyTrackUrl(resolver))
            .isEqualTo("https://open.spotify.com/track/x")
    }

    @Test fun `returns null when spotifyUri null and trackId null, never calls resolver`() = runTest {
        val q = TrackQuery(artist = "A", title = "B", spotifyUri = null, trackId = null)

        assertThat(q.resolvedSpotifyTrackUrl(resolver)).isNull()
        coVerify(exactly = 0) { resolver.resolveUrl(any(), any()) }
    }
}
