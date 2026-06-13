package com.stash.core.data.social.spotify

import com.stash.core.auth.TokenManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * v0.9.52: removeTracks is the DELETE mirror of saveTracks — same
 * endpoint, same auth/429/401 handling, used by the symmetric
 * un-like path. saveTracks behaviour is pinned too (it had no test).
 */
class SpotifyLibraryApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var tokenManager: TokenManager

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        tokenManager = mockk(relaxed = true)
        coEvery { tokenManager.getSpotifyAccessToken() } returns "tok"
    }

    @After fun tearDown() { server.shutdown() }

    private fun client() = SpotifyLibraryApiClient(
        tokenManager = tokenManager,
        httpClient = OkHttpClient(),
        baseUrl = server.url("/").toString().removeSuffix("/"),
    )

    @Test fun `removeTracks issues DELETE with bare ids and bearer token`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))

        client().removeTracks(listOf("spotify:track:abc123", "def456"))

        val recorded = server.takeRequest()
        assertEquals("DELETE", recorded.method)
        assertEquals("/v1/me/tracks?ids=abc123,def456", recorded.path)
        assertEquals("Bearer tok", recorded.getHeader("Authorization"))
    }

    @Test fun `saveTracks still issues PUT`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))

        client().saveTracks(listOf("spotify:track:abc123"))

        assertEquals("PUT", server.takeRequest().method)
    }

    @Test fun `removeTracks maps 429 to SpotifyRateLimitException with Retry-After`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30"))

        try {
            client().removeTracks(listOf("abc123"))
            fail("expected SpotifyRateLimitException")
        } catch (e: SpotifyRateLimitException) {
            assertEquals(30, e.retryAfterSeconds)
        }
    }

    @Test fun `removeTracks maps 401 to SpotifyAuthException and invalidates token`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))

        try {
            client().removeTracks(listOf("abc123"))
            fail("expected SpotifyAuthException")
        } catch (e: SpotifyAuthException) {
            coVerify { tokenManager.forceRefreshSpotifyAccessToken() }
        }
    }

    @Test fun `removeTracks rejects more than 50 ids`() = runBlocking {
        try {
            client().removeTracks(List(51) { "id$it" })
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("50"))
        }
    }
}
