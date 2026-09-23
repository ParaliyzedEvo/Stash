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
