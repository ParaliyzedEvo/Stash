package com.stash.core.data.sync.auth

import com.stash.core.auth.TokenManager
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyAuthHealthProbeTest {

    private val tokenManager: TokenManager = mockk()
    private val probe = SpotifyAuthHealthProbe(tokenManager)

    @Test fun `returns true when getSpotifyAccessToken returns null`() = runTest {
        // null while the worker has already gated on Connected means the
        // sp_dc refresh failed — Spotify returned anonymous, cookie is bad.
        coEvery { tokenManager.getSpotifyAccessToken() } returns null
        assertTrue(probe.isExpired())
    }

    @Test fun `returns false when getSpotifyAccessToken returns a token`() = runTest {
        coEvery { tokenManager.getSpotifyAccessToken() } returns "BQA-real-looking-token"
        assertFalse(probe.isExpired())
    }

    @Test fun `returns false when network throws (conservative)`() = runTest {
        coEvery { tokenManager.getSpotifyAccessToken() } throws RuntimeException("network down")
        assertFalse(probe.isExpired())
    }

    @Test fun `propagates CancellationException for structured concurrency`() = runTest {
        coEvery {
            tokenManager.getSpotifyAccessToken()
        } throws kotlinx.coroutines.CancellationException("cancelled")
        val thrown = runCatching { probe.isExpired() }.exceptionOrNull()
        assertTrue(thrown is kotlinx.coroutines.CancellationException)
    }
}
