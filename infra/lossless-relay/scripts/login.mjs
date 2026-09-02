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
