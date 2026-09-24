package com.stash.data.lyrics.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.stash.data.lyrics.LyricsRepository
import com.stash.data.lyrics.ManualFetchResult
import com.stash.data.lyrics.TtmlUpgradeResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The background TTML upgrade pass backs off on a 429 or a run of failures. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class LyricsTtmlUpgradeWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun repoWith(vararg results: TtmlUpgradeResult): LyricsRepository {
        val repo = mockk<LyricsRepository>()
        coEvery { repo.trackIdsPendingTtml() } returns results.indices.map { it + 1L }
        results.forEachIndexed { i, r -> coEvery { repo.upgradeToTtml(i + 1L) } returns r }
        return repo
    }

    private var lastResult: ListenableWorker.Result? = null

    private fun runWorker(repo: LyricsRepository, manual: Boolean = false): ListenableWorker.Result {
        runTest {
            lastResult = TestListenableWorkerBuilder<LyricsTtmlUpgradeWorker>(context)
                .setInputData(workDataOf(LyricsTtmlUpgradeWorker.KEY_MANUAL to manual))
                .setWorkerFactory(object : WorkerFactory() {
                    override fun createWorker(c: Context, name: String, p: WorkerParameters) =
                        LyricsTtmlUpgradeWorker(c, p, repo)
                })
                .build()
                .doWork()
        }
        return lastResult!!
    }

    private fun ListenableWorker.Result.bailed() =
        (this as ListenableWorker.Result.Success).outputData.getBoolean(LyricsTtmlUpgradeWorker.OUT_BAILED, false)

    @Test fun `a 429 stops the run and leaves the rest pending`() {
        val repo = repoWith(TtmlUpgradeResult.UPGRADED, TtmlUpgradeResult.RATE_LIMITED, TtmlUpgradeResult.UPGRADED)
        val result = runWorker(repo)
        assertTrue(result.bailed())
        coVerify(exactly = 0) { repo.upgradeToTtml(3L) }
    }

    @Test fun `five failures in a row stop the run`() {
        val repo = repoWith(*Array(7) { TtmlUpgradeResult.FAILED })
        val result = runWorker(repo)
        assertTrue(result.bailed())
        coVerify(exactly = 1) { repo.upgradeToTtml(5L) }
        coVerify(exactly = 0) { repo.upgradeToTtml(6L) }
        assertEquals(5, (result as ListenableWorker.Result.Success).outputData.getInt(LyricsTtmlUpgradeWorker.OUT_FAILED, -1))
    }

    @Test fun `a success resets the failure streak`() {
        val f = TtmlUpgradeResult.FAILED
        val repo = repoWith(f, f, f, f, TtmlUpgradeResult.NO_TTML, f, f, f, f)
        val result = runWorker(repo)
        assertFalse(result.bailed())
        coVerify(exactly = 1) { repo.upgradeToTtml(9L) }
    }

    @Test fun `a bail still runs the manual fetch for tracks with no lyrics`() {
        val repo = repoWith(TtmlUpgradeResult.RATE_LIMITED, TtmlUpgradeResult.UPGRADED)
        coEvery { repo.trackIdsMissingLyrics() } returns listOf(10L, 11L)
        coEvery { repo.fetchLyricsNow(10L) } returns ManualFetchResult.FETCHED
        coEvery { repo.fetchLyricsNow(11L) } returns ManualFetchResult.NOT_FOUND
        val result = runWorker(repo, manual = true)
        assertTrue(result.bailed())
        coVerify(exactly = 0) { repo.upgradeToTtml(2L) }
        coVerify(exactly = 1) { repo.fetchLyricsNow(10L) }
        coVerify(exactly = 1) { repo.fetchLyricsNow(11L) }
        assertEquals(1, (result as ListenableWorker.Result.Success).outputData.getInt(LyricsTtmlUpgradeWorker.OUT_FETCHED, -1))
    }
}
