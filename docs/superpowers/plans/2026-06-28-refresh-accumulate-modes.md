# Refresh / Accumulate Mix Modes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make ACCUMULATE truly never auto-delete (a single global guard on the one deleter), warn before switching a source to REFRESH, and default new installs to ACCUMULATE.

**Architecture:** One root-cause guard — `cleanOrphanedMixTracks()` early-returns when any source is ACCUMULATE, which covers both its callers (per-sync in `DiffWorker`, and the startup sweep). A confirmation `AlertDialog` gates the Refresh chip in the Sync screen. Three default constants flip REFRESH→ACCUMULATE. The pending-download orphan-cancel is deliberately left untouched.

**Tech Stack:** Kotlin, Hilt, DataStore (sync_preferences), Room DAOs, JUnit + MockK + kotlinx-coroutines-test, Jetpack Compose (Material3 AlertDialog).

**Spec:** `docs/superpowers/specs/2026-06-28-refresh-accumulate-modes-design.md`
**Branch:** create `fix/refresh-accumulate-modes` off current master (`e5cfd6f4`).

---

## Reference: exact current code (verified)

| Thing | Location |
|---|---|
| `SyncMode` enum + KDoc | `core/model/src/main/kotlin/com/stash/core/model/SyncMode.kt` |
| Mode defaults (data class) | `SyncPreferencesManager.kt:41-42` (`spotifySyncMode`/`youtubeSyncMode = SyncMode.REFRESH`) |
| Mode resolver fallbacks | `SyncPreferencesManager.kt:138` (`resolveSpotifyMode` `?: SyncMode.REFRESH`), `:144` (`resolveYoutubeMode` `?: SyncMode.REFRESH`) |
| Per-source mode flows | `SyncPreferencesManager.spotifySyncMode` / `.youtubeSyncMode` (Flow<SyncMode>) |
| The deleter | `MusicRepositoryImpl.cleanOrphanedMixTracks()` (`MusicRepositoryImpl.kt:846-883`) |
| MusicRepositoryImpl ctor | `MusicRepositoryImpl.kt:36-49` (last param `localFileOps` at `:48`) |
| Deleter callers | `DiffWorker.kt:176`, startup sweep `MusicRepositoryImpl.kt:148` |
| Test helper for the repo | `core/data/src/test/kotlin/com/stash/core/data/repository/MusicRepositoryDownloadsMixTest.kt` — `buildRepo(...)` builds `MusicRepositoryImpl` with `mockk(relaxed=true)` deps |
| SyncViewModel mode handlers | `SyncViewModel.kt:362` (`onSpotifySyncModeChanged`), `:369` (`onYoutubeSyncModeChanged`) — both already inject `syncPreferencesManager` |
| SyncUiState mode fields | `SyncViewModel.kt:102-103` (`spotifySyncMode`/`youtubeSyncMode = SyncMode.REFRESH`) |
| Chip row composable | `SyncScreen.kt:559-594` (`SyncModeChipRow`), used by Spotify card (~`:721`) + YouTube card (~`:940`) |
| AlertDialog pattern to mirror | `feature/sync/.../FailedMatchesScreen.kt:288` |

**Windows / Gradle gotchas (this box):** use the daemon (omit `--no-daemon`); always pass a `--tests` filter. If a run throws `java.net.BindException` or "Unable to delete directory build/test-results", run `./gradlew --stop` once then rerun. DI changes are only fully validated by `:app:assembleDebug` (Hilt graph), not per-module compile.

**Out of scope (do NOT touch):** `cancelDownloadsWithNoEnabledPlaylist()` and its two callers (`MusicRepositoryImpl.kt:155`, `SyncViewModel.onTogglePlaylistSync` `:301`). Cancelling a not-yet-downloaded queue row for a deselected playlist isn't a deletion — leave it ungated (spec §1).

---

## Task 1: Flip the default mode REFRESH → ACCUMULATE

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/sync/SyncPreferencesManager.kt` (`:41-42`, `:138`, `:144`)
- Modify: `core/model/src/main/kotlin/com/stash/core/model/SyncMode.kt` (KDoc)
- Modify: `feature/sync/src/main/kotlin/com/stash/feature/sync/SyncViewModel.kt` (`:102-103` UiState defaults)
- Test: `core/data/src/test/kotlin/com/stash/core/data/sync/SyncPreferencesManagerTest.kt` (new)

- [ ] **Step 1: Write the failing test.** New file. Uses Robolectric + a temp-backed DataStore the same way `ArcodCredentialStoreTest` does (Robolectric + `ApplicationProvider`, clears the store in `@Before`). Assert the new default + that explicit/legacy values still resolve.

```kotlin
package com.stash.core.data.sync

import androidx.test.core.app.ApplicationProvider
import com.stash.core.model.SyncMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncPreferencesManagerTest {
    private lateinit var mgr: SyncPreferencesManager

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        // CRITICAL: the `sync_preferences` DataStore is a per-PROCESS delegate, so
        // it leaks across tests in a Robolectric run (Task 2 writes REFRESH/REFRESH,
        // which would corrupt Task 1's "fresh store → ACCUMULATE" assertion). Delete
        // the backing file before each test so every test starts from a truly empty
        // store. (Resolve the file via the same `name = "sync_preferences"` the
        // delegate uses: `ctx.preferencesDataStoreFile("sync_preferences").delete()`.)
        ctx.preferencesDataStoreFile("sync_preferences").delete()
        mgr = SyncPreferencesManager(ctx)
    }

    @Test fun `default mode is ACCUMULATE for both sources on a fresh store`() = runTest {
        assertEquals(SyncMode.ACCUMULATE, mgr.spotifySyncMode.first())
        assertEquals(SyncMode.ACCUMULATE, mgr.youtubeSyncMode.first())
    }

    @Test fun `explicit REFRESH choice is preserved`() = runTest {
        mgr.setSpotifySyncMode(SyncMode.REFRESH)
        assertEquals(SyncMode.REFRESH, mgr.spotifySyncMode.first())
    }

    @Test fun `explicit ACCUMULATE choice is preserved`() = runTest {
        mgr.setYoutubeSyncMode(SyncMode.ACCUMULATE)
        assertEquals(SyncMode.ACCUMULATE, mgr.youtubeSyncMode.first())
    }
}
```
> NOTE: a per-process `preferencesDataStore` delegate is shared across tests in a Robolectric run. If the default test flakes due to a prior test's writes, isolate it (run the default assertion first) or follow whatever isolation `ArcodCredentialStoreTest` uses. Confirm the test genuinely starts from an empty store.

- [ ] **Step 2: Run — expect FAIL** (default resolves REFRESH, not ACCUMULATE).
```
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.sync.SyncPreferencesManagerTest"
```

- [ ] **Step 3: Flip the defaults.** In `SyncPreferencesManager.kt`:
  - `:41-42` → `spotifySyncMode: SyncMode = SyncMode.ACCUMULATE`, `youtubeSyncMode: SyncMode = SyncMode.ACCUMULATE`.
  - `:138` `resolveSpotifyMode` final `?: SyncMode.REFRESH` → `?: SyncMode.ACCUMULATE`.
  - `:144` `resolveYoutubeMode` `?: SyncMode.REFRESH` → `?: SyncMode.ACCUMULATE`.
  - Update the class/`resolveYoutubeMode` KDoc text that says "Defaults to REFRESH" → "Defaults to ACCUMULATE."
  In `SyncViewModel.kt:102-103`: UiState `spotifySyncMode`/`youtubeSyncMode = SyncMode.ACCUMULATE` (+ update the "Defaults to REFRESH for both" comment).
  In `SyncMode.kt`: rewrite the enum KDoc so REFRESH documents the **real** behavior (rotates old tracks out of the mix AND deletes their downloads to stay lean — only while all sources are Refresh) and ACCUMULATE documents the never-delete guarantee + that it's the default.

- [ ] **Step 4: Run — expect PASS.** Same command.
- [ ] **Step 5: Commit.**
```
git add core/data/src/main/kotlin/com/stash/core/data/sync/SyncPreferencesManager.kt \
        core/model/src/main/kotlin/com/stash/core/model/SyncMode.kt \
        feature/sync/src/main/kotlin/com/stash/feature/sync/SyncViewModel.kt \
        core/data/src/test/kotlin/com/stash/core/data/sync/SyncPreferencesManagerTest.kt
git commit -m "feat(sync): default mix mode to ACCUMULATE (never-delete) + fix SyncMode KDoc"
```

---

## Task 2: `anyAccumulate()` on SyncPreferencesManager

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/sync/SyncPreferencesManager.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/sync/SyncPreferencesManagerTest.kt` (extend)

- [ ] **Step 1: Write the failing test** (add to the Task 1 test file):
```kotlin
    @Test fun `anyAccumulate true on fresh store (default ACCUMULATE)`() = runTest {
        assertTrue(mgr.anyAccumulate())
    }

    @Test fun `anyAccumulate false only when both sources are REFRESH`() = runTest {
        mgr.setSpotifySyncMode(SyncMode.REFRESH)
        mgr.setYoutubeSyncMode(SyncMode.REFRESH)
        assertFalse(mgr.anyAccumulate())
    }

    @Test fun `anyAccumulate true when one source accumulates`() = runTest {
        mgr.setSpotifySyncMode(SyncMode.REFRESH)
        mgr.setYoutubeSyncMode(SyncMode.ACCUMULATE)
        assertTrue(mgr.anyAccumulate())
    }
```
(add `import org.junit.Assert.assertTrue` / `assertFalse`.)

- [ ] **Step 2: Run — expect FAIL** (`anyAccumulate` unresolved).
- [ ] **Step 3: Implement** in `SyncPreferencesManager`:
```kotlin
    /**
     * True if EITHER source's mix mode is ACCUMULATE. The orphan-cleanup sweep
     * ([com.stash.core.data.repository.MusicRepository.cleanOrphanedMixTracks])
     * consults this and deletes nothing while any source accumulates — the
     * library is append-only. Only when BOTH sources are REFRESH does cleanup run.
     */
    suspend fun anyAccumulate(): Boolean =
        spotifySyncMode.first() == SyncMode.ACCUMULATE ||
            youtubeSyncMode.first() == SyncMode.ACCUMULATE
```
(add `import kotlinx.coroutines.flow.first` if not present.)

- [ ] **Step 4: Run — expect PASS.**
- [ ] **Step 5: Commit.**
```
git add core/data/src/main/kotlin/com/stash/core/data/sync/SyncPreferencesManager.kt \
        core/data/src/test/kotlin/com/stash/core/data/sync/SyncPreferencesManagerTest.kt
git commit -m "feat(sync): SyncPreferencesManager.anyAccumulate() — drives the never-delete gate"
```

---

## Task 3: Gate `cleanOrphanedMixTracks()` on `anyAccumulate()`

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepositoryImpl.kt` (ctor `:36-49` + the function `:846`)
- Test: `core/data/src/test/kotlin/com/stash/core/data/repository/MusicRepositoryDownloadsMixTest.kt` (extend — add the `syncPreferencesManager` param to `buildRepo` + 2 gate tests)

- [ ] **Step 1: Write the failing tests.** First extend the existing `buildRepo(...)` helper to inject a `SyncPreferencesManager` mock (default `coEvery { anyAccumulate() } returns false` so existing tests keep their current behavior), then add:
```kotlin
    @Test fun `cleanOrphanedMixTracks deletes nothing when any source accumulates`() = runTest {
        val trackDao = mockk<TrackDao>(relaxed = true)
        val prefs = mockk<SyncPreferencesManager>(relaxed = true)
        coEvery { prefs.anyAccumulate() } returns true
        coEvery { trackDao.getOrphanedDownloadedTracks() } returns listOf(
            // a downloaded track that WOULD be swept if the gate were off
            trackEntity(id = 1L, filePath = "/x/a.flac"),
        )
        val repo = buildRepo(trackDao = trackDao, syncPreferencesManager = prefs)

        val cleaned = repo.cleanOrphanedMixTracks()

        assertEquals(0, cleaned)
        coVerify(exactly = 0) { trackDao.delete(any()) }
        // Ideally also assert getOrphanedDownloadedTracks() is never queried,
        // i.e. the gate short-circuits before the DAO read:
        coVerify(exactly = 0) { trackDao.getOrphanedDownloadedTracks() }
    }

    @Test fun `cleanOrphanedMixTracks deletes orphans when all sources refresh`() = runTest {
        val trackDao = mockk<TrackDao>(relaxed = true)
        val prefs = mockk<SyncPreferencesManager>(relaxed = true)
        coEvery { prefs.anyAccumulate() } returns false
        coEvery { trackDao.getOrphanedDownloadedTracks() } returns listOf(
            trackEntity(id = 1L, filePath = "/x/a.flac"),
        )
        // discovery-queue protection returns empty so the orphan is eligible:
        val discoveryQueueDao = mockk<DiscoveryQueueDao>(relaxed = true)
        coEvery { discoveryQueueDao.getActiveTrackIds() } returns emptyList()
        val repo = buildRepo(trackDao = trackDao, discoveryQueueDao = discoveryQueueDao,
            syncPreferencesManager = prefs)

        val cleaned = repo.cleanOrphanedMixTracks()

        assertEquals(1, cleaned)
        coVerify(exactly = 1) { trackDao.delete(any()) }
    }
```
> This test file has NO `trackEntity(...)` fixture — build a minimal `TrackEntity` with `isDownloaded = true` + a `filePath`. Copy the `trackEntity(id, isDownloaded = ...)` construction pattern from `core/data/src/test/kotlin/com/stash/core/data/db/dao/DiscoveryQueueDaoCapTest.kt:136`. Add a `syncPreferencesManager: SyncPreferencesManager = mockk(relaxed = true)` param to `buildRepo` and pass it to the `MusicRepositoryImpl(...)` call (new last arg). The existing `buildRepo` already defaults `anyAccumulate()` via the relaxed mock — but to keep pre-existing repo tests deterministic, have `buildRepo` stub `coEvery { syncPreferencesManager.anyAccumulate() } returns false` by default.

- [ ] **Step 2: Run — expect FAIL** (ctor arity mismatch / gate not present).
```
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.repository.MusicRepositoryDownloadsMixTest"
```

- [ ] **Step 3: Implement.**
  - Add to the `MusicRepositoryImpl` constructor (after `localFileOps` at `:48`):
    `private val syncPreferencesManager: com.stash.core.data.sync.SyncPreferencesManager,`
  - At the very top of `cleanOrphanedMixTracks()` (before `getOrphanedDownloadedTracks()`):
```kotlin
        // ACCUMULATE = never auto-delete. While any source accumulates, the
        // library is append-only — a deselected playlist, a rotated mix, or a
        // disconnected source must not delete a downloaded track or its files.
        // Single gate here covers BOTH callers (DiffWorker per-sync + the
        // startup sweep). See SyncMode / the refresh-accumulate spec.
        if (syncPreferencesManager.anyAccumulate()) {
            android.util.Log.d("StashCleanup", "Skipped orphan sweep — accumulate mode active")
            return 0
        }
```

- [ ] **Step 4: Run — expect PASS.** Also run the whole repo test class to confirm pre-existing tests still pass with the relaxed `anyAccumulate()=false` default.
- [ ] **Step 5: Confirm no sibling deleter (spec edge case).** Grep to confirm `cleanOrphanedMixTracks()` is the ONLY auto-deleter on the sync path and that Stash's own mix refresh doesn't independently delete library tracks:
```
grep -rnE 'deleteTrackFile|trackDao\.delete|getOrphanedDownloadedTracks' core/data/src/main/kotlin/com/stash/core/data/sync/
```
Expected: no `deleteTrackFile`/`trackDao.delete`/orphan-query in `StashMixRefreshWorker` or other sync workers (the deletion lives only in `MusicRepositoryImpl.cleanOrphanedMixTracks`, now gated). If a sibling deleter exists, STOP and report — it would need the same gate.
- [ ] **Step 6: Commit.**
```
git add core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepositoryImpl.kt \
        core/data/src/test/kotlin/com/stash/core/data/repository/MusicRepositoryDownloadsMixTest.kt
git commit -m "fix(sync): gate orphan-cleanup on accumulate — never auto-delete while accumulating"
```

---

## Task 4: Refresh-confirm dialog state + ViewModel handlers

**Files:**
- Modify: `feature/sync/src/main/kotlin/com/stash/feature/sync/SyncViewModel.kt` (UiState + handlers)
- Test: `feature/sync/src/test/kotlin/com/stash/feature/sync/SyncViewModelTest.kt` (extend if it exists; else new — match the module's VM test style)

- [ ] **Step 1: Write the failing test.** Find the existing SyncViewModel test (`git ls-files | grep SyncViewModelTest`); mirror its setup (it constructs the VM with mocks; `syncPreferencesManager` is already a dep). Assert:
```kotlin
    // tapping Refresh while current mode is ACCUMULATE does NOT change the mode,
    // it requests confirmation:
    @Test fun `requesting Spotify refresh from accumulate opens the dialog without changing mode`() = runTest {
        // arrange: current spotify mode = ACCUMULATE in uiState
        vm.onRequestSpotifyRefresh()
        assertEquals(MusicSource.SPOTIFY, vm.uiState.value.pendingRefreshSource)
        coVerify(exactly = 0) { syncPreferencesManager.setSpotifySyncMode(any()) }
    }

    @Test fun `confirming refresh applies REFRESH and clears the dialog`() = runTest {
        vm.onRequestSpotifyRefresh()      // pending = SPOTIFY
        vm.confirmRefreshMode()
        coVerify { syncPreferencesManager.setSpotifySyncMode(SyncMode.REFRESH) }
        assertNull(vm.uiState.value.pendingRefreshSource)
    }

    @Test fun `cancelling refresh clears the dialog without changing mode`() = runTest {
        vm.onRequestSpotifyRefresh()
        vm.cancelRefreshMode()
        assertNull(vm.uiState.value.pendingRefreshSource)
        coVerify(exactly = 0) { syncPreferencesManager.setSpotifySyncMode(SyncMode.REFRESH) }
    }

    @Test fun `requesting refresh when already REFRESH is a no-op (no dialog)`() = runTest {
        // arrange: current spotify mode = REFRESH
        vm.onRequestSpotifyRefresh()
        assertNull(vm.uiState.value.pendingRefreshSource)
    }
```
> Arrange the "current mode" by driving the same Flow the VM observes (the VM already collects `spotifySyncMode`/`youtubeSyncMode` into UiState — see `observeSyncMode()` `:415`). Mirror how the existing VM test seeds preference flows. If no SyncViewModelTest exists, create one following another `feature/*` ViewModel test for harness/Dispatcher setup.

- [ ] **Step 2: Run — expect FAIL.**
```
./gradlew :feature:sync:testDebugUnitTest --tests "com.stash.feature.sync.SyncViewModelTest"
```

- [ ] **Step 3: Implement** in `SyncViewModel`:
  - Add to `SyncUiState` (near `:103`): `val pendingRefreshSource: com.stash.core.model.MusicSource? = null,` with a KDoc ("non-null while the Refresh-confirm dialog is shown for that source").
  - Add handlers (next to `onSpotifySyncModeChanged`):
```kotlin
    /** Refresh chip tapped for Spotify. If currently ACCUMULATE, ask first
     *  (Refresh deletes rotated-out downloads); if already REFRESH, no-op. */
    fun onRequestSpotifyRefresh() {
        if (_uiState.value.spotifySyncMode == SyncMode.ACCUMULATE) {
            _uiState.update { it.copy(pendingRefreshSource = MusicSource.SPOTIFY) }
        }
    }

    fun onRequestYoutubeRefresh() {
        if (_uiState.value.youtubeSyncMode == SyncMode.ACCUMULATE) {
            _uiState.update { it.copy(pendingRefreshSource = MusicSource.YOUTUBE) }
        }
    }

    /** Confirm the pending Refresh switch — applies REFRESH to that source. */
    fun confirmRefreshMode() {
        val source = _uiState.value.pendingRefreshSource ?: return
        viewModelScope.launch {
            when (source) {
                MusicSource.YOUTUBE -> syncPreferencesManager.setYoutubeSyncMode(SyncMode.REFRESH)
                else -> syncPreferencesManager.setSpotifySyncMode(SyncMode.REFRESH)
            }
        }
        _uiState.update { it.copy(pendingRefreshSource = null) }
    }

    /** Dismiss the dialog — keep the current (Accumulate) mode. */
    fun cancelRefreshMode() {
        _uiState.update { it.copy(pendingRefreshSource = null) }
    }
```
  (add `import com.stash.core.model.MusicSource` if missing.) Leave `onSpotifySyncModeChanged`/`onYoutubeSyncModeChanged` as-is — the **Accumulate** chip still calls those directly (no dialog).

- [ ] **Step 4: Run — expect PASS.**
- [ ] **Step 5: Commit.**
```
git add feature/sync/src/main/kotlin/com/stash/feature/sync/SyncViewModel.kt \
        feature/sync/src/test/kotlin/com/stash/feature/sync/SyncViewModelTest.kt
git commit -m "feat(sync): Refresh-confirm dialog state + VM handlers (warn before delete-on-refresh)"
```

---

## Task 5: Wire the dialog + Refresh-chip interception into SyncScreen

**Files:**
- Modify: `feature/sync/src/main/kotlin/com/stash/feature/sync/SyncScreen.kt` (`SyncModeChipRow` + the two call sites + a new `AlertDialog`)

> Compose UI — no unit test. Verified by compile + on-device (Task 6).

- [ ] **Step 1:** Give `SyncModeChipRow` a Refresh-request callback so the Refresh chip can be intercepted, while Accumulate stays direct:
```kotlin
@Composable
private fun SyncModeChipRow(
    mode: SyncMode,
    onChange: (SyncMode) -> Unit,
    onRequestRefresh: () -> Unit,   // NEW
    accent: Color,
) {
    ...
        FilterChip(
            selected = mode == SyncMode.REFRESH,
            onClick = { onRequestRefresh() },          // was onChange(REFRESH)
            label = { Text("Refresh") },
        )
        FilterChip(
            selected = mode == SyncMode.ACCUMULATE,
            onClick = { onChange(SyncMode.ACCUMULATE) },  // unchanged
            label = { Text("Accumulate") },
        )
    ...
}
```

- [ ] **Step 2:** Thread the new callback. The chip row lives inside per-source card composables (Spotify card takes `onSyncModeChanged` ~`:707`, used at `SyncModeChipRow` ~`:721`; YouTube card ~`:923`/`:940`). `viewModel` is NOT in scope inside the cards — so:
  - Add an `onRequestRefresh: () -> Unit` param to each card composable (next to its `onSyncModeChanged`), and forward it into `SyncModeChipRow(onRequestRefresh = onRequestRefresh, ...)`.
  - Bind the `viewModel::` references at the **screen-level card invocations** (where `onSyncModeChanged` is already wired): Spotify card call ~`:210` → `onRequestRefresh = viewModel::onRequestSpotifyRefresh`; YouTube card call ~`:245` → `onRequestRefresh = viewModel::onRequestYoutubeRefresh`. Keep `onSyncModeChanged` for the Accumulate chip.

- [ ] **Step 3:** Render the confirm dialog once at the screen level, driven by `uiState.pendingRefreshSource` (mirror `FailedMatchesScreen.kt:288` for the AlertDialog shape):
```kotlin
    if (uiState.pendingRefreshSource != null) {
        AlertDialog(
            onDismissRequest = viewModel::cancelRefreshMode,
            title = { Text("Switch to Refresh?") },
            text = {
                Text(
                    "Refresh pulls fresh mixes each sync — your Daily Mixes, " +
                        "Discover Weekly, and other rotating playlists. Tracks that " +
                        "rotate out are removed from the mix and their downloads deleted " +
                        "to keep your library lean. Cleanup runs once all sources are set " +
                        "to Refresh — while any source still accumulates, nothing is " +
                        "deleted. Tracks you added manually are kept."
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRefreshMode) { Text("Switch to Refresh") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelRefreshMode) { Text("Cancel") }
            },
        )
    }
```
(add imports: `androidx.compose.material3.AlertDialog`, `TextButton` if not present.)

- [ ] **Step 4: Compile.**
```
./gradlew :feature:sync:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit.**
```
git add feature/sync/src/main/kotlin/com/stash/feature/sync/SyncScreen.kt
git commit -m "feat(sync): Refresh-chip confirm dialog in the Sync screen"
```

---

## Task 6: Build gate + on-device verification

- [ ] **Step 1: Full graph + suites.**
```
./gradlew :app:assembleDebug
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.sync.SyncPreferencesManagerTest" \
          --tests "com.stash.core.data.repository.MusicRepositoryDownloadsMixTest"
./gradlew :feature:sync:testDebugUnitTest --tests "com.stash.feature.sync.SyncViewModelTest"
```
Expected: all green; `assembleDebug` confirms the new `SyncPreferencesManager` ctor injection into `MusicRepositoryImpl` resolves in the Hilt graph.

- [ ] **Step 2: On-device** (gated on user consent; offer a library backup first per `feedback_ask_before_device_destructive`). `:app:installDebug`, then:
  - Fresh-state check: a clean install shows both chips on **Accumulate** by default.
  - Accumulate never-delete: with a source on Accumulate, deselect a synced playlist (or let a mix rotate) → re-sync → relaunch → the previously-downloaded files are still present (logcat shows `Skipped orphan sweep — accumulate mode active`).
  - Refresh warning: tap **Refresh** on an Accumulate source → dialog appears; Cancel → stays Accumulate; Confirm → mode flips to Refresh.
  - Refresh cleanup: with BOTH sources on Refresh, run a sync that rotates a mix → orphaned old downloads are cleaned (logcat `Cleaned N orphaned track(s)`).
- [ ] **Step 3:** Record results in a `## Verification Results` section appended to the spec; commit.

---

## Done when
- All unit tests green; `:app:assembleDebug` green.
- On-device: default is Accumulate; Accumulate never deletes (deselect/rotate safe); Refresh shows the warning and (all-Refresh) cleans up.
- The single gate lives in `cleanOrphanedMixTracks()` only; the pending-download cancel is untouched.
