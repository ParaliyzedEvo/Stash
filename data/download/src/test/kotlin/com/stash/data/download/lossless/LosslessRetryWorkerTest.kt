package com.stash.data.download.lossless

import android.content.Context
import androidx.work.WorkerParameters
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.DownloadStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v0.9.17 strict-FLAC: tests the one-shot sweep worker that re-resolves
 * [DownloadStatus.WAITING_FOR_LOSSLESS] rows.
 *
 * Behavior under test:
 *  - Resolved row → row flipped to [DownloadStatus.PENDING] so the
 *    standard download chain picks it up.
 *  - Still-null row → no DB write; row stays in WAITING_FOR_LOSSLESS for
 *    the next trigger.
 *  - Empty deferred set → success, no DB writes.
 *
 * The worker does not download — it only re-resolves and re-queues.
 *
 * Mocks the constructor deps directly (no Hilt), since [LosslessRetryWorker]
 * is a plain class that can be instantiated with stub [WorkerParameters].
 * MockK matches the precedent set by [DownloadManagerDeferTest].
 */
class LosslessRetryWorkerTest {

    private val appContext: Context = mockk(relaxed = true)
    private val workerParams: WorkerParameters = mockk(relaxed = true)
    private val downloadQueueDao: DownloadQueueDao = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk(relaxed = true)
    private val registry: LosslessSourceRegistry = mockk()

    private fun newWorker(): LosslessRetryWorker = LosslessRetryWorker(
        appContext = appContext,
        params = workerParams,
        downloadQueueDao = downloadQueueDao,
        trackDao = trackDao,
        registry = registry,
    )

    @Test
    fun `resolved row flips to PENDING and reports resolved=1 total=1`() = runTest {
        coEvery { downloadQueueDao.waitingForLosslessTracks() } returns listOf(
            entry(id = 100L, trackId = 1L),
        )
        coEvery { trackDao.getById(1L) } returns stubTrackEntity(1L)
        coEvery { registry.resolve(any()) } returns stubSourceResult()

        val result = newWorker().doWork() as androidx.work.ListenableWorker.Result.Success

        assertEquals(1, result.outputData.getInt(LosslessRetryWorker.KEY_RESOLVED, -1))
        assertEquals(1, result.outputData.getInt(LosslessRetryWorker.KEY_TOTAL, -1))
        coVerify(exactly = 1) {
            downloadQueueDao.updateStatus(
                id = 100L,
                status = DownloadStatus.PENDING,
            )
        }
    }

    @Test
    fun `unresolved row stays WAITING_FOR_LOSSLESS and reports resolved=0 total=1`() = runTest {
        coEvery { downloadQueueDao.waitingForLosslessTracks() } returns listOf(
            entry(id = 100L, trackId = 1L),
        )
        coEvery { trackDao.getById(1L) } returns stubTrackEntity(1L)
        coEvery { registry.resolve(any()) } returns null

        val result = newWorker().doWork() as androidx.work.ListenableWorker.Result.Success

        assertEquals(0, result.outputData.getInt(LosslessRetryWorker.KEY_RESOLVED, -1))
        assertEquals(1, result.outputData.getInt(LosslessRetryWorker.KEY_TOTAL, -1))
        coVerify(exactly = 0) {
            downloadQueueDao.updateStatus(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `empty deferred set returns resolved=0 total=0`() = runTest {
        coEvery { downloadQueueDao.waitingForLosslessTracks() } returns emptyList()

        val result = newWorker().doWork() as androidx.work.ListenableWorker.Result.Success

        assertEquals(0, result.outputData.getInt(LosslessRetryWorker.KEY_RESOLVED, -1))
        assertEquals(0, result.outputData.getInt(LosslessRetryWorker.KEY_TOTAL, -1))
        coVerify(exactly = 0) {
            downloadQueueDao.updateStatus(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `partial match across 3 rows reports resolved=2 total=3`() = runTest {
        coEvery { downloadQueueDao.waitingForLosslessTracks() } returns listOf(
            entry(id = 100L, trackId = 1L),
            entry(id = 101L, trackId = 2L),
            entry(id = 102L, trackId = 3L),
        )
        coEvery { trackDao.getById(1L) } returns stubTrackEntity(1L)
        coEvery { trackDao.getById(2L) } returns stubTrackEntity(2L)
        coEvery { trackDao.getById(3L) } returns stubTrackEntity(3L)
        coEvery { registry.resolve(match { it.title == "Track 1" }) } returns stubSourceResult()
        coEvery { registry.resolve(match { it.title == "Track 2" }) } returns null
        coEvery { registry.resolve(match { it.title == "Track 3" }) } returns stubSourceResult()

        val result = newWorker().doWork() as androidx.work.ListenableWorker.Result.Success

        assertEquals(2, result.outputData.getInt(LosslessRetryWorker.KEY_RESOLVED, -1))
        assertEquals(3, result.outputData.getInt(LosslessRetryWorker.KEY_TOTAL, -1))
        coVerify(exactly = 1) {
            downloadQueueDao.updateStatus(id = 100L, status = DownloadStatus.PENDING)
        }
        coVerify(exactly = 1) {
            downloadQueueDao.updateStatus(id = 102L, status = DownloadStatus.PENDING)
        }
        coVerify(exactly = 0) {
            downloadQueueDao.updateStatus(id = 101L, status = DownloadStatus.PENDING)
        }
    }

    private fun entry(id: Long, trackId: Long) = DownloadQueueEntity(
        id = id,
        trackId = trackId,
        status = DownloadStatus.WAITING_FOR_LOSSLESS,
        searchQuery = "test query",
    )

    private fun stubTrackEntity(id: Long) = TrackEntity(
        id = id,
        title = "Track $id",
        artist = "Artist $id",
        album = "Album $id",
        durationMs = 200_000L,
        canonicalTitle = "track $id",
        canonicalArtist = "artist $id",
        isrc = "USRC1700000$id",
    )

    private fun stubSourceResult(): SourceResult = SourceResult(
        sourceId = "squid_qobuz",
        downloadUrl = "https://example.test/file.flac",
        format = AudioFormat(codec = "flac", bitrateKbps = 0),
        confidence = 0.99f,
    )
}
