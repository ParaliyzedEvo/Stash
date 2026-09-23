package com.stash.core.model.share

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShareLinksTest {
    private val base = ShareConfig.BASE_URL

    @Test fun `mix url and parse round-trip`() {
        assertThat(ShareLinks.mixUrl("Kx7Qa2pL")).isEqualTo("$base/m/Kx7Qa2pL")
        assertThat(ShareLinks.parse("$base/m/Kx7Qa2pL")).isEqualTo(ShareLinks.Parsed.Mix("Kx7Qa2pL"))
    }

    @Test fun `track url carries every field and parses back`() {
        val t = SharedTrack("Song & Dance", "Aphex Twin", "Drukqs", 125_000, "GBBPW0100025", "abc", "xyz")
        val url = ShareLinks.trackUrl(t)
        assertThat(url).startsWith("$base/t?t=Song+%26+Dance&a=Aphex+Twin")
        assertThat(ShareLinks.parse(url)).isEqualTo(ShareLinks.Parsed.Track(t))
    }

    @Test fun `legacy stash track links still parse`() {
        val legacy = "stash://track?t=Avril+14th&a=Aphex+Twin&s=https%3A%2F%2Fopen.spotify.com%2Ftrack%2Fabc&y=xyz"
        assertThat(ShareLinks.parse(legacy)).isEqualTo(
            ShareLinks.Parsed.Track(SharedTrack("Avril 14th", "Aphex Twin", spotifyId = "abc", youtubeId = "xyz")),
        )
    }

    @Test fun `foreign hosts, bad ids and missing fields are rejected`() {
        assertThat(ShareLinks.parse("https://evil.example/m/Kx7Qa2pL")).isNull()
        assertThat(ShareLinks.parse("$base/m/short")).isNull()
        assertThat(ShareLinks.parse("$base/t?a=OnlyArtist")).isNull()
        assertThat(ShareLinks.parse(null)).isNull()
        assertThat(ShareLinks.parse("not a url")).isNull()
        assertThat(ShareLinks.parse("$base/t?t=%zz&a=A")).isNull()
    }
}
