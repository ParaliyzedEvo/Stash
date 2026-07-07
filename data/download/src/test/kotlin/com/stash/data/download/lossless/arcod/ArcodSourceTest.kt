package com.stash.data.download.lossless.arcod

import com.google.common.truth.Truth.assertThat
import com.stash.data.download.lossless.AggregatorRateLimiter
import com.stash.data.download.lossless.LosslessQualityTier
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.RateLimitState
import com.stash.data.download.lossless.TrackQuery
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Unit tests for [ArcodSource] (the job-based ARCOD/arcod.xyz implementation).
 *
 * Mocks the [ArcodClient], [ArcodCredentialStore] and [AggregatorRateLimiter];
 * [ArcodMatcher] is a real object, so candidates are real [ArcodTrackItem]s
 * whose title/artist match the query so the matcher returns them (≥0.5).
 *
 * Verifies the LosslessSource contract independently of the network: the
 * enablement gates, the rate-limiter bookkeeping on each failure mode, the
 * 429 → reportRateLimited path, and that cancellation propagates rather than
 * being swallowed as a failure.
 */
class ArcodSourceTest {

    private val client: ArcodClient = mockk()
    private val credentialStore: ArcodCredentialStore = mockk()
    private val rateLimiter: AggregatorRateLimiter = mockk(relaxUnitFun = true)
    private val losslessPrefs: LosslessSourcePreferences = mockk()

    private fun source() =
        ArcodSource(client, credentialStore, rateLimiter, ArcodJobGate(), losslessPrefs)

    private fun query(
        artist: String = "Radiohead",
        title: String = "Karma Police",
        isrc: String? = null,
        durationMs: Long? = 261_000L,
    ) = TrackQuery(artist = artist, title = title, isrc = isrc, durationMs = durationMs)

    /** A real, matchable catalog item (title/artist agree with the default query). */
    private fun matchableItem(
        id: Long = 100L,
        title: String = "Karma Police",
        artist: String = "Radiohead",
        duration: Int? = 261,
        albumId: String? = "0093624804567",
        albumTitle: String = "OK Computer",
        artistId: Long? = 42L,
        coverLarge: String? = "https://arcod.xyz/cover/large.jpg",
        releaseDate: String? = "1997-05-21",
        tracksCount: Int? = 12,
    ) = ArcodTrackItem(
        id = id,
        title = title,
        isrc = null,
        duration = duration,
        maxBitDepth = 16,
        performer = ArcodNamed(name = artist, id = artistId),
        album = ArcodAlbum(
            id = albumId,
            title = albumTitle,
            artist = ArcodNamed(name = artist, id = artistId),
            image = ArcodImage(large = coverLarge),
            releaseDate = releaseDate,
            tracksCount = tracksCount,
        ),
    )

    private fun stubConnectedAndReady() {
        coEvery { credentialStore.isConnected() } returns true
        coEvery { rateLimiter.stateOf(ArcodSource.SOURCE_ID) } returns
            RateLimitState(2.0, 0L, isCircuitBroken = false, msUntilUnblock = 0L, recentFailures = 0)
        coEvery { rateLimiter.acquire(ArcodSource.SOURCE_ID) } returns true
        coEvery { losslessPrefs.qualityTierNow() } returns LosslessQualityTier.MAX
        // Default the fast single-GET path OFF (as if the stream base is
        // unconfigured) so the legacy job-render tests exercise the FALLBACK.
        // The single-GET fast-path tests stub a non-null stream explicitly.
        coEvery { client.streamUrl(any(), any()) } returns null
    }

    // ── enablement gates ─────────────────────────────────────────────────

    @Test fun `not connected returns null and never searches`() = runTest {
        coEvery { credentialStore.isConnected() } returns false
        coEvery { rateLimiter.stateOf(ArcodSource.SOURCE_ID) } returns
            RateLimitState(2.0, 0L, false, 0L, 0)

        assertThat(source().resolve(query())).isNull()
        coVerify { client wasNot Called }
    }

    @Test fun `circuit-broken returns null and never searches`() = runTest {
        coEvery { credentialStore.isConnected() } returns true
        coEvery { rateLimiter.stateOf(ArcodSource.SOURCE_ID) } returns
            RateLimitState(0.0, 0L, isCircuitBroken = true, msUntilUnblock = 60_000L, recentFailures = 5)

        assertThat(source().resolve(query())).isNull()
        coVerify { client wasNot Called }
    }

    @Test fun `acquire false returns null and never searches`() = runTest {
        coEvery { credentialStore.isConnected() } returns true
        coEvery { rateLimiter.stateOf(ArcodSource.SOURCE_ID) } returns
            RateLimitState(0.0, 0L, false, 0L, 0)
        coEvery { rateLimiter.acquire(ArcodSource.SOURCE_ID) } returns false

        assertThat(source().resolve(query())).isNull()
        coVerify { client wasNot Called }
    }

    // ── resolve failure modes ────────────────────────────────────────────

    @Test fun `no match returns null WITHOUT reporting failure`() = runTest {
        stubConnectedAndReady()
        // Items that don't match the query at all → matcher returns null.
        coEvery { client.search(any()) } returns listOf(
            matchableItem(title = "Totally Different Song", artist = "Some Other Band"),
        )

        assertThat(source().resolve(query())).isNull()
        // A catalog miss must NOT count toward the circuit breaker — ARCOD is
        // 3rd-string and sees miss-prone tracks; otherwise 5 misses self-disable
        // it for tracks it CAN serve.
        coVerify(exactly = 0) { rateLimiter.reportFailure(any()) }
        coVerify(exactly = 0) { client.createJob(any()) }
    }

    @Test fun `empty search results return null WITHOUT reporting failure`() = runTest {
        stubConnectedAndReady()
        coEvery { client.search(any()) } returns emptyList()

        assertThat(source().resolve(query())).isNull()
        coVerify(exactly = 0) { rateLimiter.reportFailure(any()) }
    }

    @Test fun `null album id on matched item returns null WITHOUT reporting failure`() = runTest {
        stubConnectedAndReady()
        coEvery { client.search(any()) } returns listOf(matchableItem(albumId = null))

        assertThat(source().resolve(query())).isNull()
        coVerify(exactly = 0) { rateLimiter.reportFailure(any()) }
        coVerify(exactly = 0) { client.createJob(any()) }
    }

    @Test fun `createJob null returns null and reports failure`() = runTest {
        stubConnectedAndReady()
        coEvery { client.search(any()) } returns listOf(matchableItem())
        coEvery { client.createJob(any()) } returns null

        assertThat(source().resolve(query())).isNull()
        coVerify { rateLimiter.reportFailure(ArcodSource.SOURCE_ID) }
        coVerify(exactly = 0) { client.pollStatus(any(), any(), any()) }
    }

    @Test fun `pollStatus null returns null and reports failure`() = runTest {
        stubConnectedAndReady()
        coEvery { client.search(any()) } returns listOf(matchableItem())
        coEvery { client.createJob(any()) } returns ArcodJob(id = "job1", status = "pending")
        coEvery { client.pollStatus("job1", any(), any()) } returns null

        assertThat(source().resolve(query())).isNull()
        coVerify { rateLimiter.reportFailure(ArcodSource.SOURCE_ID) }
    }

    @Test fun `completed job with no url returns null and reports failure`() = runTest {
        stubConnectedAndReady()
        val completed = ArcodJob(id = "job1", status = "completed", downloadUrl = null)
        coEvery { client.search(any()) } returns listOf(matchableItem())
        coEvery { client.createJob(any()) } returns ArcodJob(id = "job1", status = "pending")
        coEvery { client.pollStatus("job1", any(), any()) } returns completed
        coEvery { client.downloadUrlFrom(completed) } returns null

        assertThat(source().resolve(query())).isNull()
        coVerify { rateLimiter.reportFailure(ArcodSource.SOURCE_ID) }
    }

    // ── happy path ───────────────────────────────────────────────────────

    @Test fun `happy path returns SourceResult and reports success`() = runTest {
        stubConnectedAndReady()
        val item = matchableItem()
        val url = "https://dl.arcod.xyz/render/karma-police.flac"
        val completed = ArcodJob(id = "job1", status = "completed", downloadUrl = url)
        coEvery { client.search("Radiohead Karma Police") } returns listOf(item)
        coEvery { client.createJob(any()) } returns ArcodJob(id = "job1", status = "pending")
        coEvery { client.pollStatus("job1", any(), any()) } returns completed
        coEvery { client.downloadUrlFrom(completed) } returns url

        val result = source().resolve(query())

        assertThat(result).isNotNull()
        result!!
        assertThat(result.sourceId).isEqualTo(ArcodSource.SOURCE_ID)
        assertThat(result.downloadUrl).isEqualTo(url)
        assertThat(result.downloadHeaders).isEmpty()
        assertThat(result.format.codec).isEqualTo("flac")
        assertThat(result.format.bitrateKbps).isEqualTo(0)
        assertThat(result.format.sampleRateHz).isEqualTo(0)
        assertThat(result.format.bitsPerSample).isEqualTo(0)
        assertThat(result.confidence).isGreaterThan(0.5f)
        assertThat(result.sourceTrackId).isEqualTo("job1")
        assertThat(result.coverArtUrl).isEqualTo("https://arcod.xyz/cover/large.jpg")
        coVerify { rateLimiter.reportSuccess(ArcodSource.SOURCE_ID) }
    }

    @Test fun `confidence on result equals matcher confidence`() = runTest {
        stubConnectedAndReady()
        val item = matchableItem()
        val expected = ArcodMatcher.best(query(), listOf(item))!!.confidence
        val url = "https://dl.arcod.xyz/x.flac"
        val completed = ArcodJob(id = "job1", status = "completed", downloadUrl = url)
        coEvery { client.search(any()) } returns listOf(item)
        coEvery { client.createJob(any()) } returns ArcodJob(id = "job1", status = "pending")
        coEvery { client.pollStatus("job1", any(), any()) } returns completed
        coEvery { client.downloadUrlFrom(completed) } returns url

        assertThat(source().resolve(query())!!.confidence).isEqualTo(expected)
    }

    // ── fast single-GET stream path (no job render/poll) ──────────────────

    @Test fun `single-GET stream success returns SourceResult WITHOUT creating a job`() = runTest {
        stubConnectedAndReady()
        val item = matchableItem()
        val url = "https://stream.arcod.xyz/100.flac"
        coEvery { client.search("Radiohead Karma Police") } returns listOf(item)
        coEvery { client.streamUrl(100L, LosslessQualityTier.MAX.qobuzCode) } returns
            ArcodStreamResult(url = url, expiresInSec = 300)

        val result = source().resolve(query())

        assertThat(result).isNotNull()
        result!!
        assertThat(result.downloadUrl).isEqualTo(url)
        assertThat(result.format.codec).isEqualTo("flac")
        assertThat(result.sourceTrackId).isEqualTo("100")
        assertThat(result.coverArtUrl).isEqualTo("https://arcod.xyz/cover/large.jpg")
        coVerify { rateLimiter.reportSuccess(ArcodSource.SOURCE_ID) }
        // The whole point: no enqueue, no poll.
        coVerify(exactly = 0) { client.createJob(any()) }
        coVerify(exactly = 0) { client.pollStatus(any(), any(), any()) }
    }

    @Test fun `single-GET uses the configured download quality tier`() = runTest {
        stubConnectedAndReady()
        coEvery { losslessPrefs.qualityTierNow() } returns LosslessQualityTier.HI_RES
        val item = matchableItem()
        coEvery { client.search(any()) } returns listOf(item)
        coEvery { client.streamUrl(100L, LosslessQualityTier.HI_RES.qobuzCode) } returns
            ArcodStreamResult(url = "https://stream.arcod.xyz/100.flac")

        assertThat(source().resolve(query())).isNotNull()
        coVerify { client.streamUrl(100L, LosslessQualityTier.HI_RES.qobuzCode) }
    }

    @Test fun `stream null falls back to the job render path`() = runTest {
        stubConnectedAndReady() // streamUrl already stubbed null here
        val item = matchableItem()
        val url = "https://dl.arcod.xyz/render/karma-police.flac"
        val completed = ArcodJob(id = "job1", status = "completed", downloadUrl = url)
        coEvery { client.search(any()) } returns listOf(item)
        coEvery { client.createJob(any()) } returns ArcodJob(id = "job1", status = "pending")
        coEvery { client.pollStatus("job1", any(), any()) } returns completed
        coEvery { client.downloadUrlFrom(completed) } returns url

        val result = source().resolve(query())

        assertThat(result).isNotNull()
        assertThat(result!!.downloadUrl).isEqualTo(url)
        assertThat(result.sourceTrackId).isEqualTo("job1")
        coVerify { client.createJob(any()) }
    }

    // ── error propagation ────────────────────────────────────────────────

    @Test fun `rate-limited exception reports rate limited and returns null`() = runTest {
        stubConnectedAndReady()
        coEvery { client.search(any()) } throws ArcodRateLimitedException()

        assertThat(source().resolve(query())).isNull()
        coVerify { rateLimiter.reportRateLimited(ArcodSource.SOURCE_ID) }
        coVerify(exactly = 0) { rateLimiter.reportFailure(ArcodSource.SOURCE_ID) }
    }

    @Test fun `cancellation propagates and is not swallowed as failure`() = runTest {
        stubConnectedAndReady()
        coEvery { client.search(any()) } throws CancellationException("cancelled")

        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { source().resolve(query()) }
        }
        coVerify(exactly = 0) { rateLimiter.reportFailure(ArcodSource.SOURCE_ID) }
        coVerify(exactly = 0) { rateLimiter.reportSuccess(ArcodSource.SOURCE_ID) }
    }
}
