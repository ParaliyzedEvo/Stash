# antra Lossless Source — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add antra (`antra.hoshi.cfd`) as an independent, per-user-account lossless fallback after kennyy/squid, for both the download and streaming paths.

**Architecture:** Mirror the existing kennyy/squid split — a shared `AntraClient` consumed by an `AntraSource` (download, implements `LosslessSource`) and an `AntraStreamResolver` (stream, returns `StreamUrl`). antra is job-based: `resolve → createJob → poll /status → /download`. Auth = a `session` + `cf_clearance` cookie pair harvested by an in-app WebView (mirroring `SquidWtfCaptchaScreen`) and attached by an `AntraCookieInterceptor` (mirroring `SquidWtfCaptchaInterceptor`). Cloudflare handling = **Approach A** (OkHttp cookie-replay) as v1; Approach B (WebView request-proxy) is a contingency only if on-device shows 403s.

**Tech Stack:** Kotlin, Hilt, OkHttp, kotlinx.serialization (JSON), Coroutines, JUnit + MockWebServer + MockK + Truth, Jetpack Compose (WebView via AndroidView), Media3 (stream side).

**Spec:** `docs/superpowers/specs/2026-06-08-antra-lossless-source-design.md`

**Reference files to read before starting (mirror these patterns):**
- `data/download/.../lossless/LosslessSource.kt` — the interface + `TrackQuery`/`SourceResult`/`AudioFormat`.
- `data/download/.../lossless/kennyy/KennyySource.kt` + `.../kennyy/di/KennyyModule.kt` — download source + DI/registration shape.
- `data/download/.../lossless/squid/SquidWtfCaptchaInterceptor.kt` — cookie-from-prefs interceptor (template for `AntraCookieInterceptor`).
- `feature/settings/.../components/SquidWtfCaptchaScreen.kt` — WebView + `CookieManager` harvest (template for `AntraConnectScreen`).
- `core/media/.../streaming/KennyyStreamResolver.kt` — `StreamUrl` + stream-resolver shape.
- `core/media/.../streaming/StreamSourceRegistry.kt` — stream failover ordering.
- `data/download/.../lossless/LosslessSourceRegistry.kt` — download failover ordering.

**Phasing (spec decision):** Phase 1–5 below are the Approach-A v1. Phase 6 (Approach B) is built ONLY if Phase 5's on-device test shows Cloudflare 403'ing the OkHttp replay.

**Branch:** Create `feat/antra-lossless-source` before Task 1.

---

## Phase 1 — Credentials & cookie plumbing

### Task 1: `AntraCredentialStore` (persist session + cf_clearance)

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/lossless/antra/AntraCredentialStore.kt`
- Test: `data/download/src/test/kotlin/com/stash/data/download/lossless/antra/AntraCredentialStoreTest.kt`
- Read first: `SquidWtfCaptchaInterceptor.kt` and whatever prefs class backs `prefs.captchaCookieValue` (find it: `grep -rn "captchaCookieValue" --include=*.kt`), to reuse the same prefs/DataStore mechanism.

- [ ] **Step 1: Write failing test** — store + retrieve + clear, and `isConnected()` reflects presence of BOTH cookies.

```kotlin
class AntraCredentialStoreTest {
    private val store = AntraCredentialStore(InMemoryAntraPrefs()) // or a fake backing the same prefs API

    @Test fun `not connected when cookies absent`() = runTest {
        assertThat(store.isConnected()).isFalse()
        assertThat(store.cookieHeader()).isNull()
    }

    @Test fun `stores both cookies and builds a Cookie header`() = runTest {
        store.save(session = "sess-abc", cfClearance = "cf-xyz", username = "rawn")
        assertThat(store.isConnected()).isTrue()
        val header = store.cookieHeader()!!
        assertThat(header).contains("session=sess-abc")
        assertThat(header).contains("cf_clearance=cf-xyz")
    }

    @Test fun `markStale clears connection`() = runTest {
        store.save("s", "c", "rawn")
        store.markStale()
        assertThat(store.isConnected()).isFalse()
    }
}
```

- [ ] **Step 2: Run test — expect FAIL** (`AntraCredentialStore` unresolved). Run: `./gradlew :data:download:testDebugUnitTest --tests "*AntraCredentialStoreTest"`
- [ ] **Step 3: Implement `AntraCredentialStore`** — persist `session`, `cf_clearance`, `username` via the same prefs/DataStore the squid cookie uses; `isConnected()` = both cookies non-blank; `cookieHeader()` = `"session=$s; cf_clearance=$c"`; `markStale()` clears. Expose a flow for observers if the prefs API provides one (mirror squid).
- [ ] **Step 4: Run test — expect PASS.**
- [ ] **Step 5: Commit** — `feat(antra): credential store for session + cf_clearance cookies`

### Task 2: `AntraCookieInterceptor` (attach cookies to antra requests)

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/lossless/antra/AntraCookieInterceptor.kt`
- Test: `data/download/src/test/kotlin/com/stash/data/download/lossless/antra/AntraCookieInterceptorTest.kt`
- Mirror: `SquidWtfCaptchaInterceptor.kt`

- [ ] **Step 1: Failing test** — interceptor adds `Cookie` + browser-style headers when connected; passes request through unchanged when not connected. Use a MockWebServer + a real OkHttp client with the interceptor; assert the recorded request's `Cookie` header.
- [ ] **Step 2: Run — FAIL.**
- [ ] **Step 3: Implement** — on each request to `antra.hoshi.cfd`, if `credentialStore.cookieHeader()` non-null, add `Cookie`, plus the `User-Agent` / `Origin` / `Referer` / `sec-ch-ua*` headers observed in the HAR (so the request fingerprint matches the `cf_clearance` binding). Pass through otherwise.
- [ ] **Step 4: Run — PASS.**
- [ ] **Step 5: Commit** — `feat(antra): cookie + browser-header interceptor`

---

## Phase 2 — API client

### Task 3: API response models + JSON parsing

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/lossless/antra/AntraApiModels.kt`
- Test: `data/download/src/test/kotlin/com/stash/data/download/lossless/antra/AntraApiModelsTest.kt`

Models (kotlinx.serialization `@Serializable`, `ignoreUnknownKeys`), shaped to the HAR:

```kotlin
@Serializable data class AntraMe(
    val username: String, val is_supporter: Boolean = false,
    val concurrent_jobs: Int = 0, val albums_left: Int = 0,
    val playlists_left: Int = 0, val singles_left: Int = 0,
)
@Serializable data class AntraResolveTrack(
    val index: Int = 0, val title: String = "", val artist: String = "",
    val duration_ms: Long = 0, val track_number: Int = 0, val explicit: Boolean = false,
)
@Serializable data class AntraResolve(
    val release_name: String = "", val release_type: String = "",
    val artist: String = "", val artwork_url: String? = null,
    val tracks: List<AntraResolveTrack> = emptyList(),
)
@Serializable data class AntraJobCreated(val job_id: String, val ws_token: String? = null)
@Serializable data class AntraJobStatus(
    val job_id: String, val status: String, val done: Int = 0, val failed: Int = 0,
    val total: Int = 0, val error: String? = null, val filename: String? = null,
)
```

- [ ] **Step 1: Failing test** — parse each canned JSON body from the HAR into the model and assert key fields (e.g. status `"complete"`, `filename = "Pusherman.flac"`, `singles_left = 99`).
- [ ] **Step 2: Run — FAIL.**
- [ ] **Step 3: Implement models** (above) + a shared `Json { ignoreUnknownKeys = true }`.
- [ ] **Step 4: Run — PASS.**
- [ ] **Step 5: Commit** — `feat(antra): API response models`

### Task 4: `AntraClient` (resolve / createJob / pollStatus / quota / downloadUrl)

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/lossless/antra/AntraClient.kt`
- Test: `data/download/src/test/kotlin/com/stash/data/download/lossless/antra/AntraClientTest.kt`

Public surface:

```kotlin
private const val ANTRA_BASE = "https://antra.hoshi.cfd"
private const val ANTRA_FORMAT = "lossless-24"   // fixed (spec)

class AntraClient @Inject constructor(
    private val client: OkHttpClient,   // built with AntraCookieInterceptor (DI, Task 8)
    private val json: Json,
) {
    /** internal so tests can point at a MockWebServer base. */
    internal var baseUrl: String = ANTRA_BASE

    suspend fun me(): AntraMe?                                   // GET /api/auth/me
    suspend fun resolve(spotifyUrl: String): AntraResolve?       // POST /api/resolve
    suspend fun createJob(spotifyUrl: String, startIndex: Int, endIndex: Int): AntraJobCreated?  // POST /api/jobs
    suspend fun pollStatus(jobId: String): AntraJobStatus        // GET /status, poll until terminal or timeout
    fun downloadUrl(jobId: String): String = "$baseUrl/api/jobs/$jobId/download"
}
```

Behavior: all calls on `Dispatchers.IO`; non-2xx → return null (or for `pollStatus`, treat as failure). `pollStatus` loops `GET /api/jobs/$jobId/status` every `POLL_INTERVAL_MS` (2 s) until `status in {"complete","failed","error"}` or `POLL_TIMEOUT_MS` (180 s) → return last status (synthesize `status="error"` on timeout). Surface a Cloudflare `403` distinctly (e.g. throw `AntraCloudflareException`) so callers can `markStale()`.

- [ ] **Step 1: Failing test (resolve)** — MockWebServer returns the HAR resolve body; assert `resolve(url)?.tracks?.first()?.title == "Pusherman"` and the recorded request body is `{"url":<url>,"format":"lossless-24"}`.
- [ ] **Step 2: Run — FAIL.**
- [ ] **Step 3: Implement `resolve` + `me` + `downloadUrl`.**
- [ ] **Step 4: Run — PASS.**
- [ ] **Step 5: Failing test (job lifecycle)** — MockWebServer: `POST /api/jobs` → `{job_id, ws_token}`; first `GET /status` → `queued`, second → `complete`. Assert `createJob` returns the id and `pollStatus` returns `complete` after polling. Use a virtual-time dispatcher so the 2 s interval doesn't slow the test.
- [ ] **Step 6: Run — FAIL.**
- [ ] **Step 7: Implement `createJob` + `pollStatus`** (bounded loop, injectable interval/clock for test).
- [ ] **Step 8: Run — PASS.**
- [ ] **Step 9: Failing test (Cloudflare 403)** — MockWebServer returns `403` with a `cf-mitigated: challenge` header; assert the client throws `AntraCloudflareException`.
- [ ] **Step 10: Run — FAIL → implement 403 detection → PASS.**
- [ ] **Step 11: Commit** — `feat(antra): API client (resolve, job lifecycle, poll, cf-403 detection)`

---

## Phase 3 — Download source

### Task 5: Add `spotifyUri` to `TrackQuery` and populate it

**Files:**
- Modify: `data/download/.../lossless/LosslessSource.kt` (the `TrackQuery` data class, ~line 80)
- Modify: `data/download/.../DownloadManager.kt:375`, `data/download/.../lossless/LosslessRetryWorker.kt:65`, `data/download/.../search/SearchDownloadCoordinator.kt:527`
- Test: `data/download/.../lossless/TrackQuerySpotifyUriTest.kt`

- [ ] **Step 1: Failing test** — `TrackQuery(artist="A", title="B", spotifyUri="spotify:track:xyz").spotifyTrackUrl() == "https://open.spotify.com/track/xyz"`; returns null when `spotifyUri` is null or not a track URI. (Add a small `TrackQuery.spotifyTrackUrl()` extension that converts `spotify:track:<id>` or a bare id to the open.spotify.com URL.)
- [ ] **Step 2: Run — FAIL.**
- [ ] **Step 3: Implement** — add `val spotifyUri: String? = null` to `TrackQuery`; add `fun TrackQuery.spotifyTrackUrl(): String?`. Populate `spotifyUri` at the 3 construction sites:
    - `DownloadManager.kt:375` and `LosslessRetryWorker.kt:65` — pass `spotifyUri = track.spotifyUri` (the track/entity has the field).
    - `SearchDownloadCoordinator.kt:527` (`TrackItem.toQuery()`) — **`TrackItem` has NO `spotifyUri` field** (it's the YouTube/search-tab flow: videoId/title/artist/duration only). Pass `spotifyUri = null` here. That correctly makes search-tab downloads skip antra, consistent with the spec's "only tracks with a spotifyUri can use antra" limitation. Do NOT hunt for a non-existent field.
    - (Default arg keeps all existing `TrackQuery(...)` calls/tests compiling.)
- [ ] **Step 4: Run — PASS;** also run the full `:data:download:testDebugUnitTest` to confirm no construction site broke. (Note the pre-existing unrelated red `YtLibraryCanonicalizerTest`.)
- [ ] **Step 5: Commit** — `feat(antra): thread spotifyUri through TrackQuery`

### Task 6: `AntraSource : LosslessSource`

**Files:**
- Create: `data/download/.../lossless/antra/AntraSource.kt`
- Test: `data/download/.../lossless/antra/AntraSourceTest.kt`
- Mirror: `KennyySource.kt` (id/displayName/isEnabled/rateLimitState + `AggregatorRateLimiter` usage).

`resolve(query)` flow: gate (`isEnabled()` = credentialStore.isConnected(); `query.spotifyTrackUrl()` non-null; quota via `client.me()?.singles_left > 0` — cache the `me()` per resolve) → `client.resolve(url)` (sanity) → `client.createJob(url, 0, 1)` → `client.pollStatus(jobId)` == `complete` → return `SourceResult(sourceId=id, downloadUrl=client.downloadUrl(jobId), downloadHeaders=mapOf("Cookie" to credentialStore.cookieHeader()!!), format=AudioFormat("flac", bitrateKbps=0, sampleRateHz=0, bitsPerSample=24), confidence=if(query.isrc!=null)0.95f else 0.85f, coverArtUrl=resolve.artwork_url)`. On `AntraCloudflareException` → `credentialStore.markStale()` + return null. Any gate fail / job fail / null → return null. Wrap network in the `AggregatorRateLimiter` for `id="antra"` (report success/failure/429) exactly like `KennyySource`.

- [ ] **Step 1: Failing test (happy path)** — fakes: connected store, `client.me()` quota=5, `resolve` ok, `createJob`→id, `pollStatus`→complete. Assert `SourceResult.downloadUrl` ends `/api/jobs/<id>/download`, `downloadHeaders["Cookie"]` set, `format.bitsPerSample==24`, `format.isLossless`.
- [ ] **Step 2: Run — FAIL.**
- [ ] **Step 3: Implement happy path.**
- [ ] **Step 4: Run — PASS.**
- [ ] **Step 5: Failing tests (gating)** — returns null when: not connected; `spotifyUri` null; quota 0; `pollStatus` failed; and `markStale()` is called on `AntraCloudflareException`.
- [ ] **Step 6: Run — FAIL → implement gates → PASS.**
- [ ] **Step 7: Commit** — `feat(antra): AntraSource download resolver`

---

## Phase 4 — Stream resolver + registries + DI

### Task 7: `AntraStreamResolver` (fetch FLAC to cache, return local file)

**Files:**
- Create: `core/media/.../streaming/AntraStreamResolver.kt`
- Test: `core/media/.../streaming/AntraStreamResolverTest.kt`
- Mirror: `KennyyStreamResolver.kt` (StreamUrl shape, expiry, origin).

`resolve(track: TrackEntity): StreamUrl?` — gate (`track.spotifyUri`→url; connected; quota) → check a per-track local cache file (e.g. `context.cacheDir/antra/<trackId>.flac`); if present+non-empty → return `StreamUrl(url=fileUri, origin="antra", codec="flac", bitsPerSample=24, ...)` WITHOUT a job (no quota spend). Else: `createJob → pollStatus complete → download bytes (GET downloadUrl with cookie) → write cache file → return StreamUrl(file uri)`. On failure/timeout → null (registry falls to YouTube). Bound by a timeout like `KennyyStreamResolver`'s; propagate `CancellationException`.

- [ ] **Step 1: Failing test (cache hit)** — pre-create the cache file; assert `resolve` returns a `file://` StreamUrl with `origin="antra"` and the client's `createJob` is NEVER called (no quota spend). Inject `AntraClient` + a temp cacheDir.
- [ ] **Step 2: Run — FAIL → implement cache-hit path → PASS.**
- [ ] **Step 3: Failing test (cache miss → job → file)** — no cache file; fakes drive job→complete→bytes; assert a cache file is written and the StreamUrl points at it.
- [ ] **Step 4: Run — FAIL → implement fetch+cache path → PASS.**
- [ ] **Step 5: Failing test (gating)** — null when no spotifyUri / not connected / job failed.
- [ ] **Step 6: Run — FAIL → implement → PASS.**
- [ ] **Step 7: Commit** — `feat(antra): AntraStreamResolver (download-to-cache, play local file)`

### Task 8: DI module + register in both registries

**Files:**
- Create: `data/download/.../lossless/antra/di/AntraModule.kt` (mirror `KennyyModule.kt`) — provide `AntraCredentialStore`, the antra `OkHttpClient` (with `AntraCookieInterceptor`), `AntraClient`, and `AntraSource`; contribute `AntraSource` into the multibound/registered set the `LosslessSourceRegistry` consumes (match how `KennyyModule` registers).
- Modify: `core/media/.../streaming/StreamSourceRegistry.kt` — inject `AntraStreamResolver`; add `add("antra" to { t -> antra.resolve(t) })` to the resolver list AFTER squid and BEFORE youtube (both the `forceYt` and normal branches, mirroring how `youtube` is added; gate `if (antra.isUsable())` if cheap, else let it self-gate by returning null).
- Test: `core/media/.../streaming/StreamSourceRegistryTest.kt` (extend existing) — assert ordering puts antra after squid, before youtube; assert antra is skipped when not connected.

- [ ] **Step 1: Failing test** — in `StreamSourceRegistryTest`, mock kennyy+squid miss, antra returns a StreamUrl → assert antra served; and assert order (antra tried before youtube).
- [ ] **Step 2: Run — FAIL.**
- [ ] **Step 3: Implement** the `StreamSourceRegistry` wiring + `AntraModule`. Notes verified against the real code:
    - Download side: `KennyyModule` uses `@Binds @IntoSet` into `Set<LosslessSource>`; `LosslessSourceRegistry` orders by `LosslessSourcePreferences.priorityOrder` and **appends sources not in that list, in registration order** — so a fresh `AntraSource` naturally falls last. Register it the same `@IntoSet` way; **also check `LosslessSourcePreferences`' default priority list and append `"antra"` there if a hardcoded default exists** (so it's last, not accidentally first).
    - Stream side: `StreamSourceRegistry` has **no** health-gate call — resolvers self-gate by returning null. So antra's stream wiring is just adding it to the resolver list after squid / before youtube; **no `LosslessSourceHealthGate` call is needed on the stream side** (the spec's "gated by health gate" applies to the download registry, which already does it for all `LosslessSource`s including antra).
- [ ] **Step 4: Run — PASS;** then `./gradlew :app:assembleDebug` to confirm the Hilt graph compiles end-to-end.
- [ ] **Step 5: Commit** — `feat(antra): register AntraSource + AntraStreamResolver in failover chains`

---

## Phase 5 — Connect UI + on-device verification

### Task 9: `AntraConnectScreen` (WebView login + cookie harvest)

**Files:**
- Create: `feature/settings/.../components/AntraConnectScreen.kt` (mirror `SquidWtfCaptchaScreen.kt`)
- Modify: `feature/settings/.../SettingsScreen.kt` + `SettingsViewModel.kt` + nav (`app/.../navigation/StashNavHost.kt`) — add a "Connect antra" row that opens the screen; show connected username / "Reconnect" when stale.

- [ ] **Step 1:** Mirror `SquidWtfCaptchaScreen`: an `AndroidView` WebView loading `https://antra.hoshi.cfd`, JS + DOM storage enabled, a normal browser User-Agent. Let the user log in; Cloudflare's JS challenge runs in the WebView.
- [ ] **Step 2: Harvest** — on each page-finished, query `CookieManager.getInstance().getCookie("https://antra.hoshi.cfd")`; when BOTH `session=` and `cf_clearance=` are present, also fetch `/api/auth/me` (via the WebView or a one-shot OkHttp with those cookies) to confirm a `username`; on success call `viewModel.onAntraConnected(session, cfClearance, username)` → `AntraCredentialStore.save(...)` and pop the screen.
- [ ] **Step 3:** Settings row: not-connected → "Connect antra"; connected → "antra: <username> (Reconnect)". Tapping opens the WebView.
- [ ] **Step 4:** Manual build + UI smoke: `./gradlew :app:installDebug`; open Settings → Connect antra → log in → confirm the row shows the username.
- [ ] **Step 5: Commit** — `feat(antra): in-app WebView connect + Settings entry`

### Task 10: On-device end-to-end verification (decides A vs B)

> No code unless this fails. This is the empirical Cloudflare test.
>
> **RESULT 2026-06-09: PASSED — Approach A confirmed, Phase 6 not needed.** Run via a temporary probe (commit `eb5c271b`, reverted) instead of an organic registry-routed download: full me→resolve→createJob→poll→`/download` lifecycle over plain OkHttp returned a 27 MB `fLaC` file with zero Cloudflare 403s. Details in the spec's Verification Results.

- [x] **Step 1:** Connect antra (Task 9). ~~Find a streamable track~~ → probe used a hardcoded test track (`1mMYaXpT65iZDtvfRA9EkE`, 'Fresh').
- [x] **Step 2 (download):** resolve→job→poll→`/download` returned FLAC bytes (`bytes=27131849 magic='fLaC'`), no Cloudflare `403` anywhere. (Duration backstop / Now Playing badge not exercised by the probe — release QA.)
- [ ] **Step 3 (stream):** deferred to release QA (same `AntraClient`+interceptor transport the probe verified; spends a single per cache-miss).
- [ ] **Step 4 (no-regression):** deferred to release QA (kennyy/squid/YT with antra connected).
- [x] **Step 5:** No 403s → **Approach A sufficient; Phase 6 skipped.**
- [x] **Step 6:** Spec `## Verification Results` updated with the outcome.

---

## Phase 6 — Approach B (CONTINGENCY ONLY — build only if Task 10 Step 5 hit 403)

### Task 11: Route antra requests through the WebView (cf_clearance + matching TLS)

**Files:** Create `core/media/` or `data/download/` `AntraWebViewProxy.kt` + tests; swap `AntraClient`'s transport from OkHttp to the WebView proxy for the antra-API calls (and, if `/download` also 403s, fetch the FLAC as a blob inside the WebView and hand bytes back).

- [ ] **Step 1:** Headless/background `WebView` retained for the antra session; expose `suspend fun request(method, path, body): String` that runs `fetch()` inside the page context (which holds a valid `cf_clearance` + matching TLS fingerprint) and returns the response text via a JS bridge.
- [ ] **Step 2:** Re-point `AntraClient.resolve/createJob/pollStatus/me` at the proxy; keep the same parsing/tests (swap only the transport behind an interface so Task 4's tests still pass against a fake transport).
- [ ] **Step 3:** For `/download`: try OkHttp+cookie first; on 403, `fetch()` the blob in the WebView, base64 back, write the cache file.
- [ ] **Step 4:** Re-run Task 10 on-device. Append final result to the spec.
- [ ] **Step 5: Commit** — `feat(antra): WebView request-proxy fallback for Cloudflare`

---

## Definition of done
- Phases 1–5 complete, all new unit tests green, `:app:assembleDebug` compiles, on-device Task 10 passes (download + stream + no-regression), spec `## Verification Results` appended. Phase 6 only if 403s forced it. Pre-existing unrelated red (`YtLibraryCanonicalizerTest`) ignored.
