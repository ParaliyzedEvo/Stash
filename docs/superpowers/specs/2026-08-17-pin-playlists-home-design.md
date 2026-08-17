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
- `HomeViewModel`: rail content = playlists where `pinnedToHomeAt != null && mixRail(p) == null`, ordered ascending by `pinnedToHomeAt` (first pinned = first card; stable, mirrors the Your Mixes stable-order precedent). Exposed as `HomeUiState.yourPlaylists: List<HomeMix>`. Derivation slots into the existing classification loop in `homePlaylistFlow` (`HomeViewModel.kt:250`). Rail membership depends **only** on `pinnedToHomeAt`; `hideFromHome` is ignored here — it is a mixes-only flow and mixes are excluded from this rail by the `mixRail(p) == null` condition.
- The Liked card is **separate state**, not a synthetic `HomeMix`: `HomeUiState.likedCard: LikedCardState?` — null when `showLikedOnHome` is false; when non-null it carries the merged (de-duped, matching the Liked tab's `distinctBy { it.id }`) track count across `STASH_LIKED` + `LIKED_SONGS` playlists. It renders as the first card of the rail with a heart treatment and has its own tap handler — it never enters the id-routed playlist tap path.
- `HomeScreen`: new `when` branch for the section, gated on `uiState.yourPlaylists.isNotEmpty() || uiState.likedCard != null` (so enabling Liked with zero pins still shows the rail, and a fully-off state renders nothing — Home unchanged until first opt-in). Playlist cards reuse `MixRailCard`.

### Taps

- Playlist card tap → `PlaylistDetailRoute(id)` (same screen Library opens).
- Liked card tap → Library ▸ Liked tab, as a **bottom-nav tab switch** (same `popUpTo(start)/saveState/restoreState` navigation the bottom bar performs — Back behaves exactly like a tab switch, and the Library tab highlights correctly). `LibraryRoute` stays a `@Serializable data object` — **no route-shape change** — because `StashScaffold` matches tabs by qualified-name string equality (`StashScaffold.kt:253`, `173-177`) and a nav argument would break highlighting and reselect-pop. Tab pre-selection travels out of band: a `LibraryDeepLinkController` mirroring the existing `SettingsDeepLinkController` pattern (`core/data/navigation`) — Home writes the request before navigating; `LibraryScreen` reads-and-clears it on entry and switches to the Liked tab via the existing tab-selection state. The existing `LikedSongsDetailRoute` is the wrong target: it merges only external (`LIKED_SONGS`) likes, not `STASH_LIKED`.
- Long-press on a pinned playlist card → minimal sheet: **Open · Play All · Remove from Home** (unpinning never requires a trip back to Library). Play All reuses `HomeViewModel.playMix(playlistId)` (`HomeViewModel.kt:499`) — despite the name it is a generic play-playlist-by-id with the streaming/offline gate; no parallel path. Long-press on the Liked card: none in v1 (remove via the Settings switch).

### Toggle surfaces

- Library Playlists grid long-press sheet: new row directly under "Pin Playlist" — label **"Show on Home"** / **"Remove from Home"** by `pinnedToHomeAt != null`, wired to the DAO setter via LibraryViewModel. No snackbar (consistent with the Pin action). The grid is CUSTOM-only, which scopes eligibility for free: created and imported playlists get the action; mixes and system playlists don't.
- Settings > Appearance > Home layout (`SettingsAppearanceRoute` — the screen hosting the existing section-reorder UI, per `HomeScreen.kt:457`): **"Show Liked Songs on Home"** switch (writes `HomeSectionsPreference.showLikedOnHome`). Discoverable from Home via the existing Personalize card.

### Edge cases

- Deleting a pinned playlist deletes the pin with the row; the rail re-emits without it. No dangling state.
- Sync: pins must survive sync the same way the existing `pinned` and `hideFromHome` flags do (all three ride the playlist row). The implementation plan must verify the sync upsert path updates rows in place rather than replacing them; if any path recreates playlist rows, it must carry `pinned_to_home_at` forward exactly as it carries `pinned`.
- Liked card with zero liked tracks: shows with count 0 when toggled on (the toggle is explicit user intent; no hidden vanishing).

### Tests

- HomeViewModel rail derivation: NULL excluded, mixes excluded even if somehow stamped, pin-time ascending order, Liked card leads when enabled.
- Migration 41→42 (existing migration-test pattern).
- DAO setter round-trip (pin → visible in query; unpin → NULL).
- `resolveHomeSectionOrder`: YOUR_PLAYLISTS first on empty saved state; appended for a saved order lacking it (existing rule, one new assertion).
- `LibraryDeepLinkController` request/consume round-trip (read clears the request), mirroring the `SettingsDeepLinkController` shape.
- Device smoke (Pixel 5 rig): pin two playlists + enable Liked card; verify top rail, drag the section in Settings > Home layout, tap through playlist card / Liked card / long-press unpin.
