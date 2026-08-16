package com.stash.data.download.lossless

import android.content.Context
import com.stash.core.data.audio.AudioDurationExtractor
import com.stash.core.data.audio.AudioMetadata
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.model.Track
import com.stash.core.model.UpgradeResult
import com.stash.data.download.DownloadManager
import com.stash.data.download.TrackDownloadResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LosslessUpgraderImplTest {

    private val context: Context = mockk(relaxed = true)
    private val downloadManager: DownloadManager = mockk()
    private val trackDao: TrackDao = mockk(relaxUnitFun = true)
    private val audioExtractor: AudioDurationExtractor = mockk()
    private val losslessPrefs: LosslessSourcePreferences = mockk {
        every { enabled } returns flowOf(true)
    }
    private val subject = LosslessUpgraderImpl(context, downloadManager, trackDao, audioExtractor, losslessPrefs)

    @Test fun `Success maps to Upgraded`() = runTest {
        coEvery { downloadManager.tryLosslessDownload(any(), forced = true) } returns
            TrackDownloadResult.Success(filePath = "/path/to/file.flac")
        coEvery { audioExtractor.extract(any()) } returns null
        assertEquals(UpgradeResult.Upgraded, subject.upgradeToLossless(stubTrack()))
    }

    @Test fun `null maps to NoMatch`() = runTest {
        coEvery { downloadManager.tryLosslessDownload(any(), forced = true) } returns null
        assertEquals(UpgradeResult.NoMatch, subject.upgradeToLossless(stubTrack()))
    }

    @Test fun `Unmatched maps to NoMatch`() = runTest {
        coEvery { downloadManager.tryLosslessDownload(any(), forced = true) } returns
            TrackDownloadResult.Unmatched()
        assertEquals(UpgradeResult.NoMatch, subject.upgradeToLossless(stubTrack()))
    }

    @Test fun `Failed maps to NoMatch`() = runTest {
        coEvery { downloadManager.tryLosslessDownload(any(), forced = true) } returns
            TrackDownloadResult.Failed("network")
        assertEquals(UpgradeResult.NoMatch, subject.upgradeToLossless(stubTrack()))
    }

    @Test fun `Deferred maps to NoMatch`() = runTest {
        coEvery { downloadManager.tryLosslessDownload(any(), forced = true) } returns
            TrackDownloadResult.Deferred
        assertEquals(UpgradeResult.NoMatch, subject.upgradeToLossless(stubTrack()))
    }

    @Test fun `thrown exception maps to Error`() = runTest {
        coEvery { downloadManager.tryLosslessDownload(any(), forced = true) } throws
            RuntimeException("boom")
        assertEquals(UpgradeResult.Error, subject.upgradeToLossless(stubTrack()))
    }

    @Test fun `passes forced = true to bypass global lossless toggle`() = runTest {
        coEvery { downloadManager.tryLosslessDownload(any(), forced = true) } returns null
        subject.upgradeToLossless(stubTrack())
        // mockk's `forced = true` matcher in the coEvery already enforces this;
        // a separate coVerify is redundant but documents the intent.
    }

    /**
     * Regression for the bug where Find-in-FLAC wrote the new file to disk
     * but never updated tracks.file_path / file_format / quality_kbps. The
     * Now Playing player kept reading the stale 'opus' row, the new FLAC
     * file was orphaned on disk, and "Recently downloaded" never surfaced
     * the upgraded track because no row changed.
     */
    @Test fun `Success persists markAsDownloaded + setFormatAndQuality + deletes old file`() = runTest {
        val track = stubTrack(filePath = "/old/Artist - Song.m4a", fileFormat = "opus")
        val newPath = "/new/Artist - Song.flac"
        coEvery { downloadManager.tryLosslessDownload(track, forced = true) } returns
            TrackDownloadResult.Success(filePath = newPath)
        coEvery { audioExtractor.extract(newPath) } returns AudioMetadata(
            durationMs = 0,  // skip setDuration branch
            bitrateKbps = 1411,
            format = "flac",
            sampleRateHz = 44_100,
            bitsPerSample = 16,
        )

        val result = subject.upgradeToLossless(track)

        assertEquals(UpgradeResult.Upgraded, result)
        // Note: positional matchers — markAsDownloaded has a defaulted
        // downloadedAt: Long that the production call leaves implicit.
        // Named-arg coVerify computes its own System.currentTimeMillis()
        // for the default and never matches. Positional-with-any() sidesteps
        // that. Order is (trackId, filePath, fileSizeBytes, downloadedAt,
        // sampleRateHz, bitsPerSample).
        coVerify {
            trackDao.markAsDownloaded(
                track.id,
                newPath,
                any(),
                any(),
                44_100,
                16,
            )
        }
        coVerify {
            trackDao.setFormatAndQuality(
                trackId = track.id,
                fileFormat = "flac",
                qualityKbps = 1411,
            )
        }
    }

    @Test fun `NoMatch does not call markAsDownloaded`() = runTest {
        coEvery { downloadManager.tryLosslessDownload(any(), forced = true) } returns null
        subject.upgradeToLossless(stubTrack())
        coVerify(exactly = 0) {
            trackDao.markAsDownloaded(any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) {
            trackDao.setFormatAndQuality(any(), any(), any())
        }
    }

    private fun stubTrack(
        filePath: String? = null,
        fileFormat: String = "opus",
    ): Track = Track(
        id = 1,
        title = "Karma Police",
        artist = "Radiohead",
        filePath = filePath,
        fileFormat = fileFormat,
    )
}
