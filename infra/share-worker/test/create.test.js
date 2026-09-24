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

test("covers off the album-art allowlist are dropped, not rejected", async () => {
    const e = env();
    const covers = ["https://i.scdn.co/image/a", "https://evil.example/log.gif", "https://x.i.ytimg.com/vi/b.jpg", "https://i.scdn.co.evil.example/c"];
    const r = await post({ doc: doc({ covers }), editKey: KEY }, e);
    assert.equal(r.status, 201);
    const stored = await e.SHARE_KV.get(`mix:${(await r.json()).id}`, "json");
    assert.deepEqual(stored.doc.covers, ["https://i.scdn.co/image/a", "https://x.i.ytimg.com/vi/b.jpg"]);
    const none = await post({ doc: doc({ covers: ["https://evil.example/a.jpg"] }), editKey: KEY }, e);
    assert.equal(none.status, 201);
    assert.equal((await e.SHARE_KV.get(`mix:${(await none.json()).id}`, "json")).doc.covers, undefined);
});
