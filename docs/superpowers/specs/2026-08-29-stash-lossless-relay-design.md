# Stash Lossless Relay — Design

**Date:** 2026-08-29
**Status:** Approved by user (this session), pending spec review
**Refs:** memory `project_own_lossless_pool_decision`, `project_monochrome_tidal_hifi_source`, `project_legal_risk_analysis` (2026-08-29 additions); research dossier (artifact "Monochrome Source Dossier", Parts I–II).

## Goal

**Free, automatic, best-effort lossless** for every Stash user — "lossless when available" — without depending on strangers' credential pools, without shipping anyone's tokens in the APK, and with the app always able to say truthfully which state it is in.

## Problem

Stash's only zero-config lossless path is `qbdlx`: a shared, third-party pool of other people's Qobuz `user_auth_token`s, encrypted into the APK and re-fetched at runtime. It dies on a cycle (19 tokens healthy 2026-08-15 → all `USER_BLOCKED` 2026-08-21), it is the project's top remaining legal exposure (credential trafficking), and when it is dead the UI still says "Qobuz — active". Every other community pool the app has leaned on (kennyy, squid, monochrome's workers) has died or retreated within twelve months.

## Decisions (settled with user)

1. **Goal is free + automatic when available**, not BYO-required. BYO stays first-class; the custom-endpoint field ships alongside.
2. **Provider: Qobuz first.** Stash already speaks Qobuz end to end (progressive, Range-seekable FLAC — zero player changes). Tidal is reserved in the contract, not built.
3. **Approach A — thin minting relay.** The relay mints file URLs only. Catalog/search/ISRC-match/Home stay in the app and go tokenless (verified 2026-08-29: `catalog/search` answers with `X-App-Id` alone).
4. **Hosting:** one always-on box at home behind a Cloudflare Tunnel — one stable residential egress IP; users reach a domain, never the home IP.
5. **Rationing: strict human-shaped per-account budget + one in-flight call per account + a sacrificial probe account** that measures Qobuz's real tolerance. Start with 3 playback + 1 probe accounts.
6. **Addressing & disclosure:** fresh domain on its own Cloudflare account; **zero lossless hostnames in the APK** (runtime-fetched signed config); truthful README; repo to an org; release mirror off GitHub.

Rejected: (B) full hifi-api-style proxy — Qobuz catalog needs no auth, so proxying it only multiplies traffic and spends budget on search; (C) the developer's own tokens in the APK — extractable, un-rationable, banned from every user's IP at once.

## Non-goals (v1)

- Tidal on the relay (contract reserves `GET /v1/tidal/manifest`; needs the DASH work documented in the dossier).
- BYO-Tidal.
- Play Integrity / attestation gating of the relay.
- Any catalog proxying, any audio caching or transit, any user accounts on the relay.
- Token refresh/resurrection logic — a dead account is replaced by the operator, not revived by code.
- Adaptive budget controllers — the probe account measures; a human sets the number.

## Architecture

One seam. Every lossless resolve in Stash ends at **"give me a signed FLAC URL for Qobuz track X"** — `QbdlxApiClient.getFileUrl`. It gains three implementations behind one interface, tried in order:

1. **BYO** — user connected their own Qobuz account → sign locally with their token (exists; wins precedence; never spends relay budget).
2. **Relay** — first healthy entry from runtime config → `GET {relay}/v1/qobuz/file?track_id&format_id`.
3. **Custom endpoint** — user-pasted base URL in Settings › Advanced → same call, different base. Outranks the public relay when set (explicit beats implicit).

Then ARCOD, JioSaavn, YouTube exactly as today. The output is the same akamaized `etsp`-signed FLAC URL Stash already plays, so `StreamSourceRegistry`, `StreamUrlCache`, `LosslessUrlDownloader`, prefetch and crossfade are unchanged.

**Audio never transits the relay.** It returns a URL to Qobuz's CDN.

**Runtime config.** The APK contains no relay hostname. On launch (and every 6 h) it fetches a small Ed25519-signed JSON from a stable URL the operator controls; relays can move, die or be replaced without a release.

```
App ──search/ISRC (X-App-Id only)──────────────▶ Qobuz catalog API
App ──GET /v1/qobuz/file ──▶ Relay (home box, cloudflared) ──signed getFileUrl──▶ Qobuz API
App ◀── {url, expires_at} ──┘                                                       │
App ──GET url (Range) ─────────────────────────────────────────────────────▶ Qobuz CDN (akamaized)
App ──GET lossless.json (signed) ──▶ static config host
```

## Relay (server)

**Stack:** one Node 20 + TypeScript process (Hono), SQLite state (`relay.db`), systemd unit (Docker optional), published via `cloudflared` as `https://<relay-domain>`. No audio, no disk cache, no user data beyond a request log. Lives in `infra/lossless-relay/`.

**Components** (one module each; each testable alone):

- **`accounts`** — table `{id, email, user_auth_token, app_id, app_secret, role: playback|probe|catalog, region, budget_per_hour, state: live|cooling|dead, cooling_until, last_error, created_at}`. Tokens are obtained by logging in *on the relay* with the operator's own credentials (`user/login` with MD5 password, the same flow as `QobuzLoginClient`), via CLI `relay accounts add <email>`; the signing pair is scraped from the Qobuz web bundle at login time (same as `QobuzWebCredentialsClient`). Never pasted from third parties, never in git. Region-locked tracks (`sample: true`) are a per-track miss, not an account fault.
- **`signer`** — `request_sig = md5("trackgetFileUrl" + "format_id" + format + "intent" + "stream" + "track_id" + id + ts + app_secret)`; headers `X-App-Id`, `X-User-Auth-Token`. Pure function, a port of `QbdlxSigner`, unit-tested against vectors produced by the Kotlin implementation.
- **`pool`** — rationing: per-account token bucket (`budget_per_hour`, default **18**), **at most one in-flight upstream call per account**, round-robin over `live` accounts, one fixed real-client User-Agent, one egress IP (no proxies). Upstream 401 or 403 `USER_BLOCKED` → `dead` (out of rotation, alert). 429, or a `sample: true` on a track the catalog reports as full → `cooling` 30 min. No account with budget → `busy`.
- **`mint`** — `GET /v1/qobuz/file?track_id=<int>&format_id=<5|6|7|27>`
  - `200 {url, expires_at, format_id, bit_depth, sample_rate}` (`expires_at` = the URL's `etsp` in epoch seconds; **`sample_rate` is an integer in Hz** — Qobuz returns kHz as a float and the client's `classify()` multiplies by 1000, so the relay converts before responding; the client's relay parser does *not* multiply again)
  - `404 {status:"no_match"}` — sample/region/track not streamable
  - `503 {status:"busy", retry_after: <s>}` — pool budget exhausted or no live account
  - `502 {status:"upstream"}` — Qobuz error other than the above
  - `400` — bad params or missing `X-Stash-Version`
- **`status`** — `GET /v1/status` → `{accounts_live, accounts_total, budget_remaining_pct, minted_last_hour, busy_last_hour, version}`; public; cached 30 s. The app's honesty UI reads only this.
- **`guard`** — per-IP limits (default 60 mints/h, 4 concurrent), required `X-Stash-Version` header, no shipped secret. Hotlinking is rate-limited, not prevented.
- **`probe`** — the `probe`-role account runs a scheduled synthetic load against real, varied tracks at 2×, then 4×, then 8× the human rate (one week each), on the same box and IP. Its state transitions and upstream error strings feed the daily summary. Its findings set `budget_per_hour`; the public budget is not raised above 18 until the probe has run three weeks.
- **`ops`** — structured JSON request log (no user identifiers beyond hashed IP), daily summary (mints, busy %, per-account state), alert webhook (Discord/email) on any `dead` transition and when `accounts_live == 0`.

## Client (Stash)

**`QbdlxApiClient.getFileUrl`** picks its implementation at call time: BYO token → local signing (unchanged code); else relay base from config → `LosslessRelayClient`; else custom base → `LosslessRelayClient`; else `null`. `LosslessRelayClient` (new, small): OkHttp on the shared client, 8 s timeout, sends `X-Stash-Version`. **It owns its own cooldown** — one in-memory `cooledUntilMs` per base URL (busy → now + 60 s; 502/timeout → now + 5 min; 404 → no cooldown) and returns null without a request while cooled. It does *not* use `LosslessSourceHealthGate` (fixed 5-min cooldown, and `QbdlxStreamResolver` deliberately doesn't consult it) nor `LosslessSourceHealth` (a miss counter with no cooldown). Because both `QbdlxStreamResolver` (streaming) and `QbdlxQobuzSource` (downloads) reach the relay through the same client instance, the cooldown applies to both paths automatically. A 200 maps to the existing `QbdlxResolveResult.Ok` exactly as a locally signed `getFileUrl` would; a relay 404 maps to `RegionLocked` (the relay collapses "not streamable" and "region-locked" into one answer, and the source treats both as "next rung"); no new field — the streaming path keeps deriving `StreamUrl.expiresAtMs` by parsing `etsp=` out of the URL (`parseEtspMs`), so the relay's `expires_at` is informational only.

**Catalog goes tokenless — under the web-player app_id.** The other eight token-bearing `QbdlxApiClient` methods (search, artists, playlists, albums, featured, discography…) drop the token and signature and send `X-App-Id` only. Verified 2026-08-29 (all eight endpoints, live): tokenless calls answer **401 "User authentication is required" under the bundled Android-lineage app_id (798…)** but **200 under the Qobuz web player's app_id (`712109809`)**, which is what the web player uses for logged-out browsing. So catalog calls use a separate `catalogAppId` (default `712109809`, a public constant present in Qobuz's own bundle) and self-heal: on a catalog 401 the client refreshes the id once via `QobuzWebCredentialsClient.fetch()` (already live-scraped for login) and retries. `getFileUrl` signing is unaffected (BYO signs with the account's own pair; the relay signs server-side). **Three catalog consumers currently bail on `activeToken()` before reaching the client and must lose that gate too:** `HomeDiscoveryRepositoryImpl.withToken()` (returns `emptyList()` + `QobuzDiscoveryStatus.NO_TOKEN`) — the `NO_TOKEN` status and the `QobuzDiscoveryBanner` copy ("set up tokens via github actions…") are deleted, discovery failures become ordinary network errors; `QobuzAlbumFetcherImpl` (`error("qbdlx: no live token")`) — call the client directly; `QobuzDiscographyProvider` — drops both its token use and its `isEnabledForStreaming()` gate (discography is catalog-only and must not depend on file-URL availability).

**`QbdlxQobuzSource` resolve path — restructured, not "unchanged".** Today the whole path is token-shaped: `search()` opens with `credentialStore.activeToken() ?: return null` and rotates tokens on failure; `resolveFile()` loops `activeToken()`/`markDead()` up to `MAX_TOKEN_ATTEMPTS`; `resolveRegion()` iterates `tokensForRegion()`. With no pool there is no token to start from, so the path becomes:

1. **Availability check first.** `resolveInternal` begins with `apiClient.fileUrlAvailable()` — true when a BYO token is live, or the relay/custom base is configured and not cooled. False → return null *before* any catalog call (so a cooled relay costs zero HTTP, which is what device test (2) asserts).
2. **Tokenless search.** `search()` calls `apiClient.search(term)` (X-App-Id only) and returns `(track, confidence)`; the token loop, `tried` set and `MAX_TOKEN_ATTEMPTS` go.
3. **Single file resolve.** `resolveFile()` makes one `apiClient.getFileUrl(track.id, formatId)` call; the client picks BYO-local / relay / custom internally. Outcomes: `Ok` → `build()`; `TokenDead` (only possible on the BYO path) → `credentialStore.markDead(byoToken)` and return null — there is nothing to rotate to; `RegionLocked` → return null — with one account, region-retry has no meaning. `resolveRegion()`, `tokensForRegion()`, and the sticky-advance logic are deleted with the pool.
4. `QbdlxStreamResolver` reaches the same path through `resolveImmediate`, so streaming and downloads behave identically.

**Deleted:** `QbdlxPoolProvider`, `QbdlxPoolCipher`, `QbdlxRemotePool`/`HttpQbdlxRemotePool`, the pool/pinned/pasted halves of `QbdlxCredentialStore` (pool, poolForPicker, refreshIfExhausted, tokensForRegion, deadUntil/lastFailedAt bookkeeping, cached-pool DataStore key), the `encryptPool`/`poolFp` functions and `QBDLX_TOKEN_POOL`/`QBDLX_POOL_FP` build fields, and `release.yml`'s pool-fetch and dex-fingerprint-verify steps.

**`QBDLX_CONFIGURED` is deleted.** Today it is `app_id && app_secret && tokenPool.isNotBlank()` and gates both registries (`StreamSourceRegistry.kt:249`, `LosslessSourceRegistry.kt:60`) — an empty pool silently disables lossless even for a connected BYO account. Both filters are removed, the `buildConfigField` goes, the chain log line at `StreamSourceRegistry.kt:305` drops it, and the two `assumeTrue` test guards go with it. Enablement is entirely the availability predicate below.

**One availability predicate — `LosslessAvailability` (new, `data/download/.../lossless/`).** Today `QbdlxCredentialStore.allDead()` returns true when there is no BYO login and no pool token, and `QbdlxQobuzSource.isEnabled()` / `isEnabledForStreaming()` gate on `!allDead()`; after the pool deletion that is the normal state of every relay-only user. Rather than three overlapping predicates, one class answers the three questions, exposed both as `Flow<…>` (for `HomeViewModel`'s combine) and as `suspend` getters (for `DownloadManager` / `SearchDownloadCoordinator`):

| Question | Definition | Used by |
|---|---|---|
| `qbdlxEnabled` | `hasLogin \|\| relayConfigured \|\| customEndpointSet` | replaces `!allDead()` in `QbdlxQobuzSource.isEnabled()`/`isEnabledForStreaming()` (Plan A1); `allDead()` itself is deleted in Plan C together with the Settings token surface that still reads it |

The seam that picks BYO-local / custom / relay for a file URL is a small `QbdlxFileUrlRouter` (one class, one method) sitting between `QbdlxQobuzSource` and `QbdlxApiClient`/`LosslessRelayClient`, so the HTTP client stays a plain HTTP client and the routing is unit-testable on its own. Order: BYO → custom endpoint → config relays by priority.
| `fileUrlAvailableNow` | `byoTokenLive \|\| (relayConfigured && !relayCooled) \|\| (customEndpointSet && !customCooled)` | `QbdlxQobuzSource.resolveInternal` entry check (a cooled relay costs zero HTTP) |
| `anyConfigured` | `qbdlxEnabled \|\| arcodConnected` | the download deferral reason (`NO_SOURCE_CONFIGURED` when false) |
| `anyUserOwned` | `hasLogin \|\| customEndpointSet \|\| arcodConnected` | the Home banner — a dead public relay must **not** suppress the banner whose CTA is "connect your own account" |

`relayConfigured` = the cached runtime config lists ≥ 1 relay (busy/cooling still counts as configured); `customEndpointSet` = the Advanced field is non-blank; cooldown state comes from `LosslessRelayClient`.

`hasLogin` = an account is PRESENT (including a pasted token awaiting migration), not that it is live. Liveness belongs to `fileUrlAvailableNow` alone: `qbdlxEnabled` and `anyUserOwned` describe configuration, so a login serving its 60 s dead-cooldown must not flip the source off or make the "connect your own account" banner reappear — exactly the flicker a liveness test would cause on every transient 401. A relay entry is "configured" while cooling for the same reason.

**`LosslessConfigFetcher`** (new): `GET https://<config-host>/stash/lossless.json` on launch and every 6 h; body `{"v":1,"relays":[{"base":"https://…","priority":1}],"updated_at":<epoch>}` plus a detached signature fetched from `lossless.json.sig` (base64 DER, **ECDSA P-256 / `SHA256withECDSA`** — Android's JCA has no Ed25519 below API 33 and the app's minSdk is 26; ECDSA needs no dependency; same trust model). Public key baked into the app as `BuildConfig.LOSSLESS_CONFIG_PUBKEY` (base64 X.509 SPKI) alongside `BuildConfig.LOSSLESS_CONFIG_URL`; both empty → fetcher disabled → no relay. Private key kept off GitHub. Valid → applied and cached in DataStore; invalid signature → ignored, cached copy kept; network failure → cached copy; nothing cached → no relay. A relay entry is "healthy" when its last `/v1/status` said `accounts_live > 0`.

**Precedence:** BYO never touches the relay; custom endpoint outranks the public relay; relay `busy` on one track cools the relay for 60 s and the rest of the queue keeps resolving through the remaining rungs.

**Downloads:** unchanged — `LosslessUrlDownloader` consumes the same `SourceResult.downloadUrl`.

**Strict-FLAC ("fallback off") behaviour — specified.** Today `DownloadManager` forces the lossy fallback chain on debug builds only (`forceYoutubeFallbackOnDebugBuilds = BuildConfig.DEBUG`, `DownloadManager.kt:141`, used at ~230–250) and honours strict deferral into `WAITING_FOR_LOSSLESS` on release — which, with no configured source, is a silent forever-wait. The fix is **not** to force lossy on release (that would override the user's own toggle). It is:
1. Drop the `BuildConfig.DEBUG` keying: debug and release honour the toggle identically.
2. Add a **deferral reason as a field, not a status.** `DownloadStatus` is unchanged and rows stay in `WAITING_FOR_LOSSLESS`, so `LosslessRetryWorker`'s sweep (`WHERE status = 'WAITING_FOR_LOSSLESS'`), the active-count/defer/requeue/orphan queries and the `QueueStatus` mapping all keep working untouched. New nullable column `deferral_reason TEXT` on the download-queue entity (Room migration), values `SOURCE_BUSY` (a lossless path exists but did not serve) | `NO_SOURCE_CONFIGURED`. It is written by **both** deferral writers: `TrackDownloaderImpl` — `TrackDownloadResult.Deferred` becomes `data class Deferred(val reason)` so `DownloadManager` can carry it — and `SearchDownloadCoordinator`'s own `WAITING_FOR_LOSSLESS` write (~`:220`). `NO_SOURCE_CONFIGURED` is keyed on `LosslessAvailability.anyConfigured` (includes ARCOD), not on qbdlx state. The Downloads screen renders `NO_SOURCE_CONFIGURED` as *"Waiting for a lossless source — none connected"* with a tap-through to Settings › Audio; `SOURCE_BUSY` keeps today's waiting row. Rendering means the column rides the existing projection: `DownloadQueueDao.observeActiveDownloadRows()` → `DownloadManagementRow` → `DownloadManagementItem` → `phaseLabel` each gain the field; `deferIfInProgress` and `SearchDownloadCoordinator`'s write take a reason argument. Search-tab defers that have no `download_queue` row (the coordinator's own comment: "most search defers won't find a row here") get no reason to stamp — this fix is for rows that exist, not the search-download orphan case.
3. **Give the retry sweep triggers it doesn't have.** `LosslessRetryScheduler` is explicitly non-periodic; its only triggers are squid captcha-cookie changes, `QobuzSource` bad-cookie transitions and breaker resets — none fire when a user connects an account, when config changes, or when a relay stops being busy. Plan A adds three triggers that call the existing `schedule()`: BYO credential connected/migrated, `LosslessConfigFetcher` applied a changed relay list, and `LosslessRelayClient` cooldown expiry; plus a **periodic 30-minute sweep** (WorkManager periodic work running the existing `LosslessRetryWorker`) that is enqueued while any `WAITING_FOR_LOSSLESS` row exists and cancelled when none do. Device test (6) therefore completes on the next sweep, within 30 minutes of the budget returning.
Strict-FLAC users therefore still never receive lossy files; they stop being stranded invisibly.

## Availability semantics & UI

Relay status is fetched on launch and every 5 min while streaming, never on the tap path.

| Relay state | App behaviour | Now Playing | Settings › Audio |
|---|---|---|---|
| live, budget > 0 | relay tried after BYO | existing `FLAC · 16-bit/44.1 kHz` badge (lossless is not "via"-labelled, by convention) | "Stash lossless · available" |
| `busy` | relay skipped 60 s, then retried | falls to YouTube → existing `via YT` badge | "Stash lossless · busy right now — falls back to YouTube" |
| down / unreachable / no config | relay skipped 5 min | `via YT` | "Stash lossless · offline" |
| BYO connected | relay never called | `FLAC …` | "Qobuz · connected as you@…" |

Per-track `no_match` falls to the next rung silently, exactly as a qbdlx miss does today.

**Home banner:** the ARCOD-rescue banner generalises. Shown only when no lossless path served in the last 6 tries **and** `LosslessAvailability.anyUserOwned` is false (no BYO Qobuz login, no custom endpoint, no ARCOD account). Deliberately *not* `anyConfigured`: a configured-but-dead public relay is exactly the outage the banner exists for, and its CTA is "connect your own account". Today's check reads only `arcodCredentialStore.accessToken`, which would show the banner to a BYO user with a cold cache; the new leg is a `Flow<Boolean>` so the combine stays live: *"Lossless is offline right now — Stash is playing YouTube audio. Connect your own Qobuz account to keep FLAC."* Same flows, but a **new dismissal key** (`losslessOfflineDismissed`) rather than the permanent `arcodRescueDismissed` flag — users who dismissed the old ARCOD banner must still see this one once. The copy no longer assumes a shipped pool. `LosslessRoutingStatus` becomes stateful (a list of sources with real state) instead of the hardcoded "Qobuz — active" row.

**Settings › Audio › Lossless** becomes a status list: Qobuz account (connect/disconnect) · Stash lossless (relay status, read-only) · ARCOD · *Advanced:* Custom lossless endpoint (URL + Test). Removed: the "Direct Qobuz" master toggle, the paste-token field, the account picker — with their state handled, not orphaned:
- **`qbdlx_enabled` pref is deleted as a gate.** `QbdlxQobuzSource.isEnabled()`/`isEnabledForStreaming()` drop the `losslessPrefs.qbdlxEnabledNow()` term, the key and its `qbdlxEnabled` flow/setter leave `LosslessSourcePreferences`, and the Settings `qbdlxEnabled` StateFlow goes. A persisted `false` from a user who once switched it off therefore cannot silently keep the relay dead (the `feedback_force_toggles_outlive_ui` failure). The one remaining lossless master switch is the existing "lossless on" pref.
- **Pasted-token users are migrated, not dropped.** On first launch after upgrade, a non-blank pasted token is moved into the BYO login-credential slot as an *unverified* credential — `pasted_token` is stored as a lone string today and signs via `signingFor()`'s fallback to `BuildConfig.QBDLX_APP_ID`/`QBDLX_APP_SECRET`, so the migration synthesises the pair from those same BuildConfig values to satisfy `setUserCredential()`'s three-non-blank requirement — it keeps working until it dies (`TokenDead` → cleared), and Settings shows it as "Qobuz · connected (token)" with the normal Disconnect. No email/password is required to keep a working setup working. Visibility and effect of the custom-endpoint field are gated together (per `StreamingPreference`'s force-toggle lesson): it is a visible release control, so its effect is not debug-gated.

**Strict-FLAC downloads** keep waiting in `WAITING_FOR_LOSSLESS` while the relay is busy and are retried by the sweep triggers above (cooldown expiry, account connect, config change, 30-min periodic).

## Testing

**Relay (Vitest, no network):** `signer` golden vectors (including parity with `QbdlxSigner` output); `pool` — budget exhausts and refills on an injected clock, never two in-flight calls per account, round-robin order, `USER_BLOCKED` → dead and out of rotation, cooling expires; `mint` contract tests against a fake Qobuz (`sample:true` → 404; 401/403 → account dead + 502; empty pool → 503 with `retry_after`; bad `format_id` → 400); `guard` per-IP trip and missing header → 400. One opt-in (env-var) integration test hits real `track/getFileUrl` with a real account, run by hand before deploys — the only test allowed to touch Qobuz.

**Stash (JVM):** `QbdlxApiClientTest` (MockWebServer) — precedence (BYO signs locally; relay called with the right query; 503 → null + cooled; 404 → null no cooldown; 502 → null + cooldown), catalog methods send `X-App-Id` and no token/signature; `LosslessConfigFetcherTest` — valid/invalid signature, network failure, no cache; `QbdlxCredentialStoreTest` shrinks to BYO cases; `StreamSourceRegistryTest` and `LosslessSourceRegistryTest` — chain with relay present/absent, BYO bypass, custom outranks relay, and the removed `QBDLX_CONFIGURED` guards; `LosslessAvailabilityTest` — the four predicates against every combination of BYO/relay/custom/ARCOD/cooldown; `QbdlxQobuzSourceTest` — cooled relay → null with zero catalog calls; a `DownloadManager` test for the deferral reason on both writer paths and a `LosslessRetryScheduler` test for the three new triggers. Baseline stays green (`:core:media` 345 / `:core:data` 578; the two known-red HomeViewModel matcher tests remain the only red).

**On device (Pixel 5 rig):** (1) relay + tunnel + config live → fresh install → tap → logcat `served … origin=qbdlx`, badge `FLAC`; (2) budget set to 0 → `busy` → `via YT` within one round-trip, no spinner, queue continues; (3) tunnel killed → "offline" in Settings, recovers without restart; (4) BYO connected → relay request count flat while FLAC plays; (5) custom-endpoint field with config removed → same as (1); (6) strict-FLAC download with relay busy → row shows the waiting state, completes on the next sweep after the budget returns (≤ 30 min, or immediately on cooldown expiry).

## Ops & rollout

**Accounts:** 3 playback + 1 probe Qobuz Studio Solo (~$52/month monthly, ~$43 annual), each on its own email, created and paid by the operator, logged in on the relay. Add = `relay accounts add`; `dead` = alert → replace.

**Deploy recipe** (`infra/lossless-relay/README.md`, following `infra/lastfm-proxy/README.md`'s *document* structure only — the runtime is a Node + systemd process on the home box, not a Cloudflare Worker): Node 20, `npm ci`, `relay accounts add`, systemd unit, `cloudflared tunnel` to `<relay-domain>`; publish `lossless.json` + signature to the static config host; back up `relay.db` and the signing key.

**Rollout (each step reversible):**
1. **Groundwork release** — tokenless catalog, `QBDLX_CONFIGURED` redefinition, registry filters removed, strict-FLAC stranding fix, `LosslessConfigFetcher` wired to an empty list. Pool code still present; users see no change (the shared pool is already dead).
2. **Relay soft launch** — relay live with 3 + 1 accounts; `lossless.json` lists it; watch busy rate and account health for a week. Rollback = empty the JSON.
3. **Pool deletion + Settings/Home honesty UI + custom-endpoint field.** Release notes: "Stash no longer ships anyone's tokens; lossless comes from Stash's own relay when available, or your own Qobuz account."
4. **Budget tuning** from the probe's findings; add accounts only if the first three survive 30 days.

**Planning units (three plans, in order):**
- **Plan A1 — qbdlx goes tokenless + relay client (Kotlin):** tokenless catalog and its three consumers, `QBDLX_CONFIGURED` deletion + registry filters, `LosslessAvailability`, `QbdlxQobuzSource` restructuring, `LosslessRelayClient` + `LosslessConfigFetcher` (pointing at an empty list), pasted-token migration, `qbdlx_enabled` gate removal.
- **Plan A2 — download deferral reason:** Room migration + `deferral_reason` column + exported schema, `TrackDownloadResult.Deferred(reason)`, both writers, the DAO projection → row → item → label chain, the retry-sweep triggers + periodic sweep. Independent of A1's relay code; both ship in the groundwork release.
- **Plan B — relay server (Node/SQLite) + deploy:** the seven modules, probe, ops, cloudflared recipe, `lossless.json` publishing. Soft launch = Plan B live + the JSON listing it.
- **Plan C — pool deletion + honesty UI + custom-endpoint field**, gated on a week of soft-launch data.
Step 4 is operations, not code.

**Hygiene (same window, separate commits):** README — delete "The FLAC backbone", fix the "only Spotify and YouTube" claim, name every service contacted, state that the relay hosts no audio, add a contact address; the `QbdlxPoolCipher` concealment comment goes with the file; move the repo to an org; publish an Obtainium-compatible release JSON on the operator's domain; move the tipjar Worker off the real-name subdomain when convenient (not blocking).

## Risks (acknowledged, not mitigated by code)

- **Account bans.** Detection is behavioural; the human-shaped budget and single egress IP reduce, not remove, the risk. The probe account exists to measure it. Expected outcome is periodic replacement of accounts, i.e. a recurring cost, and periods where the relay is `busy`/`offline` and the app is lossy — which is the contract.
- **Legal exposure.** A developer-operated service that turns "no subscription" into a signed full-track URL is predictor #2 in the project's own framework (the shape that took down Echo's original repo), and "no audio transits it" does not change that read. The user has been shown this analysis and has chosen to proceed; the mitigations in this spec (no audio transit, no shipped credentials, no marketing of the capability, truthful README, disposable addressing, org + mirror) reduce blast radius, not the theory. Not legal advice.
- **Qobuz `app_id`/`app_secret` scraping** (the 2019 Qo-DL notice's stated conduct) remains in both the relay and the BYO path; a secret-free signing path is an open question outside this spec.
