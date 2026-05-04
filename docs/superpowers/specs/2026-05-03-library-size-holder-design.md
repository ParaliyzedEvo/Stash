# Shared `LibrarySizeHolder` — Filesystem Storage StateFlow Design

**Date:** 2026-05-03
**Status:** Design
**Branch:** `fix/home-storage-from-filesystem` (extends in-progress v0.9.7 work)

## Problem

`MusicRepository.getTotalStorageBytes()` returns `SUM(file_size_bytes)` from the `tracks` table. On legacy libraries thousands of rows have `file_size_bytes = 0` because older Stash versions didn't always populate the column at download time. The recovery backfill (Library Health → Run backfill) has been failing on those rows because of orphaned DB entries + `content://` SAF path mismatches. Net effect: Home + Settings + anywhere else that displays "Storage used" shows a value that's typically 60–80 % too low — Home was reporting 4.4 GB for what's actually a 16 GB library.

The v0.9.7 branch already fixed Home by walking the filesystem via `FileOrganizer.computeMusicLibrarySize()` (a SAF-aware traversal) and holding the result in a `MutableStateFlow` inside `HomeViewModel`. **`SettingsViewModel`'s "Storage used" row still uses the broken DAO SUM and shows the wrong number.**

## Goals

- Settings → Storage → "Storage used" shows the same disk-truth value as Home.
- The shared computation runs **once** per library state, not once per consumer.
- Adding a third or fourth consumer in the future (Library Health, About screen, bug-report diagnostics) is a one-line injection.
- The displayed value never flashes to 0 during recomputes — last-known-good is preserved.
- Walks happen on `Dispatchers.IO`, not the main thread.
- The walker pauses when no UI is observing (battery hygiene).

## Non-goals

- Replacing `MusicRepository.getTotalStorageBytes()` itself — leaving the Repository method intact for any caller that legitimately wants a Room Flow over the DB column (e.g. unit tests, Library Health bucket math). The new holder is additive.
- Backfilling the broken `file_size_bytes` column. Out of scope; possibly impossible on orphaned rows.
- Adding FLAC breakdown to Settings. User explicitly chose Approach A: just fix the headline total.
- Renaming/extracting `FileOrganizer`. The walker stays where it is.

## Design

### 1. New singleton — `LibrarySizeHolder`

**Location:** `data/download/src/main/kotlin/com/stash/data/download/files/LibrarySizeHolder.kt` (next to `FileOrganizer`).

**Hilt scope:** `@Singleton` — the StateFlow lives for the lifetime of the app process.

**API surface:**

```kotlin
@Singleton
class LibrarySizeHolder @Inject constructor(
    private val fileOrganizer: FileOrganizer,
    private val musicRepository: MusicRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val size: StateFlow<LibrarySizeBreakdown> = musicRepository.getTrackCount()
        .distinctUntilChanged()
        .scan(LibrarySizeBreakdown(0L, 0L, 0)) { prev, _ ->
            runCatching { fileOrganizer.computeMusicLibrarySize() }.getOrDefault(prev)
        }
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = LibrarySizeBreakdown(0L, 0L, 0),
        )
}
```

**Why `scan` + `getOrDefault(prev)`:** if a walk throws (transient SAF permission flicker, DataStore hiccup, mid-walk file move), the StateFlow keeps the previously-computed value. UI never flashes to 0 mid-recompute.

**Why `WhileSubscribed(5_000)`:** the walker only runs when at least one ViewModel observes. Five-second grace period covers brief navigation transitions (Home → Library → back). With zero observers for >5 s, the upstream `getTrackCount()` collector pauses and no walks happen until something subscribes again.

**Why `Dispatchers.Default` for the holder's scope:** the walker itself runs on `Dispatchers.IO` via `flowOn`. The outer scope only hosts the `stateIn` machinery (sharing + value caching), which is cheap.

### 2. `HomeViewModel` simplification

**Current state** (v0.9.7 branch tip): a private `MutableStateFlow<LibrarySizeBreakdown>` inside `HomeViewModel` plus a `viewModelScope.launch { … collect … }` that drives it. ~25 lines of glue.

**New state**: inject `LibrarySizeHolder`, observe `librarySizeHolder.size` directly in the existing `musicDataFlow` combine. The local StateFlow + collector are deleted.

```kotlin
@HiltViewModel
class HomeViewModel @Inject constructor(
    …,
    private val librarySizeHolder: LibrarySizeHolder,
) : ViewModel() {

    private val musicDataFlow = combine(
        musicRepository.getAllPlaylists(),
        musicRepository.getRecentlyAdded(20),
        musicRepository.getTrackCount(),
        librarySizeHolder.size,
    ) { playlists, recentlyAdded, trackCount, librarySize ->
        MusicData(playlists, recentlyAdded, trackCount, librarySize)
    }
    // …unchanged below
}
```

`FileOrganizer` is no longer needed as a `HomeViewModel` constructor param — the holder owns it. Net `-22 LOC`.

### 3. `SettingsViewModel` wiring

`SettingsViewModel` already has a wide `combine(…) { values -> … }` block (17 inputs). Index 3 is currently `musicRepository.getTotalStorageBytes()`. Replace it with `librarySizeHolder.size`, then read `.totalBytes` from the cast value.

```kotlin
val uiState: StateFlow<SettingsUiState> = combine(
    tokenManager.spotifyAuthState,
    tokenManager.youTubeAuthState,
    musicRepository.getTrackCount(),
    librarySizeHolder.size,                      // ← was musicRepository.getTotalStorageBytes()
    qualityPreference.qualityTier,
    …
) { values ->
    …
    val storageBytes = (values[3] as LibrarySizeBreakdown).totalBytes
    …
}
```

The downstream `SettingsUiState.totalStorageBytes: Long` field stays; only its source changes. The Settings UI's `formatBytes(uiState.totalStorageBytes)` keeps working as-is.

`SettingsViewModel` constructor gains one new param: `librarySizeHolder: LibrarySizeHolder`.

### 4. Module dependency

**No gradle changes needed.** Both `feature/settings/build.gradle.kts:20` and `feature/home/build.gradle.kts:14` already declare `implementation(project(":data:download"))` (the former added when MoveLibraryCoordinator was wired; the latter added on the v0.9.7 branch for direct `FileOrganizer` access). After this refactor, `:feature:home` only needs the holder — but the dep stays for symmetry and any future direct `FileOrganizer` use.

`:data:download` already depends on `:core:data` (for `MusicRepository`), so the holder's `MusicRepository` injection works without extra plumbing.

## Touch points

| File | Change |
|---|---|
| `data/download/src/main/kotlin/com/stash/data/download/files/LibrarySizeHolder.kt` (new) | The singleton class, ~35 LOC including KDoc |
| `feature/home/src/main/kotlin/com/stash/feature/home/HomeViewModel.kt` | Drop local `MutableStateFlow` + `init` collector; drop `FileOrganizer` constructor param; inject `LibrarySizeHolder`; use `librarySizeHolder.size` in `musicDataFlow` combine. Net `-22 LOC` |
| `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsViewModel.kt` | Add `LibrarySizeHolder` constructor param; replace combine index 3 source; update value-extraction cast. ~5 LOC modified |
| `feature/settings/build.gradle.kts` | **No change** — `implementation(project(":data:download"))` already declared at line 20 |

Net: 1 new file, 2 modified files, ~+17 LOC overall (new file +35; HomeViewModel −22; SettingsViewModel +5).

## Error handling

| Failure mode | Behaviour |
|---|---|
| First walk in flight | StateFlow emits `LibrarySizeBreakdown(0, 0, 0)`; UI shows 0 GB briefly. SAF walks can take 1–2 minutes on large libraries; internal walks finish in <1 s. |
| Walker throws | `runCatching` swallows; `scan` returns previous value. UI shows the last good number. Logged via `Log.w` in `FileOrganizer`. |
| `MusicRepository.getTrackCount()` upstream errors | The `Flow` chain propagates; `scan` doesn't run; `stateIn` keeps the last `initialValue` or last successful emission. No crash. |
| All ViewModels unsubscribe | Walker pauses 5 s after last subscribe via `WhileSubscribed(5_000)`. Next subscribe re-collects from upstream and walks again on the next track-count change. |
| App backgrounded → foregrounded | If <5 s, walker keeps running and `scan`'s last-good accumulator is preserved. If >5 s, the upstream `Flow` chain is fully cancelled. On re-subscribe `stateIn`'s `value` cache hands the previous number to the UI **immediately** (no flash on initial render), but `scan` itself restarts from `LibrarySizeBreakdown(0,0,0)` — meaning the **next** time `getTrackCount` ticks before the walker finishes, the UI could briefly see 0 again. In practice walks are infrequent and `getTrackCount` doesn't tick on backgrounding alone, so this is rare. The persistent-cache fix (DataStore) is out of scope. |

## Risks

| Risk | Mitigation |
|---|---|
| Walker runs eagerly even when no UI shows it | `WhileSubscribed(5_000)` ensures it only runs when at least one ViewModel observes |
| StateFlow's `initialValue` (0,0,0) is misleading on cold-start | Acceptable — same UX as Home today; first walk completes in 1-2 minutes worst case (SAF). Could be improved later by persisting the last-known size to DataStore. Out of scope here. |
| Future consumer adds the holder dep without realising it triggers a walk | KDoc on `size` documents the eager-walk-on-first-subscribe behaviour. The walk is cheap on internal storage and only marginal cost on SAF. |
| Walker's `scan` accumulator quietly dropping errors makes diagnosis harder | `FileOrganizer.computeMusicLibrarySize` already logs failures; the holder's `runCatching.getOrDefault(prev)` is defensive without hiding the underlying log signal. |

## Testing

### Unit tests

None added. Pattern follows existing project convention: ViewModels and Repository-adjacent helpers in this codebase aren't unit-tested in this layer (no `HomeViewModelTest`, no `MusicRepositoryImplTest`). Adding a holder-only test would require new test infrastructure for one trivial class. Manual acceptance is the discipline.

### Manual acceptance (on `com.stash.app` release-signed sideload)

1. **Cold start:** install over v0.9.7, open Home. Confirm Storage shows 0 GB initially, then ~16 GB after the SAF walk completes (1–2 min).
2. **Settings parity:** navigate to Settings → Storage section. "Storage used" row shows the same ~16 GB. (Was: 4.4 GB.)
3. **Track count change:** delete a playlist or a track. Both Home and Settings should not flash to 0; the value persists until the next walk completes, then updates to the new number (typically a small decrement).
4. **App backgrounded:** background the app for 30 s. Reopen. Both screens immediately show the previous value (no walk, no flash).
5. **Storage migration:** if you ever swap between internal and SAF via Settings → Storage, the holder picks up the new walker target on the next track-count change. (Out-of-scope to formally test in this spec; FileOrganizer handles the routing already.)

## Out of scope

- Persisting the last-known size across app restarts (would need DataStore + serialisation; deferred until cold-start UX becomes a real complaint).
- Faster SAF walks (could parallelise `DocumentFile` traversal; deferred).
- FLAC breakdown row in Settings — explicit user choice (Approach A).
- Modifying `MusicRepository.getTotalStorageBytes()` — leaving the DAO Flow alone for any other consumers.

## Ship as

Bundled with v0.9.7. The branch already has the FileOrganizer SAF-aware walker + Home wiring. This spec adds the holder + Settings wiring + simplifies Home. Single combined release.
