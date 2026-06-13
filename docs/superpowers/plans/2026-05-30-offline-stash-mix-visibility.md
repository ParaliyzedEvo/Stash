# Offline Stash Mix Visibility + Playback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Stash Mixes display and stream in Offline mode (decoupled from the sync setting), from both the detail screen and the Home screen.

**Architecture:** A DAO `STASH_MIX` exemption makes mix tracks visible offline (the shared query behind every surface). A single pure helper `queuePlayableTracks(...)` becomes the one source of truth for what's enqueueable, used by `PlaylistDetailViewModel` (from a reviewed stash) and `HomeViewModel` (the gap). Connected → mix streams; genuinely offline → downloaded-only + the existing per-tap "Online only" message.

**Tech Stack:** Kotlin, Room (`:core:data` DAO), Hilt, Kotlin coroutines + `kotlinx-coroutines-test`, MockK (`:core:media`/Room tests) and Mockito-Kotlin (`feature/library` ViewModel tests — match each module's existing convention), JUnit4, Robolectric (Room DAO tests).

**Spec:** `docs/superpowers/specs/2026-05-30-offline-stash-mix-visibility-design.md` — read first.

**Branch:** `feat/customizable-stash-mixes` (depends on the Stash Mixes feature; NOT master).

---

## Background the implementer needs

- A reviewed, verified fix is in **`git stash@{0}`** ("offline-mixes-visibility fix"). Run
  `git stash show -p stash@{0}` to see the exact diff. It contains: the DAO exemption, the
  `PlaylistDetailViewModel.playableTracks()` helper (inline), a `MusicRepositoryImpl` comment,
  and 6 tests (3 in `TrackDaoStreamableTest`, 3 in `MixOfflineTapGuardTest`). **Use it as the
  verbatim reference** for the DAO hunk and the test bodies — do NOT re-derive those from
  scratch. This plan diverges from the stash in ONE way: the playability rule is extracted
  into a shared helper (Task 1) and both ViewModels call it, instead of living inline in
  `PlaylistDetailViewModel` only.
- **Do not `git stash apply` the whole thing** — the branch churned (`PlaylistDetailViewModel`
  now injects `recipeDao`/`discoveryQueueDao` + has a `buildState` flow the stash predates), so
  an all-at-once apply risks a messy conflict. Apply the relevant hunks per task instead.
- `Track` model (`core/model/.../Track.kt`): `filePath: String?` (null = not on disk),
  `isDownloaded: Boolean`, `isStreamable: Boolean`. The "downloaded-only" filter is
  `it.filePath != null`.
- `:core:media` depends on `:core:model` and is depended on by both `feature/library` and
  `feature/home`. `ConnectivityMonitor` (`core/media/.../streaming/ConnectivityMonitor.kt`)
  exposes `fun isConnected(): Boolean`.
- `StreamingPreference` (`com.stash.core.data.prefs.StreamingPreference`): `suspend fun current(): Boolean`.
- `PlaylistDetailViewModel` already injects `connectivityMonitor` + `streamingPreference`.
  Its `playTrack`/`shuffleAll`/`playAll` currently each inline
  `val streamingOn = streamingPreference.current(); val playable = if (streamingOn) tracks else tracks.filter { it.filePath != null }`.
- `HomeViewModel` does NOT inject `ConnectivityMonitor`. `playPlaylist` (~line 632) and
  `addPlaylistToQueue` (~line 683) inline the same downloaded-only filter. `Playlist.type`
  gives `PlaylistType.STASH_MIX` for the `isMix` check.

---

## File structure

| File | Responsibility | Change |
|------|----------------|--------|
| `core/media/.../streaming/QueuePlayability.kt` | The pure enqueue rule | **Create** |
| `core/data/.../db/dao/TrackDao.kt` | `getByPlaylist` query | **Modify** (STASH_MIX exemption) |
| `core/data/.../repository/MusicRepositoryImpl.kt` | comment | **Modify** (clarify) |
| `feature/library/.../PlaylistDetailViewModel.kt` | detail playback | **Modify** (add `playableTracks()` delegating to helper) |
| `feature/home/.../HomeViewModel.kt` | home playback | **Modify** (inject `ConnectivityMonitor`; use helper) |
| `feature/home/build.gradle.kts` | module test deps | **Modify** (add Mockito/coroutines-test/Truth — module has only JUnit today) |
| `core/media/.../streaming/QueuePlayabilityTest.kt` | — | **Create** |
| `core/data/.../db/dao/TrackDaoStreamableTest.kt` | — | **Modify** (+3 from stash) |
| `feature/library/.../MixOfflineTapGuardTest.kt` | — | **Modify** (+3 from stash) |
| `feature/home/.../HomeViewModelPlaybackTest.kt` | — | **Create** |

---

## Task 1: The shared `queuePlayableTracks` helper

**Files:** Create `core/media/src/main/kotlin/com/stash/core/media/streaming/QueuePlayability.kt`; test `core/media/src/test/kotlin/com/stash/core/media/streaming/QueuePlayabilityTest.kt`.

- [ ] **Step 1: Write the failing test (exhaustive truth table).**
```kotlin
package com.stash.core.media.streaming

import com.stash.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class QueuePlayabilityTest {
    private fun dl(id: Long) = Track(id = id, title = "d$id", artist = "a", isDownloaded = true, isStreamable = true, filePath = "/m/$id.opus")
    private fun stream(id: Long) = Track(id = id, title = "s$id", artist = "a", isDownloaded = false, isStreamable = true, filePath = null)
    private val tracks = listOf(dl(1), stream(2))

    @Test fun `streaming on returns all regardless of mix or connection`() {
        assertEquals(listOf(1L, 2L), queuePlayableTracks(tracks, isMix = false, streamingEnabled = true, connected = false).map { it.id })
        assertEquals(listOf(1L, 2L), queuePlayableTracks(tracks, isMix = true, streamingEnabled = true, connected = false).map { it.id })
    }
    @Test fun `offline mix connected returns all`() {
        assertEquals(listOf(1L, 2L), queuePlayableTracks(tracks, isMix = true, streamingEnabled = false, connected = true).map { it.id })
    }
    @Test fun `offline mix disconnected returns downloaded only`() {
        assertEquals(listOf(1L), queuePlayableTracks(tracks, isMix = true, streamingEnabled = false, connected = false).map { it.id })
    }
    @Test fun `offline non-mix returns downloaded only even when connected`() {
        assertEquals(listOf(1L), queuePlayableTracks(tracks, isMix = false, streamingEnabled = false, connected = true).map { it.id })
    }
}
```
- [ ] **Step 2: Run — expect FAIL** (unresolved `queuePlayableTracks`):
`./gradlew :core:media:testDebugUnitTest --tests "com.stash.core.media.streaming.QueuePlayabilityTest"`
Expected: FAIL — unresolved reference.
- [ ] **Step 3: Implement the helper.**
```kotlin
package com.stash.core.media.streaming

import com.stash.core.model.Track

/**
 * The subset of [tracks] that can actually be enqueued right now.
 *
 * Streaming mode → every track (stream-only ones resolve via the Kennyy/Squid
 * chain inside the player). Offline mode → downloaded-only, EXCEPT a Stash Mix
 * with a live connection: a Mix is an inherently online discovery surface
 * (stream-only by design), so its streamable tracks stay enqueueable whenever
 * the device is connected, regardless of the Online/Offline preference. With
 * no connection it falls back to downloaded-only; the per-tap guard in
 * PlaylistDetailViewModel surfaces "Online only — connect to play" for a tapped
 * stream-only track.
 *
 * Pure (no I/O) so it is the single, exhaustively-testable source of truth for
 * the rule shared by PlaylistDetailViewModel and HomeViewModel.
 */
fun queuePlayableTracks(
    tracks: List<Track>,
    isMix: Boolean,
    streamingEnabled: Boolean,
    connected: Boolean,
): List<Track> =
    if (streamingEnabled || (isMix && connected)) tracks
    else tracks.filter { it.filePath != null }
```
- [ ] **Step 4: Run — expect PASS.**
- [ ] **Step 5: Commit.**
```bash
git add core/media/src/main/kotlin/com/stash/core/media/streaming/QueuePlayability.kt core/media/src/test/kotlin/com/stash/core/media/streaming/QueuePlayabilityTest.kt
git commit -m "feat(media): add pure queuePlayableTracks rule for offline mix playback"
```

---

## Task 2: DAO `STASH_MIX` offline-visibility exemption

**Files:** Modify `core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt` (`getByPlaylist`); test `core/data/src/test/kotlin/com/stash/core/data/db/dao/TrackDaoStreamableTest.kt`.

- [ ] **Step 1: Add the 3 DAO tests verbatim from the stash.** Run `git stash show -p stash@{0}`
  and copy the `TrackDaoStreamableTest.kt` additions exactly: `getByPlaylist STASH_MIX includes
  streamable even when flag is off`, `... still excludes unavailable and unchecked when flag is
  off`, `non-mix still excludes streamable when flag is off`, plus the `stashMixPlaylist()`
  helper. (They use existing helpers `insertDownloaded`/`insertStreamableOnly`/`insertUnavailable`/
  `insertUnchecked`/`customPlaylist`/`crossRef` already in that test file.)
- [ ] **Step 2: Run — expect FAIL** (current query collapses STASH_MIX to downloaded-only):
`./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.db.dao.TrackDaoStreamableTest"`
Expected: FAIL — `STASH_MIX includes streamable even when flag is off` expected `{1,2}` but got `{1}`.
- [ ] **Step 3: Apply the DAO exemption.** In `getByPlaylist`'s WHERE clause, replace:
```
          AND (t.is_downloaded = 1 OR :includeStreamable)
```
with:
```
          AND (
              t.is_downloaded = 1
              OR :includeStreamable
              OR (p.type = 'STASH_MIX' AND t.is_streamable = 1)
          )
```
Also copy the stash's KDoc addition documenting the exemption. Leave the ORDER BY unchanged.
- [ ] **Step 4: Run — expect PASS** (3 new tests + all pre-existing `TrackDaoStreamableTest` green).
- [ ] **Step 5: Commit.**
```bash
git add core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt core/data/src/test/kotlin/com/stash/core/data/db/dao/TrackDaoStreamableTest.kt
git commit -m "fix(mix): show streamable Stash Mix tracks in Offline mode (DAO exemption)"
```

---

## Task 3: Detail-screen playback via the shared helper

**Files:** Modify `feature/library/.../PlaylistDetailViewModel.kt` and
`core/data/.../repository/MusicRepositoryImpl.kt` (comment); test
`feature/library/.../MixOfflineTapGuardTest.kt`.

- [ ] **Step 1: Add the 3 ViewModel tests verbatim from the stash.** From `git stash show -p
  stash@{0}`, copy the `MixOfflineTapGuardTest.kt` additions exactly (`playTrack Offline-mode +
  connected + mix enqueues stream-only track at correct index`, `playAll Offline-mode +
  connected + mix enqueues all streamable tracks`, `playAll Offline-mode + disconnected + mix
  enqueues only downloaded tracks`) plus the `argumentCaptor` import. The `buildVm` helper there
  already accepts `connectivityMonitor` + `streamingPreference` + `recipeDao` + `discoveryQueueDao`.
- [ ] **Step 2: Run — expect FAIL** (no `playableTracks` yet; offline play still filters
  downloaded-only so the stream-only track isn't enqueued):
`./gradlew :feature:library:testDebugUnitTest --tests "com.stash.feature.library.MixOfflineTapGuardTest"`
Expected: FAIL — `enqueues stream-only track at correct index` expected queue `[1,42]` but got `[1]`.
- [ ] **Step 3: Add `playableTracks()` delegating to the helper, and use it in the three play
  methods.** Add the method (note: it calls the Task 1 helper, NOT an inline rule):
```kotlin
private suspend fun playableTracks(): List<Track> {
    val isMix = uiState.value.playlist?.type == PlaylistType.STASH_MIX
    return queuePlayableTracks(
        tracks = uiState.value.tracks,
        isMix = isMix,
        streamingEnabled = streamingPreference.current(),
        connected = connectivityMonitor.isConnected(),
    )
}
```
Import `com.stash.core.media.streaming.queuePlayableTracks` and `com.stash.core.model.PlaylistType` (if not already imported). Then in `playTrack`, `shuffleAll`, and `playAll`, replace each inline
`val streamingOn = streamingPreference.current(); val playable = if (streamingOn) uiState.value.tracks else uiState.value.tracks.filter { it.filePath != null }`
block with `val playable = playableTracks()`. Leave `playTrack`'s existing per-tap connectivity
guard (the "Online only" message path) untouched.
- [ ] **Step 4: Apply the `MusicRepositoryImpl` comment** from the stash (comment-only
  clarification on `getTracksByPlaylist`; no behavior change).
- [ ] **Step 5: Run — expect PASS** (3 new + all pre-existing `MixOfflineTapGuardTest` green).
- [ ] **Step 6: Commit.**
```bash
git add feature/library/src/main/kotlin/com/stash/feature/library/PlaylistDetailViewModel.kt core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepositoryImpl.kt feature/library/src/test/kotlin/com/stash/feature/library/MixOfflineTapGuardTest.kt
git commit -m "fix(mix): play Offline Stash Mix tracks from detail when connected (shared helper)"
```

---

## Task 4: Home-screen playback (close the gap)

**Files:** Modify `feature/home/build.gradle.kts` and `feature/home/.../HomeViewModel.kt`;
create `feature/home/src/test/kotlin/com/stash/feature/home/HomeViewModelPlaybackTest.kt`.

- [ ] **Step 0: Add the test stack to `:feature:home` (it currently has only JUnit).** The
  module's `build.gradle.kts` declares just `testImplementation("junit:junit:4.13.2")` and its
  existing tests are pure-function (no mocking) — so MockK/Mockito/coroutines-test/Truth are all
  absent and the test below won't compile without them. Copy the four lines `feature/library`
  uses (the project-wide ViewModel-test convention is **Mockito-Kotlin**, matching the
  `MixOfflineTapGuardTest` you just worked in — do NOT introduce MockK here). Add to
  `feature/home/build.gradle.kts` dependencies:
```kotlin
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
testImplementation("com.google.truth:truth:1.4.4")
testImplementation("org.mockito:mockito-core:5.14.2")
testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
```
(Confirm these versions against `feature/library/build.gradle.kts` and use whatever it has, in case they've moved.) Verify it resolves: `./gradlew :feature:home:compileDebugUnitTestKotlin` should at least get past dependency resolution (it'll fail later on the missing test file — that's fine).

- [ ] **Step 1: Write the failing test** (Mockito-Kotlin, mirroring `MixOfflineTapGuardTest`'s
  harness style). Construct `HomeViewModel` with `mock()` for every ctor dependency, stubbing
  only the four the play path touches: `musicRepository.getTracksByPlaylist(id)` →
  `flowOf(listOf(downloaded, streamOnly))`, `streamingPreference.current()` (onBlocking) →
  `false`, `connectivityMonitor.isConnected()` → the case value, and `playerRepository` with an
  `argumentCaptor<List<Track>>()` on `setQueue`. The ctor has ~20 params — pass `mock()` for all
  the irrelevant ones (mechanical; the `playerState` flow may need `doReturn MutableStateFlow(...)`
  like `MixOfflineTapGuardTest` does). Verify the rule wiring (Home computes `isMix` + `connected`
  and calls the helper). Write these `playPlaylist` cases plus one `addPlaylistToQueue`:
```kotlin
// playPlaylist, offline + connected + STASH_MIX -> setQueue receives [downloaded, streamOnly]
// playPlaylist, offline + disconnected + STASH_MIX -> setQueue receives [downloaded] only
// playPlaylist, offline + connected + CUSTOM (control) -> setQueue receives [downloaded] only
// addPlaylistToQueue, offline + connected + STASH_MIX -> addToQueue called for both tracks
```
Mark `@OptIn(ExperimentalCoroutinesApi::class)`, drive with `runTest`/`runCurrent`. **If the
~20-param ctor proves genuinely impractical to construct, STOP and report** — do not weaken the
test; we'll decide between proceeding and relying on the Task 1 helper tests + on-device.
- [ ] **Step 2: Run — expect FAIL** (Home still filters downloaded-only offline):
`./gradlew :feature:home:testDebugUnitTest --tests "com.stash.feature.home.HomeViewModelPlaybackTest"`
Expected: FAIL — offline+connected+mix queue expected `[1,42]` but got `[1]`.
- [ ] **Step 3: Inject `ConnectivityMonitor` + use the helper.** Add
  `private val connectivityMonitor: com.stash.core.media.streaming.ConnectivityMonitor` to the
  `HomeViewModel` ctor. In `playPlaylist` and `addPlaylistToQueue`, replace the inline
  `val playable = if (streamingPreference.current()) tracks else tracks.filter { it.filePath != null }`
  with:
```kotlin
val playable = queuePlayableTracks(
    tracks = tracks,
    isMix = playlist.type == PlaylistType.STASH_MIX,
    streamingEnabled = streamingPreference.current(),
    connected = connectivityMonitor.isConnected(),
)
```
Import `com.stash.core.media.streaming.queuePlayableTracks` and `PlaylistType`.
- [ ] **Step 4: Run — expect PASS.**
- [ ] **Step 5: Compile the app (Hilt graph picks up the new ctor dep).**
`./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL.
- [ ] **Step 6: Commit.**
```bash
git add feature/home/build.gradle.kts feature/home/src/main/kotlin/com/stash/feature/home/HomeViewModel.kt feature/home/src/test/kotlin/com/stash/feature/home/HomeViewModelPlaybackTest.kt
git commit -m "fix(mix): play Offline Stash Mixes from Home when connected (close the gap)"
```

---

## Task 5: Full verification + cleanup

**Files:** none (verification).

- [ ] **Step 1: Run the affected module suites.**
`./gradlew :core:media:testDebugUnitTest :core:data:testDebugUnitTest :feature:library:testDebugUnitTest :feature:home:testDebugUnitTest`
Expected: BUILD SUCCESSFUL. If a pre-existing `:core:data` DAO test is red, confirm it fails on a clean `master` checkout before attributing it here (the spec flags this); do NOT fix out-of-scope failures — note them.
- [ ] **Step 2: Compile the app.** `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL.
- [ ] **Step 3: Drop the now-applied stash.** The stash content is now committed across Tasks 2-3.
  Before dropping, diff the stash against the work to confirm all **four** artifacts were
  reproduced (the `MusicRepositoryImpl` comment from Task 3 Step 4 is the easiest to forget):
  (1) the `TrackDao.getByPlaylist` STASH_MIX hunk, (2) the `MusicRepositoryImpl.getTracksByPlaylist`
  comment, (3) the `PlaylistDetailViewModel` play-path changes, (4) all 6 tests. Run
  `git stash show -p stash@{0}` and eyeball each against the committed code. Only then:
  `git stash list` (confirm `stash@{0}` is the offline-mixes one) → `git stash drop stash@{0}`.
  If anything was NOT reproduced, add it before dropping.
- [ ] **Step 4: On-device (manual, the real acceptance):** with the app installed, toggle
  **Offline mode** ON, open a Stash Mix → it shows its **full** track list (not "0 tracks"); with
  a live connection, tapping a track plays (streams); from the **Home** screen, playing the mix
  also streams. In airplane mode, the list still shows but a tapped stream-only track surfaces the
  "Online only — connect to play" message. This is the acceptance check the unit tests stand in for.

---

## Notes for the implementer

- **DRY:** the rule lives ONLY in `queuePlayableTracks` (Task 1). Tasks 3 and 4 must call it,
  not re-inline the boolean. The detail per-tap guard (`isStreamable && !isDownloaded` in
  `playTrack`) is a separate concern — leave it; do not unify it with the helper's
  `filePath != null` predicate (they're equivalent for stream-only stubs).
- **YAGNI:** only `playPlaylist` + `addPlaylistToQueue` on Home (not `playAllMixes`/`playLikedSongs`
  — see spec). No greying-out UI. No repo-level refactor.
- **Stash as reference, not auto-apply:** copy the DAO hunk and the 6 test bodies verbatim from
  `git stash show -p stash@{0}`; hand-place them per task; do not `git stash apply` the blob.
- `./gradlew` is slow (a minute+ per module); that's normal.
