# qbdlx Multi-Token Pool — Design

**Date:** 2026-07-03
**Status:** Design (decisions locked in brainstorm; awaiting spec-review + user-review)
**Branch:** `feat/qbdlx-multi-token-pool`
**Continues:** `2026-06-29-qbdlx-qobuz-source-design.md` (the source itself, shipped v0.9.69)

## 1. Goal & honest value

Today Stash bundles **one** qbdlx Qobuz token (the FR one). The shared pool that qbdlx exposes has **4 usable tokens across 4 countries** (AR/FR/GB/NO) and rotates ~monthly. This spec takes Stash from that single token to the **whole live pool**, with:

1. **Fresh pool at every release** (CI fetches it at build time),
2. **Sticky-primary failover** (use one token until it dies, then auto-advance — replacing round-robin),
3. **A Settings "Token N" picker** (manual override + Auto),
4. **The tokens AES-encrypted in the APK** (defeat the casual `strings`/`dex-grep` that is literally how we found the pool).

**Why it matters:** qbdlx is the **primary** lossless source now (v0.9.70), so pool health *is* playback health. One token = one shared account carrying all load = one ban away from the source going dark. Four geo-spread tokens + failover + a manual picker is redundancy where it counts.

**Honest ceilings (not oversold):**
- Client-side encryption is defeatable by Frida / static analysis — the decrypt key ships in the app. The goal is raising cost past casual grep, **not a wall**.
- Log-stripping only stops the word "Qobuz" leaking via **logcat / user-shared diagnostics**; the source is still obvious from static dex analysis (`www.qobuz.com`, header names, class names remain). Real concealment needs the deferred R8/string-encryption pass.
- Sticky failover concentrates load on one account = higher per-account ban risk than round-robin's spread. The manual picker is the pressure-release; fast cooldown + paste-refresh cover the tail.

## 2. Verified facts (recon done, reproducible 2026-07-03)

- **The shared pool is an OPEN, unauthenticated JSON GET:** `https://citegptapi.f5.si/webhook/qbdlx/shared` (an n8n webhook on a free `.f5.si` domain, found by grepping the qbdlx SPA bundle). Returns `[{_id, token, country, createdAt, app_id, app_secret}]`.
- **Reachable from datacenter IPs** (a datacenter WebFetch retrieved it) → **GitHub Actions can fetch it**. Note `qbdlx.launchpd.cloud` *itself* 403s datacenter (residential-only), but the webhook does not.
- **As of 2026-07-03: 4 usable unique tokens** across AR/FR/GB/NO, all `app_id 798273057`, `app_secret abb21364945c0583309667d13ca3d93a` (same creds Stash already ships). The JSON also carries the FR token duplicated + empty/null placeholder rows → **must filter + dedupe**.
- **Usable filter:** `token` non-empty AND `app_id == "798273057"` AND `country` non-empty; dedupe by `token`; emit `token:country,token:country,…`.
- **Working curl recipe:** `curl -A "Mozilla/5.0 … Chrome/126" -H "Origin: https://qbdlx.launchpd.cloud" -H "Referer: https://qbdlx.launchpd.cloud/" https://citegptapi.f5.si/webhook/qbdlx/shared`.
- **`createdAt` is dropped** when we flatten to `token:country`, so it cannot be the stable key for picker labels — use `hash(token)` instead (§6).

## 3. Current state (what this modifies)

Grounded against the code on `master` (2026-07-03):

- `data/download/build.gradle.kts` reads `qbdlx.tokenPool` / `QBDLX_TOKEN_POOL` (plaintext) → `buildConfigField QBDLX_TOKEN_POOL`.
- `QbdlxCredentialStore.poolRaw = BuildConfig.QBDLX_TOKEN_POOL` (an `internal var`, test-overridable). `activeToken()` = pasted-if-live → else **round-robin** over live pool tokens via an `AtomicInteger rrIndex`. `markDead` writes an in-memory, time-boxed `deadUntil` map (60s cooldown, deliberately not persisted). `allDead()` drives the Settings badge + gates the source.
- `QbdlxQobuzSource` calls `activeToken()`, and on `markDead` re-fetches `activeToken()` and retries once, bounded by a `tried`-set + `MAX_TOKEN_ATTEMPTS = 6`.
- `QbdlxApiClient` / `QbdlxQobuzSource` log under TAGs `"QbdlxApiClient"` / `"QbdlxQobuzSource"`.
- `SettingsAudioQualityScreen` shows a qbdlx toggle + (when enabled) an all-dead badge + a "Paste token" `OutlinedTextField`. `SettingsViewModel` exposes `qbdlxEnabled` / `qbdlxExpired` and `onQbdlxTokenPaste`.
- `release.yml` passes `QBDLX_*` from secrets to `assembleRelease`, then a "Verify bundled credentials" step greps the decompressed dex for plaintext `app_id` + first pool token, failing the job before publish if absent.

## 4. Architecture — embed-obfuscated, broker-ready

### 4.1 The one seam
```kotlin
fun interface QbdlxPoolProvider { fun rawPool(): String }
```
- `QbdlxModule` adds one `@Provides` returning `QbdlxPoolCipher.decrypt(BuildConfig.QBDLX_TOKEN_POOL)`.
- `QbdlxCredentialStore` gains a `QbdlxPoolProvider` constructor param; `poolRaw` initializes from `provider.rawPool()` (stays `internal var` for tests).
- A future runtime/Worker broker swaps that single `@Provides`. **Failover, picker, and the source are untouched.**
- `ponytail:` `rawPool()` is synchronous — a real broker fetch is async, but the embedded path is sync and adding `suspend` now speculates on an API that doesn't exist. Broker can cache-then-expose-sync or change the seam when it lands.

### 4.2 Encryption (`QbdlxPoolCipher`)
- **AES-256-GCM** via `javax.crypto` (no new dependency).
- Key = `SHA-256(passphrase)`; the passphrase is assembled from ≥2 string fragments so it is not one greppable literal.
- Blob = `Base64.encodeToString(IV(12) ‖ ciphertext ‖ GCM-tag(16), NO_WRAP)` → the single `QBDLX_TOKEN_POOL` BuildConfig string.
- **Encrypt at build in `build.gradle.kts`** (Gradle JVM has `javax.crypto`); local.properties + CI both carry **plaintext** `token:country,…`, so no one pre-encrypts by hand. **Blank plaintext → emit `QBDLX_TOKEN_POOL=""` directly (skip encryption)**, so an unconfigured local build still yields an empty pool → paste path (not a non-empty blob that decrypts to `""`).
- **Decrypt at runtime in `QbdlxPoolCipher.decrypt(blob): String`**. Blank input, malformed base64, or a failed GCM auth tag → return `""` (empty pool → `allDead()` → paste prompt). **Never throws to the caller.**
- `ponytail:` encrypt (Gradle) + decrypt (runtime) are two unavoidable copies of ~15 lines — a shared module for this is overkill.
  - **Drift residual, stated honestly:** if the two copies diverge, real builds produce blobs the runtime can't decrypt → silent empty pool. Three guards, no CI JVM needed: (1) the fixture round-trip test (§11) — a checked-in blob generated once by the build-side scheme, decrypted by the runtime, so a scheme edit on either side that isn't mirrored fails the test; (2) a **loud comment at both crypto sites** pointing at that fixture ("change this → regenerate the fixture"); (3) a runtime sanity check: after decrypt, if `QBDLX_POOL_FP` (§9) is non-blank but `sha256(decrypted).take(8) != QBDLX_POOL_FP`, log a release-visible warning ("qbdlx pool decrypt mismatch") — turns a silent decrypt-drift into a named signal in the diagnostics we already rely on. The mandatory on-device verify before ship is the backstop (an empty pool shows immediately as the paste prompt).

### 4.3 What is and isn't encrypted
- **Encrypted:** the token pool only. It is the sensitive, rotating, ban-risk secret and the thing casual grep finds.
- **Plaintext (unchanged):** `app_id` / `app_secret` — public on qbdlx's login page, and encrypting them would not conceal the source anyway (`www.qobuz.com`, `X-User-Auth-Token`, the `lossless.qbdlx` package + class names all stay in the dex).

## 5. Freshness — CI auto-fetch at build

`release.yml` gains a **fetch step before assemble**:
```bash
POOL=$(curl -sf -A "$UA" \
        -H "Origin: https://qbdlx.launchpd.cloud" -H "Referer: https://qbdlx.launchpd.cloud/" \
        https://citegptapi.f5.si/webhook/qbdlx/shared \
  | jq -r '[.[] | select(.token!=null and .token!="" and (.app_id|tostring)=="798273057"
            and .country!=null and .country!="")]
            | unique_by(.token) | map(.token+":"+.country) | join(",")' 2>/dev/null)
if [ -n "$POOL" ]; then
  echo "QBDLX_TOKEN_POOL=$POOL" >> "$GITHUB_ENV"
  echo "Fetched $(echo "$POOL" | tr ',' '\n' | wc -l) qbdlx tokens from pool"
else
  echo "::warning::pool fetch failed/empty — falling back to QBDLX_TOKEN_POOL secret"
  echo "QBDLX_TOKEN_POOL=${{ secrets.QBDLX_TOKEN_POOL }}" >> "$GITHUB_ENV"
fi
```
- The **assemble step drops `QBDLX_TOKEN_POOL` from its hardcoded `env:` block** so the `$GITHUB_ENV` value (fetched-fresh or secret-fallback) flows through. `QBDLX_APP_ID` / `QBDLX_APP_SECRET` stay as direct `secrets.*` refs.
- **Build must never break:** any curl failure, non-200, malformed JSON, or empty filtered result → secret fallback. Refreshes only at ship time — acceptable because geo-spread tokens + failover + paste cover the tail (the in-app runtime refresh is deferred to the §4.1 broker seam).

## 6. Failover — sticky-primary (replaces round-robin)

Only `QbdlxCredentialStore` internals change; `QbdlxQobuzSource`'s rotation loop is untouched (it already re-reads `activeToken()` after `markDead` and retries once, bounded by its `tried`-set).

- Replace `AtomicInteger rrIndex` with `@Volatile var activePrimary: String?`. `ponytail:` `@Volatile` gives visibility, not atomicity — two concurrent resolves could both pick-and-set a primary, which is benign (both pick a live token, last write wins, no corruption). Round-robin's atomic counter existed for even spread; sticky doesn't need it.
- **Canonical order = pool sorted by `token.hashCode()` ascending, tie-broken by the token string** (both deterministic and portable — `String.hashCode()` has a fixed contract — so labels/order are stable across pool refresh; used by both failover "first live" and picker labels).
- `activeToken()` priority (**pasted wins** — user-confirmed):
  1. pasted token, if live → use it (**not** required to be a pool member — it's the user's own token)
  2. pinned pool token (§7), if live **AND still present in the current pool** → use it
  3. `activePrimary`, if still live → use it
  4. else first live token in canonical order → set it as `activePrimary`, use it
  5. nothing live → `null`
- **A pin to a token no longer in the pool (removed by a refresh) is ignored** — priority 2 requires `pool().any { it.first == pinned }`, so a stale pin silently falls through to Auto (the token literally no longer exists). This is the one non-obvious correctness point: without the membership check, `isDead` returns false for an unknown token and a removed pin would be used forever.
- `markDead(token)`: existing 60s cooldown write, **plus** if `token == activePrimary`, null the pointer so the next call advances.
- A pinned/pasted token that recovers after its cooldown resumes automatically (checked first each call — falls out for free).
- **Seamless-proof:** resolved FLAC URLs live ~1hr (`etsp`), so a dead token never cuts the *current* track; failover only bites at the *next* resolve, and `resolveImmediate` + prefetch hide the latency.
- `tokensForRegion` (region-locked fan-out) is unchanged — a separate bounded path, not the primary pointer.

## 7. Settings picker — anonymized "Token N"

### 7.1 Store
- New `qbdlx_creds` DataStore key `pinned_token` (raw token string; absent = Auto).
- `pinnedToken(): String?` / `setPinnedToken(token: String?)`.
- `poolForPicker(): List<TokenChoice>` where
  ```kotlin
  data class TokenChoice(val label: String, val token: String, val country: String, val live: Boolean)
  ```
  built from the pool in **canonical `hash(token)` order**, `label = "Token ${index+1}"`, `live = !isDead(token)`. **The raw token never reaches UI text** — it's the stable id behind the label only.
- `activeToken()` consults `pinnedToken()` at priority 2 (§6), honored only if the pinned token is a live member of the current pool.
- **The picker's `live` dot reflects `isDead` at compute time** (recomputed on load/paste/pin, not continuously) — it's a hint, not a real-time indicator. `ponytail:` no live-updating flow; YAGNI.

### 7.2 UI (`SettingsAudioQualityScreen`, inside the existing `qbdlxEnabled` AnimatedVisibility block, above the paste field)
- Reuse the `selectableGroup()` radio pattern already used for the quality tiers in this screen.
- Rows: **"Auto (recommended)"** then one per `TokenChoice`: `"Token 1 · GB ●"` (● = green live / red dead).
- Shown only when the pool is non-empty; a tokenless build shows just the paste field + badge (as today).

### 7.3 ViewModel (`SettingsViewModel`)
- `qbdlxTokenChoices: StateFlow<List<TokenChoice>>` and `qbdlxPinnedToken: StateFlow<String?>`. The UI maps `qbdlxPinnedToken` to a selected row by matching `TokenChoice.token`; **no match → "Auto" is selected** (covers a pin to a since-removed token).
- `onQbdlxTokenPinned(token: String?)` → `credentialStore.setPinnedToken(...)` then refresh choices.
- Recompute choices on load / after paste / after pin (same pattern as the existing `refreshQbdlxExpired`). `_qbdlxTokenChoices` must be declared **above** the `init{}` block (the init-order NPE lesson from the qbdlx source work).

## 8. Anti-RE — log strip

- Rename the log TAG containing "Qobuz": `QbdlxQobuzSource` → `"QbdlxSource"` (keep `"QbdlxApiClient"`; it has no "Qobuz").
- Audit qbdlx log **message** strings for "Qobuz"/"qobuz"/"qobuz.com" and neutralize (current messages log only the endpoint tail like `getFileUrl`/`search`, which is already clean — verify none regressed).
- Diagnostic logs themselves **stay** (they're how the blank-creds bug got caught); only the source-identifying word is scrubbed. Applied unconditionally (renaming a tag in debug too costs nothing — no `BuildConfig.DEBUG` branching).

## 9. CI dex-verify gate — rewritten

The old gate greps the dex for plaintext app_id + first token; encryption removes the token literal (the point), so it becomes three checks:

1. **app_id `798273057` present** in the decompressed dex — BuildConfig wired. *(keep)*
2. **plaintext first token ABSENT** from the dex — proves encryption actually ran; catches a silent regression that ships plaintext. *(new, inverts the old check)*
3. **`QBDLX_POOL_FP` present** — a new **non-secret** BuildConfig field `= sha256(plaintextPool).take(8)` (hex; emitted as `""` when the pool is blank, matching §4.2). The workflow computes the same fp from its pool with **`printf %s "$QBDLX_TOKEN_POOL" | sha256sum | head -c 8`** (`printf %s`, no trailing newline, or the hashes won't match) and greps the dex for it.
   - This is the real "non-blank, *current* pool actually embedded" guard — the exact v0.9.65–v0.9.69 blank/stale-pool failure class — without exposing tokens and without the per-build random IV defeating a ciphertext grep.

**Critical wiring:** both the fetch step (§5) and this verify step must read `QBDLX_TOKEN_POOL` from `$GITHUB_ENV`, **not** re-declare it as `${{ secrets.QBDLX_TOKEN_POOL }}` in their `env:` blocks. The current verify step does re-declare it — that must be removed, or the fp would be computed over the secret while the dex carries the fetched pool → false-fail. Keep the pre-build `if [ -z "$QBDLX_TOKEN_POOL" ] … exit 1` guard.

**Runtime companion to fp (§4.2):** the same `QBDLX_POOL_FP` field also powers the runtime decrypt-drift sanity log — a non-blank fp that doesn't match `sha256(decrypted).take(8)` warns loudly instead of silently emptying the pool.

## 10. Files touched

| File | Change |
|---|---|
| `data/download/build.gradle.kts` | AES-GCM encrypt pool before `buildConfigField`; add `QBDLX_POOL_FP` field |
| `…/lossless/qbdlx/QbdlxPoolCipher.kt` | **new** — `decrypt` (runtime) + `encrypt` (test/reference) |
| `…/lossless/qbdlx/QbdlxPoolProvider.kt` | **new** — `fun interface` seam |
| `…/lossless/qbdlx/di/QbdlxModule.kt` | `@Provides QbdlxPoolProvider` (decrypt) |
| `…/lossless/qbdlx/QbdlxCredentialStore.kt` | inject provider; sticky `activeToken()`; `markDead` clears primary; `pinnedToken`/`setPinnedToken`; `poolForPicker`; canonical hash order |
| `…/lossless/qbdlx/QbdlxQobuzSource.kt` | TAG rename only (behavior unchanged) |
| `…/lossless/qbdlx/QbdlxApiClient.kt` | TAG/message audit |
| `feature/settings/…/SettingsViewModel.kt` | token-choices + pinned flows, `onQbdlxTokenPinned` |
| `feature/settings/…/SettingsAudioQualityScreen.kt` | picker UI |
| `.github/workflows/release.yml` | fetch step; drop hardcoded pool env; rewrite verify step |
| tests | `QbdlxPoolCipherTest`; extend `QbdlxCredentialStoreTest` |

## 11. Tests (JVM unit, no new frameworks)

- **`QbdlxPoolCipherTest`:**
  - round-trip: `decrypt(encrypt(pool)) == pool` for a multi-entry pool.
  - **fixture drift guard:** a checked-in base64 blob (produced once by the build-side path) decrypts to its known plaintext — fails if either copy of the scheme drifts.
  - blank / malformed base64 / tampered tag → `""` (never throws).
- **`QbdlxCredentialStoreTest` (extend):**
  - **Constructor gains a `QbdlxPoolProvider`** — tests pass a trivial `QbdlxPoolProvider { "" }` and keep overriding `poolRaw` directly (the existing seam), e.g. `QbdlxCredentialStore(ctx) { "" }.also { it.poolRaw = pool }`. The current `round-robin advances…` test is **replaced** by the sticky test below.
  - sticky: `activeToken()` returns the same token across calls until `markDead`, then advances to the next live one (not round-robin).
  - priority: pasted (live) beats a pinned pool token beats sticky-auto.
  - pinned token dead → advances; recovers after cooldown → resumes.
  - **pin to a token not in the pool → ignored** (falls through to Auto/sticky).
  - `poolForPicker()` labels are stable under pool reordering (hash order), `live` reflects `deadUntil`.
  - empty pool → `poolForPicker()` empty, `allDead()` true (existing invariant preserved).

## 12. Out of scope (deferred)
- In-app runtime pool refresh / Cloudflare-Worker broker — the §4.1 seam is the drop-in point; not built now.
- R8 minify/string-encryption of the whole source (the real source-concealment pass) — risky with this repo's Hilt multibinding + ffmpeg JNI + serialization; its own device-verified effort.
- Encrypting app_id/app_secret — public, low value (§4.3).
