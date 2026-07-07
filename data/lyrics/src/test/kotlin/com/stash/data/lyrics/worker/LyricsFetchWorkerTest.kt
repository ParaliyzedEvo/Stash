package com.stash.data.lyrics.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.lyrics.LyricsRepository
import com.stash.data.lyrics.source.LyricsQuery
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [LyricsFetchWorker].
 *
 * Uses [TestListenableWorkerBuilder] + a per-test [WorkerFactory] so we can
 * drive `runAttemptCount` (which is only settable through the builder) and
 * still inject the mocked [LyricsRepository] + [TrackDao]. The `@HiltWorker`
 * generator emits an `AssistedInject` factory but we bypass it here — the
 * Hilt graph isn't on the classpath in a pure unit test.
 *
 * Behaviour under test:
 *  - Missing `KEY_TRACK_ID` -> `Result.failure()` (enqueuer bug).
 *  - Track deleted between enqueue + run -> `Result.success()` no-op (race
 *    we tolerate; nothing to fetch).
 *  - Happy path -> calls `resolveAndStore` exactly once and returns success.
 *  - Transient throw with `runAttemptCount < MAX_ATTEMPTS` -> `Result.retry`.
 *  - Transient throw with `runAttemptCount >= MAX_ATTEMPTS` -> `Result.success`
 *    so we leave the row's `lyrics_fetched_at` as NULL; a later on-open
 *    priority fetch (or a re-download) can retry it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class LyricsFetchWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `missing trackId fails`() {
        val worker = buildWorker(workDataOf(), mockk(relaxed = true), mockk(relaxed = true))
        val result = runBlocking { worker.doWork() }
        assertEquals(ListenableWorker.Result.failure(), result)
    }

    @Test
    fun `track deleted mid-flight succeeds without fetch`() {
        val trackDao = mockk<TrackDao>()
        val repo = mockk<LyricsRepository>()
        coEvery { trackDao.getById(1L) } returns null
        val worker = buildWorker(
            workDataOf(LyricsFetchWorker.KEY_TRACK_ID to 1L),
            trackDao,
            repo,
        )
        val result = runBlocking { worker.doWork() }
        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { repo.resolveAndStore(any()) }
    }

    @Test
    fun `happy path - fetches and succeeds`() {
        val trackDao = mockk<TrackDao>()
        val repo = mockk<LyricsRepository>()
        coEvery { trackDao.getById(1L) } returns sampleTrack(1L)
        // resolveAndStore returns null on a complete miss; the worker doesn't
        // distinguish — both hit and miss are "completed cleanly, don't retry".
        coEvery { repo.resolveAndStore(any()) } returns null
        val worker = buildWorker(
            workDataOf(LyricsFetchWorker.KEY_TRACK_ID to 1L),
            trackDao,
            repo,
        )
        val result = runBlocking { worker.doWork() }
        assertEquals(ListenableWorker.Result.success(), result)
        coVerify { repo.resolveAndStore(any<LyricsQuery>()) }
    }

    @Test
    fun `transient failure retries until exhausted`() {
        val trackDao = mockk<TrackDao>()
        val repo = mockk<LyricsRepository>()
        coEvery { trackDao.getById(1L) } returns sampleTrack(1L)
        coEvery { repo.resolveAndStore(any()) } throws RuntimeException("network")
        val worker = buildWorker(
            workDataOf(LyricsFetchWorker.KEY_TRACK_ID to 1L),
            trackDao,
            repo,
            runAttemptCount = 0,
        )
        val result = runBlocking { worker.doWork() }
        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    fun `transient failure after MAX_ATTEMPTS returns success (leaves NULL)`() {
        val trackDao = mockk<TrackDao>()
        val repo = mockk<LyricsRepository>()
        coEvery { trackDao.getById(1L) } returns sampleTrack(1L)
        coEvery { repo.resolveAndStore(any()) } throws RuntimeException("network")
        val worker = buildWorker(
            workDataOf(LyricsFetchWorker.KEY_TRACK_ID to 1L),
            trackDao,
            repo,
            runAttemptCount = 5,
        )
        val result = runBlocking { worker.doWork() }
        assertEquals(ListenableWorker.Result.success(), result)
    }

    private fun buildWorker(
        inputData: androidx.work.Data,
        trackDao: TrackDao,
        repo: LyricsRepository,
        runAttemptCount: Int = 0,
    ): LyricsFetchWorker = TestListenableWorkerBuilder<LyricsFetchWorker>(context)
        .setInputData(inputData)
        .setRunAttemptCount(runAttemptCount)
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = LyricsFetchWorker(appContext, workerParameters, repo, trackDao)
        })
        .build()

    private fun sampleTrack(id: Long) = TrackEntity(
        id = id,
        title = "T",
        artist = "A",
        album = "AL",
        albumArtist = "",
        filePath = "/x/y.opus",
        durationMs = 200_000,
    )
}
