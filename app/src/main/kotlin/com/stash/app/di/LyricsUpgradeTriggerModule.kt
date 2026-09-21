package com.stash.app.di

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OutOfQuotaPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.stash.data.download.lyrics.LyricsFetchStatus
import com.stash.data.download.lyrics.LyricsUpgradeTrigger
import com.stash.data.lyrics.worker.LyricsTtmlUpgradeWorker
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkManagerLyricsUpgradeTrigger @Inject constructor(
    @ApplicationContext private val context: Context,
) : LyricsUpgradeTrigger {

    override fun enqueueTtmlUpgrade() {
        // Silent on failure: the caller (retag / app start) has already done its real work.
        runCatching {
            val request = OneTimeWorkRequestBuilder<LyricsTtmlUpgradeWorker>()
                .setConstraints(
                    Constraints.Builder()
                        // ~50-100 KB of TTML per track: keep a whole-library backfill off mobile data.
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                LyricsTtmlUpgradeWorker.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }

    override fun enqueueManualFetch() {
        runCatching {
            val request = OneTimeWorkRequestBuilder<LyricsTtmlUpgradeWorker>()
                .setInputData(workDataOf(LyricsTtmlUpgradeWorker.KEY_MANUAL to true))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(
                    // The user asked for it: any connection will do.
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                LyricsTtmlUpgradeWorker.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }

    // Getter, not an initialised property: this singleton can be constructed during Application
    // injection, before WorkManager's Configuration.Provider (Hilt worker factory) is ready.
    override val status: Flow<LyricsFetchStatus>
        get() = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(LyricsTtmlUpgradeWorker.UNIQUE_WORK_NAME)
            .map { infos -> infos.lastOrNull { it.state != WorkInfo.State.CANCELLED }.toStatus() }

    private fun WorkInfo?.toStatus(): LyricsFetchStatus {
        val info = this ?: return LyricsFetchStatus.Idle
        return when (info.state) {
            WorkInfo.State.CANCELLED, WorkInfo.State.FAILED -> LyricsFetchStatus.Idle
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> LyricsFetchStatus.Queued
            WorkInfo.State.RUNNING -> LyricsFetchStatus.Running(
                done = info.progress.getInt(LyricsTtmlUpgradeWorker.KEY_DONE, 0),
                total = info.progress.getInt(LyricsTtmlUpgradeWorker.KEY_TOTAL, 0),
            )
            WorkInfo.State.SUCCEEDED -> LyricsFetchStatus.Done(
                upgraded = info.outputData.getInt(LyricsTtmlUpgradeWorker.OUT_UPGRADED, 0),
                fetched = info.outputData.getInt(LyricsTtmlUpgradeWorker.OUT_FETCHED, 0),
                notFound = info.outputData.getInt(LyricsTtmlUpgradeWorker.OUT_NOT_FOUND, 0),
                failed = info.outputData.getInt(LyricsTtmlUpgradeWorker.OUT_FAILED, 0),
                bailed = info.outputData.getBoolean(LyricsTtmlUpgradeWorker.OUT_BAILED, false),
            )
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LyricsUpgradeTriggerModule {
    @Binds
    abstract fun bindLyricsUpgradeTrigger(impl: WorkManagerLyricsUpgradeTrigger): LyricsUpgradeTrigger
}