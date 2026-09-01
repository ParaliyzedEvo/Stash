# Plan B — the lossless relay: server-side contract

**Status:** contract fixed (§1-4), design decisions settled 2026-09-01 (§5), implementation not started.
**Derived from shipped client code, not proposed.** Every requirement below is
already enforced by `LosslessRelayClient` / `LosslessConfigFetcher` on devices
running Plan A1. A relay that violates one of these does not "degrade" — it gets
cooled, skipped, or ignored, usually silently. Read this before writing a line
of server code.

Sources of truth:
- `data/download/src/main/kotlin/com/stash/data/download/lossless/relay/LosslessRelayClient.kt`
- `data/download/src/main/kotlin/com/stash/data/download/lossless/relay/LosslessConfigFetcher.kt`
- `data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxFileUrlRouter.kt`

---

## 1. The mint endpoint

```
GET {base}/v1/qobuz/file?track_id=<long>&format_id=<int>
X-Stash-Version: 1
Accept: application/json
```

`base` is whatever the config or the user's custom-endpoint field supplies,
already normalised (no trailing slash). The client appends the path verbatim.

### Success — 200

```json
{
  "url": "https://cdn.example/...",
  "format_id": 7,
  "bit_depth": 24,
  "sample_rate": 96000
}
```

Hard requirements, each one enforced:

| Field | Rule | What happens if you get it wrong |
|---|---|---|
| `url` | **must** start with `https://` | plaintext or missing → base cooled **5 min**, treated as sick or MITM'd |
| `sample_rate` | **Hz, not kHz** — you convert Qobuz's kHz, the client never multiplies | 96 instead of 96000 surfaces as a nonsense quality badge |
| `format_id` | omit it and it decodes to `0`, which reads as **region-locked** downstream | client defensively echoes the requested id, but don't rely on that |
| `bit_depth` | plain int | — |

Unknown keys are ignored, so the response may carry extra fields safely.

### The three failure statuses — they are not interchangeable

| Status | Meaning | Client behaviour |
|---|---|---|
| **404** | not streamable / region-locked for this relay's accounts | **`NoMatch` ends the router for this track.** Moves to the next lossless *source*, **not** the next relay base — every base fronts the same catalog. No cooldown; the base is considered healthy. |
| **503** | busy / at capacity | base cooled **60 s**, try next base |
| anything else non-2xx | broken | base cooled **5 min**, try next base |
| unreachable, or 200 headers then a stalled body | sick | base cooled **5 min** |

**The 404-vs-503 distinction is the one to get right.** Returning 404 for
"my accounts are rate-limited right now" permanently gives up on that track for
that play attempt instead of failing over. Returning 503 for a genuine catalog
miss makes the client wait 60s and retry something that will never exist.

**Never return 200 with an error payload.** A 200 whose body has no usable
`https://` url cools the base for 5 minutes — the harshest penalty in the table.

### Timeouts

The client's connect/read/call timeouts are **8 seconds** each. A mint that
routinely takes longer is functionally offline: it cools for 5 minutes on every
attempt. Whatever upstream work minting requires must fit inside 8s or be
pre-warmed.

---

## 2. The status endpoint

```
GET {base}/v1/status
X-Stash-Version: 1
```

Used only by the Settings "Test" button. **Any HTTP reply counts as success —
including 404.** It answers "did the user typo the host", not "is the relay
healthy". The client reads no body and does not touch cooldowns. Serving
something meaningful here is optional; being reachable is not.

---

## 3. The signed config

Devices ship **no relay hostname**. They fetch two files:

```
GET <LOSSLESS_CONFIG_URL>          -> the JSON below
GET <LOSSLESS_CONFIG_URL>.sig      -> base64 ECDSA P-256 (SHA256withECDSA)
                                      over the exact JSON bytes
```

```json
{
  "v": 1,
  "relays": [
    { "base": "https://relay-a.example", "priority": 1 },
    { "base": "https://relay-b.example", "priority": 2 }
  ],
  "updated_at": 1788220800
}
```

Relays are sorted by `priority` ascending and tried in that order. The device
verifies the signature against `LOSSLESS_CONFIG_PUBKEY` (X.509, base64) baked in
at build time.

### Publishing rules — these bite

1. **`updated_at` must increase monotonically, forever.** The device keeps a
   rollback floor: a config stamped older than the cached one is **rejected
   permanently**. Re-publishing a known-good older file after a bad push does
   **not** work — it needs a fresh, higher `updated_at`.
2. **Signature is over the exact bytes served.** Any re-encoding, whitespace
   normalisation, or CDN transformation between signing and serving breaks
   verification, and the device silently keeps its cached config.
3. **64 KiB cap.** A larger body is rejected. (This cap exists because an
   unbounded read throws an `Error`, not an `Exception`, which escapes the catch
   and crash-loops the app at cold start. Do not remove it.)
4. **A signed `{"relays": []}` is the kill switch** — the clean way to take all
   relays out of service.
5. Serve from a **CDN-fronted static host**. The shared OkHttp client has no
   disk cache, so ETag/Cache-Control only help at the edge. In practice this is
   one fetch per cold start (process death beats the 6h timer).

Failure is fail-safe by design: bad signature or network failure keeps the
cached copy; no cache means no relays, and lossless falls back to the user's own
account or custom endpoint.

---

## 4. The non-negotiable architectural constraint

**Audio bytes must never transit the relay.** It mints a signed CDN URL and gets
out of the way; the device streams from the CDN directly. This is what keeps
bandwidth costs bounded, keeps the relay off the critical path of playback, and
keeps it from being the thing that gets noticed. The client is already built for
this — it hands `url` straight to the media source factory with no auth header,
so the URL must be self-authenticating and short-lived.

---

## 5. Decisions (settled 2026-09-01)

### 5.1 Catalog: **Qobuz only**

The shipped client route `/v1/qobuz/file` speaks Qobuz track ids and stays as-is.
No translation layer, no second route. Tidal is deliberately out of scope — the
DRM-free DASH FLAC work on the parked branch stays parked.

### 5.2 Hosting: **Cloudflare Worker + D1**, not a VPS

Budget direction was "as minimal as possible", and this is the minimal answer
that is also the *reuse* answer: the Cloudflare account, the
`rawnaldclark.workers.dev` subdomain, a KV namespace, and Worker deploy
automation already exist in `infra/tipjar-worker/`. The relay is a second Worker
beside it, not new infrastructure.

Cost: **$0** on free tier (100k requests/day, D1 free tier). Workers Paid at
$5/mo only if those limits are actually hit.

The usual objection to Workers — no long-lived process — does not apply here.
Qobuz minting is stateless HTTP plus MD5 request signing, and `getFileUrl`
returns a CDN URL directly. Audio bytes never transit the relay (§4), so there
is nothing to keep a socket open for. A VPS would buy a box to patch, a bill,
and an attack surface, in exchange for capability this design does not use.

State lives in **D1** (accounts, budgets, cooldowns) rather than KV: the
rotation query in 5.3 wants ordering and an atomic update, which is SQL's job.

### 5.3 Rotation: **least-recently-used with a per-account hourly cap**

Requirement was "minimize any one being favored." One D1 statement satisfies it:
select the live account with the oldest `last_used_at` whose usage this hour is
under `budget_per_hour`, and stamp it in the same write. No counters to keep in
sync, no coordination primitive, correct under concurrent Worker invocations.

An account that errors goes `cooling` with a `cooling_until`; the next request
simply doesn't select it. v1 draws catalog and playback from one pool — the
`role` column stays in the schema so the split can happen later without a
migration.

### 5.4 Abuse gating: **HMAC over the request, with the key delivered in the signed config**

The threat is real and specific: the relay's hostname reaches every device via a
config any client can fetch, so without gating a third party can point their own
client at it and spend the subscriptions.

The decision that matters is **where the key lives**. Not baked into the APK —
that is the same extractable-secret shape Plan C just spent 1,800 deleted lines
removing, and a baked key cannot be changed without a release. Instead the key
ships **inside the existing ECDSA-signed `lossless.json`**, which devices already
fetch and verify on every cold start.

That reuses a channel that is already built, already signed, and already
tamper-proof, and it buys the property that actually matters: **rotation without
a release.** If a key leaks, publish a new config; the old key is dead within one
cold start, and the `{"relays": []}` kill switch is still there as the bigger
hammer.

Request shape: `X-Stash-Auth: <hex HMAC-SHA256(key, "<track_id>:<format_id>:<unix_ts>")>`
plus `X-Stash-Ts`, with the relay rejecting a timestamp outside ±5 minutes so a
captured header is not replayable forever. Per-IP and global daily request caps
sit in front of everything.

**This is a client change** — `LosslessRelayClient` sends only `X-Stash-Version`
today. It must land in a shipped release *before* a live relay depends on it.

Honest limit: a determined attacker can extract the key from a config fetch, the
same as any client-side secret. This does not make abuse impossible, it makes it
a treadmill that costs them re-extraction on every rotation and costs us one
publish. Per-install registration (relay issues an id + secret on first contact,
revocable individually) is the documented upgrade if that treadmill ever stops
being enough — the wire format above accommodates it without another redesign.

### 5.5 Quota exhaustion: **503, never 404**

Per §1 this is the distinction that silently breaks playback, so it is stated
flatly:

- **A single account exhausted or erroring** → mark it `cooling`, select the next
  account. The client never learns; this is internal.
- **Every account exhausted, or the global daily cap hit** → **503** with
  `Retry-After`. The client cools that base 60s and moves on, which is the
  intended failover.
- **404 is reserved strictly for a genuine catalog miss or region lock** — the
  track does not exist for these accounts. Nothing about capacity, load, or
  billing may ever answer 404, because 404 ends the router for that track
  entirely rather than failing over.

A global daily cap is what bounds cost. Pick the number when the Qobuz account
count is known; enforce it in the same D1 write as 5.3.

---

## 6. What is still open

- **The monthly budget number.** Deferred deliberately — the design above is
  free-tier-shaped, so this only becomes a question if usage exceeds it.
- **How many Qobuz accounts back the pool**, which sets the global daily cap.
