# Offline Stash Mix Visibility + Playback — Design

**Status:** Approved (brainstorm), ready for implementation plan
**Date:** 2026-05-30
**Subsystem:** Stash Mix offline visibility + playback (`:core:data` DAO, `:core:media` helper, `feature/library` + `feature/home` ViewModels)
**Branch:** `feat/customizable-stash-mixes` (this work depends on the Stash Mixes feature, so it lives here, not on `master`).

## Problem

Stash Mixes are **stream-only by design** (v0.9.37): the discovery pipeline materializes
recipe tracks as stub rows with `is_streamable = 1, is_downloaded = 0` (no file on disk).
But the playlist track query filters to downloaded-only tracks unless `includeStreamable` is
true, and **Offline mode passes `includeStreamable = false`**. So in Offline mode every Stash
Mix collapses to the handful of manually-downloaded tracks — usually **"0 tracks"** —
effectively making mixes online-mode-only. The user's intent: mixes should display and
stream **regardless of the Online/Offline (sync) setting**, because they are inherently an
online discovery surface.

A reviewed-and-verified fix for this already exists in `git stash@{0}`
("offline-mixes-visibility fix") but was never committed; the branch has since churned. It
covers the DAO + the playlist **detail** screen, but **not** the Home screen's play paths
(the known "Home-playback gap").

## Goals / Non-goals

**Goals**
- Stash Mix tracks render in Offline mode (full list + correct counts), not collapsed to
  downloaded-only.
- A mix plays end-to-end from **both** the detail screen and the Home screen whenever the
  device has a live connection, regardless of the Online/Offline preference.
- When genuinely offline (no connection), a stream-only mix track can't play: it still shows,
  and tapping it surfaces the existing "Online only — connect to play" per-tap message; bulk
  play falls back to the downloaded subset.
- The exemption is scoped strictly to `STASH_MIX`. Library/CUSTOM/synced playlists keep their
  current downloaded-only-when-offline behavior.

**Non-goals (YAGNI)**
- Greying-out / badging unplayable stream-only tracks when disconnected (chosen UX is
  "show all + per-tap toast").
- Pushing queue-playability into `MusicRepository` (over-engineered for two call sites).
- Changing any non-`STASH_MIX` playlist behavior.
- The aggregate Home actions `playAllMixes` and `playLikedSongs` (they use the same inline
  filter and will still collapse mixes to downloaded-only when offline). Scope is the
  single-mix entry points `playPlaylist` + `addPlaylistToQueue`. If a user reports "play all
  mixes" is empty offline, extending those two is a trivial follow-up using the same helper —
  deliberately deferred to keep this change focused.
- Fixing the pre-existing red `:core:data` DAO test unless this change touches it (confirm it
  pre-dates this on `master`; note, don't chase).

## Architecture

Two layers — visibility (DAO) and playability (one shared rule, used by two ViewModels):

**Visibility (DAO).** `TrackDao.getByPlaylist` is the shared query behind every surface
(Home cards' counts via `getTracksByPlaylist`, detail list). Adding a `STASH_MIX` exemption
there fixes "0 tracks" everywhere at once.

**Playability (one rule, two ViewModels).** Extract the enqueue rule into a single pure
helper so it isn't duplicated:

```
queuePlayableTracks(tracks, isMix, streamingEnabled, connected): List<Track>
  = if (streamingEnabled || (isMix && connected)) tracks
    else tracks.filter { it.filePath != null }
```

Lives in `:core:media` (which owns `ConnectivityMonitor` + streaming concerns and is depended
on by both feature modules). Pure (booleans + `List<Track>`), so exhaustively unit-testable.

## Components

1. **`TrackDao.getByPlaylist`** *(from stash)* — WHERE clause becomes:
   `... AND (t.is_downloaded = 1 OR :includeStreamable OR (p.type = 'STASH_MIX' AND t.is_streamable = 1))`.
   Unavailable (`checked_at != null AND is_streamable = 0`) and unchecked (`checked_at IS NULL`)
   rows stay excluded. ORDER BY unchanged.
2. **New `queuePlayableTracks(...)`** in `:core:media` — the pure rule above. Single source of truth.
3. **`PlaylistDetailViewModel.playableTracks()`** *(from stash, refactored)* — computes `isMix`
   from `uiState.playlist?.type == STASH_MIX` and `connected` from `connectivityMonitor.isConnected()`,
   delegates to the helper. Already used by `playTrack` / `shuffleAll` / `playAll`; the per-tap
   connectivity guard in `playTrack` is unchanged.
4. **`HomeViewModel`** *(the gap)* — inject `ConnectivityMonitor`; `playPlaylist` and
   `addPlaylistToQueue` replace their inline `if (streamingPreference.current()) tracks else
   tracks.filter { filePath != null }` with the helper (passing `playlist.type == STASH_MIX`
   and `connectivityMonitor.isConnected()`).
5. **`MusicRepositoryImpl.getTracksByPlaylist`** *(from stash)* — comment-only clarification
   that the STASH_MIX exemption lives in the DAO and tap-time playability is governed by live
   connectivity, not the preference.

**Isolation:** the only new unit is the pure `queuePlayableTracks` helper (one responsibility,
no dependencies beyond the `Track` model). Everything else is small in-place edits to code
that already owns the concern.

## Data flow

Offline mode → `getTracksByPlaylist(playlistId)` → DAO returns the mix's streamable tracks
(exemption) → full list + counts render → user plays from detail **or** Home → helper gates
the queue:
- **connected** (preference off, but a live connection): stream-only tracks are enqueued and
  resolve via the Kennyy/Squid chain (made fast + reliable by the streaming-failover work).
- **disconnected:** downloaded-only queue; a tapped stream-only track surfaces the per-tap
  "Online only — connect to play" message.

## Error handling

- **Disconnected tap on a stream-only track (detail):** existing per-tap guard in
  `PlaylistDetailViewModel.playTrack` emits the "Online only" user message; no unplayable item
  is enqueued.
- **Disconnected bulk play (detail or Home):** helper returns the downloaded subset; if that's
  empty, the play is a no-op (existing `if (playable.isEmpty()) return` guards).
- **Non-mix playlist offline:** helper returns downloaded-only (control behavior preserved).

## Testing

- **From the stash (6):** DAO — exemption shows streamable for STASH_MIX with flag off; still
  excludes unavailable/unchecked; non-mix (CUSTOM) still collapses. `PlaylistDetailViewModel` —
  offline+connected+mix enqueues the stream-only track at the correct index; playAll
  offline+connected enqueues all streamable; playAll offline+disconnected enqueues only
  downloaded.
- **New helper tests:** exhaustive truth table over `streamingEnabled × isMix × connected`
  (e.g. streaming-on → all; offline+mix+connected → all; offline+mix+disconnected →
  downloaded-only; offline+non-mix → downloaded-only regardless of connection).
- **New `HomeViewModel` tests:** `playPlaylist` and `addPlaylistToQueue` — offline+connected+mix
  → queue includes streamable; offline+disconnected+mix → downloaded-only; offline+non-mix →
  downloaded-only (control).

## Open implementation details (resolve during planning)

- Reconcile the stash onto the current branch (`git stash apply`, resolve any offset/conflict
  in `PlaylistDetailViewModel` from the `recipeDao`/`discoveryQueueDao`/`buildState` churn;
  the DAO base matches exactly). Decide whether to refactor the stash's inline `playableTracks`
  to call the new shared helper (yes — that's the DRY point) or keep its body and only add the
  helper for Home (avoid; would re-duplicate).
- Exact package/file for `queuePlayableTracks` in `:core:media`.
- Confirm `HomeViewModel` test harness can inject a mocked `ConnectivityMonitor`.
- Confirm the pre-existing red `:core:data` DAO test is unrelated (fails on clean `master`).
