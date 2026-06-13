package com.stash.data.download.lossless.spotifyresolve

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.SpotifyResolutionDao
import com.stash.core.data.db.entity.SpotifyResolutionEntity
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.matching.SpotifySearchScorer
import com.stash.data.spotify.SpotifyApiClient
import com.stash.data.spotify.SpotifyRateLimitException
import com.stash.data.spotify.SpotifyTrackCandidate
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Unit tests for [SpotifyUriResolver] — the cache-first orchestrator that ties
 * the Spotify search/score/cache pieces together. Collaborators are mocked; a
 * fixed clock is injected via [SpotifyUriResolver.nowMs].
 *
 * Invariants under test: a cache HIT never calls the API; TTL gates suppress
 * API calls within their window; outcomes are cached with the right status and
 * TTL; transient failures (429 / IO) never write NO_MATCH (except the
 * attempts>5 promotion); unknown-duration short-circuits with no work; and two
 * concurrent resolves of one uncached track issue exactly ONE search.
 */
class SpotifyUriResolverTest {

    private val spotify: SpotifyApiClient = mockk()
    private val scorer: SpotifySearchScorer = mockk()
    private val dao: SpotifyResolutionDao = mockk(relaxed = true)

    private val now = 1_000_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun resolver() = SpotifyUriResolver(spotify, scorer, dao).apply {
        nowMs = { now }
    }

    private fun track() = TrackQuery(
        artist = "Curtis Mayfield",
        title = "Pusherman",
        durationMs = 297_000L,
    )

    private fun candidate(id: String = "abc") = SpotifyTrackCandidate(
        id = id,
        name = "Pusherman",
        artists = listOf("Curtis Mayfield"),
        albumName = "Super Fly",
        durationMs = 297_000L,
        isrc = "USXYZ1234567",
        explicit = false,
    )

    @Test fun `cache MATCHED returns url without searching`() = runTest {
        coEvery { dao.get(42L) } returns SpotifyResolutionEntity(
            trackId = 42L,
            status = "MATCHED",
            spotifyUri = "spotify:track:abc",
            matchedIsrc = null,
            titleSim = null,
            durDeltaSec = null,
            resolvedAtMs = now,
            expiresAtMs = Long.MAX_VALUE,
        )

        val url = resolver().resolveUrl(42L, track())

        assertThat(url).isEqualTo("https://open.spotify.com/track/abc")
        coVerify(exactly = 0) { spotify.searchTracksGraphQL(any(), any()) }
    }

    @Test fun `cache NO_MATCH within TTL returns null without searching`() = runTest {
        coEvery { dao.get(42L) } returns SpotifyResolutionEntity(
            trackId = 42L,
            status = "NO_MATCH",
            spotifyUri = null,
            matchedIsrc = null,
            titleSim = null,
            durDeltaSec = null,
            resolvedAtMs = now,
            expiresAtMs = now + 5 * day,
        )

        val url = resolver().resolveUrl(42L, track())

        assertThat(url).isNull()
        coVerify(exactly = 0) { spotify.searchTracksGraphQL(any(), any()) }
    }

    @Test fun `cache TRANSIENT within TTL returns null without searching`() = runTest {
        coEvery { dao.get(42L) } returns SpotifyResolutionEntity(
            trackId = 42L,
            status = "TRANSIENT",
            spotifyUri = null,
            matchedIsrc = null,
            titleSim = null,
            durDeltaSec = null,
            resolvedAtMs = now,
            expiresAtMs = now + 16 * 60 * 1000,
            attempts = 2,
        )

        val url = resolver().resolveUrl(42L, track())

        assertThat(url).isNull()
        coVerify(exactly = 0) { spotify.searchTracksGraphQL(any(), any()) }
    }

    @Test fun `miss then search then scorer accepts then upserts MATCHED then returns url`() = runTest {
        coEvery { dao.get(42L) } returns null
        coEvery { spotify.searchTracksGraphQL(any(), any()) } returns listOf(candidate("xyz"))
        coEvery { scorer.pick(any(), any()) } returns
            SpotifySearchScorer.Decision(candidate("xyz"), "accepted")
        val slot = slot<SpotifyResolutionEntity>()
        coEvery { dao.upsert(capture(slot)) } just Runs

        val url = resolver().resolveUrl(42L, track())

        assertThat(url).isEqualTo("https://open.spotify.com/track/xyz")
        assertThat(slot.captured.status).isEqualTo("MATCHED")
        assertThat(slot.captured.spotifyUri).isEqualTo("spotify:track:xyz")
        assertThat(slot.captured.expiresAtMs).isEqualTo(Long.MAX_VALUE)
    }

    @Test fun `miss then search then scorer rejects then upserts NO_MATCH 30d then null`() = runTest {
        coEvery { dao.get(42L) } returns null
        coEvery { spotify.searchTracksGraphQL(any(), any()) } returns listOf(candidate())
        coEvery { scorer.pick(any(), any()) } returns
            SpotifySearchScorer.Decision(null, "no candidate passed gates")
        val slot = slot<SpotifyResolutionEntity>()
        coEvery { dao.upsert(capture(slot)) } just Runs

        val url = resolver().resolveUrl(42L, track())

        assertThat(url).isNull()
        assertThat(slot.captured.status).isEqualTo("NO_MATCH")
        assertThat(slot.captured.spotifyUri).isNull()
        assertThat(slot.captured.expiresAtMs).isEqualTo(now + 30 * day)
    }

    @Test fun `429 upserts TRANSIENT not NO_MATCH then null`() = runTest {
        coEvery { dao.get(42L) } returns null
        coEvery { spotify.searchTracksGraphQL(any(), any()) } throws
            SpotifyRateLimitException(retryAfterSeconds = null)
        val slot = slot<SpotifyResolutionEntity>()
        coEvery { dao.upsert(capture(slot)) } just Runs

        val url = resolver().resolveUrl(42L, track())

        assertThat(url).isNull()
        assertThat(slot.captured.status).isEqualTo("TRANSIENT")
    }

    @Test fun `unknown duration returns null with no search and no upsert`() = runTest {
        coEvery { dao.get(any()) } returns null

        val url = resolver().resolveUrl(42L, track().copy(durationMs = null))

        assertThat(url).isNull()
        coVerify(exactly = 0) { spotify.searchTracksGraphQL(any(), any()) }
        coVerify(exactly = 0) { dao.upsert(any()) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `concurrent resolves of same track issue one search`() = runTest {
        coEvery { dao.get(42L) } returns null
        val release = CompletableDeferred<Unit>()
        coEvery { spotify.searchTracksGraphQL(any(), any()) } coAnswers {
            release.await()
            listOf(candidate("xyz"))
        }
        coEvery { scorer.pick(any(), any()) } returns
            SpotifySearchScorer.Decision(candidate("xyz"), "accepted")
        coEvery { dao.upsert(any()) } just Runs

        val r = resolver()
        val a = async { r.resolveUrl(42L, track()) }
        val b = async { r.resolveUrl(42L, track()) }
        // Run both up to their suspension points (the gated search / the
        // in-flight join) without advancing the clock, then release.
        runCurrent()
        release.complete(Unit)
        val ra = a.await()
        val rb = b.await()

        assertThat(ra).isEqualTo("https://open.spotify.com/track/xyz")
        assertThat(rb).isEqualTo("https://open.spotify.com/track/xyz")
        coVerify(exactly = 1) { spotify.searchTracksGraphQL(any(), any()) }
    }

    @Test fun `TRANSIENT promoted to NO_MATCH after attempts over 5`() = runTest {
        coEvery { dao.get(42L) } returns SpotifyResolutionEntity(
            trackId = 42L,
            status = "TRANSIENT",
            spotifyUri = null,
            matchedIsrc = null,
            titleSim = null,
            durDeltaSec = null,
            resolvedAtMs = now - 60_000,
            expiresAtMs = now - 1, // expired -> re-search
            attempts = 5,
        )
        coEvery { spotify.searchTracksGraphQL(any(), any()) } throws
            SpotifyRateLimitException(retryAfterSeconds = null)
        val slot = slot<SpotifyResolutionEntity>()
        coEvery { dao.upsert(capture(slot)) } just Runs

        val url = resolver().resolveUrl(42L, track())

        assertThat(url).isNull()
        assertThat(slot.captured.status).isEqualTo("NO_MATCH")
        assertThat(slot.captured.attempts).isEqualTo(6)
    }

    @Test fun `expired NO_MATCH re-searches`() = runTest {
        coEvery { dao.get(42L) } returns SpotifyResolutionEntity(
            trackId = 42L,
            status = "NO_MATCH",
            spotifyUri = null,
            matchedIsrc = null,
            titleSim = null,
            durDeltaSec = null,
            resolvedAtMs = now - 60 * day,
            expiresAtMs = now - 1, // expired
        )
        coEvery { spotify.searchTracksGraphQL(any(), any()) } returns listOf(candidate("xyz"))
        coEvery { scorer.pick(any(), any()) } returns
            SpotifySearchScorer.Decision(candidate("xyz"), "accepted")
        coEvery { dao.upsert(any()) } just Runs

        val url = resolver().resolveUrl(42L, track())

        assertThat(url).isEqualTo("https://open.spotify.com/track/xyz")
        coVerify(exactly = 1) { spotify.searchTracksGraphQL(any(), any()) }
    }
}
