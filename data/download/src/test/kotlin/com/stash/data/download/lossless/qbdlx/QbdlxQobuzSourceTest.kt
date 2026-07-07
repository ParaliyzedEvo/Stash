package com.stash.data.download.lossless.qbdlx

import com.google.common.truth.Truth.assertThat
import com.stash.data.download.lossless.AggregatorRateLimiter
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.RateLimitState
import com.stash.data.download.lossless.TrackQuery
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Unit tests for [QbdlxQobuzSource]. The [QbdlxApiClient], [QbdlxCredentialStore],
 * [AggregatorRateLimiter] and [LosslessSourcePreferences] are MockK'd; the real
 * [com.stash.data.download.lossless.qobuz.QobuzCandidateMatcher] object scores
 * real [QbdlxTrack]s so matching/threshold runs end-to-end (mirrors AmzSourceTest).
 */
class QbdlxQobuzSourceTest {

    private val apiClient: QbdlxApiClient = mockk()
    private val credentialStore: QbdlxCredentialStore = mockk(relaxUnitFun = true)
    private val rateLimiter: AggregatorRateLimiter = mockk(relaxUnitFun = true)
    private val prefs: LosslessSourcePreferences = mockk()

    private fun source() = QbdlxQobuzSource(apiClient, credentialStore, rateLimiter, prefs)

    private val sid = QbdlxQobuzSource.SOURCE_ID

    private val notBroken =
        RateLimitState(2.0, 0L, isCircuitBroken = false, msUntilUnblock = 0L, recentFailures = 0)

    private val query = TrackQuery(
        artist = "John Frusciante",
        title = "Murderers",
        isrc = "USWB10003085",
        durationMs = 160_000,
    )

    private fun candidate(id: Long = 42) = QbdlxTrack(
        id = id,
        title = "Murderers",
        isrc = "USWB10003085",
        duration = 160,
        streamable = true,
        performer = QbdlxPerformer("John Frusciante"),
        maximumBitDepth = 16,
        maximumSamplingRate = 44.1f,
        album = QbdlxAlbum(QbdlxImage(large = "https://art/large.jpg")),
    )

    private fun ok(url: String = "https://cdn/file?fmt=27") =
        QbdlxResolveResult.Ok(url, codec = "flac", bitDepth = 24, sampleRateHz = 96_000)

    /** Toggle on, pool live, breaker closed, tokens available, MAX tier (qobuzCode 27). */
    private fun enabledAndAcquired() {
        coEvery { prefs.qbdlxEnabledNow() } returns true
        coEvery { prefs.qualityTierNow() } returns com.stash.data.download.lossless.LosslessQualityTier.MAX
        coEvery { credentialStore.allDead() } returns false
        coEvery { rateLimiter.stateOf(sid) } returns notBroken
        coEvery { rateLimiter.acquire(sid) } returns true
    }

    @Test
    fun `match yields SourceResult with response format`() = runTest {
        enabledAndAcquired()
        coEvery { credentialStore.activeToken() } returns "tok1"
        coEvery { apiClient.search(any(), "tok1") } returns listOf(candidate())
        coEvery { apiClient.getFileUrl(42, 27, "tok1") } returns ok()

        val r = source().resolve(query)

        assertThat(r).isNotNull()
        assertThat(r!!.sourceId).isEqualTo("qbdlx_qobuz")
        assertThat(r.downloadUrl).isEqualTo("https://cdn/file?fmt=27")
        assertThat(r.downloadHeaders).isEmpty()
        assertThat(r.confidence).isEqualTo(0.95f) // ISRC match
        assertThat(r.format.codec).isEqualTo("flac")
        assertThat(r.format.bitrateKbps).isEqualTo(0)
        // Format comes from the getFileUrl RESPONSE, not the search candidate.
        assertThat(r.format.bitsPerSample).isEqualTo(24)
        assertThat(r.format.sampleRateHz).isEqualTo(96_000)
        assertThat(r.sourceTrackId).isEqualTo("42")
        assertThat(r.coverArtUrl).isEqualTo("https://art/large.jpg")
        coVerify { credentialStore.recordAlive("tok1") }
        coVerify { rateLimiter.reportSuccess(sid) }
    }

    @Test
    fun `TokenDead marks token dead rotates and succeeds on second token`() = runTest {
        enabledAndAcquired()
        coEvery { credentialStore.activeToken() } returnsMany listOf("tok1", "tok2")
        coEvery { apiClient.search(any(), any()) } returns listOf(candidate())
        coEvery { apiClient.getFileUrl(42, 27, "tok1") } returns QbdlxResolveResult.TokenDead
        coEvery { apiClient.getFileUrl(42, 27, "tok2") } returns ok()

        val r = source().resolve(query)

        assertThat(r).isNotNull()
        coVerify { credentialStore.markDead("tok1") }
        coVerify { credentialStore.recordAlive("tok2") }
    }

    @Test
    fun `RegionLocked tries another region token`() = runTest {
        enabledAndAcquired()
        coEvery { credentialStore.activeToken() } returns "tok1"
        coEvery { credentialStore.tokensForRegion(null) } returns listOf("tok1", "tok2")
        coEvery { apiClient.search(any(), any()) } returns listOf(candidate())
        coEvery { apiClient.getFileUrl(42, 27, "tok1") } returns QbdlxResolveResult.RegionLocked
        coEvery { apiClient.getFileUrl(42, 27, "tok2") } returns ok()

        val r = source().resolve(query)

        assertThat(r).isNotNull()
        // RegionLocked is NOT a token-death — tok1 must not be marked dead.
        coVerify(exactly = 0) { credentialStore.markDead("tok1") }
        coVerify { credentialStore.recordAlive("tok2") }
    }

    @Test
    fun `search auth failure marks token dead and rotates without tripping breaker`() = runTest {
        enabledAndAcquired()
        coEvery { credentialStore.activeToken() } returnsMany listOf("tok1", "tok2")
        coEvery { apiClient.search(any(), "tok1") } throws QbdlxAuthException(401)
        coEvery { apiClient.search(any(), "tok2") } returns listOf(candidate())
        coEvery { apiClient.getFileUrl(42, 27, "tok2") } returns ok()

        val r = source().resolve(query)

        assertThat(r).isNotNull()
        coVerify { credentialStore.markDead("tok1") }
        // A dead token is not a source-health failure.
        coVerify(exactly = 0) { rateLimiter.reportFailure(sid) }
    }

    @Test
    fun `whole pool dead disables source and resolve returns null`() = runTest {
        coEvery { prefs.qbdlxEnabledNow() } returns true
        coEvery { credentialStore.allDead() } returns true
        coEvery { rateLimiter.stateOf(sid) } returns notBroken

        assertThat(source().isEnabled()).isFalse()
        assertThat(source().resolve(query)).isNull()
        coVerify(exactly = 0) { apiClient.search(any(), any()) }
    }

    @Test
    fun `resolveImmediate succeeds even when circuit broken and bypasses acquire`() = runTest {
        coEvery { prefs.qbdlxEnabledNow() } returns true
        // resolveImmediate(query) with no explicit quality falls back to the tier.
        coEvery { prefs.qualityTierNow() } returns com.stash.data.download.lossless.LosslessQualityTier.MAX
        coEvery { credentialStore.allDead() } returns false
        coEvery { rateLimiter.stateOf(sid) } returns
            RateLimitState(0.0, 0L, isCircuitBroken = true, msUntilUnblock = 60_000L, recentFailures = 5)
        coEvery { credentialStore.activeToken() } returns "tok1"
        coEvery { apiClient.search(any(), "tok1") } returns listOf(candidate())
        coEvery { apiClient.getFileUrl(42, 27, "tok1") } returns ok()

        val r = source().resolveImmediate(query)

        assertThat(r).isNotNull()
        coVerify(exactly = 0) { rateLimiter.acquire(any()) }
        coVerify { rateLimiter.reportSuccess(sid) }
    }

    @Test
    fun `disabled toggle blocks both download and streaming gates`() = runTest {
        coEvery { prefs.qbdlxEnabledNow() } returns false
        coEvery { credentialStore.allDead() } returns false
        coEvery { rateLimiter.stateOf(sid) } returns notBroken

        assertThat(source().isEnabled()).isFalse()
        assertThat(source().isEnabledForStreaming()).isFalse()
    }

    @Test
    fun `429 reports rate limited not failure`() = runTest {
        enabledAndAcquired()
        coEvery { credentialStore.activeToken() } returns "tok1"
        coEvery { apiClient.search(any(), "tok1") } throws QbdlxApiException(429, "Too Many Requests")

        val r = source().resolve(query)

        assertThat(r).isNull()
        coVerify { rateLimiter.reportRateLimited(sid) }
        coVerify(exactly = 0) { rateLimiter.reportFailure(sid) }
    }

    @Test
    fun `download path requests the user quality tier not always hi-res`() = runTest {
        // CD tier → qobuzCode 6. resolve() (download) must request 6, not a hardcoded 27.
        coEvery { prefs.qbdlxEnabledNow() } returns true
        coEvery { prefs.qualityTierNow() } returns com.stash.data.download.lossless.LosslessQualityTier.CD
        coEvery { credentialStore.allDead() } returns false
        coEvery { rateLimiter.stateOf(sid) } returns notBroken
        coEvery { rateLimiter.acquire(sid) } returns true
        coEvery { credentialStore.activeToken() } returns "tok1"
        coEvery { apiClient.search(any(), "tok1") } returns listOf(candidate())
        coEvery { apiClient.getFileUrl(42, 6, "tok1") } returns ok()

        val r = source().resolve(query)

        assertThat(r).isNotNull()
        coVerify { apiClient.getFileUrl(42, 6, "tok1") } // CD = format_id 6
    }

    @Test
    fun `cancellation propagates and is not swallowed as a failure`() = runTest {
        enabledAndAcquired()
        coEvery { credentialStore.activeToken() } returns "tok1"
        coEvery { apiClient.search(any(), "tok1") } throws kotlinx.coroutines.CancellationException("cancelled")

        org.junit.Assert.assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { source().resolve(query) }
        }
        coVerify(exactly = 0) { rateLimiter.reportFailure(sid) }
    }
}
