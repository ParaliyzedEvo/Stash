# Streaming Data Efficiency — Per-Network FLAC Quality, Save Data, and Cellular Budget

**Date:** 2026-06-18
**Status:** Design (awaiting review)
**Scope:** One spec, phased implementation. Phase 1 ships the quality-control layer; Phase 2 adds the cellular data budget.

---

## 1. Problem & current behavior

When streaming lossless audio, Stash resolves a Qobuz `format_id` (a "quality tier") and hands the signed CDN URL to ExoPlayer. The data cost is dominated by that tier.

**Audit (read from code, 2026-06-18):**

- `LosslessQualityTier` (in `:data:download`) maps tiers to Qobuz `format_id` codes and approximate sizes:
  - `CD` → code 6 → 16-bit/44.1 kHz → **~28 MB / 4-min track**
  - `HI_RES` → code 7 → 24-bit/96 kHz → **~70 MB / 4-min track**
  - `MAX` → code 27 → 24-bit/192 kHz → **~140 MB / 4-min track**
- **Streaming and downloads currently share ONE quality preference.** Both the download path (`QobuzSource.resolve` / `KennyySource.resolve`) and the streaming path (`QobuzSource.resolveImmediate` / `KennyySource.resolveImmediate`, called by `QobuzStreamResolver` / `KennyyStreamResolver`) read the same `LosslessSourcePreferences.qualityTierNow()` and pass `tier.qobuzCode` to `getFileUrl(...)`. The proxy then serves "highest available ≤ requested."
- **Therefore streaming is NOT necessarily pulling the maximum.** It pulls whatever the single shared tier is set to — default `HI_RES` (24/96) for fresh installs, `MAX` (24/192) for opted-in users.
- Existing network awareness: `ConnectivityMonitor.isCellular()` / `isConnected()` exist. `StreamingPreference` (DataStore `streaming_preference`) already has `enabled`, `streamOnCellular` (binary "refuse streaming on cellular", default false), a coarse `streamQuality` (`LOSSLESS` | `HIGH_QUALITY_LOSSY`), and the `forceYouTubeFallback` test toggle. `StreamSourceRegistry` already supports a "skip lossless → YouTube only" path.
- Prefetch (`PrefetchOrchestrator`) resolves only the *next track's URL* (a small API call) at 60% progress — it does **not** pre-buffer audio bytes. So prefetch is not a meaningful data cost; tier is.

**Goal:** reduce streaming data use without compromising quality, with the user in control. Specifically:
1. Split streaming quality from download quality, with **per-network tiers** (Wi-Fi vs Cellular).
2. A **Save Data** master that forces the lossless floor (CD) everywhere.
3. A **cellular data budget** (self-metered) that falls back to YouTube when exhausted.

**Non-goals:** changing download quality behavior; OS-level data accounting (`NetworkStatsManager`); per-track manual quality override; Wi-Fi data accounting.

---

## 2. Definitions

- **Quality tier** — `LosslessQualityTier` (CD / HI_RES / MAX). All lossless FLAC; they differ in bit depth / sample rate and therefore file size. CD (16/44.1) is transparent in blind listening; MAX (24/192) is ~5× the data for no audible playback benefit.
- **Per-network tier** — the tier Stash requests for streaming, chosen by the *active transport* (Wi-Fi/Ethernet vs Cellular).
- **Save Data** — a master override that forces CD on every network.
- **Cellular budget** — a monthly, self-metered cap on cellular streaming bytes; on exhaustion, cellular streaming falls back to the YouTube (lossy) resolver.

---

## 3. Architecture: one decision chokepoint

Today the quality decision is **buried inside the source** (`QobuzSource.resolveInternal` reads the pref itself, and the same method serves both download and streaming). That entanglement is the root cause of "streaming == download quality."

**Fix:** lift the decision out of the source into a single `StreamQualityPolicy` (in `:core:media`), and pass the chosen quality *into* the resolver.

```
StreamQualityPolicy.decide(): StreamDecision

  inputs:
    ConnectivityMonitor.isCellular()
    StreamingPreference { streamOnCellular, wifiTier, cellularTier, saveData }
    CellularBudget { enabled, limitBytes, usedBytes }   // Phase 2

  precedence:
    0. !isConnected                         → (caller already handles NoConnectivity)
    1. isCellular && !streamOnCellular       → RefuseCellular   // EXISTING hard gate, preserved
    2. budget.enabled && isCellular
         && usedBytes >= limitBytes          → ForceYouTube     // Phase 2
    3. saveData                              → Tier(CD)         // master floor
    4. isCellular                            → Tier(cellularTier)
    5. else (wifi/ethernet)                  → Tier(wifiTier)

  → StreamDecision = Tier(LosslessQualityTier) | ForceYouTube | RefuseCellular
```

**Consumers:**
- `QobuzStreamResolver` / `KennyyStreamResolver` call `policy.decide()`; on `Tier(t)` they pass `t.qobuzCode` into a new `resolveImmediate(query, requestedQuality)` overload.
- `StreamSourceRegistry.resolve` consumes `ForceYouTube` via its existing skip-lossless path (same mechanism as `forceYouTubeFallback`).
- `RefuseCellular` is surfaced as the existing "won't stream on cellular" outcome.
- **Downloads never call the policy.** `QobuzSource.resolve` / `KennyySource.resolve` keep reading `LosslessSourcePreferences.qualityTier` (download tier) and pass it into the same threaded `resolveInternal(query, bypassRateLimit, requestedQuality)`.

**Resolver refactor (shared by both phases):** `resolveInternal` currently reads `qualityTierNow()` internally and uses the result for (a) `getFileUrl`, (b) `urlInspector.isDegraded(url, requestedQuality)`, (c) codec mapping. Change it to accept `requestedQuality: Int` as a parameter. `resolve()` computes it from the download pref; `resolveImmediate(query, requestedQuality)` receives it from the stream resolver/policy. Behavior-preserving for downloads.

---

## 4. Phase 1 — Quality control (per-network tiers + Save Data)

### 4.1 Preferences
Extend the existing `StreamingPreference` (DataStore `streaming_preference`) — do **not** create a parallel store:
- `streamingWifiTier: LosslessQualityTier`
- `streamingCellularTier: LosslessQualityTier`
- `saveData: Boolean`

(The existing coarse `streamQuality` LOSSLESS|HQ_LOSSY remains as-is; it governs lossless-vs-lossy intent and is orthogonal to FLAC resolution. Folding/retiring it is an open item, §7.)

### 4.2 Migration (no silent quality change)
On first read with no new keys present:
- `streamingWifiTier` ← current `LosslessSourcePreferences.qualityTier` (so Wi-Fi keeps what they had).
- `streamingCellularTier` ← **CD** (the data-saving default requested).
- `saveData` ← false.

### 4.3 Settings UI (`SettingsAudioQualityScreen`)
- Re-label the existing lossless picker block → **"Download quality"** (unchanged binding to `losslessQualityTier`).
- New **"Streaming"** block:
  - Picker: **On Wi-Fi** (CD / Hi-Res / Max).
  - Picker: **On cellular** (CD / Hi-Res / Max).
  - Switch: **Save Data** — subtitle "Force CD quality on all networks to minimize data." When ON, the two pickers render disabled/greyed (visibly overridden) so the master relationship is obvious.
- Reuse existing `SettingsPickerRow` / `SettingsToggleRow` / `AudioQualityPicker` styling.
- `SettingsViewModel` / `SettingsUiState` gain the three fields + setters.

### 4.4 Outcome
Setting cellular = CD yields ~5× less cellular data with no audible loss. This layer alone delivers the bulk of the savings.

---

## 5. Phase 2 — Cellular data budget (self-metered + YouTube fallback)

### 5.1 Metering
- Attach a counting `TransferListener` to the `DataSource.Factory` that serves **lossless streaming** bytes. **Important:** in `StashMediaSourceFactory`, only YouTube-origin items go through `streamingFactory` (the refresh chain); **lossless Kennyy/Squid streams play through the plain `localFactory` (`DefaultMediaSourceFactory`).** Since the budget exists to meter FLAC (the dominant cost), the Phase 2 plan must identify where the lossless stream's `DataSource.Factory` is built and attach the listener there — wrapping both factory paths is the safe choice so neither lossless nor YT-fallback cellular bytes are missed. (Download path untouched.)
- On `onBytesTransferred`, add bytes **only when `ConnectivityMonitor.isCellular()`** is true.
- Accurate to actual streamed bytes; no permissions. Reads slightly under carrier counters (no TLS/retransmit overhead) — acceptable for a *protective* budget (under-counting → falls back marginally early). Settings copy says "approximate."

### 5.2 Budget state
New `CellularBudgetStore` (DataStore):
- `enabled: Boolean` (default false — opt-in)
- `limitBytes: Long` (user sets GB)
- `resetDayOfMonth: Int` (default 1; matches billing cycle)
- `usedBytes: Long`
- `cycleStartEpochMs: Long`
On read/increment, if now ≥ next reset boundary, zero `usedBytes` and advance `cycleStartEpochMs`.

### 5.3 At-limit behavior (hard fallback + warnings)
- Policy returns `ForceYouTube` for cellular once `usedBytes >= limitBytes` → registry skips Kennyy/Squid, routes via YouTube (lossy, ~5–10× smaller).
- **Warn at ~90%:** notification "Approaching your cellular data limit — streaming will switch to YouTube quality soon."
- **At 100%:** notification "Cellular data limit reached — streaming in YouTube quality until <reset date> or Wi-Fi," with a **"Use HD anyway"** action (one-cycle override).
- **Always exempt:** downloaded tracks (no network), Wi-Fi/Ethernet.

### 5.4 Settings UI
New **"Cellular data budget"** block: enable switch; limit field (GB); reset-day picker; live "X.X / Y GB used this cycle" readout.

---

## 6. Data flow (streaming tap, both phases)

```
User taps track (Online mode)
  → PlayerRepositoryImpl resolves stream URL
      → StreamSourceRegistry.resolve(track)
          → KennyyStreamResolver / QobuzStreamResolver
              → StreamQualityPolicy.decide()
                   Tier(t)      → source.resolveImmediate(query, t.qobuzCode)
                   ForceYouTube → registry skips to YouTubeStreamResolver
                   RefuseCellular → NotAvailable (existing cellular guard)
  → ExoPlayer plays via StashMediaSourceFactory
      → [Phase 2] counting TransferListener adds cellular bytes to CellularBudgetStore
```

---

## 7. Open items / risks (self-critique)

- **Save Data vs cellular=CD redundancy:** functionally overlapping. Kept because the user chose it and it's a recognizable one-tap affordance; mitigated by visibly disabling the per-network pickers when Save Data is on.
- **`streamQuality` (LOSSLESS|HQ_LOSSY):** RESOLVED — confirmed stored-but-unused today (`KennyyStreamResolver` KDoc states it explicitly; no production read). Leave it untouched in Phase 1; no regression risk.
- **Metering fidelity:** self-metering undercounts vs carrier; documented as approximate.
- **Re-buffering on skip:** ExoPlayer buffer-ahead can fetch slightly more than played; secondary optimization (buffer trim on cellular) deferred — not in scope.
- **`streamOnCellular=false` precedence:** preserved as a hard gate above the new tier/budget logic, so existing "no cellular streaming" users see no behavior change.

---

## 8. Testing

- **`StreamQualityPolicy`** — unit tests for every precedence branch (cellular off, save-data, per-network, budget exhausted → ForceYouTube, refuse-cellular). Pure/deterministic with injected `ConnectivityMonitor` + prefs.
- **Resolver refactor** — existing `QobuzSourceTest` / `KennyySourceTest` updated: `resolve` uses download tier, `resolveImmediate(quality)` uses the passed quality; assert the `format_id` actually sent to `getFileUrl`.
- **Migration** — first-read defaults (Wi-Fi inherits, cellular = CD, saveData off).
- **Phase 2 metering** — `TransferListener` increments only on cellular; cycle rollover zeroes usage on reset day; 90%/100% thresholds fire once each.
- **Settings** — ViewModel state/setters; Save Data disables pickers.

---

## 9. Phasing / rollout

- **Phase 1:** policy chokepoint + resolver refactor + per-network prefs + Save Data + Settings UI. High value, low risk; ships first.
- **Phase 2:** metering + budget store + at-limit routing + warnings + budget Settings UI.

Each phase gets its own implementation plan. The `StreamQualityPolicy` interface is designed in Phase 1 with the Phase-2 `ForceYouTube` branch already shaped (returns the decision; the budget inputs are wired in Phase 2).
