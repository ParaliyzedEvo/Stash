# Live-Lyrics Bar Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** While Now Playing is open, replace the bottom MiniPlayer with a live synced-lyrics bar (current line in the album accent on the ambient backdrop); tap opens the existing lyrics sheet.

**Architecture:** Approach C from the spec (`docs/superpowers/specs/2026-07-05-live-lyrics-bar-design.md`): the bar lives inside `NowPlayingScreen` on the route-scoped `NowPlayingViewModel`; `StashScaffold` merely stops rendering MiniPlayer on the Now Playing route. The lyrics fetch trigger moves from sheet-open to a **subscription-gated** collector inside the `lyricsViewState` chain (see spec — this MUST NOT live in `init{}`, or the MiniPlayer's always-alive activity-scoped VM instance would fetch app-wide).

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Hilt, Room, JUnit4. Modules touched: `:feature:nowplaying`, `:app`.

**One documented deviation from the spec's component table:** the spec sketches an `AmbientStrip` composable with its own radial washes. In Approach C the bar sits inside `NowPlayingScreen`'s root `Box`, where the real full-screen `AmbientBackground` already animates **behind the bar area**. Painting an opaque mini-ambience over the genuine one would be strictly worse. The bar therefore uses a translucent near-black scrim (vertical gradient for text contrast) and lets the actual atmosphere show through — satisfying the spec's requirement ("the Now Playing atmosphere flows through the bar") with less code and zero extra animations. If device verification finds the strip lacks definition, add the washes then.

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/ui/LyricsView.kt` | Modify | Gains the shared `currentLineIndex()` helper; `LyricsSyncedRenderer` rewired to use it |
| `feature/nowplaying/src/test/kotlin/com/stash/feature/nowplaying/ui/CurrentLineIndexTest.kt` | Create | Unit tests for the helper |
| `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/ui/LiveLyricsBar.kt` | Create | `LiveBarMode` mapping + the bar composable |
| `feature/nowplaying/src/test/kotlin/com/stash/feature/nowplaying/ui/LiveBarModeTest.kt` | Create | Unit tests for state→mode mapping |
| `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingViewModel.kt` | Modify | Subscription-gated fetch trigger; `onShowLyrics` loses fetch side-effect; live-stamp guard in `fetchLibraryLyrics` |
| `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingScreen.kt` | Modify | Unconditional lyrics collect; bar pinned at bottom; top-right lyrics button removed |
| `app/src/main/kotlin/com/stash/app/navigation/StashScaffold.kt` | Modify | Hide MiniPlayer on the Now Playing route |

Work on a feature branch: `git checkout -b feat/live-lyrics-bar` (master has unreleased commits; keep this isolated the same way).

---

### Task 1: Shared current-line helper

The rule `lines.indexOfLast { it.timestampMs <= positionMs }.coerceAtLeast(0)` currently lives inline in `LyricsSyncedRenderer` (`LyricsView.kt:78-80`). Extract it so bar and sheet can never disagree, with tests.

**Files:**
- Test: `feature/nowplaying/src/test/kotlin/com/stash/feature/nowplaying/ui/CurrentLineIndexTest.kt` (create)
- Modify: `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/ui/LyricsView.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.stash.feature.nowplaying.ui

import com.stash.data.lyrics.parser.LrcLine
import org.junit.Assert.assertEquals
import org.junit.Test

class CurrentLineIndexTest {

    private val lines = listOf(
        LrcLine(timestampMs = 10_000L, text = "line one"),
        LrcLine(timestampMs = 20_000L, text = "line two"),
        LrcLine(timestampMs = 30_000L, text = "line three"),
    )

    @Test fun `before first timestamp coerces to 0`() {
        assertEquals(0, currentLineIndex(lines, positionMs = 0L))
        assertEquals(0, currentLineIndex(lines, positionMs = 9_999L))
    }

    @Test fun `exactly on a timestamp selects that line`() {
        assertEquals(0, currentLineIndex(lines, positionMs = 10_000L))
        assertEquals(1, currentLineIndex(lines, positionMs = 20_000L))
    }

    @Test fun `between lines selects the earlier line`() {
        assertEquals(1, currentLineIndex(lines, positionMs = 25_000L))
    }

    @Test fun `after last timestamp selects the last line`() {
        assertEquals(2, currentLineIndex(lines, positionMs = 99_000L))
    }

    @Test fun `empty list returns 0 - callers must getOrNull`() {
        assertEquals(0, currentLineIndex(emptyList(), positionMs = 5_000L))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :feature:nowplaying:testDebugUnitTest --tests "com.stash.feature.nowplaying.ui.CurrentLineIndexTest"`
Expected: compilation FAILS — `currentLineIndex` unresolved.

- [ ] **Step 3: Add the helper and rewire the renderer**

In `LyricsView.kt`, add near the file's other top-level declarations (above `USER_SCROLL_GRACE_MS`):

```kotlin
/**
 * Index of the lyric line being sung at [positionMs]: the latest line whose
 * timestamp is `<= positionMs`. Before the first timestamp (intro), coerces
 * `-1` to `0` so the first line shows early and the index can never go out
 * of bounds. Single source of truth shared by [LyricsSyncedRenderer] and
 * [LiveLyricsBar] — the bar and the sheet must never disagree on the line.
 */
internal fun currentLineIndex(lines: List<LrcLine>, positionMs: Long): Int =
    lines.indexOfLast { it.timestampMs <= positionMs }.coerceAtLeast(0)
```

In `LyricsSyncedRenderer`, replace the body of the existing `currentIndex` computation (keep the explanatory BUG FIX comment above it):

```kotlin
    val currentIndex = remember(lines, currentPositionMs) {
        currentLineIndex(lines, currentPositionMs)
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :feature:nowplaying:testDebugUnitTest --tests "com.stash.feature.nowplaying.ui.CurrentLineIndexTest"`
Expected: 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add feature/nowplaying
git commit -m "refactor(lyrics): extract shared currentLineIndex helper"
```

---

### Task 2: Bar-mode mapping

Pure function `LyricsViewState → LiveBarMode` (Live / Static / Hidden), per the spec's state table. Lives in the new `LiveLyricsBar.kt`; the composable itself comes in Task 3.

**Files:**
- Test: `feature/nowplaying/src/test/kotlin/com/stash/feature/nowplaying/ui/LiveBarModeTest.kt` (create)
- Create: `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/ui/LiveLyricsBar.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.stash.feature.nowplaying.ui

import com.stash.data.lyrics.parser.LrcLine
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveBarModeTest {

    private val lines = listOf(LrcLine(timestampMs = 1_000L, text = "hi"))

    @Test fun `synced maps to Live carrying the lines`() {
        val mode = liveBarModeFor(LyricsViewState.Synced(lines, plainFallback = "hi"))
        assertEquals(LiveBarMode.Live(lines), mode)
    }

    @Test fun `plain maps to Static`() {
        assertEquals(LiveBarMode.Static, liveBarModeFor(LyricsViewState.Plain("text")))
    }

    @Test fun `everything else maps to Hidden`() {
        assertEquals(LiveBarMode.Hidden, liveBarModeFor(LyricsViewState.Loading))
        assertEquals(LiveBarMode.Hidden, liveBarModeFor(LyricsViewState.None))
        assertEquals(LiveBarMode.Hidden, liveBarModeFor(LyricsViewState.Instrumental))
        assertEquals(LiveBarMode.Hidden, liveBarModeFor(LyricsViewState.Error(retryable = true)))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :feature:nowplaying:testDebugUnitTest --tests "com.stash.feature.nowplaying.ui.LiveBarModeTest"`
Expected: compilation FAILS — `LiveBarMode` / `liveBarModeFor` unresolved.

- [ ] **Step 3: Create `LiveLyricsBar.kt` with just the mapping**

```kotlin
package com.stash.feature.nowplaying.ui

import com.stash.data.lyrics.parser.LrcLine

/**
 * What the live-lyrics bar renders for a given [LyricsViewState]:
 *  - [Live]   synced lyrics — the current line, accent-colored, advancing
 *             with playback.
 *  - [Static] plain-only lyrics — a dim "View lyrics ♪" tap target (the
 *             ~15% of lyric hits with no timing data).
 *  - [Hidden] nothing to offer (no lyrics / instrumental / loading / error)
 *             — the bar row is absent entirely; it animates in when a fetch
 *             lands with lyrics.
 */
internal sealed interface LiveBarMode {
    data class Live(val lines: List<LrcLine>) : LiveBarMode
    data object Static : LiveBarMode
    data object Hidden : LiveBarMode
}

internal fun liveBarModeFor(state: LyricsViewState): LiveBarMode = when (state) {
    is LyricsViewState.Synced -> LiveBarMode.Live(state.lines)
    is LyricsViewState.Plain -> LiveBarMode.Static
    else -> LiveBarMode.Hidden
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :feature:nowplaying:testDebugUnitTest --tests "com.stash.feature.nowplaying.ui.LiveBarModeTest"`
Expected: 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add feature/nowplaying
git commit -m "feat(lyrics): LiveBarMode mapping for the live-lyrics bar"
```

---

### Task 3: The LiveLyricsBar composable

Visual component — no unit test (module has no Compose UI test infra; device-verified in Task 7).

**Files:**
- Modify: `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/ui/LiveLyricsBar.kt`

- [ ] **Step 1: Append the composable to `LiveLyricsBar.kt`**

Add these imports at the top of the file:

```kotlin
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
```

Then the composable:

```kotlin
/** Near-black scrim base — matches `AmbientBackground.BaseDark`. */
private val BarBase = Color(0xFF06060C)

/**
 * Live synced-lyrics bar pinned at the bottom of Now Playing, where the
 * MiniPlayer sits on every other screen. Renders per [LiveBarMode]:
 * the currently-sung line in the album accent (Live), a dim "View lyrics ♪"
 * (Static), or nothing (Hidden). Tapping opens the full lyrics sheet.
 *
 * The backdrop is a translucent scrim — the screen's full-bleed
 * [AmbientBackground] animates beneath, so the Now Playing atmosphere flows
 * through the bar; the vertical gradient only guarantees text contrast.
 *
 * @param accentColor `uiState.vibrantColor` — the same accent
 *        [GlowingProgressBar] uses, NOT `dominantColor`.
 */
@Composable
fun LiveLyricsBar(
    state: LyricsViewState,
    currentPositionMs: Long,
    accentColor: Color,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mode = liveBarModeFor(state)
    AnimatedVisibility(
        visible = mode != LiveBarMode.Hidden,
        enter = fadeIn(tween(400)) + expandVertically(tween(400)),
        exit = fadeOut(tween(250)) + shrinkVertically(tween(250)),
        modifier = modifier,
    ) {
        // 800ms accent crossfade on track change — matches AmbientBackground's
        // CROSSFADE_MS so the whole surface recolors as one.
        val animAccent by animateColorAsState(
            targetValue = accentColor,
            animationSpec = tween(800),
            label = "liveBarAccent",
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onTap)
                .background(
                    Brush.verticalGradient(
                        0f to BarBase.copy(alpha = 0.30f),
                        1f to BarBase.copy(alpha = 0.65f),
                    ),
                )
                .padding(horizontal = 20.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (mode) {
                is LiveBarMode.Live -> {
                    val index = remember(mode.lines, currentPositionMs) {
                        currentLineIndex(mode.lines, currentPositionMs)
                    }
                    val line = mode.lines.getOrNull(index)?.text.orEmpty()
                    AnimatedContent(
                        targetState = line,
                        transitionSpec = {
                            (fadeIn(tween(250)) + slideInVertically(tween(250)) { it / 3 })
                                .togetherWith(
                                    fadeOut(tween(250)) + slideOutVertically(tween(250)) { -it / 3 },
                                )
                        },
                        label = "liveBarLine",
                    ) { text ->
                        Text(
                            // LRC bodies often carry empty-text lines for
                            // instrumental breaks — show a quiet note glyph
                            // rather than a blank bar.
                            text = text.ifBlank { "♪" },
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                shadow = Shadow(
                                    color = animAccent.copy(alpha = 0.55f),
                                    blurRadius = 18f,
                                ),
                            ),
                            color = animAccent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                LiveBarMode.Static -> Text(
                    text = "View lyrics ♪",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White.copy(alpha = 0.45f),
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                LiveBarMode.Hidden -> Unit
            }
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :feature:nowplaying:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add feature/nowplaying
git commit -m "feat(lyrics): LiveLyricsBar composable — accent line on ambient scrim"
```

---

### Task 4: ViewModel — subscription-gated fetch trigger, slim onShowLyrics

**Files:**
- Modify: `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingViewModel.kt`

Three changes. **Read the spec's "Fetch trigger moves to screen-open" section first** — the gating rationale matters: the trigger goes inside the `lyricsViewState` chain because that flow is `SharingStarted.WhileSubscribed` and only NowPlayingScreen collects it; putting the trigger in `init{}` would make the MiniPlayer's always-alive activity-scoped VM instance fetch lyrics on every track change app-wide.

- [ ] **Step 1: Extend the `onEach` in the `lyricsViewState` chain**

Find (in the `lyricsViewState` declaration, ~line 138):

```kotlin
        .onEach {
            // reset transient state on track change
            _streamingLyricsState.value = null
            _lyricsFetchFailedTrackId.value = null
        }
```

Replace with:

```kotlin
        .onEach { track ->
            // reset transient state on track change
            _streamingLyricsState.value = null
            _lyricsFetchFailedTrackId.value = null
            // Live-lyrics-bar fetch trigger. Deliberately HERE and not in
            // init{}: this onEach only runs while lyricsViewState has
            // collectors (WhileSubscribed) — i.e. while NowPlayingScreen is
            // showing the bar/sheet. The MiniPlayer's activity-scoped VM
            // instance never collects lyricsViewState, so it can never fire
            // fetches app-wide. Covers screen-open (initial emission) and
            // every track change while open.
            when {
                track == null -> Unit
                track.id > 0L && track.lyricsFetchedAt == null -> fetchLibraryLyrics(track)
                track.id == 0L -> fetchStreamingLyrics(track)
            }
        }
```

- [ ] **Step 2: Remove the fetch side-effect from `onShowLyrics`**

Find the current `onShowLyrics` (~line 615, with its `when` block dispatching to `fetchLibraryLyrics`/`fetchStreamingLyrics`) and replace the whole function including its KDoc:

```kotlin
    /**
     * Open the lyrics sheet for the currently-playing track. Open-only since
     * the live-lyrics bar: fetching is the lyricsViewState chain's
     * subscription-gated trigger's job (plus the sheet's Retry). The old
     * unconditional streaming re-fetch here reset shared state to Loading —
     * with the bar consuming that same state, a bar tap would have hidden
     * the bar mid-tap.
     */
    fun onShowLyrics() {
        _lyricsSheetOpen.value = true
    }
```

- [ ] **Step 3: Guard `fetchLibraryLyrics` against stale track snapshots**

`uiState.currentTrack` is a queue-time snapshot — its `lyricsFetchedAt` can read NULL after a fetch already stamped Room (e.g. skip back to a previous track). Add a live-stamp re-check inside the coroutine. In `fetchLibraryLyrics`, right after the `try {`:

```kotlin
            try {
                // Snapshot stamps go stale (queue Tracks don't refresh after a
                // fetch stamps Room) — re-check the LIVE stamp so a revisited
                // track doesn't re-hit the network. Retry (force) skips the
                // guard deliberately.
                if (!force && lyricsRepository.observeFetchedAt(track.id).first() != null) {
                    return@launch
                }
```

Add the import: `import kotlinx.coroutines.flow.first`

- [ ] **Step 4: Compile and run the module's tests**

Run: `./gradlew :feature:nowplaying:compileDebugKotlin :feature:nowplaying:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all existing tests (incl. `LyricsViewStateTest`) still PASS.

- [ ] **Step 5: Commit**

```bash
git add feature/nowplaying
git commit -m "feat(lyrics): subscription-gated lyrics fetch on track change; onShowLyrics opens sheet only"
```

---

### Task 5: NowPlayingScreen — pin the bar, remove the button

**Files:**
- Modify: `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingScreen.kt`

- [ ] **Step 1: Hoist the lyrics collects to top level (unconditional)**

Find (~line 137):

```kotlin
    val showLyrics by viewModel.lyricsSheetOpen.collectAsStateWithLifecycle()
    if (showLyrics) {
        val lyricsState by viewModel.lyricsViewState.collectAsStateWithLifecycle()
        val lyricsPositionMs by viewModel.currentPositionMs.collectAsStateWithLifecycle()
        LyricsBottomSheet(
```

Replace with (collects move OUT of the `if` — this unconditional subscription is what keeps the fetch trigger alive; see spec):

```kotlin
    val showLyrics by viewModel.lyricsSheetOpen.collectAsStateWithLifecycle()
    // Collected unconditionally (not just while the sheet is open): the bar
    // needs the state, and this subscription is what arms the ViewModel's
    // WhileSubscribed fetch trigger from screen-open onward. The screen
    // already recomposes every 250ms from uiState position ticks, so the
    // extra position collect adds no new recomposition pressure.
    val lyricsState by viewModel.lyricsViewState.collectAsStateWithLifecycle()
    val lyricsPositionMs by viewModel.currentPositionMs.collectAsStateWithLifecycle()
    if (showLyrics) {
        LyricsBottomSheet(
```

- [ ] **Step 2: Restructure the content column and pin the bar**

Find (~line 252):

```kotlin
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
```

Replace with (outer column splits the screen into scrollable content + pinned bar; children of the inner column are untouched):

```kotlin
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .statusBarsPadding()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
```

Then find the end of that content column (the `Spacer(modifier = Modifier.height(48.dp))` followed by its closing `}` , ~line 380-381) and close both columns with the bar between them:

```kotlin
                Spacer(modifier = Modifier.height(48.dp))
            }

            // Live-lyrics bar — sits exactly where the MiniPlayer is on other
            // screens (the scaffold hides MiniPlayer on this route), directly
            // above the nav bar. Zero-height when Hidden, so the content
            // column keeps the full screen for lyric-less tracks.
            LiveLyricsBar(
                state = lyricsState,
                currentPositionMs = lyricsPositionMs,
                accentColor = uiState.vibrantColor,
                onTap = viewModel::onShowLyrics,
            )
        }
```

Re-indent the existing children of the inner column one level (Kotlin doesn't care, but the file should read consistently).

Add the import: `import com.stash.feature.nowplaying.ui.LiveLyricsBar`

- [ ] **Step 3: Remove the top-right lyrics button**

1. In the `TopBar(...)` call site (~line 265): delete the line `onLyricsClick = viewModel::onShowLyrics,`
2. In `private fun TopBar(...)` (~line 404): delete the parameter `onLyricsClick: () -> Unit,`
3. Delete the lyrics `IconButton` block (~lines 485-499, the one with `Icons.Outlined.Lyrics` and its "v0.9.36 Task 13" comment).
4. Remove the now-unused import `androidx.compose.material.icons.outlined.Lyrics` (line 35).
5. Update the `TopBar` call-site comment (~line 260) from `flag, like, download, save, lyrics, queue` to `flag, like, download, save, queue`.

- [ ] **Step 4: Compile**

Run: `./gradlew :feature:nowplaying:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. If it fails on a leftover `onLyricsClick` or `Lyrics` import reference, fix the reference the error names.

- [ ] **Step 5: Commit**

```bash
git add feature/nowplaying
git commit -m "feat(nowplaying): pin LiveLyricsBar at screen bottom; drop top-right lyrics button"
```

---

### Task 6: StashScaffold — hide MiniPlayer on Now Playing

**Files:**
- Modify: `app/src/main/kotlin/com/stash/app/navigation/StashScaffold.kt`

- [ ] **Step 1: Wrap the MiniPlayer call in a route check**

Find (~line 107):

```kotlin
                Column(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
                    MiniPlayer(
                        onExpand = {
                            navController.navigate(NowPlayingRoute) {
                                launchSingleTop = true
                            }
                        },
                    )
```

Replace with (same qualified-name comparison `StashBottomBar` uses; fade so the swap doesn't pop during the screen's slide transition):

```kotlin
                Column(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
                    // On Now Playing the LiveLyricsBar (rendered inside the
                    // screen itself) takes the MiniPlayer's spot — the full
                    // player already shows all transport controls, so the
                    // duplicate mini transport hides on this route only.
                    androidx.compose.animation.AnimatedVisibility(
                        visible = currentRoute != NowPlayingRoute::class.qualifiedName,
                        enter = androidx.compose.animation.fadeIn(),
                        exit = androidx.compose.animation.fadeOut(),
                    ) {
                        MiniPlayer(
                            onExpand = {
                                navController.navigate(NowPlayingRoute) {
                                    launchSingleTop = true
                                }
                            },
                        )
                    }
```

Close the new `AnimatedVisibility` block: the original `MiniPlayer(...)` call's closing `)` is followed by a blank line and `StashBottomBar(` — add the extra closing `}` for the AnimatedVisibility lambda after the `MiniPlayer(...)` call.

- [ ] **Step 2: Compile the app module**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app
git commit -m "feat(nowplaying): hide MiniPlayer while Now Playing is open"
```

---

### Task 7: Full build, install, device verification

- [ ] **Step 1: Run the full lyrics-adjacent test surface**

Run: `./gradlew :feature:nowplaying:testDebugUnitTest :data:lyrics:testDebugUnitTest`
Expected: all PASS.

- [ ] **Step 2: Install on device**

Run: `./gradlew :app:installDebug`
Expected: `Installed on … device(s).` (Wireless ADB port rotates when the phone sleeps — ask the user for a fresh `192.168.137.88:<port>` if the device is offline. Device is the user's DAILY phone: check the foreground app before every interaction and stop if another app is in use.)

- [ ] **Step 3: Device verification checklist (from the spec)**

Guide the user through (or drive via adb where possible):

- [ ] Synced track: bar shows the current line in the accent color; line advances in sync; accent visually matches the progress bar.
- [ ] Tap the bar → full lyrics sheet opens (auto-scroll + tap-to-seek intact).
- [ ] **Streaming-mode track: tapping the bar does NOT flicker/hide the bar** (guards the onShowLyrics fetch-removal).
- [ ] Plain-only track: static "View lyrics ♪" bar; tap opens sheet showing plain text.
- [ ] Lyric-less/instrumental track: no bar at all.
- [ ] Fresh track (never fetched): bar absent, then fades in when the fetch lands — without opening the sheet.
- [ ] Track change while open: line + accent + ambient crossfade smoothly (~800ms).
- [ ] Every other screen: MiniPlayer behaves exactly as before; open/close Now Playing swaps MiniPlayer ↔ bar without layout jumps.
- [ ] The bar sits cleanly above the still-visible bottom nav bar — no overlap, no gap (only MiniPlayer is hidden on this route; StashBottomBar remains).
- [ ] Quick close+reopen of Now Playing on a streaming track: brief Loading reset is expected (transient re-resolve, no cache) — verify it settles back to the live line within ~a second.
- [ ] Rapid skip across several STREAMING tracks with Now Playing open: the bar must end on the FINAL track's lyrics (guards the trackKey'd result write in fetchStreamingLyrics).
- [ ] Queue and Save sheets still overlay correctly.
- [ ] Background fetch check via logcat: with Now Playing CLOSED, change tracks from the MiniPlayer and confirm NO lyrics fetch fires (no `LyricsRepository` source-failure logs, no lrclib traffic) — validates the subscription gating.

- [ ] **Step 4: Merge (after user confirms)**

```bash
git checkout master && git merge --no-ff feat/live-lyrics-bar -m "Merge: live-lyrics bar (replaces MiniPlayer on Now Playing)"
```

Do NOT tag/release — "ship" means public release and needs explicit user say-so.
