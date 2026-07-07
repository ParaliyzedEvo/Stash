# Search tab: per-track actions + recent searches

**Date:** 2026-07-01
**Status:** Approved (brainstorm)
**Scope:** Two independent, small UX gaps in the Search tab, both built on existing rails.

## Problem

1. **No per-track actions on search / artist-profile rows.** On an artist
   profile you can add a whole album to the queue, but a single popular track
   or a single search result has no "Add to queue", "Play next", or "Add to
   playlist" affordance — only preview and download. Same gap on the Search
   results list and Album Discovery.
2. **Recent searches aren't saved.** Leaving and re-opening the Search tab
   loses whatever you typed; there's no history to tap back into.

## Feature 1 — Per-track ⋮ overflow menu

### Where it appears
The shared row composable `PreviewDownloadRow`
(`feature/search/.../PreviewDownloadRow.kt`) is rendered by all three
surfaces: the Search results list (`ResultsList` in `SearchScreen.kt`), the
Artist Profile Popular section (`PopularTracksSection`), and Album Discovery
(`AlbumDiscoveryScreen`). Adding the menu to this one composable covers all
three with no fork.

### UI
A third trailing icon (`Icons.Default.MoreVert`, ⋮) is added after the
existing preview and download icons. Tapping it opens a Material3
`DropdownMenu` anchored to the row with three items, in order:

- **Play next** (`Icons.Default.PlaylistPlay`)
- **Add to queue** (`Icons.Default.PlaylistAdd`)
- **Add to playlist** (`Icons.Default.PlaylistAddCheck`)

The menu's open/closed state is local to the row (`remember`). Selecting an
item closes the menu and invokes the corresponding callback. "Add to
playlist" additionally raises the playlist picker (below).

`PreviewDownloadRow` gains three new callback params, all with default no-op
values so existing call sites and tests compile unchanged until wired:
`onPlayNext: () -> Unit = {}`, `onAddToQueue: () -> Unit = {}`,
`onAddToPlaylist: () -> Unit = {}`.

### Logic — `TrackActionsDelegate`
`core/media/.../actions/TrackActionsDelegate.kt` is the action handler already
shared by `SearchViewModel`, `ArtistProfileViewModel`, and
`AlbumDiscoveryViewModel` (it owns preview + download today). It gains:

- `fun playNext(item: TrackItem)` → converts to `Track`, calls
  `playerRepository.addNext(track)`; emits a "Playing next" message via the
  existing `userMessages` SharedFlow. Wraps in `runCatching`, re-throwing
  `CancellationException` (matches the delegate's existing error convention).
- `fun addToQueue(item: TrackItem)` → `playerRepository.addToQueue(track)`;
  emits "Added to queue".
- `fun addToPlaylist(item: TrackItem, playlistId: Long)` → see semantics
  below; emits "Added to <playlist name>".
- Exposes the user's playlists for the picker as a `StateFlow<List<PlaylistInfo>>`
  (`userPlaylists`), collected from the existing `MusicRepository` playlist
  query. `PlaylistInfo` is the same lightweight type `SaveToPlaylistSheet`
  already consumes.

The `TrackItem → Track` conversion is a NEW small mapper in the delegate —
no existing one fits (preview/download pass `TrackItem` through untouched;
the only analog is the transient-entity synthesis private inside
`PlayerRepositoryImpl.playFromStreamInner`). It must set
`youtubeId = item.videoId`, `source = MusicSource.YOUTUBE`,
`isStreamable = true`, and a synthetic stable id (`videoId.hashCode().toLong()`,
the `ArtistProfileViewModel.toDomainTrack` convention) so
`resolveTrackToMediaItem`'s `track.toEntity()` fallback can stream-resolve a
queued item that isn't in the DB.

Known limitation (accepted for v1): `addNext`/`addToQueue` return `Unit` and
silently no-op when a track can't resolve to a media item (only occurs with
streaming disabled and the track not downloaded — a state in which the whole
search-streaming surface is already inert). The confirmation message is
therefore emitted unconditionally; no result-signature change.

### Add-to-playlist semantics
A search/popular track may not exist in the local DB yet. On "Add to playlist":

1. Ensure the track row exists in the DB (insert if absent) via a NEW small
   helper — no reusable path exists today (`SearchDownloadCoordinator.upsertSearchTrack`
   is private and structurally coupled to a completed download's
   `FinalizeResult`). The helper mirrors upsertSearchTrack's lookup sequence —
   `findByYoutubeId` → canonical-identity match → stub insert (no
   filePath/download columns) — and MUST run the `BlocklistGuard` check
   before inserting (the v0.9.15 blocklist redesign exists because insert
   paths that skipped the guard leaked blocked tracks).
2. Link it to the chosen playlist via
   `MusicRepository.addTrackToPlaylist(trackId, playlistId)`.

The track is **not** auto-downloaded — it appears in the playlist as a
streamable entry; the row's existing download button remains the explicit
download path. Side benefit: playlist membership makes the track survive the
orphan-cleanup sweep that otherwise deletes search-originated tracks with no
playlist membership.

### Playlist picker
Reuse the existing `core/ui/.../components/SaveToPlaylistSheet.kt`
(`ModalBottomSheet`) — the same picker Library screens use, including its
"create new playlist" flow (`onCreatePlaylist`). The screen shows the sheet
when a row's "Add to playlist" fires, passes `delegate.userPlaylists`, and on
selection calls `delegate.addToPlaylist(pendingItem, playlistId)`. Creating a
new playlist routes through the existing create path, then adds the track to
the newly created playlist.

## Feature 2 — Recent searches

### Storage — `RecentSearchesStore`
A new class in `feature/search` (nothing else consumes it) backed by its own
Preferences DataStore. Stores an ordered list of
query strings, serialized as a single delimited string value (mirrors the
existing `LosslessSourcePreferences` priority-list pattern).

- **Order:** most-recent-first.
- **Dedupe:** case-insensitive; recording an existing query moves it to the
  front rather than duplicating.
- **Cap:** 10 entries; recording past the cap drops the oldest.
- API: `val recent: Flow<List<String>>`, `suspend fun record(query: String)`
  (trims, ignores blank), `suspend fun remove(query: String)`,
  `suspend fun clear()`.

### When a query is recorded
Only on a **committed** search, not on every debounced keystroke:

- the user presses the keyboard Search/IME action, OR
- the user taps any result (track, artist, or album) from the results.

`SearchViewModel` calls `recentSearchesStore.record(currentQuery)` at those
moments. The debounce-driven live search that fires while typing does NOT
record.

### UI
`SearchViewModel` exposes `recentSearches: StateFlow<List<String>>` from the
store's flow. `SearchScreen` shows a "Recent searches" list **only when the
query text is empty** (the current empty/idle state). Each entry:

- leading clock icon (`Icons.Outlined.Schedule` / history icon),
- the query text — tapping it sets the query and runs the search
  (`onQueryChanged(query)` + commit),
- a trailing ✕ (`Icons.Default.Clear`) that removes just that entry
  (`viewModel.removeRecentSearch(query)`).

A "Clear all" text button at the top/bottom of the list calls
`viewModel.clearRecentSearches()`. The list is replaced by results the moment
the query is non-empty; no recents shown while a search is active.

## Non-goals (YAGNI)

- No "recently viewed items" (tapped artists/albums as rich rows) — queries
  only.
- No long-press multi-select on search rows — the ⋮ menu covers the
  single-track case, which is the request.
- No auto-download on add-to-playlist.
- No cross-device / cloud sync of recent searches — local DataStore only.

## Testing

- **`RecentSearchesStore`** (Robolectric + a real temp DataStore, the same
  pattern as `QbdlxCredentialStoreTest`): cap at 10, case-insensitive
  dedupe/MRU reorder, remove-one, clear-all, blank ignored.
- **`TrackActionsDelegate`** new actions: `playNext`/`addToQueue` call the
  right `PlayerRepository` method once with a Track carrying
  `youtubeId`/`isStreamable`; `addToPlaylist` inserts-if-absent (blocklist
  checked, dedupe by youtubeId) then links; emits the expected
  `userMessages` string; `CancellationException` re-thrown.
- **`SearchViewModel`**: records on commit (IME action / result tap), does NOT
  record on debounce; `recentSearches` reflects the store; remove/clear
  delegate through.
- Row menu itself follows existing Compose UI patterns; no new UI-test
  framework.

## Files touched (anticipated)

- `feature/search/.../PreviewDownloadRow.kt` — ⋮ icon + DropdownMenu + 3 callbacks
- `feature/search/.../SearchScreen.kt` — thread callbacks, recents idle list, playlist sheet host
- `feature/search/.../PopularTracksSection.kt`, `AlbumDiscoveryScreen.kt` — thread callbacks
- `feature/search/.../SearchViewModel.kt`, `ArtistProfileViewModel.kt`, `AlbumDiscoveryViewModel.kt` — expose delegate actions + (Search) recents
- `core/media/.../actions/TrackActionsDelegate.kt` — playNext/addToQueue/addToPlaylist + userPlaylists
- `feature/search/.../RecentSearchesStore.kt` — new
- reuse: `core/ui/.../components/SaveToPlaylistSheet.kt`, `MusicRepository.addTrackToPlaylist`, `PlayerRepository.addNext/addToQueue`
