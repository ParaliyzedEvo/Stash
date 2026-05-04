# LibrarySizeHolder Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract the v0.9.7 in-flight `MutableStateFlow<LibrarySizeBreakdown>` out of `HomeViewModel` into a shared `@Singleton` holder so both Home and Settings show filesystem-truth Storage values.

**Architecture:** New `LibrarySizeHolder` class in `:data:download` next to `FileOrganizer`. Holds a `StateFlow<LibrarySizeBreakdown>` driven by `MusicRepository.getTrackCount()` changes; computes via `FileOrganizer.computeMusicLibrarySize()` on `Dispatchers.IO`; preserves the last-good value across walk failures via `scan(prev)`. `HomeViewModel` simplifies to inject the holder; `SettingsViewModel` wires the same source for its "Storage used" row.

**Tech Stack:** Kotlin, Hilt, Kotlin Coroutines (StateFlow / scan / stateIn / WhileSubscribed), Jetpack Compose, Android Room (existing).

**Spec:** `docs/superpowers/specs/2026-05-03-library-size-holder-design.md`

---

## Pre-flight

The current branch `fix/home-storage-from-filesystem` already contains the v0.9.7 work (FileOrganizer SAF-aware walker + Home wiring). This plan extends that branch — no new branch needed; commits land on top of the existing v0.9.7 work and ship together.

- [ ] **Confirm branch + clean tree (apart from the brainstorm scratch dirs)**

```bash
cd C:/Users/theno/Projects/MP3APK
git branch --show-current
git status --short
```

Expected:
- Current branch: `fix/home-storage-from-filesystem`
- No `M`/`A`/`D` lines for production files (only `??` brainstorm artefacts are fine)

- [ ] **Confirm spec is committed on this branch**

```bash
cd C:/Users/theno/Projects/MP3APK
git log --oneline -5 docs/superpowers/specs/2026-05-03-library-size-holder-design.md
```

Expected: at least the commits `0a09deb` (initial spec) and `f839af4` (reviewer fixes).

---

## Task 1: Create `LibrarySizeHolder`

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/files/LibrarySizeHolder.kt`

**No tests** — this codebase doesn't unit-test classes of this shape (`HomeViewModel`, `MusicRepositoryImpl`, `FileOrganizer` are all untested). The verification path is the device acceptance flow at the end of this plan.

- [ ] **Step 1: Create the file with the singleton class**

Write to `data/download/src/main/kotlin/com/stash/data/download/files/LibrarySizeHolder.kt`:

```kotlin
package com.stash.data.download.files

import com.stash.core.data.repository.MusicRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn

/**
 * Single source of truth for the music library's on-disk size + lossless
 * breakdown. Replaces per-screen StateFlow + collector glue with a shared
 * @Singleton observed by [com.stash.feature.home.HomeViewModel] and
 * [com.stash.feature.settings.SettingsViewModel].
 *
 * The legacy DAO `SUM(file_size_bytes)` is unreliable on libraries that
 * predate the v0.8.x downloader fix — many rows have the column stuck at
 * 0 and the recovery backfill fails on orphaned rows + content:// path
 * mismatches. [FileOrganizer.computeMusicLibrarySize] walks the actual
 * filesystem (storage-mode-aware: internal `filesDir/music` for default,
 * SAF DocumentFile traversal for users on external storage) and returns
 * disk truth.
 *
 * Lifecycle:
 *  - The walker only runs when at least one consumer observes [size]
 *    (`SharingStarted.WhileSubscribed(5_000)`); a 5-second grace period
 *    covers brief navigation transitions.
 *  - Walks happen on [Dispatchers.IO] via `flowOn`.
 *  - On walk failure, `scan` returns the previous value — the StateFlow
 *    never flashes to 0 mid-recompute.
 *
 * Cold-start: the StateFlow starts at `LibrarySizeBreakdown(0, 0, 0)`. On
 * SAF storage with ~3000 files the first walk can take 1-2 minutes; the
 * UI shows 0 GB during that window. Persisting the last value across app
 * restarts is out of scope (see spec).
 */
@Singleton
class LibrarySizeHolder @Inject constructor(
    private val fileOrganizer: FileOrganizer,
    private val musicRepository: MusicRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val size: StateFlow<LibrarySizeBreakdown> = musicRepository.getTrackCount()
        .distinctUntilChanged()
        .scan(LibrarySizeBreakdown(0L, 0L, 0)) { prev, _ ->
            runCatching { fileOrganizer.computeMusicLibrarySize() }
                .getOrDefault(prev)
        }
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = LibrarySizeBreakdown(0L, 0L, 0),
        )
}
```

- [ ] **Step 2: Build the data:download module to confirm compile**

```bash
cd C:/Users/theno/Projects/MP3APK
./gradlew :data:download:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. The class has no consumers yet, so the build only verifies syntax + Hilt's annotation processor accepts the singleton.

- [ ] **Step 3: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK
git add data/download/src/main/kotlin/com/stash/data/download/files/LibrarySizeHolder.kt
git commit -m "feat(data): LibrarySizeHolder — shared filesystem-truth StateFlow

@Singleton holding a StateFlow<LibrarySizeBreakdown> backed by
FileOrganizer.computeMusicLibrarySize(). Driven by MusicRepository.getTrackCount()
changes; runs on Dispatchers.IO; preserves last-good values across
walk failures via scan(prev). WhileSubscribed(5s) pauses the walker
when no UI observes.

Replaces the per-ViewModel MutableStateFlow + collector pattern from the
v0.9.7 prototype. HomeViewModel + SettingsViewModel will inject this in
the next two tasks.

Spec: docs/superpowers/specs/2026-05-03-library-size-holder-design.md
"
```

---

## Task 2: Refactor `HomeViewModel` to inject the holder

Drop the local `MutableStateFlow<LibrarySizeBreakdown>` + `init { viewModelScope.launch { … collect … } }` block introduced on the v0.9.7 branch. Inject `LibrarySizeHolder` instead. Use `librarySizeHolder.size` directly in `musicDataFlow`.

**Files:**
- Modify: `feature/home/src/main/kotlin/com/stash/feature/home/HomeViewModel.kt`

- [ ] **Step 1: Read the current state of the file**

Use the Read tool on `C:/Users/theno/Projects/MP3APK/feature/home/src/main/kotlin/com/stash/feature/home/HomeViewModel.kt`. Verify the v0.9.7 prototype is present:
- A `private val _libraryDiskSize: MutableStateFlow<LibrarySizeBreakdown>` field (around lines 58-72)
- A matching `private val libraryDiskSize: StateFlow<LibrarySizeBreakdown>`
- An `init { viewModelScope.launch { … } }` block that collects `getTrackCount()` and updates `_libraryDiskSize`
- `FileOrganizer` and `LibrarySizeBreakdown` imports
- `FileOrganizer` constructor parameter
- `musicDataFlow` consumes `libraryDiskSize` at the 4th combine slot

- [ ] **Step 2: Replace the imports**

Find the imports near the top (around lines 11-28). Make these specific changes:

- **Remove**: `import com.stash.data.download.files.FileOrganizer`
- **Keep**: `import com.stash.data.download.files.LibrarySizeBreakdown` (still used in MusicData)
- **Add**: `import com.stash.data.download.files.LibrarySizeHolder`
- **Remove** (if no longer used elsewhere in the file): `import kotlinx.coroutines.Dispatchers`, `import kotlinx.coroutines.flow.asStateFlow`, `import kotlinx.coroutines.flow.distinctUntilChanged`, `import kotlinx.coroutines.withContext`

After this step, the imports should not reference `FileOrganizer`, `Dispatchers`, `asStateFlow`, `distinctUntilChanged`, or `withContext` (unless any of these are used elsewhere in the file).

- [ ] **Step 3: Replace the constructor params**

In the `@HiltViewModel class HomeViewModel @Inject constructor(...)` declaration:

- **Remove**: `private val fileOrganizer: FileOrganizer,`
- **Add**: `private val librarySizeHolder: LibrarySizeHolder,`

(Both as private val constructor parameters in the same position they were before.)

- [ ] **Step 4: Delete the local StateFlow + init collector block**

Find and remove the entire block (around lines 56-87 of the current file) that includes:
- The KDoc comment about the disk-truth size
- `private val _libraryDiskSize = MutableStateFlow(LibrarySizeBreakdown(0L, 0L, 0))`
- `private val libraryDiskSize: StateFlow<LibrarySizeBreakdown> = _libraryDiskSize.asStateFlow()`
- The `init { viewModelScope.launch { … } }` that drives it

This entire ~30-line block goes away; the holder replaces it.

- [ ] **Step 5: Update `musicDataFlow` to consume `librarySizeHolder.size`**

The `combine(...) { … }` call assembling `musicDataFlow` had `libraryDiskSize` as its 4th input. Change that single line to `librarySizeHolder.size`. The transform lambda's parameter name (`librarySize`) and the produced `MusicData(playlists, recentlyAdded, trackCount, librarySize)` line both stay unchanged.

After this edit, `musicDataFlow` should read like:

```kotlin
private val musicDataFlow = combine(
    musicRepository.getAllPlaylists(),
    musicRepository.getRecentlyAdded(20),
    musicRepository.getTrackCount(),
    librarySizeHolder.size,
) { playlists, recentlyAdded, trackCount, librarySize ->
    MusicData(playlists, recentlyAdded, trackCount, librarySize)
}
```

- [ ] **Step 6: Build :feature:home to confirm compile**

```bash
cd C:/Users/theno/Projects/MP3APK
./gradlew :feature:home:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. If unresolved-reference errors mention `Dispatchers`, `asStateFlow`, `distinctUntilChanged`, or `withContext`, an extraneous import remained — re-run Step 2.

- [ ] **Step 7: Build the full app to confirm Hilt graph still resolves**

```bash
cd C:/Users/theno/Projects/MP3APK
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. Hilt errors here would indicate the singleton's dependencies aren't visible (shouldn't be the case — `MusicRepository` and `FileOrganizer` are both already in the graph).

- [ ] **Step 8: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK
git add feature/home/src/main/kotlin/com/stash/feature/home/HomeViewModel.kt
git commit -m "refactor(home): drop local StateFlow; observe LibrarySizeHolder

HomeViewModel no longer manages its own MutableStateFlow + collector for
the disk-truth library size. The shared @Singleton LibrarySizeHolder
handles that — same StateFlow semantics (last-good preservation,
WhileSubscribed pause) but with one walker for the whole app.

Net -22 LOC in HomeViewModel; FileOrganizer is no longer a constructor
dep here (the holder owns the walker).
"
```

---

## Task 3: Wire `LibrarySizeHolder` into `SettingsViewModel`

Replace `MusicRepository.getTotalStorageBytes()` (the broken DAO SUM at combine index 3) with `librarySizeHolder.size`. The `values[3] as Long` cast becomes `as LibrarySizeBreakdown` and we read `.totalBytes`.

**Files:**
- Modify: `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsViewModel.kt`

- [ ] **Step 1: Read the current state of SettingsViewModel**

Read `C:/Users/theno/Projects/MP3APK/feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsViewModel.kt` from the top to ~line 160. Locate:
- The constructor — note the existing `@Inject constructor(...)` parameter list
- The `combine(...)` block at line ~94 with 17 inputs
- Specifically line 98 (`musicRepository.getTotalStorageBytes()`)
- Line ~117 (`val storageBytes = values[3] as Long`)
- Line 148 (`totalStorageBytes = storageBytes,`)

- [ ] **Step 2: Add the import**

In the import block at the top of the file, add:

```kotlin
import com.stash.data.download.files.LibrarySizeBreakdown
import com.stash.data.download.files.LibrarySizeHolder
```

(Sorted alphabetically with the other imports.)

- [ ] **Step 3: Add `LibrarySizeHolder` to the constructor**

In the `@HiltViewModel class SettingsViewModel @Inject constructor(...)` declaration, add a new constructor parameter:

```kotlin
private val librarySizeHolder: LibrarySizeHolder,
```

Place it adjacent to other repository/data-layer dependencies (e.g. right after `private val musicRepository: MusicRepository,`).

- [ ] **Step 4: Replace the combine source at index 3**

Find the line at ~98:

```kotlin
musicRepository.getTotalStorageBytes(),
```

Replace it with:

```kotlin
librarySizeHolder.size,
```

This changes the index-3 type from `Flow<Long>` to `StateFlow<LibrarySizeBreakdown>`. The combine's vararg machinery doesn't care; only the consumer cast at Step 5 needs adjustment.

- [ ] **Step 5: Update the value cast inside the combine lambda**

Find the line at ~117:

```kotlin
val storageBytes = values[3] as Long
```

Replace with:

```kotlin
val storageBytes = (values[3] as LibrarySizeBreakdown).totalBytes
```

The downstream `SettingsUiState(... totalStorageBytes = storageBytes ...)` line stays unchanged — its type is still `Long`.

- [ ] **Step 6: Build :feature:settings to confirm compile**

```bash
cd C:/Users/theno/Projects/MP3APK
./gradlew :feature:settings:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. ClassCastExceptions can only happen at runtime — the compile-time check confirms only that the new types match the cast.

- [ ] **Step 7: Build the full app + run any existing tests for regression**

```bash
cd C:/Users/theno/Projects/MP3APK
./gradlew :app:assembleDebug :feature:settings:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL` for both tasks (or `NO-SOURCE` for the test task if `:feature:settings` has no test sources, which is the case in this repo per memory `feedback_install_after_fix.md`).

- [ ] **Step 8: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK
git add feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsViewModel.kt
git commit -m "feat(settings): Storage row from disk via LibrarySizeHolder

Settings → Storage used now reads from the shared @Singleton
LibrarySizeHolder (filesystem walker) rather than MusicRepository's
DAO SUM. On legacy libraries the DAO column SUM understates by up
to ~80% because many rows have file_size_bytes stuck at 0 from older
download paths; the filesystem walker is disk truth.

Mirrors HomeViewModel's source. The two screens now agree on the
storage number.
"
```

---

## Task 4: Bump version + ship

- [ ] **Step 1: Bump versionCode + versionName**

In `app/build.gradle.kts` find:

```kotlin
versionCode = 44
versionName = "0.9.7"
```

Leave them as-is — this work bundles into v0.9.7. (The v0.9.7 release was never tagged because the prototype on the branch had bugs the user kept reporting; this plan finishes v0.9.7 properly.)

- [ ] **Step 2: Build the signed release APK**

```bash
cd C:/Users/theno/Projects/MP3APK
./gradlew :app:assembleRelease
```

Expected: `BUILD SUCCESSFUL`. APK lands at `app/build/outputs/apk/release/app-release.apk`.

- [ ] **Step 3: Sideload over the user's existing release install**

```bash
cd C:/Users/theno/Projects/MP3APK
adb devices
adb install -r app/build/outputs/apk/release/app-release.apk
```

Expected: device line shown in `adb devices`; install reports `Success`. The user's library data is preserved (same package, same signing key).

If `adb devices` shows no device, ask the user to re-seat the cable + accept the USB-debugging prompt; do not proceed until the device shows up.

- [ ] **Step 4: Run the manual acceptance flow**

Open the **main `com.stash.app`** (not debug). For each scenario, confirm the observed behaviour matches:

1. **Home → sync card** — Storage shows ~16 GB (within 1-2 minutes after install on SAF storage; instantly on internal). FLAC sub-text reads `~2.2 GB FLAC` (or whatever your actual lossless total is).
2. **Settings → scroll to Storage section** — "Storage used" row shows the **same** ~16 GB. (Was: 4.4 GB in v0.9.6.) "Total tracks" row shows the same ~3280 as before.
3. **Cross-screen consistency** — toggle between Home and Settings. Both Storage values match. No flash to 0 on navigation.
4. **Track-count change** — delete one playlist or one track. Both screens' Storage values stay at the previous total during the recompute (1-2 minutes on SAF), then update to the new value.
5. **Background/foreground (5+ s)** — background the app for 30 s, reopen. Both screens immediately show the previous value (`StateFlow.value` cache). No re-walk if track count hasn't changed.

If any scenario fails, **stop and report**. Do not proceed to merge/tag.

- [ ] **Step 5: Commit empty marker for the device-acceptance milestone (optional)**

```bash
cd C:/Users/theno/Projects/MP3APK
git commit --allow-empty -m "test: manual device acceptance — Home + Settings storage parity"
```

Skip if you'd rather keep the history flat.

---

## Task 5: Merge, tag, GitHub release

Only if Task 4 acceptance passed.

- [ ] **Step 1: Create the consolidated release-notes commit on the branch tip**

The existing `8c0eb24` commit message describes only the original v0.9.7 walker work — it predates the holder refactor (Tasks 1-3). Per memory `feedback_release_notes.md`, GitHub renders the release body from the tagged-commit message body, so we need a single commit whose message describes everything that's shipping in v0.9.7.

Create an empty commit at the branch tip containing the consolidated release notes:

```bash
cd C:/Users/theno/Projects/MP3APK
git commit --allow-empty -m "$(cat <<'EOF'
feat: 0.9.7 — Home + Settings Storage from disk truth

Replaces the unreliable DB `SUM(file_size_bytes)` for the Home and
Settings "Storage used" displays. On legacy libraries thousands of
rows have file_size_bytes stuck at 0 (older download paths didn't
populate it; the recovery backfill failed on orphaned rows + SAF
content:// path mismatches). The new path walks the actual filesystem
and reports disk truth.

Changes:
- FileOrganizer.computeMusicLibrarySize() — storage-mode-aware walker.
  Internal `filesDir/music` walk for default users; SAF DocumentFile
  traversal for users on external storage. Returns total bytes +
  lossless-codec subset (count + bytes).
- LibrarySizeHolder — @Singleton wrapping a StateFlow<LibrarySizeBreakdown>
  driven by MusicRepository.getTrackCount() changes. scan(prev) preserves
  last-good values across walk failures; WhileSubscribed(5s) pauses the
  walker when no UI observes.
- HomeViewModel + SettingsViewModel both observe the holder. Both
  screens now show the same correct Storage value (~16 GB on a typical
  legacy library; was 4.4 GB pre-fix).

Cold-start UX: the StateFlow shows 0 GB until the first walk completes
(1-2 minutes for SAF / ~3000 files; instant for internal). Persisting
the last-known size across app restarts is deferred.

Specs:
- docs/superpowers/specs/2026-05-03-library-size-holder-design.md

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

Capture the resulting SHA — you'll need it for Step 5 (the tag command). For example:

```bash
cd C:/Users/theno/Projects/MP3APK
RELEASE_SHA=$(git rev-parse HEAD)
echo "release commit: $RELEASE_SHA"
```

- [ ] **Step 2: Push the feature branch**

```bash
cd C:/Users/theno/Projects/MP3APK
git push origin fix/home-storage-from-filesystem
```

- [ ] **Step 3: Switch to master, fast-forward to remote, merge with --no-ff**

```bash
cd C:/Users/theno/Projects/MP3APK
git checkout master
git pull --ff-only origin master
git merge --no-ff fix/home-storage-from-filesystem -m "Merge branch 'fix/home-storage-from-filesystem'"
```

Expected: clean merge commit. Anything other than `Merge made by the 'ort' strategy.` (or similar) means a conflict — stop and resolve manually.

- [ ] **Step 4: Push master**

```bash
cd C:/Users/theno/Projects/MP3APK
git push origin master
```

- [ ] **Step 5: Create + push lightweight tag**

Tag the consolidated release-notes commit (`$RELEASE_SHA` from Step 1, also visible in `git log --oneline -5` as the most recent commit on `fix/home-storage-from-filesystem` before the merge commit). Lightweight tag — per memory `feedback_release_notes.md`, an annotated tag would compete with the commit body for the release notes.

```bash
cd C:/Users/theno/Projects/MP3APK
git tag v0.9.7 "$RELEASE_SHA"
git push origin v0.9.7
```

If `$RELEASE_SHA` isn't in scope (different shell), look it up:

```bash
cd C:/Users/theno/Projects/MP3APK
git log master --oneline | head -5
# Find the "feat: 0.9.7 — Home + Settings Storage from disk truth" commit. Tag that SHA.
```

- [ ] **Step 6: Create GitHub release using the tagged-commit body**

```bash
cd C:/Users/theno/Projects/MP3APK
gh release create v0.9.7 \
  --title "v0.9.7 — Home + Settings Storage from disk truth"
```

(No `--notes` — GitHub renders the tagged commit's body. Per memory `feedback_release_notes.md` this is the release-notes mechanism this project uses.)

Expected: gh prints the release URL.

- [ ] **Step 7: Verify the release on GitHub**

Open the URL gh printed (or `https://github.com/rawnaldclark/Stash/releases/tag/v0.9.7`). Confirm:
- Title reads "v0.9.7 — Home + Settings Storage from disk truth"
- Body contains the natural-language notes from the version-bump commit
- The auto-generated release APK appears (after CI build completes — typically ~5-10 minutes)

---

## Skills reference

- @superpowers:verification-before-completion — before claiming Task 4 / Task 5 done. Don't skip the on-device acceptance flow.
- Memory `feedback_install_after_fix.md` — always installDebug or sideload release after a fix; compile-pass alone isn't enough.
- Memory `feedback_release_notes.md` — release body comes from the tagged commit's message body, not the tag annotation. Use lightweight tags + omit `--notes`.

## Risks & rollback

- **Cold-start UX regression** — the StateFlow shows 0 GB until the first walk completes (1-2 minutes for SAF / 3000 tracks). Same UX as v0.9.7's Home today; no new regression, but worth flagging if a user complains. Mitigation deferred (DataStore-persisted last-known size) per spec.
- **Hilt graph misconfiguration** — `LibrarySizeHolder` is `@Singleton` and depends on `FileOrganizer` (`@Singleton`) + `MusicRepository` (binding). Both are already in the graph; the new singleton should resolve. If `:app:assembleDebug` reports a Hilt error mentioning `LibrarySizeHolder`, it's a missing import or a typo — re-check Step 1 of Task 1.
- **Rollback** — revert the merge commit on master + delete the tag (`git push --delete origin v0.9.7`). User's data is unaffected (no schema change, no DataStore writes). Worst case the user reinstalls v0.9.6 from GitHub releases.
