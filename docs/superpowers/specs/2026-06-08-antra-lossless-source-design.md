# antra — Independent Lossless Fallback Source (design)

- **Date:** 2026-06-08
- **Status:** Approved (brainstorming) — pending spec review
- **Branch (proposed):** `feat/antra-lossless-source`

## Context & Goal

Stash's lossless streaming/downloading currently relies on two **shared-key Qobuz proxies** — kennyy (`qobuz.kennyy.com.br`) and squid (`qobuz.squid.wtf`). They share the same public Qobuz `app_id`, so they tend to degrade together, and a hardening/ban event can leave Stash with **no independent lossless source** (only the lossy YouTube fallback remains). A shipped degradation-detection + failover safety net (`LosslessUrlInspector` + `LosslessSourceHealthGate`) keeps Stash from serving silent garbage, but it cannot conjure a *new* source.

**antra** (`antra.hoshi.cfd`) is a community paste-a-link service that resolves a streaming-service track URL to a real lossless file from its **own multi-source backend** (Qobuz / Tidal / Deezer / Amazon / Soulseek) using **its own accounts** — therefore **structurally independent** of the shared Qobuz `app_id`. A real end-to-end download was captured on-device (HAR, 2026-06-08), confirming the full flow works.

**Goal:** add antra as an independent lossless fallback for both the **download** and **streaming** paths, slotted *after* kennyy/squid, so Stash can still produce a genuine FLAC when both Qobuz proxies are down.

## Non-goals

- Not replacing or migrating kennyy/squid (antra is purely additive, lowest priority in the chain).
- Not a primary/fast source — antra is job-based and (on the free tier) throttled; it is a fallback.
- Not progressive remote streaming in v1 (see Known Limitations).
- No shared/baked-in antra account (quota + ban risk) — per-user only.

## Verified existing structures to build on

- **Download side:** `data/download/.../lossless/LosslessSource.kt` (interface) with `KennyySource` / `QobuzSource` impls; `LosslessSourceRegistry.kt` (ordered failover); `LosslessSourceHealthGate.kt` + `LosslessUrlInspector.kt` (shipped degradation gate); `DownloadManager.kt` (consumes the registry, has a duration backstop).
- **Stream side:** `core/media/.../streaming/StreamSourceRegistry.kt` (ordered failover: kennyy → squid → youtube) with `KennyyStreamResolver` / `QobuzStreamResolver`.
- **Cookie-auth precedent (squid):** `SquidWtfCaptchaScreen.kt` (WebView that solves a challenge and harvests a cookie via `CookieManager`, in `feature/settings/.../components/`), `SquidWtfCaptchaInterceptor.kt` (OkHttp interceptor injecting the cookie), `SquidCookieAutoRefresher.kt` (stale-cookie handling). antra's `session` + `cf_clearance` cookies follow this exact pattern.
- **Input:** `core/model/Track.kt` → `val spotifyUri: String?`. antra `/api/resolve` takes `https://open.spotify.com/track/<id>`, derivable from `spotifyUri`.

**Note:** kennyy/squid have *separate* download (`LosslessSource`) and stream (`StreamResolver`) classes sharing a lower-level client. antra mirrors this: one shared `AntraClient` consumed by an `AntraSource` (download) and an `AntraStreamResolver` (stream).

## Mapped antra flow (from the 2026-06-08 HAR)

All requests are cookie-authenticated (`session` from login + `cf_clearance` from a Cloudflare JS challenge), browser-style headers (UA, sec-ch-ua, origin/referer):

1. `GET /api/auth/me` → `{username, is_supporter, concurrent_jobs, albums_left, singles_left}` — auth + quota.
2. `POST /api/resolve` `{"url":"https://open.spotify.com/track/<id>","format":"lossless-24"}` → release/track metadata.
3. `POST /api/jobs` `{"url":<spotify url>,"format":"lossless-24","start_index":0,"end_index":1}` → `{"job_id","ws_token"}`. (Decrements `singles_left`.)
4. `GET /api/jobs/<id>/status` → `{"status":"complete"|"queued"|...,"done","failed","total","filename","error"}`. (A `wss .../ws` exists for live progress but **polling `/status` is sufficient** — no websocket needed.)
5. `GET /api/jobs/<id>/download` → `200 audio/flac` (the file).

## Decisions (from brainstorming)

| Decision | Choice |
|---|---|
| Account model | **Per-user** — each user logs into their own antra account via an in-app WebView. |
| Scope | **Download + stream**; "stream" = download-the-FLAC-to-cache-then-play (caching avoids re-spending quota on repeat plays). |
| Cloudflare handling | **Approach C** — try cookie-replay over OkHttp first; escalate to a WebView request-proxy only if on-device shows Cloudflare 403s on the replay. |
| Priority | After kennyy/squid in both registries; gated by the existing health gate. |
| Job progress | Poll `/status` (no websocket). |
| Playback | Play the fetched **local file** (no cookie-in-ExoPlayer, no "file-exists-only-after-job" race). |

## Components

1. **`AntraCredentialStore`** — persists `session` + `cf_clearance` cookies, expiry, username; encrypted prefs. `isConnected()`, `cookieHeader()`, `markStale()`. Mirrors how the squid cookie is stored.

2. **`AntraConnectScreen`** (Settings → "Connect antra") — WebView at `antra.hoshi.cfd`; user logs in, Cloudflare JS challenge auto-solves; on detecting logged-in state (`/api/auth/me` 200 with username), harvest cookies via `CookieManager` → `AntraCredentialStore`. Reuses `SquidWtfCaptchaScreen` structure.

3. **`AntraClient`** (shared; Approach A — OkHttp + `AntraCookieInterceptor`) — `resolve()`, `createJob()`, `pollStatus()` (bounded timeout), `fetchFlac()` (to a file), `quota()`. On Cloudflare `403` → `credentialStore.markStale()`. Browser-style headers to match the `cf_clearance` fingerprint.

4. **`AntraSource : LosslessSource`** (download) — gates: connected? quota>0? not degraded? **track has `spotifyUri`?** Any fail → `null` (failover continues). Else resolve → createJob → poll → fetchFlac → return the local file to `DownloadManager` (which already has the duration backstop). Registered last in `LosslessSourceRegistry`.

5. **`AntraStreamResolver`** (stream) — same gating + job flow, fetch FLAC to an evictable cache file, return a `StreamUrl` pointing at the local file URI. Registered after kennyy/squid in `StreamSourceRegistry`. Cache keyed by track so repeat plays don't re-spend quota.

6. **Wiring** — register both behind the existing `LosslessSourceHealthGate`; surface a "Reconnect antra" prompt when creds go stale.

## Data flow

`track → spotifyUri present? → [cache hit → play] → AntraClient.resolve → createJob(spotify url) → poll /status until complete → fetchFlac → local cache file → play local file` (download path also persists into the library).

## Error handling

- No `spotifyUri` / not connected / quota exhausted / job `failed` / poll timeout → return `null`, failover proceeds (→ YouTube lossy as today).
- Cloudflare `403` on the OkHttp replay → `markStale()` + "Reconnect antra" prompt; if chronic on-device, escalate to **Approach B** (route antra requests through the WebView's JS `fetch`, which holds a valid `cf_clearance` + matching TLS fingerprint).
- Degraded source → existing `LosslessSourceHealthGate` cooldown (auto-skip).
- `cf_clearance` expiry (~30 min) → treated as a 403 → re-challenge (a silent background WebView refresh, mirroring `SquidCookieAutoRefresher`, is a follow-up optimization).

## Testing

- **`AntraClient`** — MockWebServer unit tests replaying the HAR's exact JSON for resolve / job-create / status(queued→complete) / download / 401 / 403 (→markStale).
- **`AntraSource` + `AntraStreamResolver`** — gating (no-spotifyUri, quota=0, degraded), cache-hit (no second job), job-success→file, job-failed→null, poll-timeout→null.
- **`AntraCredentialStore`** — store / expiry / markStale round-trips.
- **On-device** — the WebView harvest and the **A-vs-B Cloudflare reality** (does the OkHttp cookie-replay get 403'd?) can only be settled on-device; that test decides whether Approach B is needed.

## Known limitations (explicit)

- **Coverage:** only tracks with a `spotifyUri` can use antra (antra may also accept Tidal/Qobuz/Apple URLs — future widening).
- **Speed:** v1 fetches the whole FLAC before playback; on the throttled free tier (no Ko-fi supporter) a track may take a while to start. It is a fallback.
- **Quota:** free tier ≈ 99 singles / period; antra auto-skips when `singles_left == 0`.
- **Fragility:** Cloudflare can tighten the challenge (interactive Turnstile) at any time; the health gate makes that a graceful auto-skip, not a crash.

## Implementation notes for planning

- **Types:** `AntraSource` (download) receives the download path's track type; `AntraStreamResolver` receives **`TrackEntity`** (the `StreamSourceRegistry.resolve(track: TrackEntity, …)` signature). Both `Track.spotifyUri` (model, line 14) and `TrackEntity.spotifyUri` (entity, line 79) exist — read `spotifyUri` from whichever type each side gets; do not wire against `Track` on the stream side.
- **Phasing (keep Approach A as v1):** the OkHttp cookie-replay path (Approach A) is the **v1 deliverable**; Approach B (WebView request-proxy) is a **bounded contingency task** to build *only if* the on-device test shows Cloudflare 403'ing the replay. Do not build both up front.
- **Fixed format:** `format = "lossless-24"` is intended as a **fixed constant** (24-bit lossless), matching the HAR. No client-side format negotiation / 16-bit fallback in v1; the shipped duration backstop already guards against a quality regression.

## Open questions / risks

1. **A-vs-B (the central risk):** will a WebView-harvested session survive replay through OkHttp's TLS stack? **RESOLVED 2026-06-09: yes — zero Cloudflare 403s on-device (see Task 10 below); Approach B never needed.**
2. **Quota reset semantics** — period length of the 99-singles cap is unknown; we only read `singles_left` and skip at 0 (no client-side reset modeling needed).
3. **`/download` cookie need** — **RESOLVED 2026-06-09: the interceptor's cookie replay covers `/download`; 27 MB FLAC fetched clean on-device.**

## Verification Results

### Phases 1–5 — implementation complete (2026-06-08)

All code for Approach A (Phases 1–5, Tasks 1–9) is implemented TDD-first and committed on `feat/antra-lossless-source`:

| Task | Unit | Tests | Status |
|---|---|---|---|
| 1 | `AntraCredentialStore` | `AntraCredentialStoreTest` | ✅ green |
| 2 | `AntraCookieInterceptor` | `AntraCookieInterceptorTest` | ✅ green |
| 3 | API models + `AntraJson` | `AntraApiModelsTest` | ✅ green |
| 4 | `AntraClient` (+`downloadTo`) | `AntraClientTest` | ✅ green |
| 5 | `TrackQuery.spotifyUri` + `spotifyTrackUrl()` | `TrackQuerySpotifyUriTest` | ✅ green |
| 6 | `AntraSource : LosslessSource` | `AntraSourceTest` | ✅ green |
| 7 | `AntraStreamResolver` | `AntraStreamResolverTest` | ✅ green |
| 8 | `AntraModule` + registry wiring | `StreamSourceRegistryTest` | ✅ green |
| 9 | `AntraConnectScreen` + Settings entry | (manual) | ✅ builds |

**Build/runtime verified (no device account needed):**
- `:app:assembleDebug` compiles end-to-end — the Hilt graph accepts `AntraSource` (`@IntoSet Set<LosslessSource>`) and `AntraStreamResolver` (constructor-injected into `StreamSourceRegistry`).
- `:app:installDebug` + launch on a connected device: app starts cleanly (no FATAL / Hilt / antra errors in logcat), Settings screen renders the new "Connect antra" row.
- Pre-existing unrelated reds (`YtLibraryCanonicalizerTest`, `InnerTubeSearchExecutorTest`) confirmed red on the clean baseline — not caused by this work.

**Implementation note (deviation from plan):** the plan's Task 8 Step 2 said add antra to "both the `forceYt` and normal branches" of `StreamSourceRegistry`. antra was added to the **normal branch only** — the `forceYt` toggle's documented purpose is to skip ALL lossless sources to reproduce the YouTube-only fallback path, so adding antra (a lossless source) there would defeat the toggle. Single-source-of-truth UA lives in the new `AntraFingerprint` object, shared by the WebView (mint) and interceptor (replay).

### Task 10 — on-device end-to-end (PASSED 2026-06-09 — **Approach A confirmed**)

Run on the user's Pixel 6 Pro against their real antra account (`rawnald1`).

**Auth-model corrections found during connect (commit `0d89b43f`):** antra's login cookie is an HttpOnly **`antra_session`** (64 chars), not `session`; `cf_clearance` never existed for this client (Cloudflare wasn't challenging), so it is captured opportunistically but not required. `/api/auth/me` returns `{username, is_admin, is_supporter, concurrent_jobs, albums_left, playlists_left, singles_left}` (username top-level). Confirming login from the connect WebView required a JS→Kotlin bridge (`AntraJsBridge`) because `evaluateJavascript` returns an async fetch's un-awaited Promise (`{}`).

**The decisive Cloudflare test:** a temporary probe (commit `eb5c271b`, reverted after the test) ran the full lifecycle from `SettingsViewModel` over Stash's plain OkHttp stack — non-browser TLS, pinned `AntraFingerprint` UA, `antra_session` replayed by `AntraCookieInterceptor`:

```
/api/auth/me  => 200 username=rawnald1
/api/resolve  => 200 1 track 'Fresh'
/api/jobs     => 200 job_id=b02bfdba
/status polls => 200 ... status=complete filename=Fresh.flac
/download     => 200 ok=true bytes=27131849 magic='fLaC'
```

**Zero Cloudflare 403s anywhere, including the large-binary `/download` fetch. Approach A (cookie replay) is sufficient; Phase 6 (Approach B, WebView request-proxy) is NOT needed and was never built.**

Notes:
- Connect flow verified end-to-end: log in → harvest `antra_session` → in-page `/api/auth/me` confirm → save → Settings row shows username.
- The lifecycle was exercised via the probe rather than the organic download path; the organic path uses the same `AntraClient` + interceptor, so the Cloudflare question it was gating on is answered. Organic stream-resolve + no-regression (kennyy/squid/YT) checks remain ordinary QA items for release testing.
- A duplicate probe run got `createJob => job_id=null` (likely duplicate/concurrent-job rejection) — graceful, no crash.
