package com.stash.data.lyrics.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.stash.data.lyrics.LyricsRepository
import com.stash.data.lyrics.ManualFetchResult
import com.stash.data.lyrics.TtmlUpgradeResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay

/**
 * Lyrics catch-up worker, two entry points sharing one unique-work name:
 *  - auto (once per version / after retag): only upgrades stored lyrics to word-synced TTML.
 *  - manual ([KEY_MANUAL], Library Health button): the same upgrade PLUS a fetch for downloaded
 *    tracks that have no lyrics yet (never tried, or previously "No lyrics found", since the source
 *    chain now includes Apple).
 *
 * Only ever adds: a hit replaces the row + sidecars, a miss/failure leaves everything as it was.
 * Idempotent and resumable: each run recomputes what's pending. Paced at ~1 track / 3s (the iTunes
 * Search API is ~20 req/min and the lyrics API is a free community service). Bails after
 * [MAX_CONSECUTIVE_FAILURES] failures in a row: auto retries with backoff, manual reports it.
 */
@HiltWorker
class LyricsTtmlUpgradeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val lyricsRepository: LyricsRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        val toUpgrade = lyricsRepository.trackIdsPendingTtml()
        val toFetch = if (manual) lyricsRepository.trackIdsMissingLyrics() else emptyList()
        val total = toUpgrade.size + toFetch.size

        var done = 0
        var upgraded = 0
        var fetched = 0
        var notFound = 0
        var failed = 0
        var streak = 0

        fun summary(bailed: Boolean) = workDataOf(
            OUT_TOTAL to total,
            OUT_UPGRADED to upgraded,
            OUT_FETCHED to fetched,
            OUT_NOT_FOUND to notFound,
            OUT_FAILED to failed,
            OUT_BAILED to bailed,
        )

        fun bail(): Result {
            Log.w(TAG, "Bailing after $streak consecutive failures (done=$done/$total)")
            // Auto: let WorkManager back off and resume. Manual: report it, the user can tap again.
            return if (!manual && runAttemptCount < MAX_ATTEMPTS) Result.retry()
            else Result.success(summary(bailed = true))
        }

        for (trackId in toUpgrade) {
            done++
            when (lyricsRepository.upgradeToTtml(trackId)) {
                TtmlUpgradeResult.UPGRADED -> { upgraded++; streak = 0 }
                TtmlUpgradeResult.NO_TTML -> streak = 0
                TtmlUpgradeResult.FAILED -> {
                    failed++
                    if (++streak >= MAX_CONSECUTIVE_FAILURES) return bail()
                }
                TtmlUpgradeResult.SKIPPED -> continue          // no network used, no pacing needed
            }
            setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
            delay(REQUEST_SPACING_MS)
        }

        for (trackId in toFetch) {
            done++
            when (lyricsRepository.fetchLyricsNow(trackId)) {
                ManualFetchResult.FETCHED -> { fetched++; streak = 0 }
                ManualFetchResult.NOT_FOUND -> { notFound++; streak = 0 }
                ManualFetchResult.FAILED -> {
                    failed++
                    if (++streak >= MAX_CONSECUTIVE_FAILURES) return bail()
                }
                ManualFetchResult.SKIPPED -> continue
            }
            setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
            delay(REQUEST_SPACING_MS)
        }

        Log.i(TAG, "Lyrics run finished: upgraded=$upgraded fetched=$fetched notFound=$notFound failed=$failed of $total")
        return Result.success(summary(bailed = false))
    }

    companion object {
        private const val TAG = "LyricsTtmlUpgradeWorker"
        const val UNIQUE_WORK_NAME = "lyrics_ttml_upgrade"

        /** Input: true for the Library Health button (also fetches missing lyrics). */
        const val KEY_MANUAL = "manual"

        /** Progress keys. */
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"

        /** Output keys (final [Result.success] data). */
        const val OUT_TOTAL = "out_total"
        const val OUT_UPGRADED = "out_upgraded"
        const val OUT_FETCHED = "out_fetched"
        const val OUT_NOT_FOUND = "out_not_found"
        const val OUT_FAILED = "out_failed"
        const val OUT_BAILED = "out_bailed"

        private const val MAX_CONSECUTIVE_FAILURES = 5
        private const val MAX_ATTEMPTS = 3
        private const val REQUEST_SPACING_MS = 3_000L
    }
}