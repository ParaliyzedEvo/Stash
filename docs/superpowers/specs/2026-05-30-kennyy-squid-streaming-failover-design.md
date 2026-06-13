# Kennyy → Squid Streaming Failover — Design

**Status:** Approved (brainstorm), ready for implementation plan
**Date:** 2026-05-30
**Subsystem:** Lossless streaming source routing (`:core:media` streaming, `:data:download` lossless, app lifecycle)

## Problem

When a track is streamed (Stash Mixes are stream-only), Stash resolves a FLAC URL by
walking a source roster in priority order:

1. **Kennyy** (`qobuz.kennyy.com.br`) — Qobuz catalog, **no captcha**. Primary.
2. **Squid** (`qobuz.squid.wtf`) — same Qobuz catalog/quality, but requires an
   ALTCHA captcha cookie that expires on a ~30-minute sliding window.
3. **YouTube** — lossy last resort.

Kennyy's *only* advantage is that it avoids the captcha; quality and catalog are identical
to Squid. So the product goal is: **use Kennyy whenever it is up (zero captcha cost), and
fail over to Squid only while Kennyy is actually down — solving the captcha only during
those windows.**

The failover "works, but takes more time than it should," and the Squid cookie
auto-authentication "only works sometimes, if at all anymore." Root-cause investigation
found three compounding defects:

1. **[Primary] The play path always tries Kennyy first and waits for it to fail — even when
   the health monitor already knows Kennyy is down.** `KennyyStreamResolver.resolve` never
   consults `KennyyHealthMonitor.isHealthy`; it always does a full network round-trip.
   `KennyyApiClient` inherits the shared OkHttp client's **30s** timeouts, so a hung Kennyy
   costs up to ~30s *per fresh track* before Squid is even attempted. Mixes are entirely
   fresh tracks, so every track pays it.
2. **[Cold-start gap] `KennyyHealthMonitor` resets to `healthy` on every process start**
   (state is process-transient by design). So after a restart the cookie pre-warmer
   (`SquidCookieAutoRefresher`, which only warms while Kennyy is unhealthy) sleeps, and the
   first several plays hit dead-Kennyy timeouts *and* a possibly-cold Squid cookie (falling
   to lossy YouTube) until ~3 failures flip the flag. This is the "only works sometimes."
3. **[Halt gap] `SquidCookieAutoRefresher` permanently stops after 2 consecutive ALTCHA
   solve failures** (until app restart / manual re-auth). A transient solve hiccup disables
   lossless streaming for the rest of the session. This is the "if at all anymore."

`QobuzStreamResolver` (Squid) is *not* itself slow — it returns null immediately when the
cookie is cold (it never solves ALTCHA inline). So the latency is **routing + cookie
warmth**, not the solver on the critical path.

## Goals / Non-goals

**Goals**
- Never make a user's playback wait on a Kennyy instance that is already known to be down.
- Recover to the no-captcha Kennyy path automatically and promptly when Kennyy returns,
  without a user tap being the slow probe.
- Establish accurate Kennyy health *before* the first play after launch.
- Never permanently disable lossless streaming for a session due to a transient solve failure.
- Keep ALTCHA solves minimal — only while Kennyy is genuinely down.

**Non-goals (YAGNI)**
- Persisting health verdict across restarts (the launch probe makes it moot; a persisted
  verdict can be stale if Kennyy recovered while the app was closed).
- Changing Squid cookie storage, the ALTCHA solver, or the download/sync (non-streaming) path.
- Any new UI beyond the existing `CaptchaExpiredNotifier`.
- Racing Kennyy and Squid in parallel, or making Squid the primary — both would burn an
  ALTCHA solve roughly every 30 min even when Kennyy works, defeating Kennyy's only purpose.

## Architecture

Keep the existing pieces (`KennyyHealthMonitor`, `SquidCookieAutoRefresher`, the resolver
chain). `KennyyHealthMonitor` remains the single source of truth for "is Kennyy up" — now
**consumed by routing** (new) and **fed by both real plays and a dedicated probe** (new).
Five focused changes:

| # | Component | Change |
|---|-----------|--------|
| 1 | `KennyyStreamResolver.resolve` | When `healthMonitor.isHealthy.value == false`, return `null` immediately without a network call. Mirrors `QobuzStreamResolver` returning null when its cookie is cold. |
| 2 | **New** `KennyyHealthProbe` | Background loop: on `start()` probe Kennyy once immediately; then, **only while unhealthy**, probe every ~45s with one cheap, short-timeout call; record each outcome into `KennyyHealthMonitor`. Sole caller of Kennyy while unhealthy → its successes flip health back to healthy. **The loop MUST survive every iteration** — see "Probe-loop resilience (critical)" below. |
| 3 | `StashApplication` STARTED lifecycle | Start `KennyyHealthProbe` alongside the existing `squidCookieAutoRefresher.start()`. Its immediate probe establishes ground truth before first play (cold-start fix). `stop()` on STOPPED. |
| 4 | `SquidCookieAutoRefresher.refresh` | Replace permanent halt-after-2-failures with exponential backoff (60s → cap 5min) that keeps retrying while Kennyy is unhealthy; reset on success. `CaptchaExpiredNotifier` stays as the manual fallback nag. Never permanently halts for the session. |
| 5 | `KennyyStreamResolver` | Wrap the streaming Kennyy resolve in a ~3s `withTimeout` (a timeout counts as a network failure → feeds the monitor → trips health faster). The download path keeps 30s. Backstop for slow-hang degradation that hasn't tripped the health threshold yet. |

**Isolation:** the only new unit is `KennyyHealthProbe` (single responsibility: probe Kennyy +
record outcome while unhealthy; owns cold-start + recovery). All other changes are small,
in-place edits to components that already own the relevant concern.

**Recovery speed (deliberate):** the monitor is a 5-event window requiring failures < 3.
Returning from fully-dead (5 failures) takes ~3 successful probes ≈ ~90s at a 45s cadence.
Chosen conservative to avoid flapping back onto a still-flaky Kennyy. No fast-reset path.

## Data flow

- **Kennyy healthy (common):** unchanged. Kennyy serves; probe idle; no captcha, no Squid;
  zero added cost.
- **Kennyy goes down mid-session:** real plays fail → monitor flips unhealthy → (a) cookie
  pre-warmer wakes and warms the cookie; (b) play path skips Kennyy → next tap → Squid
  instantly. Probe begins ~45s polling.
- **Cold start, Kennyy already down:** `KennyyHealthProbe.start()` immediate probe → fail →
  unhealthy before first play → pre-warmer warms cookie → first tap → Squid.
- **Kennyy recovers:** probe calls succeed → ~3 successes → healthy → pre-warmer sleeps
  (captcha churn stops) → play path uses Kennyy again. No user ate a slow probe.

## Error handling

- **Slow-hang Kennyy:** ~3s `withTimeout` cancels the streaming call, records a failure,
  returns null → Squid. Downloads unaffected (keep 30s).
- **Transient ALTCHA solve failure:** pre-warmer backs off and keeps trying while Kennyy is
  down; never the permanent session-kill it is today; user still nagged via
  `CaptchaExpiredNotifier`.
- **Worst case — Kennyy down AND cookie un-solvable:** play path → Kennyy skipped → Squid
  cold → YouTube lossy fallback (graceful degradation to sound, not silence) while the
  pre-warmer retries and the user is nagged to paste a cookie.
- **Kennyy down for hours:** stay on warm-Squid; captcha re-solves ~every 25min — the
  unavoidable, intended cost of Kennyy being down.

## The probe call

A lightweight **search** against Kennyy for a hardcoded always-in-catalog track (no
download-URL resolution; we only care whether Kennyy responds), wrapped in the short
timeout. Success = ≥1 result. The implementation plan must confirm the cheapest existing
`KennyyApiClient` search method (e.g. the `/get-music` search endpoint) and reuse it — do
**not** add a new proxy endpoint.

**Outcome → health recording (exact):** the probe must record exactly two outcomes:
- ≥1 result within the timeout → `recordSuccess()`.
- Anything else — timeout, thrown exception (DNS/TLS/`IOException`/parse), **or zero results**
  for the hardcoded always-in-catalog track (itself a proxy anomaly) → `recordFailure()`.

The probe must **not** route its zero-result case through the play path's `recordNoMatch()`
(a deliberate no-op for genuine per-track catalog misses). Because the probe track is chosen
to always exist, zero results means the proxy is misbehaving, and that must move the health
window. The play path's failure classification keys off `KennyySource.lastResolveFailedNetwork`
after a full `resolveImmediate`; since the probe uses a different (search-only) call, the plan
must confirm the chosen search method exposes a comparable network-vs-no-match distinction —
**or** the probe wraps the call in its own try/catch and classifies the outcome itself (the
default if no such signal exists).

## Probe-loop resilience (critical)

Change #1 makes the play path **skip Kennyy entirely while unhealthy**, so real plays never
call Kennyy (and never `recordSuccess`) during an outage. That makes `KennyyHealthProbe` the
**sole** path back to `healthy`. If the probe loop ever dies, health is stuck unhealthy for
the rest of the foreground session and Kennyy is never retried — re-creating the exact "only
works sometimes" symptom this spec exists to eliminate.

Therefore the loop is required to survive every iteration:
- Each probe iteration wraps the entire probe call in a `try/catch` that records **any**
  `Throwable` as `recordFailure()` and continues to the next interval — no exception may
  escape the loop body.
- The catch MUST re-throw `CancellationException` *before* the catch-all, so `stop()`
  (lifecycle STOPPED) still cancels the loop cleanly. (Project convention — see the
  cancellation-in-worker-catches rule.)
- The loop continues polling on its interval for as long as the app is foregrounded and
  Kennyy is unhealthy; it idles only when health flips to `healthy`.

This is the single most important correctness property in the design: the recovery loop must
be unbrickable.

## Testing

All unit-level (mocks + `TestScope`, TDD), following the existing
`SquidCookieAutoRefresherTest` pattern:

- `KennyyStreamResolver`: unhealthy → returns null and does **not** call
  `source.resolveImmediate` (verify not called); healthy → calls it and returns a `StreamUrl`.
- `KennyyStreamResolver`: a streaming resolve exceeding the timeout is cancelled, recorded as
  a failure, returns null.
- `KennyyHealthProbe`: `start()` probes once immediately; probes on interval while unhealthy;
  idle while healthy; probe success → `recordSuccess`, probe failure → `recordFailure`;
  zero-results → `recordFailure` (not `recordNoMatch`); `stop()` cancels the loop.
- `KennyyHealthProbe` resilience: a probe iteration that **throws** is recorded as a failure
  and the loop **survives** to probe again on the next interval (it does not die); `stop()`
  still cancels cleanly (CancellationException is not swallowed).
- `SquidCookieAutoRefresher`: after a solve failure it retries with backoff and does **not**
  permanently stop; after success it resets backoff and resumes the age-based cadence.
- Integration (`StreamSourceRegistry`): with Kennyy unhealthy, resolve is served via Squid
  with no Kennyy network wait.
- Existing `KennyyHealthMonitorTest`, `KennyyStreamResolverTest`,
  `SquidCookieAutoRefresherTest` stay green (extend, don't break).

## Open implementation details (resolve during planning)

- Exact `KennyyApiClient` search method + the hardcoded probe track.
- Whether the ~3s streaming timeout is a coroutine `withTimeout` in the resolver (preferred,
  localized) or a separate short-timeout OkHttp client for streaming calls.
- `KennyyHealthProbe` injection shape (mirror `SquidCookieAutoRefresher`'s explicit-scope +
  `@Inject` secondary constructor so a `TestScope` can be injected in tests).
- Backoff schedule constants for the pre-warmer.
