package com.stash.data.download.lossless.qbdlx

import com.google.common.truth.Truth.assertThat
import com.stash.data.download.lossless.AggregatorRateLimiter
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.RateLimitState
import com.stash.data.download.lossless.TrackQuery
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * A user-initiated (foreground) resolve must be able to skip the per-source token
 * bucket via `bypassRateLimit = true` — a preview tap can't be throttled behind
 * speculative background prefetches. Mirrors [QbdlxQobuzSourceTest]'s harness with
 * the limiter DENYING all tokens (`acquire` → false).
 */
class QbdlxBypassRateLimitTest {

    private val apiClient: QbdlxApiClient = mockk()
    private val credentialStore: QbdlxCredentialStore = mockk(relaxUnitFun = true)
    private val rateLimiter: AggregatorRateLimiter = mockk(relaxUnitFun = true)
    private val prefs: LosslessSourcePreferences = mockk()

    private fun source() = QbdlxQobuzSource(apiClient, credentialStore, rateLimiter, prefs)
    private val sid = QbdlxQobuzSource.SOURCE_ID

    private val notBroken =
        RateLimitState(0.0, 0L, isCircuitBroken = false, msUntilUnblock = 0L, recentFailures = 0)

    private val query = TrackQuery(
        artist = "John Frusciante", title = "Murderers", isrc = "USWB10003085", durationMs = 160_000,
    )

    private fun candidate() = QbdlxTrack(
        id = 42, title = "Murderers", isrc = "USWB10003085", duration = 160, streamable = true,
        performer = QbdlxPerformer("John Frusciante"), maximumBitDepth = 16, maximumSamplingRate = 44.1f,
        album = QbdlxAlbum(QbdlxImage(large = "https://art/large.jpg")),
    )

    private fun ok() = QbdlxResolveResult.Ok("https://cdn/file?fmt=27", codec = "flac", bitDepth = 24, sampleRateHz = 96_000)

    /** Enabled + pool live + breaker closed, but the limiter is EXHAUSTED (acquire → false). */
    private fun enabledButThrottled() {
        coEvery { prefs.qbdlxEnabledNow() } returns true
        coEvery { prefs.qualityTierNow() } returns com.stash.data.download.lossless.LosslessQualityTier.MAX
        coEvery { credentialStore.allDead() } returns false
        coEvery { rateLimiter.stateOf(sid) } returns notBroken
        coEvery { rateLimiter.acquire(sid) } returns false
        coEvery { credentialStore.activeToken() } returns "tok1"
        coEvery { apiClient.search(any(), "tok1") } returns listOf(candidate())
        coEvery { apiClient.getFileUrl(42, 27, "tok1") } returns ok()
    }

    @Test
    fun `bypass true resolves despite an exhausted rate limiter`() = runTest {
        enabledButThrottled()

        val r = source().resolve(query, bypassRateLimit = true)

        assertThat(r).isNotNull()
        assertThat(r!!.sourceId).isEqualTo("qbdlx_qobuz")
        assertThat(r.downloadUrl).isEqualTo("https://cdn/file?fmt=27")
    }

    @Test
    fun `bypass false is throttled when the limiter has no tokens`() = runTest {
        enabledButThrottled()

        val r = source().resolve(query, bypassRateLimit = false)

        assertThat(r).isNull()
    }
}
