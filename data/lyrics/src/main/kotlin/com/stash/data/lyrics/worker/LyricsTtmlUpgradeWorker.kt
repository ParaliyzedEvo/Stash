package com.stash.data.lyrics.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.stash.data.lyrics.LyricsRepository
import com.stash.data.lyrics.TtmlUpgradeResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay

/**
 * One-shot backfill that upgrades already-stored lyrics to word-synced TTML.
 *
 * Drains `LyricsRepository.trackIdsPendingTtml()` (real lyrics row, no TTML, never definitively
 * missed). Per track it only ever ADDS: a hit replaces the row + `.lrc` sidecar, a clean miss
 * stamps `ttml_checked_at` (so this terminates), a failure writes nothing and stays pending.
 * Idempotent: re-running just picks up whatever is still pending.
 *
 * Paced at ~1 track / 3s: the iTunes Search API is documented at roughly 20 searches/min, and
 * this is a free community API on the other side, so we're deliberately slow and polite.
 * Bails with retry after [MAX_CONSECUTIVE_FAILURES] failures in a row (paxsenix/iTunes down or
 * rate-limiting) instead of hammering.
 */
@HiltWorker
class LyricsTtmlUpgradeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val lyricsRepository: LyricsRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val pending = lyricsRepository.trackIdsPendingTtml()
        if (pending.isEmpty()) return Result.success()

        var upgraded = 0
        var noTtml = 0
        var consecutiveFailures = 0
        for ((index, trackId) in pending.withIndex()) {
            when (lyricsRepository.upgradeToTtml(trackId)) {
                TtmlUpgradeResult.UPGRADED -> { upgraded++; consecutiveFailures = 0 }
                TtmlUpgradeResult.NO_TTML -> { noTtml++; consecutiveFailures = 0 }
                TtmlUpgradeResult.SKIPPED -> continue          // no network used, no need to pace
                TtmlUpgradeResult.FAILED -> {
                    if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        Log.w(TAG, "Bailing after $consecutiveFailures consecutive failures (upgraded=$upgraded)")
                        // Rows stay pending; after MAX_ATTEMPTS we stop retrying this run and let the
                        // next enqueue (next retag / manual kick) resume where we left off.
                        return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
                    }
                }
            }
            setProgress(workDataOf(KEY_DONE to index + 1, KEY_TOTAL to pending.size))
            delay(REQUEST_SPACING_MS)
        }
        Log.i(TAG, "TTML upgrade finished: upgraded=$upgraded, noTtml=$noTtml, of ${pending.size}")
        return Result.success()
    }

    companion object {
        private const val TAG = "LyricsTtmlUpgradeWorker"
        const val UNIQUE_WORK_NAME = "lyrics_ttml_upgrade"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        private const val MAX_CONSECUTIVE_FAILURES = 5
        private const val MAX_ATTEMPTS = 3
        private const val REQUEST_SPACING_MS = 3_000L
    }
}