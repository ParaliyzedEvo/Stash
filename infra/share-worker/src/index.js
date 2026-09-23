/**
 * Stash Share: Cloudflare Worker (spec docs/superpowers/specs/2026-09-23-shared-mixes-design.md §4).
 * One KV document per shared mix; updates and deletes are authorised by a per-mix edit key whose
 * SHA-256 is all we store.
 */
import { validateDoc, validEditKey, MAX_BODY_BYTES } from "./validate.js";
import { freeId, readMix, sameHex, sha256Hex, writeMix, writeTombstone } from "./store.js";

const MIX_API = /^\/v1\/mixes\/([A-Za-z0-9]{8})(\/version)?$/;

export default { fetch: (request, env) => handle(request, env) };

export async function handle(request, env) {
    const url = new URL(request.url);
    const path = url.pathname;
    const method = request.method;

    if (path === "/v1/mixes") return method === "POST" ? createMix(request, env, url) : methodNotAllowed();
    const m = MIX_API.exec(path);
    if (m) {
        const id = m[1];
        if (m[2]) return method === "GET" ? getVersion(env, id) : methodNotAllowed();
        if (method === "GET") return getMix(env, id);
        if (method === "PUT") return updateMix(request, env, id);
        if (method === "DELETE") return deleteMix(request, env, id);
        return methodNotAllowed();
    }
    return json({ error: "not_found" }, 404);
}

export function json(obj, status = 200, extra = {}) {
    return new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json", ...extra } });
}
const methodNotAllowed = () => json({ error: "method_not_allowed" }, 405);

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
