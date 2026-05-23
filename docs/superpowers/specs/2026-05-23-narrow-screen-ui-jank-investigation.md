# Narrow-screen UI jank investigation (Issue #87)

**Date:** 2026-05-23
**Status:** Pre-spec — diagnosis only, fix not yet shipped
**Issue:** https://github.com/rawnaldclark/Stash/issues/87
**Reporter:** @krishnadixitops on Nothing Phone 3a / Android 16
**Screenshot:** the OP image on the issue (also saved locally at `C:\Users\theno\Downloads\New folder (7)\595437130-7b706654-a593-499f-b02f-bff36934d2fc.png` at investigation time)

## Symptoms visible in the screenshot

1. **"NOW PLAYING" header text wraps to one capital letter per vertical line.** Title renders as `N\nO\nW\nP\nL\nA\nY\nI\nN\nG` — character-per-line softWrap.
2. **Bottom mini-player + bottom nav stack tight against the system gesture bar** with no apparent navigationBars inset padding.
3. **Playback slider thumb (red dot) renders free-floating** near the center-bottom of the album art region, decoupled from the slider track.

Most users (incl. Pixel 6 Pro running Android 14/15) don't see any of this. Issue is specific to a combination of narrow viewport + likely-elevated fontScale + Android 16 edge-to-edge enforcement.

## Bug 1 — Toolbar overflows the row, title squeezes to ~one character wide

### Root cause (confirmed)

`feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingScreen.kt:381-440` — `TopBar` composable is a `Row` with **6 icons + the title**:

- IconButton: Dismiss (down arrow) — line 387
- Text: "NOW PLAYING" with `Modifier.weight(1f)` — line 396
- IconButton: Flag (gated on `hasTrack`) — line 409
- LikeButton (gated on `hasTrack`) — line 424
- IconButton: Download/DownloadDone toggle (gated on `hasTrack`) — line 437
- (continues past line 440) Save + Queue

Each IconButton has Material3's 48dp minimum touch target. 6 × 48dp = 288dp consumed before the title gets any space. On a ~393dp-wide phone (Nothing Phone 3a estimate) the title's `weight(1f)` gets only ~105dp. At default fontScale "NOW PLAYING" in `labelMedium` (14sp) fits in ~100dp. At fontScale ≥ 1.1× — common Android accessibility default — the text overflows, and **because there's no `maxLines = 1` or `overflow = TextOverflow.Ellipsis`** Compose softWraps it character-by-character.

### Recommended fix (combo)

1. **Move Flag + Save into a 3-dot overflow menu** (Material conventional pattern). Drops icon count from 6 to 4 → frees ~96dp for the title.
2. **Add `maxLines = 1, overflow = TextOverflow.Ellipsis`** to the title as a belt-and-suspenders guard against future fontScale / screen-width combinations.

Alternative simpler fixes considered: `FlowRow` (awkward), shrink IconButton (a11y regression), Text-only ellipsis (still ugly on narrow screens).

## Bug 2 — Mini-player + bottom nav lack navigationBars inset

### Hypothesis (needs verification)

Android 15+ enforces edge-to-edge by default; apps must apply `WindowInsets.navigationBars` padding explicitly. Stash's bottom Scaffold likely passes `contentWindowInsets = WindowInsets(0)` or otherwise overrides default insets, leaving the bottom bar to draw flush with the gesture bar.

### Investigation steps for the fix session

1. Read `app/src/main/kotlin/com/stash/app/StashScaffold.kt` (or wherever the root Scaffold lives) — check if `contentWindowInsets` is overridden.
2. Check `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/MiniPlayer.kt` (or equivalent) for inset handling on the mini-player.
3. Look at how the existing Pixel 6 Pro behavior renders — gesture bar padding might already work there because of API differences.

### Recommended fix

Add `Modifier.navigationBarsPadding()` to the bottom bar surface (`StashScaffold` bottom slot, or the mini-player container). Reproduce on an emulator with `gesture navigation` enabled before committing.

## Bug 3 — Slider thumb free-floating

### Status

Unknown root cause. Slider code not yet read. Possibilities:

- Hardcoded thumb pixel position
- Slider not constrained to the right vertical region (Z-index / layout issue)
- Density/screen-size mismatch in a custom Slider implementation

### Investigation steps

1. Read the slider code in `NowPlayingScreen.kt` (grep for `Slider`, `progressFraction`, `seekTo`).
2. Compare against Material3's stock Slider — if Stash has a custom one, look for hardcoded sizes.

## Verification strategy

The Pixel 6 Pro dev environment WILL NOT reproduce the bug. Verification needs:

1. **Emulator config:** screen width ≤ 393dp, density 440 dpi, gesture navigation enabled, fontScale 1.15× or higher.
2. **Specifically:** `adb shell settings put system font_scale 1.30` to force the issue.
3. **Reproduce the screenshot:** open NowPlaying with a Liked Songs track playing, take screenshot, compare visually to the user's image.
4. **After fix:** confirm "NOW PLAYING" renders horizontally on a single line, mini-player + nav have visible gap from gesture bar, slider thumb tracks the progress correctly.

## Brainstorming open questions for next session

1. **Should we audit other screens** for similar overflow risk, or just fix NowPlaying? (Recommend audit — same pattern likely repeats.)
2. **Is the toolbar overflow menu material-standard or custom?** (Use standard `DropdownMenu`.)
3. **Edge-to-edge migration scope** — fixing only the mini-player vs. doing a proper insets pass across the app. (Lean toward narrow fix first; broader pass as separate work.)
4. **Add fontScale 1.3× as a CI screenshot test?** Would have caught this. Out of scope for this fix; worth a note for later.

## Session handoff notes

The fix is at least three bugs, each potentially requiring its own commit. The combo for Bug 1 alone is ~30 lines across one file. Bug 2 likely touches the root Scaffold. Bug 3 needs more investigation. Recommend brainstorming session → spec → plan → implement → verify on configured emulator before declaring done.
