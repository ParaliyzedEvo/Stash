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
