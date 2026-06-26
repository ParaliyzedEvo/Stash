# ArcodSource — arcod.xyz lossless source — Design

**Date:** 2026-06-16
**Status:** Design (approved in brainstorm; pending spec review)
**Branch:** `feat/arcod-source` (off master @ v0.9.53)

## Summary

Add `arcod.xyz` as Stash's 3rd lossless source — **download + streaming** of clear
FLAC. Slots **after** kennyy/squid and **before** the YouTube fallback in both the
download (`LosslessSourceRegistry`) and streaming (`StreamSourceRegistry`) chains.

ARCOD is a Qobuz-DL proxy run by a **different operator** than kennyy/squid, so it
is **uptime/outage redundancy** for the same Qobuz catalog (not a new catalog).
When kennyy and squid both miss or degrade, ARCOD can still serve clear lossless.

**Verified working (2026-06-16):** downloaded files byte-checked = magic `fLaC`
(clear hi-res FLAC, e.g. 43 MB 24-bit). No DRM. This is the verification bar amz
failed (amz = Widevine CENC); ARCOD passes it.

## Verified API (from a real authenticated HAR, 2026-06-16)

Two hosts: `arcod.xyz` (Next.js app + `/api/*`) and `dl.arcod.xyz` (file CDN).
Supabase project ref: `fnlghyzwyoklfqyhqlav`.

### Auth — Supabase JWT via Google OAuth
- The web app authenticates with a **Supabase session** obtained through Google
  OAuth (`fnlghyzwyoklfqyhqlav.supabase.co/auth/v1/authorize?provider=google` →
  `…/callback` → `…/auth/v1/user`). The Supabase JS client stores the session in
  **localStorage** (key `sb-fnlghyzwyoklfqyhqlav-auth-token`: `access_token`,
  `refresh_token`, `expires_at`).
- Authenticated `/api/*` calls send **`Authorization: Bearer <access_token>`**
  (confirmed in the app JS: `authorization:`bearer``, `access_token`,
  `getSession`; the HAR globally redacted the literal header value).
- Access-token JWT expires (~1 h). Refresh via
  `POST fnlghyzwyoklfqyhqlav.supabase.co/auth/v1/token?grant_type=refresh_token`
  with header `apikey: <supabase anon key>` (the anon key is a public JWT present
  in the HAR's supabase request headers) and body `{"refresh_token":"…"}` →
  returns a fresh `access_token` + `refresh_token` + `expires_at`.

### Catalog — open (no auth)
- `GET arcod.xyz/api/get-music?q=<query>&offset=0` → `{success, data:{albums,
  tracks:{items:[…]}}}`. Each `tracks.items[i]` carries `id` (Qobuz track id),
  `album` (with `album.id`), `isrc`, `title`, `performer`/`performers`,
  `duration`, `maximum_bit_depth`, `hires`, `streamable`, etc. **No album
  expansion needed** — search returns everything the matcher + job need.

### Job-based download — Bearer auth
- `POST arcod.xyz/api/v2/downloads` body (verbatim shape):
  `{"albumId","trackId","albumTitle","artistName","artistId","coverUrl","releaseDate","tracksCount":1,"quality":27,"format":"FLAC","bitrate":320,"embedLyrics":false,"lyricsMode":"none","downloadBooklet":false,"attachCover":false,"zipName":"{artists} - {name}","trackName":"{track} - {name}"}`
  → `{"id":"<jobid>","status":"pending",…}`.
- Poll `GET arcod.xyz/api/v2/downloads/<jobid>` → `{"id","status","progress",
  "description","error","fileName","fileSize","downloadUrl"}`. `status` goes
  `pending → … → "completed"` (terminal: `completed`, or `error != null`). **On
  `completed`, the response itself contains `downloadUrl`.**
- (Optional) `POST arcod.xyz/api/v2/downloads/<jobid>/url` returns a fresh
  `{"downloadUrl","fileName","expiresIn":300}` for a completed job.
- `GET https://dl.arcod.xyz/downloads/<jobid>/<file>.flac` → `audio/flac`, **open
  (no auth header), `accept-ranges: bytes`**, URL valid ~300 s. Clear FLAC bytes.

### Rate limit
- Guest: 50 downloads/hr per IP (the saturated-shared-IP wall). A signed-in
  account lifts this. `api.arcod.xyz/v2/rate-limit-status` reports state.

## Architecture & components

New package `data/download/.../lossless/arcod/` (download side) + a stream
resolver in `core/media`, mirroring the antra/amz/qobuz layout. Each unit has one
responsibility.

| Component | Responsibility | Mirrors |
|---|---|---|
> **Mirrors column note:** the antra components (`AntraConnectScreen`,
> `AntraCredentialStore`, `AntraClient`, `AntraSource`, `AntraStreamResolver`) were
> **deleted from the tree in v0.9.53** — they are **git-history references**
> (recoverable via `git show <pre-removal-sha>:<path>`), not extant files to copy.
> The patterns are sound and reconstructable; just don't expect the files to exist.

| Component | Responsibility | Pattern source |
|---|---|---|
| `ArcodConnectScreen` (WebView) | Opens `arcod.xyz`; user does Google login; on success harvests the Supabase session from **localStorage** (`sb-…-auth-token`) → access/refresh/expires. Requires `WebSettings.domStorageEnabled = true` (localStorage is OFF by default in Android WebView) — harvest via `evaluateJavascript("localStorage.getItem('sb-…-auth-token')")`. | antra `AntraConnectScreen` (history) — cookie harvest → localStorage harvest |
| `ArcodCredentialStore` (DataStore) | Persists `accessToken`, `refreshToken`, `expiresAtMs`; `isConnected` = non-blank access token; `markStale()` clears on refresh failure. | antra `AntraCredentialStore` (history) |
| `ArcodTokenRefresher` | `POST supabase…/token?grant_type=refresh_token` (apikey + refresh_token) → new tokens; persists them. Single-flight. | new (Supabase-specific) |
| `ArcodAuthInterceptor` | Host-scoped (`arcod.xyz` + `api.arcod.xyz` only); attaches `Authorization: Bearer <access_token>` (refreshing first if expired via `ArcodTokenRefresher`); on a 401 re-mints once and retries. **NOT installed on the shared client.** Added ONLY to a **derived** OkHttpClient built inside `ArcodClient` (`sharedClient.newBuilder().addInterceptor(arcodAuth).build()`) — exactly how `QobuzApiClient`/`KennyyApiClient` scope their host interceptors, which keeps the Bearer token off every other app HTTP call and avoids a `:core:network` change/cycle. (The host check is belt-and-suspenders since only arcod calls use this client.) | `QobuzApiClient`'s derived-client + a single-flight token interceptor |
| `ArcodApiModels` | `@Serializable` DTOs: search (`get-music`), job create/status (`downloads`), url. | `QobuzApiModels` |
| `ArcodClient` | Builds the derived (interceptor-bearing) client off the shared `OkHttpClient`; `search(query)` (open — no interceptor needed but harmless), `createJob(track)`, `pollStatus(jobId)` (until `completed`/`error`, bounded), `downloadUrl(jobId)` (from completed status or POST /url). | `QobuzApiClient` |
| `ArcodMatcher` | Pure scorer over `get-music` track items (artist+title; ISRC + duration confirm). Returns the best track (id + album.id + metadata). | the (amz, history) matcher / `MatchScorer` |
| `ArcodSource : LosslessSource` | `id="arcod"`; resolve = gate → search → match → createJob → poll → `SourceResult(downloadUrl, format flac/0/0/0, …)`. | `QobuzSource` |
| `ArcodStreamResolver` | `resolve(track)` → same job flow → `StreamUrl(url=dl, origin="arcod")`. Plays via the **default** media-source factory (open + Range URL, like kennyy/squid) — NO custom data source, NO routing branch. | `QobuzStreamResolver` |
| `ArcodModule` (Hilt) | `@Binds @IntoSet ArcodSource` into `Set<LosslessSource>`. (NO interceptor multibinding — the auth interceptor lives on `ArcodClient`'s derived client.) | `QobuzModule` |

**Wiring:**
- Download: `"arcod"` appended to `LosslessSourcePreferences.DEFAULT_PRIORITY`
  (currently the literal list is `["squid_qobuz", "kennyy_qobuz"]` → becomes
  `["squid_qobuz", "kennyy_qobuz", "arcod"]`, i.e. last among lossless).
- Streaming: `ArcodStreamResolver` added to `StreamSourceRegistry`'s **normal**
  branch after `squid`, before `youtube` (NOT in the forceYt branch) — a
  constructor param + one `add("arcod" to …)` line.
- `StashMediaSourceFactory` needs **no change** — verified: `StashPlaybackService`'s
  `streamingTrackId` predicate gates the refresh chain on `origin == "youtube"`
  only, so an `"arcod"` origin falls to the default `DefaultMediaSourceFactory`,
  which streams the open Range-supporting `dl.arcod.xyz` URL.
- **No `:core:network` change** — the auth interceptor rides `ArcodClient`'s
  derived client (see components table), not the shared client.
- Gated by the existing `LosslessSourceHealthGate` + `AggregatorRateLimiter`
  (new conservative `configs["arcod"]` in the init block, like `kennyy_qobuz`).
- Settings: a "Connect ARCOD" row (reconstructs the removed antra row's pattern)
  → `ArcodConnectScreen`.

## Data flow

### Auth
1. User taps "Connect ARCOD" → `ArcodConnectScreen` WebView at `arcod.xyz`.
2. User completes Google login (Supabase OAuth, all in the WebView).
3. A poller reads WebView `localStorage['sb-fnlghyzwyoklfqyhqlav-auth-token']`;
   when present, parse `access_token`/`refresh_token`/`expires_at` → `ArcodCredentialStore.save()`.
4. `ArcodAuthInterceptor` attaches `Bearer access_token` to `arcod.xyz`/`api.arcod.xyz`
   requests. If `expires_at` passed (or a 401 comes back), `ArcodTokenRefresher`
   refreshes once (single-flight); on refresh failure → `markStale()` + reconnect prompt.

### Download (`ArcodSource.resolve`)
1. `isEnabled()` (connected + not circuit-broken) and `rateLimiter.acquire("arcod")` else null.
2. `client.search("<artist> <title>")` → track items.
3. `ArcodMatcher.best(query, items)` → track (id, album.id, isrc, duration) or null.
4. `client.createJob(track)` → jobId; `client.pollStatus(jobId)` until `completed`
   (bounded poll w/ timeout; `error != null` → fail). `completed` → `downloadUrl`.
5. Return `SourceResult(sourceId="arcod", downloadUrl, downloadHeaders=emptyMap()
   [dl URL is open], format=AudioFormat("flac",0,0,0), confidence, sourceTrackId=jobId,
   coverArtUrl=track.album cover)`. Post-download quality probe sets real bits/rate.
6. 429 → `reportRateLimited`; success → `reportSuccess`; failure → `reportFailure`;
   re-throw `CancellationException` before `catch(Exception)`.

### Streaming (`ArcodStreamResolver.resolve`)
- Same search→match→job→poll→url, then `StreamUrl(url=downloadUrl, expiresAtMs=
  now+~280s [URL lives ~300s], codec="flac", origin="arcod", coverArtUrl=…)`.
  ExoPlayer streams the open Range URL via the default factory.

## Accepted v1 limitations (decided in brainstorm)
1. **Job latency on streaming** — a tapped ARCOD track waits a few seconds while
   the job builds before playback starts. Acceptable: ARCOD is a 3rd-string
   fallback (only when kennyy AND squid miss).
2. **300 s URL expiry mid-playback** — the dl URL dies after ~5 min, so a >5 min
   track or a long pause could stall. v1 accepts this; a refresh-on-403 hookup
   (re-`POST /url`) is a later enhancement, not v1.

## Error handling & failover
- Any failure (not connected, no match, job error/timeout, network) → `resolve`
  returns null → registry advances (kennyy/squid already tried; YouTube next).
  ARCOD never surfaces a hard error.
- Refresh failure / 401 after refresh → `markStale()` → Settings shows reconnect;
  source self-disables until reconnected.
- Bounded poll: cap total poll time (e.g. ~60 s) and attempts so a stuck job
  fails over instead of hanging.

## Testing strategy
- `ArcodTokenRefresher`: MockWebServer — refresh returns new tokens; persists;
  single-flight under concurrent callers.
- `ArcodAuthInterceptor`: attaches Bearer; refreshes when expired; retry-once on
  401; off-host + `dl.arcod.xyz` pass-through; no infinite loop.
- `ArcodApiClient` + `ArcodApiModels`: parse real captured `get-music` + job
  status JSON (lock against HAR samples); `pollStatus` stops on `completed`/`error`/timeout.
- `ArcodMatcher`: artist+title scoring, ISRC + duration confirm, wrong-track reject.
- `ArcodSource`: gating, 429, cancellation re-throw, `SourceResult` shape (empty headers).
- `ArcodStreamResolver`: returns arcod-origin `StreamUrl` with the dl URL; null on miss.
- Registry wiring tests: arcod last among lossless (download) + after squid/before
  youtube (stream), absent from forceYt branch.

## On-device verification (Phase N)
- Connect ARCOD (Google login in WebView) → token harvested.
- Force a track only ARCOD has (or use a force-arcod test toggle like the amz one)
  → confirm download writes a real FLAC (quality probe shows 16/24-bit), and a
  stream plays audibly via the default factory (no DRM, unlike amz).
- Confirm failover to YouTube when ARCOD not connected / misses.
- Confirm a token-expiry refresh works (play after >1 h, or force-expire).

## Out of scope (v1)
- URL-expiry refresh-on-403 (accepted limitation #2).
- Non-Qobuz ARCOD content (it's Qobuz catalog).
- Any change to kennyy/squid behavior.
