# Plan B — the lossless relay: server-side contract

**Status:** contract fixed, implementation not started.
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

## 5. Open decisions — these need you, and none are answered here

The contract above is fixed. The design below is not, and I have deliberately
not guessed:

1. **Which subscriptions back it** — Qobuz, Tidal, or both. The shipped client
   route is `/v1/qobuz/file` and speaks Qobuz track ids; a Tidal-backed pool
   needs either a translation layer or a second route and a client change.
2. **Where it runs, and the budget** — Worker vs VPS, and what you're willing to
   pay monthly. This changes the rotation and pacing design.
3. **Account rotation policy.** Your stated requirement was "smart about
   rotating the tokens we use, trying to minimize any one being favored." Needs
   a concrete rule: round-robin, least-recently-used, per-account daily budget,
   and separate roles for catalog vs playback.
4. **Abuse gating.** Nothing stops a third party from pointing their own client
   at the relay once its hostname is known. HMAC over the request, Turnstile, or
   attestation — each has a different client-side cost, and the client currently
   sends only `X-Stash-Version`.
5. **What happens at quota exhaustion.** Today arcod answers a spent allowance
   with a shared 429 (see the arcod quota gate). The relay needs its own answer,
   and per the table above it should be **503**, not 404.

Item 4 is the one that decides whether this survives contact with the internet.
