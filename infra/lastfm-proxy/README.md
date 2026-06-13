# Stash Last.fm Proxy (Cloudflare Worker)

Proxies the **generic, cross-user** Last.fm read lookups that power Stash Mixes
(`tag.getTopTracks`, `artist.getSimilar`, etc.) through **one server-side API
key** with an **edge cache**. Each unique query is fetched from Last.fm at most
once per TTL and served to every user from cache — so the key never hits the
per-application rate limit (error 29), no matter how many app installs there
are. This is the durable fix for the shared-key throttling that breaks custom
mixes. Design: [`../../docs/superpowers/specs/2026-05-31-lastfm-worker-proxy-design.md`](../../docs/superpowers/specs/2026-05-31-lastfm-worker-proxy-design.md).

**Does NOT proxy** auth/scrobble (signed; stay on the app's primary key) or
per-user reads (`user.*`, `track.getInfo` w/ username). It rejects any
`api_key`/`api_sig`/`sk`/`user`/`username` param. Personalization is unaffected
— the proxy only serves the generic candidate pool; the app scores/filters it
per user.

## Endpoint

```
GET https://stash-lastfm-proxy.<your-subdomain>.workers.dev/lastfm?method=<m>&<params>
```

Example:
```
/lastfm?method=tag.gettoptracks&tag=shoegaze&limit=50
```
Returns the raw Last.fm JSON. `X-Stash-Cache: HIT|MISS|BYPASS` header shows cache
status. `429` (with `Retry-After`) if upstream is rate-limited.

## Deploy

Prereqs: Node 18+, a Cloudflare account (the same one running `stash-tipjar`).

```bash
cd infra/lastfm-proxy
npm install

# 1. Log in (opens browser once)
npx wrangler login

# 2. Set the server-side Last.fm key as a secret (NOT committed).
#    Use a DEDICATED key for the proxy — register a fresh one at
#    https://www.last.fm/api/account/create — so proxy traffic is isolated
#    from any key the app still ships.
npx wrangler secret put LASTFM_API_KEY
#    (paste the key when prompted)

# 3. Deploy
npx wrangler deploy
```

`wrangler deploy` prints the live URL. Smoke-test it:
```bash
curl "https://stash-lastfm-proxy.<subdomain>.workers.dev/lastfm?method=tag.gettoptracks&tag=techno&limit=2"
# run twice — second response should show  X-Stash-Cache: HIT
```

## Local dev

```bash
npx wrangler dev          # runs locally
# in another shell, set a key for the local session if needed, then:
curl "http://localhost:8787/lastfm?method=artist.getsimilar&artist=radiohead&limit=2"
```

## Wire the app to it

In `local.properties` (and the release secret), point the app at the Worker:
```
lastfm.proxyUrl=https://stash-lastfm-proxy.<subdomain>.workers.dev/lastfm
```
The app routes only its **generic read** lookups through the proxy (omitting its
own api_key); auth/scrobble and per-user reads stay direct. The on-device cache
and per-key breaker still apply as a fast L1 / fallback. Leave `lastfm.proxyUrl`
empty to disable (app talks to Last.fm directly).

## Tests

```bash
npm test    # node --test — covers cache-key normalization, error parsing, allowlist
```

## Tuning

- `CACHE_TTL_SECONDS` in `src/index.js` (default 14 days). These charts move
  slowly; raise it to cut upstream calls further.
- `ALLOWED_METHODS` — the read methods the proxy will serve. Keep it minimal.
