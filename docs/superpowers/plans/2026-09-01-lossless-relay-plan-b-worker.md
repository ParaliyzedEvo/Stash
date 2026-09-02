# Lossless Relay Worker (Plan B) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the server half of Plan B — a Cloudflare Worker at `infra/lossless-relay/` that mints Qobuz FLAC URLs for the already-shipped client, plus the tooling that publishes the signed `lossless.json` the client fetches — and prove it end to end on a device with no Qobuz account.

**Architecture:** One Worker (`stash-relay`) with a D1 database for rotation state, the shared mint cache and daily quotas; a rate-limit binding for the per-IP cap; Qobuz account tokens in a Worker secret (D1 holds no credential). The signed config is served by the already-deployed tipjar Worker out of its KV (`/lossless.json` + `.sig`), so no new hostname enters the APK and no key enters git history. Audio never transits the relay (spec §4).

**Tech Stack:** Cloudflare Workers (wrangler 4, `nodejs_compat`), D1 (SQLite), Workers rate-limit binding, Node 24 (`node --test`; `node:sqlite` stands in for D1 in tests; `node:crypto` for MD5/HMAC/ECDSA). No runtime dependencies.

**Spec:** `docs/superpowers/specs/2026-08-31-lossless-relay-plan-b-contract.md` — §1-4 are the wire contract the shipped client enforces, §5-6 the settled decisions. Read it first. Client sources of truth: `data/download/src/main/kotlin/com/stash/data/download/lossless/relay/LosslessRelayClient.kt`, `.../relay/LosslessConfigFetcher.kt`, `.../qbdlx/QbdlxApiClient.kt` (`getFileUrl` + `classify`), `.../qbdlx/QbdlxSigner.kt`, `.../qbdlx/QobuzLoginClient.kt`.

---

## Scope

**This plan does NOT include:** Tidal (spec §5.1); per-install registration (§5.4's upgrade path); a negative cache for 404s; Date-header skew correction; catalog/playback account roles (the `role` column exists per spec §5.3; nothing reads it yet); an admin HTTP surface (ops go through `wrangler d1 execute`); the donation goal tracker (§6.5, separate work); CI deploy automation (the sibling Workers deploy by hand and so does this one).

**Preconditions (operator, not code):**
- **PR #465** (`feat/relay-request-signing`) must be merged **before Task 10 Step 2 sets the release secrets**: from that moment a release cut from master fetches the config, and a client that cannot sign gets 401 on every mint and a 5 min cooldown. Task 11 needs it on the device for the same reason. PR #464 is independent.
- `npx wrangler login` works. This machine currently reports "Not logged in"; `infra/tipjar-worker/setup.ps1` shows the `CLOUDFLARE_API_TOKEN` alternative.
- At least one paid or trial Qobuz account for the first `QOBUZ_ACCOUNTS` entry. The device-verified trial on the Pixel 6 qualifies (spec §6.4: trials carry lossless entitlement).

## Decisions this plan adds to the spec (Task 8 records them there)

| Decision | Choice | Why |
|---|---|---|
| Config host (§3) | The tipjar Worker serves `GET /lossless.json` and `/lossless.json.sig` from `STASH_KV` | Already deployed, hostname already disclosed in README, KV is "rarely written, often read", bytes served exactly as stored. GitHub raw would put the relay key in public git history; the relay serving its own config would put the relay hostname in the APK. |
| Where account tokens live | Worker secret `QOBUZ_ACCOUNTS` (JSON array); D1 `accounts` holds rotation state only, keyed by `label` | Secrets are encrypted at rest and `d1 execute` can never print one; re-seeding a token is `wrangler secret put`, never SQL with a token on a command line. |
| Per-install and per-IP cap status | **429** with `Retry-After` | The client cools a non-2xx/non-503 base for 5 min (spec §1), the right backoff for a runaway install; 503 would have it retry every 60 s. Global exhaustion stays **503** (§5.5). |
| Caps | `[vars]` in `wrangler.toml`: global 600/day, install 100/day, per account 20/h and 200/day | Tunable by redeploy, not code. |
| Attempts per request | 2 accounts max, 3 s upstream timeout each | Two attempts plus the D1 round trips fit inside the client's 8 s budget (§1). A Worker that overran it would turn the intended 503 (60 s cooldown) into a client-side timeout (5 min cooldown). |
| Upstream classification | 401, 403 `USER_BLOCKED`, or a preview body → account **dead** (operator re-logs in); anything else, Qobuz 400 included → account cools 5 min | Mirrors `QbdlxApiClient` exactly. A 400 is more likely our bug than a dead account and must not kill the pool. |
| `/v1/status` body | `{"ok":true}` | The client reads no body (§2). |
| Non-lossless formats (Data Saver's `format_id=5`) | **404**, no Qobuz call; 400 only for malformed input | The relay is lossless-only and the client's router discards any relay format below 6; 404 carries no cooldown, so the phone falls to JioSaavn AAC 320 as Save Data intends. A 400 would cool the relay 5 min for every Save Data user. |

## File structure

```
infra/lossless-relay/
  package.json                 scripts dev / deploy / tail / test; devDependency wrangler only
  wrangler.toml                bindings DB (D1) + MINT_RL (rate limit), [vars] caps, hourly cron
  migrations/0001_init.sql     accounts (state only), mints (shared cache), quota (daily counters)
  .dev.vars.example            names of the secrets `wrangler dev` needs (the real .dev.vars is gitignored)
  src/index.js                 fetch + scheduled handlers, the mint flow; `handle()` exported for tests
  src/auth.js                  verifyMint — HMAC + skew check (spec §5.4), pure
  src/qobuz.js                 signGetFileUrl (MD5), classify, etspOf, extractCreds, mintFromQobuz
  src/db.js                    every SQL statement, over the D1 binding
  src/config.js                buildConfig — canonical signed-config bytes (monotonic updated_at, normalised relays)
  scripts/login.mjs            email + password → one QOBUZ_ACCOUNTS entry (the call QobuzLoginClient makes)
  scripts/publish-config.mjs   keygen / pubkey / relaykey / publish / verify
  scripts/sign-request.mjs     prints curl headers for a signed smoke-test mint
  test/fake-d1.js              node:sqlite shim with D1's prepare/bind/first/all/run/batch
  test/auth.test.js  test/qobuz.test.js  test/db.test.js  test/config.test.js  test/index.test.js
  README.md                    deploy recipe + ops runbook (structure of infra/lastfm-proxy/README.md)
infra/tipjar-worker/src/index.js   MODIFY: GET /lossless.json(.sig) from KV ahead of the supporters GET
.gitignore                         MODIFY: .wrangler/, .dev.vars, *.pem
docs/superpowers/specs/2026-08-31-lossless-relay-plan-b-contract.md   MODIFY: new §5.6, §7
README.md                          MODIFY (Task 12): name the relay and config hosts
```

Conventions copied from `infra/lastfm-proxy`: ESM (`"type": "module"`), 4-space indent, a JSDoc header per file, pure functions exported for `node --test`, a local `json()` helper. Work on `feat/lossless-relay-worker` off `master` in a worktree (`superpowers:using-git-worktrees`); copy `local.properties` into it for Task 11.

All commands are bash (Git Bash or the Bash tool), not PowerShell, and run from `infra/lossless-relay/` unless a path says otherwise. `npm test` is `node --no-warnings --test`; the silenced warning is `node:sqlite`'s experimental notice on Node 24. Times in code are unix **seconds**; day and hour keys are UTC so every cap resets at midnight UTC together with ARCOD's.

---

### Task 1: Scaffold the Worker project

**Files:**
- Create: `infra/lossless-relay/package.json`, `infra/lossless-relay/wrangler.toml`, `infra/lossless-relay/migrations/0001_init.sql`, `infra/lossless-relay/.dev.vars.example`, `infra/lossless-relay/src/index.js` (placeholder), `infra/lossless-relay/test/smoke.test.js`
- Modify: `.gitignore`

- [ ] **Step 1: `package.json`**

```json
{
  "name": "stash-relay",
  "version": "1.0.0",
  "private": true,
  "type": "module",
  "description": "Cloudflare Worker that mints short-lived Qobuz FLAC URLs for the Stash app from a small pool of operator-owned accounts. Audio never transits it.",
  "scripts": {
    "dev": "wrangler dev",
    "deploy": "wrangler deploy",
    "tail": "wrangler tail",
    "test": "node --no-warnings --test"
  },
  "devDependencies": {
    "wrangler": "^4.120.0"
  }
}
```

- [ ] **Step 2: `wrangler.toml`**

```toml
name = "stash-relay"
main = "src/index.js"
# nodejs_compat is on by default from 2026-08-04; declared anyway so node:crypto is an explicit dependency.
compatibility_date = "2026-08-04"
compatibility_flags = ["nodejs_compat"]

# Caps (spec §5.4, §6.2, §7). Strings: TOML vars reach the Worker as strings.
[vars]
GLOBAL_DAILY_CAP = "600"      # 3 live accounts x 200
INSTALL_DAILY_CAP = "100"
ACCOUNT_HOURLY_CAP = "20"
ACCOUNT_DAILY_CAP = "200"

# Rotation state, mint cache, daily counters (spec §5.2: D1, not KV, because of KV's write limit).
[[d1_databases]]
binding = "DB"
database_name = "stash-relay"
database_id = "REPLACE_WITH_D1_ID"   # printed by `npx wrangler d1 create stash-relay` (Task 9)
migrations_dir = "migrations"

# Per-IP cap in front of everything (spec §5.4). Counts every /v1/qobuz/file request, hits included.
[[ratelimits]]
name = "MINT_RL"
namespace_id = "1001"
simple = { limit = 30, period = 60 }

# Hourly prune of expired mints and stale quota rows.
[triggers]
crons = ["17 * * * *"]

# Secrets: `npx wrangler secret put <NAME>`, never committed.
#   RELAY_KEY       hex HMAC key. The SAME value is published as `relay_key` in lossless.json.
#   RELAY_KEY_PREV  optional: the previous key, accepted during a rotation window.
#   QOBUZ_ACCOUNTS  JSON array of {"label","token","app_id","app_secret"} entries from scripts/login.mjs.
```

- [ ] **Step 3: `migrations/0001_init.sql`**

```sql
-- Rotation state only. Tokens live in the QOBUZ_ACCOUNTS secret, joined by label.
CREATE TABLE accounts (
  label         TEXT PRIMARY KEY,
  state         TEXT    NOT NULL DEFAULT 'live',   -- live | reserve | dead
  role          TEXT    NOT NULL DEFAULT 'playback', -- spec §5.3: kept for a later catalog/playback split; nothing reads it yet
  cooling_until INTEGER NOT NULL DEFAULT 0,        -- unix seconds; a transient failure parks an account here
  last_used_at  INTEGER NOT NULL DEFAULT 0,        -- the LRU key (spec §5.3)
  hour_key      TEXT    NOT NULL DEFAULT '',       -- "2026-09-01T17" (UTC)
  hour_n        INTEGER NOT NULL DEFAULT 0,
  day_key       TEXT    NOT NULL DEFAULT '',       -- "2026-09-01" (UTC)
  day_n         INTEGER NOT NULL DEFAULT 0,
  dead_reason   TEXT    NOT NULL DEFAULT ''
);

-- Shared mint cache (spec §6.3): one Qobuz call serves everyone playing that track for ~1 h.
CREATE TABLE mints (
  track_id      INTEGER NOT NULL,
  format_id     INTEGER NOT NULL,   -- the REQUESTED format; got_format_id is what Qobuz returned
  url           TEXT    NOT NULL,
  got_format_id INTEGER NOT NULL,
  bit_depth     INTEGER NOT NULL,
  sample_rate   INTEGER NOT NULL,   -- Hz
  etsp          INTEGER NOT NULL,   -- unix seconds, parsed from the URL itself
  PRIMARY KEY (track_id, format_id)
);

-- Daily counters: key = 'global' or 'i:<install id>'. The cron prunes old days.
CREATE TABLE quota (
  day TEXT NOT NULL,
  key TEXT NOT NULL,
  n   INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (day, key)
);
```

- [ ] **Step 4: `.dev.vars.example`** (the real `.dev.vars` is gitignored and carries a real account for local runs)

```
RELAY_KEY=dev-key
QOBUZ_ACCOUNTS=[{"label":"dev","token":"…","app_id":"…","app_secret":"…"}]
```

- [ ] **Step 5: placeholder `src/index.js` plus a smoke test so `npm test` has something to run**

```js
export default {
    async fetch() {
        return new Response(JSON.stringify({ error: "not_found" }), { status: 404, headers: { "content-type": "application/json" } });
    },
};
```

`test/smoke.test.js`:
```js
import { test } from "node:test";
import assert from "node:assert/strict";
import worker from "../src/index.js";

test("worker module exports a fetch handler", () => {
    assert.equal(typeof worker.fetch, "function");
});
```

- [ ] **Step 6: root `.gitignore`**: append after the `node_modules/` line

```
# Cloudflare Workers local state and local secrets (infra/*)
.wrangler/
.dev.vars
*.pem
```
`git ls-files | grep -E '\.pem$'` is empty today (checked while writing this plan), so the pattern hides nothing tracked.

- [ ] **Step 7: Install and run**

Run: `npm install && npm test`
Expected: `# pass 1`, `# fail 0`.
Run: `npx wrangler deploy --dry-run --outdir .wrangler/dry`
Expected: bundles with no config error (the placeholder D1 id is fine for a dry run). If wrangler rejects `[[ratelimits]]`, it is older than 4.36: `npm i -D wrangler@latest`. If it says the `compatibility_date` is in the future, use the newest date it accepts and keep the explicit `nodejs_compat` flag.

- [ ] **Step 8: Commit**

```bash
git add infra/lossless-relay/package.json infra/lossless-relay/package-lock.json infra/lossless-relay/wrangler.toml infra/lossless-relay/migrations/0001_init.sql infra/lossless-relay/.dev.vars.example infra/lossless-relay/src/index.js infra/lossless-relay/test/smoke.test.js .gitignore
git commit -m "feat(relay): scaffold the stash-relay Worker (D1 schema, bindings, caps, test runner)"
```

---

### Task 2: Request authentication (`src/auth.js`)

**Files:**
- Create: `infra/lossless-relay/src/auth.js`, `infra/lossless-relay/test/auth.test.js`

- [ ] **Step 1: Write the failing test.** The fixture is the client's own (`LosslessRelayClientTest`: key `k-secret`, install `0f7c3a2e-install`, track 42, format 27, ts 1700000000). The hex was computed with `node:crypto` while writing this plan; pinning it proves both sides MAC the same string.

```js
import { test } from "node:test";
import assert from "node:assert/strict";
import { verifyMint } from "../src/auth.js";

const H = {
    install: "0f7c3a2e-install",
    ts: "1700000000",
    auth: "fc6ad1b240aa21efc87e1088841bd3b2eac9bb5139d855de9dde6e0729bb0532",
};

test("accepts the client's HMAC vector", () => {
    assert.equal(verifyMint(H, 42, 27, 1700000000, ["k-secret"]), true);
});

test("accepts the previous key during a rotation; blanks are skipped", () => {
    assert.equal(verifyMint(H, 42, 27, 1700000000, ["new-key", "k-secret"]), true);
    assert.equal(verifyMint(H, 42, 27, 1700000000, ["new-key", undefined]), false);
});

test("rejects a wrong key, a tampered track id, and a tampered install id", () => {
    assert.equal(verifyMint(H, 42, 27, 1700000000, ["other"]), false);
    assert.equal(verifyMint(H, 43, 27, 1700000000, ["k-secret"]), false);
    assert.equal(verifyMint({ ...H, install: "someone-else-1" }, 42, 27, 1700000000, ["k-secret"]), false);
});

test("rejects a timestamp outside ±300 s in either direction", () => {
    assert.equal(verifyMint(H, 42, 27, 1700000000 + 300, ["k-secret"]), true);
    assert.equal(verifyMint(H, 42, 27, 1700000000 + 301, ["k-secret"]), false);
    assert.equal(verifyMint(H, 42, 27, 1700000000 - 301, ["k-secret"]), false);
});

test("rejects malformed or missing headers without throwing", () => {
    assert.equal(verifyMint({ install: "", ts: "x", auth: "zz" }, 42, 27, 1700000000, ["k-secret"]), false);
    assert.equal(verifyMint({}, 42, 27, 1700000000, ["k-secret"]), false);
    assert.equal(verifyMint({ ...H, auth: H.auth.slice(0, 63) }, 42, 27, 1700000000, ["k-secret"]), false);
});
```

- [ ] **Step 2: Run it**: `npm test` → FAIL with `Cannot find module '../src/auth.js'`.

- [ ] **Step 3: Implement**

```js
import { createHmac, timingSafeEqual } from "node:crypto";

/** Clock skew tolerated on X-Stash-Ts, in seconds (spec §5.4: ±5 min). */
export const MAX_SKEW_S = 300;

/**
 * Verifies the signature the client attaches when the signed config carries a
 * `relay_key` (spec §5.4):
 *   X-Stash-Auth = hex( HMAC-SHA256( key, "<install>:<track_id>:<format_id>:<ts>" ) )
 * `keys` holds the current key and, during a rotation, the previous one; blanks are skipped.
 * Pure: header values as strings, server clock in unix seconds. Never throws.
 */
export function verifyMint({ install, ts, auth } = {}, trackId, formatId, nowSec, keys) {
    if (!/^[A-Za-z0-9-]{8,64}$/.test(install || "")) return false;
    if (!/^\d{1,12}$/.test(ts || "")) return false;
    if (Math.abs(nowSec - Number(ts)) > MAX_SKEW_S) return false;
    if (!/^[0-9a-f]{64}$/.test(auth || "")) return false;
    const got = Buffer.from(auth, "hex");
    const msg = `${install}:${trackId}:${formatId}:${ts}`;
    return keys.filter(Boolean).some((k) => timingSafeEqual(createHmac("sha256", k).update(msg).digest(), got));
}
```

- [ ] **Step 4: Run it**: `npm test` → `# pass 6`, `# fail 0`.

- [ ] **Step 5: Commit**

```bash
git add infra/lossless-relay/src/auth.js infra/lossless-relay/test/auth.test.js
git commit -m "feat(relay): verify the client's HMAC mint signature inside a ±5 min window"
```

---

### Task 3: Qobuz signing, classification and the upstream call (`src/qobuz.js`)

**Files:**
- Create: `infra/lossless-relay/src/qobuz.js`, `infra/lossless-relay/test/qobuz.test.js`

- [ ] **Step 1: Write the failing tests.** The two MD5 vectors are the HAR vectors `QbdlxSignerTest` locks on the client (secret `abb21364945c0583309667d13ca3d93a`).

```js
import { test } from "node:test";
import assert from "node:assert/strict";
import { signGetFileUrl, classify, etspOf, extractCreds, mintFromQobuz, UA } from "../src/qobuz.js";

const SECRET = "abb21364945c0583309667d13ca3d93a";

test("signGetFileUrl reproduces the client's HAR vectors", () => {
    assert.equal(signGetFileUrl(1782781652, 2841459, 27, SECRET), "013c10042c5e15ca5f1d85610bdd62ad");
    assert.equal(signGetFileUrl(1782781565, 3144087, 6, SECRET), "ff083dedd464374d86affbb22daeae01");
});

test("etspOf parses the CDN URL's expiry the way the client does", () => {
    assert.equal(etspOf("https://streaming-qobuz-std.akamaized.net/file?uid=1&etsp=1756760000&hmac=x"), 1756760000);
    assert.equal(etspOf("https://cdn.example/f.flac"), null);
    assert.equal(etspOf(null), null);
});

test("classify: a good body is ok, with sample_rate converted to Hz", () => {
    const r = classify(200, JSON.stringify({ url: "https://cdn/x?etsp=99", format_id: 7, bit_depth: 24, sampling_rate: 96 }));
    assert.deepEqual(r, { kind: "ok", url: "https://cdn/x?etsp=99", formatId: 7, bitDepth: 24, sampleRateHz: 96000, etsp: 99 });
    assert.equal(classify(200, JSON.stringify({ url: "https://cdn/x", format_id: 6, bit_depth: 16, sampling_rate: 44.1 })).sampleRateHz, 44100);
});

test("classify: dead-account signals mirror QbdlxApiClient", () => {
    assert.deepEqual(classify(401, "{}"), { kind: "dead", reason: "401" });
    assert.deepEqual(classify(403, '{"code":403,"message":"USER_BLOCKED"}'), { kind: "dead", reason: "USER_BLOCKED" });
    assert.equal(classify(200, JSON.stringify({ url: "https://cdn/x", format_id: 5, sample: false })).kind, "dead");
    assert.equal(classify(200, JSON.stringify({ url: "https://cdn/x", format_id: 7, sample: true })).kind, "dead");
    assert.equal(classify(200, JSON.stringify({ url: "https://cdn/x", format_id: 7, restrictions: [{ code: "UserUnauthenticated" }] })).kind, "dead");
});

test("classify: a region lock is 'locked' (→ 404); everything else is transient", () => {
    assert.deepEqual(classify(200, JSON.stringify({ format_id: 7 })), { kind: "locked" });
    assert.deepEqual(classify(200, JSON.stringify({ url: "https://cdn/x", format_id: 0 })), { kind: "locked" });
    assert.equal(classify(403, '{"message":"geo"}').kind, "transient");
    assert.equal(classify(400, '{"message":"Invalid Request Signature parameter"}').kind, "transient");
    assert.equal(classify(500, "").kind, "transient");
    assert.equal(classify(200, "<html>").kind, "transient");
    assert.equal(classify(200, JSON.stringify({ url: "http://cdn/x", format_id: 7 })).reason, "plaintext_url");
});

test("extractCreds pulls the web pair out of a bundle", () => {
    assert.deepEqual(extractCreds('x;app_id:"712109809",app_secret:"abb21364945c0583309667d13ca3d93a";y'),
        { app_id: "712109809", app_secret: "abb21364945c0583309667d13ca3d93a" });
    assert.equal(extractCreds("nothing here"), null);
});

test("mintFromQobuz sends the signed request the way the app does", async () => {
    const calls = [];
    const fetchImpl = async (url, init) => {
        calls.push({ url: new URL(url), init });
        return new Response(JSON.stringify({ url: "https://cdn/x?etsp=5", format_id: 27, bit_depth: 24, sampling_rate: 192 }), { status: 200 });
    };
    const acct = { label: "a", token: "tok", app_id: "712109809", app_secret: SECRET };
    const r = await mintFromQobuz(fetchImpl, acct, 2841459, 27, 1782781652);
    assert.equal(r.kind, "ok");
    assert.equal(r.sampleRateHz, 192000);
    const { url, init } = calls[0];
    assert.equal(url.origin + url.pathname, "https://www.qobuz.com/api.json/0.2/track/getFileUrl");
    assert.equal(url.searchParams.get("track_id"), "2841459");
    assert.equal(url.searchParams.get("format_id"), "27");
    assert.equal(url.searchParams.get("app_id"), "712109809");
    assert.equal(url.searchParams.get("request_ts"), "1782781652");
    assert.equal(url.searchParams.get("request_sig"), "013c10042c5e15ca5f1d85610bdd62ad");
    assert.equal(url.searchParams.get("intent"), "stream");
    assert.equal(init.headers["X-User-Auth-Token"], "tok");
    assert.equal(init.headers["X-App-Id"], "712109809");
    assert.equal(init.headers["User-Agent"], UA);
    assert.ok(init.signal instanceof AbortSignal);
});

test("mintFromQobuz turns a thrown fetch into transient, never a throw", async () => {
    const acct = { label: "a", token: "t", app_id: "1", app_secret: "s" };
    const boom = async () => { throw Object.assign(new Error("t"), { name: "TimeoutError" }); };
    assert.deepEqual(await mintFromQobuz(boom, acct, 1, 6, 1), { kind: "transient", reason: "timeout" });
    const down = async () => { throw new TypeError("fetch failed"); };
    assert.equal((await mintFromQobuz(down, acct, 1, 6, 1)).reason, "network");
});
```

- [ ] **Step 2: Run it**: `npm test` → FAIL with `Cannot find module '../src/qobuz.js'`.

- [ ] **Step 3: Implement**

```js
import { createHash } from "node:crypto";

export const QOBUZ_ORIGIN = "https://www.qobuz.com";
/** Same UA the app sends (QbdlxApiClient.UA): the relay should look like the client that already works. */
export const UA = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36";
/** The client's whole budget is 8 s (spec §1). Two attempts at this timeout plus the D1 round trips still fit. */
export const QOBUZ_TIMEOUT_MS = 3000;

const md5 = (s) => createHash("md5").update(s).digest("hex");

/**
 * `request_sig` for track/getFileUrl: the exact concatenation QbdlxSigner.signGetFileUrl
 * uses on the device, locked by the same HAR vectors. `ts` is unix seconds and MUST be
 * the value sent as `request_ts`.
 */
export function signGetFileUrl(ts, trackId, formatId, appSecret) {
    return md5("trackgetFileUrl" + "format_id" + formatId + "intentstream" + "track_id" + trackId + ts + appSecret);
}

/** Unix-seconds expiry Qobuz embeds in its CDN URL (the client parses the same param), or null. */
export function etspOf(url) {
    const m = /[?&]etsp=(\d+)/.exec(url || "");
    return m ? Number(m[1]) : null;
}

/** The web player's app_id/app_secret pair inside its JS bundle (QobuzWebCredentialsClient.CREDS_RE). */
export function extractCreds(js) {
    const m = /app_id:"(\d{9})",app_secret:"([a-f0-9]{32})"/.exec(js || "");
    return m ? { app_id: m[1], app_secret: m[2] } : null;
}

/**
 * Classifies one getFileUrl reply the way QbdlxApiClient.get + classify do on the device:
 *   { kind: "ok", url, formatId, bitDepth, sampleRateHz, etsp }   stream it
 *   { kind: "dead", reason }      this account is finished (401, USER_BLOCKED, previews): stop using it
 *   { kind: "locked" }            no URL / lossy format: region lock or not streamable → 404 (spec §1)
 *   { kind: "transient", reason } anything else: cool the account briefly, try another
 */
export function classify(status, body) {
    if (status === 401) return { kind: "dead", reason: "401" };
    if (status === 403 && /USER_BLOCKED/i.test(body)) return { kind: "dead", reason: "USER_BLOCKED" };
    if (status !== 200) return { kind: "transient", reason: `http_${status}` };
    let f;
    try { f = JSON.parse(body); } catch { return { kind: "transient", reason: "bad_json" }; }
    const unauth = (f.restrictions || []).some((r) => /^UserUnauthenticated$/i.test(r?.code || ""));
    if (f.sample === true || f.format_id === 5 || unauth) return { kind: "dead", reason: "preview" };
    if (!f.url || !(f.format_id >= 6)) return { kind: "locked" };
    if (!f.url.startsWith("https://")) return { kind: "transient", reason: "plaintext_url" };
    return {
        kind: "ok",
        url: f.url,
        formatId: f.format_id,
        bitDepth: f.bit_depth | 0,
        sampleRateHz: Math.round((f.sampling_rate || 0) * 1000), // Qobuz says kHz; the wire contract is Hz (spec §1)
        etsp: etspOf(f.url),
    };
}

/** One signed getFileUrl call for `account` = { label, token, app_id, app_secret }. Never throws. */
export async function mintFromQobuz(fetchImpl, account, trackId, formatId, nowSec) {
    const sig = signGetFileUrl(nowSec, trackId, formatId, account.app_secret);
    const url = `${QOBUZ_ORIGIN}/api.json/0.2/track/getFileUrl?track_id=${trackId}&format_id=${formatId}`
        + `&app_id=${account.app_id}&request_ts=${nowSec}&request_sig=${sig}&intent=stream`;
    let res;
    try {
        res = await fetchImpl(url, {
            headers: { "X-App-Id": account.app_id, "X-User-Auth-Token": account.token, Accept: "application/json", "User-Agent": UA },
            signal: AbortSignal.timeout(QOBUZ_TIMEOUT_MS),
        });
    } catch (e) {
        return { kind: "transient", reason: e?.name === "TimeoutError" ? "timeout" : "network" };
    }
    return classify(res.status, await res.text());
}
```

- [ ] **Step 4: Run it**: `npm test` → `# fail 0`.

- [ ] **Step 5: Commit**

```bash
git add infra/lossless-relay/src/qobuz.js infra/lossless-relay/test/qobuz.test.js
git commit -m "feat(relay): Qobuz request signing and reply classification, locked to the client's vectors"
```

---
### Task 4: D1 access (`src/db.js`) tested against `node:sqlite`

**Files:**
- Create: `infra/lossless-relay/src/db.js`, `infra/lossless-relay/test/fake-d1.js`, `infra/lossless-relay/test/db.test.js`

Why a shim and not a Workers test harness: every statement here is plain SQLite, D1 *is* SQLite, and Node 24 ships `node:sqlite`. Fifteen lines of shim let `src/db.js` run unchanged in both places, with no dependency and no miniflare. The rotation statement below was executed under `node:sqlite` while writing this plan and produced exactly the sequences the tests assert.

- [ ] **Step 1: The shim, `test/fake-d1.js`**

```js
import { DatabaseSync } from "node:sqlite";
import { readFileSync } from "node:fs";

/**
 * Just enough of D1's prepare/bind/first/all/run/batch over node:sqlite for the SQL in
 * src/db.js to run unchanged. Applies migrations/0001_init.sql to a fresh in-memory DB.
 */
export function fakeD1() {
    const raw = new DatabaseSync(":memory:");
    raw.exec(readFileSync(new URL("../migrations/0001_init.sql", import.meta.url), "utf8"));
    return {
        raw,
        prepare(sql) {
            const st = raw.prepare(sql);
            let args = [];
            const s = {
                bind: (...a) => { args = a; return s; },
                // node:sqlite rows are null-prototype objects; D1 hands back plain ones, and deepEqual can tell.
                first: async () => { const r = st.get(...args); return r ? { ...r } : null; },
                all: async () => ({ results: st.all(...args).map((r) => ({ ...r })) }),
                run: async () => { const r = st.run(...args); return { meta: { changes: r.changes } }; },
            };
            return s;
        },
        async batch(stmts) {
            const out = [];
            for (const s of stmts) out.push(await s.all());
            return out;
        },
    };
}
```

- [ ] **Step 2: Write the failing tests, `test/db.test.js`**

```js
import { test } from "node:test";
import assert from "node:assert/strict";
import { fakeD1 } from "./fake-d1.js";
import {
    selectAccount, ensureAccounts, coolAccount, killAccount,
    getCached, putCachedStmt, readQuota, bumpQuotaStmt, prune, dayKey, hourKey,
} from "../src/db.js";

const CAPS = { hourly: 2, daily: 3 };
const T0 = 1788282000; // 2026-09-01T17:00:00Z

test("day and hour keys are UTC", () => {
    assert.equal(dayKey(T0), "2026-09-01");
    assert.equal(hourKey(T0), "2026-09-01T17");
});

test("selectAccount rotates least-recently-used under the hourly cap and stamps in the same write", async () => {
    const db = fakeD1();
    await ensureAccounts(db, ["a", "b", "c"]);
    const picks = [];
    for (let i = 0; i < 7; i++) picks.push(await selectAccount(db, T0 + i, CAPS));
    assert.deepEqual(picks, ["a", "b", "c", "a", "b", "c", null]);
    const row = await db.prepare("SELECT hour_n, day_n, last_used_at FROM accounts WHERE label = 'a'").first();
    assert.deepEqual(row, { hour_n: 2, day_n: 2, last_used_at: T0 + 3 });
});

test("a new hour resets the hourly counter but the daily cap still binds", async () => {
    const db = fakeD1();
    await ensureAccounts(db, ["a"]);
    for (let i = 0; i < 2; i++) await selectAccount(db, T0 + i, CAPS);
    assert.equal(await selectAccount(db, T0 + 3600, CAPS), "a");   // third of the day
    assert.equal(await selectAccount(db, T0 + 3601, CAPS), null);  // daily cap 3
    assert.equal(await selectAccount(db, T0 + 86400, CAPS), "a");  // next UTC day
});

test("cooling, dead and reserve accounts are never selected", async () => {
    const db = fakeD1();
    await ensureAccounts(db, ["cool", "dead", "res", "ok"]);
    await coolAccount(db, "cool", T0 + 300);
    await killAccount(db, "dead", "401");
    await db.prepare("UPDATE accounts SET state = 'reserve' WHERE label = 'res'").run();
    assert.equal(await selectAccount(db, T0, CAPS), "ok");
    assert.equal(await selectAccount(db, T0 + 1, CAPS), "ok");
    assert.equal(await selectAccount(db, T0 + 2, CAPS), null);      // ok at its hourly cap, nothing else eligible
    assert.equal(await selectAccount(db, T0 + 3600, CAPS), "cool"); // cooled off and least recently used
    assert.deepEqual(await db.prepare("SELECT state, dead_reason FROM accounts WHERE label = 'dead'").first(), { state: "dead", dead_reason: "401" });
});

test("ensureAccounts is idempotent and never resets state", async () => {
    const db = fakeD1();
    await ensureAccounts(db, ["a"]);
    await killAccount(db, "a", "x");
    await ensureAccounts(db, ["a", "b"]);
    assert.equal((await db.prepare("SELECT state FROM accounts WHERE label = 'a'").first()).state, "dead");
    assert.equal((await db.prepare("SELECT COUNT(*) AS n FROM accounts").first()).n, 2);
    await ensureAccounts(db, []); // no-op, no throw
});

test("the cache serves only while the URL has ≥ 15 min left; prune drops expired rows", async () => {
    const db = fakeD1();
    const m = { url: "https://cdn/x?etsp=5000", formatId: 7, bitDepth: 24, sampleRateHz: 96000, etsp: 5000 };
    await db.batch([putCachedStmt(db, 1, 27, m)]);
    assert.deepEqual(await getCached(db, 1, 27, 4100), { url: m.url, got_format_id: 7, bit_depth: 24, sample_rate: 96000, etsp: 5000 });
    assert.equal(await getCached(db, 1, 27, 4101), null);
    assert.equal(await getCached(db, 1, 7, 4000), null); // a different requested format is a different key
    await prune(db, 5001);
    assert.equal(await getCached(db, 1, 27, 0), null);
});

test("quota upserts per day and key; prune keeps today and yesterday", async () => {
    const db = fakeD1();
    await db.batch([bumpQuotaStmt(db, "2026-09-01", "global"), bumpQuotaStmt(db, "2026-09-01", "global"), bumpQuotaStmt(db, "2026-09-01", "i:x")]);
    assert.equal(await readQuota(db, "2026-09-01", "global"), 2);
    assert.equal(await readQuota(db, "2026-09-01", "i:x"), 1);
    assert.equal(await readQuota(db, "2026-09-02", "global"), 0);
    await db.batch([bumpQuotaStmt(db, "2026-08-30", "global")]);
    await prune(db, T0); // keeps 2026-08-31 and 2026-09-01
    assert.equal(await readQuota(db, "2026-08-30", "global"), 0);
    assert.equal(await readQuota(db, "2026-09-01", "global"), 2);
});
```

- [ ] **Step 3: Run it**: `npm test` → FAIL with `Cannot find module '../src/db.js'`.

- [ ] **Step 4: Implement `src/db.js`**

```js
/**
 * All D1 access. Every function takes the D1 binding (or test/fake-d1.js) and plain
 * values; no ORM, no runtime migrations (see migrations/). Times are unix seconds.
 * Day and hour keys are UTC strings, so every cap resets at midnight UTC.
 */
export const dayKey = (nowSec) => new Date(nowSec * 1000).toISOString().slice(0, 10);  // "2026-09-01"
export const hourKey = (nowSec) => new Date(nowSec * 1000).toISOString().slice(0, 13); // "2026-09-01T17"

/** Serve a cached mint only while its URL has at least this long left (spec §6.3). */
export const CACHE_MIN_LEFT_S = 900;

export async function getCached(db, trackId, formatId, nowSec) {
    return db.prepare(
        "SELECT url, got_format_id, bit_depth, sample_rate, etsp FROM mints WHERE track_id = ?1 AND format_id = ?2 AND etsp - ?3 >= ?4",
    ).bind(trackId, formatId, nowSec, CACHE_MIN_LEFT_S).first();
}

/** Statement (not executed) so the caller can batch it with the quota bumps. */
export function putCachedStmt(db, trackId, formatId, m) {
    return db.prepare(
        "INSERT OR REPLACE INTO mints (track_id, format_id, url, got_format_id, bit_depth, sample_rate, etsp) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
    ).bind(trackId, formatId, m.url, m.formatId, m.bitDepth, m.sampleRateHz, m.etsp);
}

export async function readQuota(db, day, key) {
    const row = await db.prepare("SELECT n FROM quota WHERE day = ?1 AND key = ?2").bind(day, key).first();
    return row ? row.n : 0;
}

export function bumpQuotaStmt(db, day, key) {
    return db.prepare("INSERT INTO quota (day, key, n) VALUES (?1, ?2, 1) ON CONFLICT(day, key) DO UPDATE SET n = n + 1").bind(day, key);
}

/**
 * Spec §5.3 in one statement: pick the live, un-cooled account with the oldest
 * last_used_at that is under both caps, and stamp its counters in the same write.
 * SET expressions read the pre-update row, so `hour_key = ?2` on the right-hand
 * side compares the OLD key. Atomic under concurrent invocations because D1 runs
 * one statement at a time. Returns the label, or null when nothing is eligible.
 */
export async function selectAccount(db, nowSec, caps) {
    const row = await db.prepare(`
UPDATE accounts SET
  hour_n = CASE WHEN hour_key = ?2 THEN hour_n + 1 ELSE 1 END, hour_key = ?2,
  day_n  = CASE WHEN day_key  = ?3 THEN day_n  + 1 ELSE 1 END, day_key  = ?3,
  last_used_at = ?1
WHERE label = (
  SELECT label FROM accounts
  WHERE state = 'live' AND cooling_until <= ?1
    AND (hour_key != ?2 OR hour_n < ?4)
    AND (day_key  != ?3 OR day_n  < ?5)
  ORDER BY last_used_at ASC, label ASC LIMIT 1)
RETURNING label`).bind(nowSec, hourKey(nowSec), dayKey(nowSec), caps.hourly, caps.daily).first();
    return row ? row.label : null;
}

/** Give every label in the secret a state row. INSERT OR IGNORE writes nothing for a known label. */
export async function ensureAccounts(db, labels) {
    if (labels.length === 0) return;
    await db.batch(labels.map((l) => db.prepare("INSERT OR IGNORE INTO accounts (label) VALUES (?1)").bind(l)));
}

export async function coolAccount(db, label, untilSec) {
    await db.prepare("UPDATE accounts SET cooling_until = ?2 WHERE label = ?1").bind(label, untilSec).run();
}

export async function killAccount(db, label, reason) {
    await db.prepare("UPDATE accounts SET state = 'dead', dead_reason = ?2 WHERE label = ?1").bind(label, reason).run();
}

/** Hourly cron: drop mints past their etsp and quota rows older than yesterday. */
export async function prune(db, nowSec) {
    await db.batch([
        db.prepare("DELETE FROM mints WHERE etsp < ?1").bind(nowSec),
        db.prepare("DELETE FROM quota WHERE day < ?1").bind(dayKey(nowSec - 86400)),
    ]);
}
```

- [ ] **Step 5: Run it**: `npm test` → `# fail 0`.

- [ ] **Step 6: Commit**

```bash
git add infra/lossless-relay/src/db.js infra/lossless-relay/test/fake-d1.js infra/lossless-relay/test/db.test.js
git commit -m "feat(relay): D1 rotation, mint cache and daily quotas as single statements, tested on node:sqlite"
```

---

### Task 5: The mint flow (`src/index.js`)

**Files:**
- Modify: `infra/lossless-relay/src/index.js` (replace the placeholder)
- Create: `infra/lossless-relay/test/index.test.js`
- Delete: `infra/lossless-relay/test/smoke.test.js` (superseded)

Order of checks on a mint, and why: version → params → key configured → signature → Data Saver short-circuit → per-IP limiter → cache → global cap → install cap → account → Qobuz. Everything cheap and local rejects before anything that costs a D1 write or a Qobuz request; a cache hit costs one D1 read and no counter.

- [ ] **Step 1: Write the failing tests, `test/index.test.js`**

```js
import { test } from "node:test";
import assert from "node:assert/strict";
import { createHmac } from "node:crypto";
import { fakeD1 } from "./fake-d1.js";
import worker, { handle } from "../src/index.js";
import { bumpQuotaStmt } from "../src/db.js";

const NOW = 1788282000; // 2026-09-01T17:00:00Z
const KEY = "k-secret";
const BASE = "https://relay.test";
const ACCOUNTS = [
    { label: "a", token: "tok-a", app_id: "111111111", app_secret: "s-a" },
    { label: "b", token: "tok-b", app_id: "111111111", app_secret: "s-b" },
];
const GOOD = { url: "https://cdn.example/f.flac?etsp=" + (NOW + 3599), format_id: 7, bit_depth: 24, sampling_rate: 96 };

function env(over = {}) {
    return {
        DB: fakeD1(), RELAY_KEY: KEY, QOBUZ_ACCOUNTS: JSON.stringify(ACCOUNTS),
        MINT_RL: { limit: async () => ({ success: true }) },
        GLOBAL_DAILY_CAP: "600", INSTALL_DAILY_CAP: "100", ACCOUNT_HOURLY_CAP: "20", ACCOUNT_DAILY_CAP: "200",
        ...over,
    };
}

function mintReq(trackId, formatId, { key = KEY, install = "install-0001", ts = NOW, signed = true, version = "1" } = {}) {
    const h = { "X-Stash-Version": version, Accept: "application/json", "CF-Connecting-IP": "203.0.113.9" };
    if (signed) {
        h["X-Stash-Install"] = install;
        h["X-Stash-Ts"] = String(ts);
        h["X-Stash-Auth"] = createHmac("sha256", key).update(`${install}:${trackId}:${formatId}:${ts}`).digest("hex");
    }
    return new Request(`${BASE}/v1/qobuz/file?track_id=${trackId}&format_id=${formatId}`, { headers: h });
}

/** A fake Qobuz: `replies` are [status, body] pairs consumed in order; every call is recorded. */
function qobuz(...replies) {
    const calls = [];
    const f = async (url, init) => {
        calls.push({ url: String(url), init });
        const [status, body] = replies.length ? replies.shift() : [500, ""];
        return new Response(typeof body === "string" ? body : JSON.stringify(body), { status });
    };
    f.calls = calls;
    return f;
}

test("routing: status answers 200 with a body, unknown paths 404, non-GET 405; the module exports both handlers", async () => {
    const e = env();
    const s = await handle(new Request(`${BASE}/v1/status`), e, qobuz(), NOW);
    assert.equal(s.status, 200);
    assert.deepEqual(await s.json(), { ok: true });
    assert.equal((await handle(new Request(`${BASE}/`), e, qobuz(), NOW)).status, 404);
    assert.equal((await handle(new Request(`${BASE}/v1/qobuz/file`, { method: "POST" }), e, qobuz(), NOW)).status, 405);
    assert.equal(typeof worker.fetch, "function");
    assert.equal(typeof worker.scheduled, "function");
});

test("400 for another protocol version or malformed ids; Qobuz untouched", async () => {
    const e = env(); const q = qobuz();
    assert.equal((await handle(mintReq(42, 27, { version: "2" }), e, q, NOW)).status, 400);
    assert.equal((await handle(new Request(`${BASE}/v1/qobuz/file?track_id=abc&format_id=27`, { headers: { "X-Stash-Version": "1" } }), e, q, NOW)).status, 400);
    assert.equal((await handle(new Request(`${BASE}/v1/qobuz/file?track_id=42&format_id=x`, { headers: { "X-Stash-Version": "1" } }), e, q, NOW)).status, 400);
    assert.equal(q.calls.length, 0);
});

test("401 when unsigned or signed with the wrong key; the previous key still works", async () => {
    const e = env({ RELAY_KEY: "new", RELAY_KEY_PREV: KEY }); const q = qobuz([200, GOOD]);
    assert.equal((await handle(mintReq(42, 27, { signed: false }), e, q, NOW)).status, 401);
    assert.equal((await handle(mintReq(42, 27, { key: "bogus" }), e, q, NOW)).status, 401);
    assert.equal((await handle(mintReq(42, 27, { key: KEY }), e, q, NOW)).status, 200);
});

test("500 when RELAY_KEY is unset: an open relay is a misconfiguration, not a mode", async () => {
    assert.equal((await handle(mintReq(42, 27), env({ RELAY_KEY: undefined }), qobuz([200, GOOD]), NOW)).status, 500);
});

test("Data Saver (format 5) or any other non-lossless format → 404 with no Qobuz call and no quota", async () => {
    const e = env(); const q = qobuz([200, GOOD]);
    assert.equal((await handle(mintReq(42, 5), e, q, NOW)).status, 404);
    assert.equal((await handle(mintReq(42, 8), e, q, NOW)).status, 404);
    assert.equal(q.calls.length, 0);
    assert.equal(await e.DB.prepare("SELECT n FROM quota WHERE key = 'global'").first(), null);
});

test("miss → 200 in the wire shape after one Qobuz call; the same track is then a HIT with no Qobuz call and no quota", async () => {
    const e = env(); const q = qobuz([200, GOOD]);
    const r1 = await handle(mintReq(42, 27), e, q, NOW);
    assert.equal(r1.status, 200);
    assert.equal(r1.headers.get("X-Stash-Cache"), "MISS");
    assert.deepEqual(await r1.json(), { url: GOOD.url, format_id: 7, bit_depth: 24, sample_rate: 96000 });
    assert.equal(q.calls.length, 1);
    assert.match(q.calls[0].url, /request_sig=[0-9a-f]{32}/);
    assert.equal(q.calls[0].init.headers["X-User-Auth-Token"], "tok-a");
    const r2 = await handle(mintReq(42, 27, { install: "install-0002" }), e, q, NOW + 60);
    assert.equal(r2.status, 200);
    assert.equal(r2.headers.get("X-Stash-Cache"), "HIT");
    assert.equal(q.calls.length, 1);
    assert.equal((await e.DB.prepare("SELECT n FROM quota WHERE key = 'global'").first()).n, 1);
});

test("a dead account is retired and the next one serves the same request", async () => {
    const e = env(); const q = qobuz([401, {}], [200, GOOD]);
    assert.equal((await handle(mintReq(42, 27), e, q, NOW)).status, 200);
    assert.equal(q.calls[1].init.headers["X-User-Auth-Token"], "tok-b");
    assert.deepEqual(await e.DB.prepare("SELECT state, dead_reason FROM accounts WHERE label = 'a'").first(), { state: "dead", dead_reason: "401" });
});

test("region lock → 404, never cached, quota spent", async () => {
    const e = env(); const q = qobuz([200, { format_id: 7 }], [200, { format_id: 7 }]);
    assert.equal((await handle(mintReq(42, 27), e, q, NOW)).status, 404);
    assert.equal((await handle(mintReq(42, 27), e, q, NOW)).status, 404);
    assert.equal(q.calls.length, 2);
    assert.equal((await e.DB.prepare("SELECT n FROM quota WHERE key = 'global'").first()).n, 2);
});

test("every account failing transiently → 503 with Retry-After after at most two attempts; both cool; the next request is a 503 with no Qobuz call", async () => {
    const e = env(); const q = qobuz([500, ""], [500, ""], [200, GOOD]);
    const r = await handle(mintReq(42, 27), e, q, NOW);
    assert.equal(r.status, 503);
    assert.equal(r.headers.get("Retry-After"), "60");
    assert.equal(q.calls.length, 2);
    assert.equal((await e.DB.prepare("SELECT COUNT(*) AS n FROM accounts WHERE cooling_until > 0").first()).n, 2);
    assert.equal((await handle(mintReq(43, 27), e, q, NOW + 10)).status, 503);
    assert.equal(q.calls.length, 2);
});

test("global daily cap → 503; per-install cap → 429; per-IP limiter → 429; none reach Qobuz", async () => {
    const q = qobuz([200, GOOD]);
    const g = env({ GLOBAL_DAILY_CAP: "1" });
    await g.DB.batch([bumpQuotaStmt(g.DB, "2026-09-01", "global")]);
    const r1 = await handle(mintReq(42, 27), g, q, NOW);
    assert.equal(r1.status, 503);
    assert.equal(r1.headers.get("Retry-After"), "600");
    const i = env({ INSTALL_DAILY_CAP: "1" });
    await i.DB.batch([bumpQuotaStmt(i.DB, "2026-09-01", "i:install-0001")]);
    assert.equal((await handle(mintReq(42, 27), i, q, NOW)).status, 429);
    const ip = env({ MINT_RL: { limit: async () => ({ success: false }) } });
    assert.equal((await handle(mintReq(42, 27), ip, q, NOW)).status, 429);
    assert.equal(q.calls.length, 0);
});

test("a URL without etsp is served but never cached", async () => {
    const e = env(); const q = qobuz([200, { ...GOOD, url: "https://cdn.example/no-expiry.flac" }], [200, GOOD]);
    assert.equal((await handle(mintReq(42, 27), e, q, NOW)).status, 200);
    assert.equal((await handle(mintReq(42, 27), e, q, NOW)).headers.get("X-Stash-Cache"), "MISS");
    assert.equal(q.calls.length, 2);
});
```

- [ ] **Step 2: Run it**: `npm test` → FAIL (`handle` is not exported; the placeholder answers 404 to everything).

- [ ] **Step 3: Implement `src/index.js`**

```js
/**
 * Stash Lossless Relay — Cloudflare Worker
 *
 * Mints short-lived Qobuz FLAC URLs for the Stash app from a small pool of
 * operator-owned accounts. The device streams from Qobuz's CDN directly; audio
 * never transits here (spec §4). The wire contract in spec §1-2 is enforced by
 * the shipped client, so every status below means exactly one thing to it:
 *   200 mint · 404 not available (ends the track) · 503 busy (base cools 60 s)
 *   anything else: base cools 5 min. Never 200 with an error body.
 *
 * Endpoints:
 *   GET /v1/qobuz/file?track_id=&format_id=   signed (X-Stash-Install/-Ts/-Auth), X-Stash-Version: 1
 *   GET /v1/status                            reachability probe for the Settings "Test" button
 *
 * State: D1 (accounts' rotation state, the shared mint cache, daily quotas).
 * Secrets: RELAY_KEY, RELAY_KEY_PREV (optional), QOBUZ_ACCOUNTS. Deploy: see README.md.
 */
import { verifyMint } from "./auth.js";
import { mintFromQobuz } from "./qobuz.js";
import {
    getCached, putCachedStmt, readQuota, bumpQuotaStmt,
    selectAccount, ensureAccounts, coolAccount, killAccount, prune, dayKey,
} from "./db.js";

/** The lossless formats the client asks for (LosslessQualityTier → 6 / 7 / 27). Data Saver's 5 (MP3 320) is answered 404 below. */
const LOSSLESS_FORMATS = new Set([6, 7, 27]);
/** A transiently failing account sits out this long. */
const TRANSIENT_COOL_S = 300;
/** Two 3 s upstream attempts plus the D1 round trips fit inside the client's 8 s budget (spec §1). */
const MAX_ATTEMPTS = 2;

export default {
    fetch: (request, env) => handle(request, env, globalThis.fetch, Math.floor(Date.now() / 1000)),
    scheduled: (event, env) => prune(env.DB, Math.floor(Date.now() / 1000)),
};

/** The whole flow with fetch and the clock injectable, so it runs under node:test. */
export async function handle(request, env, fetchImpl, nowSec) {
    const url = new URL(request.url);
    if (request.method !== "GET") return json({ error: "method_not_allowed" }, 405);
    if (url.pathname === "/v1/status") return json({ ok: true }, 200);
    if (url.pathname !== "/v1/qobuz/file") return json({ error: "not_found" }, 404);
    return mint(request, url, env, fetchImpl, nowSec);
}

async function mint(request, url, env, fetchImpl, nowSec) {
    const h = (name) => request.headers.get(name) || "";
    if (h("X-Stash-Version") !== "1") return json({ error: "bad_version" }, 400);
    const trackRaw = url.searchParams.get("track_id") || "";
    const formatId = Number(url.searchParams.get("format_id"));
    if (!/^\d{1,12}$/.test(trackRaw) || !Number.isInteger(formatId) || formatId <= 0) return json({ error: "bad_request" }, 400);
    const trackId = Number(trackRaw);
    if (!env.RELAY_KEY) return json({ error: "server_misconfigured", message: "RELAY_KEY not set" }, 500);

    const install = h("X-Stash-Install");
    const signed = verifyMint({ install, ts: h("X-Stash-Ts"), auth: h("X-Stash-Auth") }, trackId, formatId, nowSec, [env.RELAY_KEY, env.RELAY_KEY_PREV]);
    if (!signed) return json({ error: "unauthorized" }, 401);

    // Data Saver asks for format 5 (MP3 320). The relay serves lossless only, and the client's
    // router discards any relay format below 6 anyway, so a well-formed non-lossless format is
    // answered 404: no cooldown, no Qobuz call, no quota, and the device falls to its lossy rung
    // (JioSaavn AAC 320) exactly as Save Data intends. 400 is reserved for malformed input.
    if (!LOSSLESS_FORMATS.has(formatId)) return json({ error: "not_available", message: "lossless only" }, 404);

    // Per-IP cap (spec §5.4). Guarded: some local-dev setups have no binding.
    if (env.MINT_RL) {
        const { success } = await env.MINT_RL.limit({ key: h("CF-Connecting-IP") || "?" });
        if (!success) return json({ error: "rate_limited" }, 429, { "Retry-After": "60" });
    }

    const cached = await getCached(env.DB, trackId, formatId, nowSec);
    if (cached) return ok(cached.url, cached.got_format_id, cached.bit_depth, cached.sample_rate, "HIT");

    const day = dayKey(nowSec);
    const caps = vars(env);
    // Exhaustion is 503, never 404 (spec §5.5). Read-then-bump can overshoot by a few under
    // concurrency; that is harmless and saves a write on every rejected request.
    if ((await readQuota(env.DB, day, "global")) >= caps.global) {
        console.log(`503 global daily cap ${caps.global} reached`);
        return json({ error: "busy" }, 503, { "Retry-After": "600" });
    }
    if ((await readQuota(env.DB, day, "i:" + install)) >= caps.install) {
        return json({ error: "rate_limited" }, 429, { "Retry-After": "3600" });
    }

    const accounts = parseAccounts(env);
    for (let attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
        let label = await selectAccount(env.DB, nowSec, caps);
        if (!label) { // first request ever, or a label added to the secret since: give it a row and look again
            await ensureAccounts(env.DB, accounts.map((a) => a.label));
            label = await selectAccount(env.DB, nowSec, caps);
        }
        const account = accounts.find((a) => a.label === label);
        if (!account) break; // nothing live under its caps (or a D1 label with no secret entry) → 503 below
        const r = await mintFromQobuz(fetchImpl, account, trackId, formatId, nowSec);
        console.log(`mint track=${trackId} fmt=${formatId} acct=${label} install=${install.slice(0, 8)} -> ${r.kind}${r.reason ? " " + r.reason : ""}`);
        if (r.kind === "ok" || r.kind === "locked") {
            const writes = [bumpQuotaStmt(env.DB, day, "global"), bumpQuotaStmt(env.DB, day, "i:" + install)];
            if (r.kind === "ok" && r.etsp) writes.push(putCachedStmt(env.DB, trackId, formatId, r)); // no etsp → serve once, never cache
            await env.DB.batch(writes);
            return r.kind === "ok" ? ok(r.url, r.formatId, r.bitDepth, r.sampleRateHz, "MISS") : json({ error: "not_available" }, 404);
        }
        if (r.kind === "dead") await killAccount(env.DB, label, r.reason);
        else await coolAccount(env.DB, label, nowSec + TRANSIENT_COOL_S);
    }
    return json({ error: "busy" }, 503, { "Retry-After": "60" });
}

function vars(env) {
    const n = (v, d) => (Number.parseInt(v, 10) > 0 ? Number.parseInt(v, 10) : d);
    return {
        global: n(env.GLOBAL_DAILY_CAP, 600),
        install: n(env.INSTALL_DAILY_CAP, 100),
        hourly: n(env.ACCOUNT_HOURLY_CAP, 20),
        daily: n(env.ACCOUNT_DAILY_CAP, 200),
    };
}

/** QOBUZ_ACCOUNTS is operator-written JSON; a malformed secret must degrade to "no accounts" (503), not a 500 loop. */
function parseAccounts(env) {
    try {
        const a = JSON.parse(env.QOBUZ_ACCOUNTS || "[]");
        return Array.isArray(a) ? a.filter((x) => x && x.label && x.token && x.app_id && x.app_secret) : [];
    } catch {
        return [];
    }
}

/** The spec §1 success shape. sample_rate is already Hz. `no-store`: the URL is short-lived and per-request headers gate it. */
function ok(url, formatId, bitDepth, sampleRateHz, cache) {
    return json({ url, format_id: formatId, bit_depth: bitDepth, sample_rate: sampleRateHz }, 200, { "X-Stash-Cache": cache, "Cache-Control": "no-store" });
}

function json(obj, status, extra = {}) {
    return new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json", ...extra } });
}
```

- [ ] **Step 4: Run it**: `npm test` → `# fail 0` (delete `test/smoke.test.js` first; the routing test covers it).

- [ ] **Step 5: Local end-to-end against the real Qobuz, once.** Put a real account into `.dev.vars`: `scripts/login.mjs` from Task 6 prints the entry, so do this step right after Task 6. Then:

```bash
npx wrangler d1 migrations apply stash-relay --local
npx wrangler dev          # http://localhost:8787
```
In another shell, `node scripts/sign-request.mjs dev-key 937987774 27 http://localhost:8787` (Task 6) prints a curl; run it. Expected: `200`, a JSON body whose `url` starts with `https://streaming-qobuz-` and contains `etsp=`, `X-Stash-Cache: MISS`; run it again → `HIT`. `curl -s -r 0-1023 "<url>" -o /dev/null -w "%{http_code} %{content_type}\n"` → `206 audio/flac`. This step may be done after Task 6 if the scripts are not there yet; the unit tests are the gate for this commit.

- [ ] **Step 6: Commit**

```bash
git rm -q infra/lossless-relay/test/smoke.test.js
git add infra/lossless-relay/src/index.js infra/lossless-relay/test/index.test.js
git commit -m "feat(relay): the mint flow — signed request, per-IP and daily caps, shared cache, LRU account rotation"
```

---
### Task 6: Operator scripts and the config builder

**Files:**
- Create: `infra/lossless-relay/src/config.js`, `infra/lossless-relay/test/config.test.js`, `infra/lossless-relay/scripts/login.mjs`, `infra/lossless-relay/scripts/publish-config.mjs`, `infra/lossless-relay/scripts/sign-request.mjs`

The only logic with rules worth a test is the config builder (monotonic `updated_at`, relay normalisation, signature format); it lives in `src/` so the scripts stay side-effect-only.

- [ ] **Step 1: Write the failing tests, `test/config.test.js`**

```js
import { test } from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync, sign, verify, createPublicKey } from "node:crypto";
import { buildConfig, normaliseBase } from "../src/config.js";

test("normaliseBase: https only, no trailing slash; rejects a query or fragment (the client would strip them)", () => {
    assert.equal(normaliseBase("https://stash-relay.example.workers.dev/"), "https://stash-relay.example.workers.dev");
    assert.equal(normaliseBase(" https://a.example/relay/ "), "https://a.example/relay");
    assert.equal(normaliseBase("http://a.example"), null);
    assert.equal(normaliseBase("https://a.example/?x=1"), null);
    assert.equal(normaliseBase("not a url"), null);
});

test("buildConfig: v=1, relays normalised and sorted by priority, updated_at strictly above the live one", () => {
    const live = 1788220800;
    const s = buildConfig({ relays: [{ base: "https://b.example/", priority: 2 }, { base: "https://a.example", priority: 1 }] }, live, live - 5000);
    assert.deepEqual(JSON.parse(s), { v: 1, relays: [{ base: "https://a.example", priority: 1 }, { base: "https://b.example", priority: 2 }], updated_at: live + 1 });
    assert.equal(JSON.parse(buildConfig({ relays: [] }, live, live + 100)).updated_at, live + 100); // a later clock wins
    assert.equal(JSON.parse(buildConfig({ relays: [] }, 0, 5)).updated_at, 5);                    // first publish
});

test("buildConfig: the kill switch is a valid empty list; the key rides along; bad input throws", () => {
    const s = buildConfig({ relays: [], relay_key: "a".repeat(64) }, 0, 1);
    assert.deepEqual(JSON.parse(s), { v: 1, relays: [], updated_at: 1, relay_key: "a".repeat(64) });
    assert.throws(() => buildConfig({ relays: [{ base: "http://a.example" }] }, 0, 1), /https/);
    assert.throws(() => buildConfig({ relays: [], relay_key: "short" }, 0, 1), /relay_key/);
});

test("a signature over the built bytes verifies the way the device does (SHA256withECDSA, DER, SPKI key)", () => {
    const { privateKey, publicKey } = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
    const bytes = Buffer.from(buildConfig({ relays: [] }, 0, 1));
    const sigB64 = sign("sha256", bytes, privateKey).toString("base64");
    const spkiB64 = publicKey.export({ type: "spki", format: "der" }).toString("base64"); // what LOSSLESS_CONFIG_PUBKEY holds
    const pub = createPublicKey({ key: Buffer.from(spkiB64, "base64"), type: "spki", format: "der" });
    assert.equal(verify("sha256", bytes, pub, Buffer.from(sigB64, "base64")), true);
    assert.equal(verify("sha256", Buffer.from(bytes.toString() + " "), pub, Buffer.from(sigB64, "base64")), false);
});
```

- [ ] **Step 2: Run it**: `npm test` → FAIL with `Cannot find module '../src/config.js'`.

- [ ] **Step 3: Implement `src/config.js`**

```js
/**
 * Builds the exact bytes of lossless.json (spec §3). Pure so the publishing rules are
 * testable: `updated_at` strictly above whatever is live (the device keeps a rollback
 * floor), relays normalised to what the client's LosslessSourcePreferences.normaliseEndpoint
 * produces (https only, no trailing slash) and sorted by priority. Stricter on input than the
 * client, on purpose: it STRIPS a query or fragment, this REJECTS one so a typo fails at publish time;
 * `v: 1`, and the optional relay_key. Callers sign the returned string's UTF-8 bytes as-is.
 */
export function buildConfig(input, liveUpdatedAt, nowSec) {
    const relays = (input.relays || []).map((r, i) => {
        const base = normaliseBase(r.base);
        if (!base) throw new Error(`relays[${i}].base must be an https URL with no query or fragment: ${r.base}`);
        return { base, priority: Number.isInteger(r.priority) ? r.priority : 1 };
    }).sort((a, b) => a.priority - b.priority);
    const cfg = { v: 1, relays, updated_at: Math.max(nowSec, (liveUpdatedAt || 0) + 1) };
    if (input.relay_key !== undefined) {
        if (typeof input.relay_key !== "string" || input.relay_key.length < 32) throw new Error("relay_key must be a string of at least 32 characters");
        cfg.relay_key = input.relay_key;
    }
    return JSON.stringify(cfg);
}

export function normaliseBase(raw) {
    let u;
    try { u = new URL(String(raw || "").trim()); } catch { return null; }
    if (u.protocol !== "https:" || u.search || u.hash) return null;
    return (u.origin + u.pathname).replace(/\/+$/, "");
}
```

- [ ] **Step 4: Run it**: `npm test` → `# fail 0`.

- [ ] **Step 5: `scripts/login.mjs`**

```js
#!/usr/bin/env node
/**
 * Logs one Qobuz account in exactly as the app does (QobuzLoginClient) and prints the
 * QOBUZ_ACCOUNTS entry for it. Nothing is stored. The token is bound to the web app_id it
 * was minted under, so that pair is printed with it: the relay signs with it.
 *
 *   QOBUZ_EMAIL=… QOBUZ_PASSWORD=… node scripts/login.mjs <label>
 *
 * Exit 1 with a message for wrong credentials, a free account (no lossless entitlement),
 * or a web bundle without the pair. The label is what D1 and the logs show; never the email.
 */
import { createHash } from "node:crypto";
import { UA, QOBUZ_ORIGIN, extractCreds } from "../src/qobuz.js";

const label = process.argv[2];
const { QOBUZ_EMAIL: email, QOBUZ_PASSWORD: password } = process.env;
if (!label || !email || !password) {
    console.error("usage: QOBUZ_EMAIL=… QOBUZ_PASSWORD=… node scripts/login.mjs <label>");
    process.exit(2);
}
const get = async (url) => (await fetch(url, { headers: { "User-Agent": UA, Accept: "*/*" } })).text();

const html = await get("https://open.qobuz.com/");
let creds = null;
for (const path of new Set(html.match(/\/resources\/[^"']+\.js/g) || [])) {
    creds = extractCreds(await get("https://open.qobuz.com" + path));
    if (creds) break;
}
if (!creds) { console.error("could not find app_id/app_secret in the web bundle"); process.exit(1); }

const u = new URL(`${QOBUZ_ORIGIN}/api.json/0.2/user/login`);
u.searchParams.set("email", email.trim());
u.searchParams.set("password", createHash("md5").update(password).digest("hex")); // Qobuz's scheme, same as the app
u.searchParams.set("app_id", creds.app_id);
const res = await fetch(u, { headers: { "X-App-Id": creds.app_id, Accept: "application/json", "User-Agent": UA } });
if (res.status === 401) { console.error("invalid credentials"); process.exit(1); }
const body = res.status === 200 ? await res.json() : {};
if (!body.user_auth_token) { console.error(`unexpected reply: HTTP ${res.status}`); process.exit(1); }
const params = body.user?.credential?.parameters;
if (!params || Object.keys(params).length === 0) { console.error("free account: no lossless entitlement, nothing to serve"); process.exit(1); }
console.log(JSON.stringify({ label, token: body.user_auth_token, app_id: creds.app_id, app_secret: creds.app_secret }));
```

- [ ] **Step 6: `scripts/publish-config.mjs`**

```js
#!/usr/bin/env node
/**
 * Signs and publishes lossless.json to the config host: the tipjar Worker's KV (spec §3).
 *
 *   node scripts/publish-config.mjs keygen <key.pem>                 new P-256 private key (mode 0600, never overwrites); prints LOSSLESS_CONFIG_PUBKEY
 *   node scripts/publish-config.mjs pubkey <key.pem>                 prints LOSSLESS_CONFIG_PUBKEY again
 *   node scripts/publish-config.mjs relaykey                         prints a fresh 64-hex RELAY_KEY
 *   node scripts/publish-config.mjs publish <key.pem> <input.json>   sign, upload both KV keys, read back, verify
 *   node scripts/publish-config.mjs verify [<key.pem>]               fetch the live pair and report (verifies when the key is given)
 *
 * input.json: {"relays":[{"base":"https://…","priority":1}],"relay_key":"<the Worker's RELAY_KEY secret>"}
 * `relays: []` is the kill switch. Keep key.pem and input.json OUTSIDE the repo.
 * `updated_at` is never hand-edited: it becomes max(now, live + 1), so the device's rollback floor always passes.
 * Needs a wrangler login (or CLOUDFLARE_API_TOKEN). Temp paths must not contain spaces (npx runs through a shell).
 */
import { generateKeyPairSync, createPrivateKey, createPublicKey, randomBytes, sign, verify } from "node:crypto";
import { readFileSync, writeFileSync, mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { execFileSync } from "node:child_process";
import { buildConfig } from "../src/config.js";

export const CONFIG_URL = "https://stash-tipjar.rawnaldclark.workers.dev/lossless.json";
/** STASH_KV: the id committed in infra/tipjar-worker/wrangler.toml. */
const KV_NAMESPACE_ID = "fca1bc38b42741e6a8cac11f54de5abd";
const KV_JSON = "lossless_config";
const KV_SIG = "lossless_config_sig";

const [cmd, a1, a2] = process.argv.slice(2);
const spki = (priv) => createPublicKey(priv).export({ type: "spki", format: "der" }).toString("base64");

async function live() {
    const [j, s] = await Promise.all([fetch(CONFIG_URL), fetch(`${CONFIG_URL}.sig`)]);
    if (j.status === 404) return null;
    if (!j.ok || !s.ok) throw new Error(`config host answered ${j.status}/${s.status}`);
    return { bytes: Buffer.from(await j.arrayBuffer()), sig: (await s.text()).trim() };
}

function kvPut(key, path) {
    execFileSync("npx", ["wrangler", "kv", "key", "put", key, "--path", path, "--namespace-id", KV_NAMESPACE_ID, "--remote"],
        { stdio: "inherit", shell: process.platform === "win32" });
}

async function report(pem) {
    const current = await live();
    if (!current) { console.log("live: nothing published yet (404)"); return; }
    const cfg = JSON.parse(current.bytes.toString());
    const sigOk = pem
        ? verify("sha256", current.bytes, createPublicKey(createPrivateKey(readFileSync(pem))), Buffer.from(current.sig, "base64"))
        : "(no key given)";
    console.log(`live: updated_at=${cfg.updated_at} relays=${cfg.relays.map((r) => r.base).join(",") || "(none)"} relay_key=${cfg.relay_key ? "present" : "absent"} signature=${sigOk}`);
}

switch (cmd) {
    case "keygen": {
        const { privateKey } = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
        writeFileSync(a1, privateKey.export({ type: "pkcs8", format: "pem" }), { mode: 0o600, flag: "wx" });
        console.log(`wrote ${a1}\nLOSSLESS_CONFIG_PUBKEY=${spki(privateKey)}`);
        break;
    }
    case "pubkey":
        console.log(spki(createPrivateKey(readFileSync(a1))));
        break;
    case "relaykey":
        console.log(randomBytes(32).toString("hex"));
        break;
    case "publish": {
        const priv = createPrivateKey(readFileSync(a1));
        const input = JSON.parse(readFileSync(a2, "utf8"));
        const current = await live();
        const liveUpdatedAt = current ? JSON.parse(current.bytes.toString()).updated_at : 0;
        const text = buildConfig(input, liveUpdatedAt, Math.floor(Date.now() / 1000));
        const bytes = Buffer.from(text);
        const dir = mkdtempSync(join(tmpdir(), "lossless-config-"));
        writeFileSync(join(dir, "lossless.json"), bytes);
        writeFileSync(join(dir, "lossless.json.sig"), sign("sha256", bytes, priv).toString("base64"));
        console.log(`publishing: ${text}`);
        kvPut(KV_JSON, join(dir, "lossless.json"));
        kvPut(KV_SIG, join(dir, "lossless.json.sig"));
        console.log("uploaded. KV propagates within ~60 s; reading the live copy now (re-run `verify` if it is still the old one):");
        await report(a1);
        break;
    }
    case "verify":
        await report(a1);
        break;
    default:
        console.error("usage: keygen <key.pem> | pubkey <key.pem> | relaykey | publish <key.pem> <input.json> | verify [<key.pem>]");
        process.exit(2);
}
```

- [ ] **Step 7: `scripts/sign-request.mjs`**

```js
#!/usr/bin/env node
/**
 * Prints a curl command for a signed smoke-test mint, signed the way the app signs (spec §5.4).
 *   node scripts/sign-request.mjs <relay_key> <track_id> <format_id> [base]
 * Pipe it to bash to run it. Set STASH_INSTALL to reuse one install id across calls (to see a HIT).
 */
import { createHmac, randomUUID } from "node:crypto";

const [key, trackId, formatId, base = "https://stash-relay.rawnaldclark.workers.dev"] = process.argv.slice(2);
if (!key || !trackId || !formatId) { console.error("usage: sign-request.mjs <relay_key> <track_id> <format_id> [base]"); process.exit(2); }
const install = process.env.STASH_INSTALL || randomUUID();
const ts = Math.floor(Date.now() / 1000);
const auth = createHmac("sha256", key).update(`${install}:${trackId}:${formatId}:${ts}`).digest("hex");
console.log(`curl -si -H "X-Stash-Version: 1" -H "Accept: application/json" -H "X-Stash-Install: ${install}" -H "X-Stash-Ts: ${ts}" -H "X-Stash-Auth: ${auth}" "${base}/v1/qobuz/file?track_id=${trackId}&format_id=${formatId}"`);
```

- [ ] **Step 8: Run the scripts' usage paths**

```bash
node scripts/publish-config.mjs relaykey | grep -cE '^[0-9a-f]{64}$'     # 1
node scripts/sign-request.mjs k 1 27 http://localhost:8787 | grep -c 'X-Stash-Auth'   # 1
node scripts/login.mjs; echo "exit=$?"                                     # usage line, exit=2
node scripts/publish-config.mjs; echo "exit=$?"                            # usage line, exit=2
```
Then finish Task 5 Step 5 (the local `wrangler dev` mint) now that `login.mjs` can produce the `.dev.vars` entry.

- [ ] **Step 9: Commit**

```bash
git add infra/lossless-relay/src/config.js infra/lossless-relay/test/config.test.js infra/lossless-relay/scripts/login.mjs infra/lossless-relay/scripts/publish-config.mjs infra/lossless-relay/scripts/sign-request.mjs
git commit -m "feat(relay): operator scripts — account login, signed config publishing, smoke-test signing"
```

---

### Task 7: The tipjar Worker serves the signed config

**Files:**
- Modify: `infra/tipjar-worker/src/index.js` (the `fetch` GET branch; one new function), `infra/tipjar-worker/README.md` (one section)

- [ ] **Step 1: Route the two config paths ahead of the supporters GET.** Replace the GET branch in `fetch`:

```js
        if (request.method === "GET") {
            const path = new URL(request.url).pathname;
            if (path === "/lossless.json" || path === "/lossless.json.sig") {
                return serveLosslessConfig(path, env);
            }
            return serveSupporters(env);
        }
```

Add below `serveSupporters`:

```js
/**
 * The Stash lossless relay config (Plan B contract §3): the signed `lossless.json`
 * and its base64 ECDSA signature, written to KV by
 * infra/lossless-relay/scripts/publish-config.mjs. Served byte-for-byte — the device
 * verifies the signature over the exact bytes, so nothing here may re-encode them.
 * 404 until the first publish; a device then keeps whatever it has cached.
 */
async function serveLosslessConfig(path, env) {
    const isJson = path === "/lossless.json";
    const bytes = await env.STASH_KV.get(isJson ? "lossless_config" : "lossless_config_sig", "arrayBuffer");
    if (!bytes) return new Response("Not found", { status: 404 });
    return new Response(bytes, {
        headers: {
            "Content-Type": isJson ? "application/json" : "text/plain",
            "Cache-Control": "public, max-age=300",
        },
    });
}
```

Update the file's header comment: after "GET → Serves the current supporter list…", add "GET /lossless.json and /lossless.json.sig → the signed lossless relay config, byte-for-byte from KV (see infra/lossless-relay)."

- [ ] **Step 2: README section** (append to `infra/tipjar-worker/README.md`):

```markdown
## Lossless relay config

The same Worker also serves `GET /lossless.json` and `GET /lossless.json.sig` —
the signed relay list the Stash app fetches at every cold start. They come
byte-for-byte from the KV keys `lossless_config` / `lossless_config_sig`, which
only `infra/lossless-relay/scripts/publish-config.mjs` writes. 404 until the
first publish. Nothing about supporters changes.
```

- [ ] **Step 3: Deploy and smoke**

```bash
cd infra/tipjar-worker && npm install && npx wrangler deploy
curl -si https://stash-tipjar.rawnaldclark.workers.dev/lossless.json | head -1        # HTTP/2 404 (nothing published yet)
curl -s https://stash-tipjar.rawnaldclark.workers.dev | head -c 60; echo              # {"supporters":[…  — unchanged
```

- [ ] **Step 4: Commit**

```bash
git add infra/tipjar-worker/src/index.js infra/tipjar-worker/README.md
git commit -m "feat(tipjar): serve the signed lossless relay config from KV"
```

---

### Task 8: Docs — the relay README and the spec amendments

**Files:**
- Create: `infra/lossless-relay/README.md`
- Modify: `docs/superpowers/specs/2026-08-31-lossless-relay-plan-b-contract.md`

- [ ] **Step 1: `infra/lossless-relay/README.md`**

```markdown
# Stash Lossless Relay (Cloudflare Worker)

Mints short-lived Qobuz FLAC URLs for the Stash app from a small pool of
operator-owned Qobuz accounts. The device streams from Qobuz's CDN directly;
**no audio ever transits this Worker.** Contract and design:
[`../../docs/superpowers/specs/2026-08-31-lossless-relay-plan-b-contract.md`](../../docs/superpowers/specs/2026-08-31-lossless-relay-plan-b-contract.md).

Sized as a bridge, not the main path: the durable lossless path is the user's
own Qobuz account (free to us, no ceiling). Four accounts serve roughly 65 daily
actives thanks to the shared mint cache; see spec §6.

## Endpoints

```
GET /v1/qobuz/file?track_id=<long>&format_id=<6|7|27>
    X-Stash-Version: 1  ·  X-Stash-Install  ·  X-Stash-Ts  ·  X-Stash-Auth (HMAC, spec §5.4)
    200 {"url","format_id","bit_depth","sample_rate"}   sample_rate in Hz
    404 not available for these accounts (ends the track on the device); also any non-lossless format such as Data Saver's 5 — lossless only
    503 busy — every account exhausted or the global cap hit (device cools 60 s)
    401 bad/missing signature · 429 per-install or per-IP cap · 400 bad request
GET /v1/status  → 200 {"ok":true}
```
`X-Stash-Cache: HIT|MISS` says whether the mint came from the shared cache.

## Deploy

Prereqs: Node 24, the Cloudflare account that runs `stash-tipjar`, at least one
paid or trial Qobuz account.

```bash
cd infra/lossless-relay
npm install
npx wrangler login                                  # or CLOUDFLARE_API_TOKEN=…

npx wrangler d1 create stash-relay                  # paste the printed database_id into wrangler.toml
npx wrangler d1 migrations apply stash-relay --remote

node scripts/publish-config.mjs relaykey            # keep this value: it is also published in lossless.json
npx wrangler secret put RELAY_KEY                   # paste it

QOBUZ_EMAIL=… QOBUZ_PASSWORD=… node scripts/login.mjs acct-1   # one line of JSON per account; repeat per account
npx wrangler secret put QOBUZ_ACCOUNTS              # paste the JSON ARRAY:  [ {…}, {…} ]

npx wrangler deploy                                 # prints https://stash-relay.<subdomain>.workers.dev
```

Smoke test (real Qobuz call, spends one mint):
```bash
curl -si https://stash-relay.<subdomain>.workers.dev/v1/status | head -1          # 200
node scripts/sign-request.mjs <RELAY_KEY> 937987774 27 https://stash-relay.<subdomain>.workers.dev | bash
# → 200, X-Stash-Cache: MISS, {"url":"https://streaming-qobuz-…etsp=…","format_id":…,"bit_depth":24,"sample_rate":96000}
curl -s -r 0-1023 "<url from the body>" -o /dev/null -w "%{http_code} %{content_type}\n"   # 206 audio/flac
```

Then publish the config that tells devices about it (Task 10 of the plan):
```bash
node scripts/publish-config.mjs keygen ~/.stash/lossless-config.key.pem   # once, ever; back the PEM up offline
# ~/.stash/lossless.input.json = {"relays":[{"base":"https://stash-relay.<subdomain>.workers.dev","priority":1}],"relay_key":"<RELAY_KEY>"}
node scripts/publish-config.mjs publish ~/.stash/lossless-config.key.pem ~/.stash/lossless.input.json
```
Release builds need `LOSSLESS_CONFIG_URL` = `https://stash-tipjar.<subdomain>.workers.dev/lossless.json`
and `LOSSLESS_CONFIG_PUBKEY` = the printed public key (GitHub Actions secrets; `lossless.configUrl` /
`lossless.configPubKey` in `local.properties` for local builds).

## Running it

| Need | Command |
|---|---|
| Live logs | `npx wrangler tail` — one line per mint: track, format, account label, outcome |
| Account health | `npx wrangler d1 execute stash-relay --remote --command "SELECT label,state,dead_reason,hour_n,day_n,cooling_until FROM accounts"` |
| Today's usage | `npx wrangler d1 execute stash-relay --remote --command "SELECT key,n FROM quota WHERE day=date('now') ORDER BY n DESC LIMIT 10"` |
| Hold an account in reserve | `… --command "UPDATE accounts SET state='reserve' WHERE label='acct-4'"` (`'live'` to bring it back) |
| Replace a dead account | re-run `login.mjs` for it, edit the array, `wrangler secret put QOBUZ_ACCOUNTS`, then `… --command "UPDATE accounts SET state='live', dead_reason='' WHERE label='acct-2'"` |
| Rotate the relay key | `relaykey` → `secret put RELAY_KEY` (new) and `secret put RELAY_KEY_PREV` (old) → publish a config with the new key → after a week `wrangler secret delete RELAY_KEY_PREV` |
| Kill switch | publish `{"relays":[]}` — every device drops the relay within one cold start |
| Change a cap | edit `[vars]` in `wrangler.toml`, `npx wrangler deploy` |
| Local dev | `.dev.vars` with `RELAY_KEY` + `QOBUZ_ACCOUNTS`; `npx wrangler d1 migrations apply stash-relay --local`; `npx wrangler dev` |
| Tests | `npm test` — everything but Cloudflare itself, D1 included (`node:sqlite`) |

An account goes `dead` on a 401, a `USER_BLOCKED` 403, or a preview reply, and
stays out until you replace it. Any other failure cools it for 5 minutes. The
device never learns which; it only sees 200, 404 or 503.

## Cost

Free tier: Workers 100k requests/day, D1 5M reads + 100k writes/day. A mint is
one read (cache) plus ≤ 4 writes on a miss; a cache hit writes nothing. At the
600 mints/day global cap that is under 3k writes/day. The real budget is Qobuz:
see spec §6.2 for why the per-account caps are what they are.
```

- [ ] **Step 2: Spec amendments.** In `docs/superpowers/specs/2026-08-31-lossless-relay-plan-b-contract.md`:

1. Line 3 status → `**Status:** contract fixed (§1-4), design decisions settled 2026-09-01 (§5), implementation plan written 2026-09-01 (docs/superpowers/plans/2026-09-01-lossless-relay-plan-b-worker.md).`
2. §2, after "being reachable is not.": add `The Worker answers `200 {"ok":true}`.`
3. §3, after the JSON example: add `The config host is the tipjar Worker: `LOSSLESS_CONFIG_URL = https://stash-tipjar.rawnaldclark.workers.dev/lossless.json`, the signature at `…/lossless.json.sig`, both served byte-for-byte from KV and written only by `infra/lossless-relay/scripts/publish-config.mjs`. Chosen over GitHub raw (the relay key would sit in public git history) and over the relay serving its own config (its hostname would then be in the APK).`
4. New **§5.6 Implementation decisions (2026-09-01, from the Worker plan)** after §5.5 — the eight-row table from this plan's "Decisions" section, verbatim, followed by:

   ```
   Status the Worker emits, and what the client does with it:
   | Worker | Meaning | Client |
   |---|---|---|
   | 200 | mint (cache HIT or MISS) | streams it |
   | 404 | no URL / lossy format from Qobuz for these accounts; or a request for a non-lossless format (Data Saver's 5), answered without a Qobuz call | NoMatch — ends the router for the track; Save Data falls to JioSaavn AAC 320 |
   | 503 + Retry-After | global cap hit, or every account dead/cooling/capped | cools the base 60 s |
   | 429 + Retry-After | per-install daily cap, or the per-IP limiter | cools the base 5 min |
   | 401 | signature missing/wrong, or \|skew\| > 300 s | cools the base 5 min |
   | 400 | malformed input only: a non-numeric or absent track_id/format_id, or a wrong X-Stash-Version | cools the base 5 min |
   | 500 | RELAY_KEY not configured | cools the base 5 min |
   ```
   Then extend §5.4's *Honest scope of this cap* paragraph with: *A cache hit is served before the per-install check and does not count against it — it spends no Qobuz request; the per-IP limiter is what bounds a client replaying popular tracks.*
5. §7 → replace the "Remaining is execution…" paragraph with: `Remaining is execution of the Worker plan: `docs/superpowers/plans/2026-09-01-lossless-relay-plan-b-worker.md` (Worker, publish tooling, first signed config, device proof, README disclosure). PR #465 must be merged before the release secrets `LOSSLESS_CONFIG_URL`/`LOSSLESS_CONFIG_PUBKEY` exist (plan Task 10 Step 2): until they do, no released device fetches a config at all, so there is no mixed-population window as long as that order holds.`

- [ ] **Step 3: Commit**

```bash
git add infra/lossless-relay/README.md docs/superpowers/specs/2026-08-31-lossless-relay-plan-b-contract.md
git commit -m "docs(relay): deploy recipe + ops runbook; record the Worker plan's decisions in the Plan B contract"
```

---
### Task 9: Deploy the relay (operator steps)

Nothing here is code. Every command is in `infra/lossless-relay/README.md`; this task is the checklist with expected output. Needs the user for: the wrangler login, the Qobuz credentials, and the account count.

- [ ] **Step 1: Login and database**

```bash
cd infra/lossless-relay
npx wrangler whoami            # "You are logged in" — else `npx wrangler login`
npx wrangler d1 create stash-relay
```
Expected: a `database_id`. Paste it into `wrangler.toml` (replacing `REPLACE_WITH_D1_ID`).
```bash
npx wrangler d1 migrations apply stash-relay --remote
```
Expected: `0001_init.sql` applied, 3 tables.

- [ ] **Step 2: Secrets**

```bash
node scripts/publish-config.mjs relaykey          # save the printed value: it goes into the config in Task 10
npx wrangler secret put RELAY_KEY                 # paste it
read -s -p "Qobuz password for acct-1: " QOBUZ_PASSWORD; echo; export QOBUZ_PASSWORD   # read -s keeps it out of shell history
QOBUZ_EMAIL=… node scripts/login.mjs acct-1      # one JSON line; repeat per account (acct-2, …)
unset QOBUZ_PASSWORD
npx wrangler secret put QOBUZ_ACCOUNTS            # paste:  [ <line from acct-1>, <line from acct-2>, … ]
```
Labels are what logs and D1 show; they must not be the email.

- [ ] **Step 3: Deploy and smoke (one real mint)**

```bash
npx wrangler deploy
```
Expected: `https://stash-relay.rawnaldclark.workers.dev`. Then:
```bash
curl -si https://stash-relay.rawnaldclark.workers.dev/v1/status | head -1                      # HTTP/2 200
curl -si -H "X-Stash-Version: 1" "https://stash-relay.rawnaldclark.workers.dev/v1/qobuz/file?track_id=937987774&format_id=27" | head -1   # HTTP/2 401
export STASH_INSTALL=smoke-test-0001
node scripts/sign-request.mjs <RELAY_KEY> 937987774 27 | bash
```
Expected: `HTTP/2 200`, `X-Stash-Cache: MISS`, body `{"url":"https://streaming-qobuz-std.akamaized.net/file?…etsp=…","format_id":7|27,"bit_depth":24,"sample_rate":96000|192000}`.
```bash
node scripts/sign-request.mjs <RELAY_KEY> 937987774 27 | bash | grep X-Stash-Cache               # HIT
curl -s -r 0-1023 "<url from the body>" -o /dev/null -w "%{http_code} %{content_type}\n"           # 206 audio/flac
npx wrangler d1 execute stash-relay --remote --command "SELECT label,state,hour_n,day_n FROM accounts"   # acct-1 live 1 1
```
`npx wrangler tail` in another shell shows `mint track=937987774 fmt=27 acct=acct-1 install=smoke-te -> ok`.

- [ ] **Step 4: Reserve**

With 4 accounts: `npx wrangler d1 execute stash-relay --remote --command "UPDATE accounts SET state='reserve' WHERE label='acct-4'"` (spec §6.4). With fewer, keep 1 in reserve all the same and lower `GLOBAL_DAILY_CAP` to `200 × live` in `wrangler.toml` (spec §7) and redeploy.

- [ ] **Step 5: Commit the database id**

```bash
git add infra/lossless-relay/wrangler.toml
git commit -m "chore(relay): bind the production D1 database"
```

---

### Task 10: Signing key, first signed config, build secrets

- [ ] **Step 1: Generate the config signing key, once**

```bash
mkdir -p ~/.stash
node scripts/publish-config.mjs keygen ~/.stash/lossless-config.key.pem
```
Expected: `wrote …` and one `LOSSLESS_CONFIG_PUBKEY=<base64>` line (124 chars of base64 for a P-256 SPKI key). **Back the PEM up offline.** Losing it means every installed device stops accepting new configs until a release ships a new public key; leaking it means anyone can point every device at their relay.

- [ ] **Step 2: Build secrets and local build props**

**Gate: PR #465 is merged into master** (`gh pr view 465 --json state -q .state` → `MERGED`). Do not set these secrets before it is: the next release would fetch the config and fail every mint with a 401.

```bash
gh secret set LOSSLESS_CONFIG_URL --body "https://stash-tipjar.rawnaldclark.workers.dev/lossless.json"
gh secret set LOSSLESS_CONFIG_PUBKEY --body "<base64 from step 1>"
gh secret list | grep LOSSLESS_CONFIG        # both present
```
Append to `local.properties` (gitignored):
```
lossless.configUrl=https://stash-tipjar.rawnaldclark.workers.dev/lossless.json
lossless.configPubKey=<base64 from step 1>
```

- [ ] **Step 3: Publish the first config**

`~/.stash/lossless.input.json`:
```json
{"relays":[{"base":"https://stash-relay.rawnaldclark.workers.dev","priority":1}],"relay_key":"<the RELAY_KEY from Task 9>"}
```
```bash
node scripts/publish-config.mjs publish ~/.stash/lossless-config.key.pem ~/.stash/lossless.input.json
```
Expected: `publishing: {"v":1,"relays":[{"base":"https://stash-relay.rawnaldclark.workers.dev","priority":1}],"updated_at":17882…,"relay_key":"…"}`, two wrangler uploads, then `live: updated_at=… relays=https://stash-relay.rawnaldclark.workers.dev relay_key=present signature=true`. If the read-back still shows the 404 or an older stamp, wait a minute and `node scripts/publish-config.mjs verify ~/.stash/lossless-config.key.pem`.

- [ ] **Step 4: Prove the publish rule that bites (spec §3 rule 1)**

Run the publish again with the same input. Expected: `updated_at` is strictly greater than the previous one even though nothing else changed. That is the rollback floor being respected automatically.

---

### Task 11: Device proof — a phone with no Qobuz account plays FLAC through the relay

Use the **Pixel 5 test rig**, not the daily Pixel 6: it has no Qobuz account connected, so the relay is the only lossless path, and it is driven entirely over adb (memory: `infra_pixel5_test_rig`). Rules from memory: `adb -s` always; check the APK's mtime and `versionName` before believing any logcat; a track already resolved this session replays from cache and logs nothing, so search a track never played on that device. **Save Data must be OFF on the rig** (Settings › Audio & Quality, the `Save Data` toggle in `SettingsAudioQualityScreen.kt`): with it on, the tier is Data Saver (format 5) and the relay answers 404 by design.

- [ ] **Step 1: Build from a branch that contains PR #465** (merged master, or this branch rebased on it) with the two `lossless.*` props in `local.properties`:

```bash
./gradlew :app:assembleDebug --max-workers=3 -Dorg.gradle.jvmargs=-Xmx4096m 2>&1 | grep -E "BUILD|^e:"; echo "EXIT=${PIPESTATUS[0]}"
ls -l --time-style=+%H:%M app/build/outputs/apk/debug/app-debug.apk       # mtime = now
```

- [ ] **Step 2: Install on the rig**

```bash
adb devices                                     # 192.168.137.35:5555 — if absent, ask the user for the current wireless-debugging port
adb -s 192.168.137.35:5555 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 192.168.137.35:5555 shell dumpsys package com.stash.app.debug | grep versionName
```

- [ ] **Step 3: Config applied at cold start**

```bash
adb -s 192.168.137.35:5555 logcat -c
adb -s 192.168.137.35:5555 shell am force-stop com.stash.app.debug
adb -s 192.168.137.35:5555 shell monkey -p com.stash.app.debug -c android.intent.category.LAUNCHER 1
sleep 12
adb -s 192.168.137.35:5555 logcat -d | grep -E "LosslessConfig"
```
Expected: `LosslessConfig: lossless config applied: 1 relay(s)`. Anything else is a config-side bug: `signature invalid` → the PUBKEY in `local.properties` is not this PEM's; `config failed: HTTP 404` → Task 10 Step 3 did not land.

- [ ] **Step 4: Settings shows the relay as the source.** Screencap Settings › Audio & Quality (`adb … exec-out screencap -p > s.png`): the routing rows read *Your Qobuz account ○ not connected · Stash lossless ● configured*.

- [ ] **Step 5: Play a never-played track.** Search (via `adb shell input text` + keyevent 66), tap the first result, wait 12 s, then:

```bash
adb -s 192.168.137.35:5555 logcat -d | grep -E "QbdlxFileUrlRouter|LosslessRelay|QbdlxStreamResolver: (resolved|disabled|no_)|StreamSourceRegistry: .*served"
```
Expected, in order: no `connected account` line (nothing to try), no `LosslessRelay … cooling` line, `QbdlxStreamResolver: resolved id=<n> origin=qbdlx expiresInSec=<~3500>`, `StreamSourceRegistry: qbdlx served <n> '<title>'`. `npx wrangler tail` on the PC shows the same mint with `install=<first 8 of the rig's install id>`. Screencap Now Playing: the FLAC badge (24/96 or better).

- [ ] **Step 6: The cache is shared.** Play the same track on the PC through `sign-request.mjs` with a fresh `STASH_INSTALL`: `X-Stash-Cache: HIT` and no new `mint` line in `wrangler tail`.

- [ ] **Step 7: Record it.** Add a `## Device proof` paragraph to the relay README with the date, the rig, and the two logcat lines. Commit:

```bash
git add infra/lossless-relay/README.md
git commit -m "docs(relay): record the first relay-served FLAC on a device with no Qobuz account"
```

---

### Task 12: README disclosure and the PR

**Files:**
- Modify: `README.md` (the Lossless section and the hosts list)

- [ ] **Step 1: Lossless section.** Replace the "A relay configured at runtime" bullet with:

```markdown
- **Stash's own relay** — the official release build fetches a signed config at each cold start that lists `stash-relay.rawnaldclark.workers.dev`, a relay the project runs on a few paid Qobuz accounts. It hands your phone a short-lived Qobuz CDN link for the track you tapped; the audio comes from Qobuz's CDN, never through the relay. It is sized as a bridge, not a main path: connect your own account and your phone stops using it. The relay hostname is not in the APK — a plain source checkout has no config URL, so a Stash you build yourself has this path switched off entirely.
```

- [ ] **Step 2: Hosts list.** Replace the "A lossless relay" and "The lossless config host" bullets with:

```markdown
- **`stash-relay.rawnaldclark.workers.dev`** — the project's lossless relay, reached only from the official release build and only when no account of your own is connected. It is sent the Qobuz track id and format of what you're playing, a random per-install id used for rate limiting, and nothing else — so this host learns what an anonymous install listens to. No credential ever crosses it. Or the endpoint you configure yourself, same contract.
- **`stash-tipjar.rawnaldclark.workers.dev/lossless.json`** — the signed relay config and its `.sig`, fetched at every cold start and every 6 hours after by the official release build. This URL *is* in the APK and it is the only thing Stash fetches with nothing of yours connected, besides the tip jar list on the same host. A plain checkout has no config URL and skips it.
```

- [ ] **Step 3: Release-notes line** for the next release (paste into the notes when it ships; `feedback_release_notes`: CI creates the release, edit the notes after):

> Lossless without an account: the official build now reaches Stash's own Qobuz relay when you have no Qobuz account connected — Settings › Audio shows it as *Stash lossless*. Your own account still comes first and costs the project nothing.

- [ ] **Step 4: Commit and open the PR**

```bash
git add README.md
git commit -m "docs: disclose the lossless relay and config hosts the official build contacts"
git push -u origin feat/lossless-relay-worker
gh pr create --title "feat(relay): Stash's own lossless relay — Worker, signed config publishing, device-proven" --body-file <(cat <<'EOF'
## What
The server half of Plan B: `infra/lossless-relay/` (Cloudflare Worker + D1), the tipjar Worker serving the signed `lossless.json`, and the operator scripts (account login, config publishing). Contract: `docs/superpowers/specs/2026-08-31-lossless-relay-plan-b-contract.md` §1-5.

## Verification
- `npm test` in `infra/lossless-relay`: auth vector shared with `LosslessRelayClientTest`, MD5 vectors shared with `QbdlxSignerTest`, rotation/cache/quota SQL on `node:sqlite`, the whole mint flow with a fake Qobuz.
- Deployed; one real mint over curl → 200 + `206 audio/flac` on the CDN URL; second call HIT.
- Device: Pixel 5 with no Qobuz account → `qbdlx served` FLAC through the relay (README "Device proof").

## Not in this PR
Tidal, per-install registration, negative caching, skew correction, the donation goal tracker.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)
```

---

## Verification summary

| Claim | How it is proven |
|---|---|
| The Worker signs Qobuz requests exactly like the app | Task 3: the two HAR vectors from `QbdlxSignerTest` |
| The Worker verifies exactly what PR #465 sends | Task 2: `LosslessRelayClientTest`'s fixture, pinned hex |
| Rotation is LRU under caps and atomic | Task 4: executed under `node:sqlite`, the same SQLite D1 runs |
| Every status in the contract maps to the right client behaviour | Task 5 tests + the §5.6 table |
| Devices accept the published config | Task 10 read-back verify + Task 11 Step 3 `config applied` |
| No-account device plays FLAC via the relay | Task 11 Step 5 logcat + Now Playing badge |
| Mints are shared across installs | Task 11 Step 6 HIT from a different install id |

## Notes for the executor

- The Qobuz `User-Agent`, origin, header names and `intent=stream` are copied from `QbdlxApiClient`; do not "improve" them — that request shape is the one Qobuz answers with FLAC.
- `sample_rate` must be Hz on the wire (spec §1). Qobuz's `sampling_rate` is kHz.
- Never log a minted URL or a token. The Worker logs track, format, label, and 8 chars of the install id.
- `updated_at` is set by the publish script, never by hand. Re-publishing an old file needs the script, not a KV editor.
- Format 5 (Data Saver) is answered 404 before any Qobuz call. Separately, the client's own-account path has an open bug with format 5 (`QbdlxApiClient.classify` treats a format-5 reply as a dead token even when 5 was requested, so Save Data plus a connected account rejects the login); not this plan's scope, tracked outside it.
- If `wrangler dev` rejects the `[[ratelimits]]` binding locally, the mint path already tolerates its absence (`if (env.MINT_RL)`); it is enforced in production.
