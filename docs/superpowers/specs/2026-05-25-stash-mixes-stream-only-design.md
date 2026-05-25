# v0.9.37 — Stash Mixes: stream-only for new discoveries

**Date:** 2026-05-25
**Status:** Design
**Branch:** `feat/mixes-stream-only` (to be created)
**Follows:** v0.9.36 (`19fc52f`) — lyrics integration
**Bundled with:** Track B (YT scrobbler 2-layer fix), Track C (Now Playing favorites/download fix)

## Problem

Stash Mixes (Daily Discover, Deep Cuts, and other recipe-driven rotating playlists) currently **download** every discovered track to device. The discovery pipeline at `StashDiscoveryWorker.handle()` (`core/data/.../sync/workers/StashDiscoveryWorker.kt:278`) enqueues a `DownloadQueueEntity` for every PENDING `DiscoveryQueueEntity` row that survives the filter passes. The download worker then writes the file to disk and stamps `isDownloaded = true`.

This burns user data (each FLAC is 30-50 MB) and storage. The weekly cap of 100 discoveries per recipe (`StashDiscoveryWorker.kt:74`) was sized to bound the cost — but the cost is still meaningful for users on cellular plans or low-storage devices.

The v0.9.30 **online streaming engine** (`022e98a`) makes downloads unnecessary for this use case. The player at `PlayerRepositoryImpl.buildMediaItemForTrack` (`core/media/.../PlayerRepositoryImpl.kt:856`) already plays any track with `isStreamable = true, isDownloaded = false, youtubeId = ...` via Qobuz/Kennyy + YouTube fallback. The infrastructure is in place; the discovery worker just doesn't use it yet.

## Goals

- **New** Mix discoveries are stream-only. The discovery worker creates a `TrackEntity(isStreamable = true, isDownloaded = false)` and **does not** insert a `DownloadQueueEntity`.
- **Existing** downloaded Mix tracks remain on disk. They continue to play from local file. They rotate out of the Mix recipe naturally as `excludeSnapshot` cycles them; they stay in Library indefinitely (no purge).
- The Mix playlist remains visible in Library and Home even when offline. Tapping a stream-only track while offline shows a **Snackbar** ("Online only — connect to play this track") and aborts the play.
- When the queue auto-advances to a stream-only track while offline, **silently skip** to the next playable (downloaded) track in the queue. The Snackbar fires only on **explicit user tap**, never on auto-advance.
- Search-tab downloads, Spotify-sync downloads, Liked-songs downloads — **unchanged**. The behavior change is scoped to the discovery worker's Mix path only.

## Non-goals

- **No migration / purge of already-downloaded Mix tracks.** User explicitly chose "leave the downloads as is in the stash mixes. future songs that appear there from last.fm will be stream only." No one-shot cleanup; no file deletion; no `download_queue` cancellation.
- **No discovery-cap change.** Weekly cap stays at 100/recipe. Now that the storage cost is gone, raising it is reasonable future work — out of scope for this commit.
- **No "Save offline" per-track button** on Mix tracks. Users who want a Mix track on disk can tap the Now Playing download button (after Track C fixes that path); fully-fledged "save this Mix" bulk action is out of scope.
- **No "Refresh when online" CTA** when offline and the entire Mix queue is stream-only. Snackbar says end-of-queue; user reconnects manually.
- **No Search-tab parser fix.** A parallel regression (same root cause as Track B) has the Search tab's "Songs / Artists / Albums" sections coming back empty; the Top-result card still works. That's a separate issue (#TBD) and a separate fix.
- **No new lyrics-on-streaming-Mix work.** The streaming lyrics fetch shipped in `118a9b3` should cover this via the shared player path; verify in manual QA.
- **No UI redesign.** Mix tiles, Mix detail screen, and Home Mix cards render exactly as today. Per `feedback_preserve_existing_design.md`.

## Design

### 1. Seam choice

**Seam A** — divert at `StashDiscoveryWorker.handle()` line 278. Lowest blast radius; the change is confined to the discovery worker plus one new DAO method. Alternatives considered:

- **Seam B** — branch in `DiscoveryDownloadWorker.doWork()` to mark Mix items COMPLETED without downloading. Rejected: leaves spurious `download_queue` rows; spends a DAO read per item; muddles two concerns.
- **Seam C** — branch in `TrackDownloader.downloadTrack` / `DownloadCoordinator`. Rejected: this interface is shared with Search and Spotify-sync downloads; risk of bleeding into "other pipelines unchanged."

### 2. Components

| Component | Change | Why |
|---|---|---|
| `StashDiscoveryWorker.handle()` (`core/data/.../sync/workers/StashDiscoveryWorker.kt:278`) | Skip `downloadQueueDao.insert(...)` for Mix-recipe items; create the stub `TrackEntity` with `isStreamable = true, isDownloaded = false`. | Diverts new Mix discoveries from the download pipeline. |
| `PlaylistDao` (new method) | `getStreamableOrDoneTrackIdsForRecipe(recipeId: Long): List<Long>` — selects tracks for a recipe whose status is `isDownloaded = 1` **OR** `isStreamable = 1`. | MixRefresh needs to link both downloaded and stream-only stubs into the Mix playlist. |
| `StashMixRefreshWorker.materializeMix` (`core/data/.../sync/workers/StashMixRefreshWorker.kt:452`) | Call the new DAO method instead of `getDoneTrackIdsForRecipe`. | Stream-only stubs become Mix members. |
| `StashPlaybackService` filter (`core/media/.../service/StashPlaybackService.kt:500, 749`) | Replace `tracks.filter { it.isDownloaded }` with `tracks.filter { it.isDownloaded \|\| it.isStreamable }` at both sites. | Stream-only tracks become playable in the queue. |
| **New:** `MixOfflineTapGuard` in `PlaylistDetailViewModel` (and Home + Library Mix tap handlers) | Before `playTrack(track)`, check `track.isStreamable && !track.isDownloaded && !networkMonitor.isOnline.value`. If true → emit `SnackbarEvent("Online only — connect to play this track")`, abort. | Tap-time offline UX. |
| `PlayerRepositoryImpl` queue advancement | When the next-up track is stream-only AND offline, advance silently to the next playable track. If the queue is exhausted of playable tracks, stop and emit a Snackbar event ("End of offline Mix"). | Auto-advance silent-skip. |

**No database migration. No file deletion. No purge.**

### 3. Data flow

#### New-discovery path

1. `MixSeedGenerator` queries Last.fm → `DiscoveryQueueEntity(status = PENDING)` row inserted. *(Unchanged.)*
2. `StashDiscoveryWorker.handle()` drains PENDING rows:
   - Creates `TrackEntity(isDownloaded = false, **isStreamable = true**, youtubeId = <resolved>, ...)`.
   - **Does NOT** insert into `download_queue`.
   - Marks the `DiscoveryQueueEntity` as `DONE`.
3. `StashMixRefreshWorker.materializeMix` (next worker tick, daily cadence):
   - Calls `playlistDao.getStreamableOrDoneTrackIdsForRecipe(recipeId)` — returns downloaded + streamable track ids.
   - Inserts `PlaylistTrackCrossRef` rows linking them into the Mix playlist.
4. User opens the Mix → taps a track:
   - **Online:** plays via streaming engine (Qobuz/Kennyy + YouTube fallback). Already wired.
   - **Offline:** `MixOfflineTapGuard` emits Snackbar; play aborts.
   - **Auto-advance offline:** queue advances silently past stream-only tracks; stops with Snackbar when no playable track remains.

#### Existing-track path

Untouched. Already-downloaded Mix tracks continue to play from local file via `PlayerRepositoryImpl.resolveTrackToMediaItem` (which always prefers local file when `track.filePath` is non-null). They rotate out of the Mix recipe naturally as `excludeSnapshot` cycles them and remain in Library indefinitely.

### 4. Error handling

- **Resolver fails to find `youtubeId` for a new seed:** `StashDiscoveryWorker` already skips such tracks. No new code.
- **Streaming engine returns 0 sources at playback time:** existing "Source unavailable" handling in `PlayerRepositoryImpl`. No new code.
- **Network drops mid-playback (downloaded track playing, next track is stream-only):** auto-advance silent-skip handles it.
- **Entire Mix queue is stream-only and user is offline:** Snackbar "End of offline Mix"; playback stops. Future enhancement: "Refresh when online" CTA — out of scope.
- **Existing `getDoneTrackIdsForRecipe` query** is preserved for non-Mix callers (if any). The new method is materializer-only.

### 5. Testing

| Layer | Test |
|---|---|
| Unit (`StashDiscoveryWorkerTest`) | Mix recipe + PENDING discovery → asserts no `download_queue` insert; the `TrackEntity` row has `isStreamable = true, isDownloaded = false`. |
| Unit (`PlaylistDaoTest`) | New `getStreamableOrDoneTrackIdsForRecipe` query returns downloaded + streamable track ids for a recipe. |
| Unit (`MixOfflineTapGuardTest`) | Offline + stream-only Mix track → Snackbar event emitted; play call aborted. |
| Unit (`PlayerRepositoryImplTest`) | Stream-only next-up + offline → skip to next downloaded track; queue exhausted of playable → Snackbar event. |
| Integration (`StashMixRefreshWorkerIntegrationTest`) | Recipe with mixed downloaded + stream-only stubs materializes correctly into the Mix playlist. |
| **On-device manual** (per `feedback_install_after_fix`) | Airplane-mode + tap Mix track → Snackbar appears; play through Mix mid-queue → silent skip past stream-only; restore connectivity + tap → streams normally. |

### 6. Risks

| Risk | Mitigation |
|---|---|
| `NetworkMonitor` (or equivalent) may not be reachable from `PlaylistDetailViewModel` / `PlayerRepositoryImpl` with the existing DI graph. | Verify before implementation; if not present, introduce one (`ConnectivityManager` callback wrapped in a `StateFlow<Boolean>` Singleton). |
| Lookup of "next playable track in queue" for silent-skip could be expensive if the queue is large. | Linear scan over the in-memory MediaSession queue is O(n) and n ≤ ~100. Acceptable. |
| Snackbar host scoping — the global `SnackbarHostState` must be reachable from the abort point. | Confirm `MainScaffold` exposes a global host; if not, route through an event channel observed by `MainScreen`. |
| Lyrics for stream-only Mix tracks — assumed working via `118a9b3` streaming lyrics fetch. A silent regression here would mean new Mix tracks show no lyrics until refresh. | Smoke-test in manual QA; flag as a follow-up if broken. |
| Same recipe touched by a v0.9.30 streaming-engine `TrackEntity` insert race could collide with the discovery worker's insert (both keyed on the same `youtubeId`). | Both paths must use the same upsert primitive (`findByYoutubeId` then insert with `id = 0` for autogen). Mirror the `SearchDownloadCoordinator.upsertSearchTrack` pattern. |

### 7. Implementation order

1. New DAO method `PlaylistDao.getStreamableOrDoneTrackIdsForRecipe` + unit test.
2. `StashDiscoveryWorker.handle` — gate the download enqueue on recipe type (Mix vs not-Mix).
3. `StashMixRefreshWorker.materializeMix` — switch to the new DAO method.
4. `StashPlaybackService` queue filter — include `isStreamable` tracks.
5. `MixOfflineTapGuard` + Snackbar wiring + auto-advance silent-skip in `PlayerRepositoryImpl`.
6. Manual on-device QA: tap online, tap offline, queue mid-play with mixed Mix tracks, airplane-mode toggle mid-Mix.

### 8. Out of scope (separate work)

- Raise the 100/week discovery cap now that storage cost is gone.
- "Refresh when online" CTA on empty-offline-Mix Snackbar.
- Per-track "Save offline" button on Mix tracks (download a stream-only Mix track on demand).
- Search-tab `musicShelfRenderer` parser fix (same root cause as Track B, separate surface).
- Custom playlist covers for stream-only Mixes (already tracked in `memory/project_next_session_playlist_images.md`).
