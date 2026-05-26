package com.stash.core.data.sync.workers

import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthState
import com.stash.core.auth.model.UserInfo
import com.stash.core.data.sync.AuthExpiryState
import com.stash.core.data.sync.auth.SpotifyAuthHealthProbe
import com.stash.core.data.sync.auth.YoutubeAuthHealthProbe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [runAuthProbes] — the helper extracted from
 * [PlaylistFetchWorker.doWork] that fans out [SpotifyAuthHealthProbe] +
 * [YoutubeAuthHealthProbe] in parallel and decides whether to short-circuit
 * the sync chain when either source is expired.
 *
 * The helper is tested directly (rather than the whole HiltWorker) because
 * spinning up a WorkerParameters/Configuration test harness for an
 * @AssistedInject worker is heavyweight and the orchestration logic here is
 * the only thing worth verifying — the probes themselves are tested in
 * [SpotifyAuthHealthProbeTest] and [YoutubeAuthHealthProbeTest].
 */
class PlaylistFetchWorkerAuthProbeTest {

    @Test fun `short-circuits when spotify probe reports expired`() = runTest {
        val (tokenManager, spotify, youtube) = mocks(
            spotifyConnected = true, youtubeConnected = true,
            spotifyExpired = true, youtubeExpired = false,
        )
        val result = runAuthProbes(tokenManager, spotify, youtube)
        assertTrue(result.shortCircuit)
        assertEquals(AuthExpiryState(spotifyExpired = true, youtubeExpired = false), result.state)
    }

    @Test fun `short-circuits when youtube probe reports expired`() = runTest {
        val (tokenManager, spotify, youtube) = mocks(
            spotifyConnected = true, youtubeConnected = true,
            spotifyExpired = false, youtubeExpired = true,
        )
        val result = runAuthProbes(tokenManager, spotify, youtube)
        assertTrue(result.shortCircuit)
        assertEquals(AuthExpiryState(spotifyExpired = false, youtubeExpired = true), result.state)
    }

    @Test fun `proceeds when both probes report healthy`() = runTest {
        val (tokenManager, spotify, youtube) = mocks(
            spotifyConnected = true, youtubeConnected = true,
            spotifyExpired = false, youtubeExpired = false,
        )
        val result = runAuthProbes(tokenManager, spotify, youtube)
        assertFalse(result.shortCircuit)
        assertEquals(AuthExpiryState(spotifyExpired = false, youtubeExpired = false), result.state)
    }

    @Test fun `does not probe spotify when not connected`() = runTest {
        // Spotify is NOT connected. Even if the probe would (counterfactually)
        // report expired, we must skip it — otherwise a user who never linked
        // Spotify would see a "Spotify expired" banner. The probe should never
        // be called; coVerify(exactly = 0) below confirms the mechanism, not
        // just the outcome (a future refactor that calls the probe and
        // defaults to false when disconnected would otherwise silently pass).
        val (tokenManager, spotify, youtube) = mocks(
            spotifyConnected = false, youtubeConnected = true,
            spotifyExpired = true, // would-say-expired, but probe should be skipped
            youtubeExpired = false,
        )
        val result = runAuthProbes(tokenManager, spotify, youtube)
        assertFalse(result.shortCircuit)
        assertFalse(result.state.spotifyExpired) // false because probe was skipped
        assertFalse(result.state.youtubeExpired)
        coVerify(exactly = 0) { spotify.isExpired() }
        coVerify(exactly = 1) { youtube.isExpired() }
    }

    @Test fun `does not probe youtube when not connected`() = runTest {
        val (tokenManager, spotify, youtube) = mocks(
            spotifyConnected = true, youtubeConnected = false,
            spotifyExpired = false,
            youtubeExpired = true, // would-say-expired, but probe should be skipped
        )
        val result = runAuthProbes(tokenManager, spotify, youtube)
        assertFalse(result.shortCircuit)
        assertFalse(result.state.spotifyExpired)
        assertFalse(result.state.youtubeExpired) // false because probe was skipped
        coVerify(exactly = 1) { spotify.isExpired() }
        coVerify(exactly = 0) { youtube.isExpired() }
    }

    @Test fun `short-circuits when only youtube is connected and youtube is expired`() = runTest {
        // User only linked YouTube and its credentials are bad — the chain
        // must still abort even though Spotify is absent. Guards against a
        // regression where the short-circuit logic only fires when spotify
        // is also present.
        val (tokenManager, spotify, youtube) = mocks(
            spotifyConnected = false, youtubeConnected = true,
            spotifyExpired = false, youtubeExpired = true,
        )
        val result = runAuthProbes(tokenManager, spotify, youtube)
        assertTrue(result.shortCircuit)
        assertFalse(result.state.spotifyExpired)
        assertTrue(result.state.youtubeExpired)
        coVerify(exactly = 0) { spotify.isExpired() }
        coVerify(exactly = 1) { youtube.isExpired() }
    }

    private fun mocks(
        spotifyConnected: Boolean,
        youtubeConnected: Boolean,
        spotifyExpired: Boolean,
        youtubeExpired: Boolean,
    ): Triple<TokenManager, SpotifyAuthHealthProbe, YoutubeAuthHealthProbe> {
        val tokenManager = mockk<TokenManager>()
        val spotify = mockk<SpotifyAuthHealthProbe>()
        val youtube = mockk<YoutubeAuthHealthProbe>()

        val spotifyState: AuthState =
            if (spotifyConnected) AuthState.Connected(UserInfo(id = "u", displayName = "u"))
            else AuthState.NotConnected
        val youtubeState: AuthState =
            if (youtubeConnected) AuthState.Connected(UserInfo(id = "u", displayName = "u"))
            else AuthState.NotConnected

        every { tokenManager.spotifyAuthState } returns MutableStateFlow(spotifyState)
        every { tokenManager.youTubeAuthState } returns MutableStateFlow(youtubeState)
        coEvery { spotify.isExpired() } returns spotifyExpired
        coEvery { youtube.isExpired() } returns youtubeExpired
        return Triple(tokenManager, spotify, youtube)
    }
}
