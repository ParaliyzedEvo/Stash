package com.stash.data.lyrics.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.stash.data.lyrics.LyricsRepository
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

    private fun runWorker(repo: LyricsRepository): ListenableWorker.Result {
        runTest {
            lastResult = TestListenableWorkerBuilder<LyricsTtmlUpgradeWorker>(context)
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
}
