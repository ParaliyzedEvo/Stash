# YouTube-Fallback Streaming Fix — Design

**Date:** 2026-06-07
**Status:** Design (pending review)
**Scope:** Fix YouTube-fallback *streaming* (not downloads, not lossless). Three user-reported symptoms + a quality bug, all traced to one architectural cause plus a dead fast-path.

---

## Problem statement (user-reported)

1. **Shallow queue.** When streaming a mix with zero downloaded tracks via YouTube fallback, only ~2 tracks are ever in the queue — the next loads only after the current has played for minutes.
2. **Skips streamable tracks.** A mix mixing downloaded + streamable tracks skips every streamable track and jumps to the downloaded ones.
3. **Slow.** Tap-to-play on a YT-fallback track takes 10–20 s.
4. **(Found during diagnosis) Quality.** YT fallback effectively serves 128 k AAC, not the best free format.

## Root cause (measured, not theorized)

On-device `LATDIAG` capture (Pixel 6 Pro, v0.9.48 debug, force-YT on) proved:

- **The InnerTube fast path is 100 % dead.** Every track returns `status=UNPLAYABLE` / no direct URL across all three configured client variants (`ANDROID_VR`, `IOS`, `WEB_REMIX`), falling through to **yt-dlp at ~11 s every time**, serialized at `YTDLP_CONCURRENCY = 1`.
  - `prefetch-next-end dt=47970ms` — the one-ahead prefetch took 48 s queued behind the foreground track on yt-dlp's single slot. The queue physically cannot keep up at 11 s/track on one slot.
- **Background fill hard-excludes YouTube** (`PlayerRepositoryImpl.setQueue` calls `fillQueueAppend/Prepend(..., allowYouTube = false)` at ~lines 402–403). For an all-YT queue it resolves nothing and drops every track → single-item timeline (symptom 1). For a mixed queue it keeps only downloaded/lossless tracks → ExoPlayer races through the downloaded skeleton (symptom 2).

### Why the fast path died — and the fix

A no-rebuild spike (direct `POST /youtubei/v1/player` with current yt-dlp client configs) established:

- Our `IOS` variant uses the **wrong host** (`music.youtube.com`; must be `www.youtube.com`) and a **stale version** (`19.45.4` → current `21.02.3`). Fixed, the **iOS client returns 5 direct, unciphered audio formats in <1 s**, including **Opus itag 251 (~160 k)**.
- `ANDROID_VR` and `TVHTML5` are now `LOGIN_REQUIRED` (YouTube 2026 PO-token rollout / android_vr March-2026 regression) — dead weight in the audio order.

### Quality ceiling (measured across 8 diverse tracks, 2 clients)

Every track tops out at **itag 251 Opus (~130–170 k VBR)**. **itag 141 (256 k AAC) is Premium-entitlement-gated and never appears for a free account** — confirmed absent in all probes. Therefore:

- **The honest free ceiling is Opus 251 (~160 k).** 256 k requires paid Premium or the lossless Qobuz source (the deferred FLAC path).
- Today the app serves **128 k AAC**: the InnerTube selector picks by raw bitrate (AAC 132 k wrongly beats Opus 130 k), and the *download* format string is `141/140/251/...` (AAC 140 before Opus 251). The fix is therefore a genuine **128 k AAC → 160 k Opus upgrade**, not a downgrade from an imagined 256.

---

## Design

Three components. Component 1 is the dominant win (speed + quality); Component 2 delivers the deep/correct queue on top of it; Component 3 is small resilience polish.

### Component 1 — Resurrect the iOS fast lane, Opus-preferred

**Files:** `data/ytmusic/.../InnerTubeClient.kt`, `data/download/.../preview/PreviewUrlExtractor.kt`, `core/media/.../streaming/YouTubeStreamResolver.kt`.

1. **Per-variant host.** Give `InnerTubeVariant` a host (`www.youtube.com` for `IOS`; `music.youtube.com` stays for `WEB_REMIX`). The **player** call uses the variant's host; **search/library** (WEB_REMIX) are unchanged. Must not regress the working search path.
2. **Update `IOS`** to clientVersion `21.02.3`, matching user-agent, `osName="iPhone"`, `osVersion="18.3.2.22D82"`, `deviceModel="iPhone16,2"`.
3. **`AUDIO_VARIANT_ORDER = [IOS]`** as the fast lane (optionally `ANDROID` as a secondary fast variant — it also returns direct Opus in probes). **Remove `ANDROID_VR`, `TVHTML5`, `WEB_REMIX`** from the *audio* order (login-walled / ciphered → wasted round-trips). WEB_REMIX remains for non-player endpoints.
4. **Opus-preferred selection** in `extractViaInnerTube`: replace `sortedByDescending { bitrate }` with "pick the highest-bitrate **Opus (itag 251)** direct format; else highest-bitrate AAC." Fixes the AAC-over-Opus bug and targets YouTube's max.
5. **yt-dlp stays the Tier-2 backstop** for the rare iOS miss (age-restricted, future PO-token walls). The existing `extractViaYtDlpForRetry` (ExoPlayer-rejects-throttled-URL → re-extract via yt-dlp) is retained. yt-dlp's `FORMAT_SELECTOR` already prefers `251` — unchanged.
6. **Metadata honesty (minor):** thread the chosen codec/bitrate out of the InnerTube path so `YouTubeStreamResolver` reports `codec="opus"`/actual kbps instead of the hardcoded `"aac"` (improves the Now-Playing format label). Optional within this component; no behavior risk.

**Outcome:** ~0.5 s direct-URL resolution for the common case, fully parallelizable, at YouTube's best free quality (160 k Opus).

### Component 2 — Stop starving the queue (fast/slow YouTube split)

**Files:** `core/media/.../PlayerRepositoryImpl.kt`, `core/media/.../streaming/StreamSourceRegistry.kt`, `core/media/.../streaming/YouTubeStreamResolver.kt`, `data/download/.../preview/PreviewUrlExtractor.kt`.

Today a single `allowYouTube: Boolean` gates *all* of YouTube. Split it so the **fast InnerTube lane** and the **slow yt-dlp lane** are gated independently:

- Thread an `allowYtDlp` (slow-lane) flag alongside `allowYouTube` from `PlayerRepositoryImpl` → `StreamSourceRegistry.resolve` → `YouTubeStreamResolver.resolve` → `PreviewUrlExtractor.extractStreamUrl(videoId, allowYtDlp)`. When `allowYtDlp = false`, the race runs **InnerTube only** (no yt-dlp arm) and returns null if InnerTube fails.
- **Background fill** (`fillQueueAppend/Prepend`): `allowYouTube = true, allowYtDlp = false`. The whole queue resolves in order via the iOS fast lane (cap-8 parallel) → **no dropped streamable tracks (fixes symptom 2), full deep timeline (fixes symptom 1)**. Tracks where iOS fails return null and are skipped from the batch (rare now), then caught by the next-up prefetch.
  - **Fill-skipped straggler recovery (pin during planning):** the existing `prefetchNextTrack` already re-resolves the logical next-up track (matched by `EXTRA_TRACK_ID` against `currentQueueTracks`, which retains the full list) and `insertNextMediaItem`s it when it's absent from the timeline. With Component 2 it must run that re-resolve with **`allowYtDlp = true`** so a fill-skipped (iOS-miss) track gets the yt-dlp backstop at the next-up slot. Confirm this single seam: the prefetch path's resolve call uses the slow-lane-allowed flag even though background fill did not.
- **Foreground tapped track + next-up prefetch:** `allowYouTube = true, allowYtDlp = true` (full power, yt-dlp backstop available exactly where latency is hidden behind the current track).

This is the only viable path to a fast deep queue: yt-dlp **cannot** be parallelized (see Component 3), so the cap-8 InnerTube lane must do the bulk fill.

### Component 3 — Resilience polish

- **Do NOT raise `YTDLP_CONCURRENCY`.** Verified: it is `1` deliberately — cap=2 caused cascading `YoutubeDLException` at the JNI boundary (on-device LATDIAG, `feat/tap-cancel-hardening`). It stays `1`.
- **Next-up prefetch** keeps its yt-dlp-allowed 1-ahead behavior as the straggler safety net. Optionally deepen to ~3-ahead (cheap via the fast lane) to cover consecutive yt-dlp-only stragglers — secondary, since background fill now resolves the whole queue. Decide during planning based on whether background-fill coverage makes it redundant.

---

## Data flow (after fix): "tap a YT-fallback mix"

1. `setQueue` resolves the tapped track with `allowYouTube=true, allowYtDlp=true` → iOS fast lane (~0.5 s) → plays.
2. Background fill resolves the **entire** remainder with `allowYouTube=true, allowYtDlp=false` via iOS at cap-8 → full in-order timeline in a couple of seconds.
3. Rare iOS-miss tracks are skipped from fill, then re-resolved at the next-up slot with yt-dlp allowed.
4. ExoPlayer auto-advances through a complete, correctly-ordered queue; mixed downloaded/streamable queues no longer skip.

## Error handling

- **iOS URL n-throttled / rejected by ExoPlayer:** existing retry-via-yt-dlp path (`extractViaYtDlpForRetry`) re-resolves. iOS URLs are generally un-throttled, so rare.
- **iOS goes login-walled in future** (as android_vr did): yt-dlp backstop still serves; degradation is "slower," not "broken." A future health-signal could demote iOS, but is out of scope.
- **CancellationException** must keep propagating through the new flag plumbing (preserve current structured-concurrency contracts in `race`, `resolve`).

## Testing

- **Unit (JVM):**
  - variant → host mapping (IOS=www, WEB_REMIX=music).
  - audio variant order excludes login-walled clients.
  - Opus-preferred selection: `[AAC 132, Opus 130]` → Opus; `[AAC 140]` (no Opus) → AAC; ties → Opus.
  - fast/slow split: `extractStreamUrl(allowYtDlp=false)` never invokes the yt-dlp arm (extend `raceForTest`).
  - the slow-lane gate does **not** disable `extractViaYtDlpForRetry` — the ExoPlayer-rejection retry is a separate entry point and must still reach yt-dlp regardless of the `allowYtDlp` queue-fill flag.
- **On-device (LATDIAG, the method that found the bug):**
  - `extract-end` sub-second via iOS (`won with variant=IOS`); not yt-dlp.
  - `controller.mediaItemCount` == full queue size (not 2) for an all-YT mix.
  - Mixed downloaded/streamable mix plays streamable tracks in order — no skip-to-downloads.
  - Now Playing shows Opus ~160 k.
- Per project convention (`feedback_install_after_fix`): build + `installDebug` and verify on device; compile-pass is not enough.

## Out of scope / follow-ups

- **FLAC / lossless** path — explicitly deferred per user.
- **Download path** format-order quality bug (`141/140/251` prefers AAC 140 over Opus 251 for free accounts) — same root quality issue, separate scope.
- **Honest quality label** UI tweak (stop implying 256 k on free accounts) — small, optional follow-up; not in core.
- **NewPipe extractor** — held in reserve if the iOS lane degrades (existing spike doc `2026-05-21-newpipe-extractor-spike-design.md`).

## Risks

- **YouTube changes the iOS client/PO-token policy** (the recurring cat-and-mouse). Mitigation: yt-dlp backstop keeps playback working (slower); iOS version is a one-line bump when it drifts.
- **Host/plumbing regression to search/library** — mitigated by keeping WEB_REMIX + music.youtube.com untouched for non-player endpoints and unit-testing the mapping.

## Verification Results (on-device, 2026-06-08; recorded retroactively 2026-06-09)

Three device-test rounds on the Pixel 6 Pro; the original design was partially **refuted** on-device and pivoted. Final state committed as `334f2ff6` and merged to master (via the antra branch lineage) on 2026-06-09.

1. **Round 1 — extraction fixed, playback regressed:** the resurrected IOS fast lane worked exactly as specced (`won with variant=IOS`, extract 154–314 ms vs ~11 s, Opus-preferred 129–156 k, queue filled 39 deep in order) — but every IOS googlevideo URL is **PO-token-gated to ~1.1 MB** (deterministically reproduced off-device: every offset past ~1.1 MB 403s via Range, `&range=`, sequential, `&rn/&rbuf`, `&ratebypass`). The old code only "worked" because its broken fast lane always lost to yt-dlp, whose URLs stream fully.
2. **Pivot (user-confirmed):** keep the queue-depth + Opus wins; playback goes **yt-dlp-direct** when `allowYtDlp=true` (semaphore-guarded at the hard JNI cap-1, coalesced); IOS retained only for fill placeholders and ~30 s search previews where 1 MB suffices.
3. **Round 2 — found `StreamingMediaSourceFactory`/`RefreshingDataSource` were dead code** (never wired into ExoPlayer), so 403'd placeholders skip-stormed to Halt. Fixed with new `StashMediaSourceFactory` routing only youtube-origin items through the Cache→Refreshing→Http chain, wired via `setMediaSourceFactory` in `StashPlaybackService`.
4. **Round 3 — PASSED:** placeholders recover transparently (`yt-dlp: SUCCESS`), prefetch pre-swaps next-up, auto-advance smooth. Manual tap/skip costs ~11 s (yt-dlp, cap-1 — the confirmed hard ceiling for free YouTube full-track streaming); a buffering spinner (optimistic `isBuffering` in `PlayerState` → Now Playing + MiniPlayer) covers the wait. User verdict on-device: "functioning a lot better now."

Deferred (recorded in memory): instant-start hybrid (IOS 1 MB preview + bounded-range chunk source + yt-dlp tail swap) if YT tap latency ever needs to be fast.
