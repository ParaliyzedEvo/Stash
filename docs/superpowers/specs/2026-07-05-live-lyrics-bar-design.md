# Live-Lyrics Bar — Design

**Date:** 2026-07-05
**Status:** Approved by user (brainstorm session, visual companion mockups)
**Depends on:** lyrics reliability fixes (master `cffb430d`) — failure≠miss classification, working Retry, fetch-stamp repair.

## Goal

While the Now Playing screen is open, the bottom MiniPlayer bar becomes a live
synced-lyrics bar: the currently-sung line, rendered in the album accent color
on an ambient backdrop, replacing the duplicate transport controls (the full
player above already shows play/next). Tapping the bar opens the existing full
lyrics sheet. The top-right lyrics button on Now Playing is removed — the bar
is the lyrics entry point.

On every other screen the MiniPlayer is unchanged.

## Decisions (made with user)

| Decision | Choice |
|---|---|
| Bar layout | Single centered line (mockup option A) |
| Premium treatment | Glowing accent line (mockup P1): whole line in accent + soft glow, on an ambient strip. NOT the karaoke sweep. |
| No synced lyrics, but plain lyrics exist (~15% of lyric hits) | Bar still shows: static dim "View lyrics ♪" on the same ambient strip; tap opens sheet |
| No lyrics at all / instrumental / loading / fetch failed | No bar. It fades in when lyrics arrive. |
| Architecture | Approach C: bar lives inside NowPlayingScreen; scaffold merely hides MiniPlayer on the Now Playing route |

## UI & behavior

### Bar states (driven by the existing `LyricsViewState`)

- **Synced** → live bar. Single centered line = `lines[indexOfLast { timestampMs <= positionMs }]`
  — the exact rule the sheet's `LyricsSyncedRenderer` uses, extracted to one
  shared helper so bar and sheet can never disagree. Before the first
  timestamp `indexOfLast` returns `-1`; the helper coerces to `0` (first line
  shows during the intro — matching the sheet's current behaviour, and
  guaranteeing the index can never go out of bounds).
- **Plain** → static bar: dim-white "View lyrics ♪", same backdrop, same tap target.
- **Loading / None / Instrumental / Error** → no bar rendered. The row is absent
  (nav bar only); when a fetch lands with lyrics the bar animates in.

### Visual treatment

- One bold centered line (~titleMedium/16sp, SemiBold), ellipsized — no marquee.
- Line color = `uiState.vibrantColor` — the exact accent Now Playing passes to
  `GlowingProgressBar` and the queue sheet (NOT `dominantColor`, which is what
  the MiniPlayer's thin progress uses) — with a soft accent glow behind the
  text (same family as the progress bar's playhead glow).
- Backdrop = **AmbientStrip**: near-black base (`#06060C`, matching
  `AmbientBackground.BaseDark`) with two soft radial washes in the palette
  colors (dominant/vibrant/muted), slowly drifting. A slim, bar-height variant
  of the ambient treatment — the Now Playing atmosphere flows through the bar
  instead of stopping at a flat surface.
- Line change: crossfade + slight vertical drift, ~250 ms (matches the sheet's
  highlight glide).
- Track change: accent + ambient colors crossfade over 800 ms (matches
  `AmbientBackground`'s existing `CROSSFADE_MS`).
- The bar sits inside NowPlayingScreen, so it inherits the screen's existing
  slide-up/slide-down navigation transitions for free.

### Interactions

- Tap anywhere on the bar → opens the existing `LyricsBottomSheet`
  (auto-scroll, tap-to-seek, Retry — all unchanged inside the sheet).
- **`onShowLyrics()` loses its fetch side-effect**: it only opens the sheet.
  Today the streaming branch (`track.id == 0L`) re-fetches unconditionally on
  every sheet open, synchronously resetting the shared state to Loading — with
  the bar consuming that same state, a bar tap would hide the bar mid-tap,
  and a transient failure on the redundant re-fetch would kill a working bar.
  Fetching becomes the track-change trigger's job exclusively (plus the
  sheet's Retry, which keeps `force` semantics).
- No other gestures. No transport controls in the bar.

### Fetch trigger moves to screen-open

Today the lyrics fetch fires when the sheet opens. With the button gone, the
bar is the only entry point — but the bar needs lyrics to exist before it
shows (chicken-and-egg). Therefore: **opening Now Playing, or the track
changing while it is open, triggers the lyrics fetch** — for library tracks
only when they have never had an attempt (`lyricsFetchedAt == null`), for
streaming tracks whenever the transient state is unpopulated — through the
same `fetchLibraryLyrics` / `fetchStreamingLyrics` paths the sheet-open uses
today.

**The trigger MUST be subscription-gated, not VM-lifetime-gated.** Two
instances of `NowPlayingViewModel` exist (see Architecture): the MiniPlayer's
activity-scoped instance is alive for the whole session, so a trigger in
`init{}` / an unconditionally-launched collector would fire fetches on every
track change app-wide even with Now Playing closed. Instead, the trigger
lives inside the `lyricsViewState` flow chain (fires on the subscribed flow's
initial emission — covering screen-open — and on each track flip). That flow
is `SharingStarted.WhileSubscribed`, and its only collectors are the bar
(route-scoped, inside NowPlayingScreen) and the sheet; the MiniPlayer never
collects it, so its always-alive instance stays quiet.

Concretely: NowPlayingScreen collects `lyricsViewState` **unconditionally at
top level** (today the collect sits inside the sheet's `if (showLyrics)`
block — it moves out), and the bar renders conditionally on the state. That
unconditional collect is what keeps the trigger alive for screen-open. Since
`stateIn` shares one upstream, the sheet subscribing on top of the bar adds
no second trigger firing.

With `onShowLyrics` no longer fetching, the subscription-gated trigger and
Retry are the only fetch entry points, so `fetchStreamingLyrics`'s lack of
in-flight dedupe (unlike `fetchLibraryLyrics`) cannot double-fire from the
trigger/tap pair. (A Retry tap racing an auto-advance can still launch two
transient resolves for the same track — benign and idempotent, last write
wins, pre-existing; do not treat "single-flight" as a hard invariant.)

Deliberate simplification: a failed fetch leaves the bar hidden — no error
chip. The reliability work already guarantees failures never stamp a false
miss and re-fetch on the next open/track change, and the sheet's Retry stays
reachable whenever the bar shows.

## Architecture (Approach C)

The MiniPlayer (scaffold chrome) and NowPlayingScreen hold **different
instances** of `NowPlayingViewModel` (activity- vs route-scoped). Playback
data flows through singleton repositories so both see the same state, but
UI-command state (sheet open/close) does not cross instances. Approach C
avoids the problem entirely by keeping everything on the route-scoped
instance that already owns the sheet:

| Component | Change |
|---|---|
| `LiveLyricsBar.kt` (new, `:feature:nowplaying/ui`) | Stateless composable: `(LyricsViewState, currentPositionMs, dominant/vibrant/muted colors, onTap)` → live line / static / nothing. Contains the `AmbientStrip` backdrop. |
| Current-line helper (new, small) | `indexOfLast { timestampMs <= positionMs }` extraction, shared by `LyricsSyncedRenderer` and the bar. |
| `NowPlayingScreen` | Pin bar at the screen's bottom edge, outside the scroll area (content `weight(1f)` + bar). Remove the top-right lyrics `IconButton`. Sheet wiring untouched. |
| `NowPlayingViewModel` | Fetch trigger on screen-open + track change, **inside the `lyricsViewState` chain** (subscription-gated — see fetch section; NOT `init{}`, or the MiniPlayer's activity-scoped instance fetches app-wide). Library: only when `lyricsFetchedAt == null`; streaming: when transient state unpopulated. Remove the fetch calls from `onShowLyrics` — it only opens the sheet. |
| `StashScaffold` (`:app`) | One conditional: skip rendering `MiniPlayer` when `currentRoute == NowPlayingRoute::class.qualifiedName` (same comparison pattern `StashBottomBar` already uses). Nav bar unaffected. |

**Why not a full-screen `AmbientBackground` reuse:** it draws hard-edged
circles sized by `min(w, h)` — at bar height that produces small solid dots,
not soft washes. A ~40-line `AmbientStrip` with `Brush.radialGradient` washes
is cleaner than parameterizing the existing composable.

## Data flow

No new plumbing — the bar is a new consumer of feeds that already exist on
the route-scoped VM:

```
uiState.currentTrack ──► lyricsViewState (existing Room row + stamp combine) ──┐
playerRepository.currentPosition (existing 250 ms tick) ──────────────────────┼──► LiveLyricsBar
uiState.dominant/vibrant/mutedColor (existing palette extraction) ────────────┘
        tap ──► onShowLyrics() (existing)
```

## Error handling

Inherited wholesale from the reliability work (`cffb430d`): sources throw on
transport failure instead of faking a miss, fetches dedupe per track,
`CancellationException` is rethrown, failures leave the stamp NULL
(retryable). The bar introduces no new error surface.

## Testing

- **Unit — current-line helper:** position before first timestamp, between
  lines, exactly on a timestamp, after the last line, empty list.
- **Unit — bar-mode mapping:** `Synced` → live, `Plain` → static, `Loading` /
  `None` / `Instrumental` / `Error` → hidden. Follows the existing
  `LyricsViewStateTest` pattern.
- **Device verification:** line advances in sync with playback; accent matches
  the progress bar; tap opens the sheet; **tapping the bar on a
  streaming-mode track must not flicker/hide the bar** (guards the
  `onShowLyrics` fetch-removal); plain-only track shows the static bar;
  lyric-less track shows no bar; bar fades in when a fresh fetch lands;
  MiniPlayer behaves normally on all other screens; queue/save sheets overlay
  correctly.

## Out of scope (explicitly)

- **Word-level karaoke highlighting** — LRC data is line-timed; true word
  timing would need a new lyrics source (e.g. enhanced-LRC/Musixmatch).
  Future note, separate feature.
- **More synced-lyrics sources** for the ~15% plain-only tracks — separate
  feature.
- **Error chip in the bar** — deliberate simplification, see fetch section.
- **Marquee for long lines** — ellipsis only.
- **Bar on routes other than Now Playing** — Approach C confines it; promote
  to scaffold chrome later if ever wanted.
- **Lockscreen / notification lyrics** — untouched.
