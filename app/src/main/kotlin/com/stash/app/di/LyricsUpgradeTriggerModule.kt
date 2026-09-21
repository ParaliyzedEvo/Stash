package com.stash.app.di

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.stash.data.download.lyrics.LyricsUpgradeTrigger
import com.stash.data.lyrics.worker.LyricsTtmlUpgradeWorker
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkManagerLyricsUpgradeTrigger @Inject constructor(
    @ApplicationContext private val context: Context,
) : LyricsUpgradeTrigger {
    override fun enqueueTtmlUpgrade() {
        // Silent on failure: the caller (retag) has already done its real work.
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
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LyricsUpgradeTriggerModule {
    @Binds
    abstract fun bindLyricsUpgradeTrigger(impl: WorkManagerLyricsUpgradeTrigger): LyricsUpgradeTrigger
}