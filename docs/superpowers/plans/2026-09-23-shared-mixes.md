# Shared Mixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a Stash user share any playlist or mix as a tappable `https` link that other Stash users can **Follow** (a read-only copy that keeps receiving the owner's changes) or **Save a copy** (a frozen, editable playlist). Single-track share links are upgraded to the same style.

**Architecture:**
- **Server:** a new Cloudflare Worker (`infra/share-worker`, deployed as `stash-share`) stores one JSON document per shared mix in KV. It authorises updates with a per-mix edit key, of which it stores only the SHA-256. It also serves preview pages and Android's `assetlinks.json`.
- **App core** (`core/data/share`): the app turns playlist rows into documents and back. Received tracks are persisted through the existing `MusicRepository.ensureTrackPersisted` path, so the existing stream chain plays them.
- **Background work:** two WorkManager jobs keep owner and follower copies in step.
- **UI:** a mix screen, a track card, a share sheet, and the followed-playlist state in `PlaylistDetailScreen`.

**Tech stack:**
- **Worker:** JavaScript (ES modules), KV, `[[ratelimits]]`, `node --test`.
- **App:** Kotlin, Room 2.7.1, Hilt, WorkManager, OkHttp, kotlinx-serialization, Jetpack Compose with type-safe Navigation.
- **App tests:** JUnit4, Truth, MockK, Robolectric, MockWebServer.

**Spec:** `docs/superpowers/specs/2026-09-23-shared-mixes-design.md`. Read it first. Section numbers below (§n) refer to it.

---

## Ground rules for this repo (read before Task 1)

- **Staging:** never use `git add -A` or `git add .`: the repo has large untracked binaries under `spike/`. Stage explicit paths, and check `git diff --cached --name-only` before each commit.
- **Line endings:** the repo uses CRLF on Windows (`core.autocrlf=true`). Edit files with the editor tools; git normalises line endings.
- **Gradle:** run one Gradle invocation at a time. Two in parallel corrupt KSP caches.
  - Unit tests for one module: `./gradlew :core:data:testDebugUnitTest --tests '<pattern>' -q`
  - Whole-app build: `./gradlew :app:assembleDebug -q`
- **`DatabaseBackupMergeTest` is flaky on Windows** (its temp path exceeds the path-length limit). If it's the only failure, rerun it alone. It isn't caused by your change.
- **Worker tests:** `cd infra/share-worker && npm test`. They use Node's built-in runner and need no install; Node 24 is on this PC.
- **Room schema version:** this plan uses **47 → 48**. **Before Task 8, check the current `version =` in `StashDatabase.kt`.** If an open PR (e.g. #496, lyrics) has already claimed 48/49, use the next free number everywhere this plan says 48, and rename the migration and test to match.
- **Commits:** end every commit message with the trailer
  `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`

---

## File structure

**New: `infra/share-worker/`**

| File | Responsibility |
|---|---|
| `package.json`, `wrangler.toml` | Worker manifest, the KV binding and the two rate-limit bindings |
| `src/index.js` | Routing and HTTP responses |
| `src/store.js` | KV reads and writes, ID generation, edit-key hashing and comparison |
| `src/validate.js` | Document validation (the §3 limits) |
| `src/pages.js` | Preview-page HTML, HTML escaping and `assetlinks.json` |
| `test/fake-kv.js` | An in-memory KV stand-in for tests |
| `test/*.test.js` | Node tests |
| `README.md` | Deploy steps |

**New: `core/model/src/main/kotlin/com/stash/core/model/share/`**

Pure Kotlin, so `core/ui` can use it too:
- `SharedTrack.kt`: the descriptor, plus `Track` ↔ descriptor mapping.
- `ShareLinks.kt`: `ShareConfig`, and building and parsing links.

**New: `core/data/src/main/kotlin/com/stash/core/data/share/`**

| File | Responsibility |
|---|---|
| `SharedMixDocument.kt` | The document model, its JSON and the content hash |
| `ShareApiClient.kt` | The HTTP client for the Worker |
| `SharedMixRepository.kt` | Every share and follow operation |
| `SharedMixPublishWorker.kt`, `SharedMixFollowWorker.kt` | The background jobs |
| `SharePreference.kt` | The "Show my name as" preference |

**New: `core/data/.../db/`**
- `entity/SharedMixEntity.kt`
- `dao/SharedMixDao.kt`

**Modified**
- `StashDatabase.kt`: entity, DAO, migration.
- `DatabaseModule.kt`: DAO provider.
- `TrackDao.kt`: ISRC/album backfill.
- `PlaylistDao.kt`: picker exclusion, `setSyncEnabled`.
- `MusicRepositoryImpl.kt`: backfill, and a publish trigger after edits.
- `SyncFinalizeWorker.kt` and `StashApplication.kt`: triggers.
- `SharedTrackLinkHolder.kt`: now holds a `SharedTrack`.
- `MainActivity.kt`, `StashScaffold.kt`, `TopLevelDestination.kt`, `StashNavHost.kt`, `AndroidManifest.xml`: links in.
- `SearchViewModel.kt`: drop the old query hand-off.
- `ShareTrackSheet.kt`: https track link.
- `feature/library`: new `share/` package (mix screen, track card, share sheet), plus edits to `PlaylistDetailScreen.kt`, `PlaylistDetailViewModel.kt` and `LibraryScreen.kt`.
- `feature/home/HomeScreen.kt`: Share row.
- `README.md`: host disclosure.

---

# Part A: The Worker

### Task 1: Worker scaffold, fake KV, routing

**Files:**
- Create: `infra/share-worker/package.json`, `infra/share-worker/wrangler.toml`, `infra/share-worker/src/index.js`, `infra/share-worker/test/fake-kv.js`, `infra/share-worker/test/routes.test.js`

- [ ] **Step 1: Create `package.json`**

```json
{
  "name": "stash-share",
  "version": "1.0.0",
  "private": true,
  "type": "module",
  "description": "Stores shared Stash mixes (one JSON document per mix in KV) and serves their preview pages.",
  "scripts": {
    "dev": "wrangler dev",
    "deploy": "wrangler deploy",
    "tail": "wrangler tail",
    "test": "node --no-warnings --test"
  },
  "devDependencies": {
    "wrangler": "^4.135.0"
  }
}
```

- [ ] **Step 2: Create `wrangler.toml`.** The KV `id` is filled in during Task 23.

```toml
name = "stash-share"
main = "src/index.js"
compatibility_date = "2026-09-01"
compatibility_flags = ["nodejs_compat"]

# Created in Task 23: `npx wrangler kv namespace create SHARE_KV`, then paste the id.
[[kv_namespaces]]
binding = "SHARE_KV"
id = "REPLACE_IN_TASK_23"

# A ratelimits binding carries ONE fixed limit, so creating and writing get one each (spec §4).
[[ratelimits]]
name = "CREATE_RL"
namespace_id = "2001"
simple = { limit = 5, period = 60 }

[[ratelimits]]
name = "WRITE_RL"
namespace_id = "2002"
simple = { limit = 30, period = 60 }
```

- [ ] **Step 3: Create `test/fake-kv.js`**

```js
/** Just enough of Workers KV for src/store.js: get(key, "json"), put(key, value, opts), delete(key). */
export function fakeKV() {
    const map = new Map();
    return {
        map,
        async get(key, type) {
            const v = map.has(key) ? map.get(key).value : null;
            return v !== null && type === "json" ? JSON.parse(v) : v;
        },
        async put(key, value, opts = {}) { map.set(key, { value, opts }); },
        async delete(key) { map.delete(key); },
    };
}

export function env(over = {}) {
    return {
        SHARE_KV: fakeKV(),
        CREATE_RL: { limit: async () => ({ success: true }) },
        WRITE_RL: { limit: async () => ({ success: true }) },
        ...over,
    };
}
```

- [ ] **Step 4: Write the failing test, `test/routes.test.js`**

```js
import { test } from "node:test";
import assert from "node:assert/strict";
import { handle } from "../src/index.js";
import { env } from "./fake-kv.js";

const BASE = "https://share.test";

test("unknown path is 404, wrong method is 405", async () => {
    assert.equal((await handle(new Request(`${BASE}/nope`), env())).status, 404);
    assert.equal((await handle(new Request(`${BASE}/v1/mixes/abcdefgh`, { method: "PATCH" }), env())).status, 405);
});
```

- [ ] **Step 5: Run it and check it fails**

Run: `cd infra/share-worker && npm test`
Expected: FAIL, `Cannot find module '../src/index.js'`.

- [ ] **Step 6: Create `src/index.js` with routing only**

```js
/**
 * Stash Share: Cloudflare Worker (spec docs/superpowers/specs/2026-09-23-shared-mixes-design.md §4).
 * One KV document per shared mix; updates and deletes are authorised by a per-mix edit key whose
 * SHA-256 is all we store.
 */
const MIX_API = /^\/v1\/mixes\/([A-Za-z0-9]{8})(\/version)?$/;

export default { fetch: (request, env) => handle(request, env) };

export async function handle(request, env) {
    const url = new URL(request.url);
    const path = url.pathname;
    const method = request.method;

    if (path === "/v1/mixes") return method === "POST" ? notImplemented() : methodNotAllowed();
    const m = MIX_API.exec(path);
    if (m) {
        if (m[2]) return method === "GET" ? notImplemented() : methodNotAllowed();
        if (method === "GET" || method === "PUT" || method === "DELETE") return notImplemented();
        return methodNotAllowed();
    }
    return json({ error: "not_found" }, 404);
}

export function json(obj, status = 200, extra = {}) {
    return new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json", ...extra } });
}
const methodNotAllowed = () => json({ error: "method_not_allowed" }, 405);
const notImplemented = () => json({ error: "not_implemented" }, 501);
```

- [ ] **Step 7: Run the tests and check they pass**

Run: `cd infra/share-worker && npm test`
Expected: `ℹ pass 1`, `ℹ fail 0`.

- [ ] **Step 8: Commit**

```bash
git add infra/share-worker/package.json infra/share-worker/wrangler.toml infra/share-worker/src/index.js infra/share-worker/test/fake-kv.js infra/share-worker/test/routes.test.js
git commit -m "feat(share): scaffold the stash-share Worker with routing and a fake KV"
```

### Task 2: Validation, the store, and creating a mix

**Files:**
- Create: `infra/share-worker/src/validate.js`, `infra/share-worker/src/store.js`, `infra/share-worker/test/create.test.js`
- Modify: `infra/share-worker/src/index.js`

- [ ] **Step 1: Write the failing test, `test/create.test.js`**

```js
import { test } from "node:test";
import assert from "node:assert/strict";
import { handle } from "../src/index.js";
import { env } from "./fake-kv.js";

const BASE = "https://share.test";
const KEY = "k".repeat(43); // base64url of 32 bytes is 43 chars
const doc = (over = {}) => ({ v: 1, name: "Ambient", sharedBy: "Rawn", tracks: [{ t: "Avril 14th", a: "Aphex Twin" }], ...over });
const post = (body, e) => handle(new Request(`${BASE}/v1/mixes`, {
    method: "POST", headers: { "content-type": "application/json", "CF-Connecting-IP": "203.0.113.9" }, body: JSON.stringify(body),
}), e);

test("create stores the doc with server-set id/version/updatedAt and only the key's hash", async () => {
    const e = env();
    const r = await post({ doc: doc(), editKey: KEY }, e);
    assert.equal(r.status, 201);
    const { id, version, url } = await r.json();
    assert.match(id, /^[A-Za-z0-9]{8}$/);
    assert.equal(version, 1);
    assert.equal(url, `${BASE}/m/${id}`);
    const stored = await e.SHARE_KV.get(`mix:${id}`, "json");
    assert.equal(stored.doc.id, id);
    assert.equal(stored.doc.version, 1);
    assert.ok(stored.doc.updatedAt > 0);
    assert.match(stored.keyHash, /^[0-9a-f]{64}$/);
    assert.ok(!JSON.stringify(stored).includes(KEY), "the raw edit key is never stored");
});

test("create rejects bad docs and bad keys", async () => {
    const e = env();
    const bad = [
        { doc: doc({ name: "" }), editKey: KEY },
        { doc: doc({ name: "x".repeat(101) }), editKey: KEY },
        { doc: doc({ sharedBy: "y".repeat(41) }), editKey: KEY },
        { doc: doc({ tracks: [] }), editKey: KEY },
        { doc: doc({ tracks: [{ t: "", a: "A" }] }), editKey: KEY },
        { doc: doc({ tracks: Array.from({ length: 2001 }, () => ({ t: "T", a: "A" })) }), editKey: KEY },
        { doc: doc({ covers: ["http://insecure/a.jpg"] }), editKey: KEY },
        { doc: doc({ v: 2 }), editKey: KEY },
        { doc: doc(), editKey: "short" },
    ];
    for (const body of bad) assert.equal((await post(body, e)).status, 400, JSON.stringify(body).slice(0, 80));
    assert.equal(e.SHARE_KV.map.size, 0);
});

test("create rejects a body over 1 MB with 413 and is rate-limited with 429", async () => {
    const big = { doc: doc({ tracks: Array.from({ length: 2000 }, () => ({ t: "T".repeat(600), a: "A" })) }), editKey: KEY };
    assert.equal((await post(big, env())).status, 413);
    const limited = env({ CREATE_RL: { limit: async () => ({ success: false }) } });
    assert.equal((await post({ doc: doc(), editKey: KEY }, limited)).status, 429);
});
```

- [ ] **Step 2: Run it and check it fails**

Run: `cd infra/share-worker && npm test`
Expected: FAIL. POST returns 501.

- [ ] **Step 3: Create `src/validate.js`**

```js
/** Spec §3 limits. Returns an error string, or null when the document is valid. */
export const MAX_BODY_BYTES = 1_000_000;
export const MAX_TRACKS = 2000;

const str = (v, min, max) => typeof v === "string" && v.trim().length >= min && v.length <= max;
const optStr = (v, max) => v === undefined || v === null || (typeof v === "string" && v.length <= max);

export function validateDoc(doc) {
    if (!doc || typeof doc !== "object") return "doc missing";
    if (doc.v !== 1) return "unsupported v";
    if (!str(doc.name, 1, 100)) return "bad name";
    if (!optStr(doc.sharedBy, 40)) return "bad sharedBy";
    if (doc.covers !== undefined) {
        if (!Array.isArray(doc.covers) || doc.covers.length > 4) return "bad covers";
        if (!doc.covers.every((c) => typeof c === "string" && c.startsWith("https://") && c.length <= 1000)) return "bad cover url";
    }
    if (!Array.isArray(doc.tracks) || doc.tracks.length < 1 || doc.tracks.length > MAX_TRACKS) return "bad tracks";
    for (const t of doc.tracks) {
        if (!t || !str(t.t, 1, 500) || !str(t.a, 1, 500)) return "bad track";
        if (!optStr(t.al, 500) || !optStr(t.isrc, 20) || !optStr(t.sp, 40) || !optStr(t.yt, 20)) return "bad track field";
        if (t.d !== undefined && t.d !== null && !(Number.isInteger(t.d) && t.d > 0)) return "bad duration";
    }
    return null;
}

/** base64url of 32 random bytes = 43 chars. */
export const validEditKey = (k) => typeof k === "string" && /^[A-Za-z0-9_-]{43}$/.test(k);
```

- [ ] **Step 4: Create `src/store.js`**

```js
const ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
const TOMBSTONE_TTL_S = 180 * 24 * 3600;
const kvKey = (id) => `mix:${id}`;

export function newId() {
    const bytes = crypto.getRandomValues(new Uint8Array(8));
    return Array.from(bytes, (b) => ALPHABET[b % 62]).join("");
}

export async function sha256Hex(s) {
    const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
    return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, "0")).join("");
}

/** Constant-time comparison of two equal-length hex strings. */
export function sameHex(a, b) {
    if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
    let diff = 0;
    for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
    return diff === 0;
}

export const readMix = (kv, id) => kv.get(kvKey(id), "json");
export const writeMix = (kv, id, record) => kv.put(kvKey(id), JSON.stringify(record));
export const writeTombstone = (kv, id) =>
    kv.put(kvKey(id), JSON.stringify({ deleted: true }), { expirationTtl: TOMBSTONE_TTL_S });

/** A fresh id that isn't taken (collisions are ~impossible, but retry anyway). */
export async function freeId(kv) {
    for (let i = 0; i < 5; i++) {
        const id = newId();
        if ((await readMix(kv, id)) === null) return id;
    }
    throw new Error("could not allocate an id");
}
```

- [ ] **Step 5: Wire POST in `src/index.js`.** Replace the `/v1/mixes` line and add the imports and `createMix`:

```js
import { validateDoc, validEditKey, MAX_BODY_BYTES } from "./validate.js";
import { freeId, sha256Hex, writeMix } from "./store.js";
```
```js
    if (path === "/v1/mixes") return method === "POST" ? createMix(request, env, url) : methodNotAllowed();
```
```js
async function readBody(request) {
    const text = await request.text();
    if (new TextEncoder().encode(text).length > MAX_BODY_BYTES) return { tooBig: true };
    try { return { body: JSON.parse(text) }; } catch { return { body: null }; }
}

const ip = (request) => request.headers.get("CF-Connecting-IP") || "?";

async function createMix(request, env, url) {
    if (!(await env.CREATE_RL.limit({ key: ip(request) })).success) return json({ error: "rate_limited" }, 429, { "Retry-After": "60" });
    const { body, tooBig } = await readBody(request);
    if (tooBig) return json({ error: "too_large" }, 413);
    if (!body || !validEditKey(body.editKey)) return json({ error: "bad_request" }, 400);
    const problem = validateDoc(body.doc);
    if (problem) return json({ error: "bad_request", message: problem }, 400);
    const id = await freeId(env.SHARE_KV);
    const doc = { ...body.doc, id, version: 1, updatedAt: Math.floor(Date.now() / 1000) };
    await writeMix(env.SHARE_KV, id, { doc, keyHash: await sha256Hex(body.editKey), deleted: false });
    return json({ id, version: 1, url: `${url.origin}/m/${id}` }, 201);
}
```

- [ ] **Step 6: Run the tests and check they pass**

Run: `cd infra/share-worker && npm test`
Expected: `ℹ fail 0`.

- [ ] **Step 7: Commit**

```bash
git add infra/share-worker/src/validate.js infra/share-worker/src/store.js infra/share-worker/src/index.js infra/share-worker/test/create.test.js
git commit -m "feat(share): create a shared mix — validation, server-set id/version, key stored as hash"
```

### Task 3: Update, delete, get, version

**Files:**
- Create: `infra/share-worker/test/lifecycle.test.js`
- Modify: `infra/share-worker/src/index.js`

- [ ] **Step 1: Write the failing test, `test/lifecycle.test.js`**

```js
import { test } from "node:test";
import assert from "node:assert/strict";
import { handle } from "../src/index.js";
import { env } from "./fake-kv.js";

const BASE = "https://share.test";
const KEY = "k".repeat(43);
const OTHER = "o".repeat(43);
const doc = (name = "Ambient") => ({ v: 1, name, tracks: [{ t: "T", a: "A" }] });
const req = (method, path, { body, key } = {}) => new Request(`${BASE}${path}`, {
    method,
    headers: { "content-type": "application/json", ...(key ? { "X-Stash-Edit-Key": key } : {}) },
    body: body ? JSON.stringify(body) : undefined,
});
async function created(e) {
    const r = await handle(req("POST", "/v1/mixes", { body: { doc: doc(), editKey: KEY } }), e);
    return (await r.json()).id;
}

test("get and version serve a live mix", async () => {
    const e = env(); const id = await created(e);
    const g = await handle(req("GET", `/v1/mixes/${id}`), e);
    assert.equal(g.status, 200);
    assert.equal((await g.json()).name, "Ambient");
    assert.deepEqual(await (await handle(req("GET", `/v1/mixes/${id}/version`), e)).json(), { version: 1 });
});

test("put with the right key bumps the version; wrong or missing key is 403", async () => {
    const e = env(); const id = await created(e);
    assert.equal((await handle(req("PUT", `/v1/mixes/${id}`, { body: { doc: doc("Renamed") }, key: OTHER }), e)).status, 403);
    assert.equal((await handle(req("PUT", `/v1/mixes/${id}`, { body: { doc: doc("Renamed") } }), e)).status, 403);
    const ok = await handle(req("PUT", `/v1/mixes/${id}`, { body: { doc: doc("Renamed") }, key: KEY }), e);
    assert.equal(ok.status, 200);
    assert.deepEqual(await ok.json(), { version: 2 });
    const g = await (await handle(req("GET", `/v1/mixes/${id}`), e)).json();
    assert.equal(g.name, "Renamed"); assert.equal(g.version, 2); assert.equal(g.id, id);
});

test("delete leaves a 410 tombstone with a TTL; unknown ids are 404", async () => {
    const e = env(); const id = await created(e);
    assert.equal((await handle(req("DELETE", `/v1/mixes/${id}`, { key: OTHER }), e)).status, 403);
    assert.equal((await handle(req("DELETE", `/v1/mixes/${id}`, { key: KEY }), e)).status, 204);
    assert.equal((await handle(req("GET", `/v1/mixes/${id}`), e)).status, 410);
    assert.equal((await handle(req("GET", `/v1/mixes/${id}/version`), e)).status, 410);
    assert.equal((await handle(req("PUT", `/v1/mixes/${id}`, { body: { doc: doc() }, key: KEY }), e)).status, 410);
    assert.ok(e.SHARE_KV.map.get(`mix:${id}`).opts.expirationTtl > 0);
    assert.equal((await handle(req("GET", "/v1/mixes/zzzzzzzz"), e)).status, 404);
    assert.equal((await handle(req("GET", "/v1/mixes/zzzzzzzz/version"), e)).status, 404);
});

test("writes are rate-limited", async () => {
    const e = env(); const id = await created(e);
    e.WRITE_RL = { limit: async () => ({ success: false }) };
    assert.equal((await handle(req("PUT", `/v1/mixes/${id}`, { body: { doc: doc() }, key: KEY }), e)).status, 429);
});
```

- [ ] **Step 2: Run it and check it fails**

Run: `cd infra/share-worker && npm test`
Expected: FAIL. The routes return 501.

- [ ] **Step 3: Implement the routes in `src/index.js`.** Replace the `if (m) {...}` block and add the functions:

```js
import { readMix, sameHex, writeTombstone } from "./store.js";
```
```js
    if (m) {
        const id = m[1];
        if (m[2]) return method === "GET" ? getVersion(env, id) : methodNotAllowed();
        if (method === "GET") return getMix(env, id);
        if (method === "PUT") return updateMix(request, env, id);
        if (method === "DELETE") return deleteMix(request, env, id);
        return methodNotAllowed();
    }
```
```js
/** Loads a mix: { record } when live, else a ready 404/410 response. */
async function load(env, id) {
    const record = await readMix(env.SHARE_KV, id);
    if (record === null) return { response: json({ error: "not_found" }, 404) };
    if (record.deleted) return { response: json({ error: "gone" }, 410) };
    return { record };
}

async function getMix(env, id) {
    const { record, response } = await load(env, id);
    return response ?? json(record.doc, 200, { "cache-control": "no-store" });
}

async function getVersion(env, id) {
    const { record, response } = await load(env, id);
    return response ?? json({ version: record.doc.version }, 200, { "cache-control": "no-store" });
}

async function authorised(request, record) {
    const key = request.headers.get("X-Stash-Edit-Key") || "";
    return validEditKey(key) && sameHex(await sha256Hex(key), record.keyHash);
}

async function updateMix(request, env, id) {
    if (!(await env.WRITE_RL.limit({ key: ip(request) })).success) return json({ error: "rate_limited" }, 429, { "Retry-After": "60" });
    const { record, response } = await load(env, id);
    if (response) return response;
    if (!(await authorised(request, record))) return json({ error: "forbidden" }, 403);
    const { body, tooBig } = await readBody(request);
    if (tooBig) return json({ error: "too_large" }, 413);
    const problem = validateDoc(body?.doc);
    if (problem) return json({ error: "bad_request", message: problem }, 400);
    const version = record.doc.version + 1;
    const doc = { ...body.doc, id, version, updatedAt: Math.floor(Date.now() / 1000) };
    await writeMix(env.SHARE_KV, id, { ...record, doc });
    return json({ version });
}

async function deleteMix(request, env, id) {
    if (!(await env.WRITE_RL.limit({ key: ip(request) })).success) return json({ error: "rate_limited" }, 429, { "Retry-After": "60" });
    const { record, response } = await load(env, id);
    if (response) return response;
    if (!(await authorised(request, record))) return json({ error: "forbidden" }, 403);
    await writeTombstone(env.SHARE_KV, id);
    return new Response(null, { status: 204 });
}
```

- [ ] **Step 4: Run the tests and check they pass**

Run: `cd infra/share-worker && npm test`
Expected: `ℹ fail 0`.

- [ ] **Step 5: Commit**

```bash
git add infra/share-worker/src/index.js infra/share-worker/test/lifecycle.test.js
git commit -m "feat(share): update, delete (410 tombstone), get and version routes"
```

### Task 4: Preview pages, escaping, `assetlinks.json`

**Files:**
- Create: `infra/share-worker/src/pages.js`, `infra/share-worker/test/pages.test.js`
- Modify: `infra/share-worker/src/index.js`

- [ ] **Step 1: Write the failing test, `test/pages.test.js`**

```js
import { test } from "node:test";
import assert from "node:assert/strict";
import { handle } from "../src/index.js";
import { env } from "./fake-kv.js";

const BASE = "https://share.test";
const KEY = "k".repeat(43);

async function withMix(e, doc) {
    const r = await handle(new Request(`${BASE}/v1/mixes`, { method: "POST", body: JSON.stringify({ doc, editKey: KEY }) }), e);
    return (await r.json()).id;
}

test("mix page escapes every string and carries Open Graph tags", async () => {
    const e = env();
    const id = await withMix(e, { v: 1, name: "<script>alert(1)</script>", sharedBy: "\"Rawn\"", covers: ["https://img.test/a.jpg"],
        tracks: [{ t: "<b>T</b>", a: "A&B" }] });
    const r = await handle(new Request(`${BASE}/m/${id}`), e);
    assert.equal(r.status, 200);
    assert.match(r.headers.get("content-type"), /text\/html/);
    const html = await r.text();
    assert.ok(!html.includes("<script>alert(1)</script>"));
    assert.ok(html.includes("&lt;script&gt;alert(1)&lt;/script&gt;"));
    assert.ok(html.includes("&lt;b&gt;T&lt;/b&gt;") && html.includes("A&amp;B") && html.includes("&quot;Rawn&quot;"));
    assert.ok(html.includes('property="og:title"') && html.includes('content="https://img.test/a.jpg"'));
    assert.ok(html.includes(`intent://`) && html.includes("releases/latest"));
});

test("page for a deleted or unknown mix still renders with the right status", async () => {
    const e = env();
    const id = await withMix(e, { v: 1, name: "X", tracks: [{ t: "T", a: "A" }] });
    await handle(new Request(`${BASE}/v1/mixes/${id}`, { method: "DELETE", headers: { "X-Stash-Edit-Key": KEY } }), e);
    const gone = await handle(new Request(`${BASE}/m/${id}`), e);
    assert.equal(gone.status, 410); assert.match(await gone.text(), /no longer shared/);
    const unknown = await handle(new Request(`${BASE}/m/zzzzzzzz`), e);
    assert.equal(unknown.status, 404);
});

test("track page escapes the query values", async () => {
    const r = await handle(new Request(`${BASE}/t?t=%3Ci%3ESong&a=Artist`), env());
    assert.equal(r.status, 200);
    const html = await r.text();
    assert.ok(html.includes("&lt;i&gt;Song") && !html.includes("<i>Song"));
});

test("assetlinks lists both packages with their certificate fingerprints", async () => {
    const r = await handle(new Request(`${BASE}/.well-known/assetlinks.json`), env());
    assert.equal(r.status, 200);
    const body = await r.json();
    const pkgs = body.map((s) => s.target.package_name).sort();
    assert.deepEqual(pkgs, ["com.stash.app", "com.stash.app.debug"]);
    for (const s of body) assert.match(s.target.sha256_cert_fingerprints[0], /^([0-9A-F]{2}:){31}[0-9A-F]{2}$/);
});
```

- [ ] **Step 2: Run it and check it fails**

Run: `cd infra/share-worker && npm test`
Expected: FAIL. `/m/…`, `/t` and `/.well-known/…` return 404.

- [ ] **Step 3: Create `src/pages.js`.** The fingerprints are the real signing certificates. Release comes from the v0.9.108 APK, and debug comes from the committed `debug.keystore`; verify both with `apksigner verify --print-certs`.

```js
const GET_STASH = "https://github.com/rawnaldclark/Stash/releases/latest";
const RELEASE_SHA256 = "8E:12:46:95:BE:83:58:C5:44:50:D9:F4:A2:B9:39:EF:77:2C:24:19:2E:C6:1C:FB:6B:33:08:7B:AB:08:A8:BA";
const DEBUG_SHA256 = "80:0F:72:0A:31:6B:07:F6:45:38:23:71:FE:F4:1D:FA:B6:4F:39:EF:DA:D7:04:D6:A5:05:02:65:F1:6C:70:AA";

export const esc = (s) => String(s ?? "")
    .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;").replace(/'/g, "&#39;");

export function assetLinks() {
    const entry = (pkg, fp) => ({
        relation: ["delegate_permission/common.handle_all_urls"],
        target: { namespace: "android_app", package_name: pkg, sha256_cert_fingerprints: [fp] },
    });
    return [entry("com.stash.app", RELEASE_SHA256), entry("com.stash.app.debug", DEBUG_SHA256)];
}

/** An intent:// URL that opens Stash on Android, falling back to the https link in a browser. */
function openInStash(url) {
    const u = new URL(url);
    const fallback = encodeURIComponent(url);
    return `intent://${u.host}${u.pathname}${u.search}#Intent;scheme=https;package=com.stash.app;S.browser_fallback_url=${fallback};end`;
}

function shell({ title, description, image, body, pageUrl }) {
    return `<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(title)}</title>
<meta property="og:title" content="${esc(title)}">
<meta property="og:description" content="${esc(description)}">
${image ? `<meta property="og:image" content="${esc(image)}">` : ""}
<style>body{font-family:system-ui,sans-serif;background:#0d0d12;color:#eee;max-width:560px;margin:0 auto;padding:24px}
a.btn{display:inline-block;padding:12px 18px;border-radius:12px;background:#8b5cf6;color:#fff;text-decoration:none;margin:6px 8px 6px 0}
a.alt{background:#2a2a35}li{margin:6px 0;color:#ccc}.muted{color:#999}</style></head>
<body>${body}
<p><a class="btn" href="${esc(pageUrl ? openInStash(pageUrl) : GET_STASH)}">Open in Stash</a><a class="btn alt" href="${GET_STASH}">Get Stash</a></p>
</body></html>`;
}

export function mixPage(doc, pageUrl) {
    const items = doc.tracks.slice(0, 10).map((t) => `<li>${esc(t.t)} <span class="muted">· ${esc(t.a)}</span></li>`).join("");
    const more = doc.tracks.length > 10 ? `<p class="muted">…and ${doc.tracks.length - 10} more</p>` : "";
    const by = doc.sharedBy ? ` · shared by ${esc(doc.sharedBy)}` : "";
    return shell({
        title: doc.name,
        description: `${doc.tracks.length} tracks · shared on Stash`,
        image: doc.covers?.[0],
        pageUrl,
        body: `<h1>${esc(doc.name)}</h1><p class="muted">${doc.tracks.length} tracks${by}</p><ol>${items}</ol>${more}`,
    });
}

export function trackPage(params, pageUrl) {
    const t = params.get("t") || "Unknown song";
    const a = params.get("a") || "";
    return shell({ title: `${t} · ${a}`, description: "A song shared on Stash", pageUrl,
        body: `<h1>${esc(t)}</h1><p class="muted">${esc(a)}</p>` });
}

export function messagePage(title, text) {
    return shell({ title, description: text, body: `<h1>${esc(title)}</h1><p class="muted">${esc(text)}</p>` });
}
```

- [ ] **Step 4: Route the pages in `src/index.js`.** Add these before the final 404:

```js
import { assetLinks, messagePage, mixPage, trackPage } from "./pages.js";
```
```js
    if (method === "GET" && path === "/.well-known/assetlinks.json") return json(assetLinks(), 200, { "cache-control": "public, max-age=3600" });
    if (method === "GET" && path === "/t") return html(trackPage(url.searchParams, url.href));
    const page = /^\/m\/([A-Za-z0-9]{8})$/.exec(path);
    if (method === "GET" && page) {
        const record = await readMix(env.SHARE_KV, page[1]);
        if (record === null) return html(messagePage("Mix not found", "This link doesn't point to a mix."), 404);
        if (record.deleted) return html(messagePage("No longer shared", "This mix is no longer shared."), 410);
        return html(mixPage(record.doc, url.href));
    }
```
```js
const html = (body, status = 200) => new Response(body, { status, headers: { "content-type": "text/html; charset=utf-8" } });
```

- [ ] **Step 5: Run the tests and check they pass**

Run: `cd infra/share-worker && npm test`
Expected: `ℹ fail 0`.

- [ ] **Step 6: Commit**

```bash
git add infra/share-worker/src/pages.js infra/share-worker/src/index.js infra/share-worker/test/pages.test.js
git commit -m "feat(share): escaped preview pages with Open Graph tags, and assetlinks for App Links"
```

### Task 5: Worker README

**Files:**
- Create: `infra/share-worker/README.md`

- [ ] **Step 1: Write `README.md`**

````markdown
# stash-share

Stores shared Stash mixes and serves their links. See `docs/superpowers/specs/2026-09-23-shared-mixes-design.md`.

- KV `SHARE_KV`: `mix:<id>` → `{ doc, keyHash, deleted }`. Deleted mixes keep a `{deleted:true}` tombstone for 180 days.
- Rate limits: `CREATE_RL` (5/min per IP), `WRITE_RL` (30/min per IP).
- Routes: `POST /v1/mixes`, `PUT|DELETE|GET /v1/mixes/{id}`, `GET /v1/mixes/{id}/version`, `GET /m/{id}`, `GET /t`, `GET /.well-known/assetlinks.json`.

## Deploy

```bash
cd infra/share-worker
npx wrangler kv namespace create SHARE_KV   # paste the id into wrangler.toml
npm test
npx wrangler deploy
```

## Remove a mix by hand

```bash
npx wrangler kv key delete --binding SHARE_KV --remote "mix:<id>"
```

## Moving to a custom domain later

Add the domain as a Worker route or custom domain, add its host to `ShareConfig.HOSTS` in the app and to the manifest intent filter, and keep the old host working. `assetlinks.json` is served on every host automatically.
````

- [ ] **Step 2: Commit**

```bash
git add infra/share-worker/README.md
git commit -m "docs(share): stash-share Worker README"
```

---

# Part B: App core

### Task 6: `SharedTrack` descriptor and `Track` mapping (`core/model`)

**Files:**
- Create: `core/model/src/main/kotlin/com/stash/core/model/share/SharedTrack.kt`
- Test: `core/model/src/test/kotlin/com/stash/core/model/share/SharedTrackTest.kt`

- [ ] **Step 1: Add Truth to `core/model`'s tests**

`core/model` already has `testImplementation("junit:junit:4.13.2")`. Add Truth to its `dependencies { }` block, next to it:
```kotlin
testImplementation(libs.truth)
```
(Check the alias with `grep -n "truth" gradle/libs.versions.toml`.) `core/model` is an Android library, so its tests run with `testDebugUnitTest`. The plain `test` task doesn't accept `--tests`.

- [ ] **Step 2: Write the failing test**

```kotlin
package com.stash.core.model.share

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.MusicSource
import com.stash.core.model.Track
import org.junit.Test

class SharedTrackTest {
    private val full = Track(
        title = "Avril 14th", artist = "Aphex Twin", album = "Drukqs", durationMs = 125_000,
        isrc = "GBBPW0100025", spotifyUri = "spotify:track:5Y6nVaayzitvsD5F7nr3DV", youtubeId = "d1fQx6aXhkc",
        source = MusicSource.SPOTIFY,
    )

    @Test fun `a track maps to a descriptor with every known field`() {
        assertThat(full.toSharedTrack()).isEqualTo(
            SharedTrack("Avril 14th", "Aphex Twin", "Drukqs", 125_000, "GBBPW0100025", "5Y6nVaayzitvsD5F7nr3DV", "d1fQx6aXhkc"),
        )
    }

    @Test fun `blank and zero fields are omitted and an open-spotify url yields its id`() {
        val t = Track(title = "T", artist = "A", album = "", durationMs = 0, isrc = " ",
            spotifyUri = "https://open.spotify.com/track/abc123?si=x", youtubeId = "")
        assertThat(t.toSharedTrack()).isEqualTo(SharedTrack("T", "A", spotifyId = "abc123"))
    }

    @Test fun `a descriptor becomes a stream-only BOTH track`() {
        val t = SharedTrack("Avril 14th", "Aphex Twin", "Drukqs", 125_000, "GBBPW0100025", "5Y6nVaayzitvsD5F7nr3DV", "d1fQx6aXhkc").toTrack()
        assertThat(t.source).isEqualTo(MusicSource.BOTH)
        assertThat(t.spotifyUri).isEqualTo("spotify:track:5Y6nVaayzitvsD5F7nr3DV")
        assertThat(t.youtubeId).isEqualTo("d1fQx6aXhkc")
        assertThat(t.isrc).isEqualTo("GBBPW0100025")
        assertThat(t.album).isEqualTo("Drukqs")
        assertThat(t.durationMs).isEqualTo(125_000)
        assertThat(t.isStreamable).isTrue()
        assertThat(t.id).isEqualTo(0L)
    }
}
```

- [ ] **Step 3: Run it and check it fails**

Run: `./gradlew :core:model:testDebugUnitTest --tests '*SharedTrackTest' -q`
Expected: FAIL, unresolved reference `SharedTrack`.

- [ ] **Step 4: Implement `SharedTrack.kt`**

```kotlin
package com.stash.core.model.share

import com.stash.core.model.MusicSource
import com.stash.core.model.Track
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The portable description of a song that any Stash can turn into playable audio
 * (spec §2). Short JSON keys keep shared documents and `/t` links small. Everything
 * except title and artist is optional; ISRC is what lets lossless match the exact recording.
 */
@Serializable
data class SharedTrack(
    @SerialName("t") val title: String,
    @SerialName("a") val artist: String,
    @SerialName("al") val album: String? = null,
    @SerialName("d") val durationMs: Long? = null,
    @SerialName("isrc") val isrc: String? = null,
    @SerialName("sp") val spotifyId: String? = null,
    @SerialName("yt") val youtubeId: String? = null,
)

private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/** `spotify:track:ID` or `https://open.spotify.com/track/ID?…` → `ID`. */
fun spotifyTrackId(uri: String?): String? {
    val u = uri.clean() ?: return null
    return when {
        u.startsWith("spotify:track:") -> u.removePrefix("spotify:track:").clean()
        u.startsWith("https://open.spotify.com/track/") ->
            u.removePrefix("https://open.spotify.com/track/").substringBefore('?').substringBefore('/').clean()
        else -> null
    }
}

fun Track.toSharedTrack(): SharedTrack = SharedTrack(
    title = title,
    artist = artist,
    album = album.clean(),
    durationMs = durationMs.takeIf { it > 0 },
    isrc = isrc.clean(),
    spotifyId = spotifyTrackId(spotifyUri),
    youtubeId = youtubeId.clean(),
)

/**
 * A received descriptor as a new, stream-only library track. Always [MusicSource.BOTH]
 * (spec §2, owner decision): download reconciliation only queues tracks whose source is
 * connected, and BOTH is always connected, so a followed mix downloads for anyone.
 */
fun SharedTrack.toTrack(): Track = Track(
    title = title,
    artist = artist,
    album = album.orEmpty(),
    durationMs = durationMs ?: 0,
    isrc = isrc,
    spotifyUri = spotifyId?.let { "spotify:track:$it" },
    youtubeId = youtubeId,
    source = MusicSource.BOTH,
    isStreamable = true,
)
```

- [ ] **Step 5: Run the test and check it passes**

Run: `./gradlew :core:model:testDebugUnitTest --tests '*SharedTrackTest' -q`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add core/model/src/main/kotlin/com/stash/core/model/share/SharedTrack.kt core/model/src/test/kotlin/com/stash/core/model/share/SharedTrackTest.kt core/model/build.gradle.kts
git commit -m "feat(share): SharedTrack descriptor and Track mapping"
```

### Task 7: `ShareLinks`: build and parse (`core/model`)

**Files:**
- Create: `core/model/src/main/kotlin/com/stash/core/model/share/ShareLinks.kt`
- Test: `core/model/src/test/kotlin/com/stash/core/model/share/ShareLinksTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.stash.core.model.share

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShareLinksTest {
    private val base = ShareConfig.BASE_URL

    @Test fun `mix url and parse round-trip`() {
        assertThat(ShareLinks.mixUrl("Kx7Qa2pL")).isEqualTo("$base/m/Kx7Qa2pL")
        assertThat(ShareLinks.parse("$base/m/Kx7Qa2pL")).isEqualTo(ShareLinks.Parsed.Mix("Kx7Qa2pL"))
    }

    @Test fun `track url carries every field and parses back`() {
        val t = SharedTrack("Song & Dance", "Aphex Twin", "Drukqs", 125_000, "GBBPW0100025", "abc", "xyz")
        val url = ShareLinks.trackUrl(t)
        assertThat(url).startsWith("$base/t?t=Song+%26+Dance&a=Aphex+Twin")
        assertThat(ShareLinks.parse(url)).isEqualTo(ShareLinks.Parsed.Track(t))
    }

    @Test fun `legacy stash track links still parse`() {
        val legacy = "stash://track?t=Avril+14th&a=Aphex+Twin&s=https%3A%2F%2Fopen.spotify.com%2Ftrack%2Fabc&y=xyz"
        assertThat(ShareLinks.parse(legacy)).isEqualTo(
            ShareLinks.Parsed.Track(SharedTrack("Avril 14th", "Aphex Twin", spotifyId = "abc", youtubeId = "xyz")),
        )
    }

    @Test fun `foreign hosts, bad ids and missing fields are rejected`() {
        assertThat(ShareLinks.parse("https://evil.example/m/Kx7Qa2pL")).isNull()
        assertThat(ShareLinks.parse("$base/m/short")).isNull()
        assertThat(ShareLinks.parse("$base/t?a=OnlyArtist")).isNull()
        assertThat(ShareLinks.parse(null)).isNull()
        assertThat(ShareLinks.parse("not a url")).isNull()
    }
}
```

- [ ] **Step 2: Run it and check it fails**

Run: `./gradlew :core:model:testDebugUnitTest --tests '*ShareLinksTest' -q`
Expected: FAIL, unresolved reference `ShareLinks`.

- [ ] **Step 3: Implement `ShareLinks.kt`**

```kotlin
package com.stash.core.model.share

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Where share links live. One place to change when a custom domain is added (spec §1). */
object ShareConfig {
    const val BASE_URL = "https://stash-share.rawnaldclark.workers.dev"
    val HOSTS: Set<String> = setOf("stash-share.rawnaldclark.workers.dev")
}

object ShareLinks {
    sealed interface Parsed {
        data class Mix(val shareId: String) : Parsed
        data class Track(val track: SharedTrack) : Parsed
    }

    private val ID = Regex("^[A-Za-z0-9]{8}$")

    fun mixUrl(shareId: String): String = "${ShareConfig.BASE_URL}/m/$shareId"

    fun trackUrl(t: SharedTrack): String = buildString {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        append(ShareConfig.BASE_URL).append("/t?t=").append(enc(t.title)).append("&a=").append(enc(t.artist))
        t.album?.let { append("&al=").append(enc(it)) }
        t.durationMs?.let { append("&d=").append(it) }
        t.isrc?.let { append("&isrc=").append(enc(it)) }
        t.spotifyId?.let { append("&sp=").append(enc(it)) }
        t.youtubeId?.let { append("&yt=").append(enc(it)) }
    }

    /** A share link (https mix/track, or the legacy `stash://track`), or null when it isn't one. */
    fun parse(link: String?): Parsed? {
        val uri = runCatching { URI(link ?: return null) }.getOrNull() ?: return null
        val q = query(uri.rawQuery)
        return when {
            uri.scheme == "https" && uri.host in ShareConfig.HOSTS -> when {
                uri.path.startsWith("/m/") -> uri.path.removePrefix("/m/").takeIf { ID.matches(it) }?.let { Parsed.Mix(it) }
                uri.path == "/t" -> trackFrom(q["t"], q["a"], q["al"], q["d"], q["isrc"], q["sp"], q["yt"])
                else -> null
            }
            uri.scheme == "stash" && uri.host == "track" ->
                trackFrom(q["t"], q["a"], null, null, null, spotifyTrackId(q["s"]), q["y"])
            else -> null
        }
    }

    private fun trackFrom(t: String?, a: String?, al: String?, d: String?, isrc: String?, sp: String?, yt: String?): Parsed? {
        if (t.isNullOrBlank() || a.isNullOrBlank()) return null
        return Parsed.Track(
            SharedTrack(t, a, al?.ifBlank { null }, d?.toLongOrNull()?.takeIf { it > 0 },
                isrc?.ifBlank { null }, sp?.ifBlank { null }, yt?.ifBlank { null }),
        )
    }

    private fun query(raw: String?): Map<String, String> =
        raw.orEmpty().split('&').filter { '=' in it }.associate { part ->
            val (k, v) = part.split('=', limit = 2)
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }
}
```

- [ ] **Step 4: Run the test and check it passes**

Run: `./gradlew :core:model:testDebugUnitTest --tests '*ShareLinksTest' -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/model/src/main/kotlin/com/stash/core/model/share/ShareLinks.kt core/model/src/test/kotlin/com/stash/core/model/share/ShareLinksTest.kt
git commit -m "feat(share): ShareLinks — build and parse https mix/track links and legacy stash://track"
```

### Task 8: `shared_mixes` table, DAO, migration 47 → 48

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/db/entity/SharedMixEntity.kt`, `core/data/src/main/kotlin/com/stash/core/data/db/dao/SharedMixDao.kt`
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt` (entities list, DAO accessor, `version`, `MIGRATION_47_48`, `ALL_MIGRATIONS`), `core/data/src/main/kotlin/com/stash/core/data/di/DatabaseModule.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/db/MigrationV47V48Test.kt`, `core/data/src/test/kotlin/com/stash/core/data/db/dao/SharedMixDaoTest.kt`
- Generated, and committed: `core/data/schemas/com.stash.core.data.db.StashDatabase/48.json`

- [ ] **Step 1: Check the schema version** (ground rules). `grep -n "version = " core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt`. It must show `47`. If it doesn't, substitute the next number throughout this task.

- [ ] **Step 2: Create `SharedMixEntity.kt`**

```kotlin
package com.stash.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One shared mix link attached to a local playlist (spec §5). [role] is OWNER for a mix this
 * phone shares, FOLLOWER for one it follows. Roles and statuses are plain strings so no type
 * converter is needed.
 */
@Entity(
    tableName = "shared_mixes",
    foreignKeys = [ForeignKey(
        entity = PlaylistEntity::class,
        parentColumns = ["id"],
        childColumns = ["playlist_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["share_id"], unique = true)],
)
data class SharedMixEntity(
    @PrimaryKey @ColumnInfo(name = "playlist_id") val playlistId: Long,
    @ColumnInfo(name = "share_id") val shareId: String,
    @ColumnInfo(name = "role") val role: String,
    /** OWNER only: the secret that authorises updates. Kept in backups (spec §2). */
    @ColumnInfo(name = "edit_key") val editKey: String? = null,
    /** OWNER: the name the mix is shared under. FOLLOWER: the last name received. */
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "version") val version: Int = 0,
    @ColumnInfo(name = "content_hash") val contentHash: String = "",
    @ColumnInfo(name = "auto_update") val autoUpdate: Boolean = true,
    @ColumnInfo(name = "status") val status: String = STATUS_ACTIVE,
    @ColumnInfo(name = "shared_by") val sharedBy: String? = null,
    @ColumnInfo(name = "missing_count") val missingCount: Int = 0,
    /** FOLLOWER: the owner stopped sharing and the one-time notice hasn't been shown yet. */
    @ColumnInfo(name = "notice_pending") val noticePending: Boolean = false,
    @ColumnInfo(name = "last_checked_at") val lastCheckedAt: Long? = null,
) {
    companion object {
        const val ROLE_OWNER = "OWNER"
        const val ROLE_FOLLOWER = "FOLLOWER"
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_REMOVED = "REMOVED"
    }
}
```

- [ ] **Step 3: Create `SharedMixDao.kt`**

```kotlin
package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.stash.core.data.db.entity.SharedMixEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SharedMixDao {
    @Upsert suspend fun upsert(row: SharedMixEntity)

    @Query("SELECT * FROM shared_mixes WHERE playlist_id = :playlistId")
    suspend fun forPlaylist(playlistId: Long): SharedMixEntity?

    @Query("SELECT * FROM shared_mixes WHERE playlist_id = :playlistId")
    fun observeForPlaylist(playlistId: Long): Flow<SharedMixEntity?>

    @Query("SELECT * FROM shared_mixes WHERE share_id = :shareId")
    suspend fun byShareId(shareId: String): SharedMixEntity?

    @Query("SELECT * FROM shared_mixes WHERE role = 'OWNER' AND status = 'ACTIVE' AND auto_update = 1")
    suspend fun activeOwnedWithUpdates(): List<SharedMixEntity>

    @Query("SELECT * FROM shared_mixes WHERE role = 'FOLLOWER' AND status = 'ACTIVE'")
    suspend fun activeFollowed(): List<SharedMixEntity>

    @Query("DELETE FROM shared_mixes WHERE playlist_id = :playlistId")
    suspend fun delete(playlistId: Long)
}
```

- [ ] **Step 4: Register it in `StashDatabase.kt`**
  - Add `SharedMixEntity::class` to the `entities = [...]` list.
  - Change `version = 47` to `version = 48`.
  - Add `abstract fun sharedMixDao(): SharedMixDao` after the last DAO accessor.
  - Add `MIGRATION_47_48` after `MIGRATION_46_47`. Its SQL must match Room's generated schema; Step 7 checks that.

```kotlin
        /**
         * v47 -> v48: shared mixes (spec docs/superpowers/specs/2026-09-23-shared-mixes-design.md §5).
         * One row per playlist that this phone shares (OWNER) or follows (FOLLOWER). Additive.
         */
        val MIGRATION_47_48 = object : Migration(47, 48) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `shared_mixes` (
                        `playlist_id` INTEGER NOT NULL,
                        `share_id` TEXT NOT NULL,
                        `role` TEXT NOT NULL,
                        `edit_key` TEXT,
                        `name` TEXT NOT NULL,
                        `version` INTEGER NOT NULL,
                        `content_hash` TEXT NOT NULL,
                        `auto_update` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `shared_by` TEXT,
                        `missing_count` INTEGER NOT NULL,
                        `notice_pending` INTEGER NOT NULL,
                        `last_checked_at` INTEGER,
                        PRIMARY KEY(`playlist_id`),
                        FOREIGN KEY(`playlist_id`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_shared_mixes_share_id` ON `shared_mixes` (`share_id`)")
            }
        }
```
  - Add `MIGRATION_47_48,` as the last entry of `ALL_MIGRATIONS`. `ALL_MIGRATIONS` itself must stay the last member of the companion object.

- [ ] **Step 5: Provide the DAO in `DatabaseModule.kt`,** next to the other DAO providers:

```kotlin
    @Provides
    fun provideSharedMixDao(db: StashDatabase): com.stash.core.data.db.dao.SharedMixDao = db.sharedMixDao()
```

- [ ] **Step 6: Write the migration test, `MigrationV47V48Test.kt`**

```kotlin
package com.stash.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV47V48Test {
    private val dbName = "migration-v47v48-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), StashDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun `v47 to v48 adds shared_mixes and keeps playlists`() {
        helper.createDatabase(dbName, 47).use { db ->
            db.execSQL("INSERT INTO playlists (id, name, source, source_id, type, track_count, is_active) VALUES (1, 'Ambient', 'BOTH', 'custom_1', 'CUSTOM', 110, 1)")
        }
        val db = helper.runMigrationsAndValidate(dbName, 48, true, StashDatabase.MIGRATION_47_48)
        db.execSQL("INSERT INTO shared_mixes (playlist_id, share_id, role, name, version, content_hash, auto_update, status, missing_count, notice_pending) VALUES (1, 'Kx7Qa2pL', 'OWNER', 'Ambient', 1, 'h', 1, 'ACTIVE', 0, 0)")
        db.query("SELECT share_id FROM shared_mixes WHERE playlist_id = 1").use { c ->
            assertTrue(c.moveToNext()); assertEquals("Kx7Qa2pL", c.getString(0))
        }
    }
}
```

- [ ] **Step 7: Build to generate `48.json`, then run the migration test**

Run: `./gradlew :core:data:testDebugUnitTest --tests '*MigrationV47V48Test' --tests '*AllMigrationsChainTest' -q`
Expected: PASS. `core/data/schemas/com.stash.core.data.db.StashDatabase/48.json` now exists.
**If validation fails** ("Migration didn't properly handle"), open `48.json`, copy the `createSql` for `shared_mixes` exactly into `MIGRATION_47_48` (replacing `${TABLE_NAME}` with `shared_mixes`), and rerun.

- [ ] **Step 8: Write the DAO test, `SharedMixDaoTest.kt`** (the template is `PlaylistDaoPinnedToHomeTest`)

```kotlin
package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.SharedMixEntity
import com.stash.core.model.MusicSource
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SharedMixDaoTest {
    private lateinit var db: StashDatabase
    private lateinit var dao: SharedMixDao
    private lateinit var playlistDao: PlaylistDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.sharedMixDao(); playlistDao = db.playlistDao()
    }
    @After fun tearDown() { db.close() }

    private suspend fun playlist(src: String) = playlistDao.insert(PlaylistEntity(name = src, source = MusicSource.BOTH, sourceId = src))

    @Test fun `queries split owners and followers and deleting the playlist cascades`() = runTest {
        val owned = playlist("custom_a"); val followed = playlist("share:Kx7Qa2pL"); val paused = playlist("custom_b")
        dao.upsert(SharedMixEntity(owned, "AAAAAAAA", SharedMixEntity.ROLE_OWNER, editKey = "k", name = "A"))
        dao.upsert(SharedMixEntity(followed, "Kx7Qa2pL", SharedMixEntity.ROLE_FOLLOWER, name = "F"))
        dao.upsert(SharedMixEntity(paused, "BBBBBBBB", SharedMixEntity.ROLE_OWNER, editKey = "k", name = "B", autoUpdate = false))
        assertEquals(listOf(owned), dao.activeOwnedWithUpdates().map { it.playlistId })
        assertEquals(listOf(followed), dao.activeFollowed().map { it.playlistId })
        assertEquals(followed, dao.byShareId("Kx7Qa2pL")?.playlistId)
        playlistDao.deleteById(followed)
        assertNull(dao.forPlaylist(followed))
    }
}
```

Before running: `grep -n "fun deleteById\|fun delete(" core/data/src/main/kotlin/com/stash/core/data/db/dao/PlaylistDao.kt`. If there's no by-ID delete, use `db.openHelper.writableDatabase.execSQL("DELETE FROM playlists WHERE id = $followed")` instead.

- [ ] **Step 9: Run the test and check it passes**

Run: `./gradlew :core:data:testDebugUnitTest --tests '*SharedMixDaoTest' -q`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/db/entity/SharedMixEntity.kt core/data/src/main/kotlin/com/stash/core/data/db/dao/SharedMixDao.kt core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt core/data/src/main/kotlin/com/stash/core/data/di/DatabaseModule.kt core/data/schemas/com.stash.core.data.db.StashDatabase/48.json core/data/src/test/kotlin/com/stash/core/data/db/MigrationV47V48Test.kt core/data/src/test/kotlin/com/stash/core/data/db/dao/SharedMixDaoTest.kt
git commit -m "feat(share): shared_mixes table, DAO and migration 47→48"
```

### Task 9: Backfill ISRC and album on a matched track

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt`, `core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepositoryImpl.kt:438-492`
- Test: `core/data/src/test/kotlin/com/stash/core/data/repository/MusicRepositoryEnsureTrackPersistedTest.kt`

- [ ] **Step 1: Write the failing test.** Add it to `MusicRepositoryEnsureTrackPersistedTest`:

```kotlin
    @Test fun `a matched track gets the incoming isrc and album backfilled`() = runTest {
        val trackDao = mockk<TrackDao>(relaxed = true)
        coEvery { trackDao.findByYoutubeId("yt1") } returns
            TrackEntity(id = 7L, title = "Song", artist = "Artist", youtubeId = "yt1", source = MusicSource.YOUTUBE)
        val repo = buildRepo(trackDao)
        repo.ensureTrackPersisted(Track(title = "Song", artist = "Artist", youtubeId = "yt1", isrc = "USABC1234567", album = "LP"))
        coVerify { trackDao.backfillIsrcIfMissing(7L, "USABC1234567") }
        coVerify { trackDao.backfillAlbumIfMissing(7L, "LP") }
    }
```

- [ ] **Step 2: Run it and check it fails**

Run: `./gradlew :core:data:testDebugUnitTest --tests '*MusicRepositoryEnsureTrackPersistedTest' -q`
Expected: FAIL, unresolved `backfillIsrcIfMissing`.

- [ ] **Step 3: Add the queries to `TrackDao.kt`,** next to `backfillDurationIfMissing`:

```kotlin
    /** Shared-mix descriptors carry ISRC; a matched library row without one gains it (lossless matches by ISRC). */
    @Query("UPDATE tracks SET isrc = :isrc WHERE id = :trackId AND (isrc IS NULL OR isrc = '')")
    suspend fun backfillIsrcIfMissing(trackId: Long, isrc: String)

    @Query("UPDATE tracks SET album = :album WHERE id = :trackId AND (album IS NULL OR album = '')")
    suspend fun backfillAlbumIfMissing(trackId: Long, album: String)
```

- [ ] **Step 4: Call them in `MusicRepositoryImpl`.** Replace `backfillDurationIfBetter` and its four call sites with one helper that takes the incoming track:

```kotlin
    private suspend fun backfillFrom(existing: com.stash.core.data.db.entity.TrackEntity, incoming: Track) {
        if (existing.durationMs <= 0L && incoming.durationMs > 0L) trackDao.backfillDurationIfMissing(existing.id, incoming.durationMs)
        incoming.isrc?.takeIf { it.isNotBlank() }?.let { trackDao.backfillIsrcIfMissing(existing.id, it) }
        incoming.album.takeIf { it.isNotBlank() }?.let { trackDao.backfillAlbumIfMissing(existing.id, it) }
    }
```

At each match site, replace `backfillDurationIfBetter(existing.id, existing.durationMs, track.durationMs)` with `backfillFrom(existing, track)`. The first site (the `track.id > 0L` branch) already has `existing` in scope. Delete `backfillDurationIfBetter`.

- [ ] **Step 5: Run the tests and check they pass**

Run: `./gradlew :core:data:testDebugUnitTest --tests '*MusicRepositoryEnsureTrackPersistedTest' -q`
Expected: PASS, including the existing spotify_uri test.

- [ ] **Step 6: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepositoryImpl.kt core/data/src/test/kotlin/com/stash/core/data/repository/MusicRepositoryEnsureTrackPersistedTest.kt
git commit -m "feat(share): ensureTrackPersisted backfills isrc and album on a matched row"
```

### Task 10: Document model and `ShareApiClient`

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/share/SharedMixDocument.kt`, `core/data/src/main/kotlin/com/stash/core/data/share/ShareApiClient.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/share/ShareApiClientTest.kt`

- [ ] **Step 1: Create `SharedMixDocument.kt`**

```kotlin
package com.stash.core.data.share

import com.stash.core.model.share.SharedTrack
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Spec §3. id/version/updatedAt are set by the server; the app sends zeros. */
@Serializable
data class SharedMixDocument(
    val v: Int = 1,
    val id: String = "",
    val version: Int = 0,
    val updatedAt: Long = 0,
    val name: String,
    val sharedBy: String? = null,
    val covers: List<String> = emptyList(),
    val tracks: List<SharedTrack>,
) {
    /** Hash of what followers see, ignoring server-set fields: "did the mix change?". */
    fun contentHash(): String {
        val canonical = ShareJson.encodeToString(serializer(), copy(id = "", version = 0, updatedAt = 0))
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** Omits nulls (optional descriptor fields) and tolerates fields a newer server adds. */
val ShareJson: Json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
```

- [ ] **Step 2: Write the failing test, `ShareApiClientTest.kt`**

```kotlin
package com.stash.core.data.share

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.share.SharedTrack
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ShareApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ShareApiClient
    private val doc = SharedMixDocument(name = "Ambient", tracks = listOf(SharedTrack("T", "A", isrc = "X")))

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        client = ShareApiClient(OkHttpClient()).apply { baseUrl = server.url("/").toString().removeSuffix("/") }
    }
    @After fun tearDown() { server.shutdown() }

    @Test fun `create posts doc and key, returns id and version`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"Kx7Qa2pL","version":1,"url":"u"}"""))
        assertThat(client.create(doc, "KEY")).isEqualTo(ShareResult.Ok(ShareApiClient.Created("Kx7Qa2pL", 1)))
        val req = server.takeRequest()
        assertThat(req.path).isEqualTo("/v1/mixes")
        val body = req.body.readUtf8()
        assertThat(body).contains("\"editKey\":\"KEY\"")
        assertThat(body).contains("\"isrc\":\"X\"")
        assertThat(body).doesNotContain("\"sp\"") // nulls omitted
    }

    @Test fun `update sends the key header; 403 404 410 map to typed results`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"version":3}"""))
        assertThat(client.update("Kx7Qa2pL", doc, "KEY")).isEqualTo(ShareResult.Ok(3))
        assertThat(server.takeRequest().getHeader("X-Stash-Edit-Key")).isEqualTo("KEY")
        server.enqueue(MockResponse().setResponseCode(403))
        assertThat(client.update("Kx7Qa2pL", doc, "BAD")).isEqualTo(ShareResult.Forbidden)
        server.enqueue(MockResponse().setResponseCode(404))
        assertThat(client.version("Kx7Qa2pL")).isEqualTo(ShareResult.NotFound)
        server.enqueue(MockResponse().setResponseCode(410))
        assertThat(client.get("Kx7Qa2pL")).isEqualTo(ShareResult.Gone)
        server.enqueue(MockResponse().setResponseCode(400))
        assertThat(client.update("Kx7Qa2pL", doc, "KEY")).isEqualTo(ShareResult.Rejected(400))
        server.enqueue(MockResponse().setResponseCode(429))
        assertThat(client.update("Kx7Qa2pL", doc, "KEY")).isInstanceOf(ShareResult.Failed::class.java)
    }

    @Test fun `get parses the doc; transport failure is Failed`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"v":1,"id":"Kx7Qa2pL","version":2,"updatedAt":5,"name":"Ambient","tracks":[{"t":"T","a":"A"}],"extra":true}"""))
        val got = client.get("Kx7Qa2pL") as ShareResult.Ok
        assertThat(got.value.version).isEqualTo(2)
        assertThat(got.value.tracks.single()).isEqualTo(SharedTrack("T", "A"))
        server.shutdown()
        assertThat(client.version("Kx7Qa2pL")).isInstanceOf(ShareResult.Failed::class.java)
    }
}
```

- [ ] **Step 3: Run it and check it fails**

Run: `./gradlew :core:data:testDebugUnitTest --tests '*ShareApiClientTest' -q`
Expected: FAIL, unresolved `ShareApiClient`.

- [ ] **Step 4: Implement `ShareApiClient.kt`** (its pattern is `ListenBrainzApiClient`)

```kotlin
package com.stash.core.data.share

import com.stash.core.model.share.ShareConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

sealed interface ShareResult<out T> {
    data class Ok<T>(val value: T) : ShareResult<T>
    data object NotFound : ShareResult<Nothing>
    data object Gone : ShareResult<Nothing>
    data object Forbidden : ShareResult<Nothing>
    /** 400/413/other 4xx: the server will never accept this request as sent, so don't retry it. */
    data class Rejected(val code: Int) : ShareResult<Nothing>
    /** Network failure, 429 or 5xx: worth retrying later. */
    data class Failed(val message: String?) : ShareResult<Nothing>
}

/** HTTP client for the stash-share Worker (spec §4). */
@Singleton
class ShareApiClient @Inject constructor(private val okHttpClient: OkHttpClient) {
    /** Test seam; off the constructor because Hilt rejects @Inject with default params. */
    internal var baseUrl: String = ShareConfig.BASE_URL

    data class Created(val id: String, val version: Int)
    @Serializable private data class CreatedBody(val id: String, val version: Int)
    @Serializable private data class VersionBody(val version: Int)

    private fun docJson(doc: SharedMixDocument) = ShareJson.encodeToJsonElement(SharedMixDocument.serializer(), doc)

    suspend fun create(doc: SharedMixDocument, editKey: String): ShareResult<Created> {
        val body = buildJsonObject { put("doc", docJson(doc)); put("editKey", editKey) }
        return call(Request.Builder().url("$baseUrl/v1/mixes").post(body.toBody())) {
            ShareJson.decodeFromString(CreatedBody.serializer(), it).let { c -> Created(c.id, c.version) }
        }
    }

    suspend fun update(id: String, doc: SharedMixDocument, editKey: String): ShareResult<Int> {
        val body = buildJsonObject { put("doc", docJson(doc)) }
        return call(Request.Builder().url("$baseUrl/v1/mixes/$id").header(KEY_HEADER, editKey).put(body.toBody())) {
            ShareJson.decodeFromString(VersionBody.serializer(), it).version
        }
    }

    suspend fun delete(id: String, editKey: String): ShareResult<Unit> =
        call(Request.Builder().url("$baseUrl/v1/mixes/$id").header(KEY_HEADER, editKey).delete()) { }

    suspend fun get(id: String): ShareResult<SharedMixDocument> =
        call(Request.Builder().url("$baseUrl/v1/mixes/$id").get()) { ShareJson.decodeFromString(SharedMixDocument.serializer(), it) }

    suspend fun version(id: String): ShareResult<Int> =
        call(Request.Builder().url("$baseUrl/v1/mixes/$id/version").get()) { ShareJson.decodeFromString(VersionBody.serializer(), it).version }

    private suspend fun <T> call(builder: Request.Builder, parse: (String) -> T): ShareResult<T> = withContext(Dispatchers.IO) {
        runCatching {
            okHttpClient.newCall(builder.build()).execute().use { r ->
                when (r.code) {
                    in 200..299 -> ShareResult.Ok(parse(r.body?.string().orEmpty()))
                    403 -> ShareResult.Forbidden
                    404 -> ShareResult.NotFound
                    410 -> ShareResult.Gone
                    429 -> ShareResult.Failed("HTTP 429")
                    in 400..499 -> ShareResult.Rejected(r.code)
                    else -> ShareResult.Failed("HTTP ${r.code}")
                }
            }
        }.getOrElse { t ->
            if (t is kotlinx.coroutines.CancellationException) throw t
            ShareResult.Failed(t.message)
        }
    }

    private fun JsonObject.toBody() = toString().toRequestBody(JSON)

    private companion object {
        const val KEY_HEADER = "X-Stash-Edit-Key"
        val JSON = "application/json".toMediaType()
    }
}
```

- [ ] **Step 5: Run the test and check it passes**

Run: `./gradlew :core:data:testDebugUnitTest --tests '*ShareApiClientTest' -q`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/share/SharedMixDocument.kt core/data/src/main/kotlin/com/stash/core/data/share/ShareApiClient.kt core/data/src/test/kotlin/com/stash/core/data/share/ShareApiClientTest.kt
git commit -m "feat(share): SharedMixDocument and ShareApiClient for the stash-share Worker"
```

### Task 11: `SharedMixRepository`, owner side

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/share/SharedMixRepository.kt`
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/dao/PlaylistDao.kt` (add `setSyncEnabled`; it's used in Task 12 but added here so the class compiles in one go)
- Test: `core/data/src/test/kotlin/com/stash/core/data/share/SharedMixRepositoryOwnerTest.kt`

- [ ] **Step 1: Add `setSyncEnabled` to `PlaylistDao`**

```kotlin
    /** The followed mix's "Download this mix" switch (spec §6): sync_enabled only, no Home pin. */
    @Query("UPDATE playlists SET sync_enabled = :enabled WHERE id = :playlistId")
    suspend fun setSyncEnabled(playlistId: Long, enabled: Boolean)
```

- [ ] **Step 2: Write the failing test.** It uses a real in-memory Room, MockWebServer, and a relaxed `MusicRepository` mock.

```kotlin
package com.stash.core.data.share

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.SharedMixEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.repository.MusicRepository
import com.stash.core.model.MusicSource
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SharedMixRepositoryOwnerTest {
    private lateinit var db: StashDatabase
    private lateinit var server: MockWebServer
    private lateinit var repo: SharedMixRepository
    private var playlistId = 0L

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries().build()
        server = MockWebServer().also { it.start() }
        val api = ShareApiClient(OkHttpClient()).apply { baseUrl = server.url("/").toString().removeSuffix("/") }
        repo = SharedMixRepository(db.sharedMixDao(), db.playlistDao(), db.trackDao(), mockk<MusicRepository>(relaxed = true), api)
        playlistId = db.playlistDao().insert(PlaylistEntity(name = "Ambient", source = MusicSource.BOTH, sourceId = "custom_1"))
        val t1 = db.trackDao().insert(TrackEntity(title = "One", artist = "A", isrc = "I1", albumArtUrl = "https://img/1.jpg", source = MusicSource.SPOTIFY))
        val t2 = db.trackDao().insert(TrackEntity(title = "Two", artist = "B", source = MusicSource.YOUTUBE, youtubeId = "y2"))
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId, t1, position = 0))
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId, t2, position = 1))
    }
    @After fun tearDown() { db.close(); server.shutdown() }

    @Test fun `share creates the link and stores an OWNER row with key and hash`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"Kx7Qa2pL","version":1,"url":"u"}"""))
        val out = repo.share(playlistId, name = "Sleep", sharedBy = "Rawn", autoUpdate = true)
        assertThat(out).isEqualTo(ShareResult.Ok("https://stash-share.rawnaldclark.workers.dev/m/Kx7Qa2pL"))
        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("\"name\":\"Sleep\"")
        assertThat(body).contains("\"t\":\"One\"")
        assertThat(body).contains("\"covers\":[\"https://img/1.jpg\"]")
        val row = db.sharedMixDao().forPlaylist(playlistId)!!
        assertThat(row.role).isEqualTo(SharedMixEntity.ROLE_OWNER)
        assertThat(row.editKey).hasLength(43)
        assertThat(row.contentHash).isNotEmpty()
    }

    @Test fun `publish sends only when the content changed, and 410 marks REMOVED`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"Kx7Qa2pL","version":1,"url":"u"}"""))
        repo.share(playlistId, "Sleep", null, true); server.takeRequest()
        assertThat(repo.publishIfChanged(db.sharedMixDao().forPlaylist(playlistId)!!)).isEqualTo(PublishOutcome.Unchanged)
        val t3 = db.trackDao().insert(TrackEntity(title = "Three", artist = "C", source = MusicSource.BOTH))
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId, t3, position = 2))
        server.enqueue(MockResponse().setBody("""{"version":2}"""))
        assertThat(repo.publishIfChanged(db.sharedMixDao().forPlaylist(playlistId)!!)).isEqualTo(PublishOutcome.Published)
        assertThat(server.takeRequest().method).isEqualTo("PUT")
        assertThat(db.sharedMixDao().forPlaylist(playlistId)!!.version).isEqualTo(2)
        db.playlistDao().updateName(playlistId, "ignored") // only the shared name matters
        val t4 = db.trackDao().insert(TrackEntity(title = "Four", artist = "D", source = MusicSource.BOTH))
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId, t4, position = 3))
        server.enqueue(MockResponse().setResponseCode(410))
        assertThat(repo.publishIfChanged(db.sharedMixDao().forPlaylist(playlistId)!!)).isEqualTo(PublishOutcome.Removed)
        assertThat(db.sharedMixDao().forPlaylist(playlistId)!!.status).isEqualTo(SharedMixEntity.STATUS_REMOVED)
    }

    @Test fun `stop sharing deletes remotely and locally`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"Kx7Qa2pL","version":1,"url":"u"}"""))
        repo.share(playlistId, "Sleep", null, true); server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(204))
        repo.stopSharing(playlistId)
        assertThat(server.takeRequest().method).isEqualTo("DELETE")
        assertThat(db.sharedMixDao().forPlaylist(playlistId)).isNull()
    }
}
```

- [ ] **Step 3: Run it and check it fails**

Run: `./gradlew :core:data:testDebugUnitTest --tests '*SharedMixRepositoryOwnerTest' -q`
Expected: FAIL, unresolved `SharedMixRepository`.

- [ ] **Step 4: Implement the owner side of `SharedMixRepository.kt`**

```kotlin
package com.stash.core.data.share

import android.util.Base64
import android.util.Log
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.SharedMixDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.SharedMixEntity
import com.stash.core.data.mapper.toDomain
import com.stash.core.data.repository.MusicRepository
import com.stash.core.model.share.ShareLinks
import com.stash.core.model.share.SharedTrack
import com.stash.core.model.share.toSharedTrack
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** Only [Failed] is retried by the publish worker. */
enum class PublishOutcome { Unchanged, Published, Removed, Forbidden, Rejected, Failed }

/** Every share and follow operation (spec §5-6). */
@Singleton
class SharedMixRepository @Inject constructor(
    private val sharedMixDao: SharedMixDao,
    private val playlistDao: PlaylistDao,
    private val trackDao: TrackDao,
    private val musicRepository: MusicRepository,
    private val api: ShareApiClient,
) {
    fun observe(playlistId: Long): Flow<SharedMixEntity?> = sharedMixDao.observeForPlaylist(playlistId)
    suspend fun forPlaylist(playlistId: Long): SharedMixEntity? = sharedMixDao.forPlaylist(playlistId)

    // ── Owner ──────────────────────────────────────────────────────────────────────────────

    /**
     * Build the document from the playlist's live members, in order (removed ones excluded).
     * Every field is clipped to the Worker's limits (worker src/validate.js) so one odd library
     * row can never make the whole mix unpublishable.
     */
    suspend fun buildDocument(playlistId: Long, name: String, sharedBy: String?): SharedMixDocument {
        val tracks = playlistDao.getTracksForPlaylist(playlistId).map { it.toDomain() }
            .filter { it.title.isNotBlank() && it.artist.isNotBlank() }
        return SharedMixDocument(
            name = name.trim().take(100),
            sharedBy = sharedBy?.trim()?.take(40)?.ifBlank { null },
            covers = tracks.mapNotNull { it.albumArtUrl?.takeIf { u -> u.startsWith("https://") && u.length <= 1000 } }.distinct().take(4),
            tracks = tracks.take(MAX_TRACKS).map { it.toSharedTrack().withinLimits() },
        )
    }

    private fun SharedTrack.withinLimits() = copy(
        title = title.take(500),
        artist = artist.take(500),
        album = album?.take(500),
        isrc = isrc?.takeIf { it.length <= 20 },
        spotifyId = spotifyId?.takeIf { it.length <= 40 },
        youtubeId = youtubeId?.takeIf { it.length <= 20 },
    )

    /** Create a link for [playlistId]; returns the https URL. */
    suspend fun share(playlistId: Long, name: String, sharedBy: String?, autoUpdate: Boolean): ShareResult<String> {
        val doc = buildDocument(playlistId, name, sharedBy)
        if (doc.tracks.isEmpty()) return ShareResult.Failed("This playlist has no songs to share.")
        val key = newEditKey()
        return when (val r = api.create(doc, key)) {
            is ShareResult.Ok -> {
                sharedMixDao.upsert(
                    SharedMixEntity(
                        playlistId = playlistId, shareId = r.value.id, role = SharedMixEntity.ROLE_OWNER,
                        editKey = key, name = doc.name, version = r.value.version, contentHash = doc.contentHash(),
                        autoUpdate = autoUpdate, sharedBy = doc.sharedBy,
                    ),
                )
                ShareResult.Ok(ShareLinks.mixUrl(r.value.id))
            }
            is ShareResult.Failed -> r
            else -> ShareResult.Failed("Couldn't create the link.")
        }
    }

    /** Send a new version only if what followers would see changed. */
    suspend fun publishIfChanged(row: SharedMixEntity): PublishOutcome {
        val key = row.editKey ?: return PublishOutcome.Forbidden
        val doc = buildDocument(row.playlistId, row.name, row.sharedBy)
        if (doc.tracks.isEmpty()) return PublishOutcome.Unchanged // an emptied playlist isn't published
        val hash = doc.contentHash()
        if (hash == row.contentHash) return PublishOutcome.Unchanged
        return when (val r = api.update(row.shareId, doc, key)) {
            is ShareResult.Ok -> { sharedMixDao.upsert(row.copy(version = r.value, contentHash = hash)); PublishOutcome.Published }
            ShareResult.Gone, ShareResult.NotFound -> { sharedMixDao.upsert(row.copy(status = SharedMixEntity.STATUS_REMOVED)); PublishOutcome.Removed }
            ShareResult.Forbidden -> { Log.w(TAG, "edit key rejected for ${row.shareId}"); PublishOutcome.Forbidden }
            is ShareResult.Rejected -> { Log.w(TAG, "server rejected ${row.shareId}: HTTP ${r.code}"); PublishOutcome.Rejected }
            is ShareResult.Failed -> PublishOutcome.Failed
        }
    }

    suspend fun setAutoUpdate(playlistId: Long, on: Boolean) {
        sharedMixDao.forPlaylist(playlistId)?.let { sharedMixDao.upsert(it.copy(autoUpdate = on)) }
    }

    /** Remove the link. A remote 404/410 still clears the local row. */
    suspend fun stopSharing(playlistId: Long): Boolean {
        val row = sharedMixDao.forPlaylist(playlistId) ?: return true
        val r = api.delete(row.shareId, row.editKey.orEmpty())
        if (r is ShareResult.Failed) return false
        sharedMixDao.delete(playlistId)
        return true
    }

    private fun newEditKey(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    companion object {
        private const val TAG = "SharedMix"
        const val MAX_TRACKS = 2000
    }
}
```

- [ ] **Step 5: Run the test and check it passes**

Run: `./gradlew :core:data:testDebugUnitTest --tests '*SharedMixRepositoryOwnerTest' -q`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/share/SharedMixRepository.kt core/data/src/main/kotlin/com/stash/core/data/db/dao/PlaylistDao.kt core/data/src/test/kotlin/com/stash/core/data/share/SharedMixRepositoryOwnerTest.kt
git commit -m "feat(share): SharedMixRepository owner side — share, publish only on change, stop"
```

### Task 12: `SharedMixRepository`, follower side

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/share/SharedMixRepository.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/share/SharedMixRepositoryFollowerTest.kt`

- [ ] **Step 1: Write the failing test.** `MusicRepository.ensureTrackPersisted` is faked to insert straight into the DB, so the dedupe and backfill paths are covered by Task 9 rather than here.

```kotlin
package com.stash.core.data.share

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.SharedMixEntity
import com.stash.core.data.mapper.toEntity
import com.stash.core.data.repository.MusicRepository
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.core.model.Track
import com.stash.core.model.share.SharedTrack
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SharedMixRepositoryFollowerTest {
    private lateinit var db: StashDatabase
    private lateinit var server: MockWebServer
    private lateinit var music: MusicRepository
    private lateinit var repo: SharedMixRepository

    private fun doc(version: Int, vararg titles: String, name: String = "Ambient") = SharedMixDocument(
        id = "Kx7Qa2pL", version = version, name = name, sharedBy = "Rawn",
        tracks = titles.map { SharedTrack(it, "Artist") },
    )
    private fun docJson(d: SharedMixDocument) = ShareJson.encodeToString(SharedMixDocument.serializer(), d)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries().build()
        server = MockWebServer().also { it.start() }
        music = mockk(relaxed = true)
        coEvery { music.ensureTrackPersisted(any()) } coAnswers {
            val t = firstArg<Track>()
            db.trackDao().findByCanonicalIdentity(t.title.lowercase(), t.artist.lowercase())?.id
                ?: db.trackDao().insert(t.toEntity().copy(canonicalTitle = t.title.lowercase(), canonicalArtist = t.artist.lowercase()))
        }
        // Real row, as MusicRepositoryImpl.createPlaylist makes it (a relaxed mock would return 0 → FK failure).
        coEvery { music.createPlaylist(any()) } coAnswers {
            db.playlistDao().insert(
                PlaylistEntity(name = firstArg(), source = MusicSource.BOTH, sourceId = "custom_${System.nanoTime()}",
                    type = PlaylistType.CUSTOM, syncEnabled = true),
            )
        }
        val api = ShareApiClient(OkHttpClient()).apply { baseUrl = server.url("/").toString().removeSuffix("/") }
        repo = SharedMixRepository(db.sharedMixDao(), db.playlistDao(), db.trackDao(), music, api)
    }
    @After fun tearDown() { db.close(); server.shutdown() }

    private suspend fun titles(playlistId: Long) = db.playlistDao().getTracksForPlaylist(playlistId).map { it.title }

    @Test fun `follow creates a read-only BOTH playlist with download off, in order`() = runBlocking {
        val id = repo.follow(doc(1, "One", "Two"))
        val p = db.playlistDao().getById(id)!!
        assertThat(p.source).isEqualTo(MusicSource.BOTH)
        assertThat(p.type).isEqualTo(PlaylistType.CUSTOM)
        assertThat(p.sourceId).isEqualTo("share:Kx7Qa2pL")
        assertThat(p.syncEnabled).isFalse()
        assertThat(titles(id)).containsExactly("One", "Two").inOrder()
        assertThat(db.trackDao().getById(db.playlistDao().getTracksForPlaylist(id)[0].id)!!.source).isEqualTo(MusicSource.BOTH)
        assertThat(repo.follow(doc(1, "One", "Two"))).isEqualTo(id) // following twice is a no-op
    }

    @Test fun `a newer version is applied: adds, removes, reorders, renames`() = runBlocking {
        val id = repo.follow(doc(1, "One", "Two", "Three"))
        server.enqueue(MockResponse().setBody("""{"version":2}"""))
        server.enqueue(MockResponse().setBody(docJson(doc(2, "Three", "One", "Four", name = "Sleep"))))
        assertThat(repo.checkForUpdate(db.sharedMixDao().forPlaylist(id)!!, now = 1000L)).isEqualTo(FollowCheck.Updated)
        assertThat(titles(id)).containsExactly("Three", "One", "Four").inOrder()
        assertThat(db.playlistDao().getById(id)!!.name).isEqualTo("Sleep")
        assertThat(db.sharedMixDao().forPlaylist(id)!!.version).isEqualTo(2)
        server.enqueue(MockResponse().setBody("""{"version":2}"""))
        assertThat(repo.checkForUpdate(db.sharedMixDao().forPlaylist(id)!!, now = 2000L)).isEqualTo(FollowCheck.UpToDate)
    }

    @Test fun `with download on, an update queues the new tracks`() = runBlocking {
        val id = repo.follow(doc(1, "One"))
        repo.setDownload(id, true)
        server.enqueue(MockResponse().setBody("""{"version":2}"""))
        server.enqueue(MockResponse().setBody(docJson(doc(2, "One", "Two"))))
        repo.checkForUpdate(db.sharedMixDao().forPlaylist(id)!!, now = 1L)
        coVerify(atLeast = 2) { music.queueDownloadsForPlaylist(id) } // once on enable, once after the update
    }

    @Test fun `410 converts to an ordinary playlist; 404 converts only on the second in a row`() = runBlocking {
        val id = repo.follow(doc(1, "One"))
        server.enqueue(MockResponse().setResponseCode(404))
        assertThat(repo.checkForUpdate(db.sharedMixDao().forPlaylist(id)!!, now = 1L)).isEqualTo(FollowCheck.Unreachable)
        assertThat(db.sharedMixDao().forPlaylist(id)!!.status).isEqualTo(SharedMixEntity.STATUS_ACTIVE)
        server.enqueue(MockResponse().setResponseCode(404))
        assertThat(repo.checkForUpdate(db.sharedMixDao().forPlaylist(id)!!, now = 2L)).isEqualTo(FollowCheck.Removed)
        val row = db.sharedMixDao().forPlaylist(id)!!
        assertThat(row.status).isEqualTo(SharedMixEntity.STATUS_REMOVED)
        assertThat(row.noticePending).isTrue()
        assertThat(repo.consumeRemovedNotice(id)).isEqualTo("Rawn stopped sharing this mix. You keep your copy.")
        assertThat(repo.consumeRemovedNotice(id)).isNull()
    }

    @Test fun `save a copy is an ordinary editable playlist with no shared row`() = runBlocking {
        val id = repo.saveCopy(doc(1, "One", "Two"))
        val p = db.playlistDao().getById(id)!!
        assertThat(p.sourceId).startsWith("custom_")
        assertThat(p.syncEnabled).isTrue()
        assertThat(titles(id)).containsExactly("One", "Two").inOrder()
        assertThat(db.sharedMixDao().forPlaylist(id)).isNull()
    }
}
```

- [ ] **Step 2: Run it and check it fails**

Run: `./gradlew :core:data:testDebugUnitTest --tests '*SharedMixRepositoryFollowerTest' -q`
Expected: FAIL, unresolved `follow`, `checkForUpdate` and friends.

- [ ] **Step 3: Add the follower side to `SharedMixRepository`.** Add these imports:

```kotlin
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.core.model.Track
import com.stash.core.model.share.toTrack
import java.time.Instant
```

Add this type above the class:

```kotlin
enum class FollowCheck { UpToDate, Updated, Removed, Unreachable }
```

Add inside the class:

```kotlin
    // ── Follower ───────────────────────────────────────────────────────────────────────────

    suspend fun fetch(shareId: String): ShareResult<SharedMixDocument> = api.get(shareId)
    suspend fun byShareId(shareId: String): SharedMixEntity? = sharedMixDao.byShareId(shareId)

    /** Persist every descriptor (deduped against the library), keeping document order and dropping repeats. */
    suspend fun persistTracks(doc: SharedMixDocument): List<Long> =
        doc.tracks.map { musicRepository.ensureTrackPersisted(it.toTrack()) }.distinct()

    suspend fun tracksFor(doc: SharedMixDocument): List<Track> =
        persistTracks(doc).mapNotNull { trackDao.getById(it)?.toDomain() }

    /** Spec §6 Follow: a CUSTOM/BOTH playlist, source_id share:<id>, download off. Idempotent per share id. */
    suspend fun follow(doc: SharedMixDocument): Long {
        sharedMixDao.byShareId(doc.id)?.let { return it.playlistId }
        val ids = persistTracks(doc)
        val playlistId = playlistDao.insert(
            PlaylistEntity(
                name = doc.name, source = MusicSource.BOTH, sourceId = "share:${doc.id}",
                type = PlaylistType.CUSTOM, isActive = true, syncEnabled = false,
            ),
        )
        playlistDao.replaceMixMembership(playlistId, ids, doc.name, Instant.now())
        sharedMixDao.upsert(
            SharedMixEntity(
                playlistId = playlistId, shareId = doc.id, role = SharedMixEntity.ROLE_FOLLOWER,
                name = doc.name, version = doc.version, sharedBy = doc.sharedBy, lastCheckedAt = System.currentTimeMillis(),
            ),
        )
        return playlistId
    }

    /** Spec §6 Save a copy: an ordinary editable playlist, no link back. */
    suspend fun saveCopy(doc: SharedMixDocument): Long {
        val ids = persistTracks(doc)
        val playlistId = musicRepository.createPlaylist(doc.name)
        playlistDao.replaceMixMembership(playlistId, ids, doc.name, Instant.now())
        return playlistId
    }

    /** Remove a followed mix. Tracks stay only if another playlist or a like claims them (existing orphan rules). */
    suspend fun unfollow(playlistId: Long) {
        val playlist = playlistDao.getById(playlistId)?.toDomain() ?: return
        sharedMixDao.delete(playlistId)
        musicRepository.removePlaylist(playlist)
    }

    /** "Download this mix" is the playlist's sync_enabled (spec §6); enabling also starts downloading now. */
    suspend fun setDownload(playlistId: Long, on: Boolean) {
        playlistDao.setSyncEnabled(playlistId, on)
        if (on) musicRepository.queueDownloadsForPlaylist(playlistId)
    }

    suspend fun checkForUpdate(row: SharedMixEntity, now: Long): FollowCheck {
        return when (val v = api.version(row.shareId)) {
            is ShareResult.Ok -> {
                if (v.value <= row.version) {
                    sharedMixDao.upsert(row.copy(missingCount = 0, lastCheckedAt = now)); FollowCheck.UpToDate
                } else when (val d = api.get(row.shareId)) {
                    is ShareResult.Ok -> { apply(row, d.value, now); FollowCheck.Updated }
                    ShareResult.Gone -> removed(row)
                    else -> FollowCheck.Unreachable
                }
            }
            ShareResult.Gone -> removed(row)
            ShareResult.NotFound -> if (row.missingCount + 1 >= 2) removed(row) else {
                sharedMixDao.upsert(row.copy(missingCount = row.missingCount + 1, lastCheckedAt = now)); FollowCheck.Unreachable
            }
            else -> FollowCheck.Unreachable
        }
    }

    private suspend fun apply(row: SharedMixEntity, doc: SharedMixDocument, now: Long) {
        val ids = persistTracks(doc)
        playlistDao.replaceMixMembership(row.playlistId, ids, doc.name, Instant.ofEpochMilli(now))
        sharedMixDao.upsert(row.copy(name = doc.name, version = doc.version, sharedBy = doc.sharedBy, missingCount = 0, lastCheckedAt = now))
        if (playlistDao.getById(row.playlistId)?.syncEnabled == true) musicRepository.queueDownloadsForPlaylist(row.playlistId)
    }

    private suspend fun removed(row: SharedMixEntity): FollowCheck {
        sharedMixDao.upsert(row.copy(status = SharedMixEntity.STATUS_REMOVED, noticePending = true))
        return FollowCheck.Removed
    }

    /** The one-time "stopped sharing" message (spec §6), or null. Clears the flag. */
    suspend fun consumeRemovedNotice(playlistId: Long): String? {
        val row = sharedMixDao.forPlaylist(playlistId)?.takeIf { it.noticePending } ?: return null
        sharedMixDao.upsert(row.copy(noticePending = false))
        return "${row.sharedBy ?: "The owner"} stopped sharing this mix. You keep your copy."
    }
```

Check that `musicRepository.removePlaylist(playlist: Playlist)` deletes the playlist row (read its implementation). If it only hides the playlist, call `playlistDao`'s delete-by-ID instead.

- [ ] **Step 4: Run the test and check it passes**

Run: `./gradlew :core:data:testDebugUnitTest --tests '*SharedMixRepositoryFollowerTest' -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/share/SharedMixRepository.kt core/data/src/test/kotlin/com/stash/core/data/share/SharedMixRepositoryFollowerTest.kt
git commit -m "feat(share): follower side — follow, save a copy, apply updates, 404/410 handling, download switch"
```

### Task 13: Background jobs and their triggers

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/share/SharedMixPublishWorker.kt`, `core/data/src/main/kotlin/com/stash/core/data/share/SharedMixFollowWorker.kt`
- Modify: `core/data/src/main/kotlin/com/stash/core/data/sync/workers/SyncFinalizeWorker.kt` (after `ArtistImageBackfillWorker.enqueueAfterSync`), `app/src/main/kotlin/com/stash/app/StashApplication.kt` (`onCreate`), `core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepositoryImpl.kt` (after `addTrackToPlaylist` and `removeTrackFromPlaylist`)
- Test: `core/data/src/test/kotlin/com/stash/core/data/share/SharedMixFollowWorkerTest.kt`

- [ ] **Step 1: Create `SharedMixPublishWorker.kt`** (its template is `ArtistImageBackfillWorker`)

```kotlin
package com.stash.core.data.share

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.stash.core.data.db.dao.SharedMixDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/** Republishes owned shared mixes whose content changed (spec §5). Quiet: failures retry with backoff. */
@HiltWorker
class SharedMixPublishWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val sharedMixDao: SharedMixDao,
    private val repository: SharedMixRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        var retry = false
        for (row in sharedMixDao.activeOwnedWithUpdates()) {
            if (isStopped) break
            // Only transient failures retry; Rejected/Forbidden would fail identically forever.
            if (repository.publishIfChanged(row) == PublishOutcome.Failed) retry = true
        }
        return if (retry) Result.retry() else Result.success()
    }

    companion object {
        private const val WORK_NAME = "stash_shared_mix_publish"

        /** After a sync ([delaySeconds] 0) or ~30 s after a local edit, so a burst of edits becomes one publish. */
        fun enqueue(context: Context, delaySeconds: Long = 0) {
            val work = OneTimeWorkRequestBuilder<SharedMixPublishWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, work)
        }
    }
}
```

- [ ] **Step 2: Write the failing test for the follow worker's 6-hour gate.** Test the gate as a pure function:

```kotlin
package com.stash.core.data.share

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.entity.SharedMixEntity
import org.junit.Test

class SharedMixFollowWorkerTest {
    private fun row(checked: Long?) = SharedMixEntity(1, "Kx7Qa2pL", SharedMixEntity.ROLE_FOLLOWER, name = "A", lastCheckedAt = checked)
    private val sixHours = 6 * 3600_000L

    @Test fun `forced checks always run; otherwise at most every 6 hours`() {
        val now = 10 * sixHours
        assertThat(SharedMixFollowWorker.isDue(row(now - 1000), now, force = true)).isTrue()
        assertThat(SharedMixFollowWorker.isDue(row(now - 1000), now, force = false)).isFalse()
        assertThat(SharedMixFollowWorker.isDue(row(now - sixHours), now, force = false)).isTrue()
        assertThat(SharedMixFollowWorker.isDue(row(null), now, force = false)).isTrue()
    }
}
```

- [ ] **Step 3: Run it and check it fails**

Run: `./gradlew :core:data:testDebugUnitTest --tests '*SharedMixFollowWorkerTest' -q`
Expected: FAIL, unresolved `SharedMixFollowWorker`.

- [ ] **Step 4: Create `SharedMixFollowWorker.kt`**

```kotlin
package com.stash.core.data.share

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.stash.core.data.db.dao.SharedMixDao
import com.stash.core.data.db.entity.SharedMixEntity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Checks followed mixes for new versions (spec §6): after each sync (forced), and on app start at most every 6 h. */
@HiltWorker
class SharedMixFollowWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val sharedMixDao: SharedMixDao,
    private val repository: SharedMixRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val force = inputData.getBoolean(KEY_FORCE, false)
        val now = System.currentTimeMillis()
        for (row in sharedMixDao.activeFollowed()) {
            if (isStopped) break
            if (isDue(row, now, force)) repository.checkForUpdate(row, now)
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "stash_shared_mix_follow"
        private const val KEY_FORCE = "force"
        private const val INTERVAL_MS = 6 * 3600_000L

        fun isDue(row: SharedMixEntity, now: Long, force: Boolean): Boolean =
            force || row.lastCheckedAt == null || now - row.lastCheckedAt >= INTERVAL_MS

        fun enqueue(context: Context, force: Boolean) {
            val work = OneTimeWorkRequestBuilder<SharedMixFollowWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(KEY_FORCE to force))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME, if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, work,
            )
        }
    }
}
```

- [ ] **Step 5: Run the test and check it passes**

Run: `./gradlew :core:data:testDebugUnitTest --tests '*SharedMixFollowWorkerTest' -q`
Expected: PASS.

- [ ] **Step 6: Add the triggers**
  - `SyncFinalizeWorker.kt`, directly after `ArtistImageBackfillWorker.enqueueAfterSync(applicationContext)`:
    ```kotlin
            // Shared mixes (spec §5-6): republish what this phone shares, and pull what it follows.
            com.stash.core.data.share.SharedMixPublishWorker.enqueue(applicationContext)
            com.stash.core.data.share.SharedMixFollowWorker.enqueue(applicationContext, force = true)
    ```
  - `StashApplication.onCreate()`, next to the other one-shot enqueues:
    ```kotlin
        // Followed shared mixes: check on start, at most every 6 h (the worker gates per mix).
        com.stash.core.data.share.SharedMixFollowWorker.enqueue(this, force = false)
    ```
  - `MusicRepositoryImpl`:
    - Add `private val sharedMixDao: com.stash.core.data.db.dao.SharedMixDao` as the **last** constructor parameter.
    - At the end of `addTrackToPlaylist` (after `updateTrackCount`), of `removeTrackFromPlaylist`, **and of `removeTrackFromPlaylistAndMaybeDelete`** (around line 769; this is the path the playlist screen actually uses, from `PlaylistDetailViewModel` lines ~299 and ~420), add:
    ```kotlin
        if (sharedMixDao.forPlaylist(playlistId)?.role == com.stash.core.data.db.entity.SharedMixEntity.ROLE_OWNER) {
            com.stash.core.data.share.SharedMixPublishWorker.enqueue(context, delaySeconds = 30)
        }
    ```
    - Then update **every** test that constructs `MusicRepositoryImpl(`: `grep -rln "MusicRepositoryImpl(" core/data/src/test`. Add `sharedMixDao = mockk(relaxed = true),` to each.

- [ ] **Step 7: Run the `core:data` suite and the app build**

Run: `./gradlew :core:data:testDebugUnitTest -q` then `./gradlew :app:assembleDebug -q`
Expected: both succeed; the only permitted failure is the known flaky `DatabaseBackupMergeTest`, which passes on a rerun.

- [ ] **Step 8: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/share/SharedMixPublishWorker.kt core/data/src/main/kotlin/com/stash/core/data/share/SharedMixFollowWorker.kt core/data/src/test/kotlin/com/stash/core/data/share/SharedMixFollowWorkerTest.kt core/data/src/main/kotlin/com/stash/core/data/sync/workers/SyncFinalizeWorker.kt app/src/main/kotlin/com/stash/app/StashApplication.kt core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepositoryImpl.kt
git add $(git diff --name-only -- core/data/src/test)   # the MusicRepositoryImpl( constructor updates
git diff --cached --name-only
git commit -m "feat(share): publish and follow workers, triggered after sync, on start and after edits"
```

### Task 14: Followed mixes: out of the pickers, visible in Library

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/dao/PlaylistDao.kt` (`getPickablePlaylists` around line 813, `getUserCreatedPlaylists` around line 792, `getAllVisible` around line 342)
- Test: `core/data/src/test/kotlin/com/stash/core/data/db/dao/PlaylistDaoFollowedPickerTest.kt`

- [ ] **Step 1: Write the failing test** (the setup is the same as `SharedMixDaoTest`)

```kotlin
package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.SharedMixEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PlaylistDaoFollowedPickerTest {
    private lateinit var db: StashDatabase
    @Before fun setUp() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java).allowMainThreadQueries().build() }
    @After fun tearDown() { db.close() }

    @Test fun `an active follow is not pickable; a converted one is`() = runTest {
        val dao = db.playlistDao()
        val mine = dao.insert(PlaylistEntity(name = "Mine", source = MusicSource.BOTH, sourceId = "custom_1", type = PlaylistType.CUSTOM, syncEnabled = true))
        val followed = dao.insert(PlaylistEntity(name = "Followed", source = MusicSource.BOTH, sourceId = "share:AAAAAAAA", type = PlaylistType.CUSTOM))
        db.sharedMixDao().upsert(SharedMixEntity(followed, "AAAAAAAA", SharedMixEntity.ROLE_FOLLOWER, name = "Followed"))
        assertThat(dao.getPickablePlaylists().first().map { it.id }).containsExactly(mine)
        assertThat(dao.getUserCreatedPlaylists().first().map { it.id }).containsExactly(mine)
        // Followed with Download off and nothing downloaded: still in Library (the user asked for it).
        assertThat(dao.getAllVisible(includeStreamable = false).first().map { it.id }).contains(followed)
        db.sharedMixDao().upsert(SharedMixEntity(followed, "AAAAAAAA", SharedMixEntity.ROLE_FOLLOWER, name = "Followed", status = SharedMixEntity.STATUS_REMOVED))
        assertThat(dao.getPickablePlaylists().first().map { it.id }).containsExactly(mine, followed)
    }
}
```

- [ ] **Step 2: Run it and check it fails**

Run: `./gradlew :core:data:testDebugUnitTest --tests '*PlaylistDaoFollowedPickerTest' -q`
Expected: FAIL. `followed` is listed.

- [ ] **Step 3: Exclude active follows in both queries.** Add this predicate to the `WHERE` of `getUserCreatedPlaylists` and of `getPickablePlaylists`:

```sql
  AND id NOT IN (SELECT playlist_id FROM shared_mixes WHERE role = 'FOLLOWER' AND status = 'ACTIVE')
```

In `getPickablePlaylists` it goes right after `AND type IN ('CUSTOM')`. In `getUserCreatedPlaylists` it goes after `is_active = 1`.

Also make followed mixes visible in Library in download-only mode. `getAllVisible` hides a playlist with `sync_enabled = 0` and no downloaded tracks, which is exactly a freshly followed mix. Add one arm next to the `pinned_to_home_at` arm:

```sql
              -- Followed shared mix (spec §6): the user chose it, so it shows even with Download off.
              OR p.source_id LIKE 'share:%'
```

- [ ] **Step 4: Run the test and check it passes**

Run: `./gradlew :core:data:testDebugUnitTest --tests '*PlaylistDaoFollowedPickerTest' -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/db/dao/PlaylistDao.kt core/data/src/test/kotlin/com/stash/core/data/db/dao/PlaylistDaoFollowedPickerTest.kt
git commit -m "feat(share): followed mixes stay out of Save-to-Playlist and stay visible in Library"
```

### Task 15: "Show my name as" preference

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/share/SharePreference.kt`

- [ ] **Step 1: Create it** (its template is `LyricsPreference`; this is a thin DataStore wrapper, so no test)

```kotlin
package com.stash.core.data.share

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.shareDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "share_preference",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** The optional display name on shared mixes (spec §5), remembered between shares. */
@Singleton
class SharePreference @Inject constructor(@ApplicationContext private val context: Context) {
    private val nameKey = stringPreferencesKey("display_name")

    suspend fun displayName(): String? =
        runCatching { context.shareDataStore.data.map { it[nameKey] }.first() }.getOrNull()?.takeIf { it.isNotBlank() }

    suspend fun setDisplayName(name: String?) {
        context.shareDataStore.edit { if (name.isNullOrBlank()) it.remove(nameKey) else it[nameKey] = name.trim().take(40) }
    }
}
```

- [ ] **Step 2: Build and commit**

Run: `./gradlew :core:data:compileDebugKotlin -q`
Expected: success.

```bash
git add core/data/src/main/kotlin/com/stash/core/data/share/SharePreference.kt
git commit -m "feat(share): remember the optional display name for shared mixes"
```

---

# Part C: App UI

### Task 16: Incoming links, from the manifest to the routes

**Files:**
- Modify:
  - `app/src/main/AndroidManifest.xml` (next to the `stash://track` intent filter)
  - `core/data/src/main/kotlin/com/stash/core/data/share/SharedTrackLinkHolder.kt` (already here; only its contents change)
  - `app/src/main/kotlin/com/stash/app/MainActivity.kt:27-30,91-119`
  - `app/src/main/kotlin/com/stash/app/navigation/StashScaffold.kt:123-154`
  - `app/src/main/kotlin/com/stash/app/navigation/TopLevelDestination.kt`
  - `feature/search/src/main/kotlin/com/stash/feature/search/SearchViewModel.kt:234-236` and its test

- [ ] **Step 1: Add the App Link intent filter** in `AndroidManifest.xml`, inside `.MainActivity`, after the `stash://track` filter:

```xml
            <!--
                Shared mix and track links (spec 2026-09-23 shared mixes §6). Verified App Links:
                the host serves /.well-known/assetlinks.json for com.stash.app and .debug.
                Adding a custom domain later = one more <data android:host=…/> pair.
            -->
            <intent-filter android:autoVerify="true">
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="https" android:host="stash-share.rawnaldclark.workers.dev" android:pathPrefix="/m/" />
                <data android:scheme="https" android:host="stash-share.rawnaldclark.workers.dev" android:path="/t" />
            </intent-filter>
```

- [ ] **Step 2: Make `SharedTrackLinkHolder` hold a `SharedTrack`**

```kotlin
package com.stash.core.data.share

import com.stash.core.model.share.SharedTrack
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/** Hands a shared-track link from MainActivity to the shared-track card, exactly once. */
@Singleton
class SharedTrackLinkHolder @Inject constructor() {
    private val pending = AtomicReference<SharedTrack?>(null)
    fun set(track: SharedTrack) { pending.set(track) }
    fun consume(): SharedTrack? = pending.getAndSet(null)
}
```

In `SearchViewModel.kt`, delete the `sharedTrackLinkHolder.consume()?.let { onQueryChanged(it) }` line and the `sharedTrackLinkHolder` constructor parameter, and update `SearchViewModelTest` to match (`grep -n "SharedTrackLinkHolder" feature/search/src/test -r`). Search no longer takes shared links; the new card does.

- [ ] **Step 3: Add the routes** in `TopLevelDestination.kt`:

```kotlin
@Serializable data class SharedMixRoute(val shareId: String)
@Serializable data object SharedTrackRoute
```

- [ ] **Step 4: Parse links in `MainActivity.handleDeepLinkIntent`.** Replace the whole `stash://track` block (lines 94-109) with:

```kotlin
        // Shared mix / track links (https App Links, and legacy stash://track). Spec §6.
        if (intent.action == Intent.ACTION_VIEW) {
            when (val parsed = com.stash.core.model.share.ShareLinks.parse(intent.data?.toString())) {
                is com.stash.core.model.share.ShareLinks.Parsed.Mix ->
                    pendingDeepLink.value = DEEP_LINK_SHARED_MIX_PREFIX + parsed.shareId
                is com.stash.core.model.share.ShareLinks.Parsed.Track -> {
                    sharedTrackLinkHolder.set(parsed.track)
                    pendingDeepLink.value = DEEP_LINK_SHARED_TRACK
                }
                null -> Unit
            }
            if (pendingDeepLink.value != null) { intent.data = null; return }
        }
```

and in the companion:

```kotlin
        const val DEEP_LINK_SHARED_MIX_PREFIX = "shared_mix:"
```

- [ ] **Step 5: Route them in `StashScaffold`.** Replace the `DEEP_LINK_SHARED_TRACK` branch, and add a mix branch before `null ->`:

```kotlin
            com.stash.app.MainActivity.DEEP_LINK_SHARED_TRACK -> {
                navController.navigate(SharedTrackRoute) { launchSingleTop = true }
                onDeepLinkConsumed()
            }
            null -> Unit
            else -> {
                if (pendingDeepLink.startsWith(com.stash.app.MainActivity.DEEP_LINK_SHARED_MIX_PREFIX)) {
                    val id = pendingDeepLink.removePrefix(com.stash.app.MainActivity.DEEP_LINK_SHARED_MIX_PREFIX)
                    navController.navigate(SharedMixRoute(id)) { launchSingleTop = true }
                }
                onDeepLinkConsumed()
            }
```

- [ ] **Step 6: Build.** The routes aren't registered yet, so navigating would crash, but it compiles. Tasks 17 and 18 register them.

Run: `./gradlew :app:compileDebugKotlin -q`
Expected: success once the Search changes compile. If the `SharedMixRoute`/`SharedTrackRoute` composables are missing, that's fine until Task 17.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/AndroidManifest.xml core/data/src/main/kotlin/com/stash/core/data/share/SharedTrackLinkHolder.kt app/src/main/kotlin/com/stash/app/MainActivity.kt app/src/main/kotlin/com/stash/app/navigation/StashScaffold.kt app/src/main/kotlin/com/stash/app/navigation/TopLevelDestination.kt feature/search/src/main/kotlin/com/stash/feature/search/SearchViewModel.kt
git add $(git diff --name-only -- feature/search/src/test)
git diff --cached --name-only
git commit -m "feat(share): https App Links and legacy stash://track route to the new shared screens"
```

### Task 17: The shared-mix screen

**Files:**
- Create: `feature/library/src/main/kotlin/com/stash/feature/library/share/SharedMixViewModel.kt`, `feature/library/src/main/kotlin/com/stash/feature/library/share/SharedMixScreen.kt`
- Modify: `app/src/main/kotlin/com/stash/app/navigation/StashNavHost.kt`
- Test: `feature/library/src/test/kotlin/com/stash/feature/library/share/SharedMixViewModelTest.kt`

- [ ] **Step 0: Add MockK to `feature/library` tests.** It only has Mockito today. In `feature/library/build.gradle.kts`, next to the mockito lines:

```kotlin
    testImplementation("io.mockk:mockk:1.13.8") // same version core/data uses
```

The Task 17 and 19 tests use MockK. The existing `PlaylistDetailViewModelTest` stays on Mockito (Task 20).

- [ ] **Step 1: Write the failing ViewModel test**

```kotlin
package com.stash.feature.library.share

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.entity.SharedMixEntity
import com.stash.core.data.share.ShareResult
import com.stash.core.data.share.SharedMixDocument
import com.stash.core.data.share.SharedMixRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.model.share.SharedTrack
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharedMixViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<SharedMixRepository>(relaxed = true)
    private val player = mockk<PlayerRepository>(relaxed = true)
    private val doc = SharedMixDocument(id = "Kx7Qa2pL", version = 1, name = "Ambient", sharedBy = "Rawn", tracks = listOf(SharedTrack("T", "A")))

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }
    private fun vm() = SharedMixViewModel(SavedStateHandle(mapOf("shareId" to "Kx7Qa2pL")), repo, player)

    @Test fun `loads the doc and knows when it's already followed`() = runTest(dispatcher) {
        coEvery { repo.fetch("Kx7Qa2pL") } returns ShareResult.Ok(doc)
        coEvery { repo.byShareId("Kx7Qa2pL") } returns SharedMixEntity(9, "Kx7Qa2pL", SharedMixEntity.ROLE_FOLLOWER, name = "Ambient")
        val vm = vm(); advanceUntilIdle()
        val s = vm.state.value as SharedMixUiState.Loaded
        assertThat(s.doc.name).isEqualTo("Ambient")
        assertThat(s.followedPlaylistId).isEqualTo(9L)
    }

    @Test fun `gone, missing, newer format and offline map to the spec messages`() = runTest(dispatcher) {
        coEvery { repo.fetch(any()) } returns ShareResult.Gone
        val gone = vm(); advanceUntilIdle()
        assertThat((gone.state.value as SharedMixUiState.Error).message).isEqualTo("This mix is no longer shared.")
        coEvery { repo.fetch(any()) } returns ShareResult.NotFound
        val missing = vm(); advanceUntilIdle()
        assertThat((missing.state.value as SharedMixUiState.Error).message).isEqualTo("This link doesn't point to a mix.")
        coEvery { repo.fetch(any()) } returns ShareResult.Ok(doc.copy(v = 2))
        val newer = vm(); advanceUntilIdle()
        assertThat((newer.state.value as SharedMixUiState.Error).message).isEqualTo("Update Stash to open this mix.")
        coEvery { repo.fetch(any()) } returns ShareResult.Failed("io")
        val offline = vm(); advanceUntilIdle()
        assertThat((offline.state.value as SharedMixUiState.Error).retryable).isTrue()
    }

    @Test fun `follow persists and reports the new playlist`() = runTest(dispatcher) {
        coEvery { repo.fetch(any()) } returns ShareResult.Ok(doc)
        coEvery { repo.byShareId(any()) } returns null
        coEvery { repo.follow(doc) } returns 42L
        val vm = vm(); advanceUntilIdle()
        var opened: Long? = null
        vm.follow { opened = it }; advanceUntilIdle()
        assertThat(opened).isEqualTo(42L)
        coVerify { repo.follow(doc) }
    }
}
```

- [ ] **Step 2: Run it and check it fails**

Run: `./gradlew :feature:library:testDebugUnitTest --tests '*SharedMixViewModelTest' -q`
Expected: FAIL, unresolved `SharedMixViewModel`.

- [ ] **Step 3: Implement `SharedMixViewModel.kt`**

```kotlin
package com.stash.feature.library.share

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.db.entity.SharedMixEntity
import com.stash.core.data.share.ShareResult
import com.stash.core.data.share.SharedMixDocument
import com.stash.core.data.share.SharedMixRepository
import com.stash.core.media.PlayerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface SharedMixUiState {
    data object Loading : SharedMixUiState
    data class Error(val message: String, val retryable: Boolean) : SharedMixUiState
    data class Loaded(val doc: SharedMixDocument, val followedPlaylistId: Long?, val isOwnMix: Boolean, val busy: Boolean = false) : SharedMixUiState
}

@HiltViewModel
class SharedMixViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: SharedMixRepository,
    private val playerRepository: PlayerRepository,
) : ViewModel() {
    private val shareId: String = checkNotNull(savedStateHandle.get<String>("shareId"))
    private val _state = MutableStateFlow<SharedMixUiState>(SharedMixUiState.Loading)
    val state: StateFlow<SharedMixUiState> = _state

    init { load() }

    fun load() {
        _state.value = SharedMixUiState.Loading
        viewModelScope.launch {
            _state.value = when (val r = repository.fetch(shareId)) {
                is ShareResult.Ok -> if (r.value.v > 1) SharedMixUiState.Error("Update Stash to open this mix.", false) else {
                    val row = repository.byShareId(shareId)
                    SharedMixUiState.Loaded(
                        doc = r.value,
                        followedPlaylistId = row?.takeIf { it.role == SharedMixEntity.ROLE_FOLLOWER }?.playlistId,
                        isOwnMix = row?.role == SharedMixEntity.ROLE_OWNER,
                    )
                }
                ShareResult.Gone -> SharedMixUiState.Error("This mix is no longer shared.", false)
                ShareResult.NotFound -> SharedMixUiState.Error("This link doesn't point to a mix.", false)
                else -> SharedMixUiState.Error("Couldn't load this mix. Check your connection.", true)
            }
        }
    }

    private fun withLoaded(block: suspend (SharedMixUiState.Loaded) -> Unit) {
        val s = _state.value as? SharedMixUiState.Loaded ?: return
        if (s.busy) return
        _state.value = s.copy(busy = true)
        viewModelScope.launch {
            try { block(s) } finally { (_state.value as? SharedMixUiState.Loaded)?.let { _state.value = it.copy(busy = false) } }
        }
    }

    fun play() = withLoaded { s ->
        val tracks = repository.tracksFor(s.doc)
        if (tracks.isNotEmpty()) playerRepository.setQueue(tracks, 0)
    }

    fun follow(onFollowed: (Long) -> Unit) = withLoaded { s ->
        val id = repository.follow(s.doc)
        _state.value = s.copy(followedPlaylistId = id)
        onFollowed(id)
    }

    fun saveCopy(onSaved: (Long) -> Unit) = withLoaded { s -> onSaved(repository.saveCopy(s.doc)) }

    fun unfollow() = withLoaded { s ->
        s.followedPlaylistId?.let { repository.unfollow(it) }
        _state.value = s.copy(followedPlaylistId = null)
    }
}
```

- [ ] **Step 4: Run the test and check it passes**

Run: `./gradlew :feature:library:testDebugUnitTest --tests '*SharedMixViewModelTest' -q`
Expected: PASS.

- [ ] **Step 5: Implement `SharedMixScreen.kt`.** Match `PlaylistDetailScreen`'s look: `StashTheme.extendedColors.glassBackground` buttons, and `AsyncImage` from Coil 3 for the covers. Check the import with `grep -n "AsyncImage" feature/library/src/main/kotlin -r | head -1`.

```kotlin
package com.stash.feature.library.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage

@Composable
fun SharedMixScreen(
    onBack: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    viewModel: SharedMixViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        when (val s = state) {
            SharedMixUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is SharedMixUiState.Error -> Column(
                Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(s.message, style = MaterialTheme.typography.titleMedium)
                if (s.retryable) { Spacer(Modifier.height(16.dp)); Button(onClick = viewModel::load) { Text("Retry") } }
            }
            is SharedMixUiState.Loaded -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        s.doc.covers.firstOrNull()?.let {
                            AsyncImage(it, null, Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                            Spacer(Modifier.size(16.dp))
                        }
                        Column {
                            Text(s.doc.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val by = s.doc.sharedBy?.let { " · shared by $it" }.orEmpty()
                            Text("${s.doc.tracks.size} tracks$by", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = viewModel::play, enabled = !s.busy) { Text("Play") }
                        when {
                            s.isOwnMix -> Text("This is your mix", Modifier.align(Alignment.CenterVertically))
                            s.followedPlaylistId != null -> {
                                OutlinedButton(onClick = { onOpenPlaylist(s.followedPlaylistId) }) { Text("Following") }
                                OutlinedButton(onClick = viewModel::unfollow, enabled = !s.busy) { Text("Unfollow") }
                            }
                            else -> {
                                Button(onClick = { viewModel.follow(onOpenPlaylist) }, enabled = !s.busy) { Text("Follow") }
                                OutlinedButton(onClick = { viewModel.saveCopy(onOpenPlaylist) }, enabled = !s.busy) { Text("Save a copy") }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                itemsIndexed(s.doc.tracks) { i, t ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text("${i + 1}. ${t.title}", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(t.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 6: Register the route in `StashNavHost`,** next to `PlaylistDetailRoute`:

```kotlin
        composable<SharedMixRoute> {
            com.stash.feature.library.share.SharedMixScreen(
                onBack = { navController.popBackStack() },
                onOpenPlaylist = { id -> navController.navigate(PlaylistDetailRoute(id)) },
            )
        }
```

- [ ] **Step 7: Build and commit**

Run: `./gradlew :app:assembleDebug -q`
Expected: success.

```bash
git add feature/library/build.gradle.kts feature/library/src/main/kotlin/com/stash/feature/library/share/SharedMixViewModel.kt feature/library/src/main/kotlin/com/stash/feature/library/share/SharedMixScreen.kt feature/library/src/test/kotlin/com/stash/feature/library/share/SharedMixViewModelTest.kt app/src/main/kotlin/com/stash/app/navigation/StashNavHost.kt
git commit -m "feat(share): shared mix screen — Play, Follow, Save a copy, Unfollow, error states"
```

### Task 18: The shared-track card

Spec §6 says "Add to library". In Stash a single song is kept by liking it, so the button likes the track but is labelled the spec's way.

**Files:**
- Create: `feature/library/src/main/kotlin/com/stash/feature/library/share/SharedTrackViewModel.kt`, `feature/library/src/main/kotlin/com/stash/feature/library/share/SharedTrackScreen.kt`
- Modify: `app/src/main/kotlin/com/stash/app/navigation/StashNavHost.kt`

- [ ] **Step 1: Implement `SharedTrackViewModel.kt`.** It's thin glue over already-tested pieces, so there's no separate test.

```kotlin
package com.stash.feature.library.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.share.SharedTrackLinkHolder
import com.stash.core.data.social.LikeCoordinator
import com.stash.core.media.PlayerRepository
import com.stash.core.model.share.SharedTrack
import com.stash.core.model.share.toTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class SharedTrackViewModel @Inject constructor(
    holder: SharedTrackLinkHolder,
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
    private val likeCoordinator: LikeCoordinator,
) : ViewModel() {
    val track: SharedTrack? = holder.consume()
    private val _liked = MutableStateFlow(false)
    val liked: StateFlow<Boolean> = _liked

    private suspend fun persisted(): Long? = track?.let { musicRepository.ensureTrackPersisted(it.toTrack()) }

    fun play() = viewModelScope.launch {
        val id = persisted() ?: return@launch
        musicRepository.getTrackById(id)?.let { playerRepository.setQueue(listOf(it), 0) }
    }

    fun like() = viewModelScope.launch {
        val id = persisted() ?: return@launch
        likeCoordinator.setLiked(id, true)
        _liked.value = true
    }
}
```

Check that `MusicRepository` has a suspend by-ID getter: `grep -n "fun getTrackById\|suspend fun getTrack(" core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepository.kt`. If the name differs, use it. If there isn't one, inject `TrackDao` and use `trackDao.getById(id)?.toDomain()`.

- [ ] **Step 2: Implement `SharedTrackScreen.kt`**

```kotlin
package com.stash.feature.library.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SharedTrackScreen(onBack: () -> Unit, viewModel: SharedTrackViewModel = hiltViewModel()) {
    val liked by viewModel.liked.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(20.dp)) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        val t = viewModel.track
        if (t == null) { Text("This link has expired. Open it again."); return@Column }
        Spacer(Modifier.height(24.dp))
        Text(t.title, style = MaterialTheme.typography.headlineSmall)
        Text(t.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        t.album?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.play() }) { Text("Play") }
            OutlinedButton(onClick = { viewModel.like() }, enabled = !liked) { Text(if (liked) "In your library" else "Add to library") }
        }
    }
}
```

- [ ] **Step 3: Register the route in `StashNavHost`**

```kotlin
        composable<SharedTrackRoute> {
            com.stash.feature.library.share.SharedTrackScreen(onBack = { navController.popBackStack() })
        }
```

- [ ] **Step 4: Build and commit**

Run: `./gradlew :app:assembleDebug -q`
Expected: success.

```bash
git add feature/library/src/main/kotlin/com/stash/feature/library/share/SharedTrackViewModel.kt feature/library/src/main/kotlin/com/stash/feature/library/share/SharedTrackScreen.kt app/src/main/kotlin/com/stash/app/navigation/StashNavHost.kt
git commit -m "feat(share): shared track card — Play and Add to Liked Songs"
```

### Task 19: The share sheet and its entry points

**Files:**
- Create: `feature/library/src/main/kotlin/com/stash/feature/library/share/ShareMixViewModel.kt`, `feature/library/src/main/kotlin/com/stash/feature/library/share/ShareMixSheet.kt`
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/PlaylistDetailScreen.kt` (`PlaylistHeader` and its call site), `feature/library/src/main/kotlin/com/stash/feature/library/LibraryScreen.kt` (the `PlaylistsGrid` sheet), `feature/home/src/main/kotlin/com/stash/feature/home/HomeScreen.kt` (the mix action sheet), `app/src/main/kotlin/com/stash/app/navigation/TopLevelDestination.kt` and `StashNavHost.kt` (the `openShare` flag)
- Test: `feature/library/src/test/kotlin/com/stash/feature/library/share/ShareMixViewModelTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.stash.feature.library.share

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.entity.SharedMixEntity
import com.stash.core.data.share.SharePreference
import com.stash.core.data.share.ShareResult
import com.stash.core.data.share.SharedMixRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShareMixViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<SharedMixRepository>(relaxed = true)
    private val pref = mockk<SharePreference>(relaxed = true)
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `not shared yet offers the form; create stores the name and shows the link`() = runTest(dispatcher) {
        coEvery { repo.observe(5) } returns flowOf(null)
        coEvery { pref.displayName() } returns "Rawn"
        coEvery { repo.share(5, "Sleep", "Rawn", true) } returns ShareResult.Ok("https://x/m/Kx7Qa2pL")
        val vm = ShareMixViewModel(repo, pref); vm.bind(5, "Ambient"); advanceUntilIdle()
        assertThat((vm.state.value as ShareMixUiState.NotShared).displayName).isEqualTo("Rawn")
        vm.create("Sleep", "Rawn", autoUpdate = true); advanceUntilIdle()
        coVerify { pref.setDisplayName("Rawn") }
        coVerify { repo.share(5, "Sleep", "Rawn", true) }
    }

    @Test fun `an owned share shows its link; a followed one shows the original link read-only`() = runTest(dispatcher) {
        coEvery { repo.observe(5) } returns flowOf(SharedMixEntity(5, "Kx7Qa2pL", SharedMixEntity.ROLE_OWNER, name = "Sleep", editKey = "k"))
        val owned = ShareMixViewModel(repo, pref); owned.bind(5, "Ambient"); advanceUntilIdle()
        val s = owned.state.value as ShareMixUiState.Shared
        assertThat(s.url).endsWith("/m/Kx7Qa2pL"); assertThat(s.canManage).isTrue()
        coEvery { repo.observe(6) } returns flowOf(SharedMixEntity(6, "BBBBBBBB", SharedMixEntity.ROLE_FOLLOWER, name = "F"))
        val followed = ShareMixViewModel(repo, pref); followed.bind(6, "F"); advanceUntilIdle()
        assertThat((followed.state.value as ShareMixUiState.Shared).canManage).isFalse()
    }
}
```

- [ ] **Step 2: Run it and check it fails**

Run: `./gradlew :feature:library:testDebugUnitTest --tests '*ShareMixViewModelTest' -q`
Expected: FAIL, unresolved `ShareMixViewModel`.

- [ ] **Step 3: Implement `ShareMixViewModel.kt`.** A followed mix shares its *original* link: re-sharing a copy would split it from the owner, so it isn't offered.

```kotlin
package com.stash.feature.library.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.db.entity.SharedMixEntity
import com.stash.core.data.share.SharePreference
import com.stash.core.data.share.ShareResult
import com.stash.core.data.share.SharedMixRepository
import com.stash.core.model.share.ShareLinks
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface ShareMixUiState {
    data object Loading : ShareMixUiState
    data class NotShared(val defaultName: String, val displayName: String?, val working: Boolean = false, val error: String? = null) : ShareMixUiState
    data class Shared(val url: String, val name: String, val canManage: Boolean, val autoUpdate: Boolean) : ShareMixUiState
}

@HiltViewModel
class ShareMixViewModel @Inject constructor(
    private val repository: SharedMixRepository,
    private val sharePreference: SharePreference,
) : ViewModel() {
    private val _state = MutableStateFlow<ShareMixUiState>(ShareMixUiState.Loading)
    val state: StateFlow<ShareMixUiState> = _state
    private var playlistId = 0L
    private var observer: Job? = null

    fun bind(playlistId: Long, playlistName: String) {
        if (this.playlistId == playlistId && observer != null) return
        this.playlistId = playlistId
        observer?.cancel()
        observer = viewModelScope.launch {
            val display = sharePreference.displayName()
            repository.observe(playlistId).collect { row ->
                _state.value = if (row == null || row.status != SharedMixEntity.STATUS_ACTIVE) {
                    ShareMixUiState.NotShared(playlistName, display)
                } else {
                    ShareMixUiState.Shared(ShareLinks.mixUrl(row.shareId), row.name, row.role == SharedMixEntity.ROLE_OWNER, row.autoUpdate)
                }
            }
        }
    }

    fun create(name: String, displayName: String?, autoUpdate: Boolean) {
        val s = _state.value as? ShareMixUiState.NotShared ?: return
        _state.value = s.copy(working = true, error = null)
        viewModelScope.launch {
            sharePreference.setDisplayName(displayName)
            when (val r = repository.share(playlistId, name.ifBlank { s.defaultName }, displayName, autoUpdate)) {
                is ShareResult.Ok -> Unit // the observer flips the state to Shared
                is ShareResult.Failed -> _state.value = s.copy(working = false, error = r.message ?: "Couldn't create the link.")
                else -> _state.value = s.copy(working = false, error = "Couldn't create the link.")
            }
        }
    }

    fun setAutoUpdate(on: Boolean) = viewModelScope.launch { repository.setAutoUpdate(playlistId, on) }
    fun stopSharing() = viewModelScope.launch { repository.stopSharing(playlistId) }
}
```

- [ ] **Step 4: Run the test and check it passes**

Run: `./gradlew :feature:library:testDebugUnitTest --tests '*ShareMixViewModelTest' -q`
Expected: PASS.

- [ ] **Step 5: Implement `ShareMixSheet.kt`**

```kotlin
package com.stash.feature.library.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Spec §5 share sheet. Keyed per playlist so each playlist gets its own ViewModel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareMixSheet(
    playlistId: Long,
    playlistName: String,
    trackCount: Int,
    onDismiss: () -> Unit,
    viewModel: ShareMixViewModel = hiltViewModel(key = "share-$playlistId"),
) {
    LaunchedEffect(playlistId) { viewModel.bind(playlistId, playlistName) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Spec §5: "Create link" goes straight on to the Android share sheet once the link exists.
    var justCreated by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state) {
        val s = state
        if (justCreated && s is ShareMixUiState.Shared) { justCreated = false; sendLink(context, s.name, trackCount, s.url) }
        if (s is ShareMixUiState.NotShared && s.error != null) justCreated = false
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Share mix", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            when (val s = state) {
                ShareMixUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                is ShareMixUiState.NotShared -> {
                    var name by rememberSaveable { mutableStateOf(s.defaultName) }
                    var display by rememberSaveable { mutableStateOf(s.displayName.orEmpty()) }
                    var auto by rememberSaveable { mutableStateOf(true) }
                    OutlinedTextField(name, { name = it.take(100) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(display, { display = it.take(40) }, label = { Text("Show my name as (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Keep it updated for followers", Modifier.weight(1f))
                        Switch(checked = auto, onCheckedChange = { auto = it })
                    }
                    s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(onClick = { justCreated = true; viewModel.create(name, display.ifBlank { null }, auto) }, enabled = !s.working, modifier = Modifier.fillMaxWidth()) {
                        Text(if (s.working) "Creating link…" else "Create link")
                    }
                }
                is ShareMixUiState.Shared -> {
                    Text(s.url, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { sendLink(context, s.name, trackCount, s.url) }) { Text("Share again") }
                        OutlinedButton(onClick = { copy(context, s.url) }) { Text("Copy link") }
                    }
                    if (s.canManage) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Keep it updated for followers", Modifier.weight(1f))
                            Switch(checked = s.autoUpdate, onCheckedChange = viewModel::setAutoUpdate)
                        }
                        var confirm by remember { mutableStateOf(false) }
                        TextButton(onClick = { if (confirm) { viewModel.stopSharing(); onDismiss() } else confirm = true }) {
                            Text(if (confirm) "Tap again to stop sharing" else "Stop sharing", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

private fun sendLink(context: Context, name: String, count: Int, url: String) {
    val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "$name: $count tracks on Stash · $url") }
    context.startActivity(Intent.createChooser(intent, "Share \"$name\""))
}

private fun copy(context: Context, url: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Stash mix", url))
}
```

- [ ] **Step 6: Add the entry points**
  - **`PlaylistDetailScreen`:**
    - Add `onShare: () -> Unit` to `PlaylistHeader`.
    - Add a Share `IconButton` right after the Search `IconButton`, with the same modifier and `Icons.Default.Share`, content description "Share mix".
    - In `PlaylistDetailScreen`, add `var showShareSheet by rememberSaveable { mutableStateOf(false) }` and pass `onShare = { showShareSheet = true }`.
    - Render the sheet once `state.playlist` is non-null:
      ```kotlin
          if (showShareSheet) state.playlist?.let { p ->
              com.stash.feature.library.share.ShareMixSheet(p.id, p.name, state.tracks.size, onDismiss = { showShareSheet = false })
          }
      ```
  - **`LibraryScreen` `PlaylistsGrid` long-press sheet:**
    - Add a row after "Add to Queue":
      `BottomSheetActionRow(icon = Icons.Default.Share, label = "Share mix", onClick = { sharePlaylist = playlist; selectedPlaylist = null })`.
    - Declare `var sharePlaylist by remember { mutableStateOf<Playlist?>(null) }` next to `selectedPlaylist`.
    - Render `sharePlaylist?.let { ShareMixSheet(it.id, it.name, it.trackCount, onDismiss = { sharePlaylist = null }) }` inside `PlaylistsGrid`.
  - **Home mix action sheet:**
    - Add the route flag `@Serializable data class PlaylistDetailRoute(val playlistId: Long, val openShare: Boolean = false)`.
    - Add `onShareMix: (Long) -> Unit = {}` to `HomeScreen` (a default, like its other callbacks) and a row `MixActionRow(icon = Icons.Default.Share, label = "Share mix", onClick = { onShareMix(id); actionSheetMixId = null })` after "Open".
    - In `StashNavHost`, pass `onShareMix = { id -> navController.navigate(PlaylistDetailRoute(id, openShare = true)) }`.
    - `PlaylistDetailViewModel` reads `val openShare: Boolean = savedStateHandle.get<Boolean>("openShare") ?: false`.
    - `PlaylistDetailScreen` initialises `showShareSheet` to `viewModel.openShare`.

- [ ] **Step 7: Build and commit**

Run: `./gradlew :feature:library:testDebugUnitTest -q` then `./gradlew :app:assembleDebug -q`
Expected: both succeed. If `PlaylistDetailViewModelTest` constructs the VM with a `SavedStateHandle` lacking `openShare`, that's fine: it defaults to false.

```bash
git add feature/library/src/main/kotlin/com/stash/feature/library/share/ShareMixViewModel.kt feature/library/src/main/kotlin/com/stash/feature/library/share/ShareMixSheet.kt feature/library/src/test/kotlin/com/stash/feature/library/share/ShareMixViewModelTest.kt feature/library/src/main/kotlin/com/stash/feature/library/PlaylistDetailScreen.kt feature/library/src/main/kotlin/com/stash/feature/library/PlaylistDetailViewModel.kt feature/library/src/main/kotlin/com/stash/feature/library/LibraryScreen.kt feature/home/src/main/kotlin/com/stash/feature/home/HomeScreen.kt app/src/main/kotlin/com/stash/app/navigation/TopLevelDestination.kt app/src/main/kotlin/com/stash/app/navigation/StashNavHost.kt
git commit -m "feat(share): share sheet from playlist detail, Library long-press and Home mix menu"
```

### Task 20: A followed playlist inside `PlaylistDetailScreen`

**Files:**
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/PlaylistDetailViewModel.kt`, `feature/library/src/main/kotlin/com/stash/feature/library/PlaylistDetailScreen.kt`, `feature/library/src/test/kotlin/com/stash/feature/library/PlaylistDetailViewModelTest.kt`

- [ ] **Step 1: Write the failing test.** Add it to `PlaylistDetailViewModelTest`, which uses **Mockito**, not MockK. First extend the existing `buildVm` helper (around line 297) with a parameter and pass it through to the constructor:

```kotlin
        sharedMixRepository: com.stash.core.data.share.SharedMixRepository = mock {
            on { observe(any()) } doReturn flowOf(null)
        },
```
```kotlin
        sharedMixRepository = sharedMixRepository,
```

Then add the test. `follow` is a `WhileSubscribed` StateFlow, so the test must collect it or it stays `null`:

```kotlin
    @Test fun `an active follow exposes read-only state with the sharer's name`() = runTest {
        val shared = mock<com.stash.core.data.share.SharedMixRepository> {
            on { observe(any()) } doReturn flowOf(
                com.stash.core.data.db.entity.SharedMixEntity(
                    1, "Kx7Qa2pL", com.stash.core.data.db.entity.SharedMixEntity.ROLE_FOLLOWER, name = "Ambient", sharedBy = "Rawn",
                ),
            )
        }
        val vm = buildVm(sharedMixRepository = shared)
        backgroundScope.launch { vm.follow.collect {} }
        runCurrent()
        val f = checkNotNull(vm.follow.value)
        assertEquals(true, f.readOnly)
        assertEquals("Rawn", f.sharedBy)
    }
```

- [ ] **Step 2: Run it and check it fails**

Run: `./gradlew :feature:library:testDebugUnitTest --tests '*PlaylistDetailViewModelTest' -q`
Expected: FAIL, unresolved `follow`.

- [ ] **Step 3: Extend `PlaylistDetailViewModel`**
  - Add the constructor parameter `private val sharedMixRepository: com.stash.core.data.share.SharedMixRepository`.
  - Add the following **after** the `_playlist` declaration (around line 94). Property initialisers run in source order, so declaring `follow` earlier would hand `combine` a null. Keep it separate from the existing 5-flow `combine`:

```kotlin
    data class FollowUi(val readOnly: Boolean, val sharedBy: String?, val downloadOn: Boolean)

    val follow: StateFlow<FollowUi?> = combine(
        sharedMixRepository.observe(playlistId),
        _playlist,
    ) { row, playlist ->
        row?.takeIf { it.role == com.stash.core.data.db.entity.SharedMixEntity.ROLE_FOLLOWER }?.let {
            FollowUi(
                readOnly = it.status == com.stash.core.data.db.entity.SharedMixEntity.STATUS_ACTIVE,
                sharedBy = it.sharedBy,
                downloadOn = playlist?.syncEnabled == true,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setFollowDownload(on: Boolean) = viewModelScope.launch {
        sharedMixRepository.setDownload(playlistId, on)
        _playlist.value = _playlist.value?.copy(syncEnabled = on)
    }

    fun unfollow(onDone: () -> Unit) = viewModelScope.launch { sharedMixRepository.unfollow(playlistId); onDone() }
```

  - In `init`, after `loadPlaylistMetadata()`:

```kotlin
        viewModelScope.launch { sharedMixRepository.consumeRemovedNotice(playlistId)?.let { _userMessages.tryEmit(it) } }
```

  - Two test files construct `PlaylistDetailViewModel`. `PlaylistDetailViewModelTest.buildVm` was extended in Step 1. **`MixOfflineTapGuardTest`** also constructs it: pass `sharedMixRepository = mock { on { observe(any()) } doReturn flowOf(null) }` there as well.

- [ ] **Step 4: Make the screen read-only for an active follow.** In `PlaylistDetailScreen`, collect `val follow by viewModel.follow.collectAsStateWithLifecycle()`, then:
  - Pass `onDelete = if (follow?.readOnly == true) null else { t -> trackToDelete = t }` to `TrackOptionsSheet`, keeping the existing lambda body.
  - Leave the `"delete"` action out of the selection actions list when `follow?.readOnly == true`.
  - Gate the header image button on `playlist.type == PlaylistType.CUSTOM && follow?.readOnly != true`. That needs a `readOnly: Boolean` parameter on `PlaylistHeader`.
  - Below the header, when `follow != null && follow.readOnly`, show:

```kotlin
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Following · from ${follow.sharedBy ?: "a friend"}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Download this mix", Modifier.weight(1f))
                    Switch(checked = follow.downloadOn, onCheckedChange = viewModel::setFollowDownload)
                }
                TextButton(onClick = { viewModel.unfollow(onBack) }) { Text("Unfollow", color = MaterialTheme.colorScheme.error) }
            }
```

- [ ] **Step 5: Run the tests and build**

Run: `./gradlew :feature:library:testDebugUnitTest -q` then `./gradlew :app:assembleDebug -q`
Expected: both succeed.

- [ ] **Step 6: Commit**

```bash
git add feature/library/src/main/kotlin/com/stash/feature/library/PlaylistDetailViewModel.kt feature/library/src/main/kotlin/com/stash/feature/library/PlaylistDetailScreen.kt feature/library/src/test/kotlin/com/stash/feature/library/PlaylistDetailViewModelTest.kt feature/library/src/test/kotlin/com/stash/feature/library/MixOfflineTapGuardTest.kt
git commit -m "feat(share): followed mixes are read-only, with Download this mix, Unfollow and the stopped-sharing notice"
```

### Task 21: https links from the Track share sheet

**Files:**
- Modify: `core/ui/src/main/kotlin/com/stash/core/ui/components/ShareTrackSheet.kt:153-160`, `core/ui/src/test/kotlin/com/stash/core/ui/components/ShareLinksTest.kt`

- [ ] **Step 1: Update the tests to expect the https link.** Replace the two `stashShareLink` tests:

```kotlin
    @Test
    fun `stash link is the https track link with the known ids`() {
        val link = stashShareLink(title = "Song & Dance", artist = "Aphex Twin", spotifyUri = "spotify:track:abc", youtubeId = "xyz")
        assertThat(link).startsWith("https://stash-share.rawnaldclark.workers.dev/t?t=Song+%26+Dance&a=Aphex+Twin")
        assertThat(link).contains("&sp=abc")
        assertThat(link).contains("&yt=xyz")
    }

    @Test
    fun `stash link omits missing ids`() {
        assertThat(stashShareLink("T", "A", spotifyUri = null, youtubeId = null))
            .isEqualTo("https://stash-share.rawnaldclark.workers.dev/t?t=T&a=A")
    }
```

- [ ] **Step 2: Run them and check they fail**

Run: `./gradlew :core:ui:testDebugUnitTest --tests '*ShareLinksTest' -q`
Expected: FAIL. The link still starts with `stash://`.

- [ ] **Step 3: Delegate to `ShareLinks`.** Replace the body of `stashShareLink`, keeping its signature so callers are untouched:

```kotlin
fun stashShareLink(title: String, artist: String, spotifyUri: String?, youtubeId: String?): String =
    com.stash.core.model.share.ShareLinks.trackUrl(
        com.stash.core.model.share.SharedTrack(
            title = title,
            artist = artist,
            spotifyId = com.stash.core.model.share.spotifyTrackId(spotifyUri),
            youtubeId = youtubeId?.takeIf { it.isNotBlank() },
        ),
    )
```

Update the "Stash link" row's share text to drop the "(Opens in Stash — get it: …)" line. The page at the link now offers "Get Stash" itself: `send("$artist — $title\n$link")`.

- [ ] **Step 4: Run the tests and check they pass**

Run: `./gradlew :core:ui:testDebugUnitTest --tests '*ShareLinksTest' -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/ui/src/main/kotlin/com/stash/core/ui/components/ShareTrackSheet.kt core/ui/src/test/kotlin/com/stash/core/ui/components/ShareLinksTest.kt
git commit -m "feat(share): the Track share sheet's Stash link is now a tappable https link"
```

---

# Part D: Disclosure, deploy, device test

### Task 22: README disclosure

**Files:**
- Modify: `README.md` ("What Stash talks to" list)

- [ ] **Step 1: Add the entry** next to the other `workers.dev` hosts:

```markdown
- **`stash-share.rawnaldclark.workers.dev`** — shared mixes and song links. Only when you share or open one: sharing sends the mix's name, its songs (title, artist and, when known, album, length, ISRC and Spotify/YouTube IDs), up to four album-art links and, if you choose, a display name. Opening or following a mix reads it back; followed mixes are re-checked after syncs and at most every 6 hours. Nothing about your account, device or listening is sent. Anyone with a mix's link can read it; links can't be guessed and nothing lists them.
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: disclose the stash-share host and exactly what shared mixes send"
```

### Task 23: Deploy the Worker, verify App Links, device test

**Files:**
- Modify: `infra/share-worker/wrangler.toml` (KV id)

- [ ] **Step 1: Create the KV namespace and deploy**

```bash
cd infra/share-worker
npx wrangler kv namespace create SHARE_KV     # copy the printed id into wrangler.toml
npm test
npx wrangler deploy
```
Expected: `https://stash-share.rawnaldclark.workers.dev` is printed.

- [ ] **Step 2: Smoke test against production**

```bash
BASE=https://stash-share.rawnaldclark.workers.dev
KEY=$(python -c "import secrets,base64;print(base64.urlsafe_b64encode(secrets.token_bytes(32)).decode().rstrip('='))")
ID=$(curl -s -X POST $BASE/v1/mixes -H 'content-type: application/json' \
  -d "{\"doc\":{\"v\":1,\"name\":\"Smoke\",\"tracks\":[{\"t\":\"Avril 14th\",\"a\":\"Aphex Twin\"}]},\"editKey\":\"$KEY\"}" | python -c "import json,sys;print(json.load(sys.stdin)['id'])")
curl -s $BASE/v1/mixes/$ID/version              # {"version":1}
curl -s -o /dev/null -w '%{http_code}\n' $BASE/m/$ID   # 200
curl -s -X DELETE $BASE/v1/mixes/$ID -H "X-Stash-Edit-Key: $KEY" -o /dev/null -w '%{http_code}\n'   # 204
curl -s $BASE/.well-known/assetlinks.json       # both packages
```

- [ ] **Step 3: Commit the KV id**

```bash
git add infra/share-worker/wrangler.toml
git commit -m "chore(share): KV namespace id for the deployed stash-share Worker"
```

- [ ] **Step 4: Device test with two Stash users on one phone**

The debug (`com.stash.app.debug`) and release (`com.stash.app`) apps are separate users. Install the debug build (`./gradlew :app:installDebug`) and a release build from this branch, or use the existing release app once it ships. Then check App Link verification:
- `adb shell pm get-app-links com.stash.app.debug` shows `stash-share.rawnaldclark.workers.dev: verified`.
- If it doesn't, run `adb shell pm verify-app-links --re-verify com.stash.app.debug`.

Walk through the spec §10 scenario, checking each result:
1. **Share** (app A): Library → a playlist → Share → Create link → Copy link.
2. **Open** (app B): `adb shell am start -a android.intent.action.VIEW -d "<link>" com.stash.app.debug`. The shared mix screen opens (not a browser) with the right name and tracks.
3. **Follow** (app B): the playlist opens showing "Following · from …"; delete and the cover image are hidden; it isn't offered in "Save to Playlist".
4. **Update** (app A): add a song to the shared playlist and wait about 30 s for the publish. Then in app B, force the check: run a sync, or `adb shell cmd jobscheduler run -f -n androidx.work.systemjobscheduler com.stash.app.debug <job id of SharedMixFollowWorker>`. App B has the new song.
5. **Download this mix** (app B): switching it on queues downloads (Downloads screen).
6. **Stop sharing** (app A), then another follow check (app B): the playlist becomes an ordinary editable playlist and the "stopped sharing" message appears once.
7. **Save a copy** (app B, from a new share): creates an editable playlist.
8. **Track link:** Now Playing → Share → Stash link → open it in the other app: the track card appears; Play works.

- [ ] **Step 5: Open the PR**

```bash
git push -u origin <branch>
gh pr create --base master --title "feat: shared mixes — followable links, Save a copy, https song links" --body "<summary; spec + plan links; device test results>"
```
