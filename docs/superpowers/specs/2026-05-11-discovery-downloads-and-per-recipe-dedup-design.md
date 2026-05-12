# Discovery Downloads + Per-Recipe Dedup Design

> **Status:** Design — pending implementation plan.
> **Scope:** PR 4 of the post-v0.9.19 audit (PR 1: snackbar lifecycle + orphan-FK fix; PR 3: recipe pivot to 85% discovery + batch-mode cross-mix dedup). PR 4 closes two remaining symptoms surfaced after the PR 3 install: per-recipe refresh still produces overlapping mixes, and recommendation tracks are present-but-unplayable because the existing download pipeline is sync-gated.
> **Related:** continues from `2026-05-11-stash-mix-recommendation-pivot-design.md`.

---

## Goal

After PR 3 install, the user observed three concrete problems:

1. **Daily Discover and Deep Cuts still share top tracks** ("Hold it Down" by Jerreau was #1 in both; "Lye" by Earl Sweatshirt appeared in both). The cross-mix dedup we shipped only fires when the worker is invoked in batch mode (full sweep). Per-recipe refresh — the path that fires when the user long-presses → "Refresh this mix" — bypasses dedup entirely.
2. **First Listen says it refreshed but didn't change.** The 42 tracks shown are discovery stubs (TrackEntity rows with `isDownloaded = false`); none are playable.
3. **Daily Discover has 39 tracks but only 4 are playable.** Same root cause — discovery survivors are marked `STATUS_DONE` in `discovery_queue` the moment the stub TrackEntity is inserted, but the actual download requires `TrackDownloadWorker` which hard-rejects work without a `syncId` (`TrackDownloadWorker.kt:87-91`). The download stays queued indefinitely unless the user manually triggers a sync.

This PR fixes both at the root:

- **Per-recipe refresh respects cross-mix dedup** by pre-populating `excludeIds` from the OTHER builtin mixes' current playlist contents.
- **Discovery candidates actually download** via a new `DiscoveryDownloadWorker` that drains `download_queue WHERE syncId IS NULL` using the existing `TrackDownloader` abstraction. Chained from the tail of `StashDiscoveryWorker.doWork`, plus a final `StashMixRefreshWorker.enqueueOneTime` to re-materialize mixes once new tracks land.
- **Defense in depth**: `getDoneTrackIdsForRecipe` adds `AND t.is_downloaded = 1` so the mix never surfaces an undownloaded stub even in a transient window.

## Non-goals

- **Modifying `TrackDownloadWorker` to accept `syncId = null`.** Explicitly rejected — the sync chain is working and the cost of a subtle sync regression is higher than the cost of a parallel worker.
- **Modifying `StashDiscoveryWorker`'s "mark DONE before file lands" semantic.** The DONE rows are still useful as a discovery-history record (per-recipe-weekly cap, blocklist guard, etc.); we just stop surfacing them in the playlist until they're playable.
- **A sync-history entry for discovery downloads.** No coupling to `SyncHistoryEntity`, `SyncStateManager`, or `SyncNotificationManager`. Discovery downloads are not "syncs" in the user's mental model.
- **A UI surface for in-flight discovery downloads.** Out of scope; the foreground-service notification already gives the user signal that work is happening.
- **A "discovery-pending" filter chip on the mix screen.** YAGNI — once Path B's `is_downloaded = 1` filter is in place, mixes show only playable tracks; phantom UI states aren't a thing.
- **Pre-seeding discovery_queue inline on retune (deferred from PR 3).** Still rejected.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│ core:data  ::  PlaylistDao                                              │
│   NEW: getTrackIdsForPlaylists(playlistIds: List<Long>): List<Long>     │
│                                                                         │
│ core:data  ::  DownloadQueueDao                                         │
│   NEW: pendingDiscoveryDownloads(): List<DownloadQueueEntity>           │
│        (sync_id IS NULL AND status IN ('PENDING', 'FAILED' < 3 retry)) │
│   MODIFY: getAllPendingBySources + getRetryableBySources add            │
│           AND dq.sync_id IS NOT NULL — partitions sync-chain workers    │
│           off discovery rows (Failure mode #1).                         │
│                                                                         │
│ core:data  ::  DiscoveryQueueDao  (PR 1 Task 8 extension)               │
│   getDoneTrackIdsForRecipe: add AND t.is_downloaded = 1 to WHERE.       │
│                                                                         │
│ core:data  ::  StashMixRefreshWorker                                    │
│   doWork: when targetId > 0 (single-recipe path), pre-populate          │
│           excludeIds from other builtin mixes' current playlist         │
│           contents via the new PlaylistDao query.                       │
│                                                                         │
│ core:data  ::  StashDiscoveryWorker                                     │
│   doWork end: enqueue DiscoveryDownloadWorker.enqueueOneTime(context)   │
│                                                                         │
│ data:download  ::  DiscoveryDownloadWorker  (NEW)                       │
│   doWork:                                                               │
│     1. Promote to foreground service via inline ForegroundInfo builder  │
│     2. Drain pendingDiscoveryDownloads()                                │
│     3. For each: same per-track flow as TrackDownloadWorker (blocklist  │
│        guard → TrackDownloader.downloadTrack → mark COMPLETED/FAILED    │
│        + isDownloaded=true on success, with format/quality + duration   │
│        reconciliation)                                                  │
│     4. Always enqueue StashMixRefreshWorker.enqueueOneTime so the       │
│        mixes re-materialize with the newly-downloaded survivors.        │
└─────────────────────────────────────────────────────────────────────────┘
```

No schema migration. Room schema v22 stays. All changes are new queries, new worker, tightened existing queries, and a single-line partition predicate on the two sync feeders.

## Component 1 — Per-recipe dedup (Bug A)

`core/data/src/main/kotlin/com/stash/core/data/db/dao/PlaylistDao.kt`. Add:

```kotlin
/**
 * Returns DISTINCT track ids that appear in any of the given playlists.
 * Used by [com.stash.core.data.sync.workers.StashMixRefreshWorker]'s
 * single-recipe refresh path to seed `excludeIds` from the user's
 * currently-materialized OTHER mixes — without this, a manual refresh
 * of one mix sees an empty exclude set and naturally produces overlap
 * with the others (the very symptom PR 3's batch-mode dedup was meant
 * to fix).
 */
@Query("SELECT DISTINCT track_id FROM playlist_tracks WHERE playlist_id IN (:playlistIds)")
suspend fun getTrackIdsForPlaylists(playlistIds: List<Long>): List<Long>
```

`core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorker.kt`, modify `doWork` around lines 219-225 (just before the `val orderedRecipes = active.sortedBy { ... }` line, after the `active` list is constructed):

```kotlin
val orderedRecipes = active.sortedBy { recipeDedupPriority(it) }
val excludeIds = mutableSetOf<Long>()

// v0.9.20 follow-up: single-recipe path needs explicit seeding from the
// OTHER mixes' current playlist contents. The batch-mode loop accumulates
// excludeIds naturally as it iterates; the single-element loop has nothing
// to accumulate, so we seed it manually from the materialized state of the
// other builtin mixes. Effect: manual refresh of one mix no longer overlaps
// with whatever is currently in the others.
if (targetId > 0L) {
    val otherPlaylistIds = recipeDao.getActive()
        .filter { it.id != targetId && it.playlistId != null }
        .mapNotNull { it.playlistId }
    if (otherPlaylistIds.isNotEmpty()) {
        excludeIds += playlistDao.getTrackIdsForPlaylists(otherPlaylistIds)
    }
}

for (recipe in orderedRecipes) {
    val excludeSnapshot = excludeIds.toSet()
    val tracks = mixGenerator.generate(recipe, excludeSnapshot)
    // ... rest unchanged
}
```

**Why this works correctly across the three single-recipe refreshes:**
- User refreshes Daily Discover → reads current Deep Cuts + First Listen playlists, picks fresh tracks not in either.
- User refreshes Deep Cuts → reads current Daily Discover (now updated) + First Listen, picks fresh tracks not in either.
- User refreshes First Listen → reads current Daily Discover + Deep Cuts (both now updated), picks fresh tracks not in either.

After one round of refreshes, the three mixes naturally diverge. Subsequent refreshes maintain the divergence as long as each recipe has enough fresh discovery survivors to pick from.

## Component 2 — `DiscoveryDownloadWorker` (Bugs B + C)

`data/download/src/main/kotlin/com/stash/data/download/DiscoveryDownloadWorker.kt` (NEW).

### 2a. New `DownloadQueueDao` query + partition predicate on existing sync feeders

**The new query** in `core/data/src/main/kotlin/com/stash/core/data/db/dao/DownloadQueueDao.kt`:

```kotlin
/**
 * Discovery rows queued for download — `sync_id IS NULL` partitions
 * them away from sync-chain TrackDownloadWorker (which after the
 * v0.9.20 partition predicate only touches rows with non-null sync_id).
 *
 * Includes FAILED rows with retry_count < 3 so a transient network
 * blip doesn't permanently sideline a discovery candidate — matches
 * the retry posture of TrackDownloadWorker's getRetryableBySources.
 *
 * Filtered to exclude WAITING_FOR_LOSSLESS (owned by LosslessRetryWorker)
 * and IN_PROGRESS / COMPLETED (already running or done).
 */
@Query(
    """
    SELECT * FROM download_queue
    WHERE sync_id IS NULL
      AND (status = 'PENDING' OR (status = 'FAILED' AND retry_count < 3))
    ORDER BY id ASC
    """
)
suspend fun pendingDiscoveryDownloads(): List<DownloadQueueEntity>
```

**Partition predicate on the existing sync feeders** — required to honor the "zero risk to sync path" promise. Without this, `TrackDownloadWorker` would race the new worker on the same rows (both queries match discovery rows today because STASH_MIX playlists are created with `syncEnabled = true` and discovery stubs have `source = MusicSource.YOUTUBE`).

In `getAllPendingBySources` (lines 76-95), add `AND dq.sync_id IS NOT NULL` to the WHERE clause:

```kotlin
WHERE dq.status = 'PENDING'
  AND dq.sync_id IS NOT NULL   -- v0.9.20: partition off discovery rows
  AND t.source IN (:sources)
  ...
```

In `getRetryableBySources` (lines 105-124), the same predicate:

```kotlin
WHERE dq.status = 'FAILED' AND dq.retry_count < 3
  AND dq.sync_id IS NOT NULL   -- v0.9.20: partition off discovery rows
  AND t.source IN (:sources)
  ...
```

**Why these are safe edits to the sync path:**
- Every sync-initiated row in `download_queue` has a non-null `sync_id` (set by `DiffWorker` when it queues the row).
- Every discovery-initiated row has `sync_id = null` (set by `StashDiscoveryWorker.handle()` at line 222).
- The predicate makes explicit a partition that was previously implicit (discovery rows happened to be drained by sync only because nothing prevented it).
- Sync's actual behavior on its own rows is unchanged.

**Risk assessment:** if there are any in-the-wild rows where `sync_id IS NULL AND source IN (Spotify, YouTube)` that don't fall under "discovery" (i.e., some other producer puts rows with null sync_id), those would stop being drained by sync. After grepping the codebase, the only producers of `download_queue` rows are `DiffWorker` (sets sync_id) and `StashDiscoveryWorker.handle` (sets sync_id = null). So the partition is exhaustive.

### 2b. The worker

```kotlin
package com.stash.data.download

import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.stash.core.data.audio.AudioDurationExtractor
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.mapper.toDomain
import com.stash.core.data.sync.SyncNotificationManager
import com.stash.core.data.sync.TrackDownloadOutcome
import com.stash.core.data.sync.TrackDownloader
import com.stash.core.data.sync.workers.StashMixRefreshWorker
import com.stash.core.model.DownloadFailureType
import com.stash.core.model.DownloadStatus
import com.stash.core.model.Track
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File

/**
 * Drains `download_queue` rows produced by [StashDiscoveryWorker]
 * (`sync_id IS NULL`). Parallels [TrackDownloadWorker]'s per-track
 * flow (blocklist guard → [TrackDownloader.downloadTrack] → mark
 * COMPLETED with isDownloaded=true + filePath, or FAILED with retry
 * accounting) without the sync-history coupling that worker requires.
 *
 * Chained from the tail of [StashDiscoveryWorker.doWork] (REPLACE
 * policy on a unique work name so a rapid discovery + drain cycle
 * doesn't double-run). At the end of the drain, enqueues a one-shot
 * [StashMixRefreshWorker] so mixes re-materialize and the user sees
 * the newly-downloaded survivors without manual refresh.
 *
 * Foreground-service promotion via [getForegroundInfo] is the same
 * pattern TrackDownloadWorker uses — required for long batches that
 * outlive normal background limits.
 */
@HiltWorker
class DiscoveryDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val downloadQueueDao: DownloadQueueDao,
    private val trackDao: TrackDao,
    private val trackDownloader: TrackDownloader,
    private val audioDurationExtractor: AudioDurationExtractor,
    private val blocklistGuard: BlocklistGuard,
    private val syncNotificationManager: SyncNotificationManager,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val UNIQUE_WORK_NAME = "discovery_download"
        private const val TAG = "DiscoveryDownload"

        /**
         * Constraints mirror TrackDownloadWorker's posture: respects the
         * user's DownloadNetworkMode preference. Wire whatever
         * constraint-builder the existing sync chain uses — search the
         * codebase for `NetworkType.UNMETERED` and `downloadNetworkPreference`
         * to find the existing helper.
         */
        fun enqueueOneTime(context: Context, constraints: Constraints) {
            val work = OneTimeWorkRequestBuilder<DiscoveryDownloadWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                work,
            )
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return buildForegroundInfo("Downloading discoveries", "Preparing\u2026", progress = -1f)
    }

    /**
     * Inline mirror of [TrackDownloadWorker]'s private `createForegroundInfo`
     * helper (which lives at lines 497-515 of that file). Duplicated rather
     * than promoted to a public method on `SyncNotificationManager` because
     * the WorkManager cancel intent is per-worker-instance and SyncNotification
     * Manager is a singleton.
     */
    private fun buildForegroundInfo(
        title: String,
        text: String,
        progress: Float,
    ): ForegroundInfo {
        val notification = syncNotificationManager.buildProgressNotification(
            title = title,
            text = text,
            progress = progress,
            cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
        )
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(
                SyncNotificationManager.NOTIFICATION_ID_PROGRESS,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(SyncNotificationManager.NOTIFICATION_ID_PROGRESS, notification)
        }
    }

    override suspend fun doWork(): Result {
        setForeground(getForegroundInfo())

        val pending = downloadQueueDao.pendingDiscoveryDownloads()
        if (pending.isEmpty()) {
            // Still chain the refresh — keeps the contract simple
            // (the mix-refresh worker is a no-op when nothing changed).
            chainRefresh()
            return Result.success()
        }

        Log.i(TAG, "draining ${pending.size} discovery download(s)")

        for ((index, queueItem) in pending.withIndex()) {
            // Progress notification update
            setForeground(
                buildForegroundInfo(
                    title = "Downloading discoveries",
                    text = "${index + 1} of ${pending.size}",
                    progress = (index.toFloat() / pending.size),
                )
            )

            // v0.9.20: stamp IN_PROGRESS BEFORE invoking the downloader.
            // REPLACE policy on UNIQUE_WORK_NAME prevents concurrent instances
            // of THIS worker; the sync-side partition predicate prevents
            // TrackDownloadWorker from touching the same row. Defense-in-depth:
            // a stale IN_PROGRESS row left over from a crashed run gets reset
            // to PENDING on the next sync (the existing `resetStaleInProgress`
            // sweep in TrackDownloadWorker's startup) — that path is unchanged.
            downloadQueueDao.updateStatus(
                id = queueItem.id,
                status = DownloadStatus.IN_PROGRESS,
            )

            val trackEntity = trackDao.getById(queueItem.trackId) ?: continue

            // Blocklist guard (mirror TrackDownloadWorker)
            if (blocklistGuard.isBlocked(
                    artist = trackEntity.artist,
                    title = trackEntity.title,
                    spotifyUri = null,
                    youtubeId = null,
                )) {
                Log.d(TAG, "Skipping blocked track ${trackEntity.id}")
                downloadQueueDao.deleteByTrackId(trackEntity.id)
                continue
            }

            // Already downloaded? Idempotent COMPLETED stamp.
            if (trackEntity.isDownloaded && trackEntity.filePath != null) {
                downloadQueueDao.updateStatus(
                    id = queueItem.id,
                    status = DownloadStatus.COMPLETED,
                    completedAt = System.currentTimeMillis(),
                )
                continue
            }

            val track = trackEntity.toDomain()
            val outcome = runCatching {
                trackDownloader.downloadTrack(track = track, preResolvedUrl = queueItem.youtubeUrl)
            }.getOrElse {
                Log.e(TAG, "downloadTrack threw for ${track.artist} - ${track.title}", it)
                TrackDownloadOutcome.Failed(error = it.message.orEmpty())
            }

            when (outcome) {
                is TrackDownloadOutcome.Success -> handleSuccess(queueItem, trackEntity, outcome)
                is TrackDownloadOutcome.Unmatched -> handleUnmatched(queueItem, track, outcome)
                is TrackDownloadOutcome.Failed -> handleFailed(queueItem, track, outcome)
                is TrackDownloadOutcome.Deferred -> {
                    // Lossless deferred — TrackDownloaderImpl already moved
                    // the row to WAITING_FOR_LOSSLESS. LosslessRetryWorker
                    // owns the re-attempt. No-op here.
                    Log.i(TAG, "Deferred (waiting for lossless): ${track.artist} - ${track.title}")
                }
            }
        }

        chainRefresh()
        return Result.success()
    }

    private suspend fun handleSuccess(
        queueItem: DownloadQueueEntity,
        trackEntity: TrackEntity,
        outcome: TrackDownloadOutcome.Success,
    ) {
        val fileSize = try { File(outcome.filePath).length() } catch (_: Exception) { 0L }
        val meta = audioDurationExtractor.extract(outcome.filePath)

        trackDao.markAsDownloaded(
            trackId = trackEntity.id,
            filePath = outcome.filePath,
            fileSizeBytes = fileSize,
            sampleRateHz = meta?.sampleRateHz,
            bitsPerSample = meta?.bitsPerSample,
        )

        if (meta != null && meta.format != "unknown") {
            runCatching {
                trackDao.setFormatAndQuality(
                    trackId = trackEntity.id,
                    fileFormat = meta.format,
                    qualityKbps = meta.bitrateKbps,
                )
            }
        }

        downloadQueueDao.updateStatus(
            id = queueItem.id,
            status = DownloadStatus.COMPLETED,
            completedAt = System.currentTimeMillis(),
        )
    }

    private suspend fun handleUnmatched(
        queueItem: DownloadQueueEntity,
        track: Track,
        outcome: TrackDownloadOutcome.Unmatched,
    ) {
        val err = "No YouTube match for: ${track.artist} - ${track.title}"
        Log.w(TAG, err)
        downloadQueueDao.incrementRetryCount(queueItem.id)
        downloadQueueDao.updateStatus(
            id = queueItem.id,
            status = DownloadStatus.FAILED,
            failureType = DownloadFailureType.NO_MATCH,
            errorMessage = err,
            rejectedVideoId = outcome.rejectedVideoId,
        )
    }

    private suspend fun handleFailed(
        queueItem: DownloadQueueEntity,
        track: Track,
        outcome: TrackDownloadOutcome.Failed,
    ) {
        Log.e(TAG, "Download failed for ${track.artist} - ${track.title}: ${outcome.error}")
        downloadQueueDao.incrementRetryCount(queueItem.id)
        downloadQueueDao.updateStatus(
            id = queueItem.id,
            status = DownloadStatus.FAILED,
            failureType = DownloadFailureType.DOWNLOAD_ERROR,
            errorMessage = outcome.error.take(500),
        )
    }

    private fun chainRefresh() {
        // Always chain — even on all-failure batches. The mix-refresh
        // worker is a no-op when nothing new is on disk; simpler to
        // unconditionally fire than to branch.
        StashMixRefreshWorker.enqueueOneTime(applicationContext)
    }
}
```

**Code-duplication note:** the `handleSuccess` / `handleUnmatched` / `handleFailed` methods mirror logic in `TrackDownloadWorker.kt:262-380`. We intentionally duplicate rather than refactor: TrackDownloadWorker's per-track block is inside a `coroutineScope.launch` parallel-execution context with shared atomic counters and sync-state callbacks; surgically extracting it would touch the sync chain. The duplication is bounded (~80 lines), the contract surface (TrackDownloader interface) is stable, and any future divergence between sync and discovery download behaviors is easier to reason about with two clear sites.

### 2c. `StashDiscoveryWorker` chains the downloader

`core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashDiscoveryWorker.kt`, at the end of `doWork()` after the existing recipe loop:

```kotlin
// v0.9.20: after queueing/processing discoveries, kick the downloader
// so the new tracks become playable in this charging+WiFi window.
// Mirror this worker's own constraints (charging + batteryNotLow +
// NetworkType.UNMETERED) — discovery downloads should respect the same
// posture that gated the discovery itself.
val downloadConstraints = Constraints.Builder()
    .setRequiresCharging(true)
    .setRequiresBatteryNotLow(true)
    .setRequiredNetworkType(NetworkType.UNMETERED)
    .build()
DiscoveryDownloadWorker.enqueueOneTime(applicationContext, downloadConstraints)
```

**Required new imports** in `StashDiscoveryWorker.kt`:

```kotlin
import androidx.work.Constraints
import androidx.work.NetworkType
import com.stash.data.download.DiscoveryDownloadWorker
```

The first two may already be imported via existing scheduling helpers — check before adding to avoid duplicates. The third is the new worker class.

**On constraint choice:** the spec earlier said "respects DownloadNetworkMode pref." After re-reading the existing infrastructure, the simpler honest answer is: StashDiscoveryWorker already runs on charging + battery-not-low + WiFi, so the downloader chained from its tail runs in the same window. The user pref governs sync downloads (which respond to user-driven sync actions); discovery is opportunistic by design. Pinning the downloader to the discovery-worker's constraints is consistent and avoids a new dependency on `downloadNetworkPreference`. If we later want this to vary, it's a one-line change to the constraint builder.

### 2d. `applicationContext` access in the worker

`CoroutineWorker.applicationContext` is exposed via the `appContext` constructor parameter (already standard in this codebase — `StashDiscoveryWorker` uses the same pattern). Use it directly in `chainRefresh()`.

## Component 3 — `is_downloaded = 1` filter (Path B, defense-in-depth)

`core/data/src/main/kotlin/com/stash/core/data/db/dao/DiscoveryQueueDao.kt` lines 92-102 currently has the v0.9.20 query from PR 1 Task 8. Extend:

```kotlin
@Query(
    """
    SELECT dq.track_id FROM discovery_queue dq
    INNER JOIN tracks t ON t.id = dq.track_id
    WHERE dq.recipe_id = :recipeId
      AND dq.status = 'DONE'
      AND dq.track_id IS NOT NULL
      AND t.is_downloaded = 1
    ORDER BY dq.completed_at DESC
    LIMIT :limit
    """
)
suspend fun getDoneTrackIdsForRecipe(recipeId: Long, limit: Int): List<Long>
```

KDoc gets an additional paragraph:

```
 * v0.9.20 follow-up: AND t.is_downloaded = 1 ensures the mix never
 * surfaces a stub TrackEntity (a discovery row that was marked DONE
 * by StashDiscoveryWorker but whose file hasn't landed on disk yet).
 * Without this filter, a transient window between StashDiscoveryWorker
 * completion and DiscoveryDownloadWorker completion would put unplayable
 * tracks in the playlist. DiscoveryDownloadWorker fixes the underlying
 * issue (downloads run promptly); this filter is defense-in-depth.
```

### 3a. Existing test updates

`core/data/src/test/kotlin/com/stash/core/data/db/dao/DiscoveryQueueDaoCapTest.kt` already has 6 tests after PR 1 Task 8. The current `trackEntity(id: Long)` helper relies on `TrackEntity` defaults — `isDownloaded` defaults to `false`. With the new filter, all existing test inserts would return empty results.

**Fix:** update the helper to default to `isDownloaded = true`:

```kotlin
private fun trackEntity(id: Long, isDownloaded: Boolean = true) = TrackEntity(
    id = id,
    title = "Track $id",
    artist = "Artist $id",
    canonicalTitle = "track $id",
    canonicalArtist = "artist $id",
    isDownloaded = isDownloaded,   // NEW
)
```

Then add a new test:

```kotlin
@Test fun `excludes rows whose track is not yet downloaded`() = runTest {
    db.trackDao().insert(trackEntity(id = 1L, isDownloaded = true))
    db.trackDao().insert(trackEntity(id = 2L, isDownloaded = false))  // stub
    dao.insertIfNew(doneRow(recipeId, trackId = 1L, completedAt = 1000L))
    dao.insertIfNew(doneRow(recipeId, trackId = 2L, completedAt = 2000L))

    val result = dao.getDoneTrackIdsForRecipe(recipeId, limit = 99)

    assertEquals(listOf(1L), result)
}
```

## Migration story

No schema migration. Room schema v22 stays. Existing installs pick up the changes on next cold start:

1. `StashApplication.onCreate` enqueues the existing `StashMixRefreshWorker.enqueueOneTime` (already in place).
2. The first mix refresh now runs against the tightened `getDoneTrackIdsForRecipe` (Component 3) — stubs are filtered, mixes shrink to only playable tracks.
3. `StashDiscoveryWorker` (which already runs daily on charging + WiFi) now chains `DiscoveryDownloadWorker` at the end. Within one charging window, the discovery_queue's PENDING-with-null-syncId backlog drains into actual downloads.
4. After the drain, the chained `StashMixRefreshWorker.enqueueOneTime` re-materializes mixes against the now-downloaded survivors — mixes fill out to their target sizes (~40 tracks for Daily Discover + Deep Cuts; ~50 for First Listen).
5. Per-recipe refresh (Component 1) immediately respects the other mixes' contents, so manual long-press refreshes converge to non-overlapping mixes within one round.

## Tests

### Robolectric + Room in-memory (DAO behavior)

- **`PlaylistDaoOtherMixTracksTest`** (new): verify `getTrackIdsForPlaylists` returns DISTINCT ids; verify empty input returns empty list; verify a track appearing in two playlists shows once.
- **`DiscoveryQueueDaoCapTest`** (extend): update `trackEntity` helper to default `isDownloaded = true`; add the "stub track excluded" test (Component 3a).

### MockK (worker behavior)

- **`StashMixRefreshWorkerPerRecipeDedupTest`** (new): hand-construct the worker; stub `recipeDao.getActive()` to return 3 recipes (two with playlistIds, one without); stub `playlistDao.getTrackIdsForPlaylists` to return a known set; invoke `doWork` with `KEY_RECIPE_ID = 1L`; capture `excludeIds` passed to `mixGenerator.generate(recipe1, ...)` and assert it equals the pre-seeded set. Mirrors the precedent at `StashMixRefreshWorkerDedupTest` from PR 3.
- **`DiscoveryDownloadWorkerTest`** (new): cases:
  - `empty queue → Result.success, refresh still chained`
  - `single PENDING row, TrackDownloader returns Success → marks COMPLETED + isDownloaded=true`
  - `single PENDING row, TrackDownloader returns Failed → marks FAILED with retry increment, isDownloaded stays false`
  - `single PENDING row, TrackDownloader returns Deferred → row untouched (status stays as TrackDownloaderImpl set it — WAITING_FOR_LOSSLESS)`
  - `blocked track → row deleted via deleteByTrackId, TrackDownloader NOT called`
  - `chainRefresh always enqueues StashMixRefreshWorker, even on all-failure batches`

## Failure modes + edge cases

1. **`DiscoveryDownloadWorker` and `TrackDownloadWorker` race on `download_queue`.** Mitigated by the partition predicates added in Component 2a — `TrackDownloadWorker`'s feeders (`getAllPendingBySources`, `getRetryableBySources`) gain `AND dq.sync_id IS NOT NULL`; `DiscoveryDownloadWorker`'s feeder (`pendingDiscoveryDownloads`) requires `sync_id IS NULL`. The two workers operate on disjoint row sets. `DiscoveryDownloadWorker` additionally stamps `IN_PROGRESS` before each download as defense-in-depth.

2. **A discovery row was canonical-matched to an existing library track during `StashDiscoveryWorker.handle()`.** That row's `trackId` points to an already-downloaded track row (`isDownloaded = true`); the worker's "already downloaded? idempotent COMPLETED stamp" branch handles it cleanly.

3. **`StashDiscoveryWorker` enqueues `DiscoveryDownloadWorker` but the downloader's constraints aren't met immediately.** Constraints match the discovery worker's own (charging + batteryNotLow + UNMETERED). If the user unplugs mid-flight, JobScheduler defers the downloader until conditions are met again. Acceptable — the work is opportunistic by design.

4. **`StashMixRefreshWorker.enqueueOneTime` chained at the end of `DiscoveryDownloadWorker` runs in parallel with any in-flight per-recipe refresh.** The chained call uses the no-arg overload of `enqueueOneTime` (unique work name `stash_mix_refresh_oneshot`) while a manual long-press refresh uses `enqueueOneTime(context, recipeId)` (unique work name `stash_mix_refresh_oneshot_$recipeId`). The two have **different** unique work names, so they do NOT coalesce — both run independently. This is the right behavior: the user's per-recipe refresh updates ONE mix; the chained full refresh updates ALL mixes against the newly-downloaded survivors. The mix the user refreshed manually gets re-written by whichever finishes second, but both wrote correct content so there's no race-induced inconsistency.

5. **Per-recipe refresh with all three mixes having empty playlists (first cold-start install).** `recipeDao.getActive().mapNotNull { it.playlistId }` returns an empty list; `getTrackIdsForPlaylists(emptyList())` is skipped via the `if (otherPlaylistIds.isNotEmpty())` guard. `excludeIds` stays empty for the first round of refreshes — acceptable since there's no overlap to dedup against.

6. **A user-created (non-builtin) recipe with name colliding with a builtin.** The dedup ordering keys by name (PR 3 design), the per-recipe seed reads `recipeDao.getActive()` (all active recipes, not just builtins). So a user recipe participates in the exclude set on either side. Probably the right behavior — if you have a "Daily Discover" custom and the builtin, you don't want them overlapping either.

7. **Discovery download succeeds but the chained mix-refresh runs before the file is fully flushed to disk.** `markAsDownloaded` writes `isDownloaded = true` synchronously after `audioDurationExtractor.extract` returns (which reads the file). The file is committed before the row write. By the time `StashMixRefreshWorker` reads, the JOIN to `tracks` returns the new state.

8. **`pendingDiscoveryDownloads()` returns a very large list (say, 100+).** Foreground service handles long-running execution; WorkManager's default 10-minute limit doesn't apply once `setForeground` is called. Progress notification updates per-track keep the user informed.

9. **A discovery_queue DONE row whose track_id was deleted from `tracks` after Component 3's JOIN filter.** Same orphan-FK case PR 1 Task 8 already addresses — INNER JOIN excludes it from the result.

## Rollout

Single APK install on Pixel, manual verification:
1. Trigger Daily Discover refresh. Note its first track.
2. Trigger Deep Cuts refresh. Verify its first track is DIFFERENT from Daily Discover's.
3. Trigger First Listen refresh. Same — different top.
4. Plug in to charge + WiFi. Wait for StashDiscoveryWorker to fire (or trigger via dev menu if available).
5. Watch logcat for `DiscoveryDownload` lines (`draining N discovery download(s)`, progress per track).
6. After the drain, the chained refresh fires automatically. Re-open each mix and verify track counts shrink to "playable count" (e.g., Daily Discover goes from 39 phantom to ~20-40 real).
7. Tap several tracks in each mix — every one should play.

No push, no tag. Per memory `feedback_ship_terminology.md`, release happens separately after on-device verification.

## Open questions

None. All design decisions resolved during brainstorming:
- Path C2 (new worker, not modify TrackDownloadWorker) — user picked **b**.
- Constraint posture (mirror StashDiscoveryWorker's charging+UNMETERED) — user picked **a** (match TrackDownloadWorker's existing behavior; the simpler reading lands on StashDiscoveryWorker's constraints since the downloader is chained from there).
- Always-chain the refresh after the drain (vs. only on ≥1 success) — user picked **b**.
