# Radio tuning feedback, overflow-menu consolidation, batch FLAC upgrade — design

**Date:** 2026-07-22
**Status:** Approved by maintainer (this session)
**Related:** shipped separately this session — `54bbe399` fixed the scrollbar grab strip swallowing right-edge taps (`sharePointerInputWithSiblings`), which had made every ⋮ menu on scrollbar-equipped lists dead since #288. This spec covers the three feature changes that remain.

## Goals

1. Tapping the Now Playing radio button gives immediate, truthful feedback — a radar-sweep animation while the station builds, a lock-in moment on success, and per-cause error messages on failure.
2. Every per-track ⋮ in Library opens one consistent, fully wired menu; the Liked tab gains the menu and long-press multiselect it never had.
3. The library can be filtered to non-FLAC tracks and any selection can be batch-upgraded to FLAC through the existing lossless pipeline, running as a cancelable background job.

## Non-goals

- No station-label chip in Now Playing (mockup option C was considered and rejected in favor of the radar sweep).
- No parallelization of `RadioStationGenerator`'s per-candidate YouTube lookups (latency win, but a behavior change — separate follow-up if wanted).
- No persistent "no FLAC available" markers; batch re-runs retry misses fresh.
- Search's dropdown menu stays as-is; only Library's duplicated bottom sheet is consolidated.

---

## 1. Radio radar-sweep feedback

### State model

- `NowPlayingViewModel` gains `radioTuning: StateFlow<Boolean>`, VM-owned: set true before `playerRepository.startRadio(...)`, false when the call returns (either way). Existing `radioSeedLabel != null` remains the "active" signal.
- Taps on the radio slot while `radioTuning` are ignored (no double-start). Content description cycles Start radio / Tuning radio / Stop radio.

### Sealed start result (replaces Boolean)

`PlayerRepository.startRadio` returns `RadioStartResult`:

| Value | Cause today (`PlayerRepositoryImpl.kt`) | User message |
|---|---|---|
| `Started` | first batch spliced (`:717-743`) | none — animation locks |
| `StreamingOff` | streaming guard (`:690`) | "Radio needs Online mode — turn on streaming." |
| `PlayerNotReady` | null controller (`:691`) | "Player is still starting — try again." |
| `NoStation` | empty first batch (`:693`) | "Couldn't find similar tracks for this song." |

The generator (`RadioStationGenerator`) is untouched. `NowPlayingViewModel.startRadioFromCurrent` (`:203-215`) maps the result to `_userMessages`; the existing toast collector renders it.

### Visuals (Now Playing top bar, `NowPlayingScreen.kt:611` slot)

- **Tuning:** a `RadarSweep` composable wraps the icon: a faint full track ring (accent at ~22% alpha) plus a ~40° conic arc rotating at ~1.15s/revolution via `rememberInfiniteTransition`, drawn with Canvas, sized to the existing 34dp slot, colored `npAccent(uiState.vibrantColor)` — same accent already passed to the button. Runs under all themes including AMOLED (icon-local effect; the AMOLED no-op convention in `AmbientBackground` applies to backgrounds, not controls). Cadence/easing from `StashMotion` tokens where applicable.
- **Lock (success):** on `radioTuning` false + active true transition, the arc completes to a full circle at full alpha for ~250ms, then fades out (`animateFloatAsState`), leaving the accent-tinted icon (existing active state).
- **Failure:** sweep stops immediately; icon returns to idle tint; toast per the table above.

### Files

`feature/nowplaying/.../NowPlayingScreen.kt` (TopBar slot + new `RadarSweep` composable, or a small new file in the same package), `NowPlayingViewModel.kt`, `core/media/.../PlayerRepository.kt` + `PlayerRepositoryImpl.kt` (sealed result). `RadioStartResult` lives in `core/model` — it carries no Media3 types, matching the `UpgradeResult` precedent.

### Testing

- VM unit tests: tuning flag lifecycle; result→message mapping (all four values); tap-while-tuning ignored.
- Repo unit test: each failure path returns its distinct result value.
- Animation verified by device smoke (no screenshot assertions).

---

## 2. Overflow-menu consolidation + Liked tab wiring

### TrackOptionsSheet gains Share

`core/ui/.../TrackOptionsSheet.kt` adds an optional `onShare: (() -> Unit)? = null` row (rendered when non-null), using the existing `ShareTrackSheet` flow at call sites. All existing call sites (Album/Artist/Playlist/LikedSongs detail) pass their share hook too — six call sites total after Library lands, one menu everywhere.

### Library Songs tab

`LibraryScreen.kt` TracksTab: the hand-rolled `ModalBottomSheet` (`:1163-1245`) and `BottomSheetActionRow` (`:1313`) are deleted; `selectedTrack` drives the shared `TrackOptionsSheet` instead. Action mapping: Play Next → `viewModel.playNext`, Add to Queue → `viewModel.addToQueue`, Save to Playlist → existing `SaveToPlaylistSheet` (already track-count-agnostic — the caller wires the one selected track, as the detail screens do), Download/Remove → the same single-track download toggle the detail screens use, Share → `trackToShare = track`, Delete → existing `trackToDelete` confirm dialog (keeps the "also block" toggle).

### Liked tab

`LikedTab` rows (`LibraryScreen.kt:624-633`) get real wiring: `onMoreClick = { selectedTrack = track }` (same shared sheet), `onLongPress = { selection.enter(track.id) }`, `selectionActive`/`selected` from the same hoisted `SelectionState`. The selection-actions list and `SelectionScaffoldOverlay` render condition extend from `activeTab == TRACKS` to `TRACKS || LIKED` — the five existing VM batch methods are already track-list-agnostic. Tab switches keep force-clearing selection (existing `:142` behavior). Delete semantics identical to `LikedSongsDetailScreen` (which already exposes Delete via this sheet).

### Testing

- Compose-free unit tests where logic moved (action mapping stays in the screen, so coverage is via existing VM tests — no new frameworks).
- Device smoke: ⋮ on Songs and Liked opens the sheet, every row fires; long-press on Liked enters selection with working bulk bar.

---

## 3. Batch FLAC upgrade

### Non-FLAC filter

- `SourceFilter.NON_FLAC` (`LibraryUiState.kt:59`), chip label "Non-FLAC" in `LibrarySortFilterSheet.kt` chip row.
- `LibraryViewModel` filter branch: `it.isDownloaded && it.fileFormat.lowercase() !in LOSSLESS_CODECS` (constant at `LibraryViewModel.kt:43`).
- Whole-library flow composes: filter Non-FLAC → long-press → Select All → Upgrade to FLAC.

### Selection action + confirmation

- New `SelectionAction("upgrade_flac", "Upgrade to FLAC", …)` in Library's selection actions (Tracks + Liked tabs). Already-FLAC/not-downloaded tracks in the selection are excluded up front and reported in the dialog count.
- Confirmation dialog: "Upgrade N tracks to FLAC? Roughly ~X GB of downloads. Originals are replaced." Estimate = Σ durationMs × 1000 kbps / 8 (FLAC-typical), formatted to one decimal. When estimate > 80% of free space on the download volume (StatFs), append a warning line; the dialog never hard-blocks.

### Queue table

New Room entity `FlacUpgradeQueueEntity` (`flac_upgrade_queue`): `trackId` (PK, FK→tracks CASCADE), `status` (`PENDING/DONE/NO_MATCH/FAILED`), `enqueuedAt`. Confirming the dialog clears any previous batch's rows and inserts the new PENDING set (single-batch semantics), then enqueues the worker (unique work, `ExistingWorkPolicy.KEEP` so confirming while a batch runs appends rows that the live worker drains).

### Worker

`FlacUpgradeWorker` (`data/download`). The drain-rows loop is modeled on `LosslessRetryWorker`; the foreground/notification/cancel machinery is modeled on `TrackDownloadWorker` + `SyncNotificationManager` in `core/data/sync` (`LosslessRetryWorker` is a plain one-shot worker with none of that plumbing — don't under-scope from it):

- Foreground `CoroutineWorker`; notification "Upgrading to FLAC · done+skipped/total" with cancel action; cancel clears remaining PENDING rows.
- Network constraint: `UNMETERED` when `SyncPreferencesManager.wifiOnly` (default true) else `CONNECTED`.
- Loop: drain PENDING rows one at a time → `losslessUpgrader.upgradeToLossless(track)` (existing forced-resolve → download → tag → swap → delete-old pipeline; `AggregatorRateLimiter` and the qbdlx/amz token-pool and captcha-herd protections throttle automatically). Map `UpgradeResult.Upgraded/NoMatch/Error` → `DONE/NO_MATCH/FAILED`. `CancellationException` re-thrown before generic catch (worker-catch rule).
- Completion: summary notification "N upgraded · M no FLAC found · K failed"; same text as a snackbar if the app is foreground. NO_MATCH/FAILED rows linger only because cleanup is deferred to the next batch's clear-and-insert — nothing reads them and nothing re-runs automatically (no review UI; deliberate).

### Testing

- DAO tests: queue transitions, clear-and-insert semantics.
- Worker test with fake `LosslessUpgrader` returning a mixed result sequence: verifies per-row status writes, summary counts, cancellation clearing PENDING.
- VM test: NON_FLAC filter branch; estimate math (duration-based, one known input → known GB).
- Device smoke: 2–3-track batch end to end on the real pipeline.

---

## Rollout

Single release. Implementation order: §2 (smallest, immediately user-visible), §1, §3 — the order matters in one place: §3's "Upgrade to FLAC" selection action on the Liked tab depends on §2 wiring Liked-tab selection first. Each section is otherwise independently shippable; nothing here migrates data except the additive `flac_upgrade_queue` table (Room schema version bump + additive migration).
