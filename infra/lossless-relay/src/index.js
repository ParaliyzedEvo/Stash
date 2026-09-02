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
/** Per D1 binding: the QOBUZ_ACCOUNTS value whose labels already have rows. One cheap batch per isolate. */
const ensured = new WeakMap();

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
    // Every label needs a D1 row BEFORE selection, or the LRU never reaches an account added to the
    // secret while the old ones are still under their caps. Once per isolate per secret value.
    if (ensured.get(env.DB) !== env.QOBUZ_ACCOUNTS) {
        await ensureAccounts(env.DB, accounts.map((a) => a.label));
        ensured.set(env.DB, env.QOBUZ_ACCOUNTS);
    }
    for (let attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
        const label = await selectAccount(env.DB, nowSec, caps);
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
