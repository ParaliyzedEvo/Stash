/**
 * Stash Share: Cloudflare Worker (spec docs/superpowers/specs/2026-09-23-shared-mixes-design.md §4).
 * One KV document per shared mix; updates and deletes are authorised by a per-mix edit key whose
 * SHA-256 is all we store.
 */
import { validateDoc, validEditKey, MAX_BODY_BYTES } from "./validate.js";
import { freeId, sha256Hex, writeMix } from "./store.js";

const MIX_API = /^\/v1\/mixes\/([A-Za-z0-9]{8})(\/version)?$/;

export default { fetch: (request, env) => handle(request, env) };

export async function handle(request, env) {
    const url = new URL(request.url);
    const path = url.pathname;
    const method = request.method;

    if (path === "/v1/mixes") return method === "POST" ? createMix(request, env, url) : methodNotAllowed();
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
