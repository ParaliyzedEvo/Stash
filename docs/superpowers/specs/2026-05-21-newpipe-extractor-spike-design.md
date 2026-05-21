# NewPipe Extractor Spike — Design

**Date:** 2026-05-21
**Branch:** `feat/newpipe-extractor-spike` (to be cut from `feat/yt-fallback-resolver`)
**Status:** Spec — pending implementation plan

## Goal

Evaluate whether [TeamNewPipe/NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) can replace yt-dlp + QuickJS as the primary cipher-solving extractor for YouTube preview URLs, by running it as a third arm in the existing `PreviewUrlExtractor` race and measuring on-device latency + success rate via `LATDIAG` logcat keys.

This is a **feasibility spike**, not a feature branch. No demotion of yt-dlp, no UI surface, no settings toggle. The branch is evaluated against LATDIAG data; promotion (or abandonment) happens in a follow-up.

## Context

`PreviewUrlExtractor` (`data/download/src/main/kotlin/com/stash/data/download/preview/PreviewUrlExtractor.kt`) currently races two extractors:

- **InnerTube** (`extractViaInnerTube`, ~1–2 s) — rotates ANDROID_VR → IOS → WEB_REMIX client variants looking for an unciphered `streamingData.adaptiveFormats[*].url`. Concurrency cap 8.
- **yt-dlp + QuickJS** (`extractViaYtDlp`, ~15–35 s) — full signature solving. Slow but reliable. Concurrency cap 2.

Race semantics: sequential preference. InnerTube is awaited first; yt-dlp's result is only used if InnerTube returns null.

The just-shipped YT-fallback resolver (commit `5583e29`, `feat/yt-fallback-resolver`) routes tracks not in Qobuz's catalog through this same path. Empirically (logged via `LATDIAG`), every YT-fallback track pays the full ~11 s yt-dlp + QuickJS cost: an InnerTube probe in `.worktrees/online-streaming-engine` confirmed all three InnerTube variants return ciphered URLs for restricted music. yt-dlp is the only path that produces a playable URL, and 11 s of "tap → audio" latency is the spike's motivating user-visible pain point.

NewPipe Extractor is an established Android library that performs YouTube cipher-solving in-process (Rhino-backed). It does not depend on yt-dlp's Python runtime and typically resolves a stream URL in 1–4 s on the YT-fallback population. The spike adds it as a measurement-instrumented third racer.

## Architecture

The spike adds NewPipe as the middle arm of a three-way preview-URL race, with sequential preference.

```
PreviewUrlExtractor.extractStreamUrl(videoId)
    │
    └─ race(videoId, innerTubeExtract, newPipeExtract, ytDlpExtract,
              itSem, npSem, ytSem)
            │
            ├─ async { innerTubeExtract }  cap=8  rescue→null
            ├─ async { newPipeExtract   }  cap=4  rescue→null   ← new
            └─ async { ytDlpExtract     }  cap=2  throws on fail
            │
            ├─ await inner   → non-null? cancel np+yt, return
            ├─ await newpipe → non-null? cancel yt,    return   ← new
            └─ await yt      → return (or throw)
```

Behavioral contract:

- **Catalog tracks** (InnerTube returns unciphered URL): identical to today. InnerTube wins in ~1–2 s; NewPipe and yt-dlp are cancelled. Zero user-visible change.
- **YT-fallback / restricted tracks** (InnerTube returns null after ~1 s, once it has exhausted variants): NewPipe and yt-dlp both ran in parallel from t=0. When InnerTube punts, the race looks at NewPipe's slot — if NewPipe has already finished with a URL, return immediately; if still pending, await it; if it returned null, fall through to yt-dlp.
- **NewPipe outage** (library bug, YouTube change, network): NewPipe arm returns null, race falls through to yt-dlp exactly as today. No regression possible — yt-dlp remains the correctness backstop.

The new arm is purely additive: every code path that exists today still exists with the same outcome on yt-dlp failure or success. The only new behavior is "NewPipe might return first."

## Components

### `NewPipeStreamExtractor` (new)

Path: `data/download/src/main/kotlin/com/stash/data/download/preview/NewPipeStreamExtractor.kt`

Shape mirrors `YtDlpManager` — `@Singleton`, owns lazy init, exposes one suspend.

```kotlin
@Singleton
class NewPipeStreamExtractor @Inject constructor(
    private val downloader: OkHttpNewPipeDownloader,
) {
    @Volatile private var initialized = false
    private val initLock = Any()

    private fun ensureInitialized() {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            NewPipe.init(downloader, Localization("en", "US"))
            initialized = true
        }
    }

    /** Returns highest-bitrate audio stream URL, or null on any failure. */
    suspend fun extractStreamUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        ensureInitialized()
        val url = "https://www.youtube.com/watch?v=$videoId"
        withTimeout(NEWPIPE_TIMEOUT_MS) {
            runCatching {
                val service: StreamingService = ServiceList.YouTube
                val info = StreamInfo.getInfo(service, url)
                info.audioStreams
                    .maxByOrNull { it.averageBitrate.takeIf { b -> b > 0 } ?: it.bitrate }
                    ?.content   // resolved direct URL (NewPipe deciphers internally)
            }.getOrElse { t ->
                if (t is CancellationException) throw t
                Log.w(TAG, "NewPipe extract failed for $videoId: ${t.javaClass.simpleName} ${t.message}")
                null
            }
        }
    }

    companion object {
        private const val TAG = "NewPipeStreamExtractor"
        private const val NEWPIPE_TIMEOUT_MS = 15_000L
    }
}
```

### `OkHttpNewPipeDownloader` (new)

Path: `data/download/src/main/kotlin/com/stash/data/download/preview/OkHttpNewPipeDownloader.kt`

Implements NewPipe's abstract `Downloader.execute(request)` on top of the existing injected `OkHttpClient`. Reusing the shared client inherits timeouts, interceptors, and the connection pool — no second HTTP stack.

```kotlin
@Singleton
class OkHttpNewPipeDownloader @Inject constructor(
    private val client: OkHttpClient,
) : Downloader() {
    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody(null)
        val httpReq = okhttp3.Request.Builder()
            .url(request.url())
            .method(request.httpMethod(), body)
            .apply { request.headers().forEach { (k, vs) -> vs.forEach { v -> addHeader(k, v) } } }
            .build()
        client.newCall(httpReq).execute().use { resp ->
            return Response(
                resp.code, resp.message,
                resp.headers.toMultimap(),
                resp.body?.string(),
                resp.request.url.toString(),
            )
        }
    }
}
```

### `PreviewUrlExtractor` (modified)

- Constructor takes the new `NewPipeStreamExtractor`.
- `race()` companion signature gains `newPipeExtract` + `npSem`.
- New `newPipeSemaphore` field on the companion (cap = `NEWPIPE_CONCURRENCY`).
- `extractStreamUrl` wires the new arm with a `newpipe-end` LATDIAG log and a `winner` field on `extract-end`.

```kotlin
private suspend fun race(
    videoId: String,
    innerTubeExtract: suspend (String) -> String?,
    newPipeExtract:  suspend (String) -> String?,
    ytDlpExtract:    suspend (String) -> String,
    itSem: Semaphore,
    npSem: Semaphore,
    ytSem: Semaphore,
): String = coroutineScope {
    val inner = async { itSem.acquire(); try { rescueNull { innerTubeExtract(videoId) } } finally { itSem.release() } }
    val np    = async { npSem.acquire(); try { rescueNull { newPipeExtract(videoId)  } } finally { npSem.release() } }
    val yt    = async { ytSem.acquire(); try { ytDlpExtract(videoId)                  } finally { ytSem.release() } }

    inner.await()?.let { url ->
        np.cancel(CancellationException("InnerTube won"))
        yt.cancel(CancellationException("InnerTube won"))
        return@coroutineScope url
    }
    np.await()?.let { url ->
        yt.cancel(CancellationException("NewPipe won"))
        return@coroutineScope url
    }
    yt.await()
}
```

`rescueNull` is a small helper factored out of the existing pattern: `runCatching { block() }.getOrElse { if (it is CancellationException) throw it; null }`.

## Dependency injection

All three new types are `@Inject constructor`-eligible. No `@Module` boilerplate needed.

- `OkHttpClient` is already provided as `@Singleton` in `core/network/NetworkModule.kt`. No change.
- `OkHttpNewPipeDownloader` and `NewPipeStreamExtractor` are both `@Singleton` — one instance per process.
- `PreviewUrlExtractor` constructor gains `private val newPipeStreamExtractor: NewPipeStreamExtractor`.

## Init lifecycle

NewPipe's static `NewPipe.init(downloader, localization)` is process-global and only safe to call once. Lazy double-checked-locking inside `NewPipeStreamExtractor.ensureInitialized()`:

- First `extractStreamUrl` call pays the init cost (cheap — registers services, no network).
- Subsequent calls hit the `@Volatile` fast path and skip the lock.
- Init runs on `Dispatchers.IO` because the wrapping `withContext(IO)` is already there.

**No eager init.** `Application.onCreate()` is unchanged. Cold-start cost stays at zero for catalog-only users who never hit the YT-fallback path.

## Operational dials

Constants in `PreviewUrlExtractor.companion object`, discoverable as a single tuning surface alongside the existing dials:

```kotlin
private const val NEWPIPE_TIMEOUT_MS = 15_000L
private const val NEWPIPE_CONCURRENCY = 4
```

**Timeout: 15 s.** Sits between InnerTube (10 s) and yt-dlp (60 s). NewPipe typically responds in 1–4 s; 15 s absorbs slow round-trips and JS-deciphering without competing with yt-dlp's 60 s timeout. Past 15 s, NewPipe is almost certainly broken on this videoId and we want the race to fall through to yt-dlp quickly. Wrapped via `withTimeout` inside `extractStreamUrl`. Timeout failure is rescued as null by the same `runCatching`.

**Concurrency: 4.** Between InnerTube (8) and yt-dlp (2). NewPipe is heavier than InnerTube (own HTTP + JS-deciphering via Rhino) but lighter than yt-dlp (no Python runtime, no JNI surface). 4 lets the prefetcher warm a small queue without saturating the OkHttp pool the rest of the app shares.

**Format selector:** `audioStreams.maxByOrNull { averageBitrate.takeIf { it > 0 } ?: bitrate }`. Matches `extractViaInnerTube` semantics.

**No retry / no variant-rotation.** Single attempt per request. Failure → race falls through to yt-dlp. Internal retry would muddle the latency signal.

## LATDIAG instrumentation

Existing keys stay. One new key plus a `winner` field on `extract-end`:

```
extract-start  videoId=...
innertube-end  videoId=... dt=...ms outcome=url|null|throw:Exception
newpipe-end    videoId=... dt=...ms outcome=url|null|throw:Exception    ← new
ytdlp-end      videoId=... dt=...ms outcome=url|throw:Exception
extract-end    videoId=... dt=...ms winner=innertube|newpipe|ytdlp     ← winner is new
extract-fail   videoId=... dt=...ms err=Exception
```

The `winner` field lets us answer the spike's question with a single `grep "extract-end" | awk` over a session's logcat. Stamped in `extractStreamUrl` by tagging which arm produced the returned URL.

## Build configuration

`data/download/build.gradle.kts`:

```kotlin
implementation("com.github.TeamNewPipe:NewPipeExtractor:0.24.6")
```

`settings.gradle.kts` (verify; most yt-dlp-android projects already have it):

```kotlin
dependencyResolutionManagement {
    repositories {
        maven("https://jitpack.io")
    }
}
```

## Tests

Two surfaces. JVM unit tests only — no instrumented/integration tests. On-device LATDIAG numbers are the evaluation signal.

### `PreviewUrlExtractorTest.kt` (extended)

The existing file exercises `raceForTest` against the `TestHooks` interface. Extend `TestHooks` with `suspend fun newPipeExtract(id: String): String?`; existing doubles return `null` (preserves their 2-arm behavior). New cases:

- `newpipe_wins_when_innertube_returns_null_and_newpipe_returns_first` — InnerTube returns null after a delay, NewPipe returns a URL faster than yt-dlp; assert NewPipe's URL is returned and yt-dlp is cancelled.
- `falls_through_to_ytdlp_when_innertube_and_newpipe_both_return_null` — both nullable arms punt; yt-dlp's URL wins.
- `newpipe_throw_does_not_poison_race` — NewPipe throws `RuntimeException`; race still returns yt-dlp's URL. Asserts the `rescueNull` helper catches non-cancellation throws.
- `newpipe_cancellation_propagates` — outer scope is cancelled mid-race; assert NewPipe's permit is released (semaphore `availablePermits` returns to baseline).
- `winner_field_reflects_arm_that_produced_url` — drive each arm to win in turn; assert the value PreviewUrlExtractor stamps for LATDIAG matches.

Timing via `runTest` + `advanceTimeBy`, matching the existing file's style.

### `NewPipeStreamExtractorTest.kt` (new)

Pure JVM unit test. Injects a fake `OkHttpNewPipeDownloader`:

- `returns_highest_bitrate_audio_url` — fake Downloader returns canned NewPipe `StreamInfo` JSON with multiple audioStreams; assert the highest-bitrate one is picked.
- `returns_null_on_extractor_exception` — fake Downloader throws / returns malformed JSON; method returns null.
- `returns_null_on_timeout` — fake Downloader delays past `NEWPIPE_TIMEOUT_MS`; method returns null. (Uses `runTest` virtual time.)
- `cancellation_propagates_not_swallowed` — outer scope cancels; method rethrows `CancellationException` rather than swallowing it.

**Spike unknown:** if `NewPipe.init()` fights MockWebServer (touches real network or static state in tests), degrade test #1 to a smaller-scope unit test against the format-selection logic, extracted to `internal fun pickBestAudio(streams: List<AudioStream>): String?`. This is the only "tbd in implementation" in the design.

### Out of scope for tests

- No tests for `OkHttpNewPipeDownloader` directly — thin adapter, covered transitively.
- No tests against real YouTube — CI flakiness without answering the spike's question.

## Out of scope

Explicit non-goals — listed because each is a plausible "while we're in there" expansion that would muddy the data or balloon the diff.

- **No demotion of yt-dlp.** If NewPipe wins consistently, yt-dlp's cap, timeout, and call-site stay unchanged. Demotion is a follow-up branch informed by this spike's LATDIAG numbers.
- **No retry-path integration.** `extractViaYtDlpForRetry` (called by `SearchViewModel.onPreviewError` when ExoPlayer rejects a throttled InnerTube URL) still calls yt-dlp directly. Wiring NewPipe in there is a second data-affecting change; do it after the primary signal lands.
- **No offline-download integration.** Downloads still use yt-dlp's full-file path. NewPipe Extractor only resolves stream URLs.
- **No InnerTube changes.** `playerForAudio` variant rotation untouched.
- **No cookie / auth integration for NewPipe.** Measure the clean-public-track path first. Cookie support is a follow-up if data shows it's needed.
- **No settings-tab exposure / no UI toggle.** Runs unconditionally on the branch. Bad data → abandon the branch, no UI cleanup.
- **No analytics / Sentry events.** LATDIAG via `adb logcat` is sufficient.
- **No eager `Application.onCreate()` init.** Cold start clean; catalog-only users pay zero NewPipe cost.
- **No alternative library evaluation.** Upstream TeamNewPipe 0.24.6 (or latest at branch-cut) — that's the conclusion the data is about.

## Branch posture

`feat/newpipe-extractor-spike` cut from `feat/yt-fallback-resolver` (YT-fallback is the population that benefits most). It's an evaluation branch, not a promotion-ready feature branch. Depending on LATDIAG results, the next action is one of:

- **NewPipe is a clear win** → rebase + ship; queue follow-up branch to demote yt-dlp.
- **NewPipe wins sometimes** → follow-up tuning branch (cookie support? variant rotation? alternative versions?).
- **NewPipe doesn't beat yt-dlp** → close branch without merge; document findings.

## Risks

- **YouTube cat-and-mouse.** NewPipe breaks periodically when YouTube ships extraction-defense changes. yt-dlp remains the backstop — a NewPipe outage degrades to today's behavior, not to a broken app.
- **Rhino size.** NewPipe pulls in Mozilla Rhino for JS execution. APK bloat needs to be measured during implementation; if it's significant (>1 MB), document the trade-off in the evaluation.
- **NewPipe.init() in tests.** Static init may fight JVM unit tests. Mitigation in the test plan above.
- **Cipher-solving via Rhino is single-threaded.** Concurrency 4 may not deliver 4x throughput. If LATDIAG shows queuing delays, drop to 2.
