package com.stash.core.data.sync.auth

import com.stash.core.model.SyncResult
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.PagedPlaylists
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YoutubeAuthHealthProbeTest {

    private val api: YTMusicApiClient = mockk()
    private val probe = YoutubeAuthHealthProbe(api)

    @Test fun `returns false when getUserPlaylists returns Empty (authed-but-empty library is not expiry)`() = runTest {
        // Regression: an authenticated account with zero YouTube Music
        // playlists also returns Empty. Treating Empty as "expired" used to
        // short-circuit the whole sync (Spotify included) and spam a bogus
        // "re-authenticate YouTube" prompt. Empty must NOT mean expired.
        coEvery { api.getUserPlaylists() } returns SyncResult.Empty("Library returned no playlists")
        assertFalse(probe.isExpired())
    }

    @Test fun `returns false when getUserPlaylists returns Success`() = runTest {
        coEvery { api.getUserPlaylists() } returns SyncResult.Success(
            PagedPlaylists(playlists = emptyList(), partial = false, partialReason = null)
        )
        assertFalse(probe.isExpired())
    }

    @Test fun `returns false when getUserPlaylists returns Error (conservative)`() = runTest {
        coEvery { api.getUserPlaylists() } returns SyncResult.Error("network down")
        assertFalse(probe.isExpired())
    }

    @Test fun `propagates CancellationException for structured concurrency`() = runTest {
        coEvery { api.getUserPlaylists() } throws kotlinx.coroutines.CancellationException("cancelled")
        val thrown = runCatching { probe.isExpired() }.exceptionOrNull()
        assertTrue(thrown is kotlinx.coroutines.CancellationException)
    }
}
