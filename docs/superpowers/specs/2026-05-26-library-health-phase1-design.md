# Library Health Phase 1 — Failed Downloads Viewer + Auth Probe + Opus Cover Art

**Release target:** v0.9.38
**Status:** Design
**Closes (full or partial):** #88, #95, plus diagnostic deflection for #71, #29, #34, #27, #21, #50

---

## Goal

Give users visibility — and one-tap recovery — for every download failure that isn't a "no match found" case. Today the only signal a user gets when downloads stall is "fewer songs than I expected." This spec ships:

1. A **Failed Downloads** viewer (Sync tab sibling card), grouped by failure reason, with per-row Retry and Block.
2. An **auth-expiry probe** that runs at sync start and surfaces an amber re-authenticate banner before the user has to triage mystery failures.
3. The **Opus `METADATA_BLOCK_PICTURE`** fix from #95, bundled in the same release.

The existing `FailedMatchesScreen` (no-match lane) and `BlockedSongsScreen` stay as-is and unchanged. This spec is strictly additive to those surfaces.

---

## Non-goals (Phase-1 scope lock)

To prevent scope creep, these are explicitly **out**:

- Per-playlist health badges.
- Failure trend analytics or charts.
- Auto-heal (silent background re-auth).
- Notification when failures occur outside an open app session.
- Surfacing `WAITING_FOR_LOSSLESS` rows in this viewer (they have their own retry worker).
- Refactoring `FailedMatchesScreen` or merging it into Library Health.
- Sync-progress overhaul.

Anything in this list is a Phase 2 candidate and gets its own spec.

---

## 1. Failure taxonomy

### 1.1 Enum expansion

Current `DownloadFailureType` (in `core/model/.../DownloadFailureType.kt`):

```kotlin
enum class DownloadFailureType { NONE, NO_MATCH, DOWNLOAD_ERROR }
```

New values land alongside the existing two, and `DOWNLOAD_ERROR` is renamed to `UNKNOWN` (semantically: "we don't know yet" rather than "we know it broke"). Final:

```kotlin
enum class DownloadFailureType {
    NONE,                 // not a failure
    NO_MATCH,             // existing — owned by FailedMatchesScreen, untouched
    AUTH_EXPIRED,         // 401/403/captcha-redirect on a connected source
    NETWORK,              // timeouts, DNS, no connectivity, socket reset
    PROVIDER_UNAVAILABLE, // region block, deleted video, registry says "no source"
    FFMPEG_ERROR,         // post-processing failure (codec, mux, attached_pic)
    STORAGE_ERROR,        // SAF unreachable, disk full, permission revoked
    UNKNOWN,              // catchall — classifier didn't recognize the pattern
}
```

**Why these specific buckets:** each one maps to a distinct user-facing fix path. Auth → re-authenticate. Network → wait + retry. Provider → manual search or block. ffmpeg → retry, escalate if persistent. Storage → re-pick folder. Unknown → retry, send logs.

**Why drop the earlier `not_in_source` bucket:** it was a confused concept that collapses cleanly into NO_MATCH (matching layer) or PROVIDER_UNAVAILABLE (download layer) depending on where the failure surfaced.

### 1.2 Why a hard rename instead of additive

Keeping `DOWNLOAD_ERROR` as a legacy value alongside `UNKNOWN` would create two synonymous values and force every query and UI element to handle both. The rename is honest: existing rows really are "we don't know what specifically broke" — same semantic as the new `UNKNOWN`.

### 1.3 DB migration (v28 → v29)

`StashDatabase` is currently at `version = 28`. `MIGRATION_28_29`:
```sql
UPDATE download_queue
   SET failure_type = 'UNKNOWN'
 WHERE failure_type = 'DOWNLOAD_ERROR';
```

`failure_type` is `TEXT NOT NULL DEFAULT 'NONE'` (already established by an earlier migration), so the schema itself doesn't change — only data. No backfill of finer buckets for historical rows: they get `UNKNOWN` until they're retried, then the new classifier assigns the right bucket on the next failure.

---

## 2. DownloadFailureClassifier

### 2.1 Interface

```kotlin
// data/download/.../classifier/DownloadFailureClassifier.kt
class DownloadFailureClassifier @Inject constructor() {

    /**
     * Classify a raw failure into a [DownloadFailureType] bucket. Pure
     * function — no IO, no state. Phase context matters because the
     * same HTTP status carries different meaning at different phases
     * (a 401 during MATCHING is a Spotify cookie issue; during
     * DOWNLOADING it's a YouTube cookie issue).
     */
    fun classify(context: FailureContext): DownloadFailureType
}

data class FailureContext(
    val phase: DownloadPhase,            // MATCHING, DOWNLOADING, PROCESSING, TAGGING
    val errorText: String?,              // raw error_message field
    val httpStatus: Int? = null,
    val causeChain: List<String> = emptyList(),  // exception type names
)

enum class DownloadPhase { MATCHING, DOWNLOADING, PROCESSING, TAGGING, STORAGE }
```

### 2.2 Pattern rules (Phase 1 starter set)

Ordered — first match wins:

| Order | Match | Type |
|---|---|---|
| 1 | `httpStatus in 401..403` OR `errorText` contains `"login required"`, `"sign in"`, `"captcha"`, `"403 Forbidden"` | `AUTH_EXPIRED` |
| 2 | `errorText` contains `"unable to extract"`, `"video unavailable"`, `"private"`, `"removed"`, `"region"`, `"copyright"` | `PROVIDER_UNAVAILABLE` |
| 3 | `errorText` contains `"timed out"`, `"timeout"`, `"unreachable"`, `"no address"`, `"reset by peer"`, `"connection"` OR `causeChain` includes `SocketTimeoutException`, `UnknownHostException`, `ConnectException` | `NETWORK` |
| 4 | `phase == PROCESSING` AND (`errorText` contains `"ffmpeg"`, `"exit code"`, `"mux"`, `"codec"`) | `FFMPEG_ERROR` |
| 5 | `phase == STORAGE` OR `errorText` contains `"ENOSPC"`, `"permission denied"`, `"SAF"`, `"no such file"` | `STORAGE_ERROR` |
| 6 | else | `UNKNOWN` |

### 2.3 Diagnostic logging for `UNKNOWN`

Every classifier call that returns `UNKNOWN` logs `Log.w(TAG, "UNKNOWN failure: phase=$phase httpStatus=$httpStatus text=$errorText")`. Lets me iterate the pattern table release-over-release without flying blind. No PII concerns — these messages are technical strings, not user content.

### 2.4 Wiring

`TrackDownloadWorker` (~lines 368, 381, 401, 451, 477 — current `errorMessage = …` call sites) gets a single classify-then-write wrapper:

```kotlin
private suspend fun markFailed(
    queueId: Long,
    phase: DownloadPhase,
    errorText: String?,
    httpStatus: Int? = null,
    cause: Throwable? = null,
) {
    val type = classifier.classify(
        FailureContext(
            phase = phase,
            errorText = errorText,
            httpStatus = httpStatus,
            causeChain = generateSequence(cause) { it.cause }
                .map { it::class.java.simpleName }.toList(),
        )
    )
    downloadQueueDao.markFailed(queueId, errorMessage = errorText?.take(1000), failureType = type)
}
```

`DownloadQueueDao.markFailed` is a new method that updates `status`, `error_message`, and `failure_type` in one statement.

---

## 3. Failed Downloads viewer

### 3.1 Placement

Sync tab, new card row alongside the existing **Unmatched Songs** and **Blocked Songs** entries. Card shows: icon + label "Failed Downloads" + count badge.

Tapping navigates to `FailedDownloadsScreen`.

### 3.2 Screen layout

```
┌─────────────────────────────────────────┐
│ ←  Failed Downloads        [Retry all]  │   ← header (count + bulk action)
│    14 tracks couldn't download          │
├─────────────────────────────────────────┤
│ 🔑 Sign-in expired              8 ▾    │   ← group header (collapsible)
│   ┌─────────────────────────────────┐   │
│   │ 🎵 Olivia Dean — Dive            │   │   ← row
│   │   Liked Songs · 401 Unauthorized │   │
│   │                  [Retry] [Block]  │   │
│   └─────────────────────────────────┘   │
│   …                                     │
│ 📡 Network errors               3 ▾    │
│ ⚙️ Encoding errors              1 ▾    │
│ 📁 Storage unreachable          2 ▾    │
└─────────────────────────────────────────┘
```

Group ordering (high-leverage first, hidden when count is 0):

1. `AUTH_EXPIRED`
2. `STORAGE_ERROR`
3. `NETWORK`
4. `PROVIDER_UNAVAILABLE`
5. `FFMPEG_ERROR`
6. `UNKNOWN`

Each group is collapsible. All groups expanded by default for the first render; collapse state is screen-local (not persisted — UI sugar only).

### 3.3 Row content

- Album art (track's existing art if known, gradient placeholder otherwise — matches `UnmatchedTrackRow` style).
- Line 1: `title — artist` (1 line, ellipsis).
- Line 2: `<source playlist name> · <short reason>` where short reason is the human-friendly enum label, not the raw error.
- Tap to expand → reveals raw `error_message` (full text) + the failed-attempt count from `download_queue.attempts`.
- Trailing buttons: **Retry**, **Block**.

### 3.4 Actions

- **Retry (per row):** atomic `UPDATE download_queue SET status='PENDING', error_message=NULL, failure_type='NONE' WHERE id=? AND status='FAILED'`, then enqueue a single-track `TrackDownloadWorker` immediately. Loses race with a concurrent sync → noop (the predicate fails). Row stays on screen with a spinner; on completion the reactive flow removes it.
- **Block (per row):** calls `BlocklistGuard.block(track)` (existing v0.9.15 infra). Row disappears from this viewer AND appears in the existing Blocked Songs viewer.
- **Retry all (header):** iterates the same per-row retry path. Existing download semaphore caps concurrency, so even 50 rows fan out safely.
- **Retry group (group header):** same as Retry all but scoped to the group's tracks.

### 3.5 Empty state

Same tone as `FailedMatchesScreen`'s "All caught up!" — large neutral icon + "No failed downloads" + "Everything synced cleanly."

### 3.6 DAO

Extend `DownloadQueueDao` rather than create a new DAO:

```kotlin
data class FailedDownloadRow(
    val queueId: Long,
    val trackId: Long,
    val title: String,
    val artist: String,
    val albumArtUrl: String?,
    val playlistName: String?,        // first sync-enabled playlist containing the track
    val failureType: DownloadFailureType,
    val errorMessage: String?,
    val retryCount: Int,              // download_queue.retry_count — existing column
    val completedAt: Long?,           // failure time — completed_at is stamped on terminal status writes
)

@Query("""
    SELECT dq.id AS queueId, t.id AS trackId, t.title, t.artist,
           t.album_art_url AS albumArtUrl,
           (SELECT p.name FROM playlists p
             INNER JOIN playlist_tracks pt ON pt.playlist_id = p.id
             WHERE pt.track_id = t.id AND pt.removed_at IS NULL
             ORDER BY p.id ASC LIMIT 1) AS playlistName,
           dq.failure_type AS failureType,
           dq.error_message AS errorMessage,
           dq.retry_count AS retryCount,
           dq.completed_at AS completedAt
      FROM download_queue dq
      INNER JOIN tracks t ON t.id = dq.track_id
     WHERE dq.status = 'FAILED'
       AND dq.failure_type != 'NONE'
       AND dq.failure_type != 'NO_MATCH'
     ORDER BY COALESCE(dq.completed_at, dq.created_at) DESC
""")
fun getFailedDownloads(): Flow<List<FailedDownloadRow>>
```

`download_queue` has no `updated_at` column — terminal status writes stamp `completed_at`, which doubles as "when did this fail" for FAILED rows. `retry_count` is the existing attempt counter. `playlistName` subquery picks the first sync-enabled playlist arbitrarily (LIMIT 1) — for tracks in multiple playlists, only one shows.

`NO_MATCH` is explicitly excluded from this viewer (it lives in `FailedMatchesScreen`).

#### Atomic claim helpers

```kotlin
/** Atomic single-row claim. Returns true iff the row was FAILED at claim time. */
@Query("""
    UPDATE download_queue
       SET status = 'PENDING', error_message = NULL, failure_type = 'NONE'
     WHERE id = :queueId AND status = 'FAILED'
""")
suspend fun atomicallyClaimForRetry(queueId: Long): Int   // Room returns affected-row count; >0 = claimed

/** Group claim. Returns the queue ids that were claimed (so the VM can enqueue them). */
@Transaction
suspend fun atomicallyClaimGroupForRetry(type: DownloadFailureType): List<Long> {
    val ids = selectFailedIdsByType(type)
    if (ids.isNotEmpty()) resetToPending(ids)
    return ids
}

@Query("SELECT id FROM download_queue WHERE status = 'FAILED' AND failure_type = :type")
suspend fun selectFailedIdsByType(type: DownloadFailureType): List<Long>

@Query("SELECT id FROM download_queue WHERE status = 'FAILED' AND failure_type NOT IN ('NONE', 'NO_MATCH')")
suspend fun selectAllNonMatchFailedIds(): List<Long>

@Query("UPDATE download_queue SET status='PENDING', error_message=NULL, failure_type='NONE' WHERE id IN (:ids)")
suspend fun resetToPending(ids: List<Long>)

/** All-rows claim. Same idempotency story as the group variant. */
@Transaction
suspend fun atomicallyClaimAllForRetry(): List<Long> {
    val ids = selectAllNonMatchFailedIds()
    if (ids.isNotEmpty()) resetToPending(ids)
    return ids
}

/** Write a classified failure in one shot from the worker. */
@Query("""
    UPDATE download_queue
       SET status = 'FAILED',
           error_message = :errorMessage,
           failure_type = :failureType,
           completed_at = :completedAt
     WHERE id = :queueId
""")
suspend fun markFailed(
    queueId: Long,
    errorMessage: String?,
    failureType: DownloadFailureType,
    completedAt: Long = System.currentTimeMillis(),
)
```

The single-row variant uses Room's affected-row return; the batch variants return the claimed ids so the ViewModel can enqueue them through `SingleTrackDownloadEnqueuer` (§3.8). The race story holds either way: a row only gets claimed if it's still `FAILED` at the moment the `UPDATE` runs.

### 3.7 ViewModel

```kotlin
@HiltViewModel
class FailedDownloadsViewModel @Inject constructor(
    private val downloadQueueDao: DownloadQueueDao,
    private val trackDao: TrackDao,
    private val downloadEnqueuer: SingleTrackDownloadEnqueuer,  // new — see §3.8
    private val blocklistGuard: BlocklistGuard,
) : ViewModel() {

    val uiState: StateFlow<FailedDownloadsUiState> =
        downloadQueueDao.getFailedDownloads()
            .map { rows -> groupAndOrder(rows) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FailedDownloadsUiState.Loading)

    fun retry(queueId: Long) = viewModelScope.launch {
        val rowsAffected = downloadQueueDao.atomicallyClaimForRetry(queueId)
        if (rowsAffected > 0) downloadEnqueuer.enqueue(queueId)
    }

    fun retryGroup(type: DownloadFailureType) = viewModelScope.launch {
        downloadQueueDao.atomicallyClaimGroupForRetry(type)
            .forEach { downloadEnqueuer.enqueue(it) }
    }

    fun retryAll() = viewModelScope.launch {
        downloadQueueDao.atomicallyClaimAllForRetry()
            .forEach { downloadEnqueuer.enqueue(it) }
    }

    fun block(trackId: Long) = viewModelScope.launch {
        val track = trackDao.getById(trackId) ?: return@launch
        blocklistGuard.block(track, BlockSource.FAILED_DOWNLOADS)
    }
}
```

A new `BlockSource.FAILED_DOWNLOADS` enum value is added in `BlocklistGuard.kt` (the existing enum already has `NOW_PLAYING, CONTEXT_MENU, PLAYLIST_DELETE, MIGRATION_V19, INTEGRITY_WORKER, OTHER`). The ViewModel resolves `TrackEntity` from the trackId before calling `block(track, source)` — that matches the existing `BlocklistGuard.block` signature exactly. No new convenience wrapper on `BlocklistGuard` (the resolve-then-call pattern is local to this VM).

### 3.8 Single-track download path

`SingleTrackDownloadEnqueuer` is a thin wrapper that enqueues a one-shot `TrackDownloadWorker` for a specific `queueId` rather than the full sync pipeline. The worker already supports being run with a `queueId` input; we just need a one-row variant of the existing entrypoint.

---

## 4. Auth-expiry probe + banner

### 4.1 Probe interface

```kotlin
// data/download/.../auth/AuthHealthProbe.kt
interface AuthHealthProbe {
    suspend fun isExpired(): Boolean
    val source: AuthSource
}

enum class AuthSource { SPOTIFY, YOUTUBE }
```

Two implementations:

- **`SpotifyAuthHealthProbe`** — hits `GET /v1/me` with the stored access token. 401 → expired. Network failure → not expired (treat as "we don't know," conservative).
- **`YoutubeAuthHealthProbe`** — calls the cheapest authenticated YT endpoint already wired into the codebase. **Exact endpoint deferred to writing-plans** — it depends on which YT auth surface the scrobbler / playlist fetcher uses, and that needs a code read I'd rather do during plan-writing.

### 4.2 Probe invocation

The sync chain is `PlaylistFetchWorker → DiffWorker → TrackDownloadWorker → SyncFinalizeWorker`, scheduled by `SyncScheduler` as a unique work chain (`stash_daily_sync`). `SyncStateManager` already exposes an `onAuthenticating()` phase that maps perfectly to where the probe runs.

The probe lives at the head of `PlaylistFetchWorker`, before any playlist fetch IO:

1. `syncStateManager.onAuthenticating()`
2. For each connected source, call its `AuthHealthProbe.isExpired()` in parallel.
3. Always call `syncStateManager.onAuthExpiryProbed(state)` once the probes resolve. If `state.anyExpired` is true, return `Result.failure()` so the chain short-circuits — no diff, no download, run aborts cleanly.
4. Otherwise: proceed to `syncStateManager.onFetchingPlaylists()` and the existing fetch path.

#### 4.2.1 SyncStateManager additions

```kotlin
data class AuthExpiryState(
    val spotifyExpired: Boolean,
    val youtubeExpired: Boolean,
) {
    val anyExpired: Boolean get() = spotifyExpired || youtubeExpired
}

// New on SyncStateManager:
private val _authExpiry = MutableStateFlow(AuthExpiryState(false, false))
val authExpiry: StateFlow<AuthExpiryState> = _authExpiry.asStateFlow()

fun onAuthExpiryProbed(state: AuthExpiryState) { _authExpiry.value = state }
```

The banner subscribes to `authExpiry`. A successful re-auth (handled by the existing connector flow) clears the flag for that source — the banner disappears with no extra plumbing.

### 4.3 Banner

Component name: `AuthExpiredBanner`. Lives above `SyncStatusCard` at the top of the Sync tab.

Visibility: derived purely from `AuthExpiryState.anyExpired`. No dismiss state — when the user re-auths, the state flips, the banner disappears. No "X to dismiss," because dismissing a live problem hides info.

Copy (single CTA, per locked decision 1A):

| State | Headline | Body | Button |
|---|---|---|---|
| Spotify only | "Spotify session expired" | "Sync paused — re-authenticate to keep your downloads flowing." | "Re-authenticate Spotify" |
| YouTube only | "YouTube session expired" | "Sync paused — re-authenticate to keep your downloads flowing." | "Re-authenticate YouTube" |
| Both | "Sign-ins expired" | "Both Spotify and YouTube need fresh sign-ins to resume sync." | "Re-authenticate" (deep-links to Connections settings) |

Color: amber (`extendedColors.warningContainer` or theme equivalent) matching the project's existing amber banner pattern (per `feedback_stash_design_system.md`).

### 4.4 Mid-sync expiry handling

Locked decision is trigger model 1 (probe at start only). But the classifier in §2 catches mid-run 401/403 as AUTH_EXPIRED, so a sync that started healthy but had cookies expire mid-run still produces correctly-bucketed rows for the viewer. The banner doesn't appear until the *next* sync's start-probe — accepted trade-off.

---

## 5. Opus cover art (#95)

Implementation is fully scoped in #95's issue body. Summary for this spec:

- `MetadataEmbedder.kt` — `OPUS_OGG_EXTENSIONS` branch:
  - Build a FLAC Picture metadata block in memory: `picture_type=3` (front cover), MIME `image/jpeg`, dimensions parsed from the JPEG header, raw JPEG bytes.
  - Base64-encode.
  - Pass `-metadata METADATA_BLOCK_PICTURE=<base64>` to ffmpeg instead of the current `-i artFile -map 1:0 -disposition:v:0 attached_pic` argv (which ffmpeg refuses to mux into Ogg).
- Test update: `MetadataEmbeddingIntegrationTest.embedsTagsButNotArtIntoOpus` renamed to `embedsTagsAndArtIntoOpus`; assertion flips from `embeddedPicture == null` to `embeddedPicture != null`.
- Closes #95 directly. No DB or UI impact.

This ships in v0.9.38 as a separate PR from the Failed Downloads + Auth Probe work for clean review boundaries.

---

## 6. Module + file layout

```
core/model/
  └── DownloadFailureType.kt              # enum expansion

core/data/
  ├── db/StashDatabase.kt                  # version bump 28 → 29, MIGRATION_28_29
  └── db/dao/DownloadQueueDao.kt           # +getFailedDownloads(), +atomic claim helpers, +markFailed()

data/download/
  ├── classifier/DownloadFailureClassifier.kt   # new — pure classifier
  ├── classifier/FailureContext.kt              # new — context data class
  ├── auth/AuthHealthProbe.kt                   # new — sealed interface
  ├── auth/SpotifyAuthHealthProbe.kt            # new
  ├── auth/YoutubeAuthHealthProbe.kt            # new
  ├── single/SingleTrackDownloadEnqueuer.kt     # new — one-row entrypoint
  └── files/MetadataEmbedder.kt                 # Opus branch rewrite (#95)

core/data/sync/
  ├── workers/TrackDownloadWorker.kt       # wire classifier at every errorMessage = … site
  ├── workers/PlaylistFetchWorker.kt       # +AuthHealthProbe invocation at chain head
  └── SyncStateManager.kt                  # +authExpiry: StateFlow<AuthExpiryState>, +onAuthExpiryProbed()

feature/sync/
  ├── FailedDownloadsScreen.kt             # new — composable
  ├── FailedDownloadsViewModel.kt          # new — VM
  ├── components/FailedDownloadsGroupCard.kt    # new — collapsible group
  ├── components/AuthExpiredBanner.kt           # new
  ├── FailureReasonDisplay.kt              # new — icon + color + copy per enum value
  └── SyncScreen.kt                        # +navigation entry for FailedDownloads, +AuthExpiredBanner mount

app/
  └── navigation/StashNavGraph.kt          # +FailedDownloadsScreen route
```

---

## 7. Testing

### 7.1 Unit tests

- `DownloadFailureClassifierTest` — every pattern row in §2.2 gets at least one positive and one negative test. Phase-sensitivity has explicit cases (401 at MATCHING vs DOWNLOADING).
- `DownloadQueueDaoTest` — `getFailedDownloads()` returns expected groups; `atomicallyClaimForRetry` is a no-op when status isn't FAILED (race protection).
- `FailedDownloadsViewModelTest` — Retry path calls enqueuer iff atomic claim succeeded; Block path calls BlocklistGuard.

### 7.2 Migration test

`StashDatabaseMigrationTest.MIGRATION_28_29_rewrites_DOWNLOAD_ERROR_to_UNKNOWN` — seed v28 with a row carrying `failure_type='DOWNLOAD_ERROR'`, run migration, assert it's `UNKNOWN`.

### 7.3 Integration test

`MetadataEmbeddingIntegrationTest.embedsTagsAndArtIntoOpus` (renamed) — full embed → `MediaMetadataRetriever` round-trip, assert `embeddedPicture != null` and tag set is intact.

### 7.4 Manual on-device smoke

After each PR lands, install on real device (per `feedback_install_after_fix.md` — compile pass isn't enough):

- Failed Downloads card visible in Sync tab with correct count.
- Tap → screen renders, groups expand/collapse.
- Force an AUTH_EXPIRED (revoke Spotify token externally) → banner appears at next sync, single-CTA deep-links to Connections.
- Tap Retry on a row → spinner → row removed (or returns with refreshed error on real failure).
- Tap Block → row removed AND appears in Blocked Songs.
- Opus download → file art readable in Plex / Foobar / Symfonium.

---

## 8. Risk register

| Risk | Mitigation |
|---|---|
| Classifier mis-buckets common failures, dumping everything in UNKNOWN. | Every UNKNOWN logs raw error text to logcat; iterate patterns each release. |
| Banner flickers if probe is slow vs. mid-sync recovery. | Probe runs in parallel for both sources at sync start only; state is derived, not persisted; no dismiss state. |
| Retry race: user taps Retry while background sync also grabs the row. | Atomic `UPDATE … WHERE status='FAILED'` predicate — second writer noops. |
| Long raw yt-dlp messages overflow the row UI. | Show short enum label in row body; raw `error_message` lives in the tap-to-expand details. |
| Migration regression for existing users with 100s of DOWNLOAD_ERROR rows. | Single `UPDATE` statement, no schema change; covered by migration test. |
| Opus art rewrite regresses M4A/FLAC paths. | Existing integration tests for M4A and FLAC stay green; only the Opus branch changes. |

---

## 9. Acceptance criteria

- v0.9.38 ships with a Failed Downloads card in the Sync tab. Tapping it lands on a grouped viewer (6 buckets) with per-row Retry + Block.
- Every TrackDownloadWorker failure path routes through `DownloadFailureClassifier` and writes a typed `failure_type` to `download_queue`.
- DB migration v28 → v29 converts every existing `DOWNLOAD_ERROR` row to `UNKNOWN`.
- Auth probe runs at every sync start; sync aborts cleanly if any connected source is expired; `AuthExpiredBanner` appears above `SyncStatusCard` until the user re-auths.
- Opus downloads carry an embedded front-cover picture readable by Plex / Foobar / Symfonium / car stereo. Integration test passes.
- Per-row tap-to-expand reveals the raw error and the failed-attempt count.
- Empty state copy matches the established "All caught up" tone.
- Manual on-device smoke test passes against the matrix in §7.4.

---

## 10. Out-of-scope follow-ups (Phase 2 candidates)

- Per-playlist health badges.
- Notification when failures occur in background.
- Auto-heal silent re-auth on app open.
- Surfacing `WAITING_FOR_LOSSLESS` rows alongside failed downloads.
- Failure trend charts in Settings.
- Bulk re-pick folder action for STORAGE_ERROR rows.

Each gets its own brainstorm and spec when prioritized.
