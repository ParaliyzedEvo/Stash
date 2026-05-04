# v0.9.9 — `syncEnabled` becomes the playlist banlist

**Date:** 2026-05-03
**Status:** Design
**Branch:** `feat/playlist-banlist` (worktree path: `.worktrees/playlist-banlist`; branch name is a historical artifact from when the design started as an explicit banlist table — final design uses the existing `syncEnabled` flag instead)

## Problem

Playlists from upstream Spotify and YouTube libraries that the user has *not* turned on the sync switch for still appear as empty mosaics on Home and in the Library. Today's `DiffWorker` creates a local row for every playlist in the upstream snapshot — `syncEnabled = false` skips the track import, but the playlist row itself is committed to the `playlists` table and surfaces everywhere via `MusicRepository.getAllPlaylists()`.

The user has hundreds of Spotify/YouTube playlists in their upstream library. Each fresh sync re-imports the snapshot and re-creates rows for ones the user has explicitly "Delete & Block"-ed (because `deletePlaylistWithCascade` deletes the row, and the next sync's `findOrCreatePlaylist` recreates it with `syncEnabled = false`). The mosaic grid becomes a clutter of empty placeholders.

The original framing of this work was a separate "banlist" table with its own management screen. After exploration the user reframed the request: **"if sync switch is off for that playlist in the sync preferences nothing from that playlist should be saved to disk to appear anywhere in the app. it should be that simple."** The fix is one repository-level filter, not a new schema.

## Goals

- Playlists where the user has not toggled the sync switch on **do not appear** on Home or in the Library.
- Playlists with downloaded tracks (`is_downloaded = 1` AND `is_blacklisted = 0`) **stay visible** even when `syncEnabled = false`, preserving user investment in playlists they previously synced.
- The Sync tab's per-playlist picker continues to show every playlist (so the user can flip sync on for one they want).
- Locally-created CUSTOM playlists (`source = LOCAL`) are visible immediately on creation, even when empty — they have no upstream toggle to be meaningful.
- No DB schema migration. No new UI screens. No new toggles in the delete dialog.

## Non-goals

- A separate `playlist_blocklist` table.
- A "Banned Playlists" management screen in Settings.
- A new toggle in the playlist delete dialog.
- A "What's new" / changelog modal explaining the visibility change.
- Auto-cleanup of tracks that are orphaned by sync-off playlists. The existing cascade rules in `MusicRepositoryImpl.removeTrackFromPlaylistAndMaybeDelete` already handle this for explicit deletes; sync-off does not trigger automatic deletion.
- Bulk "hide all sync-off playlists" action — they're hidden by default now.
- Modifying the sync engine itself (`DiffWorker`, `PlaylistFetchWorker`). Those keep creating rows; only the *consumers* of `getAllPlaylists()` change which query they call.

## Design

### 1. New repository method

**Location:** `core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepository.kt` (interface) + `MusicRepositoryImpl.kt` (impl).

**Interface addition:**

```kotlin
/**
 * Playlists that should appear on Home + Library. Filters the
 * "discovery clutter" of upstream playlists the user has not
 * synced — they remain reachable through the Sync tab's picker
 * via the unfiltered [getAllPlaylists].
 *
 * Returns rows where:
 *   - `sync_enabled = 1`, OR
 *   - the playlist has at least one downloaded, non-blacklisted track
 *     (preserves visibility into playlists the user previously synced
 *     and has invested local storage in).
 *
 * Order matches `getAllPlaylists`: most-recently-added first via
 * `playlists.date_added DESC`.
 */
fun getDisplayPlaylists(): Flow<List<Playlist>>
```

The existing `getAllPlaylists(): Flow<List<Playlist>>` is unchanged and stays the source for the Sync tab's picker.

### 2. New DAO query

**Location:** `core/data/src/main/kotlin/com/stash/core/data/db/dao/PlaylistDao.kt`.

```kotlin
@Query("""
    SELECT p.* FROM playlists p
    WHERE p.sync_enabled = 1
       OR EXISTS (
           SELECT 1 FROM playlist_tracks pt
           JOIN tracks t ON pt.track_id = t.id
           WHERE pt.playlist_id = p.id
             AND t.is_downloaded = 1
             AND t.is_blacklisted = 0
       )
    ORDER BY p.date_added DESC
""")
fun getDisplayPlaylists(): Flow<List<PlaylistEntity>>
```

The `EXISTS` clause is the key construct. It evaluates O(log n) per playlist row against the existing `playlist_tracks(playlist_id)` foreign-key index and the standard `tracks(is_downloaded)` lookup pattern. For libraries up to ~10k tracks the cost is negligible. If profiling ever shows pressure, the escape hatch is a denormalized `downloaded_track_count` column on `PlaylistEntity` — explicitly out of scope for this spec.

### 3. Repository implementation

**Location:** `MusicRepositoryImpl.kt`. Single-line method delegating to the DAO and mapping `PlaylistEntity → Playlist`:

```kotlin
override fun getDisplayPlaylists(): Flow<List<Playlist>> =
    playlistDao.getDisplayPlaylists().map { entities ->
        entities.map { it.toPlaylist() }
    }
```

The `.toPlaylist()` extension function already exists (used by `getAllPlaylists`). No new mapper.

### 4. Caller migration

| File | Change |
|---|---|
| `feature/home/src/main/kotlin/com/stash/feature/home/HomeViewModel.kt` | `musicDataFlow` first input: `musicRepository.getAllPlaylists()` → `musicRepository.getDisplayPlaylists()` (around line 81) |
| `feature/library/src/main/kotlin/com/stash/feature/library/LibraryViewModel.kt` | Wherever `getAllPlaylists()` is consumed for the Library Playlists tab, swap to `getDisplayPlaylists()` |
| `feature/sync/src/main/kotlin/com/stash/feature/sync/SyncViewModel.kt` | **No change** — keeps using `getAllPlaylists()` (picker needs to show all rows including `syncEnabled = false`) |

If other ViewModels (NowPlaying, Search, etc.) consume `getAllPlaylists` for non-discovery purposes (e.g. SaveToPlaylistSheet's "add to existing playlist" picker), they should keep using `getAllPlaylists()` so the user can save to a sync-off playlist they own. Audit during implementation; default is "leave caller using `getAllPlaylists` unless it's a Home/Library *discovery* surface."

### 5. CUSTOM-playlist fix

`MusicRepositoryImpl.kt:263, 289` — both `insertPlaylist` paths default `syncEnabled = false`. For locally-created playlists (`source = MusicSource.LOCAL`), this would make a brand-new empty playlist invisible until the user adds a downloaded track.

**Fix:** in `insertPlaylist` (and `createPlaylist`, which calls into it), set `syncEnabled = true` when `playlist.source == MusicSource.LOCAL`. Concrete change in the data layer — no API change to callers.

```kotlin
// In MusicRepositoryImpl.insertPlaylist (and createPlaylist):
val entity = playlist.toEntity().copy(
    syncEnabled = playlist.syncEnabled || playlist.source == MusicSource.LOCAL,
)
```

The `||` keeps the existing `syncEnabled = true` callers (e.g. `StashMixRefreshWorker:198`) working as-is, only flipping the LOCAL fallback. STASH_MIX / DOWNLOADS_MIX system-owned playlists are not LOCAL-sourced and already explicitly set `syncEnabled = true`; no change for them.

### 6. Version bump

`app/build.gradle.kts`: `versionCode 45 → 46`, `versionName "0.9.8" → "0.9.9"`.

## Risks

| Risk | Mitigation |
|---|---|
| **`EXISTS` performance on large libraries.** Per-playlist subquery scans `playlist_tracks` joined with `tracks`. | Existing FK index on `playlist_tracks.playlist_id` and the `tracks(is_downloaded)` lookup pattern make this O(log n) per row. For libraries < 10k tracks the cost is negligible. Escape hatch is a denormalized `downloaded_track_count` column on `PlaylistEntity`, deferred. |
| **v0.9.8 → v0.9.9 surprise.** Users who toggled some playlists off in Sync tab will see those mosaics vanish on upgrade. No banner, no "What's new". | Acceptable — this is the fix the user asked for. The Sync tab still shows them; users who notice "where did Spotify Daily Mix 1 go?" can re-enable in two taps. Same silent-migration approach as v0.9.8's lossless default flip. |
| **CUSTOM playlist callers that pass an explicit `syncEnabled` value would have it overridden by the LOCAL `||` fallback.** | The fallback only kicks in when `playlist.syncEnabled == false`, and only for `LOCAL` source. Callers explicitly setting `syncEnabled = true` are unaffected. Callers that pass `syncEnabled = false` on a LOCAL playlist are misusing the field — there's no upstream toggle for LOCAL — so flipping their false to true is the right behaviour. |
| **`SaveToPlaylistSheet` and other "add to existing playlist" pickers** may use `getAllPlaylists`. If we accidentally migrate them to `getDisplayPlaylists`, users couldn't save to a sync-off playlist they own with downloaded tracks (filter still passes) but couldn't save to one with no downloaded content (filter excludes). | Audit each `getAllPlaylists` caller during implementation. Only Home + Library *discovery* surfaces migrate. Picker/save dialogs stay on `getAllPlaylists`. Default during the audit: "leave caller as-is unless I can articulate why it's a discovery surface." |
| **`DiffWorker` keeps writing rows for `syncEnabled = false` playlists.** That's intentional — the rows back the Sync tab picker. But every full sync writes to the `playlists` table even for content that's permanently invisible to the user. | Acceptable. Row writes are cheap; the table size is bounded by the user's upstream library size (typically dozens to hundreds of rows). No GC needed. |
| **Daily mixes regenerate.** If sync is on for a source, daily mixes refresh and stay visible. If sync is off, the snapshot persists at last-known state with `syncEnabled = false`. The filter hides it. | Intentional. User who wants daily mixes back flips sync on. |

## Testing

### Unit tests

None added. Pattern follows the project precedent (no `HomeViewModelTest`, no `MusicRepositoryImplTest`, no `LibraryViewModelTest`). The discipline is on-device acceptance.

### Manual acceptance (on signed release sideload)

1. **Cold start** — install over v0.9.8, open Home. Mosaic grid should show only playlists where sync is on or downloaded tracks exist. Empty placeholders for never-synced upstream playlists are gone.
2. **Library → Playlists tab** — same filter applied. Sync-off playlists with no downloads are gone.
3. **Sync tab → per-playlist picker** — every upstream playlist still listed. Sync-off ones show their toggle in the off position; user can flip.
4. **Toggle a Spotify playlist's sync OFF in Sync tab** — Home + Library updates immediately (Flow re-emits, filter re-applies). The playlist disappears.
5. **Toggle that same playlist's sync back ON** — playlist reappears on Home + Library after the next sync (or immediately if it has downloaded tracks already).
6. **Create a new local playlist via the in-app + Playlist button** — visible immediately on Home and Library even with zero tracks.
7. **"Delete & Block" a Spotify playlist** — confirm:
   - The playlist mosaic disappears from Home + Library (existing behaviour, preserved).
   - On the next fresh sync, the playlist row re-appears in the `playlists` table (`syncEnabled = false` re-import) — but is **not** visible on Home + Library (this is the v0.9.9 fix).
   - The Sync tab picker shows it again as a sync-off entry. To make it disappear from the picker too, user would need a future banlist mechanism (deferred).

### Regression check

- DOWNLOADS_MIX still appears (search-tab one-off downloads land here; system-owned, `syncEnabled = true`).
- STASH_MIX still appears (locally generated, `syncEnabled = true`).
- LIKED_SONGS visibility: on with sync, off without (user can re-enable via Sync tab).
- The "Save to playlist" sheet (if used elsewhere in the app) still shows all playlists regardless of sync state.

## Out of scope

- Separate banlist table or "Banned Playlists" Settings screen (the original v0.9.9 framing — dropped after user reframing).
- Auto-cleanup of tracks orphaned by sync-off playlists. Existing cascade rules in `MusicRepositoryImpl.removeTrackFromPlaylistAndMaybeDelete` handle explicit deletes; sync-off is reversible and shouldn't auto-destroy data.
- Modifying the playlist delete dialog. The existing `alsoBlacklist` toggle stays unchanged.
- "What's new" modal.
- Bulk actions in the Sync tab (e.g., "Disable sync for all daily mixes").
- A denormalized `downloaded_track_count` column. Deferred unless `EXISTS` performance becomes a real complaint.
- Modifying `PlaylistFetchWorker` or `DiffWorker`. The sync engine continues to mirror upstream truth; only the *display* layer changes.

## Ship as

v0.9.9. The work is small enough — ~30 lines of production code change, no schema migration, no new UI — to ride alone as a focused fix release. Bundling other items (FLAC-only mode, donation marquee, search→squid.wtf downloads from the user's later list) would dilute the release narrative; each gets its own brainstorm.

Bumps `versionCode 45 → 46` and `versionName "0.9.8" → "0.9.9"` in `app/build.gradle.kts`.
