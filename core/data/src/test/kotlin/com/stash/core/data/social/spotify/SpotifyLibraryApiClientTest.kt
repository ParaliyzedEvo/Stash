package com.stash.core.data.social.spotify

import com.stash.data.spotify.SpotifyApiClient
import com.stash.data.spotify.SpotifyLibraryWriteResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * v0.9.73: SpotifyLibraryApiClient now delegates to SpotifyApiClient's
 * GraphQL library mutations (the deprecated + throttled REST endpoint was
 * the reason likes never synced). These tests pin the delegation and the
 * result→exception mapping the LikeCoordinator's pacing depends on.
 */
class SpotifyLibraryApiClientTest {

    private val api = mockk<SpotifyApiClient>()
    private fun client() = SpotifyLibraryApiClient(api)

    @Test fun `saveTracks calls addToLibrary with full uris`() = runBlocking {
        coEvery { api.addToLibrary(any()) } returns SpotifyLibraryWriteResult.Success

        client().saveTracks(listOf("spotify:track:abc123", "spotify:track:def456"))

        coVerify { api.addToLibrary(listOf("spotify:track:abc123", "spotify:track:def456")) }
    }

    @Test fun `removeTracks calls removeFromLibrary`() = runBlocking {
        coEvery { api.removeFromLibrary(any()) } returns SpotifyLibraryWriteResult.Success

        client().removeTracks(listOf("spotify:track:abc123"))

        coVerify { api.removeFromLibrary(listOf("spotify:track:abc123")) }
    }

    @Test fun `RateLimited maps to SpotifyRateLimitException with Retry-After`() = runBlocking {
        coEvery { api.addToLibrary(any()) } returns SpotifyLibraryWriteResult.RateLimited(30)

        try {
            client().saveTracks(listOf("spotify:track:abc"))
            fail("expected SpotifyRateLimitException")
        } catch (e: SpotifyRateLimitException) {
            assertEquals(30, e.retryAfterSeconds)
        }
    }

    @Test fun `AuthFailed maps to SpotifyAuthException`() = runBlocking {
        coEvery { api.addToLibrary(any()) } returns SpotifyLibraryWriteResult.AuthFailed

        try {
            client().saveTracks(listOf("spotify:track:abc"))
            fail("expected SpotifyAuthException")
        } catch (e: SpotifyAuthException) {
            // expected
        }
    }

    @Test fun `Failed maps to SpotifyApiException`() = runBlocking {
        coEvery { api.addToLibrary(any()) } returns SpotifyLibraryWriteResult.Failed("boom")

        try {
            client().saveTracks(listOf("spotify:track:abc"))
            fail("expected SpotifyApiException")
        } catch (e: SpotifyApiException) {
            assertTrue(e.message!!.contains("boom"))
        }
    }

    @Test fun `Success does not throw`() = runBlocking {
        coEvery { api.addToLibrary(any()) } returns SpotifyLibraryWriteResult.Success
        client().saveTracks(listOf("spotify:track:abc")) // no throw
    }

    @Test fun `rejects more than 50 uris`() = runBlocking {
        try {
            client().saveTracks(List(51) { "spotify:track:id$it" })
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("50"))
        }
    }
}
