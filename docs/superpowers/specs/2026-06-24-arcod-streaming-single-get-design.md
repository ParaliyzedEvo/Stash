# ARCOD streaming — single-GET stream resolver — Design

**Date:** 2026-06-24
**Status:** Design (approved in brainstorm; spec review passed 2026-06-24)
**Branch:** `feat/arcod-streaming-single-get` (off master @ v0.9.57)

## Summary

Replace the slow job-render flow inside **`ArcodStreamResolver.resolve()`** with the
arcod dev's new **single stream-URL GET**. Today a tapped ARCOD stream does
`search → create-job → poll-until-completed (up to 60s) → open download URL`. The
new endpoint hands back a playable URL in one request, so streaming drops from a
multi-second render-and-poll round-trip to `search-GET + stream-GET`.

**Streaming only.** The download path (`ArcodSource`, `ArcodClient.createJob`/
`pollStatus`) is **unchanged** — downloads still use the job flow (max-quality
archival FLAC). ARCOD stays **last among the lossless streaming sources** and
**foreground-only**, exactly as the pipeline is ordered today (kennyy → squid →
arcod → amz → youtube). No re-ordering.

This is a latency/quality improvement to an existing, shipped source — not a new
source. The catalog, auth, matcher, and registry slot are untouched.

## The new endpoint (from the dev, 2026-06-23)

```
GET <ARCOD_STREAM_BASE>/{trackId}?quality={code}
Authorization: Bearer <user Supabase JWT>
```

Dev's notes, verbatim points:
- Returns **a URL** — "drop that straight into your player and it plays."
- `quality`: `27` = Hi-Res (default), `6` = FLAC CD. (These are Qobuz `format_id`
  codes; they pass through verbatim. Our `LosslessQualityTier` maps CD=6,
  HI_RES=7, MAX=27 — all accepted; an unspecified/unknown code "defaults to 27".)
- The URL is **short-lived** — request it when the user actually hits play, not
  ahead of time. (Matches the resolver, which already resolves on demand.)
- **Login required** — every call needs the user's token.
- **Range/seeking works**, and the underlying Qobuz URL never leaks (everything is
  proxied through arcod).
- Limits are "wide open for now" (test phase); the dev will add limits later.

### Host & auth — already covered, verified

The endpoint is on the **`api.arcod.xyz`** host (the `/api` segment is *not* part
of the path — it's `…<ARCOD_STREAM_BASE>/…`, distinct from the existing
`arcod.xyz/api/...` calls). Two things were verified against current code:

- `ArcodAuthInterceptor.HOSTS = setOf("arcod.xyz", "api.arcod.xyz")` already
  includes `api.arcod.xyz`, so the Bearer (with refresh-on-expiry + retry-once-on-401)
  attaches to the new call with **no auth-plumbing change**.
- The interceptor lives only on `ArcodClient`'s derived OkHttp client, so adding
  another method to that client inherits the same auth automatically.

### Response shape — the one unverified unknown

The dev said "you get back a url" but gave no sample body. "Drop straight into your
player" rules out a 302 redirect (OkHttp would auto-follow it and yield bytes, not
a URL). That leaves two live possibilities, handled by a **tolerant parser** rather
than a blocking probe:

1. A bare URL as the plain-text body (`https://…`).
2. JSON — either a flat `{url|streamUrl|downloadUrl}` **or** arcod's usual
   `{success, data:{…}}` envelope (every other arcod endpoint wraps payloads this
   way), optionally carrying an `expiresIn` (the download URL flow returns
   `expiresIn:300`).

The parser is confirmed against the live shape on the first on-device run via a
one-time raw-body debug log (below). The `ArcodUrlResponse{downloadUrl, fileName,
expiresIn}` model already in `ArcodApiModels.kt` is a ready reference shape.

## Changes

### 1. `ArcodClient` — add `streamUrl(trackId, qualityCode)`

A new suspend method alongside the **unchanged** `search` / `createJob` /
`pollStatus` / `downloadUrlFrom` (downloads keep those):

- New test seam: `internal var streamBaseUrl = "https://api.arcod.xyz"` (separate
  from `baseUrl = "https://arcod.xyz/api"` — different host, no `/api` segment).
- Issues `GET $streamBaseUrl<ARCOD_STREAM_BASE>/$trackId?quality=$qualityCode` where
  `trackId` is the `Long` Qobuz track id (`ArcodTrackItem.id`) and `qualityCode`
  is the Qobuz format_id int.
- Reuses the existing `arcodRequest()` builder (UA / Origin / Referer). The Bearer
  is auto-attached by the interceptor (host is in `HOSTS`).
- Runs on `Dispatchers.IO`, consistent with the other client methods.
- **Tolerant parse** via a small private helper `parseStreamUrl(body): ParsedStream?`
  that returns the URL plus optional `expiresIn`:
  - Trim the body; if it starts with `http://`/`https://`, treat the whole body as
    the URL (no `expiresIn`).
  - Otherwise JSON-decode and look for a URL field at the **top level**
    (`url`/`streamUrl`/`downloadUrl`) **and** one level of nesting under `data`
    (`data.url`/`data.streamUrl`/`data.downloadUrl`); pick up an `expiresIn` (or
    `data.expiresIn`) if present.
  - On a parse miss, log the raw body once at debug (`Log.d`) so first bring-up
    pins the exact shape, and return null.
- `429 → ArcodRateLimitedException` (consistent with the other methods); any other
  non-2xx or unparseable body → null; `CancellationException` re-thrown.

### 2. `ArcodStreamResolver.resolve()` — swap the tail

- **Keep:** build `TrackQuery` → `client.search("<artist> <title>")` →
  `ArcodMatcher.best(query, items)` → `item` + `item.id`.
- **Drop:** the `item.album?.id` null-gate (the GET needs only `trackId`, so a
  match whose album id is null now resolves too); the entire
  `jobGate.withJob { createJob → pollStatus → downloadUrlFrom }` block; and the
  `ArcodJobGate` constructor dependency.
  - *Why dropping the gate is safe:* the gate serialized slow render jobs
    app-wide. A single GET creates no render job, and ARCOD is already
    foreground-only via `StreamSourceRegistry`'s `allowYtDlp` gate (never runs on
    the speculative background fill), so concurrency is already bounded to the
    current + next-up track. `ArcodJobGate` stays in the codebase — `ArcodSource`
    (downloads) still uses it; only the resolver stops injecting it.
- **Add:** inject `StreamQualityPolicy` (same module, mirrors `AmzStreamResolver`);
  compute `val code = qualityPolicy.streamingTier().qobuzCode` and pass it to
  `client.streamUrl(item.id, code)`.
  - This also fixes a latent bug: the current job request hardcodes `quality = 27`,
    so streaming ignores the user's per-network / Save-Data tier. Feeding
    `streamingTier()` in makes ARCOD streaming respect cellular/Wi-Fi/Save-Data
    like kennyy/squid/amz already do.
- **TTL:** if the parse yielded an `expiresIn` (seconds), use
  `expiresAtMs = now + expiresIn*1000 − 20s` safety margin; otherwise fall back to
  the existing conservative `280s`. (Prevents `StreamUrlCache` from replaying a
  URL that has already expired server-side.)
- **Return:** `StreamUrl(url, expiresAtMs, codec="flac", origin="arcod",
  coverArtUrl = item.album?.image?.large?.takeIf { it.isNotBlank() })`, unchanged
  in shape. New constructor: `(client, qualityPolicy)`.
- Error handling unchanged: any failure → null → registry advances to amz/youtube
  (re-throw `CancellationException` before `catch(Exception)`).

### 3. Registry / DI — no change

`StreamSourceRegistry` already wires `ArcodStreamResolver` after squid, before amz,
in the normal branch only (and in the `forceArcodOnly` test toggle). The resolver's
public `resolve(track): StreamUrl?` shape is unchanged, so no registry edit. The
only DI delta is the resolver's own constructor params (drop `ArcodJobGate`, add
`StreamQualityPolicy` — both already provided by Hilt).

`StashMediaSourceFactory` needs no change: its refresh chain is gated by the
`streamingTrackId(mediaItem)` predicate (non-null only for YouTube http(s) items),
and amz is routed through its own authed `OkHttpDataSource` branch — an
`"arcod"`-origin URL matches neither, so it plays via the **default** factory, and
the dev confirms the returned URL is open + Range-capable, exactly what the default
factory expects.

## Testing strategy

- **`ArcodClientTest`** (MockWebServer, mirroring the existing setup which points
  `baseUrl` at the mock server and builds the interceptor over a null session):
  - `streamUrl` issues `GET <ARCOD_STREAM_BASE>/{id}?quality={code}` (assert method, path,
    query) with `streamBaseUrl` pointed at the mock server.
  - Parses a **plain-text URL** body.
  - Parses a **flat JSON** body (`{"url":…}`) and an **enveloped JSON** body
    (`{"success":true,"data":{"url":…,"expiresIn":…}}`), surfacing `expiresIn`.
  - `429 → ArcodRateLimitedException`; non-2xx → null; unparseable → null.
  - **Not** asserting the Bearer header — infeasible at this layer (the interceptor
    skips non-`HOSTS` localhost and the test session is null); Bearer attachment is
    already covered by `ArcodAuthInterceptorTest`.
- **`ArcodStreamResolverTest`** (replace the job-flow mocks):
  - Mock `client.streamUrl(...)` + `qualityPolicy.streamingTier()`; assert the
    tier's `qobuzCode` is threaded through to the client call (e.g. CD→6, HI_RES→7,
    MAX→27).
  - A match with **no album id** still resolves (album gate removed).
  - Empty search / no match / null stream URL → null.
  - Success → `StreamUrl(origin="arcod", codec="flac")` with the expected TTL
    (derived from `expiresIn` when provided, else 280s).
  - `ArcodJobGate` is no longer referenced.

## On-device verification

1. **(Make-or-break) The returned media URL plays through the default factory with
   no Bearer.** ExoPlayer's default `DataSource` does not send the arcod token. If
   the stream URL is a self-authenticating signed URL (expected — matches the open
   `dl.arcod.xyz` download URLs), it plays. If it 401s, the URL needs the header and
   we escalate to a header-injecting `DataSource` plus a routing branch in
   `StashMediaSourceFactory` analogous to the existing amz `OkHttpDataSource` branch
   (named fallback, not built in v1). Force-arcod-only toggle + logcat to confirm
   audible playback.
2. Confirm a **HI_RES-tier** user (code `7`) streams audibly (the dev cited only
   `6`/`27`; `7` is expected-good but unverified).
3. Confirm **seeking** late in a track still works (Range + URL still valid).
4. Confirm the first raw-body debug log matches the parser's assumptions; tighten
   the parser if the live shape differs.
5. Confirm failover to amz/YouTube when ARCOD misses or the stream GET fails.

## Now Playing quality display (added after on-device verification)

On-device verification confirmed arcod streams true hi-res (Daft Punk *RAM*
decoded at 88.2 kHz at `quality=27`; a CD-only master like Melvins *Houdini*
correctly stayed 16/44.1). But Now Playing showed only bare "FLAC" for arcod,
because — unlike `KennyyStreamResolver`, which sets `bitsPerSample`/`sampleRateHz`
from its proxy's delivered-format metadata — `ArcodStreamResolver` left those null.

The single stream GET returns only a URL (no format), so the delivered quality is
**derived** from the search item's catalog maximums clamped to the requested tier
(the Qobuz contract: delivered = master clamped to the tier ceiling). No extra
network call.

- Tier ceiling by `qobuzCode`: `6`→(16-bit, 44100 Hz); `7`→(24-bit, 96000 Hz);
  `27`→(24-bit, 192000 Hz); any other code → treat as `27` (matches the dev's
  "unknown defaults to 27").
- Catalog max from the matched `ArcodTrackItem`: `maximum_bit_depth` (already
  modeled as `maxBitDepth`) and `maximum_sampling_rate` (kHz, **newly modeled** —
  e.g. `88.2`). `sampleRateHz = round(maxSamplingRate * 1000)`.
- `bitsPerSample = min(maxBitDepth, ceilingBits)`; `sampleRateHz =
  min(catalogRateHz, ceilingRateHz)`. Either is null (→ bare "FLAC", today's
  behavior) when its catalog field is absent.
- `bitrateKbps` stays null — FLAC is variable and the catalog item carries no
  bitrate; bit-depth/sample-rate is the lossless indicator the UI shows.

This matches the measured reality: Max + 24/88.2 master → "24-bit/88.2 kHz";
CD tier or a 16/44.1 master → "16-bit/44.1 kHz".

## Out of scope (v1)

- Any change to the ARCOD **download** path (job flow stays).
- Reading the *actual* decoded format from ExoPlayer to display (bigger, all-source
  change); the catalog-derived value is accurate for the deterministic Qobuz tiers.
- Re-ordering or promoting ARCOD out of foreground-only/last (deliberately kept as
  the pipeline is explained to the dev).
- A header-injecting `DataSource` (only built if on-device step 1 fails).
- Honoring a rate limiter / health gate on the stream path when the dev adds limits
  later (revisit then; today limits are "wide open"). Note: a stream-GET `429`
  currently surfaces as `ArcodRateLimitedException` → caught by the resolver's
  generic `catch (Exception)` → logged as an ordinary "resolve failed" → failover.
  Correct for now, but once the dev adds limits a 429 storm will read as plain
  misses in logs — add distinct 429 logging/back-off when revisiting this.
- Caching the matched `trackId` to skip the search GET (the search stays).
