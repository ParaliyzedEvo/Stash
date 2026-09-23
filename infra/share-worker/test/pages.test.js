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
