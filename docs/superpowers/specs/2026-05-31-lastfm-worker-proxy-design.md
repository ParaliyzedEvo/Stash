# Last.fm Worker Proxy + Shared Cache — Design

**Status:** Proposed (2026-05-31)
**Problem owner:** mixes/discovery rely on Last.fm; the single shared API key
gets globally rate-limited (error 29) as the user base grows, breaking custom
Stash Mixes for everyone at once.

## Goal

**Decouple Last.fm request volume from user count.** The generic read lookups
that dominate traffic (`tag.getTopTracks`, `artist.getSimilar`,
`artist.getTopTracks`, `track.getSimilar`, `track.getTopTags`,
`artist.getTopTags`, `track.getInfo` w/o username) return the *same* answer for
every user. Today each install fetches them independently; at N users that is
~N× the calls on one key. A server-side proxy with a shared cache collapses
that to "fetch each unique query once, serve everyone" — so Last.fm sees a
bounded number of queries regardless of whether there are 10 users or 100k.

This is the durable layer beneath the two app-side mitigations already shipped
(per-device cache + per-key breaker + optional multi-key read pool). Those
reduce the slope; the proxy removes the dependency on user count entirely.

## Non-goals / what stays on the device

- **Auth + scrobbling** (`auth.getSession`, `track.scrobble`): signed with the
  shared secret and bound to a user's session. These stay **direct from the
  app** using the primary key — the secret never goes to the Worker.
- **Per-user reads** (`user.getTopArtists/getTopTracks/getLovedTracks`,
  `track.getInfo` *with* username): personal, low-volume. Keep direct from the
  app (or proxy with a per-user cache key later — not needed initially).
- **Personalization**: unchanged. The proxy only serves the generic *candidate
  pool*; seed selection, scoring, filtering all remain on-device per user
  (see MixGenerator). Same ingredients for everyone, different dish each.

## Architecture

```
app  ──(generic reads, no api_key)──►  Cloudflare Worker  ──(1 server key)──►  ws.audioscrobbler.com
                                          │
                                          └─ Cache API / KV  (keyed by normalized method+params)
app  ──(auth + scrobble, signed w/ primary key)────────────────────────────►  ws.audioscrobbler.com  (direct, unchanged)
app  ──(user.* reads)──────────────────────────────────────────────────────►  ws.audioscrobbler.com  (direct, unchanged)
```

### Worker endpoint

Single generic passthrough, method-allowlisted:

```
GET https://<worker-host>/lastfm?method=<m>&<params...>
```

Worker behavior:
1. Reject any `method` not in the **read allowlist** (the generic methods
   above). No `api_key`, `sk`, `api_sig`, `user`, or `username` accepted —
   reads only, no per-user, no signed.
2. Build the canonical cache key = sorted (method + allowed params), excluding
   anything client-supplied that isn't part of the allowlist. (Mirrors the
   app's existing `lastFmCacheKey`.)
3. Cache lookup (Cloudflare **Cache API** for simplicity, or **KV** for
   cross-colo sharing). On hit within TTL → return cached JSON.
4. On miss → call Last.fm with the **Worker's own server-side api_key**, store
   the body with `Cache-Control: public, max-age=<TTL>`, return it.
5. On Last.fm error 29 at the Worker → return a 429 with `Retry-After`; the app
   already has a per-key breaker + on-device cache to ride it out. (At steady
   state this should be rare: the Worker makes at most one call per unique
   query per TTL window.)

TTL: 7–30 days (these charts move slowly). Start at 14d; tune later.

### Why this scales

Total Last.fm calls ≈ (number of *distinct* tag/artist/track queries across all
users) ÷ (TTL windows), **independent of user count**. A few thousand popular
tags/artists cached centrally covers the long tail. The Worker's single key
stays well under the limit permanently.

## App-side change (small)

Add an optional proxy base URL, plumbed exactly like the existing Last.fm
config:

- `local.properties`: `lastfm.proxyUrl=https://<worker-host>/lastfm`
  → `BuildConfig.LASTFM_PROXY_URL` → injected into `LastFmApiClient`.
- In `LastFmApiClient.unsignedGet`, when `cacheable == true` **and** a proxy URL
  is set: target the Worker (omit `api_key`) instead of `ws.audioscrobbler.com`.
  Everything else — the on-device cache check, the per-key breaker, response
  parsing — stays as-is and acts as a second-layer fallback.
- When no proxy URL is set: current behavior (direct + multi-key pool). So this
  is a drop-in, reversible toggle.

Net app diff: ~1 new BuildConfig field + a base-URL branch in `unsignedGet`.
The on-device cache becomes a fast L1 in front of the Worker's L2.

## Security & ops

- **Secret stays server-side.** The Worker holds one Last.fm api_key (+ secret
  if ever needed); the APK no longer needs to ship a read key once the proxy is
  the default. (Keep the on-device key as a fallback during rollout.)
- **Abuse protection** on the Worker: per-IP rate limit + method allowlist so it
  can't be turned into an open Last.fm proxy. Optional shared bearer/header the
  app sends (low-value, since it ships in the APK, but raises the bar).
- **Cost:** Cloudflare Workers free tier (100k req/day) + Cache API/KV is almost
  certainly free at current scale, since cache hits don't count as Last.fm calls
  and the Worker is hit at most once per unique query per TTL. Reuses the same
  Cloudflare account already running the tip-jar Worker.

## Rollout

1. Deploy the Worker (allowlist + cache + server key). Verify a few queries
   return cached on the second hit.
2. Set `lastfm.proxyUrl` in debug, route reads through it, confirm mixes still
   populate and Last.fm direct-traffic drops to ~0 for generic reads.
3. Ship with the proxy URL set; keep the on-device key as fallback if the
   Worker is unreachable (the breaker/cache already degrade gracefully).
4. Once stable, the app no longer needs a bundled read key at all.

## Relationship to shipped work

- On-device cache (`lastfm_response_cache`) → becomes L1 in front of the Worker.
- Per-key breaker + multi-key read pool → still useful for any direct reads and
  as fallback when the proxy is unset/unreachable.
- `lastFmCacheKey` (api_key-independent) → the Worker's cache key mirrors it.

## Open questions

- Cache API vs KV: Cache API is per-colo (simpler, free); KV is global
  (consistent hit rate across regions, tiny cost). Start with Cache API.
- Stale-while-revalidate to hide the occasional cold-miss latency? Optional.
- Should `user.*` eventually be proxied with per-user TTL'd cache to cut the
  ~10 persona calls/refresh too? Low priority; revisit if those become a factor.
