# Premium Discovery Experience — Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refine the discovery surface (Search → artist profile → album page) into a premium, consistent experience: a tap-to-play song row, an in-place Online/Offline switch on Search, and a fast (non-throttled) preview.

**Architecture:** Refinement, not restructure. One shared `SongRow` (rename of `PreviewDownloadRow`) replaces the cluttered inline-button row across all three screens. The Home streaming chip/sheet move to `core:ui` so Search can reuse them. The preview's FLAC resolve gains a `bypassRateLimit` fast path (mirroring streaming) so a user tap isn't throttled behind background prefetches.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt DI, coroutines, MockK/JUnit4 (`:core:data`/`:core:media`/`:feature:*` use JUnit `Assert`; `:core:media` also has Truth), Media3.

**Spec:** `docs/superpowers/specs/2026-07-11-premium-discovery-experience-design.md`

---

## Grounding facts (verified — do not re-derive)

- **Row:** `feature/search/.../PreviewDownloadRow.kt`, composable `PreviewDownloadRow(item: SearchResultItem, isDownloading, isDownloaded, isPreviewLoading, isPreviewPlaying, onPreview, onStopPreview, onDownload, modifier, isWaitingForLossless=false, isResolving=false, onPlayNext={}, onAddToQueue={}, onAddToPlaylist={}, onStartRadio={})`. Call sites: `SearchScreen.kt:491`, `PopularTracksSection.kt:65`, `AlbumDiscoveryScreen.kt:204`. `AlbumDiscoveryScreen` also has a separate `NativeAlbumTrackRow` for Qobuz-native albums.
- **Play action:** the row's play = `onPreview` → `TrackActionsDelegate.previewTrack(item)` → full stream when Online (`playFromStream`), 30s preview when Offline. Delegate exposes `previewState`, `downloadingIds`, `downloadedIds`, `waitingForLosslessIds`, `previewLoadingId` flows.
- **Now-playing source:** `PlayerRepository.playerState` (a `StateFlow<PlayerState>`); `PlayerState.currentTrack: Track?`, `Track.youtubeId: String?`. `SearchViewModel` and `ArtistProfileViewModel`/`AlbumDiscoveryViewModel` already inject `PlayerRepository`.
- **Streaming chip/sheet:** `feature/home/streaming/StreamingModeChip.kt` → `StreamingModeChip(streamingEnabled: Boolean, onClick: () -> Unit, modifier=…)`; `StreamingModeSheet.kt` → `StreamingModeSheet(streamingEnabled: Boolean, onSelect: (Boolean) -> Unit, onDismiss: () -> Unit, …)`. Both stateless, primitive params, use `StashTheme` (core:ui) + `StashConstants` (core:common). Home wires them in `HomeScreen.kt` (`showStreamingSheet` state + `HomeViewModel.applyStreamingMode`).
- **Fast preview machinery:** `SearchPreviewMediaSource.create` → `LosslessUrlPrefetcher.lookup(track)` (awaits) → `LosslessSourceRegistry.resolve(query)` (rate-limited). `QbdlxQobuzSource.resolve(query)` delegates to `resolveInternal(query, bypassRateLimit=false, …)`; `callLimited(bypassRateLimit, block)` already does `if (!bypassRateLimit && !rateLimiter.acquire(id)) return null`. Interface `LosslessSource.resolve(query): SourceResult?` and `LosslessSourceRegistry.resolve(query)` currently have NO bypass param. **5 implementors** must gain the param: `QbdlxQobuzSource`, `QobuzSource`, `AmzSource`, `ArcodSource`, `KennyySource`.
- **Test filters:** always use `--tests "FQCN"`; use the daemon; on a Gradle socket `BindException`, `./gradlew --stop` then retry. Pre-existing unrelated failures exist in `:data:ytmusic` and `:feature:search` — filter to your tests.

## File structure

**Phase A — Fast preview (`data:download`, `core:media`):**
- Modify `data/download/.../lossless/LosslessSource.kt` (interface: add flag)
- Modify `data/download/.../lossless/LosslessSourceRegistry.kt` (thread flag)
- Modify the 5 sources (`Qbdlx`, `Qobuz`, `Amz`, `Arcod`, `Kennyy`)
- Modify `core/media/.../preview/LosslessUrlPrefetcher.kt` (foreground bypass + in-flight dedup)

**Phase B — `SongRow` (`feature:search`):**
- Rename `PreviewDownloadRow.kt` → `SongRow.kt`; whole-row-plays, remove ▶, now-playing indicator
- Modify call sites: `SearchScreen.kt`, `PopularTracksSection.kt`, `AlbumDiscoveryScreen.kt`

**Phase C — Album unify (`feature:search`):**
- Modify `AlbumDiscoveryScreen.kt` (replace `NativeAlbumTrackRow` with `SongRow`, download hidden)

**Phase D — Offline chip (`core:ui`, `feature:search`):**
- Move chip/sheet to `core/ui/.../components/streaming/`; update Home imports
- Modify `SearchViewModel.kt` + `SearchScreen.kt` (mode state + header chip + sheet)

**Phase E — Integration:** assemble, targeted test sweep, device smoke test.

---

## Phase A — Fast preview

### Task A1: Thread `bypassRateLimit` through the LosslessSource interface + registry + 5 sources

**Files:** `LosslessSource.kt`, `LosslessSourceRegistry.kt`, `QbdlxQobuzSource.kt`, `QobuzSource.kt`, `AmzSource.kt`, `ArcodSource.kt`, `KennyySource.kt`
**Test:** `data/download/src/test/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxBypassRateLimitTest.kt` (new)

- [ ] **Step 1: Write the failing test** — the qbdlx source resolves even when the rate limiter has no tokens, iff `bypassRateLimit = true`.

```kotlin
package com.stash.data.download.lossless.qbdlx

import com.stash.data.download.lossless.AggregatorRateLimiter
import com.stash.data.download.lossless.TrackQuery
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class QbdlxBypassRateLimitTest {
    // Rate limiter denies all tokens (acquire=false). With bypass=false the source
    // must return null WITHOUT hitting the network; with bypass=true it proceeds.
    // Mock the api/credential collaborators to a trivial successful match.
    // See QbdlxQobuzSourceTest for the exact mock harness; reuse it and add:
    @Test fun `bypass true resolves despite exhausted rate limiter`() = runTest {
        // limiter.acquire(any()) returns false; bypass=true → resolveInternal proceeds
        // → assertNotNull(source.resolve(query, bypassRateLimit = true))
    }
    @Test fun `bypass false is throttled when limiter has no tokens`() = runTest {
        // limiter.acquire(any()) returns false; bypass=false → assertNull(source.resolve(query))
    }
}
```

> Implementer note: copy the mock harness from the existing `QbdlxQobuzSourceTest`/`QbdlxQobuzSource`-adjacent tests (MockK for `QbdlxApiClient`, `QbdlxCredentialStore`, and a `mockk<AggregatorRateLimiter>` whose `acquire` returns `false`). Keep assertions to the two behaviors above.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :data:download:testDebugUnitTest --tests "com.stash.data.download.lossless.qbdlx.QbdlxBypassRateLimitTest"`
Expected: FAIL — `resolve(query, bypassRateLimit = …)` doesn't exist.

- [ ] **Step 3: Interface — add the flag** (`LosslessSource.kt`)

```kotlin
    /**
     * @param bypassRateLimit when true, a user-initiated (foreground) resolve
     *   skips the per-source token bucket. Background/speculative callers pass
     *   false (the default). Sources without a rate limiter ignore it.
     */
    suspend fun resolve(query: TrackQuery, bypassRateLimit: Boolean = false): SourceResult?
```

- [ ] **Step 4: qbdlx — honour the flag** (`QbdlxQobuzSource.kt`)

```kotlin
    override suspend fun resolve(query: TrackQuery, bypassRateLimit: Boolean): SourceResult? {
        if (!isEnabled()) return null
        return resolveInternal(query, bypassRateLimit = bypassRateLimit, requestedQuality = null)
    }
```

- [ ] **Step 5: The other 4 sources — accept the param, thread to their own limiter**

Each has `override suspend fun resolve(query: TrackQuery): SourceResult?`. Change the signature to `override suspend fun resolve(query: TrackQuery, bypassRateLimit: Boolean): SourceResult?`. If the source rate-limits via its own `callLimited`/`acquire`, thread `bypassRateLimit` into that gate; if it doesn't rate-limit, the param is unused (accept-and-ignore — a one-line signature change).
- `QobuzSource.kt`, `AmzSource.kt`, `ArcodSource.kt`, `KennyySource.kt`.
Do NOT repeat the `= false` default on overrides (Kotlin forbids it — the default lives on the interface only).

- [ ] **Step 6: Registry — thread the flag** (`LosslessSourceRegistry.kt`)

Change `suspend fun resolve(query: TrackQuery): SourceResult?` → `suspend fun resolve(query: TrackQuery, bypassRateLimit: Boolean = false): SourceResult?` and pass `bypassRateLimit` to each `it.resolve(query, bypassRateLimit)` call inside the ordered walk.

- [ ] **Step 7: Run to verify it passes**

Run: `./gradlew :data:download:testDebugUnitTest --tests "com.stash.data.download.lossless.qbdlx.QbdlxBypassRateLimitTest"`
Expected: PASS (2/2). Then compile the module to confirm every override matches:
`./gradlew :data:download:compileDebugKotlin` → BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/lossless/ \
        data/download/src/test/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxBypassRateLimitTest.kt
git commit -m "feat(lossless): bypassRateLimit flag on resolve (foreground callers skip the token bucket)"
```

---

### Task A2: Foreground preview lookup bypasses the limiter (+ in-flight dedup)

**Files:** `core/media/.../preview/LosslessUrlPrefetcher.kt`
**Test:** `core/media/src/test/kotlin/com/stash/core/media/preview/LosslessUrlPrefetcherTest.kt` (new)

- [ ] **Step 1: Write the failing test** — `lookup` on a cold row resolves with `bypassRateLimit = true`; `warmUp` uses `false`.

```kotlin
package com.stash.core.media.preview

import com.stash.core.model.TrackItem
import com.stash.data.download.lossless.LosslessSourceRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LosslessUrlPrefetcherTest {
    private val registry: LosslessSourceRegistry = mockk(relaxed = true)
    private val item = TrackItem(videoId = "v1", title = "t", artist = "a", durationSeconds = 180.0, thumbnailUrl = null)

    @Test fun `cold lookup resolves with bypassRateLimit true`() = runTest {
        coEvery { registry.resolve(any(), any()) } returns null
        LosslessUrlPrefetcher(registry).lookup(item)
        coVerify { registry.resolve(any(), bypassRateLimit = true) }
    }

    @Test fun `warmUp resolves with bypassRateLimit false`() = runTest {
        coEvery { registry.resolve(any(), any()) } returns null
        val p = LosslessUrlPrefetcher(registry)
        p.warmUp(item)
        // warmUp dispatches onto the prefetcher's own Dispatchers.IO scope (NOT the
        // runTest scheduler), so the verify must WAIT for the real IO coroutine — a
        // bare coVerify would race and fail. `timeout` polls until the call lands.
        coVerify(timeout = 1000) { registry.resolve(any(), bypassRateLimit = false) }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :core:media:testDebugUnitTest --tests "com.stash.core.media.preview.LosslessUrlPrefetcherTest"`
Expected: FAIL — `resolve` doesn't take a second arg yet / lookup doesn't bypass.

- [ ] **Step 3: Implement** — `warmUp` stays rate-limited; `lookup` bypasses on a cold tap and **replaces** an in-flight warmUp deferred so there's no redundant call.

```kotlin
    fun warmUp(track: TrackItem) {
        val key = track.videoId
        cache[key]?.let { if (it.isFresh()) return }
        cache[key] = CachedDeferred(
            deferred = scope.async {
                concurrency.withPermit {
                    runCatching { registry.resolve(track.toQuery(), bypassRateLimit = false) }
                        // Re-throw cancellation so a lookup that supersedes this warmUp
                        // doesn't log a spurious "resolve failed … cancelled" warning
                        // (project convention: never swallow CancellationException).
                        .onFailure { e -> if (e is CancellationException) throw e
                            Log.w(TAG, "resolve failed for $key: ${e.message}") }
                        .getOrNull()
                }
            },
            createdAt = System.currentTimeMillis(),
        )
    }

    /**
     * Foreground (tap) resolve. If a warm result is already COMPLETE, return it
     * instantly. Otherwise cancel any in-flight (rate-limited) warmUp for this
     * track and start a NEW resolve that BYPASSES the rate limiter (user action,
     * mirrors streaming) — no redundant Qobuz call, no token wait.
     */
    suspend fun lookup(track: TrackItem): SourceResult? {
        val key = track.videoId
        // Already-completed warm result → instant. `await()` on a completed deferred
        // returns immediately and is NOT experimental (unlike getCompleted()), so no
        // @OptIn is needed. Only return here when it's actually done — never await a
        // slow in-flight rate-limited warmUp.
        cache[key]?.let { if (it.isFresh() && it.deferred.isCompleted) return it.deferred.await() }
        // Supersede a slow in-flight warmUp so it doesn't spend a token in the background.
        cache[key]?.deferred?.cancel()
        val fast = scope.async {
            runCatching { registry.resolve(track.toQuery(), bypassRateLimit = true) }
                .onFailure { e -> Log.w(TAG, "foreground resolve failed for $key: ${e.message}") }
                .getOrNull()
        }
        cache[key] = CachedDeferred(deferred = fast, createdAt = System.currentTimeMillis())
        return fast.await()
    }
```

> Notes: (1) `await()` under `isCompleted` is instant and non-experimental — no `@OptIn(ExperimentalCoroutinesApi)` needed (avoid `getCompleted()`, which is experimental and would fail the compile gate). (2) Cancelling the in-flight warmUp deferred before replacing is what actually delivers the "no redundant call" the spec §6 promises; cancellation inside `withPermit` safely releases the semaphore. (3) A rapid second tap on the *same* row can't double-resolve: `TrackActionsDelegate.previewTrack` already no-ops a second tap while `previewLoadingId == videoId`, so `lookup` isn't re-entered for the same track mid-flight. (4) Add `import kotlinx.coroutines.CancellationException` (for `warmUp`'s re-throw guard). (5) Optional hardening: the cancel/supersede dedup is correct-by-construction; a deterministic test for it would need the prefetcher's `Dispatchers.IO` scope made injectable — skip unless it regresses; the two tests above lock in the load-bearing bypass-vs-rate-limited split.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :core:media:testDebugUnitTest --tests "com.stash.core.media.preview.LosslessUrlPrefetcherTest"`
Expected: PASS (2/2).

- [ ] **Step 5: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/preview/LosslessUrlPrefetcher.kt \
        core/media/src/test/kotlin/com/stash/core/media/preview/LosslessUrlPrefetcherTest.kt
git commit -m "fix(preview): foreground FLAC lookup bypasses the rate limiter (18s cold tap -> ~1-2s)"
```

---

## Phase B — SongRow

### Task B1: Rename `PreviewDownloadRow` → `SongRow`, whole-row-plays, remove ▶

**Files:** rename `feature/search/.../PreviewDownloadRow.kt` → `SongRow.kt`; modify `SearchScreen.kt`, `PopularTracksSection.kt`, `AlbumDiscoveryScreen.kt`.

- [ ] **Step 1: Rename the file + composable.** `git mv PreviewDownloadRow.kt SongRow.kt`; rename `fun PreviewDownloadRow(` → `fun SongRow(`. Add a KDoc line: the whole row is the play affordance.

- [ ] **Step 2: Make the row body clickable → play/stop-toggle, delete the ▶ button.** In `SongRow`, the root row `Modifier` gains a clickable that **inherits the old ▶/⏹ toggle** the deleted button had (`PreviewDownloadRow.kt:167` is `onClick = if (isPreviewPlaying) onStopPreview else onPreview`):

```kotlin
.clickable { if (isPreviewPlaying) onStopPreview() else onPlay() }
```

Rename the `onPreview` param → `onPlay`. **Keep `onStopPreview`** — this is exactly what preserves a stop affordance: while a 30s preview is playing on a row, tapping that same row stops it (no orphaned preview). Remove the standalone play/preview ▶ button composable. Keep the download button + `⋮` as inner clickables (they already consume their own taps). The `isPreviewLoading`/`isResolving` spinner renders inline on the row (a small leading spinner over the art) instead of on the deleted ▶. Keep all other params (`isDownloading`, `isDownloaded`, `isWaitingForLossless`, `onDownload`, `onPlayNext`, `onAddToQueue`, `onAddToPlaylist`, `onStartRadio`).

- [ ] **Step 3: Update the 3 call sites** — rename `PreviewDownloadRow(` → `SongRow(`, `onPreview =` → `onPlay =`. **Keep the existing `onStopPreview =` wiring** at every call site (it's now the row-tap stop path). `SearchScreen.kt:491`, `PopularTracksSection.kt:65`, `AlbumDiscoveryScreen.kt:204`.

- [ ] **Step 4: Compile**

Run: `./gradlew :feature:search:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (no dangling `PreviewDownloadRow`/`onPreview` references).

- [ ] **Step 5: Commit**

```bash
git add feature/search/src/main/kotlin/com/stash/feature/search/
git commit -m "feat(search): SongRow — whole-row tap-to-play, drop the inline play button"
```

---

### Task B2: Now-playing indicator

**Files:** `SongRow.kt`; `SearchViewModel.kt`, `ArtistProfileViewModel.kt`, `AlbumDiscoveryViewModel.kt` (expose current playing id); the 3 screens (collect + pass).
**Test:** `feature/search/src/test/kotlin/com/stash/feature/search/NowPlayingIdTest.kt` (new, pure matcher)

- [ ] **Step 1: Write the failing test** for the pure activation rule (extract it to a top-level fun so it's testable without Compose).

```kotlin
package com.stash.feature.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingIdTest {
    @Test fun `row is active when its non-blank videoId equals the playing youtubeId`() {
        assertTrue(isRowPlaying(rowVideoId = "v1", currentPlayingYoutubeId = "v1"))
    }
    @Test fun `blank videoId never matches (Qobuz-native)`() {
        assertFalse(isRowPlaying(rowVideoId = "", currentPlayingYoutubeId = ""))
        assertFalse(isRowPlaying(rowVideoId = "", currentPlayingYoutubeId = null))
    }
    @Test fun `different id is not active`() {
        assertFalse(isRowPlaying(rowVideoId = "v1", currentPlayingYoutubeId = "v2"))
    }
}
```

- [ ] **Step 2: Run to verify it fails.** Expected: FAIL — `isRowPlaying` undefined.

- [ ] **Step 3: Implement the matcher** (top of `SongRow.kt`):

```kotlin
/** A row is the now-playing row iff it has a non-blank videoId equal to the
 *  player's current youtubeId. Blank-videoId (Qobuz-native) rows never match. */
fun isRowPlaying(rowVideoId: String, currentPlayingYoutubeId: String?): Boolean =
    rowVideoId.isNotBlank() && rowVideoId == currentPlayingYoutubeId
```

- [ ] **Step 4: Thread the current playing id.** Each VM exposes:

```kotlin
val currentPlayingYoutubeId: StateFlow<String?> =
    playerRepository.playerState
        .map { it.currentTrack?.youtubeId }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)
```

Each screen `collectAsStateWithLifecycle()` it and passes into `SongRow(isPlaying = isRowPlaying(item.videoId, currentPlayingYoutubeId), …)`. `SongRow` gains an `isPlaying: Boolean = false` param and renders the active treatment (accent tint on the row + a small equalizer/▮ glyph near the art) when true.

- [ ] **Step 5: Run to verify it passes + compile**

Run: `./gradlew :feature:search:testDebugUnitTest --tests "com.stash.feature.search.NowPlayingIdTest"` → PASS.
Run: `./gradlew :feature:search:compileDebugKotlin` → BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add feature/search/src/main/kotlin/com/stash/feature/search/ \
        feature/search/src/test/kotlin/com/stash/feature/search/NowPlayingIdTest.kt
git commit -m "feat(search): now-playing indicator on SongRow (id-matched, blank-safe)"
```

---

## Phase C — Album page unify

### Task C1: Replace `NativeAlbumTrackRow` with `SongRow` (download hidden for Qobuz)

**Files:** `AlbumDiscoveryScreen.kt`; `SongRow.kt` (add `downloadSupported` param).

- [ ] **Step 1: Add `downloadSupported: Boolean = true` to `SongRow`.** When false, the download affordance is not rendered at all (Qobuz-native tracks have no videoId → no download-by-id). Everything else (tap-to-play, `⋮`, now-playing) stays.

- [ ] **Step 2: Replace the native branch.** In `AlbumDiscoveryScreen.kt`, delete the `if (vm.isNativeAlbum) { NativeAlbumTrackRow(...) ; return@itemsIndexed }` branch and the `NativeAlbumTrackRow` composable; render `SongRow(..., downloadSupported = vm.downloadSupported, onPlay = { vm.playAlbum(startIndex = index) }, …)` for all album tracks. YT albums keep `downloadSupported = true`; Qobuz-native pass `false`.

- [ ] **Step 3: Compile**

Run: `./gradlew :feature:search:compileDebugKotlin` → BUILD SUCCESSFUL.

- [ ] **Step 4: Run the album VM tests (no regressions)**

Run: `./gradlew :feature:search:testDebugUnitTest --tests "com.stash.feature.search.AlbumDiscoveryViewModel*"` → PASS.

- [ ] **Step 5: Commit**

```bash
git add feature/search/src/main/kotlin/com/stash/feature/search/
git commit -m "feat(search): unify album tracklist under SongRow (download hidden for Qobuz-native)"
```

---

## Phase D — Offline chip on Search

### Task D1: Move the streaming chip + sheet to `core:ui`

**Files:** move `feature/home/.../streaming/StreamingModeChip.kt` + `StreamingModeSheet.kt` → `core/ui/src/main/kotlin/com/stash/core/ui/components/streaming/`; update `HomeScreen.kt` imports. Leave `StreamingModePrompt.kt` in `feature:home` (Search doesn't use it).

- [ ] **Step 1: Extraction pre-check.** Confirm both composables reference only `core:ui`/`core:common` symbols (`StashTheme`, `StashConstants`, Material3, primitive params). If `StreamingModeSheet` pulls any `feature:home`-local string/helper, inline it or move it too. (Params are already `Boolean` + lambdas — no `feature:home` enum.)

- [ ] **Step 2: `git mv` both files** to `core/ui/.../components/streaming/`; change their `package` to `com.stash.core.ui.components.streaming`.

- [ ] **Step 3: Update Home.** In `HomeScreen.kt`, change the two imports to `com.stash.core.ui.components.streaming.StreamingModeChip` / `StreamingModeSheet`. No call-site behavior change.

- [ ] **Step 4: Compile Home + core:ui**

Run: `./gradlew :core:ui:compileDebugKotlin :feature:home:compileDebugKotlin` → BUILD SUCCESSFUL.

- [ ] **Step 5: Run Home's streaming tests (behavior-preserving move)**

Run: `./gradlew :feature:home:testDebugUnitTest --tests "com.stash.feature.home.*Streaming*"` (and any `StreamingMode*` test) → PASS. If none exist, note it.

- [ ] **Step 6: Commit**

```bash
git add core/ui/src/main/kotlin/com/stash/core/ui/components/streaming/ \
        feature/home/src/main/kotlin/com/stash/feature/home/
git commit -m "refactor(ui): move StreamingModeChip/Sheet to core:ui for cross-feature reuse"
```

---

### Task D2: Wire the chip into the Search header

**Files:** `SearchViewModel.kt`, `SearchScreen.kt`.
**Test:** `feature/search/src/test/kotlin/com/stash/feature/search/SearchStreamingModeTest.kt` (new)

- [ ] **Step 1: Write the failing test** — `SearchViewModel` exposes the mode and applies a change.

```kotlin
// streamingEnabled reflects StreamingPreference.enabled; applyStreamingMode(true)
// calls streamingPreference.setEnabled(true). Mirror how HomeViewModel.applyStreamingMode
// is tested (or the delegate's streamingPreference mock). Assert the flow value + setEnabled call.
```

- [ ] **Step 2: Run to verify it fails.** Expected: FAIL.

- [ ] **Step 3: Implement in `SearchViewModel`** (it already injects `streamingPreference`):

```kotlin
    val streamingEnabled: StateFlow<Boolean> =
        streamingPreference.enabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), false)

    fun applyStreamingMode(enabled: Boolean) {
        viewModelScope.launch { streamingPreference.setEnabled(enabled) }
    }
```

- [ ] **Step 4: Render in `SearchScreen` header.** Collect `streamingEnabled`; add a `showStreamingSheet` remember. In the header row next to `SearchBar`, render `StreamingModeChip(streamingEnabled = streamingEnabled, onClick = { showStreamingSheet = true })` (gated on `StashConstants.STREAMING_ENGINE_ENABLED`). When `showStreamingSheet`, render `StreamingModeSheet(streamingEnabled = streamingEnabled, onSelect = { viewModel.applyStreamingMode(it); showStreamingSheet = false }, onDismiss = { showStreamingSheet = false })`. Import from `core.ui.components.streaming`.

- [ ] **Step 5: Run to verify it passes + compile**

Run: `./gradlew :feature:search:testDebugUnitTest --tests "com.stash.feature.search.SearchStreamingModeTest"` → PASS.
Run: `./gradlew :feature:search:compileDebugKotlin` → BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add feature/search/src/main/kotlin/com/stash/feature/search/ \
        feature/search/src/test/kotlin/com/stash/feature/search/SearchStreamingModeTest.kt
git commit -m "feat(search): Online/Offline chip in the Search header (reuses core:ui streaming sheet)"
```

---

## Phase E — Integration & verification

### Task E1: Assemble, sweep, device smoke

- [ ] **Step 1:** `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL (confirms every module + the interface fanout + Hilt graph compile).

- [ ] **Step 2: Targeted test sweep**
```bash
./gradlew :data:download:testDebugUnitTest --tests "com.stash.data.download.lossless.qbdlx.QbdlxBypassRateLimitTest" \
          :core:media:testDebugUnitTest --tests "com.stash.core.media.preview.LosslessUrlPrefetcherTest" \
          :feature:search:testDebugUnitTest --tests "com.stash.feature.search.NowPlayingIdTest" --tests "com.stash.feature.search.SearchStreamingModeTest" --tests "com.stash.feature.search.AlbumDiscoveryViewModel*" --tests "com.stash.feature.search.ArtistProfileViewModelTest"
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Device smoke test** (`./gradlew :app:installDebug`; force-stop for a clean process):
  1. Search a track → **whole row taps to play** (Online: full stream; the ▶ button is gone); download shows ⭳ → spinner → ✓; ⋮ has Play next/Queue/Radio/Playlist.
  2. Now-playing row shows the accent + equalizer while it plays.
  3. Switch to **Offline** via the new Search-header chip; tap a Logic track → **preview starts in ~1–2s** (was ~18s). Confirm logcat: `preview lossless … via qbdlx_qobuz` + `totalDt` now small.
  4. Album page: Qobuz-native album (e.g., MBV Loveless) → rows tap-to-play, **no download icon**; YT album → download icon present.
  5. Artist "Popular" uses the same row.

- [ ] **Step 4: Commit** any smoke-test fixes.

```bash
git add -A && git commit -m "chore(discovery): integration wiring + smoke-test fixes"
```

---

## Notes for the implementer

- **TDD where logic lives** (bypass flag, prefetcher dedup, now-playing matcher, VM mode state); pure-Compose layout is structural (compile + device-verify).
- **Interface fanout (Task A1 Step 5):** all 5 `LosslessSource.resolve` overrides MUST add the `bypassRateLimit: Boolean` param or the module won't compile. Don't repeat the `= false` default on overrides.
- **Preview behavior unchanged, only faster:** offline tap still 30s-previews; the fix is the resolve speed, not what a tap does.
- **`onStopPreview` (pinned):** the whole-row click IS the stop affordance — `if (isPreviewPlaying) onStopPreview() else onPlay()` (Task B1 Step 2). This inherits the old ▶/⏹ button toggle, so a running 30s preview is never orphaned. Keep `onStopPreview` wired at all call sites.
- **Pre-existing test noise** in `:data:ytmusic`/`:feature:search` — always `--tests`-filter; don't gate on the flaky `:core:media` network test.
- **Device streaming pref:** a `pm clear` resets streaming to Offline — flip it back before smoke-testing playback so an offline default isn't mistaken for a bug.
