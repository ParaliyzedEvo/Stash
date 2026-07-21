package com.stash.data.download.search

import android.content.Context
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.SimpleCache
import com.stash.core.data.audio.AudioMetadata
import com.stash.core.data.audio.LoudnessMeasurer
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.files.LocalFileOps
import com.stash.core.data.repository.MusicRepository
import com.stash.core.model.MusicSource
import com.stash.core.model.TrackItem
import com.stash.data.download.DownloadExecutor
import com.stash.data.download.DownloadResult
import com.stash.data.download.files.FileOrganizer.CommittedTrack
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.LosslessSourceRegistry
import com.stash.data.download.lyrics.LyricsFetchTrigger
import com.stash.data.download.shared.TrackFinalizer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** Regression coverage for truthful terminal download status. */
class SearchDownloadCoordinatorCompletionTest {

    private val registry: LosslessSourceRegistry = mockk(relaxed = true)
    private val previewCache: SimpleCache = mockk(relaxed = true)
    private val httpDataSourceFactory: HttpDataSource.Factory = mockk(relaxed = true)
    private val cacheKeyFactory: CacheKeyFactory = mockk(relaxed = true)
    private val downloadExecutor: DownloadExecutor = mockk()
    private val trackFinalizer: TrackFinalizer = mockk()
    private val trackDao: TrackDao = mockk(relaxed = true)
    private val musicRepository: MusicRepository = mockk(relaxed = true)
    private val blocklistGuard: BlocklistGuard = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)
    private val losslessPrefs: LosslessSourcePreferences = mockk(relaxed = true)
    private val downloadQueueDao: DownloadQueueDao = mockk(relaxed = true)
    private val localFileOps: LocalFileOps = mockk()
    private val loudnessMeasurer: LoudnessMeasurer = mockk(relaxed = true)
    private val lyricsFetchTrigger: LyricsFetchTrigger = mockk(relaxed = true)

    private val tmpCacheDir = File(
        System.getProperty("java.io.tmpdir"),
        "stash-search-completion-test-${System.nanoTime()}",
    ).also { it.mkdirs() }

    private fun newSubject() = SearchDownloadCoordinator(
        registry = registry,
        previewCache = previewCache,
        httpDataSourceFactory = httpDataSourceFactory,
        cacheKeyFactory = cacheKeyFactory,
        downloadExecutor = downloadExecutor,
        trackFinalizer = trackFinalizer,
        trackDao = trackDao,
        musicRepository = musicRepository,
        blocklistGuard = blocklistGuard,
        context = context,
        losslessPrefs = losslessPrefs,
        downloadQueueDao = downloadQueueDao,
        localFileOps = localFileOps,
        loudnessMeasurer = loudnessMeasurer,
        lyricsFetchTrigger = lyricsFetchTrigger,
    )

    @Before
    fun setUp() {
        every { context.cacheDir } returns tmpCacheDir
        every { localFileOps.acceptDownloadOrDelete(any()) } returns true
    }

    private fun track() = TrackItem(
        videoId = "vid42",
        title = "Sample",
        artist = "Sample Artist",
        durationSeconds = 200.0,
        thumbnailUrl = null,
    )

    private fun existingTrack() = TrackEntity(
        id = 7L,
        title = "Sample",
        artist = "Sample Artist",
        youtubeId = "vid42",
        canonicalTitle = "sample",
        canonicalArtist = "sample artist",
        durationMs = 200_000L,
        source = MusicSource.YOUTUBE,
        albumArtUrl = null,
    )

    private fun arrangeFinalizedYtDlpDownload() {
        coEvery { losslessPrefs.enabledNow() } returns false
        val tempFile = File.createTempFile("search_yt", ".opus").apply { deleteOnExit() }
        coEvery {
            downloadExecutor.download(any(), any(), any(), any(), any())
        } returns DownloadResult.Success(tempFile)
        coEvery {
            trackFinalizer.finalizeFile(any(), any(), any(), any())
        } returns TrackFinalizer.FinalizeResult.Success(
            committed = CommittedTrack(
                filePath = "/library/Sample Artist/Sample.opus",
                sizeBytes = 4096L,
            ),
            meta = AudioMetadata(
                durationMs = 200_000L,
                bitrateKbps = 128,
                format = "opus",
                sampleRateHz = 48_000,
                bitsPerSample = 16,
            ),
        )
        coEvery { trackDao.findByYoutubeId("vid42") } returns existingTrack()
        coEvery {
            trackDao.markAsDownloaded(any(), any(), any(), any(), any(), any())
        } returns 1
    }

    private fun assertFailedNeverCompleted(statuses: List<SearchDownloadStatus>) {
        assertTrue("terminal status must be Failed, got $statuses", statuses.last() is SearchDownloadStatus.Failed)
        assertFalse("Completed must never be emitted after persistence failure, got $statuses", statuses.any {
            it is SearchDownloadStatus.Completed
        })
    }

    @Test
    fun `invalid committed file fails instead of completing`() = runTest {
        arrangeFinalizedYtDlpDownload()
        every { localFileOps.acceptDownloadOrDelete(any()) } returns false

        val statuses = newSubject().download(track()).toList()

        assertFailedNeverCompleted(statuses)
        coVerify(exactly = 0) { trackDao.markAsDownloaded(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { musicRepository.linkTrackToDownloadsMix(any()) }
    }

    @Test
    fun `markAsDownloaded failure fails instead of completing`() = runTest {
        arrangeFinalizedYtDlpDownload()
        coEvery {
            trackDao.markAsDownloaded(any(), any(), any(), any(), any(), any())
        } throws IllegalStateException("database unavailable")

        val statuses = newSubject().download(track()).toList()

        assertFailedNeverCompleted(statuses)
        coVerify(exactly = 1) { musicRepository.linkTrackToDownloadsMix(7L) }
    }
    @Test
    fun `zero-row markAsDownloaded fails instead of completing`() = runTest {
        arrangeFinalizedYtDlpDownload()
        coEvery {
            trackDao.markAsDownloaded(any(), any(), any(), any(), any(), any())
        } returns 0

        val statuses = newSubject().download(track()).toList()

        assertFailedNeverCompleted(statuses)
    }


    @Test
    fun `downloads-mix link failure fails instead of completing`() = runTest {
        arrangeFinalizedYtDlpDownload()
        coEvery { musicRepository.linkTrackToDownloadsMix(7L) } throws
            IllegalStateException("downloads mix unavailable")

        val statuses = newSubject().download(track()).toList()

        assertFailedNeverCompleted(statuses)
        coVerify(exactly = 0) { trackDao.markAsDownloaded(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `persistence cancellation is propagated`() = runTest {
        arrangeFinalizedYtDlpDownload()
        coEvery {
            trackDao.markAsDownloaded(any(), any(), any(), any(), any(), any())
        } throws CancellationException("cancelled")

        var cancellationPropagated = false
        try {
            newSubject().download(track()).toList()
        } catch (_: CancellationException) {
            cancellationPropagated = true
        }
        assertTrue(cancellationPropagated)
    }
    @Test
    fun `yt-dlp cancellation is propagated`() = runTest {
        coEvery { losslessPrefs.enabledNow() } returns false
        coEvery {
            downloadExecutor.download(any(), any(), any(), any(), any())
        } throws CancellationException("cancelled")

        var cancellationPropagated = false
        try {
            newSubject().download(track()).toList()
        } catch (_: CancellationException) {
            cancellationPropagated = true
        }
        assertTrue(cancellationPropagated)
    }

}
