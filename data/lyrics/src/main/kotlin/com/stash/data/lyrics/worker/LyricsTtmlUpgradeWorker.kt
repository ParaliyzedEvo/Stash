package com.stash.data.lyrics.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.stash.data.lyrics.LyricsRepository
import com.stash.data.lyrics.ManualFetchResult
import com.stash.data.lyrics.TtmlUpgradeResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Lyrics catch-up worker, two entry points sharing one unique-work name:
 *  - auto (once per version / after retag): only upgrades stored lyrics to word-synced TTML. Quiet.
 *  - manual ([KEY_MANUAL], Library Health button): the same upgrade PLUS a fetch for downloaded
 *    tracks that have no lyrics yet. Runs as a FOREGROUND service with a cancellable progress
 *    notification (no ~10 min background-job cap), and posts a summary notification when done.
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

    private var foregroundOk = true

    /** Required for expedited work on API < 31; also what [promoteToForeground] posts. */
    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0)

    override suspend fun doWork(): Result {
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        val toUpgrade = lyricsRepository.trackIdsPendingTtml()
        val toFetch = if (manual) lyricsRepository.trackIdsMissingLyrics() else emptyList()
        val total = toUpgrade.size + toFetch.size

        if (manual) promoteToForeground(0, total)

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

        suspend fun report() {
            setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
            if (manual) promoteToForeground(done, total)
        }

        fun bail(): Result {
            Log.w(TAG, "Bailing after $streak consecutive failures (done=$done/$total)")
            // Auto: let WorkManager back off and resume. Manual: report it, the user can tap again.
            if (!manual && runAttemptCount < MAX_ATTEMPTS) return Result.retry()
            if (manual) notifyDone(upgraded + fetched, notFound, bailed = true)
            return Result.success(summary(bailed = true))
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
            report()
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
            report()
            delay(REQUEST_SPACING_MS)
        }

        Log.i(TAG, "Lyrics run finished: upgraded=$upgraded fetched=$fetched notFound=$notFound failed=$failed of $total")
        if (manual) notifyDone(upgraded + fetched, notFound, bailed = false)
        return Result.success(summary(bailed = false))
    }

    // ── foreground / notifications ──────────────────────────────────────────────────────────────

    private suspend fun promoteToForeground(done: Int, total: Int) {
        if (!foregroundOk) return
        try {
            setForeground(foregroundInfo(done, total))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // e.g. foreground start not allowed from the background: carry on as a plain background job.
            foregroundOk = false
            Log.w(TAG, "Couldn't promote to foreground; continuing in the background", e)
        }
    }

    private fun foregroundInfo(done: Int, total: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        ensureChannel(nm)
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Fetching lyrics")
            .setContentText(if (total > 0) "$done / $total" else "Starting…")
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancel)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun notifyDone(updated: Int, notFound: Int, bailed: Boolean) {
        val nm = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(nm)
        val text = buildString {
            append(if (updated == 1) "1 track updated" else "$updated tracks updated")
            if (notFound > 0) append(", $notFound not found")
            if (bailed) append(". Stopped early: the lyrics service isn't responding")
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Lyrics")
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID_DONE, notification) }   // no-op without POST_NOTIFICATIONS
    }

    private fun ensureChannel(nm: NotificationManager?) {
        if (nm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Lyrics", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        private const val TAG = "LyricsTtmlUpgradeWorker"
        const val UNIQUE_WORK_NAME = "lyrics_ttml_upgrade"

        /** Input: true for the Library Health button (also fetches missing lyrics, runs foreground). */
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

        private const val CHANNEL_ID = "lyrics_fetch"
        private const val NOTIFICATION_ID = 9271
        private const val NOTIFICATION_ID_DONE = 9272

        private const val MAX_CONSECUTIVE_FAILURES = 5
        private const val MAX_ATTEMPTS = 3
        private const val REQUEST_SPACING_MS = 3_000L
    }
}