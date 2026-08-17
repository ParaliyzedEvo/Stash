# Pin Playlists to Home (+ Liked Songs card) — Design

**Date:** 2026-08-17
**Status:** Approved by user (this session)
**Refs:** backlog item #130 "Pin Playlists to Home, Not Just Mixes"; closed issue #126 (same ask); adjacent but out of scope: #304, #335.

## Problem

Home shows only algorithmic content (Daily Discover hero, Qobuz discovery rows, mix rails). A user's own created or imported playlists — and their Liked Songs — are only reachable through Library. Users want their playlists one glance from launch, opt-in, without disturbing the current Home for anyone who doesn't use it.

## Decisions (settled with user)

1. **Liked Songs = one merged card**, not per-source cards. Tap opens Library ▸ Liked tab, where the existing All / Stash / Spotify / YouTube chips sift by origin.
2. **Rail sits at the top by default** (first section under the Discover hero), and is draggable/hideable like every other section via Settings > Home layout.
3. **Mechanism = new nullable column + new HomeSection** (approach A). Rejected: overloading the Library `pinned` flag (breaks opt-in for existing pins; couples two intents); inverting `hideFromHome` for non-mix types (same column, opposite meaning per row type — unreadable).

## Non-goals (v1)

- No reordering of cards within the rail (pin time is the order).
- No per-source liked cards.
- No change to the mixes' existing Shown-on-Home (`hideFromHome`) flow.
- No Android Auto surface changes.
- No pin affordance for `DOWNLOADS_MIX` or other system playlists (not in the Playlists grid).

## Design

### Data

- `playlists` table: new nullable column `pinned_to_home_at INTEGER` (epoch millis). `NULL` = not on Home — the default for every existing and new row, so the feature is opt-in everywhere. Room migration **41→42**, plain `ALTER TABLE playlists ADD COLUMN pinned_to_home_at INTEGER`.
- `Playlist` model: `pinnedToHomeAt: Long?`. Entity + mapper wiring to match.
- `PlaylistDao.setPinnedToHome(id: Long, at: Long?)` — set to `System.currentTimeMillis()` to pin, `NULL` to unpin.
- Merged Liked card state is not a playlist row: `showLikedOnHome: Flow<Boolean>` (default false) added to the existing `HomeSectionsPreference` DataStore, since it is Home-layout state.

### Home rail

- New `HomeSection.YOUR_PLAYLISTS("your_playlists")`, declared **first** in the enum. Fresh installs and users who never customized their section order see the rail at the top; users with a saved custom order get it appended at the end per the existing `resolveHomeSectionOrder` merge rule (their arrangement is respected; the section is draggable).
- `HomeViewModel`: rail content = playlists where `pinnedToHomeAt != null && mixRail(p) == null`, ordered ascending by `pinnedToHomeAt` (first pinned = first card; stable, mirrors the Your Mixes stable-order precedent). Exposed as `HomeUiState.yourPlaylists: List<HomeMix>`.
- The Liked card, when `showLikedOnHome` is true, leads the rail with a heart treatment and the merged (de-duped) track count across `STASH_LIKED` + `LIKED_SONGS` playlists.
- `HomeScreen`: new `when` branch for the section, rendered through the same `isNotEmpty()` gate as every other rail (rail with no pins and Liked off renders nothing — Home unchanged until first opt-in). Cards reuse `MixRailCard`.

### Taps

- Playlist card tap → `PlaylistDetailRoute(id)` (same screen Library opens).
- Liked card tap → Library ▸ Liked tab via a new optional `initialTab` argument on `LibraryRoute` (default null = current behavior). The existing `LikedSongsDetailRoute` is the wrong target: it merges only external (`LIKED_SONGS`) likes, not `STASH_LIKED`.
- Long-press on a pinned playlist card → minimal sheet: **Open · Play All · Remove from Home** (unpinning never requires a trip back to Library). Long-press on the Liked card: none in v1 (remove via the Settings switch).

### Toggle surfaces

- Library Playlists grid long-press sheet: new row directly under "Pin Playlist" — label **"Show on Home"** / **"Remove from Home"** by `pinnedToHomeAt != null`, wired to the DAO setter via LibraryViewModel. No snackbar (consistent with the Pin action). The grid is CUSTOM-only, which scopes eligibility for free: created and imported playlists get the action; mixes and system playlists don't.
- Settings > Home layout: **"Show Liked Songs on Home"** switch (writes `HomeSectionsPreference.showLikedOnHome`). Discoverable from Home via the existing Personalize card.

### Edge cases

- Deleting a pinned playlist deletes the pin with the row; the rail re-emits without it. No dangling state.
- Sync: pins must survive sync the same way the existing `pinned` and `hideFromHome` flags do (all three ride the playlist row). The implementation plan must verify the sync upsert path updates rows in place rather than replacing them; if any path recreates playlist rows, it must carry `pinned_to_home_at` forward exactly as it carries `pinned`.
- Liked card with zero liked tracks: shows with count 0 when toggled on (the toggle is explicit user intent; no hidden vanishing).

### Tests

- HomeViewModel rail derivation: NULL excluded, mixes excluded even if somehow stamped, pin-time ascending order, Liked card leads when enabled.
- Migration 41→42 (existing migration-test pattern).
- DAO setter round-trip (pin → visible in query; unpin → NULL).
- `resolveHomeSectionOrder`: YOUR_PLAYLISTS first on empty saved state; appended for a saved order lacking it (existing rule, one new assertion).
- Device smoke (Pixel 5 rig): pin two playlists + enable Liked card; verify top rail, drag the section in Settings > Home layout, tap through playlist card / Liked card / long-press unpin.
