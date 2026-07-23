# Radio Tuning Feedback, Overflow Consolidation, Batch FLAC Upgrade — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Radar-sweep feedback (+ honest errors) on the Now Playing radio button; one shared per-track ⋮ sheet across Library with the Liked tab fully wired; a "Non-FLAC" library filter plus a batch "Upgrade to FLAC" selection action running through a cancelable foreground worker.

**Architecture:** Three independent sections implemented in spec order §2→§1→§3 (the batch-FLAC selection action on the Liked tab depends on §2's Liked selection wiring). §2 is pure UI rewiring onto existing ViewModel methods. §1 adds a sealed `RadioStartResult` in `core:model`, threads a VM-local tuning flag, and draws a Canvas sweep. §3 adds one Room table (v34→v35), one foreground `CoroutineWorker` in `core:data` (all deps — `LosslessUpgrader` interface, DAOs, `SyncNotificationManager` — already live there), and one selection action + confirm dialog in `feature:library`.

**Tech Stack:** Kotlin, Jetpack Compose (M3), Room 2.7, WorkManager + `@HiltWorker` (auto-registered via the app's `HiltWorkerFactory` — no DI module edits), Robolectric + MockK for tests.

**Spec:** `docs/superpowers/specs/2026-07-22-radio-flair-menus-batch-flac-design.md`

---

## Ground rules (read first, they are repo law)

- **Branch:** all work happens on `feat/personal-trio` off current `master`. Task 1 creates it.
- **Never `git add -A` or `git add .`** — stage explicit paths only.
- **Test invocations always use `--tests` filters.** Master has known pre-existing failures in `:core:data` (4), `:core:model` (`PlaylistTypeTest`), ytmusic, and search modules. A bare module test run WILL show failures that are not yours. The exact filtered commands are given per task; treat any failure inside your filter as yours.
- **Re-throw `CancellationException` before any generic `catch`/`runCatching` handling in workers** (project rule).
- Room auto-converts enums to their `.name` string — the new `FlacUpgradeStatus` needs **no** TypeConverter.
- Compose BOM is 2026.03: `SuspendingPointerInputModifierNode`-era APIs; nothing in this plan touches pointer internals.
- Gradle: use the daemon as-is. If a build inexplicably wedges on file locks, `./gradlew --stop` once and retry.

---

## Section A — Overflow-menu consolidation (spec §2)

### Task 1: Branch + `TrackOptionsSheet` gains an optional Share row

**Files:**
- Modify: `core/ui/src/main/kotlin/com/stash/core/ui/components/TrackOptionsSheet.kt`

- [ ] **Step 1: Create the branch**

```bash
git checkout -b feat/personal-trio master
```

- [ ] **Step 2: Add the Share parameter and row**

In `TrackOptionsSheet.kt`, add to the imports:

```kotlin
import androidx.compose.material.icons.filled.Share
```

Change the signature (add `onShare` after `onSaveToPlaylist`, keeping existing param order otherwise):

```kotlin
@Composable
fun TrackOptionsSheet(
    track: Track,
    onPlayNext: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onSaveToPlaylist: (Track) -> Unit,
    onDelete: (Track) -> Unit,
    onShare: ((Track) -> Unit)? = null,
    onDownload: ((Track) -> Unit)? = null,
    onRemoveDownload: ((Track) -> Unit)? = null,
)
```

Update the KDoc `@param` block with: `@param onShare Opens the share-links sheet. Pass null to hide the row.`

Insert the row between the Download/Remove block and the `Spacer(4.dp)` that precedes Delete (the parameter sits after `onDelete` in the signature; the ROW renders between Download and Delete):

```kotlin
        // -- Share option --
        if (onShare != null) {
            SheetOptionRow(
                icon = Icons.Default.Share,
                label = "Share",
                onClick = { onShare(track) },
            )
        }
```

- [ ] **Step 3: Wire Share into the four detail screens (spec §2: "six call sites total — one menu everywhere")**

In each of `feature/library/.../AlbumDetailScreen.kt`, `ArtistDetailScreen.kt`, `PlaylistDetailScreen.kt`, `LikedSongsDetailScreen.kt`:

1. Add state next to the screen's existing `selectedTrack`: `var trackToShare by remember { mutableStateOf<Track?>(null) }`
2. Add to the screen (sibling of the TrackOptionsSheet block):

```kotlin
    trackToShare?.let { t ->
        com.stash.core.ui.components.ShareTrackSheet(
            title = t.title,
            artist = t.artist,
            spotifyUri = t.spotifyUri,
            youtubeId = t.youtubeId,
            onDismiss = { trackToShare = null },
        )
    }
```

3. Add to the screen's `TrackOptionsSheet(...)` call: `onShare = { trackToShare = it; selectedTrack = null },`

- [ ] **Step 4: Compile**

Run: `./gradlew :core:ui:compileDebugKotlin :feature:library:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add core/ui/src/main/kotlin/com/stash/core/ui/components/TrackOptionsSheet.kt feature/library/src/main/kotlin/com/stash/feature/library/AlbumDetailScreen.kt feature/library/src/main/kotlin/com/stash/feature/library/ArtistDetailScreen.kt feature/library/src/main/kotlin/com/stash/feature/library/PlaylistDetailScreen.kt feature/library/src/main/kotlin/com/stash/feature/library/LikedSongsDetailScreen.kt
git commit -m "feat(ui): Share row in the shared TrackOptionsSheet, wired on all detail screens"
```

### Task 2: Library Songs tab adopts the shared sheet

**Files:**
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/LibraryScreen.kt` (the `TracksTab` composable, currently ~lines 1091-1340)

Context you need: `TracksTab` currently opens a hand-rolled `ModalBottomSheet` with local `BottomSheetActionRow`s (Play Next / Add to Queue / Share / Delete). It already holds `selectedTrack`, `trackToShare`, `trackToDelete` states and the delete-confirm `AlertDialog` with the "also block" checkbox — **keep all of those**. The shared sheet adds Save-to-Playlist and Download/Remove, which we wire through the *existing batch* ViewModel methods with single-element lists — no new ViewModel surface.

- [ ] **Step 1: Extend `TracksTab`'s parameter list**

`TracksTab` needs the data/hooks for the two new rows. Add parameters (and thread them from the `LibraryContent` call site, which gets them from the `LibraryScreen` root where `userPlaylists` and `viewModel` are already in scope):

```kotlin
private fun TracksTab(
    tracks: List<Track>,
    currentlyPlayingTrackId: Long?,
    onTrackClick: (Track) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onDeleteTrack: (Track, Boolean) -> Unit,
    anyServiceConnected: Boolean,
    selection: SelectionState,
    userPlaylists: List<com.stash.core.ui.components.PlaylistInfo>,
    onSaveToPlaylist: (trackId: Long, playlistId: Long) -> Unit,
    onCreatePlaylistWithTrack: (name: String, trackId: Long) -> Unit,
    onDownloadTrack: (Long) -> Unit,
    onRemoveDownloadTrack: (Long) -> Unit,
    header: @Composable () -> Unit = {},
)
```

At the `LibraryScreen` root, map the callbacks onto existing batch methods (one-element lists — deliberate reuse, no new VM code):

```kotlin
userPlaylists = userPlaylists.map { com.stash.core.ui.components.PlaylistInfo(it.id, it.name, it.trackCount) },
onSaveToPlaylist = { trackId, playlistId -> viewModel.saveSelectedToPlaylist(listOf(trackId), playlistId) },
onCreatePlaylistWithTrack = { name, trackId -> viewModel.createPlaylistAndAddTracks(name, listOf(trackId)) },
onDownloadTrack = { viewModel.downloadSelected(listOf(it)) },
onRemoveDownloadTrack = { viewModel.removeDownloadsForSelected(listOf(it)) },
```

(`LibraryContent` is a pass-through — add the same params there and forward.)

- [ ] **Step 2: Replace the hand-rolled sheet body**

Inside `TracksTab`, add one more state near `trackToShare`:

```kotlin
    // Track whose Save-to-Playlist sheet is open (single-track path).
    var trackToSave by remember { mutableStateOf<Track?>(null) }
```

Replace the entire `selectedTrack?.let { ... ModalBottomSheet ... }` block (header Column, divider, four `BottomSheetActionRow`s, trailing `Spacer`) with:

```kotlin
    // ── Context-menu bottom sheet (shared component) ────────────────────
    selectedTrack?.let { track ->
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { selectedTrack = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            com.stash.core.ui.components.TrackOptionsSheet(
                track = track,
                onPlayNext = { onPlayNext(it); selectedTrack = null },
                onAddToQueue = { onAddToQueue(it); selectedTrack = null },
                onSaveToPlaylist = { trackToSave = it; selectedTrack = null },
                onShare = { trackToShare = it; selectedTrack = null },
                onDownload = { onDownloadTrack(it.id); selectedTrack = null },
                onRemoveDownload = { onRemoveDownloadTrack(it.id); selectedTrack = null },
                onDelete = { trackToDelete = it; selectedTrack = null },
            )
        }
    }

    // ── Save to Playlist sheet (single-track) ───────────────────────────
    trackToSave?.let { track ->
        com.stash.core.ui.components.SaveToPlaylistSheet(
            playlists = userPlaylists,
            onSaveToPlaylist = { playlistId ->
                onSaveToPlaylist(track.id, playlistId)
                trackToSave = null
            },
            onCreatePlaylist = { name ->
                onCreatePlaylistWithTrack(name, track.id)
                trackToSave = null
            },
            onDismiss = { trackToSave = null },
        )
    }
```

Then DELETE the now-unused `private fun BottomSheetActionRow(...)` at the bottom of the file, and remove imports that go dead (`Icons.Default.Share` etc. — let the compiler tell you; `FlacBadge` usage in the old sheet header is gone too, but `TrackListItem` still shows the badge in rows, and `FlacBadge` lives in `core.ui`, so only the *import in this file* may become unused).

The `trackToShare?.let { ShareTrackSheet(...) }` block and the `trackToDelete?.let { AlertDialog(...) }` block stay **exactly as they are**.

- [ ] **Step 3: Compile**

Run: `./gradlew :feature:library:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run the library unit tests (filtered)**

Run: `./gradlew :feature:library:testDebugUnitTest --tests 'com.stash.feature.library.*'`
Expected: whatever passed before still passes (this task changed no logic — pure UI rewiring). If the module has no tests, the task's gate is Step 3.

- [ ] **Step 5: Commit**

```bash
git add feature/library/src/main/kotlin/com/stash/feature/library/LibraryScreen.kt
git commit -m "feat(library): Songs tab adopts the shared TrackOptionsSheet (adds Save-to-Playlist + Download; drops duplicate sheet)"
```

### Task 3: Liked tab — real ⋮, long-press multiselect, selection bar

**Files:**
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/LibraryScreen.kt` (`LikedTab` ~lines 589-638, the selection-overlay gate ~lines 195-236, and `LikedTab`'s call site in `LibraryContent`)

- [ ] **Step 1: Extend `LikedTab` to mirror `TracksTab`'s wiring**

New signature (add everything `TracksTab` has; `onDeleteTrack` too):

```kotlin
@Composable
private fun LikedTab(
    tracks: List<Track>,
    filter: LikedFilter,
    sources: Set<LikedFilter>,
    currentlyPlayingTrackId: Long?,
    onSelectSource: (LikedFilter) -> Unit,
    onTrackClick: (Track) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onDeleteTrack: (Track, Boolean) -> Unit,
    selection: SelectionState,
    userPlaylists: List<com.stash.core.ui.components.PlaylistInfo>,
    onSaveToPlaylist: (trackId: Long, playlistId: Long) -> Unit,
    onCreatePlaylistWithTrack: (name: String, trackId: Long) -> Unit,
    onDownloadTrack: (Long) -> Unit,
    onRemoveDownloadTrack: (Long) -> Unit,
)
```

Inside, add the same four local states (`selectedTrack`, `trackToShare`, `trackToSave`, `trackToDelete`) and paste the same three blocks from Task 2 (shared `TrackOptionsSheet` sheet, `SaveToPlaylistSheet`, and a delete-confirm `AlertDialog` — copy the `AlertDialog` block verbatim from `TracksTab`, it operates on the same `onDeleteTrack(track, alsoBlacklist)` callback).

Replace the row wiring:

```kotlin
            items(tracks, key = { it.id }) { track ->
                TrackListItem(
                    track = track,
                    onClick = {
                        if (selection.isActive) selection.toggle(track.id)
                        else onTrackClick(track)
                    },
                    isPlaying = track.id == currentlyPlayingTrackId,
                    onLongPress = { if (!selection.isActive) selection.enter(track.id) },
                    selectionActive = selection.isActive,
                    selected = selection.isSelected(track.id),
                    onMoreClick = { selectedTrack = track },
                )
            }
```

Also add `contentPadding = PaddingValues(bottom = if (selection.isActive) 140.dp else 0.dp)` to LikedTab's `LazyColumn` (same reason as TracksTab: clear the selection bar).

Thread the new params at `LikedTab`'s call site in `LibraryContent` (same values as `TracksTab`'s; `onDeleteTrack = onDeleteTrack`, `onPlayNext = onPlayNext`, `onAddToQueue = onAddToQueue`).

- [ ] **Step 2: Extend the selection overlay to the Liked tab**

At the `LibraryScreen` root (~line 195), the selected-track source and the gate are Tracks-only today. Change to:

```kotlin
        val activeSelectableTracks =
            if (state.activeTab == LibraryTab.LIKED) likedTracks else state.tracks
        val selectedTracks = activeSelectableTracks.filter { it.id in selection.selectedIds }
```

(keep `selectedIds`/`allDownloaded` as-is, they derive from `selectedTracks`) and:

```kotlin
        if (state.activeTab == LibraryTab.TRACKS || state.activeTab == LibraryTab.LIKED) {
            SelectionScaffoldOverlay(
                selection = selection,
                allIds = activeSelectableTracks.map { it.id },
                actions = selectionActions,
            )
        }
```

The `LaunchedEffect(state.activeTab) { selection.clear() }` already guarantees no cross-tab leakage — do not touch it.

- [ ] **Step 3: Compile + filtered tests**

Run: `./gradlew :feature:library:compileDebugKotlin :feature:library:testDebugUnitTest --tests 'com.stash.feature.library.*'`
Expected: BUILD SUCCESSFUL, no new failures.

- [ ] **Step 4: Commit**

```bash
git add feature/library/src/main/kotlin/com/stash/feature/library/LibraryScreen.kt
git commit -m "feat(library): Liked tab gets the shared track sheet, long-press multiselect, and the selection bar"
```

### Task 4: Section A device checkpoint

- [ ] **Step 1: Install**

Run: `./gradlew :app:installDebug`
Expected: `Installed on 1 device.`

- [ ] **Step 2: Report for human smoke test** — the orchestrator asks the user (their daily phone; do not inject input) to verify: Songs ⋮ → sheet has Play Next / Add to Queue / Save to Playlist / Download-or-Remove / Share / Delete, all firing; Liked ⋮ opens the same sheet; Liked long-press enters selection with the bottom bar; delete still confirms with the block checkbox.

---

## Section B — Radio radar-sweep feedback (spec §1)

### Task 5: `RadioStartResult` sealed type + repository returns it

**Files:**
- Create: `core/model/src/main/kotlin/com/stash/core/model/RadioStartResult.kt`
- Modify: `core/media/src/main/kotlin/com/stash/core/media/PlayerRepository.kt` (~lines 168-181)
- Modify: `core/media/src/main/kotlin/com/stash/core/media/PlayerRepositoryImpl.kt` (~lines 686-745)
- Modify: every other `startRadio(` caller — run `grep -rn "startRadio(" --include='*.kt' core/ data/ feature/ app/` and update each. Known production callers: `core/media/.../actions/TrackActionsDelegate.kt` (~line 403), `feature/search/.../ArtistProfileViewModel.kt` (~line 250), and `NowPlayingViewModel` (Task 6). Boolean-style mapping: where a caller checked `if (!started)`, use `if (result !is RadioStartResult.Started)` and keep its existing message copy.
- Modify: **four existing TEST files that stub/assert the Boolean — they are compile blockers for their modules' test source sets** (grep will find them; listed so nothing is missed):
  - `core/media/src/test/.../PlayerRepositoryRadioTest.kt` — asserts `.isTrue()`/`.isFalse()` (~lines 71, 85, 103, 146). REWORK per spec §1's test requirement: assert the distinct result values (`isEqualTo(RadioStartResult.Started)`, `.StreamingOff` for the streaming-off guard, `.PlayerNotReady` for the null controller, `.NoStation` for the empty batch).
  - `core/media/src/test/.../listening/ListeningRecorderSkipTest.kt` (~line 84) — fake `override suspend fun startRadio(...) = false` → `= RadioStartResult.StreamingOff` (any non-Started value; add the import).
  - `core/media/src/test/.../actions/TrackActionsDelegateQueueActionsTest.kt` (~lines 99, 114) — MockK `returns true`/`returns false` → `returns RadioStartResult.Started` / `returns RadioStartResult.NoStation`.
  - `feature/search/src/test/.../ArtistProfileViewModelTest.kt` (~line 217) — Mockito `doReturn true` → `doReturn RadioStartResult.Started`.

- [ ] **Step 1: Create the sealed type**

```kotlin
package com.stash.core.model

/**
 * Outcome of [com.stash.core.media.PlayerRepository.startRadio]. Replaces a
 * Boolean that collapsed three distinct failures into one misleading
 * "needs Online mode" toast (issue: silent radio button).
 */
sealed interface RadioStartResult {
    /** Station built and spliced/queued; the seed label is live. */
    data object Started : RadioStartResult

    /** Streaming (Online mode) is off — radio can't stream tracks. */
    data object StreamingOff : RadioStartResult

    /** MediaController unavailable (player still starting / connection lost). */
    data object PlayerNotReady : RadioStartResult

    /** Generator produced an empty first batch — no similar tracks found. */
    data object NoStation : RadioStartResult
}
```

- [ ] **Step 2: Change the interface**

In `PlayerRepository.kt` replace the `startRadio` declaration's return type and KDoc tail:

```kotlin
    /**
     * Start a radio station seeded from an artist or track. Builds a balanced
     * queue and arms self-extension; replaces any current queue/station.
     * Returns a [com.stash.core.model.RadioStartResult] naming the exact
     * outcome; anything but [RadioStartResult.Started] is a no-op.
     *
     * [keepCurrent] = true (the Now Playing "Start radio from this song" case):
     * the seed IS the currently-playing track, so DON'T restart it — keep the
     * current item playing and splice the discoveries around it.
     */
    suspend fun startRadio(
        seed: com.stash.core.data.radio.RadioSeed,
        keepCurrent: Boolean = false,
    ): com.stash.core.model.RadioStartResult
```

- [ ] **Step 3: Update the implementation**

In `PlayerRepositoryImpl.startRadio`, the three guards and the tail:

```kotlin
        if (!streamingPreference.current()) return RadioStartResult.StreamingOff
        val controller = ensureController() ?: return RadioStartResult.PlayerNotReady
        val (session, firstBatch) = radioGenerator.start(seed)
        if (firstBatch.isEmpty()) return RadioStartResult.NoStation
```

and the final `return true` becomes `return RadioStartResult.Started`. Add the import `com.stash.core.model.RadioStartResult`.

- [ ] **Step 4: Fix every other caller** (grep from the Files note). For boolean-style callers the mechanical mapping is `val started = playerRepository.startRadio(...) is RadioStartResult.Started` — keep their existing user-facing copy; ONLY NowPlaying (Task 6) gets the per-cause messages.

- [ ] **Step 5: Compile main AND test sources of every module that touches startRadio, then run the reworked tests**

Run: `./gradlew :core:media:compileDebugKotlin :feature:nowplaying:compileDebugKotlin :feature:search:compileDebugKotlin :app:compileDebugKotlin`
Then: `./gradlew :core:media:testDebugUnitTest --tests '*PlayerRepositoryRadioTest*' --tests '*ListeningRecorderSkipTest*' --tests '*TrackActionsDelegateQueueActionsTest*' :feature:search:testDebugUnitTest --tests '*ArtistProfileViewModelTest*'`
Expected: BUILD SUCCESSFUL, all filtered tests pass. (NowPlaying's VM still compiles against the old usage — fix it minimally here with the boolean-style mapping; Task 6 replaces it properly. Run :core:media tests LOCALLY only — the module's test task is CI-flaky, not locally.)

- [ ] **Step 6: Commit**

```bash
git add core/model/src/main/kotlin/com/stash/core/model/RadioStartResult.kt core/media/src/main/kotlin/com/stash/core/media/PlayerRepository.kt core/media/src/main/kotlin/com/stash/core/media/PlayerRepositoryImpl.kt core/media/src/main/kotlin/com/stash/core/media/actions/TrackActionsDelegate.kt feature/search/src/main/kotlin/com/stash/feature/search/ArtistProfileViewModel.kt core/media/src/test/kotlin/com/stash/core/media/PlayerRepositoryRadioTest.kt core/media/src/test/kotlin/com/stash/core/media/listening/ListeningRecorderSkipTest.kt core/media/src/test/kotlin/com/stash/core/media/actions/TrackActionsDelegateQueueActionsTest.kt feature/search/src/test/kotlin/com/stash/feature/search/ArtistProfileViewModelTest.kt <plus any other files the grep surfaced>
git commit -m "feat(media): startRadio returns RadioStartResult instead of a cause-collapsing Boolean"
```

### Task 6: ViewModel tuning state + per-cause messages (TDD)

**Files:**
- Modify: `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingViewModel.kt` (~lines 198-218)
- Test: `feature/nowplaying/src/test/kotlin/com/stash/feature/nowplaying/NowPlayingViewModelRadioTest.kt` (create; if `feature/nowplaying` has no `src/test` yet, create the directory — mirror another feature module's test-deps block in `feature/nowplaying/build.gradle.kts` if test deps are missing, copying from `feature/library`'s)

- [ ] **Step 1: Write the failing test**

The VM has many constructor deps — mock all with `mockk(relaxed = true)` and stub only what the radio path touches. Key stubs: `playerRepository.radioSeedLabel returns MutableStateFlow(null)`; the VM's `uiState` must hold a current track — inspect how existing NowPlaying VM tests (if any) seed it; if none exist, drive via the same `playerRepository.playerState`/track flow the VM combines (read the VM's `_uiState` construction first and copy the minimal stubbing). The four cases:

```kotlin
@Test
fun `startRadio maps each failure to its own message and toggles tuning`() = runTest {
    coEvery { playerRepository.startRadio(any(), any()) } returns RadioStartResult.StreamingOff
    vm.startRadioFromCurrent()
    advanceUntilIdle()
    assertEquals("Radio needs Online mode — turn on streaming.", messages.last())

    coEvery { playerRepository.startRadio(any(), any()) } returns RadioStartResult.PlayerNotReady
    vm.startRadioFromCurrent(); advanceUntilIdle()
    assertEquals("Player is still starting — try again.", messages.last())

    coEvery { playerRepository.startRadio(any(), any()) } returns RadioStartResult.NoStation
    vm.startRadioFromCurrent(); advanceUntilIdle()
    assertEquals("Couldn't find similar tracks for this song.", messages.last())

    coEvery { playerRepository.startRadio(any(), any()) } returns RadioStartResult.Started
    vm.startRadioFromCurrent(); advanceUntilIdle()
    // Started emits no message; tuning returned to false.
    assertFalse(vm.radioTuning.value)
}
```

plus a re-entrancy test: `startRadioFromCurrent` while `radioTuning` is true must not call the repo a second time (`coVerify(exactly = 1)`).

- [ ] **Step 2: Run it — expect FAIL** (`radioTuning` unresolved / messages wrong)

Run: `./gradlew :feature:nowplaying:testDebugUnitTest --tests '*NowPlayingViewModelRadioTest*'`

- [ ] **Step 3: Implement**

```kotlin
    /** True from radio tap until the station build returns (success or not). */
    private val _radioTuning = MutableStateFlow(false)
    val radioTuning: StateFlow<Boolean> = _radioTuning.asStateFlow()

    /** Start a song radio seeded from the currently-playing track. */
    fun startRadioFromCurrent() {
        val track = _uiState.value.currentTrack ?: return
        if (_radioTuning.value) return // ignore taps while tuning
        // Set SYNCHRONOUSLY (not inside launch) so a double-tap in the same
        // frame can't pass the guard twice before the coroutine dispatches.
        _radioTuning.value = true
        viewModelScope.launch {
            try {
                val result = playerRepository.startRadio(
                    com.stash.core.data.radio.RadioSeed.Song(
                        title = track.title, artist = track.artist, ytVideoId = track.youtubeId,
                    ),
                    keepCurrent = true,
                )
                when (result) {
                    RadioStartResult.Started -> Unit // sweep locks; no toast
                    RadioStartResult.StreamingOff ->
                        _userMessages.tryEmit("Radio needs Online mode — turn on streaming.")
                    RadioStartResult.PlayerNotReady ->
                        _userMessages.tryEmit("Player is still starting — try again.")
                    RadioStartResult.NoStation ->
                        _userMessages.tryEmit("Couldn't find similar tracks for this song.")
                }
            } finally {
                _radioTuning.value = false
            }
        }
    }
```

(imports: `RadioStartResult`; remove the old `if (!started)` line.)

- [ ] **Step 4: Run the test — expect PASS**, then **Step 5: Commit** (`git add` the VM + test file; message `feat(nowplaying): radio tuning state + per-cause start-radio messages`).

### Task 7: RadarSweep composable + TopBar integration

**Files:**
- Create: `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/ui/RadarSweep.kt`
- Modify: `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingScreen.kt` (collect `radioTuning` ~line 130; pass through the `TopBar` call ~line 346; render in the radio slot ~line 610)

- [ ] **Step 1: Create `RadarSweep.kt`** (complete file):

```kotlin
package com.stash.feature.nowplaying.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Radar-style tuning indicator drawn AROUND the radio icon (spec §1,
 * mockup option B). Two layers on one Canvas:
 *
 *  - a faint full "track" ring, always on while [tuning] or [lock] shows;
 *  - a 40° arc that rotates once per ~1.15s while [tuning]; when [lock]
 *    fires (station ready) the arc snaps to a full 360° circle which the
 *    caller fades out by dropping [lock] after [LOCK_HOLD_MS].
 *
 * Runs on every theme including AMOLED — this is icon-local feedback,
 * not an ambient background, so the pure-black no-op convention does
 * not apply.
 */
@Composable
fun RadarSweep(
    tuning: Boolean,
    lock: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "radarSweep")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1150, easing = LinearEasing)),
        label = "radarAngle",
    )
    // Lock: arc sweeps open to a full circle quickly, then the caller
    // removes the composable (fade handled by state, not alpha here).
    val sweepDegrees by animateFloatAsState(
        targetValue = if (lock) 360f else 40f,
        animationSpec = tween(durationMillis = 220),
        label = "radarSweepDegrees",
    )
    if (!tuning && !lock) return
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
        val inset = stroke.width
        val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
        val topLeft = Offset(inset, inset)
        // Track ring.
        drawArc(
            color = color.copy(alpha = 0.22f),
            startAngle = 0f, sweepAngle = 360f, useCenter = false,
            topLeft = topLeft, size = arcSize, style = stroke,
        )
        // Sweep arc (rotates while tuning; full circle on lock).
        drawArc(
            color = color.copy(alpha = if (lock) 0.95f else 0.8f),
            startAngle = if (lock) 0f else angle,
            sweepAngle = sweepDegrees,
            useCenter = false,
            topLeft = topLeft, size = arcSize, style = stroke,
        )
    }
}

/** How long the locked full circle holds before the caller clears it. */
const val LOCK_HOLD_MS = 250L
```

(add `import androidx.compose.ui.unit.dp` — used by `1.6.dp`.)

- [ ] **Step 2: Wire the screen**

In `NowPlayingScreen`: collect `val radioTuning by viewModel.radioTuning.collectAsStateWithLifecycle()` next to `radioLabel` (~line 130). Derive the lock pulse where both are in scope:

```kotlin
    // One-shot lock pulse: fires on the tuning→active transition.
    var radioLock by remember { mutableStateOf(false) }
    LaunchedEffect(radioTuning, radioLabel) {
        if (!radioTuning && radioLabel != null) {
            radioLock = true
            kotlinx.coroutines.delay(com.stash.feature.nowplaying.ui.LOCK_HOLD_MS)
            radioLock = false
        }
    }
```

Note: this also pulses when the screen re-opens with a station already active — acceptable (it reads as "station is live") — but DON'T pulse on every recomposition: `LaunchedEffect(radioTuning, radioLabel)` keys only on real state changes.

Pass `radioTuning = radioTuning`, `radioLock = radioLock` into `TopBar` (add both params), and change the radio slot inside `TopBar`:

```kotlin
        if (hasTrack) {
            Box(contentAlignment = Alignment.Center) {
                com.stash.feature.nowplaying.ui.RadarSweep(
                    tuning = radioTuning,
                    lock = radioLock,
                    color = accentColor,
                    modifier = Modifier.matchParentSize(),
                )
                IconButton(
                    onClick = {
                        when {
                            radioTuning -> Unit // ignore taps while tuning
                            radioActive -> onStopRadio()
                            else -> onStartRadio()
                        }
                    },
                ) {
                    Icon(
                        imageVector = Icons.Default.Radio,
                        contentDescription = when {
                            radioTuning -> "Tuning radio"
                            radioActive -> "Stop radio"
                            else -> "Start radio"
                        },
                        tint = if (radioActive || radioTuning) accentColor else npInk(),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
```

(`Box` import already present in the file's ambit; add if missing.)

- [ ] **Step 3: Compile** — `./gradlew :feature:nowplaying:compileDebugKotlin` → BUILD SUCCESSFUL.

- [ ] **Step 4: Run section tests** — `./gradlew :feature:nowplaying:testDebugUnitTest --tests '*NowPlayingViewModelRadioTest*'` → PASS.

- [ ] **Step 5: Commit**

```bash
git add feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/ui/RadarSweep.kt feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingScreen.kt
git commit -m "feat(nowplaying): radar-sweep tuning indicator on the radio button"
```

- [ ] **Step 6: Install + human smoke** — `./gradlew :app:installDebug`; orchestrator asks the user: tap radio on a playing song → sweep spins immediately, locks to a ring, icon stays accent; toggle streaming OFF in settings → tap radio → correct toast.

---

## Section C — Batch FLAC upgrade (spec §3)

### Task 8: NON_FLAC filter (TDD)

**Files:**
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/LibraryUiState.kt` (~line 59)
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/LibrarySortFilterSheet.kt` (filters list ~line 52)
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/LibraryViewModel.kt` (when-branch ~line 158)
- Test: `feature/library/src/test/kotlin/com/stash/feature/library/LibraryViewModelSourceFilterTest.kt` (create if absent; check for an existing LibraryViewModel test to extend first — extend rather than duplicate scaffolding)

- [ ] **Step 1: Failing test** — seed the mocked `musicRepository.getAllTracks()` flow with three tracks: downloaded opus, downloaded flac, NOT-downloaded opus. Set filter NON_FLAC, collect `uiState`, assert only the downloaded-opus track remains:

```kotlin
@Test
fun `NON_FLAC keeps only downloaded lossy tracks`() = runTest {
    // tracks: (downloaded, "opus"), (downloaded, "flac"), (not downloaded, "opus")
    vm.setSourceFilter(SourceFilter.NON_FLAC)
    val tracks = vm.uiState.first { it.sourceFilter == SourceFilter.NON_FLAC }.tracks
    assertEquals(listOf(downloadedOpus.id), tracks.map { it.id })
}
```

- [ ] **Step 2: Run — FAIL** (`NON_FLAC` unresolved): `./gradlew :feature:library:testDebugUnitTest --tests '*LibraryViewModelSourceFilterTest*'`

- [ ] **Step 3: Implement.** Enum: `enum class SourceFilter { ALL, YOUTUBE, SPOTIFY, FLAC, NON_FLAC }` (append to the KDoc: "NON_FLAC is the inverse — downloaded tracks still on a lossy codec, i.e. the batch-upgrade worklist."). Sheet: add `"Non-FLAC" to SourceFilter.NON_FLAC` after the FLAC pair. VM branch:

```kotlin
            SourceFilter.NON_FLAC -> allTracks.filter {
                it.isDownloaded && it.fileFormat.lowercase() !in LOSSLESS_CODECS
            }
```

- [ ] **Step 4: Run — PASS.** **Step 5: Commit** (3 source files + test; `feat(library): Non-FLAC filter chip — the batch-upgrade worklist`).

### Task 9: `flac_upgrade_queue` table — entity, status enum, DAO, migration (TDD)

**Files:**
- Create: `core/model/src/main/kotlin/com/stash/core/model/FlacUpgradeStatus.kt`
- Create: `core/data/src/main/kotlin/com/stash/core/data/db/entity/FlacUpgradeQueueEntity.kt`
- Create: `core/data/src/main/kotlin/com/stash/core/data/db/dao/FlacUpgradeQueueDao.kt`
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt` (entities list, `version = 35`, `MIGRATION_34_35`, new abstract dao accessor)
- Modify: `core/data/src/main/kotlin/com/stash/core/data/di/DatabaseModule.kt` (append `StashDatabase.MIGRATION_34_35`)
- Test: `core/data/src/test/kotlin/com/stash/core/data/db/dao/FlacUpgradeQueueDaoTest.kt`

- [ ] **Step 1: Failing test** (Robolectric, mirror `TrackDaoFindExistingForBatchTest` setup: in-memory `StashDatabase`, `@Config(manifest = Config.NONE, sdk = [33])`):

```kotlin
@Test fun `startBatch clears previous rows and inserts fresh PENDING`() = runTest {
    val t1 = trackDao.insert(TrackEntity(title = "A", artist = "x", canonicalTitle = "a", canonicalArtist = "x"))
    val t2 = trackDao.insert(TrackEntity(title = "B", artist = "x", canonicalTitle = "b", canonicalArtist = "x"))
    dao.startBatch(listOf(t1))
    dao.setStatus(t1, FlacUpgradeStatus.DONE)
    dao.startBatch(listOf(t2))
    assertEquals(listOf(t2), dao.pendingTrackIds())
    assertEquals(1, dao.countAll()) // t1's DONE row was cleared by the new batch
}

@Test fun `counts reflect per-status transitions`() = runTest { /* insert 3, set DONE/NO_MATCH/leave PENDING, assert countByStatus */ }
```

- [ ] **Step 2: Run — FAIL** (unresolved references): `./gradlew :core:data:testDebugUnitTest --tests '*FlacUpgradeQueueDaoTest*'`

- [ ] **Step 3: Implement.**

`FlacUpgradeStatus.kt`:

```kotlin
package com.stash.core.model

/** Per-row state in the batch FLAC-upgrade queue (spec §3). Room persists the name. */
enum class FlacUpgradeStatus { PENDING, DONE, NO_MATCH, FAILED }
```

`FlacUpgradeQueueEntity.kt`:

```kotlin
package com.stash.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.stash.core.model.FlacUpgradeStatus
import java.time.Instant

/**
 * One track in the current batch "Upgrade to FLAC" run (spec §3). A new
 * batch clears the previous batch's rows (single-batch semantics); rows
 * are the worker's persisted worklist so the batch survives process death
 * and stays cancelable. Nothing reads terminal rows — they linger only
 * until the next batch's clear-and-insert.
 */
@Entity(
    tableName = "flac_upgrade_queue",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["track_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["status"])],
)
data class FlacUpgradeQueueEntity(
    @PrimaryKey
    @ColumnInfo(name = "track_id")
    val trackId: Long,

    val status: FlacUpgradeStatus = FlacUpgradeStatus.PENDING,

    @ColumnInfo(name = "enqueued_at")
    val enqueuedAt: Instant = Instant.now(),
)
```

`FlacUpgradeQueueDao.kt`:

```kotlin
package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.stash.core.data.db.entity.FlacUpgradeQueueEntity
import com.stash.core.model.FlacUpgradeStatus

/** Persisted worklist for the batch FLAC-upgrade worker (spec §3). */
@Dao
interface FlacUpgradeQueueDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<FlacUpgradeQueueEntity>)

    @Query("DELETE FROM flac_upgrade_queue")
    suspend fun clearAll()

    /** New batch = replace the old one wholesale (single-batch semantics). */
    @Transaction
    suspend fun startBatch(trackIds: List<Long>) {
        clearAll()
        insertAll(trackIds.map { FlacUpgradeQueueEntity(trackId = it) })
    }

    @Query("SELECT track_id FROM flac_upgrade_queue WHERE status = 'PENDING' ORDER BY enqueued_at ASC")
    suspend fun pendingTrackIds(): List<Long>

    @Query("UPDATE flac_upgrade_queue SET status = :status WHERE track_id = :trackId")
    suspend fun setStatus(trackId: Long, status: FlacUpgradeStatus)

    @Query("SELECT COUNT(*) FROM flac_upgrade_queue WHERE status = :status")
    suspend fun countByStatus(status: FlacUpgradeStatus): Int

    @Query("SELECT COUNT(*) FROM flac_upgrade_queue")
    suspend fun countAll(): Int

    /** Cancel: drop the not-yet-processed remainder, keep terminal rows. */
    @Query("DELETE FROM flac_upgrade_queue WHERE status = 'PENDING'")
    suspend fun clearPending()
}
```

`StashDatabase.kt`: add `FlacUpgradeQueueEntity::class` to `entities`, bump `version = 35`, add `abstract fun flacUpgradeQueueDao(): FlacUpgradeQueueDao`, and in the companion:

```kotlin
        /**
         * v34 → v35: add the flac_upgrade_queue worklist table for the batch
         * "Upgrade to FLAC" worker (spec 2026-07-22 §3). Purely additive.
         */
        val MIGRATION_34_35 = object : Migration(34, 35) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS flac_upgrade_queue (
                        track_id INTEGER NOT NULL PRIMARY KEY,
                        status TEXT NOT NULL,
                        enqueued_at INTEGER NOT NULL,
                        FOREIGN KEY(track_id) REFERENCES tracks(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_flac_upgrade_queue_status ON flac_upgrade_queue(status)")
            }
        }
```

`DatabaseModule.kt`: append `StashDatabase.MIGRATION_34_35,` to `addMigrations`.

**Migration-fidelity check:** after the entity compiles, diff the generated schema JSON (`core/data/schemas/.../35.json`, created on build because `exportSchema = true`) against the migration SQL — column affinities and the FK/index must match exactly or Room's validation crashes at open. `Instant` persists via the existing converter — check `Converters` for how `DownloadQueueEntity.createdAt` maps (INTEGER epoch millis) and mirror it.

- [ ] **Step 4: Run — PASS**: `./gradlew :core:data:testDebugUnitTest --tests '*FlacUpgradeQueueDaoTest*'`
- [ ] **Step 5: Commit** (all six files + schema JSON; `feat(data): flac_upgrade_queue worklist table (DB v35)`).

### Task 10: `FlacUpgradeWorker` + enqueuer (TDD)

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/sync/workers/FlacUpgradeWorker.kt`
- Create: `core/data/src/main/kotlin/com/stash/core/data/sync/FlacUpgradeEnqueuer.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/sync/workers/FlacUpgradeWorkerTest.kt`

Deviation from spec noted and justified: the worker lives in `:core:data`, not `:data:download` — every dependency it needs (`LosslessUpgrader` interface, `FlacUpgradeQueueDao`, `TrackDao`, `SyncNotificationManager`) is already in `:core:data`, and the Room/worker test scaffolding lives here. The `LosslessUpgrader` binding stays in `:data:download` (app graph supplies it at runtime, exactly like `NowPlayingViewModel` consumes it today).

- [ ] **Step 1: Failing test** (mirror `DiffWorkerTest`: Robolectric + in-memory DB + `TestListenableWorkerBuilder` + fake upgrader):

```kotlin
@Test fun `drains pending rows and records per-track outcomes`() = runBlocking {
    val ids = seedTracks("opus" to 3) // helper inserts 3 downloaded opus TrackEntity rows
    db.flacUpgradeQueueDao().startBatch(ids)
    val results = ArrayDeque(listOf(UpgradeResult.Upgraded, UpgradeResult.NoMatch, UpgradeResult.Error))
    val fake = LosslessUpgrader { results.removeFirst() }   // fun-interface style; else an object
    val result = buildWorker(fake).doWork()
    assertTrue(result is ListenableWorker.Result.Success)
    assertEquals(1, dao.countByStatus(FlacUpgradeStatus.DONE))
    assertEquals(1, dao.countByStatus(FlacUpgradeStatus.NO_MATCH))
    assertEquals(1, dao.countByStatus(FlacUpgradeStatus.FAILED))
    assertEquals(0, dao.countByStatus(FlacUpgradeStatus.PENDING))
}

@Test fun `skips rows whose track vanished`() = runBlocking { /* startBatch with a bogus id — wait: FK blocks bogus ids; instead insert then delete the track, CASCADE removes the row; assert worker completes with remaining rows processed */ }

@Test fun `empty queue succeeds immediately`() = runBlocking { /* no rows → Result.success, upgrader never called */ }
```

(`LosslessUpgrader` is a normal interface — implement the fake as `object : LosslessUpgrader { override suspend fun upgradeToLossless(track: Track) = results.removeFirst() }`.)

- [ ] **Step 2: Run — FAIL**: `./gradlew :core:data:testDebugUnitTest --tests '*FlacUpgradeWorkerTest*'`

- [ ] **Step 3: Implement the worker** (complete file):

```kotlin
package com.stash.core.data.sync.workers

import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.stash.core.data.db.dao.FlacUpgradeQueueDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.lossless.LosslessUpgrader
import com.stash.core.data.sync.SyncNotificationManager
import com.stash.core.model.FlacUpgradeStatus
import com.stash.core.model.UpgradeResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * Drains the flac_upgrade_queue: one lossless upgrade per PENDING row
 * (spec 2026-07-22 §3). Foreground worker — batches run for hours behind
 * the rate limiters, so it needs the DATA_SYNC promotion and a progress
 * notification with a Cancel action (pattern: TrackDownloadWorker).
 *
 * Rate limiting, token pools, and captcha-herd safety all live inside
 * [LosslessUpgrader]'s pipeline — this loop adds none of its own pacing.
 *
 * Cancellation: WorkManager cancels the coroutine; the CancellationException
 * handler drops the unprocessed PENDING remainder so a stale batch never
 * self-resumes later, then rethrows (project rule).
 */
@HiltWorker
class FlacUpgradeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val queueDao: FlacUpgradeQueueDao,
    private val trackDao: TrackDao,
    private val losslessUpgrader: LosslessUpgrader,
    private val syncNotificationManager: SyncNotificationManager,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        createForegroundInfo(text = "Preparing…", progress = -1f)

    override suspend fun doWork(): Result {
        val pending = queueDao.pendingTrackIds()
        if (pending.isEmpty()) return Result.success()
        val alreadyTerminal = queueDao.countAll() - pending.size
        val total = queueDao.countAll()

        var upgraded = 0
        var noMatch = 0
        var failed = 0
        try {
            pending.forEachIndexed { index, trackId ->
                // getById returns the ROOM ENTITY; the upgrader takes the
                // domain model — map via TrackMapper's TrackEntity.toDomain()
                // (same module: com.stash.core.data.mapper — add the import).
                val track = trackDao.getById(trackId)?.toDomain()
                if (track == null) {
                    queueDao.setStatus(trackId, FlacUpgradeStatus.FAILED)
                    failed++
                } else {
                    val status = when (losslessUpgrader.upgradeToLossless(track)) {
                        UpgradeResult.Upgraded -> { upgraded++; FlacUpgradeStatus.DONE }
                        UpgradeResult.NoMatch -> { noMatch++; FlacUpgradeStatus.NO_MATCH }
                        UpgradeResult.Error -> { failed++; FlacUpgradeStatus.FAILED }
                    }
                    queueDao.setStatus(trackId, status)
                }
                val done = alreadyTerminal + index + 1
                safeSetForeground(
                    createForegroundInfo(
                        text = "Upgrading to FLAC · $done/$total",
                        progress = done.toFloat() / total,
                    ),
                )
            }
        } catch (ce: CancellationException) {
            // User hit Cancel (or the system pulled the plug): drop the
            // remainder so the batch doesn't zombie-resume on retry.
            queueDao.clearPending()
            syncNotificationManager.cancelProgress()
            throw ce
        }

        syncNotificationManager.showFlacUpgradeSummary(
            upgraded = upgraded, noMatch = noMatch, failed = failed,
        )
        return Result.success(
            workDataOf(KEY_UPGRADED to upgraded, KEY_NO_MATCH to noMatch, KEY_FAILED to failed),
        )
    }

    private suspend fun safeSetForeground(info: ForegroundInfo) {
        runCatching { setForeground(info) }
            .onFailure { Log.w(TAG, "setForeground failed; continuing without notification update", it) }
    }

    private fun createForegroundInfo(text: String, progress: Float): ForegroundInfo {
        val cancelIntent = WorkManager.getInstance(applicationContext)
            .createCancelPendingIntent(id)
        val notification = syncNotificationManager.buildProgressNotification(
            title = "Upgrading to FLAC",
            text = text,
            progress = progress,
            cancelIntent = cancelIntent,
        )
        return ForegroundInfo(
            SyncNotificationManager.NOTIFICATION_ID_FLAC_UPGRADE,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "flac-upgrade-batch"
        const val KEY_UPGRADED = "flac_upgraded"
        const val KEY_NO_MATCH = "flac_no_match"
        const val KEY_FAILED = "flac_failed"
        private const val TAG = "FlacUpgradeWorker"
    }
}
```

Add to `SyncNotificationManager`: `const val NOTIFICATION_ID_FLAC_UPGRADE = 9005`, `const val NOTIFICATION_ID_FLAC_SUMMARY = 9006` (dedicated id — a FLAC summary must not clobber a sync summary), and:

```kotlin
    /** Summary for a finished batch FLAC upgrade (spec 2026-07-22 §3). */
    fun showFlacUpgradeSummary(upgraded: Int, noMatch: Int, failed: Int) {
        val text = buildList {
            add("$upgraded upgraded")
            if (noMatch > 0) add("$noMatch no FLAC found")
            if (failed > 0) add("$failed failed")
        }.joinToString(" · ")
        val notification = NotificationCompat.Builder(context, CHANNEL_SYNC_SUMMARY)
            .setContentTitle("FLAC upgrade complete")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(NOTIFICATION_ID_FLAC_SUMMARY, notification)
    }
```

`FlacUpgradeEnqueuer.kt` (pattern: `SingleTrackDownloadEnqueuer`, but constraints from the sync wifiOnly pref):

```kotlin
package com.stash.core.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.stash.core.data.db.dao.FlacUpgradeQueueDao
import com.stash.core.data.sync.workers.FlacUpgradeWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Seeds the flac_upgrade_queue and enqueues the unique batch worker.
 * KEEP policy: confirming while a batch runs appends rows (startBatch
 * replaced the set) that the live worker drains — no second worker.
 * Wi-Fi behavior follows the sync wifiOnly preference (spec §3).
 */
@Singleton
class FlacUpgradeEnqueuer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val queueDao: FlacUpgradeQueueDao,
    private val syncPreferencesManager: SyncPreferencesManager,
) {
    suspend fun startBatch(trackIds: List<Long>) {
        if (trackIds.isEmpty()) return
        queueDao.startBatch(trackIds)
        val wifiOnly = syncPreferencesManager.preferences.first().wifiOnly
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<FlacUpgradeWorker>()
            .setConstraints(constraints)
            .addTag("flac_upgrade")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            FlacUpgradeWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
```

**Check the actual accessor for wifiOnly on `SyncPreferencesManager`** (`preferences.first().wifiOnly` is the shape used by `BootReceiver` — read `SyncPreferencesManager.kt:80-105` and use its real flow property name).

- [ ] **Step 4: Run — PASS**: `./gradlew :core:data:testDebugUnitTest --tests '*FlacUpgradeWorkerTest*' --tests '*FlacUpgradeQueueDaoTest*'`
- [ ] **Step 5: Commit** (`feat(data): batch FLAC-upgrade worker, enqueuer, and progress/summary notifications`).

### Task 11: Selection action + confirm dialog with size estimate (TDD)

**Files:**
- Create: `feature/library/src/main/kotlin/com/stash/feature/library/FlacUpgradeEstimate.kt`
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/LibraryViewModel.kt` (inject `FlacUpgradeEnqueuer`; add `upgradeSelectedToFlac`)
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/LibraryScreen.kt` (selection action + dialog)
- Test: `feature/library/src/test/kotlin/com/stash/feature/library/FlacUpgradeEstimateTest.kt`

- [ ] **Step 1: Failing estimate test**

```kotlin
class FlacUpgradeEstimateTest {
    @Test
    fun `estimate is duration at 1000kbps and formats to GB`() {
        // 3 tracks × 4 min = 720s → 720 × 125_000 B = 90 MB
        val tracks = List(3) { track(durationMs = 240_000L) }
        val bytes = estimateFlacBytes(tracks)
        assertEquals(90_000_000L, bytes)
        assertEquals("0.1 GB", formatGb(bytes))
    }
    @Test
    fun `eligible filters non-downloaded and already-lossless`() { /* 4 tracks: downloaded-opus kept; flac, wav, not-downloaded dropped */ }
}
```

- [ ] **Step 2: Run — FAIL**: `./gradlew :feature:library:testDebugUnitTest --tests '*FlacUpgradeEstimateTest*'`

- [ ] **Step 3: Implement `FlacUpgradeEstimate.kt`:**

```kotlin
package com.stash.feature.library

import com.stash.core.model.Track
import java.util.Locale

/** FLAC-typical average bitrate for the pre-flight size estimate (spec §3). */
private const val FLAC_BYTES_PER_SECOND = 125_000L // ~1000 kbps

/** Lossless set duplicated from LibraryViewModel (same justification). */
private val LOSSLESS = setOf("flac", "alac", "wav", "ape", "tta", "wv", "aiff")

/** Tracks the batch can actually act on: downloaded and still lossy. */
fun eligibleForFlacUpgrade(tracks: List<Track>): List<Track> =
    tracks.filter { it.isDownloaded && it.fileFormat.lowercase() !in LOSSLESS }

/** Duration-based size estimate — honest for FLAC, which tracks playtime. */
fun estimateFlacBytes(tracks: List<Track>): Long =
    tracks.sumOf { (it.durationMs / 1000L) * FLAC_BYTES_PER_SECOND }

fun formatGb(bytes: Long): String =
    String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
```

VM addition (inject `private val flacUpgradeEnqueuer: FlacUpgradeEnqueuer` in the constructor):

```kotlin
    /** Kick off the batch FLAC upgrade for the confirmed selection (spec §3). */
    fun upgradeSelectedToFlac(trackIds: List<Long>) {
        viewModelScope.launch {
            runCatching { flacUpgradeEnqueuer.startBatch(trackIds) }
                .onSuccess {
                    _userMessages.tryEmit("Upgrading ${trackIds.size} ${songs(trackIds.size)} to FLAC — watch the notification.")
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _userMessages.tryEmit("Couldn't start the FLAC upgrade.")
                }
        }
    }
```

Screen: state `var showFlacConfirm by remember { mutableStateOf(false) }`; sixth selection action appended to `selectionActions`:

```kotlin
            SelectionAction("upgrade_flac", "Upgrade to FLAC", Icons.Default.HighQuality) {
                showFlacConfirm = true
            },
```

(import `androidx.compose.material.icons.filled.HighQuality`; it lands in the ⋮ "More" overflow of the selection bar — by design, 6 > 4.)

Dialog (place near the batch-delete dialog; free-space via `StatFs` on the app's files dir — no existing precedent, keep it contained here):

```kotlin
    if (showFlacConfirm) {
        val eligible = eligibleForFlacUpgrade(selectedTracks)
        val skipped = selectedTracks.size - eligible.size
        val bytes = estimateFlacBytes(eligible)
        val freeBytes = remember {
            runCatching {
                android.os.StatFs(context.filesDir.path).availableBytes
            }.getOrDefault(Long.MAX_VALUE)
        }
        AlertDialog(
            onDismissRequest = { showFlacConfirm = false },
            title = { Text("Upgrade to FLAC?") },
            text = {
                Column {
                    Text(
                        "Upgrade ${eligible.size} ${if (eligible.size == 1) "track" else "tracks"} to FLAC? " +
                            "Roughly ~${formatGb(bytes)} of downloads. Originals are replaced." +
                            if (skipped > 0) "\n\n$skipped already-FLAC or not-downloaded ${if (skipped == 1) "track" else "tracks"} will be skipped." else "",
                    )
                    if (bytes > freeBytes * 8 / 10) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Heads up: that's close to (or beyond) your free space (${formatGb(freeBytes)} available).",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = eligible.isNotEmpty(),
                    onClick = {
                        viewModel.upgradeSelectedToFlac(eligible.map { it.id })
                        showFlacConfirm = false
                        selection.clear()
                    },
                ) { Text("Upgrade") }
            },
            dismissButton = { TextButton(onClick = { showFlacConfirm = false }) { Text("Cancel") } },
        )
    }
```

(`context` = `LocalContext.current` hoisted next to the dialog. The dialog never hard-blocks — warning only, per spec.)

- [ ] **Step 4: Run — PASS**: `./gradlew :feature:library:testDebugUnitTest --tests '*FlacUpgradeEstimateTest*' --tests '*LibraryViewModelSourceFilterTest*'`
- [ ] **Step 5: Commit** (`feat(library): Upgrade-to-FLAC selection action with size-estimate confirm`).

### Task 12: Section C device checkpoint + full gate

- [ ] **Step 1: Full filtered test sweep**

Run: `./gradlew :core:data:testDebugUnitTest --tests '*FlacUpgrade*' --tests 'com.stash.core.data.sync.workers.DiffWorkerTest' --tests '*TrackDaoFindExistingForBatchTest*' :feature:library:testDebugUnitTest --tests 'com.stash.feature.library.*' :feature:nowplaying:testDebugUnitTest --tests '*NowPlayingViewModel*' :core:media:testDebugUnitTest --tests '*PlayerRepositoryRadioTest*' --tests '*ListeningRecorderSkipTest*' --tests '*TrackActionsDelegateQueueActionsTest*' :feature:search:testDebugUnitTest --tests '*ArtistProfileViewModelTest*'`
Expected: all pass (the :core:media filters cover the Boolean→RadioStartResult rework from Task 5).

- [ ] **Step 2: Install** — `./gradlew :app:installDebug` → `Installed on 1 device.`

- [ ] **Step 3: Report for human smoke test** — orchestrator asks the user: Library → filter Non-FLAC → long-press → select 2-3 tracks → Upgrade to FLAC (in the selection bar's More overflow) → confirm dialog shows count + GB → notification progresses → tracks gain the FLAC badge; Cancel from the notification mid-batch stops it.

- [ ] **Step 4: Merge gate** — after user sign-off, merge `feat/personal-trio` to master per the repo's normal flow (the orchestrator handles this outside the plan).
