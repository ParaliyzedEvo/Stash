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

    @Test fun `hostile or sloppy links are bounded, normalised or rejected`() {
        val long = "x".repeat(5000)
        val t = (ShareLinks.parse("$base/t?t=$long&a=A") as ShareLinks.Parsed.Track).track
        assertThat(t.title).hasLength(500)
        assertThat(ShareLinks.parse("HTTPS://STASH-SHARE.RAWNALDCLARK.WORKERS.DEV/m/Kx7Qa2pL/")).isEqualTo(ShareLinks.Parsed.Mix("Kx7Qa2pL"))
        assertThat(ShareLinks.parse("stash:track?t=a&a=b")).isNull()
        assertThat(ShareLinks.parse("$base/t?t=+&a=A")).isNull()
        val round = SharedTrack("C++ é", "Artist", durationMs = null)
        assertThat(ShareLinks.parse(ShareLinks.trackUrl(round))).isEqualTo(ShareLinks.Parsed.Track(round))
        assertThat((ShareLinks.parse("$base/t?t=T&a=A&d=-5") as ShareLinks.Parsed.Track).track.durationMs).isNull()
    }

    @Test fun `cover allowlist matches hosts exactly or as a subdomain, https only`() {
        assertThat(ShareConfig.isAllowedCover("https://i.scdn.co/image/a")).isTrue()
        assertThat(ShareConfig.isAllowedCover("https://x.i.ytimg.com/vi/b.jpg")).isTrue()
        assertThat(ShareConfig.isAllowedCover("https://evil.example/a.jpg")).isFalse()
        assertThat(ShareConfig.isAllowedCover("https://i.scdn.co.evil.example/a")).isFalse()
        assertThat(ShareConfig.isAllowedCover("http://i.scdn.co/a")).isFalse()
        assertThat(ShareConfig.isAllowedCover("not a url")).isFalse()
        assertThat(ShareConfig.isAllowedCover(null)).isFalse()
    }
}
