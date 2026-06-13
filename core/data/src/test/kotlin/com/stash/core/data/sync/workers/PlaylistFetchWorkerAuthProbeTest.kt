package com.stash.core.data.sync.workers

import com.stash.core.data.sync.AuthExpiryState
import com.stash.core.data.sync.auth.SpotifyAuthHealthProbe
import com.stash.core.data.sync.auth.YoutubeAuthHealthProbe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [runAuthProbes] — the helper extracted from
 * [PlaylistFetchWorker.doWork] that fans out [SpotifyAuthHealthProbe] +
 * [YoutubeAuthHealthProbe] in parallel and decides which sources are
 * fetchable (and, derived from that, whether to short-circuit the chain).
 *
 * The helper is tested directly (rather than the whole HiltWorker) because
 * spinning up a WorkerParameters/Configuration test harness for an
 * @AssistedInject worker is heavyweight. The `fetchSpotify`/`fetchYoutube`
 * flags asserted here ARE the booleans `doWork` uses to gate each fetch, so
 * these tests also pin the per-source skip behaviour — a regression that
 * dropped one of the `&&` guards would fail here. The probes themselves are
 * tested in [SpotifyAuthHealthProbeTest] and [YoutubeAuthHealthProbeTest].
 */
class PlaylistFetchWorkerAuthProbeTest {

    @Test fun `one expired source does not short-circuit when the other is usable - spotify expired`() = runTest {
        // Per-source gating: Spotify expiring must not abort the sync when
        // YouTube is still healthy. Spotify is skipped, YouTube still fetches.
        val (spotify, youtube) = probes(spotifyExpired = true, youtubeExpired = false)
        val result = runAuthProbes(
            spotifyConnected = true, youtubeConnected = true,
            spotifyProbe = spotify, youtubeProbe = youtube,
        )
        assertFalse(result.shortCircuit)
        assertFalse(result.fetchSpotify)
        assertTrue(result.fetchYoutube)
        assertEquals(AuthExpiryState(spotifyExpired = true, youtubeExpired = false), result.state)
    }

    @Test fun `one expired source does not short-circuit when the other is usable - youtube expired`() = runTest {
        // The exact user-reported bug: a YouTube false-positive must never kill
        // a working Spotify sync. Spotify still fetches; YouTube is skipped.
        val (spotify, youtube) = probes(spotifyExpired = false, youtubeExpired = true)
        val result = runAuthProbes(
            spotifyConnected = true, youtubeConnected = true,
            spotifyProbe = spotify, youtubeProbe = youtube,
        )
        assertFalse(result.shortCircuit)
        assertTrue(result.fetchSpotify)
        assertFalse(result.fetchYoutube)
        assertEquals(AuthExpiryState(spotifyExpired = false, youtubeExpired = true), result.state)
    }

    @Test fun `short-circuits only when ALL connected sources are expired`() = runTest {
        // Nothing left to sync — abort with the auth-expired failure.
        val (spotify, youtube) = probes(spotifyExpired = true, youtubeExpired = true)
        val result = runAuthProbes(
            spotifyConnected = true, youtubeConnected = true,
            spotifyProbe = spotify, youtubeProbe = youtube,
        )
        assertTrue(result.shortCircuit)
        assertFalse(result.fetchSpotify)
        assertFalse(result.fetchYoutube)
        assertEquals(AuthExpiryState(spotifyExpired = true, youtubeExpired = true), result.state)
    }

    @Test fun `proceeds and fetches both when both probes report healthy`() = runTest {
        val (spotify, youtube) = probes(spotifyExpired = false, youtubeExpired = false)
        val result = runAuthProbes(
            spotifyConnected = true, youtubeConnected = true,
            spotifyProbe = spotify, youtubeProbe = youtube,
        )
        assertFalse(result.shortCircuit)
        assertTrue(result.fetchSpotify)
        assertTrue(result.fetchYoutube)
        assertEquals(AuthExpiryState(spotifyExpired = false, youtubeExpired = false), result.state)
    }

    @Test fun `does not probe spotify when not connected`() = runTest {
        // Spotify is NOT connected. Even if the probe would (counterfactually)
        // report expired, we must skip it — otherwise a user who never linked
        // Spotify would see a "Spotify expired" banner. coVerify(exactly = 0)
        // confirms the mechanism, not just the outcome (a future refactor that
        // calls the probe and defaults to false when disconnected would
        // otherwise silently pass).
        val (spotify, youtube) = probes(
            spotifyExpired = true, // would-say-expired, but probe should be skipped
            youtubeExpired = false,
        )
        val result = runAuthProbes(
            spotifyConnected = false, youtubeConnected = true,
            spotifyProbe = spotify, youtubeProbe = youtube,
        )
        assertFalse(result.shortCircuit)
        assertFalse(result.state.spotifyExpired) // false because probe was skipped
        assertFalse(result.fetchSpotify)         // not connected → not fetched
        assertTrue(result.fetchYoutube)
        coVerify(exactly = 0) { spotify.isExpired() }
        coVerify(exactly = 1) { youtube.isExpired() }
    }

    @Test fun `does not probe youtube when not connected`() = runTest {
        val (spotify, youtube) = probes(
            spotifyExpired = false,
            youtubeExpired = true, // would-say-expired, but probe should be skipped
        )
        val result = runAuthProbes(
            spotifyConnected = true, youtubeConnected = false,
            spotifyProbe = spotify, youtubeProbe = youtube,
        )
        assertFalse(result.shortCircuit)
        assertFalse(result.state.youtubeExpired) // false because probe was skipped
        assertTrue(result.fetchSpotify)
        assertFalse(result.fetchYoutube)          // not connected → not fetched
        coVerify(exactly = 1) { spotify.isExpired() }
        coVerify(exactly = 0) { youtube.isExpired() }
    }

    @Test fun `short-circuits when only youtube is connected and youtube is expired`() = runTest {
        // User only linked YouTube and its credentials are bad — the chain
        // must abort even though Spotify is absent (no usable source at all).
        val (spotify, youtube) = probes(spotifyExpired = false, youtubeExpired = true)
        val result = runAuthProbes(
            spotifyConnected = false, youtubeConnected = true,
            spotifyProbe = spotify, youtubeProbe = youtube,
        )
        assertTrue(result.shortCircuit)
        assertFalse(result.fetchSpotify)
        assertFalse(result.fetchYoutube)
        assertTrue(result.state.youtubeExpired)
        coVerify(exactly = 0) { spotify.isExpired() }
        coVerify(exactly = 1) { youtube.isExpired() }
    }

    private fun probes(
        spotifyExpired: Boolean,
        youtubeExpired: Boolean,
    ): Pair<SpotifyAuthHealthProbe, YoutubeAuthHealthProbe> {
        val spotify = mockk<SpotifyAuthHealthProbe>()
        val youtube = mockk<YoutubeAuthHealthProbe>()
        coEvery { spotify.isExpired() } returns spotifyExpired
        coEvery { youtube.isExpired() } returns youtubeExpired
        return spotify to youtube
    }
}
