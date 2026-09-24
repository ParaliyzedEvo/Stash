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
