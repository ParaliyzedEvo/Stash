package com.stash.data.download.lossless.antra

import com.google.common.truth.Truth.assertThat
import com.stash.data.download.lossless.AggregatorRateLimiter
import com.stash.data.download.lossless.RateLimitState
import com.stash.data.download.lossless.TrackQuery
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [AntraSource] — the download-side [LosslessSource].
 *
 * All collaborators ([AntraClient], [AntraCredentialStore],
 * [AggregatorRateLimiter]) are mocked so the test exercises the gating
 * (connected? spotifyUri? quota? job complete?) and the result shape in
 * isolation, with no network or DataStore.
 */
class AntraSourceTest {

    private val client: AntraClient = mockk()
    private val store: AntraCredentialStore = mockk()
    private val rateLimiter: AggregatorRateLimiter = mockk()

    private fun source() = AntraSource(client, store, rateLimiter)

    private val query = TrackQuery(
        artist = "Curtis Mayfield",
        title = "Pusherman",
        isrc = "USAB12345678",
        spotifyUri = "spotify:track:abc123",
    )
    private val expectedUrl = "https://open.spotify.com/track/abc123"

    @Before fun stubRateLimiter() {
        coEvery { rateLimiter.stateOf("antra") } returns NOT_BROKEN
        coEvery { rateLimiter.acquire("antra") } returns true
        coEvery { rateLimiter.reportSuccess("antra") } just Runs
        coEvery { rateLimiter.reportFailure("antra") } just Runs
        coEvery { rateLimiter.reportRateLimited("antra") } just Runs
    }

    private fun connectedHappyPath() {
        coEvery { store.isConnected() } returns true
        coEvery { store.cookieHeader() } returns "session=s; cf_clearance=c"
        coEvery { client.me() } returns AntraMe(username = "rawn", singles_left = 5)
        coEvery { client.resolve(expectedUrl) } returns
            AntraResolve(artist = "Curtis Mayfield", artwork_url = "https://img/art.jpg")
        coEvery { client.createJob(expectedUrl, 0, 1) } returns AntraJobCreated(job_id = "job-7")
        coEvery { client.pollStatus("job-7") } returns
            AntraJobStatus(job_id = "job-7", status = "complete", filename = "Pusherman.flac")
        coEvery { client.downloadUrl("job-7") } returns
            "https://antra.hoshi.cfd/api/jobs/job-7/download"
    }

    @Test fun `happy path returns a lossless SourceResult`() = runTest {
        connectedHappyPath()

        val result = source().resolve(query)

        assertThat(result).isNotNull()
        assertThat(result!!.sourceId).isEqualTo("antra")
        assertThat(result.downloadUrl).endsWith("/api/jobs/job-7/download")
        assertThat(result.downloadHeaders["Cookie"]).isEqualTo("session=s; cf_clearance=c")
        assertThat(result.format.bitsPerSample).isEqualTo(24)
        assertThat(result.format.isLossless).isTrue()
        assertThat(result.confidence).isEqualTo(0.95f) // isrc present
        assertThat(result.coverArtUrl).isEqualTo("https://img/art.jpg")
        coVerify { rateLimiter.reportSuccess("antra") }
    }

    @Test fun `confidence is lower without isrc`() = runTest {
        connectedHappyPath()

        val result = source().resolve(query.copy(isrc = null))

        assertThat(result!!.confidence).isEqualTo(0.85f)
    }

    @Test fun `returns null when not connected`() = runTest {
        coEvery { store.isConnected() } returns false

        assertThat(source().resolve(query)).isNull()
    }

    @Test fun `returns null when spotifyUri absent`() = runTest {
        coEvery { store.isConnected() } returns true

        assertThat(source().resolve(query.copy(spotifyUri = null))).isNull()
    }

    @Test fun `returns null when quota exhausted`() = runTest {
        coEvery { store.isConnected() } returns true
        coEvery { client.me() } returns AntraMe(username = "rawn", singles_left = 0)

        assertThat(source().resolve(query)).isNull()
    }

    @Test fun `returns null when job does not complete`() = runTest {
        connectedHappyPath()
        coEvery { client.pollStatus("job-7") } returns
            AntraJobStatus(job_id = "job-7", status = "failed")

        assertThat(source().resolve(query)).isNull()
        coVerify { rateLimiter.reportFailure("antra") }
    }

    @Test fun `marks credentials stale on cloudflare 403`() = runTest {
        coEvery { store.isConnected() } returns true
        coEvery { store.markStale() } just Runs
        coEvery { client.me() } throws AntraCloudflareException()

        assertThat(source().resolve(query)).isNull()
        coVerify { store.markStale() }
    }

    private companion object {
        val NOT_BROKEN = RateLimitState(
            tokensAvailable = 5.0,
            msUntilNextToken = 0,
            isCircuitBroken = false,
            msUntilUnblock = 0,
            recentFailures = 0,
        )
    }
}
