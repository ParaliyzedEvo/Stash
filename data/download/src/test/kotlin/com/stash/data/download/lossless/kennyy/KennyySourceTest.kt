package com.stash.data.download.lossless.kennyy

import com.google.common.truth.Truth.assertThat
import com.stash.data.download.lossless.AggregatorRateLimiter
import com.stash.data.download.lossless.LosslessQualityTier
import com.stash.data.download.lossless.LosslessSourceHealthGate
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.LosslessUrlInspector
import com.stash.data.download.lossless.RateLimitState
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.qobuz.QobuzDownloadData
import com.stash.data.download.lossless.qobuz.QobuzPerformer
import com.stash.data.download.lossless.qobuz.QobuzSearchData
import com.stash.data.download.lossless.qobuz.QobuzTrack
import com.stash.data.download.lossless.qobuz.QobuzTrackList
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Focused tests for the degradation-detection wiring added to
 * [KennyySource]: a preview-sample / lossy-downgrade URL must be treated
 * as a miss (return null) AND record the source degraded; a healthy full
 * URL must resolve normally without recording degraded.
 */
class KennyySourceTest {

    private val apiClient: KennyyApiClient = mockk()
    private val rateLimiter: AggregatorRateLimiter = mockk(relaxUnitFun = true)
    private val losslessPrefs: LosslessSourcePreferences = mockk()
    private val urlInspector = LosslessUrlInspector() // real pure classifier
    private val healthGate: LosslessSourceHealthGate = mockk(relaxUnitFun = true)

    private fun source() = KennyySource(apiClient, rateLimiter, losslessPrefs, urlInspector, healthGate)

    private val query = TrackQuery(artist = "Daft Punk", title = "Get Lucky", durationMs = 369_000L)

    private fun candidate() = QobuzTrack(
        id = 42L,
        title = "Get Lucky",
        duration = 369,
        isrc = null,
        performer = QobuzPerformer(name = "Daft Punk"),
        streamable = true,
        maximumBitDepth = 24,
        maximumSamplingRate = 44.1f,
    )

    private fun stubHappyPathTo(url: String) {
        coEvery { rateLimiter.acquire(KennyySource.SOURCE_ID) } returns true
        coEvery { rateLimiter.stateOf(KennyySource.SOURCE_ID) } returns
            RateLimitState(3.0, 0L, false, 0L, 0)
        coEvery { losslessPrefs.qualityTierNow() } returns LosslessQualityTier.MAX // → fmt 27
        coEvery { apiClient.search(any(), any()) } returns
            QobuzSearchData(tracks = QobuzTrackList(items = listOf(candidate())))
        coEvery { apiClient.getFileUrl(42L, any()) } returns QobuzDownloadData(url = url)
    }

    @Test
    fun `degraded preview-sample url returns null and records degraded`() = runTest {
        stubHappyPathTo("https://cdn/file?fmt=27&range=20-30&etsp=9999999999")
        val result = source().resolve(query)
        assertThat(result).isNull()
        coVerify { healthGate.recordDegraded(KennyySource.SOURCE_ID) }
    }

    @Test
    fun `healthy full url returns a result and does not record degraded`() = runTest {
        stubHappyPathTo("https://cdn/file?fmt=27&etsp=9999999999")
        val result = source().resolve(query)
        assertThat(result).isNotNull()
        assertThat(result!!.downloadUrl).contains("fmt=27")
        coVerify(exactly = 0) { healthGate.recordDegraded(any()) }
    }
}
