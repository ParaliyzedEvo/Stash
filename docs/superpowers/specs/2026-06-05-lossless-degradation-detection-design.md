# Lossless-source degradation detection + auto-failover — Design

**Date:** 2026-06-05
**Status:** Draft (brainstorm-approved)
**Author:** rawnaldclark + Claude

## Problem

Stash resolves FLAC via public Qobuz-DL proxies (`kennyy.com.br`, `qobuz.squid.wtf`)
behind a failover chain (`StreamSourceRegistry`: kennyy → squid → youtube; the
download path walks the same sources). When a proxy's backing Qobuz token loses
full-track entitlement it does **not** error — it returns a signed CDN URL for a
**30-second preview** (e.g. `…/file?fmt=5&profile=raw&range=20-30&…`) or a lossy
downgrade (FLAC requested, `fmt=5` MP3 returned). The app treats that URL as a
*success*, so:

- **Streaming** plays ~30s then stops/auto-advances (the user-reported symptom).
- **Downloading** saves a 30s stub (or junk).
- **Failover never fires** — a degraded source looks healthy, so the registry
  never advances to the next source.

Observed 2026-06-04: kennyy returned `401`, squid served `range=20-30` samples,
**simultaneously** (shared leaked app_id `312369995`); later squid recovered to
full FLAC while kennyy stayed `401` (they diverge and recover independently).

## Goal

Detect a degraded lossless source (preview-sample or lossless→lossy downgrade),
treat it as a resolve **failure** so the registry fails over — for **both
streaming and downloading** — and **skip the degraded source for a cooldown**
(per-source, with auto-recovery) so we don't pay a wasted round-trip per track.

Non-goals: fixing the upstream proxies; adding new lossless sources (separate
spec — this is its prerequisite); changing the failover *order*.

## Design

### 1. Detection — `LosslessUrlInspector` (pure, unit-tested)

A pure classifier over the resolved Qobuz CDN URL + requested quality tier:

- `isPreviewSample(url)` — URL contains a `range=` window marker (e.g.
  `range=20-30`). This is the primary, decisive signal.
- `isDowngraded(url, requestedTier)` — URL `fmt` is a lossy code (`5` = MP3)
  while the requested tier was lossless (FLAC). Secondary signal: the account
  lost lossless entitlement.

`isDegraded = isPreviewSample || isDowngraded`. Pure function over a string +
enum — no I/O, fully fixture-testable (sample URL, downgrade URL, healthy
`fmt=7/27` FLAC URL with `etsp`).

### 2. Wire into the shared source layer (covers both paths in one place)

Both streaming resolvers (`KennyyStreamResolver`, `QobuzStreamResolver`) and the
download path call the same method: `KennyySource`/`QobuzSource.resolveInternal`,
which builds the `SourceResult` from `getFileUrl`'s URL. Right after `getFileUrl`:

```
val data = getFileUrl(...)
if (data.url == null || inspector.isDegraded(data.url, tier)) {
    recordDegraded(sourceId)          // §4
    return null                        // looks like a normal miss → failover
}
```

Because both paths funnel through this method, **both inherit failover for
free** — the registry already advances to the next source on `null`.

### 3. Content-length / duration backstop (download path only)

Catches a sample that lacks the URL marker. Extend the existing v0.9.45
download-validation gate: after a finished download, if the probed audio
duration (`AudioDurationExtractor`) is sample-length **and** far short of the
track's known duration (e.g. < 50% of expected, or ≤ ~35s when expected ≫ that),
reject the file, record degraded for that source, and fall through to the next
source. Streaming intentionally relies on the URL marker only — no
"play-30s-then-switch."

### 4. Health-gating — per-source, independent, with canary recovery

A small per-source gate (`LosslessSourceHealthGate`, keyed by source id) holding
a `degradedUntil` timestamp:

- `recordDegraded(id)` → set `degradedUntil = now + COOLDOWN` (e.g. ~5 min).
- `isDegraded(id)` → `now < degradedUntil`. Checked **before** resolving, so a
  degraded source is skipped entirely (streaming + download).
- **Independent per source** — kennyy and squid (and future proxies) each have
  their own entry. They share an app_id so they often degrade together, but they
  recover independently (observed), so coupling would wrongly keep a recovered
  source disabled. "Both down" is handled by the chain (skip → skip → youtube);
  the only cost of independence is one extra resolve when both are down, paid
  once per cooldown.
- **Canary recovery** — when `degradedUntil` lapses, the next resolve attempt is
  a real probe: resolve a known-good canary track and confirm the URL is *full*
  (not a sample) before fully re-enabling. A plain network ping is insufficient —
  a sample-serving proxy is "up." (Kennyy already has `KennyyHealthProbe`/
  `KennyyHealthMonitor` for network health; this generalizes the concept to
  content health and extends it to squid.)

Open implementation choice (resolved here): **generalize** into
`LosslessSourceHealthGate` rather than extending only `KennyyHealthMonitor` —
squid needs one too and future proxies will. Kennyy's existing network
health-monitor stays; the new gate is the content-degradation layer.

### 5. Failover

No change — `StreamSourceRegistry` (streaming) and `registry.resolve` (download)
already advance on a `null`/miss. Detection just makes "degraded" register as a
miss, and the gate makes it a fast skip.

## Components / boundaries

| Unit | Responsibility | Depends on |
|------|----------------|------------|
| `LosslessUrlInspector` | Pure: classify a CDN URL as sample/downgrade/healthy | (none) |
| `LosslessSourceHealthGate` | Per-source `degradedUntil` + canary recovery | clock |
| `KennyySource`/`QobuzSource` | Reject degraded URL at resolve; record degraded | inspector, gate |
| Download-validation gate | Duration backstop post-download | `AudioDurationExtractor` |
| `StreamSourceRegistry` / download registry | Failover (unchanged) | sources |

## Testing

- `LosslessUrlInspector`: sample (`range=20-30`), downgrade (`fmt=5` w/ lossless
  tier), healthy (`fmt=27` + `etsp`, no `range`).
- Source layer: degraded URL → `resolveImmediate` returns `null` + `recordDegraded`
  called; healthy URL → returns a `SourceResult`.
- `LosslessSourceHealthGate`: degraded → skipped within cooldown; lapses →
  canary probe gates re-enable; **independence** — degrading kennyy does not gate
  squid.
- Duration backstop: 30s file with a 4-min expected duration → rejected + failover.
- Registry/integration: kennyy degraded → squid served → (squid degraded too →
  youtube). Streaming + download both fail over.

## Implementation notes (from spec review)

- The inspector + `LosslessSourceHealthGate` become new constructor deps of
  `KennyySource`/`QobuzSource`. `tier`/`requestedQuality` are already computed
  just above the `getFileUrl` call in `resolveInternal` — feed those to the
  inspector; the existing `download.url.isNullOrEmpty()` guard is where the
  degraded-URL early-return slots in.
- Gate insertion points (pick explicitly so it isn't double-checked or missed):
  **streaming** — the resolver / `StreamSourceRegistry` (because
  `resolveImmediate` deliberately bypasses the rate-limiter/breaker);
  **download** — the existing `isEnabled()` skip loop in `LosslessSourceRegistry`.
- Residual risk (accepted): a **streaming** sample whose URL lacks a `range=`
  marker is the only uncaught case — the duration backstop is download-only.
  Acceptable: the marker was present in the observed outage, and streaming
  deliberately avoids a fetch-and-probe to prevent "play-30s-then-switch."

## Rollout

Foundation for the separate "add more independent Qobuz proxies" work — once a
degraded source reliably registers as a miss, new sources append to the chain and
inherit detection + gating for free.
