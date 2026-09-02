# Stash Tip Jar — Cloudflare Worker

Receives Ko-fi webhook posts → persists to KV → serves the live
supporter list to the Stash Android app.

After this is deployed once, every Ko-fi tip auto-populates the Tip
Jar pill on Home with no app rebuild and no manual JSON edits.

## Architecture

```
   Ko-fi (tip received)
         │  POST webhook
         ▼
   Cloudflare Worker
         │  read+write
         ▼
   Cloudflare KV (key: "supporters")
         ▲
         │  GET (60s edge cache)
         │
   Stash app on phone
```

One Worker, one URL. POST = webhook receiver. GET = JSON endpoint the
app reads. Cloudflare's free tier (100k requests/day, 1GB KV) covers
~100,000× more than this needs.

## One-time setup (~30 min)

### 1. Create a Cloudflare account
Free at https://dash.cloudflare.com. No credit card required for the
Workers free tier.

### 2. Install Wrangler
```bash
npm install
npx wrangler login   # opens browser for OAuth
```

### 3. Create the KV namespace
```bash
npx wrangler kv:namespace create STASH_KV
```
This prints something like `id = "abc123..."`. Paste that id into
`wrangler.toml` replacing `REPLACE_WITH_NAMESPACE_ID`.

### 4. Get your Ko-fi verification token
Go to https://ko-fi.com/manage/webhooks. Copy the **Verification Token**
shown on that page.

### 5. Set the token as a Worker secret
```bash
npx wrangler secret put KOFI_VERIFICATION_TOKEN
```
Paste the token when prompted.

### 6. Deploy
```bash
npx wrangler deploy
```
Wrangler prints the deployed URL, like `https://stash-tipjar.<your-account>.workers.dev`.

### 7. Configure the Ko-fi webhook
Back at https://ko-fi.com/manage/webhooks, set the **Webhook URL** to
the Worker URL from step 6. Save.

### 8. Point the Stash app at the new URL
Edit `app/build.gradle.kts`:
```kotlin
buildConfigField(
    "String",
    "SUPPORTERS_JSON_URL",
    "\"https://stash-tipjar.<your-account>.workers.dev\"",
)
```
Rebuild the app and ship.

## Verifying it works

### Send a fake webhook locally
Ko-fi has a "Send Test Donation" button on their webhook page. Click it.
Then `curl https://stash-tipjar.<your-account>.workers.dev` — you
should see the test donation in the JSON.

### Watch live logs
```bash
npx wrangler tail
```
Shows every webhook + GET in real-time. Useful for debugging.

## Cost

Free, indefinitely, for any realistic Stash supporter volume.
- Workers: 100,000 requests/day on the free plan.
- KV: 100,000 reads/day, 1,000 writes/day, 1GB storage.

If Stash had 1,000 daily active users each opening Home twice = 2,000
Worker requests/day. KV writes happen only when a tip arrives, so
maybe 10/day at the high end. Well under every limit.

## Schema the Worker exposes

```jsonc
{
  "supporters": [
    { "name": "Cedric", "amountUsd": 10, "message": "Just downloaded..." },
    { "name": "Slowcab", "amountUsd": 5,  "message": "Amazing work!..." }
  ]
}
```

Sorted newest-first. Capped at the 20 most recent entries (bumpable
via `SUPPORTERS_LIMIT` in `src/index.js`).

## Migrating off Cloudflare

If you ever want to leave Cloudflare, the Worker code is ~50 lines and
ports to:
- **Deno Deploy**: change `env.STASH_KV.put` to Deno KV API (`Deno.openKv()`).
- **Vercel Functions**: change to a Vercel Edge function + Vercel KV.
- **Self-hosted Node/Express**: change KV calls to a redis client.

The schema the Worker exposes (the GET response shape) stays
identical, so the Stash app needs no changes when you migrate.

## Manual seeding

If you want to pre-seed the KV with existing supporters before
plugging in Ko-fi:
```bash
npx wrangler kv:key put --binding=STASH_KV supporters '{"supporters":[{"name":"Cedric","amountUsd":10,"message":"Just downloaded..."}]}'
```
Or use the Cloudflare dashboard's KV editor.

## Lossless relay config

The same Worker also serves `GET /lossless.json` and `GET /lossless.json.sig` —
the signed relay list the Stash app fetches at every cold start. They come
byte-for-byte from the KV keys `lossless_config` / `lossless_config_sig`, which
only `infra/lossless-relay/scripts/publish-config.mjs` writes. 404 until the
first publish. Nothing about supporters changes.
