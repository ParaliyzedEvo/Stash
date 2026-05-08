# Find in FLAC Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Find in FLAC" option to the Now Playing flag-icon dialog so a user with an m4a track currently playing can ask the lossless source registry for an upgrade. Hidden when the current track is already FLAC. Fire-and-forget snackbar feedback; next-play swap (no mid-playback disruption).

**Architecture:** A small `LosslessUpgrader` abstraction lives in `:core:data` so `:feature:nowplaying` (which can't depend on `:data:download` due to the existing graph direction) can call it. The impl in `:data:download` reuses `DownloadManager.tryLosslessDownload(track, forced = true)` — the same private helper that already powers Stash Mix's force-lossless behavior. Just promote it from `private` to `internal` and call it from the impl.

**Tech Stack:** Kotlin / Hilt / Room (no schema change) / Compose / coroutines. Tests use JUnit4 + mockk + kotlinx-coroutines-test (matches existing module conventions).

**Spec:** [docs/superpowers/specs/2026-05-08-find-in-flac-design.md](../specs/2026-05-08-find-in-flac-design.md)

---

## File Map

### Created

- `core/model/src/main/kotlin/com/stash/core/model/UpgradeResult.kt` — sealed type with three variants (`Upgraded`, `NoMatch`, `Error`)
- `core/model/src/main/kotlin/com/stash/core/model/TrackExtensions.kt` — `Track.isFlac` extension (one-line, may live alongside other Track extensions if they exist)
- `core/data/src/main/kotlin/com/stash/core/data/lossless/LosslessUpgrader.kt` — interface
- `data/download/src/main/kotlin/com/stash/data/download/lossless/LosslessUpgraderImpl.kt` — `@Singleton` impl
- `data/download/src/main/kotlin/com/stash/data/download/lossless/di/UpgraderModule.kt` — Hilt `@Binds` module
- `data/download/src/test/kotlin/com/stash/data/download/lossless/LosslessUpgraderImplTest.kt` — mockk-driven unit tests
- `feature/nowplaying/src/test/kotlin/com/stash/feature/nowplaying/NowPlayingViewModelTest.kt` — first test in this module; exercises `snackbarCopyFor` + `findInFlacForCurrentTrack` paths

### Modified

- `data/download/src/main/kotlin/com/stash/data/download/DownloadManager.kt` — promote `tryLosslessDownload` from `private` to `internal`
- `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingViewModel.kt` — inject `LosslessUpgrader`, add `findInFlacForCurrentTrack()`, add internal pure `snackbarCopyFor(result)` helper
- `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingScreen.kt` — prepend a fourth `OutlinedButton` to the existing AlertDialog at lines 136–198, gated on `!track.isFlac`
- `feature/nowplaying/build.gradle.kts` — add Robolectric + JUnit + mockk testImplementation deps (first tests in this module)
- `app/build.gradle.kts` — bump `versionCode` 55 → 56, `versionName` "0.9.17" → "0.9.18"

---

## Conventions

- **TDD throughout.** Failing test → run it → minimal implementation → run it → commit. Watch the failure for the right reason; don't skip that step.
- **One commit per task.** Match the spec'd commit message byte-for-byte (no addendum unless the deviation genuinely warrants documentation in `git log`).
- **Co-Authored-By trailer** on every commit: `Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>`.
- **No scope creep.** This plan does not touch the v0.9.17 deferral pipeline, the Home banner, or the Settings UX. Stay in the Now Playing dialog + the new abstraction layer.
- **Pre-existing master test failures (`YtLibraryCanonicalizerTest`, `PlaylistTypeTest`)** noted in v0.9.17's Task 14 still apply. Ignore them in test sweeps.

---

## Worktree setup (do this once, before Task 1)

- [ ] **Step 1: Create the worktree from current master**

```bash
cd /c/Users/theno/Projects/MP3APK
git fetch origin
git worktree add .worktrees/find-in-flac -b feat/find-in-flac origin/master
```

After fetch, `origin/master` should already include the v0.9.18 spec commits (`78d7a2e` + `5dd1dd9`) since those landed during brainstorming. If `git worktree add` checks out a stale snapshot, follow up with `git -C .worktrees/find-in-flac pull --ff-only` from the worktree.

- [ ] **Step 2: Copy `local.properties` so Last.fm credentials work in the worktree**

```bash
cp local.properties .worktrees/find-in-flac/local.properties
```

- [ ] **Step 3: cd into the worktree**

All subsequent paths are relative to the worktree root.

```bash
cd .worktrees/find-in-flac
```

---

## Task 1: Model additions — `UpgradeResult` sealed type + `Track.isFlac` extension

**Why this task is small:** Pure data model. No DI, no behavior, no tests of side-effects. The point is to give Tasks 2-4 the vocabulary they need.

**Files:**
- Create: `core/model/src/main/kotlin/com/stash/core/model/UpgradeResult.kt`
- Create OR modify: `core/model/src/main/kotlin/com/stash/core/model/TrackExtensions.kt` (or add to an existing extension file if one already exists in the package)
- Test: `core/model/src/test/kotlin/com/stash/core/model/TrackIsFlacTest.kt` (small, just the extension property)

- [ ] **Step 1: Discover the actual `Track` field**

Per the spec's Phase 1 verification step. Run:

```bash
grep -n "val.*format\|val.*codec\|val.*extension\|isLossless\|fileFormat" core/model/src/main/kotlin/com/stash/core/model/Track.kt
```

Confirm the field name (likely `fileFormat`, default `"opus"`). Note whether an `isFlac` accessor already exists. If yes, skip the extension creation in Step 4 below.

- [ ] **Step 2: Write the failing test for `Track.isFlac`**

`core/model/src/test/kotlin/com/stash/core/model/TrackIsFlacTest.kt`:

```kotlin
package com.stash.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackIsFlacTest {

    @Test fun `isFlac true for fileFormat = flac`() {
        val track = stubTrack(fileFormat = "flac")
        assertTrue(track.isFlac)
    }

    @Test fun `isFlac true for fileFormat = FLAC (case-insensitive)`() {
        val track = stubTrack(fileFormat = "FLAC")
        assertTrue(track.isFlac)
    }

    @Test fun `isFlac false for opus`() {
        val track = stubTrack(fileFormat = "opus")
        assertFalse(track.isFlac)
    }

    @Test fun `isFlac false for m4a`() {
        val track = stubTrack(fileFormat = "m4a")
        assertFalse(track.isFlac)
    }

    private fun stubTrack(fileFormat: String): Track = Track(
        // Fill in the minimum-required Track constructor params from the
        // actual Track data class. The Step-1 grep tells you the shape.
        // Common pattern: id = 0, title = "", artist = "", fileFormat = ...
        id = 0,
        title = "",
        artist = "",
        fileFormat = fileFormat,
        // ... any other required params with default-ish values
    )
}
```

- [ ] **Step 3: Verify it fails**

```bash
./gradlew :core:model:testDebugUnitTest --tests "*TrackIsFlacTest*"
```

If `:core:model` doesn't yet have a `testDebugUnitTest` task wired up, use `./gradlew :core:model:test`. Either way, expected: FAIL with `Unresolved reference 'isFlac'`.

- [ ] **Step 4: Add the extension**

`core/model/src/main/kotlin/com/stash/core/model/TrackExtensions.kt` (or merge into an existing file):

```kotlin
package com.stash.core.model

/**
 * v0.9.18: convenience accessor for "is this a lossless file?". Used
 * by the Now Playing "Find in FLAC" dialog to hide the upgrade option
 * when the current track is already FLAC.
 *
 * Case-insensitive comparison guards against any future tagger that
 * writes "FLAC" instead of "flac" — the file extension is canonical
 * lowercase per [com.stash.data.download.files.FileOrganizer], but
 * a defensive comparison is essentially free here.
 */
val Track.isFlac: Boolean
    get() = fileFormat.equals("flac", ignoreCase = true)
```

- [ ] **Step 5: Add the `UpgradeResult` sealed type**

`core/model/src/main/kotlin/com/stash/core/model/UpgradeResult.kt`:

```kotlin
package com.stash.core.model

/**
 * Outcome of a user-initiated lossless upgrade attempt
 * (Now Playing → "Find in FLAC"). Drives the snackbar copy in
 * [com.stash.feature.nowplaying.NowPlayingViewModel].
 *
 * Distinct from sync-pipeline outcomes (`TrackDownloadOutcome`)
 * because the user-facing language differs ("Upgraded" vs.
 * "Downloaded") and because no queue row is involved on this path.
 */
sealed interface UpgradeResult {
    /** Lossless source served a match; file was replaced and the row was updated. */
    data object Upgraded : UpgradeResult

    /** Sources are reachable but no candidate cleared the confidence threshold. */
    data object NoMatch : UpgradeResult

    /**
     * Caught exception during resolve/download — network, captcha-required,
     * registry threw, finalize failed. Generic enough to cover all of them
     * without leaking implementation detail to the snackbar.
     */
    data object Error : UpgradeResult
}
```

- [ ] **Step 6: Verify the test passes**

```bash
./gradlew :core:model:testDebugUnitTest --tests "*TrackIsFlacTest*"
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add core/model/src/main/kotlin/com/stash/core/model/UpgradeResult.kt \
        core/model/src/main/kotlin/com/stash/core/model/TrackExtensions.kt \
        core/model/src/test/kotlin/com/stash/core/model/TrackIsFlacTest.kt
git commit -m "$(cat <<'EOF'
feat(model): UpgradeResult sealed type + Track.isFlac extension

Vocabulary commit for v0.9.18 Find in FLAC. UpgradeResult drives the
snackbar copy in the Now Playing flag-icon dialog; Track.isFlac
guards the dialog button visibility (the upgrade option is hidden
when the current track is already FLAC).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: `LosslessUpgrader` interface + impl + Hilt binding

**Why this task carries weight:** This is the architectural seam that lets `:feature:nowplaying` (which depends only on `:core:data` + `:core:media`) reach the lossless pipeline (which lives in `:data:download`). The interface lives in `:core:data` so both sides can see it; the impl + Hilt binding live in `:data:download` where they have access to `DownloadManager.tryLosslessDownload`.

This task also promotes `DownloadManager.tryLosslessDownload` from `private` to `internal` so the impl can call it. No new wrapper method on `DownloadManager` is needed — the impl is the wrapper.

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/DownloadManager.kt` — promote `tryLosslessDownload` visibility
- Create: `core/data/src/main/kotlin/com/stash/core/data/lossless/LosslessUpgrader.kt` — interface
- Create: `data/download/src/main/kotlin/com/stash/data/download/lossless/LosslessUpgraderImpl.kt` — impl
- Create: `data/download/src/main/kotlin/com/stash/data/download/lossless/di/UpgraderModule.kt` — Hilt `@Binds` module
- Test: `data/download/src/test/kotlin/com/stash/data/download/lossless/LosslessUpgraderImplTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.stash.data.download.lossless

import com.stash.core.model.Track
import com.stash.core.model.UpgradeResult
import com.stash.data.download.DownloadManager
import com.stash.data.download.TrackDownloadResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LosslessUpgraderImplTest {

    private val downloadManager: DownloadManager = mockk()
    private val subject = LosslessUpgraderImpl(downloadManager)

    @Test fun `Success maps to Upgraded`() = runTest {
        coEvery { downloadManager.tryLosslessDownload(any(), forced = true) } returns
            TrackDownloadResult.Success(filePath = "/path/to/file.flac")
        assertEquals(UpgradeResult.Upgraded, subject.upgradeToLossless(stubTrack()))
    }

    @Test fun `null maps to NoMatch`() = runTest {
        coEvery { downloadManager.tryLosslessDownload(any(), forced = true) } returns null
        assertEquals(UpgradeResult.NoMatch, subject.upgradeToLossless(stubTrack()))
    }

    @Test fun `Unmatched maps to NoMatch`() = runTest {
        coEvery { downloadManager.tryLosslessDownload(any(), forced = true) } returns
            TrackDownloadResult.Unmatched()
        assertEquals(UpgradeResult.NoMatch, subject.upgradeToLossless(stubTrack()))
    }

    @Test fun `Failed maps to NoMatch`() = runTest {
        coEvery { downloadManager.tryLosslessDownload(any(), forced = true) } returns
            TrackDownloadResult.Failed("network")
        assertEquals(UpgradeResult.NoMatch, subject.upgradeToLossless(stubTrack()))
    }

    @Test fun `Deferred maps to NoMatch`() = runTest {
        coEvery { downloadManager.tryLosslessDownload(any(), forced = true) } returns
            TrackDownloadResult.Deferred
        assertEquals(UpgradeResult.NoMatch, subject.upgradeToLossless(stubTrack()))
    }

    @Test fun `thrown exception maps to Error`() = runTest {
        coEvery { downloadManager.tryLosslessDownload(any(), forced = true) } throws
            RuntimeException("boom")
        assertEquals(UpgradeResult.Error, subject.upgradeToLossless(stubTrack()))
    }

    @Test fun `passes forced = true to bypass global lossless toggle`() = runTest {
        coEvery { downloadManager.tryLosslessDownload(any(), forced = true) } returns null
        subject.upgradeToLossless(stubTrack())
        // mockk's `forced = true` matcher in the coEvery already enforces this;
        // a separate coVerify is redundant but documents the intent.
    }

    private fun stubTrack(): Track = Track(
        // Mirror Task 1's stubTrack helper signature.
        id = 1,
        title = "Karma Police",
        artist = "Radiohead",
        fileFormat = "opus",
    )
}
```

- [ ] **Step 2: Verify it fails**

```bash
./gradlew :data:download:testDebugUnitTest --tests "*LosslessUpgraderImpl*"
```

Expected: FAIL — `LosslessUpgrader`, `LosslessUpgraderImpl`, and `tryLosslessDownload`'s visibility are all unresolved/inaccessible.

- [ ] **Step 3: Promote `tryLosslessDownload` visibility**

Open `data/download/src/main/kotlin/com/stash/data/download/DownloadManager.kt`, find:

```kotlin
private suspend fun tryLosslessDownload(track: Track, forced: Boolean = false): TrackDownloadResult? {
```

Change `private` to `internal`:

```kotlin
internal suspend fun tryLosslessDownload(track: Track, forced: Boolean = false): TrackDownloadResult? {
```

No other code changes in this file.

- [ ] **Step 4: Define the `LosslessUpgrader` interface**

`core/data/src/main/kotlin/com/stash/core/data/lossless/LosslessUpgrader.kt`:

```kotlin
package com.stash.core.data.lossless

import com.stash.core.model.Track
import com.stash.core.model.UpgradeResult

/**
 * v0.9.18: thin abstraction for the Now Playing "Find in FLAC" path.
 *
 * Lives in `:core:data` so `:feature:nowplaying` (which depends on
 * `:core:data` but not `:data:download`) can call the lossless
 * pipeline. The implementation lives in `:data:download` where it
 * has access to [com.stash.data.download.DownloadManager].
 *
 * Distinct from the sync-time download flow: this entry point
 * always passes `forced = true` so it ignores the user's global
 * lossless toggle and `youtubeFallbackEnabled` pref. The user
 * tapped Find in FLAC; they want a lossless attempt regardless of
 * their default settings.
 */
interface LosslessUpgrader {
    suspend fun upgradeToLossless(track: Track): UpgradeResult
}
```

- [ ] **Step 5: Implement `LosslessUpgraderImpl`**

`data/download/src/main/kotlin/com/stash/data/download/lossless/LosslessUpgraderImpl.kt`:

```kotlin
package com.stash.data.download.lossless

import android.util.Log
import com.stash.core.data.lossless.LosslessUpgrader
import com.stash.core.model.Track
import com.stash.core.model.UpgradeResult
import com.stash.data.download.DownloadManager
import com.stash.data.download.TrackDownloadResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the Now Playing dialog to [DownloadManager.tryLosslessDownload].
 * Maps the nullable result onto the user-facing [UpgradeResult] tri-state.
 *
 * Conservative mapping: any non-Success outcome becomes [NoMatch].
 * The user doesn't need to distinguish "registry returned null" from
 * "lossless URL came back 404" — both are "no FLAC for you right
 * now." Thrown exceptions become [Error] so the snackbar can say
 * "Couldn't check lossless sources" rather than the misleading
 * "no match."
 */
@Singleton
class LosslessUpgraderImpl @Inject constructor(
    private val downloadManager: DownloadManager,
) : LosslessUpgrader {

    override suspend fun upgradeToLossless(track: Track): UpgradeResult = runCatching {
        when (downloadManager.tryLosslessDownload(track, forced = true)) {
            is TrackDownloadResult.Success -> UpgradeResult.Upgraded
            null,
            is TrackDownloadResult.Unmatched,
            is TrackDownloadResult.Failed,
            TrackDownloadResult.Deferred -> UpgradeResult.NoMatch
        }
    }.getOrElse { e ->
        Log.w(TAG, "upgradeToLossless threw for ${track.id}", e)
        UpgradeResult.Error
    }

    private companion object {
        const val TAG = "LosslessUpgrader"
    }
}
```

Note the exhaustive `when`: `TrackDownloadResult` has four sealed variants (`Success`, `Unmatched`, `Failed`, `Deferred`). All four plus `null` are enumerated. If `TrackDownloadResult` gains a new variant later, this `when` will be a compiler error — by design.

- [ ] **Step 6: Bind via Hilt**

`data/download/src/main/kotlin/com/stash/data/download/lossless/di/UpgraderModule.kt`:

```kotlin
package com.stash.data.download.lossless.di

import com.stash.core.data.lossless.LosslessUpgrader
import com.stash.data.download.lossless.LosslessUpgraderImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class UpgraderModule {

    @Binds
    @Singleton
    abstract fun bindLosslessUpgrader(impl: LosslessUpgraderImpl): LosslessUpgrader
}
```

- [ ] **Step 7: Verify the tests pass**

```bash
./gradlew :data:download:testDebugUnitTest --tests "*LosslessUpgraderImpl*"
```

Expected: PASS (7 tests).

- [ ] **Step 8: Verify the Hilt graph still builds**

```bash
./gradlew :app:kspDebugKotlin
```

Expected: BUILD SUCCESSFUL. If Hilt complains about `LosslessUpgrader` not being provided, double-check that `UpgraderModule` is in the `:data:download` package picked up by Hilt's compile-time scanning.

- [ ] **Step 9: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/lossless/LosslessUpgrader.kt \
        data/download/src/main/kotlin/com/stash/data/download/lossless/LosslessUpgraderImpl.kt \
        data/download/src/main/kotlin/com/stash/data/download/lossless/di/UpgraderModule.kt \
        data/download/src/main/kotlin/com/stash/data/download/DownloadManager.kt \
        data/download/src/test/kotlin/com/stash/data/download/lossless/LosslessUpgraderImplTest.kt
git commit -m "$(cat <<'EOF'
feat(lossless): LosslessUpgrader — Now Playing FLAC upgrade path

Thin abstraction in :core:data so :feature:nowplaying can reach
the lossless pipeline without taking a :data:download dep
(circular). The impl in :data:download wraps DownloadManager's
existing tryLosslessDownload helper (promoted from private to
internal) with forced = true, so the upgrade ignores the user's
global lossless toggle and youtubeFallbackEnabled pref. Maps any
non-Success outcome to UpgradeResult.NoMatch and thrown exceptions
to UpgradeResult.Error.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: NowPlayingViewModel — action + pure helper + tests

**Why this task is ViewModel-shaped:** the action itself is a one-liner that delegates to `LosslessUpgrader`, but the snackbar copy is pure mapping logic that benefits from being its own testable function. This task also stands up the test infrastructure for `:feature:nowplaying` (no test directory exists today — first tests in this module).

**Files:**
- Modify: `feature/nowplaying/build.gradle.kts` — add `testImplementation` deps for Robolectric + JUnit + mockk + kotlinx-coroutines-test (mirrors `:feature:home/build.gradle.kts` from v0.9.17 Task 13)
- Modify: `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingViewModel.kt` — add the `LosslessUpgrader` constructor param, the `findInFlacForCurrentTrack()` action, and the internal `snackbarCopyFor(result)` helper
- Create: `feature/nowplaying/src/test/kotlin/com/stash/feature/nowplaying/NowPlayingViewModelTest.kt`

- [ ] **Step 1: Add test deps to `feature/nowplaying/build.gradle.kts`**

If `:feature:nowplaying` has no `testImplementation` block today, add one. Use `:feature:home/build.gradle.kts` as the canonical pattern (it picked up the same deps in v0.9.17 Task 13b). Required deps:
- `libs.junit`
- `libs.mockk`
- `libs.coroutines.test`
- `libs.androidx.test.core`
- `libs.robolectric`
- `testOptions { unitTests { isReturnDefaultValues = true } }` in the `android { }` block

- [ ] **Step 2: Write the failing tests**

`feature/nowplaying/src/test/kotlin/com/stash/feature/nowplaying/NowPlayingViewModelTest.kt`:

```kotlin
package com.stash.feature.nowplaying

import com.stash.core.data.lossless.LosslessUpgrader
import com.stash.core.model.Track
import com.stash.core.model.UpgradeResult
import io.mockk.coEvery
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class NowPlayingViewModelSnackbarCopyTest {

    @Test fun `Upgraded maps to "Upgraded to FLAC"`() {
        assertEquals("Upgraded to FLAC", snackbarCopyFor(UpgradeResult.Upgraded))
    }

    @Test fun `NoMatch maps to "No lossless match found"`() {
        assertEquals("No lossless match found", snackbarCopyFor(UpgradeResult.NoMatch))
    }

    @Test fun `Error maps to "Couldn't check lossless sources"`() {
        assertEquals("Couldn't check lossless sources", snackbarCopyFor(UpgradeResult.Error))
    }
}
```

For the action itself, write a state-based test using a real `NowPlayingViewModel` if its dependencies can be mocked cleanly. If the constructor has too many heavy deps to make this practical, a mockk-`relaxed` approach works:

```kotlin
class NowPlayingViewModelFindInFlacTest {

    private val upgrader: LosslessUpgrader = mockk()

    @Test fun `findInFlacForCurrentTrack happy path emits looking then upgraded`() = runTest {
        coEvery { upgrader.upgradeToLossless(any()) } returns UpgradeResult.Upgraded
        // Construct ViewModel with relaxed mocks for unrelated deps + the real upgrader.
        // Set _uiState's currentTrack to a non-FLAC track.
        // Call findInFlacForCurrentTrack().
        // advanceUntilIdle().
        // Collect _userMessages and assert ["Looking for FLAC…", "Upgraded to FLAC"].
    }

    @Test fun `findInFlacForCurrentTrack no-match path`() = runTest {
        coEvery { upgrader.upgradeToLossless(any()) } returns UpgradeResult.NoMatch
        // ... assert ["Looking for FLAC…", "No lossless match found"]
    }

    @Test fun `findInFlacForCurrentTrack error path`() = runTest {
        coEvery { upgrader.upgradeToLossless(any()) } returns UpgradeResult.Error
        // ... assert ["Looking for FLAC…", "Couldn't check lossless sources"]
    }

    @Test fun `findInFlacForCurrentTrack no-op when current track is FLAC`() = runTest {
        // Set _uiState's currentTrack to a FLAC track (fileFormat = "flac").
        // Call findInFlacForCurrentTrack().
        // advanceUntilIdle().
        // coVerify(exactly = 0) { upgrader.upgradeToLossless(any()) }.
        // Assert _userMessages stayed empty.
    }
}
```

Read the existing `NowPlayingViewModel` constructor first to enumerate all the deps you need to mock. Most are likely already `relaxUnitFun`-able since they don't return values that matter for these tests.

NOTE: if instantiating `NowPlayingViewModel` end-to-end is too painful (lots of repository wiring), pull the action body out into an `internal suspend fun` that takes the upgrader and a track and emits to a SharedFlow — then test that helper. The ViewModel becomes a one-line caller. But try the direct test first; it's likely fine.

- [ ] **Step 3: Verify the tests fail**

```bash
./gradlew :feature:nowplaying:testDebugUnitTest --tests "*NowPlayingViewModel*"
```

Expected: FAIL — `snackbarCopyFor`, `findInFlacForCurrentTrack`, and the new constructor param are all unresolved.

- [ ] **Step 4: Add the action + helper to `NowPlayingViewModel`**

Inject `LosslessUpgrader` into the constructor (alongside the existing deps):

```kotlin
@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    // ... existing deps ...
    private val losslessUpgrader: com.stash.core.data.lossless.LosslessUpgrader,
) : ViewModel() {
```

Add the action (place near the existing `flagCurrentTrackAsWrongMatch` and `deleteCurrentTrack` methods so all the dialog actions sit together):

```kotlin
/**
 * v0.9.18: upgrade the currently-playing track to FLAC if any
 * lossless source can serve it. Fire-and-forget — emits a "looking"
 * snackbar immediately, then a result snackbar when the resolve +
 * download completes.
 *
 * No-op when nothing is playing or when the current track is already
 * FLAC (UI hides the button in that case, but defensive guard here
 * in case state changes mid-tap).
 */
fun findInFlacForCurrentTrack() {
    val track = _uiState.value.currentTrack ?: return
    if (track.isFlac) return
    viewModelScope.launch {
        _userMessages.tryEmit("Looking for FLAC…")
        val result = losslessUpgrader.upgradeToLossless(track)
        _userMessages.tryEmit(snackbarCopyFor(result))
    }
}
```

Add the pure helper as a top-level `internal` function in the same file (or in a new sibling file `NowPlayingSnackbarCopy.kt` if you prefer):

```kotlin
internal fun snackbarCopyFor(result: UpgradeResult): String = when (result) {
    UpgradeResult.Upgraded -> "Upgraded to FLAC"
    UpgradeResult.NoMatch -> "No lossless match found"
    UpgradeResult.Error -> "Couldn't check lossless sources"
}
```

Imports: `com.stash.core.model.UpgradeResult`, `com.stash.core.model.isFlac` (the extension from Task 1).

- [ ] **Step 5: Verify the tests pass**

```bash
./gradlew :feature:nowplaying:testDebugUnitTest --tests "*NowPlayingViewModel*"
```

Expected: PASS (3 snackbar-copy tests + 4 findInFlac tests = 7 total).

- [ ] **Step 6: Verify the app still builds**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL. The new constructor param means Hilt regenerates the factory — KSP rebuild is automatic.

- [ ] **Step 7: Commit**

```bash
git add feature/nowplaying/build.gradle.kts \
        feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingViewModel.kt \
        feature/nowplaying/src/test/kotlin/com/stash/feature/nowplaying/NowPlayingViewModelTest.kt
git commit -m "$(cat <<'EOF'
feat(nowplaying): findInFlacForCurrentTrack action + snackbar copy

ViewModel exposes the new Find in FLAC action. Fire-and-forget:
emits "Looking for FLAC…" immediately, then maps the
LosslessUpgrader's UpgradeResult to one of three snackbar copy
strings via a pure helper. Defensive no-op when the current track
is already FLAC, even though the UI also hides the button — guards
against state-change races between the dialog open and the tap.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Add the dialog button — UI

**Why this task is small:** one conditional `OutlinedButton` prepended to the existing dialog, plus on-device verification that the four-button stack renders correctly. No tests — the visibility logic is too thin to merit a Compose UI test.

**Files:**
- Modify: `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingScreen.kt` — add the new button at lines 137-198 inside the existing `AlertDialog`'s `text` column

- [ ] **Step 1: Locate the existing dialog**

In `NowPlayingScreen.kt`, find the `if (showWrongMatchDialog && track != null) { AlertDialog(...) }` block. The `text = { Column { ... } }` block currently has three OutlinedButtons (lines 158, 167, 176). Find that Column.

- [ ] **Step 2: Prepend the new button**

Inside the `Column`, before the existing "Find a better match" OutlinedButton, add:

```kotlin
if (!track.isFlac) {
    androidx.compose.material3.OutlinedButton(
        onClick = {
            viewModel.findInFlacForCurrentTrack()
            showWrongMatchDialog = false
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        androidx.compose.material3.Text("Find in FLAC")
    }
}
```

The `track` variable is already in scope from the outer `if (showWrongMatchDialog && track != null)` guard. Imports needed:
- `com.stash.core.model.isFlac` (the extension from Task 1)

- [ ] **Step 3: Build + install on device**

```bash
./gradlew :app:installDebug
```

Per project memory (`feedback_install_after_fix.md`): always `installDebug` after a UI change. Compile-pass alone is not enough.

- [ ] **Step 4: On-device verification**

Open Stash on the connected device, play a non-FLAC track, tap the flag icon in the Now Playing top bar, and verify:

- The dialog title is still **"What's wrong with this song?"** (unchanged per spec).
- The dialog has **four** OutlinedButtons stacked vertically:
  1. "Find in FLAC" (new — outlined, primary tone)
  2. "Find a better match"
  3. "Delete from library"
  4. "Delete and block forever" (filled red, error tone)
- Tapping "Find in FLAC" dismisses the dialog and shows a snackbar reading **"Looking for FLAC…"**.
- The snackbar transitions to one of: `"Upgraded to FLAC"`, `"No lossless match found"`, or `"Couldn't check lossless sources"` once the resolve+download completes (typical 5-15 seconds).

Then play a FLAC track (find one in the library, or download a FLAC via the search tab), tap the flag icon, and verify:

- The dialog still opens with the same title.
- The "Find in FLAC" button is **NOT** visible.
- The other three buttons ("Find a better match", "Delete from library", "Delete and block forever") are visible as before.

- [ ] **Step 5: Commit**

```bash
git add feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingScreen.kt
git commit -m "$(cat <<'EOF'
feat(nowplaying): Find in FLAC dialog button

Prepends a fourth OutlinedButton to the existing flag-icon dialog.
Visible only when the currently-playing track is not already FLAC
(guarded by Track.isFlac). Tap dismisses the dialog and triggers
NowPlayingViewModel.findInFlacForCurrentTrack — the snackbar copy
handles the rest of the user feedback.

Dialog title intentionally unchanged ("What's wrong with this
song?") per the v0.9.18 spec — the user explicitly de-prioritized
the rename.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Version bump + final test sweep + ship

**Files:**
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Bump versionCode + versionName**

```kotlin
// app/build.gradle.kts
versionCode = 56
versionName = "0.9.18"
```

Find the existing `versionCode = 55` and `versionName = "0.9.17"` lines and update them.

- [ ] **Step 2: Run the full test suite for changed modules**

```bash
./gradlew \
  :core:model:testDebugUnitTest \
  :core:data:testDebugUnitTest \
  :data:download:testDebugUnitTest \
  :feature:nowplaying:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL. Two pre-existing master failures (`YtLibraryCanonicalizerTest.OMV...` and `PlaylistTypeTest.enum...`) are unrelated; ignore them. If anything else fails, stop and investigate.

- [ ] **Step 3: Build + install debug APK**

```bash
./gradlew :app:installDebug
```

Expected: APK installs on the connected device.

- [ ] **Step 4: Manual smoke test on device**

DO NOT execute this step yourself — document the procedure for the user to run. The user will work through this matrix before pushing/tagging.

Procedure:
1. **Non-FLAC track upgrade — happy path.** Find an m4a track in the library (any track that pre-dated v0.9.17, or any track downloaded with YT fallback enabled). Play it. Tap the flag icon. Verify the four-button dialog. Tap "Find in FLAC". Verify "Looking for FLAC…" snackbar. Wait for completion. If the track has a lossless match, expect "Upgraded to FLAC". Skip to next track and back; the file should now be the FLAC. If no lossless match, expect "No lossless match found".
2. **FLAC track — button hidden.** Find a FLAC track. Play it. Tap the flag icon. Verify "Find in FLAC" is NOT in the dialog (only three buttons visible).
3. **Source error path.** Disconnect WiFi (or otherwise force both sources unreachable). Play a non-FLAC track. Tap "Find in FLAC". Expect "Couldn't check lossless sources" snackbar (or "No lossless match found" if the failure surfaces as null rather than throw — both are acceptable, the spec accepts the conservative mapping).
4. **No regressions in existing dialog options.** From the same dialog, tap "Find a better match" → existing flag-for-Failed-Matches behavior fires. Tap "Delete from library" → existing delete behavior. Tap "Delete and block forever" → existing block + delete. None of these should have changed.
5. **Resilience: navigate away mid-upgrade.** Tap "Find in FLAC", immediately exit the Now Playing screen (back to Home). Wait 30 seconds. Return to the track and play it. The FLAC file should be in place if the upgrade succeeded. The intermediate snackbar may have been missed — that's acceptable per the spec's non-goals.

- [ ] **Step 5: Commit + final ship**

```bash
git add app/build.gradle.kts
git commit -m "$(cat <<'EOF'
chore: bump versionCode 55->56, versionName 0.9.17->0.9.18

v0.9.18 — Find in FLAC. New dialog option in the Now Playing flag
icon that runs the lossless source registry against the
currently-playing track and replaces the file in place if a
higher-quality FLAC is served. Hidden when the current track is
already FLAC. Fire-and-forget snackbar feedback; next-play swap
(no mid-playback disruption).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 6: DO NOT push or tag**

The user gates push + tag. Stop here. The user will run the manual smoke test (Step 4), then decide to:
1. Merge to master (`git checkout master && git merge --ff-only feat/find-in-flac`)
2. Tag (`git tag -a v0.9.18 -m "v0.9.18 — Find in FLAC"`)
3. Push (`git push origin master && git push origin v0.9.18`)

The release workflow takes over from there.

---

## Notes for the executor

- **The `LosslessUpgrader` interface lives in `:core:data`, NOT `:core:model`.** This is deliberate — `:core:model` is for pure data classes; an interface that takes a domain `Track` and returns an `UpgradeResult` is a service contract, which fits `:core:data`'s role. If you find existing service interfaces in `:core:data` (e.g., `TrackDownloader`), follow that location precedent.
- **`tryLosslessDownload` visibility change is the one private-API promotion in this plan.** Don't promote anything else by accident. The function's signature stays exactly the same; only the access modifier changes.
- **No deferral integration.** A null/failed result from the registry must NOT write to `download_queue` with `WAITING_FOR_LOSSLESS`. The user is asking a one-shot question; if the answer is no, the answer is no. The deferred-track pipeline from v0.9.17 stays untouched.
- **Don't reframe the dialog title.** The user explicitly de-prioritized this. The semantic mismatch ("What's wrong with this song?" containing "Find in FLAC") is acknowledged in the spec's Non-goals.
- **Pre-existing test failures still apply.** `YtLibraryCanonicalizerTest.OMV...` and `PlaylistTypeTest.enum...` will continue to fail in the sweep. Ignore them; they are unrelated to v0.9.18.
- **Per project memory:** `installDebug` after every UI change. Compile-pass alone is not enough.
