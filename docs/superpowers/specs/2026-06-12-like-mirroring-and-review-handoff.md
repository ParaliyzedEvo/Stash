# Handoff: Like-Mirroring feature + Code-Review backlog (2026-06-12)

Checkpoint written mid-brainstorm so a fresh model session can pick up cleanly.
Two independent tracks below: **(A)** the in-progress like-mirroring feature design,
and **(B)** the code-review work — what already shipped to `master` (must be in the
next release) and what remains as a backlog.

---

## A. Like-Mirroring feature (brainstorm IN PROGRESS — not yet a final spec)

> Superseded by docs/superpowers/plans/2026-06-12-like-mirroring.md (implementation plan; decisions unchanged). Implemented on branch feat/like-mirroring (Tasks 1–10 done, all targeted tests green + app assembles; on-device checklist still owed).

**Goal:** when you ❤️ a track in Stash, also Like it on Spotify and/or YouTube
Music; un-hearting removes it there too. User is TOS-aware and wants this gated so
"only a certain type of user (e.g. on a backup account)" opts in.

### Key discovery — most infrastructure already exists, but is half-wired
Verified by reading the code (file paths are load-bearing for the next session):

- **Spotify like WRITE works:** `core/data/.../social/spotify/SpotifyLibraryApiClient.kt`
  → `PUT /v1/me/tracks` with the sp_dc web token (scope `user-library-modify`, no
  Premium, idempotent, 50/batch). Same call the official web Like button makes.
- **YouTube like WRITE works:** `core/data/.../social/ytmusic/YtMusicLibraryApiClient.kt`
  → `InnerTubeClient.likeVideo()` → `POST /like/like` (WEB_REMIX context, SAPISID-cookie
  auth). `data/ytmusic/.../InnerTubeClient.kt:349`.
- **Fan-out dispatcher exists:** `core/data/.../social/LikeDestinationDispatcher.kt` —
  `like(track, Set<Destination>)`, parallel, per-destination dedup via `*_saved_at`.
  `Destination` enum = STASH, SPOTIFY, YT_MUSIC.
- **Dedup columns exist:** `tracks.spotifySavedAt`, `tracks.ytMusicSavedAt`,
  `tracks.stashLikedAt`. DAO: `markSpotifySaved`, `markYtMusicSaved`.
- **Prefs exist:** `core/data/.../prefs/LikePreferences.kt` —
  `heart_default_stash` (default true), `heart_default_spotify` (default **true**),
  `heart_default_ytmusic` (default false). ⚠️ These are VESTIGIAL — defined with
  settings setters but **nothing reads them at the actual heart action.**
- **Auto-save Beta switch:** `core/data/.../sync/workers/AutoSaveScrobbler.kt` — the
  ONLY current writer to Spotify likes. Trigger is **play-frequency** (played on ≥N
  distinct days within 30, default 3), NOT hearts. Hardcoded `setOf(Destination.SPOTIFY)`.
  Started in `StashApplication` (`autoSaveScrobbler.start()`). Settings UI:
  `feature/settings/.../components/SpotifyAutoSaveSection.kt` ("Auto-save liked tracks" + Beta pill).

### The gap
The real heart button is **Stash-local only** and never touches the dispatcher:
- `feature/nowplaying/.../NowPlayingViewModel.kt:684 onLikeTap()` → `stashLikedRepository.add/remove` (lines 706/708)
- `core/media/.../service/StashPlaybackService.kt:1148` COMMAND_TOGGLE_LIKE → `stashLikedRepository.add/remove` (lines 1176/1177)

Those are the **only two heart entry points** — both must route through the new coordinator.

### Decisions LOCKED this session
1. **Trigger = mirror-on-heart.** (Not the play-frequency threshold.)
2. **Symmetric un-like.** Un-hearting in Stash removes the remote Like too.
3. **Forward-only.** No backfill of existing Stash likes (bulk write = most bot-shaped/flag-worthy action). Leave a clean seam for an opt-in trickle-backfill later.
4. **Architecture = new `LikeCoordinator`** (in `core/data/social`) that both heart entry points call:
   ```
   setLiked(track, liked):
     1. local first, always: stashLikedRepository.add/remove (instant, offline-proof)
     2. read mirror prefs (default OFF); for each ENABLED external destination:
          liked=true  -> dispatcher.like(track, {dest})
          liked=false -> dispatcher.unlike(track, {dest})   # unlike() is NEW
     3. per-destination skip if track lacks that id (no spotifyUri / no youtubeId)
   ```
   - Rewire the two heart entry points to call `LikeCoordinator` instead of `stashLikedRepository` directly. Local-like behavior unchanged; mirroring layered on top.
   - **New write paths to add** (clean mirrors of existing adds): `LikeDestinationDispatcher.unlike()`, `SpotifyLibraryApiClient.removeTracks()` (`DELETE /v1/me/tracks`), `InnerTubeClient.removeLike()` (`POST /like/removelike`) + `YtMusicLibraryApiClient.removeLike()`. On successful unlike, clear the existing `*_saved_at` column. **NO schema change.**
   - Existing Spotify-only auto-save Beta switch stays untouched; `*_saved_at` dedup means the two writers never double-fire.
   - Rejected approaches: (B) burying mirroring inside `StashLikedPlaylistRepository` (overloads local repo with network/auth/TOS, makes local like depend on external state); (C) WorkManager job per like (heavyweight for one idempotent write, scheduling jitter fights pacing).
5. **Settings + gating + defaults:**
   - **Two NEW prefs** `mirror_likes_spotify` / `mirror_likes_youtube`, both **default false**. ⚠️ Do NOT reuse `heart_default_spotify` — it defaults *true*, so reusing it would silently start Spotify writes for every existing user on update. Leave the old `heart_default_*` prefs alone (cleanup later, out of scope).
   - **New "Sync your likes" settings section** (Beta pill style), two toggles, each visible/enabled only when that account is connected (Spotify connected / YT cookies present) — same gating the auto-save card uses.
   - **One-time warning dialog** the first time each toggle is enabled: what it does (incl. symmetric un-like), the risk (private unofficial write path, can break / rarely flag an account), the mitigation (use a secondary/backup account), explicit **"I understand"** to enable. No ack = no writes.
   - Card subtitle states **forward-only** ("applies to new likes only").
   - Net: new installs AND existing users start with mirroring OFF; nothing writes externally until deliberate opt-in past a warning.
6. **Safety rails / failure / edge cases:**
   - **Pacing:** single serialized writer (one coroutine draining a queue), min gap ≈1–2s between external calls, respect `Retry-After` on 429 (`SpotifyRateLimitException` carries `retryAfterSeconds`). Defangs bursts (multi-select bulk-like, heart-spam, symmetric-unlike doubling). Simple: serialized + min-gap + backoff, not a token-bucket.
   - **Best-effort, local is sacred:** local Stash like is source of truth, never depends on external success; toggling ❤️ always works instantly/offline. External failure → log (diagnostics) + leave dedup column untouched so it **retries organically on next heart of that track** (same recovery the auto-save scrobbler uses). MVP UX: at most a single subtle "couldn't sync — will retry" snackbar; no nagging.
   - **Edge cases (skip, never error):** no `spotifyUri` → skip Spotify; no `youtubeId` → skip YT (dispatcher already throws typed exceptions caught into `Result.failure`). Un-like only fires if `*_saved_at` non-null (never un-Like what we never Liked). Account disconnected/token expired → fail → organic retry; local unaffected. Rapid like→unlike→like → serialized queue processes in order, converges (no op-coalescing in MVP). Offline → local works, external retries on next action (no persistent offline queue in MVP).

### Remaining brainstorm steps (NOT yet done)
- **Section 4 — Testing** (not yet presented/approved). Sketch: unit-test `LikeCoordinator` (local-always, prefs-gating, per-destination skip, symmetric unlike, unlike-only-if-mirrored); dispatcher `unlike()` paths; the paced writer (ordering/min-gap/Retry-After); new API client methods with a mock web server. Both heart entry points route through the coordinator.
- Write final design doc → spec-review loop → user review → `writing-plans` skill → implement.
- **Fable should feel free to rewrite this plan** — these are the decisions captured, not a finished spec.

---

## B. Code-review work

### B1. SHIPPED TO `master`, NOT YET RELEASED — must be in the next release
Last release tag = **v0.9.51** (`b1dca09a`). Everything after it is unreleased and
should go out in the next release (call it v0.9.52):

- `66bfafa1` — **fix(streaming): antra cache-poisoning.** Speculative background-fill
  (`allowAntra=false`) cached a lossy YouTube fallback during a Qobuz outage, which the
  antra-allowed foreground/prefetch paths then served — so users got YouTube AAC when
  antra FLAC was available. Now a youtube-origin result from an `allowAntra=false`
  resolve is provisional and not cached. (Device-verified no-regression; outage path
  unit-tested.)
- `07fd4333` — **fix(review): three priority items from the full-app review:**
  1. **StreamUrlCache** — LRU-bounded (512) + monotonic TTL (`System.nanoTime`-anchored,
     immune to wall-clock changes). Internal only; public get/put/invalidate unchanged.
  2. **EqController / LoudnessController** — moved the `runBlocking { migrate + DataStore
     read }` out of the `@Singleton` constructors into `scope.launch` (no main-thread I/O
     risk; corrupt DataStore no longer crashes DI-graph construction). Tests share one
     StandardTestDispatcher so `awaitInit()` advances under async init.
  3. **Migration tests** for the two newest, previously-untested migrations (30→31
     `lastfm_response_cache`, 31→32 `spotify_resolution`).
  - Verified locally (completed runs): StreamUrlCacheTest 10/10, Eq/Loudness 5/5 each,
    both migration tests 1/1. Full `core:media` regression left to CI (`tests.yml`).
    NOTE: `tests.yml` runs `:core:media` only — it does NOT run the new `core:data`
    migration tests; those passed locally.

### B2. Code-review BACKLOG — found but NOT fixed (future releases)
From the harsh full-app review. Priority order roughly as listed.

- **God objects → split.** `PlayerRepositoryImpl` (1853, 7 responsibilities + 9 @Volatile
  fields — the cache-poison bug lived in its seams), `TrackDao` (1879, DAO doing a
  repo's job), `SettingsScreen` (2107) / `HomeScreen` (1973) / `LibraryScreen` (1795)
  god-composables (huge recomposition scope, untestable), `StashPlaybackService` (1309).
- **Concurrency smells.** `runBlocking` still inside OkHttp interceptors
  (`AntraCookieInterceptor.kt:61`, `SquidWtfCaptchaInterceptor.kt:62`) — blocks a network
  thread on DataStore. `lastSavedQueueIds`/`lastSavedShuffle` in `PlayerRepositoryImpl:296-297`
  are plain `var` while 9 siblings are `@Volatile` — resolve the inconsistency (the class's
  true threading model is unclear).
- **Silent failures.** ~424 `runCatching`/`catch(Exception)` sites, ~101 swallow to null
  with NO log — the genre that produces "synced but didn't download / playlist empty"
  reports nobody can diagnose. Audit; every catch-returning-null in the sync/download
  pipeline needs at least a `Log.w` with track/playlist id; re-throw `CancellationException`
  first where broad catches can swallow it.
- **Migration test gaps remain** at 19→20, 22→23, 24→25 (newest two now covered in B1).
- **UI test hole.** 19 ViewModels / 12 tests → 7 untested (incl. Home/Settings VMs where
  mix-gen + sync-toggle logic lives); 2000-line composables untestable at that size.
- **Streaming has NO degradation detection; downloads do.** `DownloadManager` rejects 30s
  preview-stubs and fails over (`DownloadManager.kt:448-465`); the streaming path has no
  equivalent, so a kennyy 30s-stub streams truncated with no failover. Port the duration
  backstop to streaming.
- **Other edge cases to harden:** antra quota-boundary mid-queue has no clear user signal;
  prefetch-vs-background-fill race makes next-up quality non-deterministic; storage-full /
  SAF-permission-revoked mid-download cleanup + user-facing error; token/cookie expiry
  mid-sync can leave half-populated playlists that `snapshotId` then treats as up-to-date;
  yt-dlp nightly auto-update is a single point of failure on an external binary YouTube
  fights (no pinned-fallback-on-canary-failure); `durationMs==0` from bad metadata defeats
  the download duration-backstop exactly when metadata is poor.
- **Credit (don't "fix"):** `DiagnosticsRedactor` token redaction, the download
  duration-backstop + health-gate failover, schema export on, the
  `forceAntraOnly`/`forceYouTubeFallback` outage-drill toggles, user-supplied OAuth creds
  in DataStore. Overall test ratio (213 test / 491 source) is respectable; gaps are
  concentrated in UI + (now-closed) newest migrations.

---

## Pointers / conventions for the next session
- Release procedure (matches v0.9.50/51): bump `versionCode`/`versionName` in
  `app/build.gradle.kts` inside a single `release: vX` commit whose **body** is the
  natural-language notes (release body = tagged-commit message); lightweight tag; push
  master + tag → `release.yml` publishes the APK.
- ⚠️ Local `core:media` has a ~6-min COLD compile before any test runs; do NOT background
  it and park the session waiting, and do NOT `gradlew --stop` between runs (forces another
  cold compile). Verify with targeted `--tests "*Class"` runs; let CI do the full sweep.
- Subagent model on this project: always `opus`.
