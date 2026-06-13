# Multi-Select Tracks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a multi-track selection mode to Stash's track-list screens so users can long-press to select many tracks and apply one batch action (play next, add to queue, add to playlist, download/remove download, delete).

**Architecture:** A small reusable Compose state holder (`SelectionState`, `rememberSaveable`-backed) is hoisted per screen. Existing shared row composables (`DetailTrackRow`, `TrackListItem`) gain selection-aware params (leading checkbox) and a trailing ⋮ that hosts the *single-track* options previously triggered by long-press. Two new chrome composables — `SelectionTopBar` (overlaid contextual header) and `SelectionBottomBar` (overlaid action bar) — appear while selection is active. Batch actions wrap proven single-track ViewModel/repository paths over the selected ids. The global mini-player is hidden during selection via a callback hoisted through the NavHost.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Hilt, multi-module Gradle (`app`, `core/*`, `feature/*`), JUnit4 + Truth + mockito-kotlin + kotlinx-coroutines-test.

---

## ⚠️ Deviations from the approved spec (read first)

The spec (`docs/superpowers/specs/2026-05-31-multi-select-tracks-design.md`) was written before codebase verification. These corrections are baked into the tasks below:

1. **Rows: `DetailTrackRow`, not `TrackListItem`.** Four of five screens (Playlist, Liked, Album, Artist) render rows via the shared `core/ui/.../DetailTrackRow.kt`, which has **only** `onLongPress` (no ⋮). LibraryScreen uses `TrackListItem` (has `onMoreClick`). Both components get selection params; **`DetailTrackRow` additionally gets a new `onMoreClick` ⋮** so the single-track menu has a home once long-press is reassigned to selection.
2. **No TopAppBar to "transform".** Detail screens are `Box` + `LazyColumn` with a custom in-list header. The contextual `[✕] N selected [Select all]` bar is an **overlaid `SelectionTopBar`** (AnimatedVisibility, `statusBarsPadding`), not a transformed app bar.
3. **Fifth surface = LibraryScreen, not SearchScreen.** SearchScreen has no long-press, uses `TrackItem`/`SearchResultItem` (not domain `Track`), and is a preview/download surface. It is **out of scope**; LibraryScreen takes the 5th slot.
4. **Mini-player hide = NavHost callback.** Mini-player + bottom nav live in `app/.../StashScaffold.kt`. No shared app-level UI state exists, so each screen reports selection-active up via an `onSelectionModeChanged: (Boolean) -> Unit` callback threaded through the NavHost; `StashScaffold` hides the mini-player when true.
5. **No batch `addNext(List)`** in `PlayerRepository` — loop single `addNext`. Batch `addToQueue(List<Track>)` exists and is used.

### Per-screen action matrix (final)

| Action | Playlist | Liked | Album | Artist | Library |
|---|:--:|:--:|:--:|:--:|:--:|
| Play next | ✅ | ✅ | ✅ | ✅ | ✅ |
| Add to queue | ✅ | ✅ | ✅ | ✅ | ✅ |
| Add to playlist | ✅ | ✅ | ✅ | ✅ | ✅ |
| Download / Remove download | ✅ | ✅ | ✅* | ✅* | ✅* |
| Delete | ✅ (remove from playlist) | ✅ (unlike) | ❌ | ❌ | ✅ (delete) |

\* Album/Artist/Library VMs need trivial wrapper methods added (`queueDownload`/`removeDownload`/`saveTrackToPlaylist`) that delegate to `MusicRepository`. These are included in the per-screen tasks. Delete stays gated to user-owned collections per spec.

---

## File Structure

**New files (`core/ui`):**
- `core/ui/src/main/kotlin/com/stash/core/ui/selection/SelectionState.kt` — state holder + Saver + `rememberSelectionState()`.
- `core/ui/src/main/kotlin/com/stash/core/ui/selection/SelectionBars.kt` — `SelectionTopBar`, `SelectionBottomBar`, `SelectionAction`.
- `core/ui/src/test/kotlin/com/stash/core/ui/selection/SelectionStateTest.kt` — unit tests.

**Modified shared components (`core/ui`):**
- `core/ui/.../components/DetailTrackRow.kt` — add `selectionActive`, `selected`, `onMoreClick` params + leading checkbox + trailing ⋮.
- `core/ui/.../components/TrackListItem.kt` — add `selectionActive`, `selected` params + leading checkbox.

**Modified screens (`feature/library`):**
- `PlaylistDetailScreen.kt` (+ `PlaylistDetailViewModel.kt`) — reference implementation.
- `LikedSongsDetailScreen.kt` (+ `LikedSongsDetailViewModel.kt`).
- `AlbumDetailScreen.kt` (+ `AlbumDetailViewModel.kt`).
- `ArtistDetailScreen.kt` (+ `ArtistDetailViewModel.kt`).
- `LibraryScreen.kt` (+ `LibraryViewModel.kt`).

**Modified app chrome:**
- `app/src/main/kotlin/com/stash/app/navigation/StashScaffold.kt` — hide mini-player on selection.
- The NavHost wiring file that declares the `composable(...)` destinations (same area as `StashScaffold` / nav graph) — thread `onSelectionModeChanged`.

**ViewModel tests (`feature/library/src/test/...`):** one per VM batch-method task, mirroring `LikedSongsDetailViewModelTest.kt`.

---

## Task 1: `SelectionState` holder + Saver

**Files:**
- Create: `core/ui/src/main/kotlin/com/stash/core/ui/selection/SelectionState.kt`
- Test: `core/ui/src/test/kotlin/com/stash/core/ui/selection/SelectionStateTest.kt`

Design note: `isActive` is **derived** from a non-empty selection — there is no separate boolean. Entering selects the long-pressed track (count 1); deselecting the last track makes `isActive == false`, which the screen observes to exit mode. This keeps a single source of truth and a trivial Saver (just the id set).

- [ ] **Step 0: Set up the `core/ui` test source set (it has none today)**

`core/ui/build.gradle.kts` currently declares **zero** `testImplementation` deps and the convention plugin adds none — there is no JVM test source set yet. The `Saver`/`SaverScope`/`rememberSaveable` APIs live in `androidx.compose.runtime:runtime-saveable`, a **separate** artifact from `runtime` (transitively present in `main` via the Compose convention plugin, but **not** on the test classpath). Add:

```kotlin
// core/ui/build.gradle.kts — dependencies { }
testImplementation(libs.junit)
testImplementation(libs.truth)
testImplementation(libs.compose.runtime)            // catalog alias is libs.compose.runtime (NOT androidx.compose.runtime)
testImplementation("androidx.compose.runtime:runtime-saveable:<same-version-as-runtime>")
```
There is **no `runtime-saveable` alias** in `libs.versions.toml` — either add one or use the direct coordinate above, matching the Compose runtime version already resolved in the catalog. Confirm `libs.junit` and `libs.truth` alias names against `libs.versions.toml` (they're used by `feature/library` tests).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.stash.core.ui.selection

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SelectionStateTest {
    @Test fun starts_inactive_and_empty() {
        val s = SelectionState()
        assertThat(s.isActive).isFalse()
        assertThat(s.count).isEqualTo(0)
    }

    @Test fun enter_selects_single_and_activates() {
        val s = SelectionState()
        s.enter(7L)
        assertThat(s.isActive).isTrue()
        assertThat(s.isSelected(7L)).isTrue()
        assertThat(s.count).isEqualTo(1)
    }

    @Test fun toggle_adds_then_removes() {
        val s = SelectionState(setOf(1L))
        s.toggle(2L); assertThat(s.selectedIds).containsExactly(1L, 2L)
        s.toggle(1L); assertThat(s.selectedIds).containsExactly(2L)
    }

    @Test fun deselecting_last_track_deactivates() {
        val s = SelectionState(setOf(1L))
        s.toggle(1L)
        assertThat(s.isActive).isFalse()
    }

    @Test fun selectAll_then_clear() {
        val s = SelectionState()
        s.selectAll(listOf(1L, 2L, 3L))
        assertThat(s.count).isEqualTo(3)
        s.clear()
        assertThat(s.isActive).isFalse()
    }

    @Test fun saver_round_trips_the_id_set() {
        val original = SelectionState(setOf(4L, 9L))
        val saved = with(SelectionStateSaver) { SaverScopeStub.save(original) }
        val restored = SelectionStateSaver.restore(saved!!)
        assertThat(restored!!.selectedIds).containsExactly(4L, 9L)
    }
}

// Minimal SaverScope to invoke the save lambda in a plain JVM test.
private object SaverScopeStub : androidx.compose.runtime.saveable.SaverScope {
    override fun canBeSaved(value: Any): Boolean = true
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:ui:testDebugUnitTest --tests "com.stash.core.ui.selection.SelectionStateTest"`
Expected: FAIL — `SelectionState` / `SelectionStateSaver` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.stash.core.ui.selection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/** Multi-select state for one track-list screen. `isActive` is derived from a non-empty selection. */
class SelectionState(initial: Set<Long> = emptySet()) {
    var selectedIds by mutableStateOf(initial)
        private set

    val isActive: Boolean get() = selectedIds.isNotEmpty()
    val count: Int get() = selectedIds.size

    fun isSelected(id: Long): Boolean = id in selectedIds
    fun enter(id: Long) { selectedIds = selectedIds + id }
    fun toggle(id: Long) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }
    fun selectAll(ids: Collection<Long>) { selectedIds = ids.toSet() }
    fun clear() { selectedIds = emptySet() }
}

/** Survives rotation / process death by persisting the id set. */
val SelectionStateSaver: Saver<SelectionState, List<Long>> = Saver(
    save = { it.selectedIds.toList() },
    restore = { SelectionState(it.toSet()) },
)

@Composable
fun rememberSelectionState(): SelectionState =
    rememberSaveable(saver = SelectionStateSaver) { SelectionState() }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:ui:testDebugUnitTest --tests "com.stash.core.ui.selection.SelectionStateTest"`
Expected: PASS (6 tests). (Test deps were added in Step 0 — if you still hit an unresolved `Saver`/`SaverScope`, re-check the `runtime-saveable` coordinate.)

- [ ] **Step 5: Commit**

```bash
git add core/ui/src/main/kotlin/com/stash/core/ui/selection/SelectionState.kt core/ui/src/test/kotlin/com/stash/core/ui/selection/SelectionStateTest.kt core/ui/build.gradle.kts
git commit -m "feat(selection): SelectionState holder + rememberSaveable Saver"
```

---

## Task 2: `SelectionTopBar` + `SelectionBottomBar` chrome

**Files:**
- Create: `core/ui/src/main/kotlin/com/stash/core/ui/selection/SelectionBars.kt`

These are presentational; verified visually in the screen-wiring tasks. Match Stash's design system — reuse `GlassCard` / Material3 extended theme tokens already in `core/ui` (see existing components for the exact theme accessors). Do **not** introduce a generic dark-mode look.

- [ ] **Step 1: Implement `SelectionAction` + the two bars**

```kotlin
package com.stash.core.ui.selection

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

data class SelectionAction(
    val key: String,
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

/** Contextual header overlaid at the top of a selection-active screen. */
@Composable
fun SelectionTopBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(tonalElevation = 3.dp, modifier = modifier.fillMaxWidth().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Exit selection") }
            Text("$count selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 8.dp))
            TextButton(onClick = onSelectAll) {
                Icon(Icons.Default.DoneAll, contentDescription = null); Spacer(Modifier.width(4.dp)); Text("Select all")
            }
        }
    }
}

/** Bottom batch-action bar. Shows up to 4 labeled actions; extras collapse into a ⋮ menu. */
@Composable
fun SelectionBottomBar(actions: List<SelectionAction>, modifier: Modifier = Modifier) {
    val primary = actions.take(4)
    val overflow = actions.drop(4)
    var menuOpen by remember { mutableStateOf(false) }
    Surface(tonalElevation = 3.dp, modifier = modifier.fillMaxWidth().navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            primary.forEach { a -> LabeledAction(a) }
            if (overflow.isNotEmpty()) {
                Box {
                    LabeledAction(SelectionAction("more", "More", Icons.Default.MoreVert) { menuOpen = true })
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        overflow.forEach { a ->
                            DropdownMenuItem(text = { Text(a.label) }, leadingIcon = { Icon(a.icon, null) },
                                onClick = { menuOpen = false; a.onClick() })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LabeledAction(a: SelectionAction) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 4.dp)) {
        IconButton(onClick = a.onClick) { Icon(a.icon, contentDescription = a.label) }
        Text(a.label, style = MaterialTheme.typography.labelSmall)
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :core:ui:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. Fix any theme-token / import mismatches against the actual `core/ui` theme.

- [ ] **Step 3: Commit**

```bash
git add core/ui/src/main/kotlin/com/stash/core/ui/selection/SelectionBars.kt
git commit -m "feat(selection): SelectionTopBar + SelectionBottomBar chrome"
```

---

## Task 3: `DetailTrackRow` — selection params + leading checkbox + trailing ⋮

**Files:**
- Modify: `core/ui/src/main/kotlin/com/stash/core/ui/components/DetailTrackRow.kt`

- [ ] **Step 1: Extend the signature (defaults preserve current behavior)**

```kotlin
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DetailTrackRow(
    track: Track,
    trackNumber: Int,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    showArtist: Boolean = true,
    subtitleOverride: String? = null,
    isResolving: Boolean = false,
    selectionActive: Boolean = false,   // NEW
    selected: Boolean = false,           // NEW
    onMoreClick: (() -> Unit)? = null,   // NEW — hosts the single-track menu
)
```

- [ ] **Step 2: Render the leading checkbox and trailing ⋮**

In the root `Row` (the `combinedClickable` one), when `selectionActive` is true, render a leading `Checkbox(checked = selected, onCheckedChange = null)` (the row's `onClick` does the toggling) animated in via `AnimatedVisibility`; keep album art visible after it. When `selectionActive` is false and `onMoreClick != null`, render a trailing ⋮ `IconButton` (mirror `TrackListItem.kt` lines ~175-184). The caller controls semantics: in selection mode `onClick` = toggle; otherwise `onClick` = play.

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :core:ui:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add core/ui/src/main/kotlin/com/stash/core/ui/components/DetailTrackRow.kt
git commit -m "feat(selection): DetailTrackRow gains checkbox + overflow ⋮"
```

---

## Task 4: `TrackListItem` — selection params + leading checkbox

**Files:**
- Modify: `core/ui/src/main/kotlin/com/stash/core/ui/components/TrackListItem.kt`

- [ ] **Step 1: Extend the signature**

```kotlin
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackListItem(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    onMoreClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    isResolving: Boolean = false,
    selectionActive: Boolean = false,   // NEW
    selected: Boolean = false,           // NEW
)
```

- [ ] **Step 2: Render the leading checkbox** (same pattern as Task 3; `TrackListItem` already has the ⋮ via `onMoreClick`). When `selectionActive`, the existing ⋮ may be hidden to avoid crowding (caller passes `onMoreClick = null` while selecting).

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :core:ui:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add core/ui/src/main/kotlin/com/stash/core/ui/components/TrackListItem.kt
git commit -m "feat(selection): TrackListItem gains selection checkbox"
```

---

## Task 5: `PlaylistDetailViewModel` batch methods (reference)

**Files:**
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/PlaylistDetailViewModel.kt`
- Test: `feature/library/src/test/kotlin/com/stash/feature/library/PlaylistDetailViewModelTest.kt` (create if absent; mirror `LikedSongsDetailViewModelTest.kt`)

Batch methods wrap existing single-track paths. Queue uses the batch `addToQueue(List<Track>)`; Play Next loops `addNext`; download/remove/delete/save loop the per-id repo calls already used by the single-track methods.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun deleteSelected_removes_each_from_playlist() = runTest {
    val vm = buildVm()
    val tracks = listOf(track(1L), track(2L), track(3L))
    vm.deleteSelected(tracks, alsoBlacklist = false)
    runCurrent()
    tracks.forEach { t ->
        verify(musicRepository).removeTrackFromPlaylistAndMaybeDelete(
            trackId = t.id, fromPlaylistId = any(), alsoBlacklist = eq(false))
    }
}

@Test fun addSelectedToQueue_uses_batch_overload() = runTest {
    val vm = buildVm()
    val tracks = listOf(track(1L), track(2L))
    vm.addSelectedToQueue(tracks)
    runCurrent()
    verify(playerRepository).addToQueue(tracks)   // batch overload, single call
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :feature:library:testDebugUnitTest --tests "com.stash.feature.library.PlaylistDetailViewModelTest"`
Expected: FAIL — methods unresolved.

- [ ] **Step 3: Add the batch methods**

```kotlin
fun playSelectedNext(tracks: List<Track>) = viewModelScope.launch {
    tracks.forEach { playerRepository.addNext(it) }
}
fun addSelectedToQueue(tracks: List<Track>) = viewModelScope.launch {
    playerRepository.addToQueue(tracks)
}
fun downloadSelected(trackIds: List<Long>) = viewModelScope.launch {
    trackIds.forEach { musicRepository.queueDownload(it) }
}
fun removeDownloadsForSelected(trackIds: List<Long>) = viewModelScope.launch {
    trackIds.forEach { musicRepository.removeDownload(it) }
}
fun saveSelectedToPlaylist(trackIds: List<Long>, playlistId: Long) = viewModelScope.launch {
    trackIds.forEach { musicRepository.addTrackToPlaylist(it, playlistId) }
}
fun deleteSelected(tracks: List<Track>, alsoBlacklist: Boolean) = viewModelScope.launch {
    tracks.forEach {
        musicRepository.removeTrackFromPlaylistAndMaybeDelete(
            trackId = it.id, fromPlaylistId = playlistId, alsoBlacklist = alsoBlacklist)
    }
}
```
⚠️ The repo method is **`musicRepository.addTrackToPlaylist(trackId, playlistId)`** (`MusicRepository.kt:290`) — the existing VM `saveTrackToPlaylist` is a ViewModel method that itself delegates to `addTrackToPlaylist`. Do **not** call `musicRepository.saveTrackToPlaylist` (no such repo method). `removeTrackFromPlaylistAndMaybeDelete` returns a `CascadeRemovalSummary` (not Unit) — if you stub it in a mock, return a summary to avoid NPEs.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :feature:library:testDebugUnitTest --tests "com.stash.feature.library.PlaylistDetailViewModelTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add feature/library/src/main/kotlin/com/stash/feature/library/PlaylistDetailViewModel.kt feature/library/src/test/kotlin/com/stash/feature/library/PlaylistDetailViewModelTest.kt
git commit -m "feat(selection): batch actions on PlaylistDetailViewModel"
```

---

## Task 6: Wire `PlaylistDetailScreen` (reference implementation)

**Files:**
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/PlaylistDetailScreen.kt`

This is the canonical wiring every other screen copies. Get it right here.

- [ ] **Step 1: Add selection state + signal-out param**

- Add `onSelectionModeChanged: (Boolean) -> Unit = {}` to the screen composable signature.
- `val selection = rememberSelectionState()`.
- `LaunchedEffect(selection.isActive) { onSelectionModeChanged(selection.isActive) }`.

- [ ] **Step 2: Rewire row interactions in the `DetailTrackRow` call (~lines 246-253)**

```kotlin
DetailTrackRow(
    track = track,
    trackNumber = index + 1,
    isPlaying = /* unchanged */,
    selectionActive = selection.isActive,
    selected = selection.isSelected(track.id),
    onClick = { if (selection.isActive) selection.toggle(track.id) else /* existing play */ },
    onLongPress = { if (!selection.isActive) selection.enter(track.id) },
    onMoreClick = { selectedTrack = track }, // existing TrackOptionsSheet trigger, now on ⋮
)
```

- [ ] **Step 3: Overlay the selection chrome**

In the root `Box`, add:
```kotlin
AnimatedVisibility(visible = selection.isActive, modifier = Modifier.align(Alignment.TopCenter)) {
    SelectionTopBar(
        count = selection.count,
        onClose = { selection.clear() },
        onSelectAll = {
            val all = uiState.tracks.map { it.id }
            if (selection.count == all.size) selection.clear() else selection.selectAll(all)
        },
    )
}
AnimatedVisibility(visible = selection.isActive, modifier = Modifier.align(Alignment.BottomCenter)) {
    SelectionBottomBar(actions = buildPlaylistSelectionActions(selection, uiState.tracks, viewModel) { selection.clear() })
}
```
Build the action list from the matrix (Play next, Add to queue, Add to playlist → opens `SaveToPlaylistSheet` fed all selected ids, Download/Remove download chosen by aggregate `isDownloaded`, Delete → batch confirm dialog). Each action calls the Task 5 VM method with `selection.selectedIds` then `selection.clear()`. Reuse the existing `SaveToPlaylistSheet` and a delete `AlertDialog` ("Remove N songs from this playlist?").

- [ ] **Step 4: Back-button precedence** — add `BackHandler(enabled = selection.isActive) { selection.clear() }` so Back exits selection before leaving the screen.

- [ ] **Step 5: Install and verify on-device** (compile-pass is not enough on this project)

Run: `./gradlew :app:installDebug`
Manually verify: long-press enters mode (1 selected) → tap toggles others → top bar count updates → Select all / clear → each batch action works (queue, play next, add-to-playlist, download, remove download, delete-with-confirm) → ✕ and Back both exit → ⋮ still opens the single-track sheet when NOT selecting → mini-player hides while selecting (after Task 7).

- [ ] **Step 6: Commit**

```bash
git add feature/library/src/main/kotlin/com/stash/feature/library/PlaylistDetailScreen.kt
git commit -m "feat(selection): wire multi-select into PlaylistDetailScreen"
```

---

## Task 7: Hide mini-player during selection (app chrome)

**Files:**
- Modify: `app/src/main/kotlin/com/stash/app/navigation/StashScaffold.kt`
- Modify: the NavHost destination declarations (same nav-graph area).

- [ ] **Step 1: Hold selection-active state at the scaffold**

In `StashScaffold`: `var selectionActive by remember { mutableStateOf(false) }`. In the `bottomBar`, render the `MiniPlayer` only when `!selectionActive` (keep `StashBottomBar` as-is, or hide both per design — default: hide only the mini-player).

- [ ] **Step 2: Thread the callback**

For the `composable(PlaylistDetailRoute)` destination, pass `onSelectionModeChanged = { selectionActive = it }` into `PlaylistDetailScreen`. (Other screens get the same wiring in their tasks.)

- [ ] **Step 3: Install and verify**

Run: `./gradlew :app:installDebug`
Verify: entering selection on Playlist detail hides the mini-player; exiting restores it on every path (✕, Back, last-deselect, action completion).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/stash/app/navigation/StashScaffold.kt
git commit -m "feat(selection): hide mini-player while selecting"
```

---

## Task 8: `LikedSongsDetailScreen` + VM

**Files:**
- Modify: `feature/library/.../LikedSongsDetailViewModel.kt` (+ its test) and `LikedSongsDetailScreen.kt`.
- Wire `onSelectionModeChanged` for the Liked route in the NavHost.

- [ ] **Step 1 (test-first):** Add + test batch methods mirroring Task 5, except Delete uses `musicRepository.deleteTrack(track)` (the unlike path) per track. Add `playSelectedNext`, `addSelectedToQueue` (batch overload), `downloadSelected`, `removeDownloadsForSelected`, `saveSelectedToPlaylist`, `deleteSelected`. These batch methods call `musicRepository`/`playerRepository` **directly** (both already injected here — the Liked VM already exposes single `queueDownload`/`removeDownload`/`saveTrackToPlaylist`), so no new single-track wrappers are needed.

Run: `./gradlew :feature:library:testDebugUnitTest --tests "com.stash.feature.library.LikedSongsDetailViewModelTest"` → FAIL then PASS.

- [ ] **Step 2:** Wire the screen exactly like Task 6 (rememberSelectionState, row rewire on the `DetailTrackRow` at ~lines 162-169, overlay bars, `BackHandler`, ⋮ → existing `selectedTrack` sheet, `onSelectionModeChanged`). Delete confirm copy: "Remove N songs from Liked Songs?".

- [ ] **Step 3:** `./gradlew :app:installDebug` and verify on-device (same checklist as Task 6).

- [ ] **Step 4: Commit**

```bash
git commit -am "feat(selection): multi-select on Liked Songs"
```

---

## Task 9: `AlbumDetailScreen` + VM (no Delete)

**Files:** `AlbumDetailViewModel.kt` (+ test), `AlbumDetailScreen.kt`, NavHost wiring.

- [ ] **Step 1 (test-first):** Add batch methods `playSelectedNext`, `addSelectedToQueue`, `saveSelectedToPlaylist`, `downloadSelected`, `removeDownloadsForSelected`. No delete. The batch methods call `musicRepository`/`playerRepository` directly. The Album VM already injects `musicRepository` (used by `deleteTrack`/`saveTrackToPlaylist`) but currently exposes **no** download method — that's fine, the batch `downloadSelected`/`removeDownloadsForSelected` call `musicRepository.queueDownload`/`removeDownload` directly without needing a single-track wrapper. Test the batch behavior.

Run the `:feature:library` test for `AlbumDetailViewModelTest` → FAIL then PASS.

- [ ] **Step 2:** Wire the screen like Task 6 at the `DetailTrackRow` (~lines 157-165). Action bar = Play next, Add to queue, Add to playlist, Download/Remove download (**no Delete**). ⋮ → existing `selectedTrack` sheet.

- [ ] **Step 3:** `./gradlew :app:installDebug` and verify.

- [ ] **Step 4: Commit** `git commit -am "feat(selection): multi-select on Album detail"`

---

## Task 10: `ArtistDetailScreen` + VM (no Delete)

**Files:** `ArtistDetailViewModel.kt` (+ test), `ArtistDetailScreen.kt`, NavHost wiring.

Identical shape to Task 9 (DetailTrackRow at ~line 151). Add the same VM batch methods (calling `musicRepository`/`playerRepository` directly, incl. `queueDownload`/`removeDownload`); action bar without Delete.

- [ ] **Step 1 (test-first)** → FAIL then PASS.
- [ ] **Step 2** wire screen.
- [ ] **Step 3** `./gradlew :app:installDebug` + verify.
- [ ] **Step 4: Commit** `git commit -am "feat(selection): multi-select on Artist detail"`

---

## Task 11: `LibraryScreen` + VM (uses `TrackListItem`)

**Files:** `LibraryViewModel.kt` (+ test), `LibraryScreen.kt`, NavHost wiring.

LibraryScreen uses `TrackListItem` and a **bespoke inline** long-press sheet (Play Next / Add to Queue / Delete). Note its inline sheet — `onMoreClick`/long-press currently both open it; after this change, long-press enters selection and the inline sheet moves to the ⋮ (`onMoreClick`).

- [ ] **Step 1 (test-first):** Add `playSelectedNext`, `addSelectedToQueue`, `downloadSelected`, `removeDownloadsForSelected`, `saveSelectedToPlaylist`, and `deleteSelected` (delete uses `musicRepository.deleteTrack(track)`; the Library VM already has single `saveTrackToPlaylist` + `deleteTrack(track, alsoBlacklist=false)` and an existing batch-delete loop to mirror). Batch methods call the repos directly — only add a `queueDownload`/`removeDownload` path if the VM genuinely lacks the injected `musicRepository` (it doesn't). Test.

- [ ] **Step 2:** Wire `TrackListItem` (line ~992): `selectionActive`, `selected`, `onClick` = toggle-or-play, `onLongPress` = enter, `onMoreClick` = open the existing inline sheet (pass `null` while selecting). Overlay `SelectionTopBar`/`SelectionBottomBar` (action set: Play next, Add to queue, Add to playlist, Download/Remove download, Delete). `BackHandler`, `onSelectionModeChanged`.

- [ ] **Step 3:** `./gradlew :app:installDebug` + verify.

- [ ] **Step 4: Commit** `git commit -am "feat(selection): multi-select on Library"`

---

## Task 12: Full regression pass + finish

- [ ] **Step 1: Run the whole test suite**

Run: `./gradlew :core:ui:testDebugUnitTest :feature:library:testDebugUnitTest`
Expected: all green (note: there is a pre-existing red DAO test elsewhere unrelated to this work — confirm it's the same known failure, not new).

- [ ] **Step 2: On-device regression** — `./gradlew :app:installDebug`; walk all five screens: enter/exit on each path, Select all, every valid batch action, rotation mid-selection (selection survives), process-death restore, mini-player hide/restore, ⋮ single-track menus still work when not selecting, now-playing indicator still renders on rows.

- [ ] **Step 3:** Use superpowers:requesting-code-review before integrating.

- [ ] **Step 4:** Use superpowers:finishing-a-development-branch to merge/PR.

---

## Open questions / follow-ups (non-blocking)

- **SearchScreen multi-select** deferred (different model + no long-press) — separate spec if requested.
- **Batch progress UI** for large download/delete sets (spec mentions cancellable progress) — current per-id loops fire-and-forget via WorkManager; a dedicated progress surface is a follow-up if users ask.
- **`addNext(List<Track>)` overload** could replace the loop in every VM later for a single round-trip.
