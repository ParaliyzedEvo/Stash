# qbdlx Qobuz Lossless Source — Design

**Date:** 2026-06-29
**Status:** Design (approach + token model approved; revised after spec review #1)
**Branch:** `feat/qbdlx-qobuz-source`

## 1. Goal & honest value

Add a 5th lossless source backed by **direct Qobuz API calls** using credentials from `qbdlx.launchpd.cloud` (a QobuzDownloaderX instance), for **download and progressive streaming**, FLAC.

**Honest value (narrowed after review):** the *only* real benefit is **operator/account-host redundancy** — a pool of 5–6 real Qobuz account tokens across countries, reached when kennyy/squid miss or their host is down. It is **not** independent: it rides the *same* Qobuz `app_id 798273057` kennyy uses (a Qobuz app-level ban takes both down together), and it uses the *same* plain `getFileUrl` path as kennyy/squid (if Qobuz degrades that path, qbdlx degrades identically). The earlier "forward-compatibility" claim is dropped — the secure path that would provide it is out of scope (§9). This is purely-additive redundancy, gated so its flakiness is harmless; weigh that modest benefit against the token-management cost below.

## 2. Verified facts (live, 2026-06-29)

Confirmed against the real Qobuz API during brainstorming:

- **Signing (exact, reproduced live):** `request_sig = md5(object+method + sorted_params + request_ts + app_secret)`. For `track/getFileUrl`: `md5("trackgetFileUrl" + "format_id"+fmt + "intentstream" + "track_id"+id + ts + secret)`. Correct sig → 200; wrong secret → 400. Reproduced all 4 HAR vectors.
- **Credentials:** `app_id = 798273057`, `app_secret = abb21364945c0583309667d13ca3d93a` (constant across the pool; verified live). app_id+secret are already public (shown on qbdlx's login page) — bundling them is fine.
- **Search:** `GET /api.json/0.2/catalog/search?query=&type=tracks&limit=N&app_id=` + `X-User-Auth-Token` header. **No signature.** Returns `tracks.items[]` (`id`, `title`, `performer.name`, `isrc`, `hires`, `maximum_bit_depth`). A **signed** variant exists as a fallback if Qobuz tightens this (pre-derive the sig string during build).
- **Resolve:** signed `GET /api.json/0.2/track/getFileUrl?track_id=&format_id=&app_id=&request_ts=&request_sig=&intent=stream` → JSON with plain `url` (Akamai `profile=raw` FLAC), plus `format_id`, `bit_depth`, `sampling_rate`, `restrictions[]`, `sample`. Verified: CDN 206, `audio/flac`, magic `fLaC`, Range-seekable. Same mechanism as kennyy.
- **Dead-token signal (authoritative = JSON body, NOT URL regex):** a dead/wrong-app token returns `restrictions:[{code:"UserUnauthenticated"}]`, `sample:true`, `format_id:5` (MP3 30s preview). The CDN URL *may* carry `range=20-30` but we do **not** rely on `LosslessUrlInspector`'s squid-style query regex here — the JSON fields are parsed in the client as the source of truth. (A real dead-token getFileUrl response is captured and pinned as a test fixture.)
- **Format restriction:** healthy token, hi-res unavailable for a track → `restrictions:[FormatRestrictedByFormatAvailability]`, downgraded `format_id` (e.g. 27→6). CD FLAC (6) is still lossless → accept. Only `format_id:5`/preview is rejected.
- **Token binding:** a `user_auth_token` is bound to its minting `app_id`; pool tokens are minted under `798273057`.
- **Quality (from `QobuzQuality`):** 27=hi-res≤192, 7=hi-res≤96, 6=CD 16/44.1, 5=MP3.

## 3. Architecture

### 3.1 Reuse
- `LosslessSource`, `SourceResult`, `TrackQuery`, `AudioFormat` — unchanged.
- `AggregatorRateLimiter` — add `configs["qbdlx_qobuz"]` (concrete numbers in §6).
- Download chain `LosslessSourceRegistry` — real Hilt `Set<LosslessSource>` multibinding (`@IntoSet`). Order is **preference-driven**, so qbdlx must also be appended to the default priority list in `LosslessSourcePreferences` (not just bound).
- Stream chain `StreamSourceRegistry` — **a hardcoded `buildList`, not a dynamic registry.** Adding qbdlx = constructor-inject the resolver + one `add(...)` line, **inside the `if (allowYtDlp)` gate** that already guards arcod/amz, so speculative background queue-fill doesn't spend pool-account quota.
- Matcher — `QobuzSource`'s `normalize`/`jaccard`/`artistSimilarity` are `internal`, `confidence`/`MIN_CONFIDENCE` are `private`: **not directly reusable, especially cross-module.** Extract the scoring into a `public` `QobuzCandidateMatcher` helper in `data:download` (shared by `QobuzSource` and the new source). The **stream resolver does not re-match** — it delegates to the source (see 3.2), so no matcher needs to cross into `core:media`.

### 3.2 New components
- **`QbdlxCredentialStore`** (`data:download`, pkg `lossless.qbdlx`): holds constant app_id+secret (`BuildConfig`) and the **token pool** (`BuildConfig`, each entry `token:country`). Plus a user-pasted token in `LosslessSourcePreferences` (DataStore) that takes **priority** over the pool. Responsibilities:
  - `activeToken()` — pasted token if present, else round-robin over live pool tokens (spread load — §6).
  - `tokensForRegion(country?)` — ordered token list to try for a region-locked track (matching country first), **bounded** (≤3 attempts) so one locked track can't fan out across all 6 accounts.
  - `markDead(token)` — persists a dead flag (DataStore) so cold starts don't re-probe dead tokens; cleared on a successful call. Dead = auth failure only (`UserUnauthenticated`), never region-lock or transient.
  - `allDead(): Boolean` — true when pasted + all pool tokens are dead → drives the §7 notification.
- **`QbdlxQobuzApiClient`** (`data:download`): direct-Qobuz client (distinct from the proxy `QobuzApiClient`). Signs (MD5, **injectable `request_ts` clock** for test vectors), attaches `X-App-Id` + `X-User-Auth-Token`, calls `catalog/search` + `track/getFileUrl` on `www.qobuz.com` via a derived OkHttp client (no shared-client mutation). Classifies the response into `Ok(url, format)` / `TokenDead` / `RegionLocked` / `Transient`(throw) from the JSON `restrictions`/`sample`/`format_id` — this classification, not the URL inspector, is authoritative.
- **`QbdlxQobuzSource : LosslessSource`** (id `"qbdlx_qobuz"`): `resolve(query)` and a `resolveImmediate(query)` bypass (mirrors `QobuzSource.resolveImmediate`) for the stream resolver. Flow: rate-limit acquire → search (ISRC-first) → match (shared matcher) → signed getFileUrl. On `TokenDead`: `markDead` + try next token (bounded); on `RegionLocked`: try `tokensForRegion` (bounded); accept downgraded-but-lossless. `SourceResult.format` is read from the **getFileUrl response**, not the search row. **Two gating predicates** (mirroring `QobuzSource`): `isEnabled()` = not circuit-broken AND not `allDead()` for the download/chain path; `isEnabledForStreaming()` = **`!allDead()` only** (deliberately omits the breaker) so a user tap on the stream path bypasses the circuit breaker like `QobuzSource.isEnabledForStreaming()`. `resolveImmediate` uses the streaming predicate; `resolve` uses `isEnabled()`.
- **`QbdlxStreamResolver`** (`core:media`): mirrors `QobuzStreamResolver` — **delegates to `QbdlxQobuzSource.resolveImmediate()`**, wraps the returned URL as `StreamUrl(url, origin="qbdlx", expires=etsp)`. No matching logic duplicated. Plays via the default media-source factory (plain https FLAC).
- **All-pool-dead notification** — reuse the squid `CaptchaExpiredNotifier` pattern: when `allDead()` trips, surface a "qbdlx tokens expired — paste a fresh one" badge/notice in Settings so the redundancy doesn't silently evaporate.
- **Settings**: a "qbdlx (Qobuz)" enable toggle + a "Paste token" field.

### 3.3 Token model (approved: bundle + paste-to-refresh)
- Constant app_id+secret bundled in `BuildConfig`.
- The 5–6 pool tokens bundled in `BuildConfig` (each `token:country`). **Accepted tradeoff (user-confirmed):** `rawnaldclark/Stash` is public and BuildConfig strings are extractable from released APKs, so these tokens are effectively published and will be banned faster than private ones. Mitigations that keep the feature working anyway: round-robin to spread load (§6), persisted dead-token skip, and the user-pasted token (priority) + all-dead notification as the refresh path when the pool ages out (~monthly).

## 4. Data flow

```
resolve(TrackQuery)  [or resolveImmediate for stream]
  ├─ gate: resolve → !isEnabled() return null (breaker OR allDead);
  │         resolveImmediate → !isEnabledForStreaming() return null (allDead only, NO breaker)
  ├─ rateLimiter.acquire("qbdlx_qobuz")
  ├─ token = credentialStore.activeToken()           (pasted > round-robin pool)
  ├─ search: catalog/search?query=<isrc | artist+title>&type=tracks   [X-User-Auth-Token]
  │    classify search response too: 401/UserUnauthenticated → markDead(token); rotate (bounded)
  ├─ match (shared QobuzCandidateMatcher; ISRC→0.95 else fuzzy; MIN_CONFIDENCE)
  ├─ getFileUrl(track_id, format_id=27, signed)   [X-App-Id + token]
  │    classify JSON:
  │      Ok            → SourceResult/StreamUrl (format from response; inspector as bonus only)
  │      RegionLocked  → for t in tokensForRegion(query.country)[:3]: retry getFileUrl(t)
  │      TokenDead      → markDead(token); for t in nextLiveTokens[:3]: retry
  │      Transient      → reportFailure; null
  └─ reportSuccess/Failure/RateLimited
```

## 5. Chain placement
- **Download** (`LosslessSourceRegistry` + default priority in `LosslessSourcePreferences`): last — after squid/kennyy/arcod/amz.
- **Stream** (`StreamSourceRegistry` `buildList`): appended **inside `if (allowYtDlp)`**, after arcod/amz, before YouTube.
- Both effectively gated by `LosslessSourceHealthGate` (cooldown) + `isEnabled()`/`allDead()`.

## 6. Rate limiting (concrete)
- `configs["qbdlx_qobuz"]`: `tokensPerSecond = 1.0/3.0` (1 req / 3s — the **slow end**, since direct-to-Qobuz on shared real accounts is higher ban-risk than a proxy), `burstCapacity = 2`, `circuitBreakAfter = 5`, `circuitBreakDurationMs = 10 min`, `rateLimitTripsBreaker = false` (a Qobuz 429 is "slow down", not "broken").
- **Per-account load:** the limiter keys one bucket by source id, but ban risk is per-account. `activeToken()` **round-robins** across live pool tokens so steady traffic spreads, instead of pinning (and banning) one. Region-locked retries are bounded (≤3) so a locked track can't multiply request volume across all accounts.

## 7. Error handling
- **Token dead** (`UserUnauthenticated`/`sample:true`/fmt5, on **either** the search or getFileUrl response — a dead token typically fails at search first) → `markDead` (persisted), rotate to next live token (bounded), retry; whole pool+pasted dead → `isEnabled()` false + Settings "expired, paste token" notice.
- **Region lock** (`FormatRestrictedByFormatAvailability` with no usable format, or upstream 4xx) → try country-matched then other tokens (bounded ≤3); token stays healthy.
- **Format downgrade** (hi-res→CD, still FLAC) → accept; report real delivered format.
- **429** → backoff via limiter, no breaker trip.
- **Transient/network** → reportFailure; null; not marked dead.

## 8. Testing
- **Unit (TDD):** sign() reproduces the 4 pinned HAR vectors (injectable `request_ts`); `QbdlxQobuzApiClient` classification via MockWebServer using pinned fixtures for Ok / UserUnauthenticated-preview / FormatRestricted / 429; `QbdlxCredentialStore` round-robin + persisted markDead + pasted-priority + allDead; shared matcher (ISRC boost, fuzzy gate); `QbdlxQobuzSource.resolve` (match→SourceResult with response-format; TokenDead→rotate; RegionLocked→bounded retry; allDead→isEnabled false); `QbdlxStreamResolver` delegates to `resolveImmediate`.
- **On-device E2E:** download → byte-verify `fLaC`; stream → audible; force all-pool-dead → graceful skip + Settings notice; region-locked track resolves via a different-country token; confirm pool round-robin spreads across tokens (log).

## 9. Out of scope (v1)
- Secure segmented `file/url` instant-stream path (HKDF/AES + `session/start`) — not needed; plain progressive streaming suffices.
- Qobuz session/favorites/account features.
- Runtime scraping of qbdlx's server-rendered token page.

## 10. Build inputs needed
- Full token pool: the other 4–5 tokens + their ISO-2 country codes (only `jM-6F2Qc…`/FR captured). Required for `BuildConfig` and for the §6 region matching to be non-blind.
