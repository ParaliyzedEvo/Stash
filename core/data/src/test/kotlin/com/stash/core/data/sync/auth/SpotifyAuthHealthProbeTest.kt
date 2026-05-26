package com.stash.core.data.sync.auth

import com.stash.data.spotify.SpotifyApiClient
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Test

class SpotifyAuthHealthProbeTest {

    private val api: SpotifyApiClient = mockk()
    private val probe = SpotifyAuthHealthProbe(api)

    @Test fun `always reports healthy until proper HTTP probe lands`() = runTest {
        assertFalse(probe.isExpired())
    }
}
