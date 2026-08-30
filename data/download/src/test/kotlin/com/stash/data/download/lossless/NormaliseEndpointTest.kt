package com.stash.data.download.lossless

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NormaliseEndpointTest {
    private fun n(s: String?) = LosslessSourcePreferences.normaliseEndpoint(s)

    @Test fun `accepts https and strips query, fragment and trailing slashes`() {
        assertThat(n("  https://relay.example.org/  ")).isEqualTo("https://relay.example.org")
        assertThat(n("https://h/a?q=1#f")).isEqualTo("https://h/a")
        assertThat(n("https://h/api/")).isEqualTo("https://h/api")          // path prefix kept
        assertThat(n("https://h:8443")).isEqualTo("https://h:8443")
        assertThat(n("https://[::1]:8443/x")).isEqualTo("https://[::1]:8443/x")
    }

    @Test fun `lower-cases scheme and host`() {
        assertThat(n("HTTPS://Relay.Example.ORG/Path")).isEqualTo("https://relay.example.org/Path")
    }

    @Test fun `rejects non-https, malformed, blank and null`() {
        assertThat(n("http://insecure.example")).isNull()
        assertThat(n("https://my relay.org")).isNull()
        assertThat(n("https://..")).isNull()
        assertThat(n("https://")).isNull()
        assertThat(n("relay.example.org")).isNull()
        assertThat(n("   ")).isNull()
        assertThat(n(null)).isNull()
    }
}
