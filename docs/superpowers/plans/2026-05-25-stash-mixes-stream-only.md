# Stash Mixes — Stream-Only for New Discoveries Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Divert new Stash Mix discoveries from the download pipeline to the existing v0.9.30 streaming engine. Existing downloaded Mix tracks remain on disk and unchanged.

**Architecture:** Single decision point ("Seam A") in `StashDiscoveryWorker.handle()` skips `download_queue` insertion for Mix-recipe items and stamps the new `TrackEntity` with `isStreamable = true, isDownloaded = false`. Downstream consumers (`StashMixRefreshWorker.materializeMix`, `StashPlaybackService` queue filter, Mix tap-handlers) are updated to recognize stream-only tracks as first-class Mix members. Offline UX: Snackbar on tap, silent-skip on auto-advance.

**Tech Stack:** Kotlin coroutines, Room (DAOs + entities), Hilt (DI), Media3 (ExoPlayer/MediaSession), Compose (UI), JUnit + Turbine (tests).

**Spec:** `docs/superpowers/specs/2026-05-25-stash-mixes-stream-only-design.md`

---

## Pre-flight

Before starting Task 1, ensure the working tree is clean. The current `master` carries uncommitted Track B (scrobbler) and Track C (heart/download) fixes — those must be committed first so this plan's commits stay scoped to Track A. The recommended sequence is:

1. Commit Track B (scrobbler) — `feat(scrobbler): restore YT Music history sync (Songs filter + playbackContext)`
2. Commit Track C (heart/download/Liked-Songs count) — `fix(nowplaying): resolve streaming-track id mismatch for heart, download, count`
3. **Then** create a worktree for this Track A work: `git worktree add .worktrees/mixes-stream-only -b feat/mixes-stream-only master`. Copy `local.properties` into the worktree per `feedback_worktree_local_properties.md`.

All file paths below are relative to the worktree root.

## File map

| File | Action | Why |
|---|---|---|
| `core/data/.../db/dao/PlaylistDao.kt` | Modify | Add `getStreamableOrDoneTrackIdsForRecipe(recipeId)` query |
| `core/data/.../db/dao/PlaylistDao.kt` | Modify test | Cover the new query |
| `core/data/.../sync/workers/StashDiscoveryWorker.kt` | Modify | Seam A: skip `download_queue.insert` for STASH_MIX recipes; flag stub `isStreamable = true` |
| `core/data/.../sync/workers/StashMixRefreshWorker.kt` | Modify | Swap `getDoneTrackIdsForRecipe` → new DAO method in `materializeMix` |
| `core/media/.../service/StashPlaybackService.kt` | Modify (Task 4) | Two filter sites: `it.isDownloaded` → `it.isDownloaded \|\| it.isStreamable` |
| `core/media/.../service/StashPlaybackService.kt` | Modify (Task 6) | Add `EXTRA_TRACK_IS_STREAMABLE` companion constant + populate in `buildMediaItemForTrack` |
| `core/media/.../PlayerRepositoryImpl.kt` | Modify | Auto-advance silent-skip past stream-only tracks while offline |
| `feature/library/.../PlaylistDetailViewModel.kt` | Modify | Tap-time `MixOfflineTapGuard` (emit Snackbar when offline) |
| `feature/library/.../PlaylistDetailScreen.kt` | Modify | Wire SnackbarHost + collect events from VM |
| `feature/home/.../HomeViewModel.kt` (Mix shortcuts) | Modify (if Mix cards launch playback from Home) | Same tap-time guard pattern |

Test files:
- `core/data/src/test/.../db/dao/PlaylistDaoStreamableOrDoneTest.kt` (new)
- `core/data/src/test/.../sync/workers/StashDiscoveryWorkerStreamOnlyTest.kt` (new)
- `core/data/src/test/.../sync/workers/StashMixRefreshWorkerStreamOnlyMaterializeTest.kt` (new)
- `feature/library/src/test/.../MixOfflineTapGuardTest.kt` (new)

On-device verification (per `feedback_install_after_fix.md`):
- After every task that ships code into the worker pipeline, run `./gradlew :app:installDebug` and exercise the Mix path manually.

---

## Task 1: New DAO query — `getStreamableOrDoneTrackIdsForRecipe`

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/dao/PlaylistDao.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/db/dao/PlaylistDaoStreamableOrDoneTest.kt` (new)

- [ ] **Step 1: Write the failing test.** Test fixture: insert one `StashMixRecipeEntity`, three `TrackEntity` (one `isDownloaded=true, isStreamable=false`, one `isDownloaded=false, isStreamable=true`, one `isDownloaded=false, isStreamable=false`), link all three via `DiscoveryQueueEntity(status=DONE, recipeId=<recipe>)` to the recipe. Call `dao.getStreamableOrDoneTrackIdsForRecipe(recipeId)`. Expect the returned list to contain the first two ids only.

```kotlin
@Test
fun getStreamableOrDoneTrackIdsForRecipe_returnsDownloadedAndStreamable_skipsNeither() = runTest {
    val recipeId = recipeDao.insert(buildRecipe(name = "Test Mix"))
    val downloadedId = trackDao.insert(buildTrack(isDownloaded = true, isStreamable = false))
    val streamableId = trackDao.insert(buildTrack(isDownloaded = false, isStreamable = true))
    val neitherId = trackDao.insert(buildTrack(isDownloaded = false, isStreamable = false))
    listOf(downloadedId, streamableId, neitherId).forEach { trackId ->
        discoveryQueueDao.insert(buildDiscovery(recipeId = recipeId, trackId = trackId, status = DiscoveryQueueStatus.DONE))
    }

    val result = playlistDao.getStreamableOrDoneTrackIdsForRecipe(recipeId)

    assertThat(result).containsExactlyInAnyOrder(downloadedId, streamableId)
}
```

- [ ] **Step 2: Run the test, verify it fails.**

```
./gradlew :core:data:testDebugUnitTest --tests "*PlaylistDaoStreamableOrDoneTest*"
```

Expected: FAIL with "unresolved reference: getStreamableOrDoneTrackIdsForRecipe".

- [ ] **Step 3: Implement the DAO method.** Mirror the existing `getDoneTrackIdsForRecipe` query (find it via `grep -n "getDoneTrackIdsForRecipe" core/data/src/main/kotlin/com/stash/core/data/db/dao/PlaylistDao.kt`) and relax the predicate:

```kotlin
/**
 * Like [getDoneTrackIdsForRecipe] but also returns ids whose track row is
 * stream-only (`is_streamable = 1`, no `is_downloaded`). The Stash Mix
 * streaming-first rollout (v0.9.37) inserts stream-only stubs from
 * `StashDiscoveryWorker`; `StashMixRefreshWorker.materializeMix` must
 * surface both downloaded and streamable tracks when assembling the Mix.
 */
@Query("""
    SELECT t.id FROM tracks t
    INNER JOIN discovery_queue d ON d.track_id = t.id
    WHERE d.recipe_id = :recipeId
      AND d.status = 'DONE'
      AND (t.is_downloaded = 1 OR t.is_streamable = 1)
""")
suspend fun getStreamableOrDoneTrackIdsForRecipe(recipeId: Long): List<Long>
```

(Adjust column names to match the existing schema. Read `core/data/src/main/kotlin/com/stash/core/data/db/entity/DiscoveryQueueEntity.kt` for the exact column names.)

- [ ] **Step 4: Run the test, verify it passes.**

```
./gradlew :core:data:testDebugUnitTest --tests "*PlaylistDaoStreamableOrDoneTest*"
```

Expected: PASS.

- [ ] **Step 5: Commit.**

```
git add core/data/src/main/kotlin/com/stash/core/data/db/dao/PlaylistDao.kt
git add core/data/src/test/kotlin/com/stash/core/data/db/dao/PlaylistDaoStreamableOrDoneTest.kt
git commit -m "feat(playlist): PlaylistDao.getStreamableOrDoneTrackIdsForRecipe"
```

---

## Task 2: `StashDiscoveryWorker.handle` — skip download enqueue for Mix recipes

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashDiscoveryWorker.kt` (line ~278)
- Test: `core/data/src/test/kotlin/com/stash/core/data/sync/workers/StashDiscoveryWorkerStreamOnlyTest.kt` (new)

- [ ] **Step 1: Read context.** Open `StashDiscoveryWorker.kt:200-310`. Identify the exact `handle()` flow: how `recipeId` is reached, where the `TrackEntity` stub is built (around line 278-296), where `downloadQueueDao.insert(...)` fires. Confirm a recipe-type check is feasible (the recipe row has `type: PlaylistType` or similar — verify by reading `StashMixRecipeEntity.kt`).

- [ ] **Step 2: Write the failing test.**

```kotlin
@Test
fun handle_streamOnlyForStashMixRecipe_skipsDownloadQueueInsert() = runTest {
    val recipeId = recipeDao.insert(buildStashMixRecipe())
    val pendingId = discoveryQueueDao.insert(buildPending(recipeId = recipeId, /* artist+title */))

    worker.doWork()

    val track = trackDao.findByCanonicalIdentity(...)!!
    assertThat(track.isStreamable).isTrue()
    assertThat(track.isDownloaded).isFalse()

    val queueRows = downloadQueueDao.getAll()
    assertThat(queueRows).noneMatch { it.trackId == track.id }
}
```

- [ ] **Step 3: Run the test, verify it fails.**

```
./gradlew :core:data:testDebugUnitTest --tests "*StashDiscoveryWorkerStreamOnlyTest*"
```

Expected: FAIL with downloadQueueDao having an entry for the new track.

- [ ] **Step 4: Implement.** In `StashDiscoveryWorker.handle()` (line ~278), branch on the recipe's type:

```kotlin
val isMixRecipe = recipe.type == PlaylistType.STASH_MIX  // or however the recipe-type is exposed
val trackId = trackDao.insert(
    buildStubTrack().copy(
        isDownloaded = false,
        isStreamable = isMixRecipe,
        // ... rest unchanged
    )
)
if (!isMixRecipe) {
    downloadQueueDao.insert(buildDownloadQueueEntry(trackId, ...))
}
```

**Critical:** apply the same upsert-by-youtubeId discipline as `SearchDownloadCoordinator.upsertSearchTrack` (cited in spec risk #5). The streaming engine may race-insert a row with the same `youtubeId`; collisions on the UNIQUE index must be handled. Use `trackDao.findByYoutubeId(...)` first, fall back to insert.

- [ ] **Step 5: Run the test, verify it passes.**

```
./gradlew :core:data:testDebugUnitTest --tests "*StashDiscoveryWorkerStreamOnlyTest*"
```

Expected: PASS.

- [ ] **Step 6: Run the full module test suite to catch regressions.**

```
./gradlew :core:data:testDebugUnitTest
```

Expected: PASS (no other discovery-worker tests broken).

- [ ] **Step 7: Commit.**

```
git add core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashDiscoveryWorker.kt
git add core/data/src/test/kotlin/com/stash/core/data/sync/workers/StashDiscoveryWorkerStreamOnlyTest.kt
git commit -m "feat(mix): skip download enqueue for Stash Mix discoveries (stream-only)"
```

---

## Task 3: `StashMixRefreshWorker.materializeMix` — use new DAO method

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorker.kt` (line ~452)
- Test: `core/data/src/test/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorkerStreamOnlyMaterializeTest.kt` (new)

- [ ] **Step 1: Write the failing test.** Recipe with two `DONE` discovery rows: one whose track is downloaded, one whose track is stream-only. After `materializeMix`, both should appear as `PlaylistTrackCrossRef` rows linked to the Mix playlist.

```kotlin
@Test
fun materializeMix_includesStreamOnlyDiscoverySurvivors() = runTest {
    val recipe = buildRecipe()
    val downloadedTrackId = trackDao.insert(buildTrack(isDownloaded = true))
    val streamOnlyTrackId = trackDao.insert(buildTrack(isStreamable = true, isDownloaded = false))
    discoveryQueueDao.insert(buildDoneEntry(recipe.id, downloadedTrackId))
    discoveryQueueDao.insert(buildDoneEntry(recipe.id, streamOnlyTrackId))

    worker.doWork()

    val mixPlaylist = playlistDao.findBySourceId("stash_mix_${recipe.id}")
    val members = playlistDao.getTracksByPlaylistId(mixPlaylist!!.id)
    assertThat(members.map { it.id }).containsExactlyInAnyOrder(downloadedTrackId, streamOnlyTrackId)
}
```

- [ ] **Step 2: Run the test, verify it fails.**

```
./gradlew :core:data:testDebugUnitTest --tests "*StashMixRefreshWorkerStreamOnlyMaterializeTest*"
```

Expected: FAIL — only `downloadedTrackId` is in the playlist.

- [ ] **Step 3: Swap the DAO call** in `StashMixRefreshWorker.materializeMix` at line ~452:

```kotlin
- val doneIds = playlistDao.getDoneTrackIdsForRecipe(recipe.id)
+ val doneIds = playlistDao.getStreamableOrDoneTrackIdsForRecipe(recipe.id)
```

- [ ] **Step 4: Run the test, verify it passes.**

```
./gradlew :core:data:testDebugUnitTest --tests "*StashMixRefreshWorkerStreamOnlyMaterializeTest*"
```

Expected: PASS.

- [ ] **Step 5: Run the broader test suite for the module to catch regressions in `StashMixRefreshWorkerSeedFilterTest`, `StashMixRefreshWorkerPerRecipeDedupTest`, etc.**

```
./gradlew :core:data:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 6: Commit.**

```
git add core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorker.kt
git add core/data/src/test/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorkerStreamOnlyMaterializeTest.kt
git commit -m "feat(mix): materializeMix links stream-only discoveries into the Mix playlist"
```

---

## Task 4: `StashPlaybackService` queue filter — include streamable tracks

**Files:**
- Modify: `core/media/src/main/kotlin/com/stash/core/media/service/StashPlaybackService.kt` (lines ~500 and ~749 per spec)

There are no isolated unit tests for these sites — they live deep in the MediaSession callback chain. This task is a small mechanical change verified on-device.

- [ ] **Step 1: Find both filter sites.**

```
grep -n "filter.*isDownloaded\|filter { .*isDownloaded }" core/media/src/main/kotlin/com/stash/core/media/service/StashPlaybackService.kt
```

Confirm there are exactly two `filter { it.isDownloaded }` calls (around lines 500 and 749).

- [ ] **Step 2: Replace both with `filter { it.isDownloaded || it.isStreamable }`.** Each instance:

```kotlin
- val playable = tracks.filter { it.isDownloaded }
+ val playable = tracks.filter { it.isDownloaded || it.isStreamable }
```

- [ ] **Step 3: Build to verify compilation.**

```
./gradlew :core:media:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Install + manually verify.**

```
./gradlew :app:installDebug
```

On device: open a Mix playlist with at least one stream-only track. Tap the stream-only track. It should play (previously it would have been filtered out).

- [ ] **Step 5: Commit.**

```
git add core/media/src/main/kotlin/com/stash/core/media/service/StashPlaybackService.kt
git commit -m "feat(player): include streamable tracks in playable-queue filter"
```

---

## Task 5: `MixOfflineTapGuard` — Snackbar on offline tap

**Files:**
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/PlaylistDetailViewModel.kt`
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/PlaylistDetailScreen.kt`
- Test: `feature/library/src/test/kotlin/com/stash/feature/library/MixOfflineTapGuardTest.kt` (new)

- [ ] **Step 1: Read context.** Open `PlaylistDetailViewModel.kt`. Identify the existing `playTrack(track)` / `onTrackTap(track)` flow. Locate the existing `_userMessages: SharedFlow<String>` or similar event channel (or grep for `userMessages` — the file likely already emits Snackbar events for other features per the codebase pattern).

- [ ] **Step 2: Write the failing test.**

```kotlin
@Test
fun playTrack_offlineAndStreamOnly_emitsSnackbarAndAborts() = runTest {
    val track = Track(id = 42, isStreamable = true, isDownloaded = false, ...)
    coEvery { connectivityMonitor.isConnected() } returns false
    val playerSpy = mockk<PlayerRepository>(relaxed = true)
    val vm = PlaylistDetailViewModel(..., playerRepository = playerSpy, connectivityMonitor = connectivityMonitor)

    val messages = mutableListOf<String>()
    val job = launch { vm.userMessages.toList(messages) }

    vm.onTrackTap(track)

    coVerify(exactly = 0) { playerSpy.setQueue(any(), any()) }
    assertThat(messages).contains("Online only — connect to play this track")
    job.cancel()
}
```

- [ ] **Step 3: Run the test, verify it fails.**

```
./gradlew :feature:library:testDebugUnitTest --tests "*MixOfflineTapGuardTest*"
```

Expected: FAIL — play call was made, no Snackbar emitted.

- [ ] **Step 4: Implement.** In `PlaylistDetailViewModel`, inject `ConnectivityMonitor` if not already injected. Modify the tap handler:

```kotlin
fun onTrackTap(track: Track) {
    if (track.isStreamable && !track.isDownloaded && !connectivityMonitor.isConnected()) {
        _userMessages.tryEmit("Online only — connect to play this track")
        return
    }
    // ... existing play logic
}
```

If `PlaylistDetailViewModel` doesn't already have `_userMessages`, add it (mirror the pattern in `HomeViewModel` / `NowPlayingViewModel`).

- [ ] **Step 5: Run the test, verify it passes.**

```
./gradlew :feature:library:testDebugUnitTest --tests "*MixOfflineTapGuardTest*"
```

Expected: PASS.

- [ ] **Step 6: Wire SnackbarHost in `PlaylistDetailScreen`.** Look at `feature/search/SearchScreen.kt` for the established `SnackbarHost { ... } + LaunchedEffect { userMessages.collect { ... showSnackbar } }` pattern; mirror it. If `PlaylistDetailScreen` already wires Snackbar for other events, route the offline-only message through the same channel.

- [ ] **Step 7: Install + manually verify.**

```
./gradlew :app:installDebug
```

On device: airplane-mode on, open a Mix playlist, tap a stream-only track. Snackbar appears reading "Online only — connect to play this track". Play does NOT start.

- [ ] **Step 8: Commit.**

```
git add feature/library/src/main/kotlin/com/stash/feature/library/PlaylistDetailViewModel.kt
git add feature/library/src/main/kotlin/com/stash/feature/library/PlaylistDetailScreen.kt
git add feature/library/src/test/kotlin/com/stash/feature/library/MixOfflineTapGuardTest.kt
git commit -m "feat(mix): offline tap-guard with Snackbar for stream-only Mix tracks"
```

---

## Task 6: Player auto-advance silent-skip for offline stream-only tracks

**Files:**
- Modify: `core/media/src/main/kotlin/com/stash/core/media/PlayerRepositoryImpl.kt`

There is no clean unit-test seam here (deep ExoPlayer interaction); on-device verification is the gate.

- [ ] **Step 1: Identify the auto-advance hook.** Open `PlayerRepositoryImpl.kt` and find the `Player.Listener` block (look for `onMediaItemTransition` or `addListener` registrations). Determine which method fires when the queue advances to the next track.

- [ ] **Step 2: Implement strategy (b): listener-based skip.** Decision locked: pre-filtering at queue-build time is wrong because connectivity is dynamic (user toggles airplane mid-playback). The listener observes `Player.Listener.onMediaItemTransition` (or `onPositionDiscontinuity` with reason `MEDIA_ITEM_TRANSITION`); when the new current MediaItem corresponds to a stream-only track AND `connectivityMonitor.isConnected()` is false, call `player.seekToNextMediaItem()` until either a playable item is reached or the queue is exhausted. Spec risk #2 already accepts the linear-scan cost.

- [ ] **Step 3: Implement the listener-based skip.** Use the `MediaItem.mediaMetadata.extras` to read `isStreamable` flag (note: the existing extras don't carry this directly — you'll need to add `EXTRA_TRACK_IS_STREAMABLE` to `StashPlaybackService.Companion` and populate it from `buildMediaItemForTrack`, following the pattern used by `EXTRA_TRACK_DURATION_MS` added in Track C). Wrap the skip in a small loop that bounds itself to at most `queue.size` iterations to avoid runaway when nothing is playable.

When the entire queue is exhausted of playable tracks, stop playback and emit a single Snackbar event ("End of offline Mix") through the existing player event channel (if one exists; otherwise add a SharedFlow on `PlayerRepository` that the UI layer can subscribe to).

- [ ] **Step 4: Manual verification.** On device:
  - Queue a Mix with mixed downloaded + stream-only tracks. Toggle airplane mode mid-playback. When the current downloaded track ends and the queue would advance to a stream-only track, the player should silently skip to the next downloaded one.
  - Queue a Mix that's entirely stream-only. Toggle airplane mode. Playback stops with the "End of offline Mix" Snackbar.

- [ ] **Step 5: Commit.**

```
git add core/media/src/main/kotlin/com/stash/core/media/PlayerRepositoryImpl.kt
git add core/media/src/main/kotlin/com/stash/core/media/service/StashPlaybackService.kt  # for EXTRA_TRACK_IS_STREAMABLE
git commit -m "feat(player): silent-skip stream-only Mix tracks while offline"
```

---

## Task 7: Cross-cutting on-device QA

**Files:** none (manual verification)

- [ ] **Step 1: Install the final build.**

```
./gradlew :app:installDebug
```

- [ ] **Step 2: Smoke test the existing Mix flow.** Open a Mix that has only downloaded tracks. Verify it still plays exactly as before (no regression).

- [ ] **Step 3: Smoke test new discovery.** Trigger a Mix refresh (Home long-press "Refresh this mix" or the periodic worker). Wait for `StashDiscoveryWorker` to run. Verify in the device DB (`adb exec-out run-as com.stash.app.debug cat databases/stash.db > /tmp/stash.db` then `sqlite3 /tmp/stash.db "SELECT id, title, is_downloaded, is_streamable FROM tracks ORDER BY id DESC LIMIT 5"`) that new discoveries land with `is_streamable=1, is_downloaded=0`, and that `download_queue` has NO new rows for those track ids.

- [ ] **Step 4: Smoke test playback.** Open the refreshed Mix. Tap a freshly-discovered (stream-only) track. Verify it plays via the streaming engine.

- [ ] **Step 5: Smoke test lyrics on streaming Mix (spec risk).** While a stream-only Mix track plays, open the Lyrics sheet. Verify lyrics render via the v0.9.36 streaming lyrics fetch path.

- [ ] **Step 6: Smoke test offline tap.** Airplane mode on. Tap a stream-only Mix track. Snackbar appears, no play starts. Disable airplane mode, tap again, plays normally.

- [ ] **Step 7: Smoke test offline auto-advance.** Mix with mixed tracks playing → airplane mode on mid-playback → queue advances → stream-only tracks silently skipped → downloaded tracks play through → exhausted-queue Snackbar at end.

- [ ] **Step 8: Smoke test existing downloaded Mix tracks are untouched.** Per the spec's explicit non-goal, no existing downloaded Mix tracks should be deleted. Confirm by listing `tracks` table before and after the refresh — pre-existing rows with `is_downloaded=1` remain on disk.

- [ ] **Step 9: Commit any QA-induced fixes.** If anything surfaces, fix-then-commit per the same pattern.

---

## Task 8: Version bump + final commit prep

**Files:**
- Modify: `app/build.gradle.kts` (versionCode + versionName)

- [ ] **Step 1: Read current version.**

```
grep -nE "versionCode|versionName" app/build.gradle.kts
```

- [ ] **Step 2: Bump to v0.9.37.** Increment versionCode by 1; set versionName to `"0.9.37"`. **Coordination note:** Tracks B + C and the merged sync-card relocation (PR #103) all ship under v0.9.37. Do this version bump **once**, in this plan, as the final commit before the human tags the release — not separately in any of those tracks. If you find the version was already bumped to 0.9.37, skip this step.

- [ ] **Step 3: Commit version bump.**

```
git add app/build.gradle.kts
git commit -m "chore(release): bump versionCode + versionName to 0.9.37"
```

- [ ] **Step 4: Hand off to the human for the bundled v0.9.37 release commit.** Per `feedback_ship_terminology.md`, "ship" means tag + push, not local install. The human will compose the release commit message (per `feedback_release_notes.md`, the release body comes from the tagged-commit message body), tag, and push.

---

## Out of scope (separate work)

Per the spec — do NOT do any of these as part of this plan:
- Raise the 100/week discovery cap.
- "Refresh when online" CTA on empty-offline-Mix Snackbar.
- Per-track "Save offline" button on Mix tracks.
- Search-tab `musicShelfRenderer` parser fix (separate issue, same root cause as Track B's first regression).
- Custom playlist covers for stream-only Mixes.
- Purging existing downloaded Mix tracks (explicit non-goal per user).
- Lyrics on streaming Mix — assumed working via `118a9b3`; will smoke-test in Task 7 step 5.
