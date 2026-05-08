package com.stash.data.download

import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.sync.TrackDownloadOutcome
import com.stash.core.model.DownloadStatus
import com.stash.core.model.Track
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the v0.9.17 Task 5 translation of [TrackDownloadResult.Deferred]
 * into a [DownloadStatus.WAITING_FOR_LOSSLESS] DAO write.
 *
 * The Task 4 placeholder mapped Deferred to [TrackDownloadOutcome.Failed],
 * which (via [TrackDownloadWorker]) would write status=FAILED, get caught
 * by `getRetryableBySources`, and burn through the retry budget — leaving
 * deferred tracks permanently FAILED. Task 5 replaces this with a direct
 * DAO write to WAITING_FOR_LOSSLESS plus a dedicated [Deferred] outcome
 * the worker handles as benign.
 */
class TrackDownloaderImplDeferredTest {

    private val downloadManager: DownloadManager = mockk()
    private val downloadQueueDao: DownloadQueueDao = mockk(relaxed = true)

    private fun newSubject(): TrackDownloaderImpl = TrackDownloaderImpl(
        downloadManager = downloadManager,
        downloadQueueDao = downloadQueueDao,
    )

    private fun stubTrack(id: Long = 7L): Track = Track(
        id = id,
        title = "Sample",
        artist = "Sample Artist",
    )

    @Test
    fun `Deferred result writes WAITING_FOR_LOSSLESS to download_queue`() = runTest {
        coEvery { downloadManager.downloadTrack(any(), any()) } returns TrackDownloadResult.Deferred
        val queueEntry = DownloadQueueEntity(
            id = 42L,
            trackId = 7L,
            status = DownloadStatus.IN_PROGRESS,
        )
        coEvery { downloadQueueDao.getByTrackId(7L) } returns queueEntry

        val outcome = newSubject().downloadTrack(stubTrack(id = 7L))

        coVerify {
            downloadQueueDao.updateStatus(
                id = 42L,
                status = DownloadStatus.WAITING_FOR_LOSSLESS,
            )
        }
        assertTrue(
            "expected TrackDownloadOutcome.Deferred, got $outcome",
            outcome is TrackDownloadOutcome.Deferred,
        )
    }

    @Test
    fun `Deferred result with no queue entry skips DAO write but still returns Deferred`() = runTest {
        // Edge case: queue entry already cleaned up. The translator must not
        // crash on a null lookup; it must still return Deferred so the worker
        // chain doesn't surface a Failed outcome.
        coEvery { downloadManager.downloadTrack(any(), any()) } returns TrackDownloadResult.Deferred
        coEvery { downloadQueueDao.getByTrackId(any()) } returns null

        val outcome = newSubject().downloadTrack(stubTrack(id = 7L))

        assertTrue(
            "expected TrackDownloadOutcome.Deferred even with null queue row, got $outcome",
            outcome is TrackDownloadOutcome.Deferred,
        )
    }
}
