# Multi-Select Tracks — Design

**Status:** Approved
**Date:** 2026-05-31
**Author:** Brainstorm session (Claude + user)

---

## Problem

Users can only act on one track at a time. Every batch operation — adding several
songs to a playlist, queueing a handful, deleting a group from a playlist or Liked
Songs, downloading a set — forces the user to repeat the single-track long-press →
options-sheet flow once per track. This is the top feature request from the Discord
community.

We want a multi-selection mode that lets a user select many tracks across the app's
track-list screens and apply a single batch action (add to queue, add to playlist,
download/remove download, delete) to the whole selection.

This is the first of five prioritized requests (multi-select → crossfader → sleep
timer → share → delete custom EQ). Each gets its own spec/plan/implementation cycle;
this document covers **multi-select only**.

## Goals

- Long-press a track to enter a selection mode with that track pre-selected.
- Toggle additional tracks by tapping; "Select all" / clear; live "N selected" count.
- Apply batch actions to the whole selection from a single action bar.
- Work consistently across all five track-list surfaces.
- Reuse existing single-track action logic rather than re-implementing it.
- Feel premium: smooth enter/exit transitions, clean coexistence with the mini-player.

## Non-Goals

- Drag-to-reorder while selecting (out of scope; queue reorder already exists separately).
- Batch share (Share is a later, separate feature; single-track share comes then).
- Cross-screen selection persistence (selection is scoped to the current screen).
- Selecting non-track entities (albums, artists, playlists as items).

## Interaction Model

- **Enter selection mode:** long-press any track row → mode activates with that track
  checked. The previous long-press behavior (the `TrackOptionsSheet`) moves to the
  per-track **⋮** overflow button, which `TrackListItem` already supports via
  `onMoreClick`.
- **While active:**
  - Tapping a row toggles its checkbox (no playback) instead of playing the track.
  - Each row shows a **leading circular checkbox** that animates in; album art remains
    visible. The now-playing indicator still renders.
- **Exit selection mode:** the **✕** in the contextual top bar, the system **Back**
  button, or deselecting the last remaining track. Back always exits selection mode
  *before* navigating away from the screen.

## UI / Layout

Chosen layout: **top contextual header + bottom action bar.**

- **Top bar transforms** into a contextual app bar: `[✕]  "N selected"  [Select all]`.
  "Select all" toggles between all-in-current-list and none.
- **Bottom action bar slides up** with the batch actions (labeled icons). It replaces
  the mini-player while selection mode is active; the **mini-player is hidden during
  selection mode and restored on exit** to avoid stacked bars.
- Enter/exit use smooth slide+fade transitions.
- If more than ~4 actions are valid for the current screen/selection, extras collapse
  into a **⋮ overflow** within the bottom bar so it never crowds.

## Batch Actions (context-aware)

The bottom bar shows only the actions valid for the current screen and selection state:

| Action | Availability |
|---|---|
| Play next | All five screens |
| Add to queue | All five screens |
| Add to playlist | All five screens (reuses `SaveToPlaylistSheet`) |
| Download | Shown when ≥1 selected track is not downloaded |
| Remove download | Shown when all selected tracks are downloaded |
| Delete | **Only** on user-owned collections: Playlist detail, Liked Songs. Hidden on Album, Artist, Search. |

- Download vs. Remove download is decided from the selection's aggregate download
  state. If the selection is mixed (some downloaded, some not), show **Download**
  (acts on the not-yet-downloaded subset).
- **Delete** uses a single confirmation dialog covering the whole batch (e.g. "Remove
  N songs from this playlist?"). On Playlist detail it removes from that playlist; on
  Liked Songs it unlikes.

## Screen Scope

Selection mode is supported on all five track-list surfaces:

1. **Playlist detail** (`PlaylistDetailScreen`) — full action set incl. Delete.
2. **Liked Songs** (`LikedSongsDetailScreen`) — full action set incl. Delete (unlike).
3. **Album detail** (`AlbumDetailScreen`) — no Delete.
4. **Artist detail** (`ArtistDetailScreen`) — no Delete.
5. **Search & Library** (`feature/search` results, `LibraryScreen`) — no Delete.

## Architecture

Reuse over rebuild. The batch layer wraps proven single-track logic.

### New, small, shared pieces

- **`SelectionState`** — a hoistable holder: `selectedIds: Set<TrackId>` + `isActive:
  Boolean`, with `toggle(id)`, `selectAll(ids)`, `clear()`, `enter(id)`. Backed by
  `rememberSaveable` (set of ids) so it survives rotation / process death.
- **`SelectionBottomBar`** — a composable taking the list of valid actions + callbacks;
  renders labeled icons and the overflow.
- **Contextual top-bar state** — each screen's top bar renders the
  `[✕] "N selected" [Select all]` variant when `SelectionState.isActive`.

### Changes to existing reusable components

- **`TrackListItem`** (`core/ui/.../components/TrackListItem.kt`) gains two optional
  params: `selectionActive: Boolean` and `selected: Boolean` (renders the leading
  checkbox; defaults preserve current behavior). `onLongPress` already exists for
  entering the mode; `onMoreClick` already exists to host the moved options sheet.
  The local `DetailTrackRow` variant in `PlaylistDetailScreen` gets the same treatment
  (or is converted to use `TrackListItem`).

### Action delegation

Batch actions iterate the selection and delegate to existing ViewModel/repository
paths, so no new business logic is written:

- Delete → `PlaylistDetailViewModel.deleteTrackFromPlaylist` (and the Liked/unlike path).
- Add to playlist → existing `SaveToPlaylistSheet` flow, fed the full selection.
- Play next / Add to queue → existing `PlayerRepository` queue operations.
- Download / Remove download → existing download repository operations.

## Edge Cases

- Empty selection → bottom bar hides and mode exits automatically.
- Mixed download state → show **Download**, acting on the un-downloaded subset.
- Rotation / process death → selection survives via `rememberSaveable`.
- Large batches (download/delete) → show progress and allow cancellation where the
  underlying operation supports it.
- Mini-player must reliably restore on every exit path (✕, Back, last-deselect, action
  completion).
- Back button precedence: exits selection mode first, navigates second.

## Testing

- **`SelectionState` unit tests:** enter/toggle/selectAll/clear transitions, last-item
  deselect auto-exits, saver round-trips the id set.
- **Action-availability logic tests:** correct action set per screen; Download vs.
  Remove-download vs. mixed resolution; Delete hidden on non-owned screens.
- **Batch delegation tests:** a selection of N invokes the underlying single-track op
  N times (or the batch repo op once) with the right ids; confirmation gating for delete.
- **UI/instrumented (where feasible):** long-press enters mode, tap toggles, Back exits
  mode before navigating, mini-player hides/restores.

## Open Questions

None blocking. Per-screen rollout order will be decided in the implementation plan
(suggested: Playlist detail first as the reference implementation, then the others).
