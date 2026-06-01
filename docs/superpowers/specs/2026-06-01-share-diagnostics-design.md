# Share Diagnostics — Design

**Status:** Approved
**Date:** 2026-06-01
**Author:** Brainstorm session (Claude + user)

---

## Problem

The dev can't reproduce many of the issues Discord users report (e.g. "YouTube keeps
asking to re-authenticate," downloads silently failing, sync crashing). Getting a raw
`adb logcat` from a non-technical user is impractical — it needs a PC, USB debugging,
and adb. There is no way today for a user to hand over what the app actually did.

We want a one-tap, in-app **"Share diagnostics"** action that exports a redacted,
human-readable diagnostics bundle (recent logs + app/device/auth/sync/queue state) and
shares it via the Android share sheet, so the dev gets exactly the context needed to
debug remotely.

## Goals

- One-tap export from Settings → a **preview screen** showing the exact redacted text →
  Share (share sheet) / Copy / Cancel.
- Capture **recent logs** robustly, including the lead-up to a failure that already
  crashed/restarted the app.
- Bundle the high-value debugging state: auth/connection status, recent sync history
  (with per-service HTTP codes), download-queue + failed downloads, recent crash reports.
- **Never** leak secrets: auth tokens, the Spotify `sp_dc` cookie, the YouTube Music
  cookie, or API keys.
- Reuse the existing crash-reporter / FileProvider / share / Settings plumbing rather
  than rebuilding it.

## Non-Goals

- No auto-upload / telemetry server — the user explicitly shares (share sheet) each time.
- No zip/archive — a single readable `.txt` (easy to paste into Discord, easy to read).
- No in-app log *facade*/Timber migration — logs are harvested from the app's own logcat
  (see Decision 1).
- No remote-config or opt-in analytics.

## Approved Decisions

1. **Log capture = rolling buffer via own-logcat tail to a rotating file.** A background
   daemon tails `logcat --pid=<self>` into `cacheDir/diagnostics/applog.txt` (+ one `.1`
   rotation, ~512 KB each). Chosen over a Timber/log facade because it captures all ~122
   existing `android.util.Log.*` call sites with **zero code churn**, and the file
   survives crashes/restarts so we get the failure lead-up. (Alternative considered:
   snapshot logcat only at export time — rejected because the relevant logs are already
   gone after a crash/restart, which is exactly when these bugs bite.) Captures every
   existing `android.util.Log.*` call site (hundreds across the modules) with zero churn.
2. **Transparency = preview screen before sharing.** Tap → a scrollable screen shows the
   exact redacted bundle text with Share / Copy / Cancel. Chosen for user trust (they see
   no passwords/cookies are included) and store-policy friendliness for sending data
   off-device.

## Reuse Map (existing infrastructure to build on)

- **Crash reporter:** `core/data/.../diagnostics/CrashReporter.kt` (installs an uncaught
  handler from `app/.../StashApplication.kt`) and `CrashFileStore.kt` — saves
  `crash-<ts>.txt` to `cacheDir/crashes/` (rotates to 10), exposes `allCrashFiles()`,
  `latestCrashFile()`, `shareUriFor(file): Uri` (FileProvider), and `formatReport()` with
  the device/app metadata header. **Extract that header into a shared
  `deviceMetadataBlock()`** so the crash report and the diagnostics bundle render an
  identical block. Version gathering via `appVersionInfo()` (handles the `longVersionCode`
  SDK split).
- **Share/FileProvider:** Manifest declares `androidx.core.content.FileProvider`,
  authority `${applicationId}.fileprovider`, `@xml/file_paths`. `file_paths.xml` currently
  exposes only `<cache-path name="crashes" path="crashes/"/>` — **add** a
  `<cache-path name="diagnostics" path="diagnostics/"/>`. The `ACTION_SEND` chooser
  pattern already exists in `SettingsScreen.kt` (~lines 1438-1452): `type="text/plain"`,
  `EXTRA_STREAM`, `EXTRA_SUBJECT`, `FLAG_GRANT_READ_URI_PERMISSION`, `createChooser`, in
  `runCatching`.
- **Settings UI:** `feature/settings/.../SettingsScreen.kt` already has a
  `SectionHeader("Diagnostics")` GlassCard (~line 1406) containing "Share latest crash
  report". **Add "Share diagnostics" as a second entry in that card.**
  `SettingsViewModel.kt` has the mirror pattern: injected `crashFileStore`,
  `latestCrashShareTarget(): CrashShareTarget(file, contentUri)`, `refreshDiagnostics()`.
- **Sync history:** `SyncHistoryEntity` (table `sync_history`) + `SyncHistoryDao`
  (`getRecentSyncs(limit = 20): Flow<List<…>>`). `diagnostics` column = JSON of
  `List<SyncStepResult>` (`service, step, status, itemCount, errorMessage, httpCode`),
  written by `PlaylistFetchWorker`.
- **Auth/connection:** `core/auth/.../TokenManagerImpl` (`spotifyAuthState`,
  `youTubeAuthState: StateFlow<AuthState>`, token expiry via `ServiceToken.isExpired`);
  `core/data/.../sync/auth/AuthHealthProbe` (+ `Youtube`/`Spotify` impls, `isExpired()`);
  `SourceAccountDao.getConnected()`/`getAll()` (`is_connected`, `connected_at`).
- **Downloads:** `DownloadQueueDao.getStatusCounts()`, `getFailedDownloads()`
  (queueId/trackId/title/artist/playlistName/failureType/errorMessage/retryCount),
  `getUnmatchedCount()`. **Blocklist:** `TrackBlocklistDao.observeCount()`.

## Components

### `LogcatCapture` (new, `core/data/.../diagnostics/`)
- `@Singleton`, started once at app init alongside `CrashReporter.install()`.
- Daemon thread: `Runtime.exec(["logcat","-v","threadtime","--pid",<myPid>])`, read lines,
  append to `cacheDir/diagnostics/applog.txt`. On size cap, rotate `applog.txt → applog.1.txt`.
- Resilient: wrap in `runCatching`; if logcat read fails (OEM restriction), log a warning
  and no-op — the bundle omits logs gracefully. Does NOT clear the system buffer.
- Exposes `recentLogs(maxLines): String` (reads the rotation files, returns the tail).

### `DiagnosticsBundleBuilder` (new, `core/data/.../diagnostics/`)
- `suspend fun build(): DiagnosticsBundle` — assembles the sections below (each in its own
  `runCatching`; failure → `[section unavailable: <reason>]`), runs the whole thing through
  `DiagnosticsRedactor`, writes `cacheDir/diagnostics/stash-diagnostics-<ts>.txt`, returns
  `DiagnosticsBundle(text: String, file: File, contentUri: Uri)`. Derive `contentUri` by
  reusing `CrashFileStore.shareUriFor(file)` (or the same authority constant) so there is a
  single source of truth for the FileProvider authority.
- Sections, in order:
  1. **Header** — `deviceMetadataBlock()` (time, app version+code, device, manufacturer,
     Android release+SDK, build).
  2. **Connection / auth** — per service: connected? token expired? expiry timestamp.
     Booleans + timestamps ONLY. No token/cookie/userId values.
  3. **Recent sync history** — last ~10 rows: started/completed, status, trigger,
     counts (checked/found/downloaded/failed), redacted `error_message`, and the parsed
     `SyncStepResult` list (service · step · status · httpCode · itemCount).
  4. **Download state** — queue status counts; last ~20 failed downloads
     (title · artist · playlist · failureType · redacted error · retryCount); unmatched count.
  5. **Misc counts** — blocklist size, storage stats.
  6. **Recent crash reports** — the latest 1–2 `cacheDir/crashes/*.txt` (already formatted).
  7. **Recent logs** — `LogcatCapture.recentLogs(~1500)`.

### `DiagnosticsRedactor` (new, pure, `core/data/.../diagnostics/`)
- `fun redact(text: String): String` — replaces matches with `[REDACTED]`:
  cookies (`sp_dc=…`, `Cookie:`/`Set-Cookie:` values, `__Secure-…`, `SID`/`HSID`/`SSID`/
  `APISID`/`SAPISID`), bearer/`Authorization:` headers, `access_token`/`refresh_token`/
  `id_token` values, `api_key=…`, and long opaque hex/base64 secrets. Backstop only —
  auth state is already reduced to booleans upstream. Heavily unit-tested.

### Preview UI (`feature/settings`)
- New **"Share diagnostics"** entry in the existing Diagnostics card.
- Tap → `SettingsViewModel.buildDiagnosticsBundle()` (IO) → navigate to / show a
  **`DiagnosticsPreviewScreen`**: scrollable monospace `Text` of the redacted bundle, with
  **Share** (`ACTION_SEND` chooser via the bundle's FileProvider `contentUri`, reusing the
  existing pattern), **Copy** (to clipboard), and **Cancel/Back**.
- ViewModel mirrors `CrashShareTarget`: a `DiagnosticsShareTarget(text, file, contentUri)`.

## Data Flow

App start → `LogcatCapture` begins tailing own logcat → file. User opens Settings →
Diagnostics → "Share diagnostics" → `buildDiagnosticsBundle()` reads (`SyncHistoryDao`,
`DownloadQueueDao`, auth state, crash files, log file) → `DiagnosticsRedactor` scrubs →
writes bundle `.txt` → preview screen renders it → Share → FileProvider URI → `ACTION_SEND`.

## Error Handling

- Each bundle section is independently `runCatching`'d; a failure contributes
  `[<section> unavailable: <reason>]` and the bundle still builds.
- `LogcatCapture` failures never crash the app and never block the bundle.
- The Share intent is wrapped in `runCatching` (mirrors the crash-report share).

## Privacy

- Auth is reported as booleans + expiry timestamps; raw tokens/cookies/userIds are never
  read into the bundle. `DiagnosticsRedactor` is a second line of defense over any error
  strings or log lines that might embed secrets. The bundle file lives in app-private
  `cacheDir` until the user explicitly shares it. The preview screen lets the user see
  exactly what leaves the device.

## Testing

- **`DiagnosticsRedactor`** unit tests: one per secret pattern (sp_dc cookie, YouTube
  cookie string, bearer token, refresh_token, api_key, generic base64) → asserts the
  secret is gone and surrounding context preserved.
- **`DiagnosticsBundleBuilder`** unit tests (MockK over the DAOs/probes): assembles all
  sections in order; a throwing source yields `[… unavailable]` rather than failing;
  output passes through the redactor.
- **`LogcatCapture`** unit test: rotation (write past the cap → `.1` created, tail spans
  both); `recentLogs` returns the last N lines.
- Preview screen + share verified on-device.

## Open Questions

None blocking. Copy-to-clipboard is included in the preview (cheap, useful). Exact log
tail size / rotation cap and the precise failed-download count are tunable during
implementation.
