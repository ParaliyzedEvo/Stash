# ArcodSource (arcod.xyz) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `arcod.xyz` as Stash's 3rd lossless source (download + streaming of clear hi-res Qobuz FLAC), ranked after kennyy/squid and before the YouTube fallback.

**Architecture:** A new `arcod` package mirroring the `qobuz` source. Auth is a Supabase JWT (Google OAuth) harvested from a WebView's localStorage, replayed as `Authorization: Bearer` on a **derived** OkHttpClient (the `QobuzApiClient` pattern — never the shared client), and refreshed via Supabase's token endpoint. Downloads are job-based (create → poll until `completed` → download URL); the resulting `dl.arcod.xyz/*.flac` URL is open + Range-supporting, so streaming plays it through the existing default media-source factory with no player changes.

**Tech Stack:** Kotlin, OkHttp, kotlinx.serialization, Hilt, DataStore, Media3, Android WebView, JUnit + MockWebServer + MockK + Truth.

**Spec:** `docs/superpowers/specs/2026-06-16-arcod-lossless-source-design.md`

**Branch:** `feat/arcod-source` (off master @ v0.9.53). Antra was removed in v0.9.53 — its files are **git-history references only** (recover a pattern with `git show 89aa17c8^:<path>` if useful), not extant.

**Reference patterns to read before starting:**
- `data/download/.../lossless/qobuz/QobuzApiClient.kt` — the **derived-client** pattern (`sharedClient.newBuilder().addInterceptor(...).build()`) + its rationale (keeps auth off the shared client, avoids a `:core:network` cycle). THE model for `ArcodClient` + `ArcodAuthInterceptor`.
- `data/download/.../lossless/qobuz/QobuzSource.kt` — `LosslessSource` impl: gating, rate-limiter calls, `SourceResult`, cancellation.
- `data/download/.../lossless/LosslessSource.kt` — interface + `SourceResult`/`AudioFormat`/`TrackQuery`.
- `data/download/.../lossless/AggregatorRateLimiter.kt` — `Config` fields + `init` `configs[...]`.
- `data/download/.../lossless/LosslessSourcePreferences.kt` — `DEFAULT_PRIORITY` (currently `["squid_qobuz","kennyy_qobuz"]`).
- `data/download/.../matching/MatchScorer.kt` — text normalization/similarity to port into `ArcodMatcher`.
- `core/media/.../streaming/QobuzStreamResolver.kt` + `StreamSourceRegistry.kt` — stream resolver + chain.
- `core/media/.../service/StashPlaybackService.kt` (`streamingTrackId` predicate) — confirms `origin != "youtube"` → default factory (no change needed for arcod).
- `data/download/.../lossless/squid/SquidWtfCaptchaInterceptor.kt` — host-scoped interceptor + `@Volatile` reactive cache shape.
- For the Settings WebView row + nav: `feature/settings/.../components/SquidWtfCaptchaScreen.kt` (the surviving WebView-cookie-capture screen) + `app/.../navigation/StashNavHost.kt` + `TopLevelDestination.kt` + `SettingsAudioQualityScreen.kt`.

**Conventions:** TDD (failing test → run → impl → pass → commit). Re-throw `CancellationException` before any `catch (Exception)` in suspend funcs. **Build hygiene (Windows + Gradle):** run tests FOREGROUND with `--no-daemon` and a `--tests` class filter; a cold module compile can take ~8–12 min (normal — let it finish, no `gradlew --stop` mid-run); if a `registry.bin` BindException or "Unable to delete directory … test-results" lock appears, delete `%USERPROFILE%\.gradle\daemon\<ver>\registry.bin`+`.lock` and/or kill leftover `Android Studio\jbr` java procs and re-run once. Do NOT run the full `:core:media` suite (a flaky network test hangs) — only filtered classes. Gate any DI change on `:app:assembleDebug` (per-module `compileDebugKotlin` does NOT catch Hilt-graph errors).

**Package:** `com.stash.data.download.lossless.arcod` (download side, in `:data:download`); the stream resolver + connect screen live in `:core:media` / `:feature:settings` respectively.

**HAR vectors for test-locking** (`~/Downloads/arcod.xyz.2222.har`): real `get-music`, job-create, poll (`status:"completed"` + `downloadUrl`), and `/url` responses. Use captured JSON in parse tests.

---

## Phase 0 — Credentials + token refresh

### Task 1: `ArcodCredentialStore`

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/lossless/arcod/ArcodCredentialStore.kt`
- Test: `data/download/src/test/kotlin/com/stash/data/download/lossless/arcod/ArcodCredentialStoreTest.kt`

A `@Singleton` DataStore-backed store for the Supabase session: `accessToken: String?`, `refreshToken: String?`, `expiresAtMs: Long`. Mirror how `LosslessSourcePreferences` builds a `preferencesDataStore` (its own store, name `arcod_credentials`). API:
- `val accessToken: Flow<String?>`, `suspend fun accessTokenNow(): String?`
- `suspend fun isConnected(): Boolean` = access token non-blank
- `suspend fun session(): ArcodSession?` (data class `ArcodSession(accessToken, refreshToken, expiresAtMs)`) or null
- `suspend fun save(accessToken, refreshToken, expiresAtMs)`
- `suspend fun markStale()` (clears all three)

- [ ] **Step 1: failing test** — save → `isConnected()` true + `session()` returns the values; `markStale()` → `isConnected()` false. Use a temp DataStore (look at how an existing `*PreferencesTest` in `:data:download` or `:core:data` constructs a test DataStore; if none, use `PreferenceDataStoreFactory.create` over a `tmpFolder` file in the test).
- [ ] **Step 2:** run `./gradlew --no-daemon :data:download:testDebugUnitTest --tests "*ArcodCredentialStoreTest"` → FAIL.
- [ ] **Step 3:** implement.
- [ ] **Step 4:** run → PASS.
- [ ] **Step 5:** commit `feat(arcod): credential store (supabase session)`.

### Task 2: `ArcodTokenRefresher`

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/lossless/arcod/ArcodTokenRefresher.kt`
- Test: `.../arcod/ArcodTokenRefresherTest.kt`

Refreshes the Supabase access token. `@Singleton @Inject constructor(sharedClient: OkHttpClient, store: ArcodCredentialStore)`. Uses the **plain shared client** (NOT a Bearer-bearing client — the refresh call authenticates with `apikey` + the refresh token, never the access token). `internal var supabaseUrl = "https://fnlghyzwyoklfqyhqlav.supabase.co"`, `internal var anonKey = "<supabase anon key>"` (the public anon JWT — extract from the HAR's supabase request `apikey` header and embed as a constant; it is a public key).

`suspend fun refresh(): String?`: POST `$supabaseUrl/auth/v1/token?grant_type=refresh_token`, headers `apikey: <anonKey>`, `Content-Type: application/json`, body `{"refresh_token":"<stored refresh token>"}`. On 200 parse `{access_token, refresh_token, expires_in}` → compute `expiresAtMs = now + expires_in*1000` → `store.save(...)` → return new access token. On failure → `store.markStale()` + return null. Single-flight via a `Mutex`. Re-throw `CancellationException`.

- [ ] **Step 1: failing test (MockWebServer)** — `setUp` saves a stored session with a refresh token; enqueue a 200 `{"access_token":"new","refresh_token":"r2","expires_in":3600}`; assert `refresh()` returns `"new"` and `store.session().accessToken == "new"` and `expiresAtMs` ~ now+3600s. Second test: 400/401 → `refresh()` null + `store` marked stale. Third: two concurrent `refresh()` calls → only ONE POST hits the server (single-flight).
- [ ] **Step 2–4:** fail → implement → pass (`--tests "*ArcodTokenRefresherTest"`).
- [ ] **Step 5:** commit `feat(arcod): supabase token refresher (plain client, single-flight)`.

---

## Phase 1 — Auth interceptor (on a derived client)

### Task 3: `ArcodAuthInterceptor`

**Files:**
- Create: `.../arcod/ArcodAuthInterceptor.kt`
- Test: `.../arcod/ArcodAuthInterceptorTest.kt`

`@Singleton @Inject constructor(store: ArcodCredentialStore, refresher: ArcodTokenRefresher) : Interceptor`. Behaviour:
1. host not in {`arcod.xyz`, `api.arcod.xyz`} → `chain.proceed(req)` untouched (belt-and-suspenders; only the derived client uses it).
2. Determine the token: `runBlocking { if expired → refresher.refresh() else store.accessTokenNow() }`. (Expired = `store.session()?.expiresAtMs` is null or `<= now + 30_000` skew.) If null → proceed without header (let it 401; source fails over).
3. Attach `Authorization: Bearer <token>`, proceed. If response code == 401 → `resp.close()`, `refresher.refresh()` once; null → return original proceed; else retry the request once with the fresh token.

Mirror the structure of the (history) amz captcha interceptor / `SquidWtfCaptchaInterceptor`. Test with a fake `Interceptor.Chain` (queue response codes, capture requests):
- [ ] off-host → no header, refresher never called.
- [ ] valid unexpired token → header attached, no refresh.
- [ ] expired token → `refresher.refresh()` called, fresh header attached.
- [ ] 401 response → one refresh + one retry with new token (no loop).
- [ ] no token + refresh returns null → proceeds without header (no crash/loop).
Use MockK for `store`/`refresher`.
- [ ] **Steps:** failing test → `--tests "*ArcodAuthInterceptorTest"` FAIL → implement → PASS → commit `feat(arcod): bearer auth interceptor (refresh + retry-on-401)`.

---

## Phase 2 — API models + client

### Task 4: `ArcodApiModels`

**Files:** Create `.../arcod/ArcodApiModels.kt`; Test `.../arcod/ArcodApiModelsTest.kt`

Lenient `Json { ignoreUnknownKeys = true; isLenient = true }`. DTOs (model only what's used; `@SerialName` for snake_case):
- Search: `ArcodSearchResponse(success: Boolean = false, data: ArcodSearchData? = null)`, `ArcodSearchData(tracks: ArcodTrackList? = null)`, `ArcodTrackList(items: List<ArcodTrackItem> = emptyList())`, `ArcodTrackItem(id, title, isrc?, duration: Int? = null, @SerialName("maximum_bit_depth") maxBitDepth: Int? = null, performer: ArcodNamed? = null, album: ArcodAlbum? = null)`, `ArcodNamed(name? , id? )`, `ArcodAlbum(id: String? = null, title? , artist: ArcodNamed? = null, image: ArcodImage? = null, @SerialName("release_date_original") releaseDate? , @SerialName("tracks_count") tracksCount: Int? = null)`, `ArcodImage(large?, small?)`.
- Job: `ArcodJob(id, status, progress: Int = 0, error: String? = null, fileName: String? = null, fileSize: Long? = null, downloadUrl: String? = null)`. (Note: `id`+`status` always present; `downloadUrl` present on `completed`.)
- Url: `ArcodUrlResponse(downloadUrl, fileName? , expiresIn: Int? = null)`.

**Test:** parse the real captured `get-music` JSON (a track item has `id`, `album.id`, `isrc`); parse the captured poll JSON (`status:"completed"`, `downloadUrl` non-null); parse `/url` JSON. Assert fields; assert unknown keys don't break it. Commit `feat(arcod): api models`.

### Task 5: `ArcodClient`

**Files:** Create `.../arcod/ArcodClient.kt`; Test `.../arcod/ArcodClientTest.kt`

`@Singleton @Inject constructor(sharedClient: OkHttpClient, authInterceptor: ArcodAuthInterceptor)`. Build the **derived** client like QobuzApiClient: `private val httpClient = sharedClient.newBuilder().addInterceptor(authInterceptor).build()`. `internal var baseUrl = "https://arcod.xyz/api"`. Methods (suspend, `withContext(Dispatchers.IO)`, re-throw `CancellationException` before generic catch; throw `ArcodRateLimitedException` on 429 — define it here):
- `suspend fun search(query: String): List<ArcodTrackItem>` — `GET $baseUrl/get-music?q=<urlencoded>&offset=0` → `ArcodSearchResponse.data.tracks.items`. (Open endpoint; the Bearer header from the interceptor is harmless — confirm in on-device verify; if Qobuz/ARCOD ever rejects it, route search through `sharedClient` instead. For now use `httpClient`.)
- `suspend fun createJob(body: ArcodJobRequest): ArcodJob?` — `POST $baseUrl/v2/downloads`, JSON body (the verbatim field set from the spec; `quality:27, format:"FLAC"`). Parse `ArcodJob`.
- `suspend fun pollStatus(jobId: String, timeoutMs: Long = 60_000, intervalMs: Long = 1_500): ArcodJob?` — loop `GET $baseUrl/v2/downloads/<jobId>` until `status == "completed"` (return it) or `error != null` / `status == "error"` (return null) or timeout (return null). `delay(intervalMs)` between polls. Re-throw `CancellationException`.
- `fun downloadUrlFrom(job: ArcodJob): String?` = `job.downloadUrl` (completed jobs carry it; the separate `POST /url` is optional and only needed to refresh an expired URL — out of scope v1).

Build the JSON body via a `@Serializable ArcodJobRequest` data class (avoids manual escaping). Headers: UA/Referer matching the HAR (`Origin: https://arcod.xyz`, `Referer: https://arcod.xyz/`).

**Test (MockWebServer, inject a no-op/relaxed `ArcodAuthInterceptor` or a real one with a mocked store):** search parses captured items; createJob returns job id; pollStatus stops on an enqueued `completed` (with `downloadUrl`), stops/returns null on `error`, and returns null on repeated `pending` past a short test timeout (use a tiny timeout/interval in the test); 429 → `ArcodRateLimitedException`. Commit `feat(arcod): api client (search/job/poll, derived client)`.

---

## Phase 3 — Matching + download source

### Task 6: `ArcodMatcher`

**Files:** Create `.../arcod/ArcodMatcher.kt`; Test `.../arcod/ArcodMatcherTest.kt`

Pure `object`. `data class ArcodMatch(val item: ArcodTrackItem, val confidence: Float)`. `fun best(query: TrackQuery, items: List<ArcodTrackItem>): ArcodMatch?`. Score on title + artist (artist = `item.performer?.name ?: item.album?.artist?.name`) similarity. **Pattern to copy: `QobuzSource`'s companion helpers `normalize`/`jaccard`/`artistSimilarity` + its `confidence()` method (`QobuzSource.kt` ~358-439) — that's the exact artist+title/jaccard/duration/ISRC shape we want.** They're `internal`, but REIMPLEMENT them in `ArcodMatcher` (don't import — keep it a self-contained pure object). (Not `MatchScorer` — that's a YouTube-result scorer over `YtDlpSearchResult`, wrong shape.) When `query.durationMs != null` and `item.duration != null`, reject candidates whose duration differs by > ~5 s (studio-vs-live guard). ISRC is NOT used to *search* (search is text) but if `query.isrc != null && item.isrc == query.isrc` boost confidence to ≥0.95. Threshold 0.5 (match QobuzSource). Return best ≥ threshold else null.

**Test:** exact match high + returned; wrong artist+title → null; duration-mismatch rejected; ISRC match boosts; bracket/`feat.` tolerance in titles. Commit `feat(arcod): matcher`.

### Task 7: `AggregatorRateLimiter` config for `arcod`

**Files:** Modify `data/download/.../lossless/AggregatorRateLimiter.kt` (init block); Test: extend `AggregatorRateLimiterTest`.

Add `configs["arcod"]` conservatively (mirror `kennyy_qobuz`): e.g. `tokensPerSecond = 1.0/2.0, burstCapacity = 2.0, backoff429Ms = 60_000L, circuitBreakAfter = 5, circuitBreakDurationMs = 10*60_000L`. ARCOD's 429 = genuine over-rate (account/IP cap) so leave `rateLimitTripsBreaker` default (true). Test: `configs["arcod"]` present + a 429 backoff/breaker behaves. Commit `feat(arcod): rate-limit config`.

### Task 8: `ArcodSource : LosslessSource`

**Files:** Create `.../arcod/ArcodSource.kt`; Test `.../arcod/ArcodSourceTest.kt`

`@Singleton @Inject constructor(client: ArcodClient, credentialStore: ArcodCredentialStore, matcher (object — call directly), rateLimiter: AggregatorRateLimiter)`. `id="arcod"`, `displayName="ARCOD (Qobuz lossless)"`.
- `isEnabled()` = `credentialStore.isConnected() && !rateLimiter.stateOf(id).isCircuitBroken`.
- `rateLimitState()` = `rateLimiter.stateOf(id)`.
- `resolve(query)`: if `!isEnabled()` null; `if (!rateLimiter.acquire(id)) null`; then:
  1. `items = client.search("${query.artist} ${query.title}".trim())` (NEVER `searchTerms()`/ISRC as the query).
  2. `match = ArcodMatcher.best(query, items)` ; null → `reportFailure`; null.
  3. Build `ArcodJobRequest` from `match.item` (albumId = `item.album?.id`, trackId = `item.id`, albumTitle/artistName/coverUrl/releaseDate/tracksCount from `item.album`, `quality=27, format="FLAC"`). If `albumId`/`trackId` missing → `reportFailure`; null.
  4. `job = client.createJob(req)` ; null → `reportFailure`; null. `completed = client.pollStatus(job.id)` ; null → `reportFailure`; null. `url = completed.downloadUrl` ; null → `reportFailure`; null.
  5. `reportSuccess`; return `SourceResult(sourceId="arcod", downloadUrl=url, downloadHeaders=emptyMap(), format=AudioFormat("flac",0,0,0), confidence=match.confidence, sourceTrackId=job.id, coverArtUrl=item.album?.image?.large)`.
  6. `catch (e: ArcodRateLimitedException) { reportRateLimited; null }`; re-throw `CancellationException` before `catch (Exception) { reportFailure; null }`.

**Test (MockK client/store/limiter):** disabled→null no search; acquire false→null; no match→null+reportFailure; job null→null; poll null→null; happy path→SourceResult shape (empty headers, flac/0/0/0, confidence from matcher, sourceTrackId=jobId) + reportSuccess; 429→reportRateLimited; cancellation re-throw. Commit `feat(arcod): AmzSource… ArcodSource (LosslessSource, job-based download)`.

---

## Phase 4 — DI + download-chain wiring

### Task 9: `ArcodModule` + `DEFAULT_PRIORITY` + registry placement

**Files:** Create `.../arcod/di/ArcodModule.kt`; Modify `LosslessSourcePreferences.kt` (`DEFAULT_PRIORITY`); Test: `LosslessSourceRegistryTest`.

- `ArcodModule`: `@Module @InstallIn(SingletonComponent::class) abstract class` with `@Binds @IntoSet abstract fun bindArcodAsLosslessSource(impl: ArcodSource): LosslessSource`. (Mirror `QobuzModule`. NO interceptor multibinding — `ArcodClient` owns the derived client.)
- `DEFAULT_PRIORITY` → append `"arcod"` → `["squid_qobuz","kennyy_qobuz","arcod"]`; update KDoc.
- Test: registry `orderedSources()` under default priority places `arcod` last; arcod only resolves after squid+kennyy miss.

**Verify:** `./gradlew --no-daemon :data:download:testDebugUnitTest --tests "*LosslessSourceRegistryTest"` AND `./gradlew --no-daemon :app:assembleDebug` (Hilt graph gate — the `@IntoSet` must resolve). Both BUILD SUCCESSFUL. Commit `feat(arcod): register in lossless chain (last, before YouTube)`.

---

## Phase 5 — Streaming

### Task 10: `ArcodStreamResolver`

**Files:** Create `core/media/.../streaming/ArcodStreamResolver.kt`; Test `core/media/.../streaming/ArcodStreamResolverTest.kt`

`@Singleton @Inject constructor(client: ArcodClient)` (reuse the `:data:download` ArcodClient + ArcodMatcher — confirm `core:media` depends on `:data:download`, it does). `suspend fun resolve(track: TrackEntity): StreamUrl?`: build a `TrackQuery` from the entity (copy QobuzStreamResolver's mapping) → `client.search` → `ArcodMatcher.best` → `createJob` → `pollStatus` → `downloadUrl` → `StreamUrl(url=downloadUrl, expiresAtMs = System.currentTimeMillis()+280_000, codec="flac", origin=ORIGIN, coverArtUrl=item.album?.image?.large)`. `companion { const val ORIGIN = "arcod" }`. Re-throw `CancellationException`.

**Test (MockK client):** happy path → arcod-origin StreamUrl with the dl url + expiry ~280s; no match → null; job/poll null → null. Commit `feat(arcod): stream resolver`.

### Task 11: Register `ArcodStreamResolver` in `StreamSourceRegistry`

**Files:** Modify `core/media/.../streaming/StreamSourceRegistry.kt`; Test `StreamSourceRegistryTest`.

Add `private val arcod: ArcodStreamResolver` to the constructor (after `qobuz`). In the **normal** branch only, insert `add("arcod" to arcod::resolve)` AFTER `squid` and BEFORE the youtube add. Update the existing tests' registry construction (mock `ArcodStreamResolver`, default its `resolve` → null in existing tests) and add: amz— er, arcod consulted after kennyy/squid miss + before youtube; absent from forceYt branch.

**Verify:** `./gradlew --no-daemon :core:media:testDebugUnitTest --tests "*StreamSourceRegistryTest" --tests "*ArcodStreamResolverTest"` → SUCCESSFUL (filtered — NOT the full suite). Commit `feat(arcod): register stream resolver (after squid, before youtube)`.

---

## Phase 6 — Connect UI (WebView Google login + localStorage harvest)

### Task 12: `ArcodConnectScreen` + ViewModel hook + Settings row + nav

**Files:**
- Create: `feature/settings/.../components/ArcodConnectScreen.kt`
- Modify: `feature/settings/.../SettingsViewModel.kt` (add `onArcodConnected(access, refresh, expiresAtMs)` → `arcodCredentialStore.save(...)`; inject `ArcodCredentialStore`; add `arcodConnected` StateFlow from `credentialStore.accessToken` for the row label)
- Modify: `feature/settings/.../SettingsUiState.kt` (+ `arcodConnected: Boolean = false`)
- Modify: `feature/settings/.../SettingsAudioQualityScreen.kt` (add a "Connect ARCOD" `SettingsNavRow` — the component used by the Equalizer row — INSIDE the Lossless card's `AnimatedVisibility` block, e.g. just below the `LosslessRoutingStatus` composable. NOTE: there is no standalone squid `SettingsNavRow`; the squid solver is an `onSolveCaptcha` button inside `LosslessRoutingStatus`, so don't look for one. New screen param: `onNavigateToArcodConnect`)
- Modify: `app/.../navigation/TopLevelDestination.kt` (+ `@Serializable data object ArcodConnectRoute`)
- Modify: `app/.../navigation/StashNavHost.kt` (+ `composable<ArcodConnectRoute>` hosting `ArcodConnectScreen(onConnected = viewModel::onArcodConnected, onClose = { popBackStack() })`, using the Settings-scoped ViewModel like the SquidWtfCaptchaRoute does; pass `onNavigateToArcodConnect` into `SettingsAudioQualityScreen`)

`ArcodConnectScreen`: a `WebView` (mirror the surviving `SquidWtfCaptchaScreen` structure) pointed at `https://arcod.xyz/`, with `settings.javaScriptEnabled = true` and **`settings.domStorageEnabled = true`** (REQUIRED — localStorage is off by default). A polling coroutine runs `webView.evaluateJavascript("localStorage.getItem('sb-fnlghyzwyoklfqyhqlav-auth-token')") { ... }`; when non-null, parse the JSON (Supabase stores `{"access_token","refresh_token","expires_at",...}`; `expires_at` is epoch SECONDS → `*1000` for ms) and call `onConnected(access, refresh, expiresAtMs)`, then close. Pin a desktop/mobile UA if Google login needs it.

This task is **UI/WebView — minimal unit-testable surface.** Do NOT force a brittle WebView unit test. Verify by: `./gradlew --no-daemon :app:assembleDebug` (compiles + Hilt graph with the new ViewModel injection + nav) → BUILD SUCCESSFUL. The real validation is the on-device step (Task 13). Commit `feat(arcod): connect screen (webview supabase login + localStorage harvest) + settings row`.

---

## Phase 7 — On-device verification

### Task 13: End-to-end on-device (REQUIRES device + user)

> Needs the user's ARCOD Google login + a device. ASK before installing (project rule: on-device actions that touch app data need consent). A "Force ARCOD only (test)" toggle (mirror the amz/forceYt toggle pattern) makes this deterministic — OPTIONAL; add only if blind testing proves too hard.

- [ ] `./gradlew --no-daemon :app:assembleDebug`; install (with consent); launch — confirm no crash, no Hilt error.
- [ ] Settings → Audio & Quality → **Connect ARCOD** → complete Google login in the WebView → confirm the token is harvested (logcat: credential saved; row shows connected).
- [ ] Trigger a track ARCOD has (force-arcod toggle, or a track kennyy/squid miss): confirm logcat shows search→job→poll(completed)→download; a real FLAC lands (download path) and/or a stream **plays audibly** via the default factory (byte-/ear-verify it's real audio — the amz lesson).
- [ ] Confirm failover to YouTube when ARCOD not connected / misses.
- [ ] Confirm a token-expiry refresh path works (force-expire `expiresAtMs` in the store, or play after >1h) — interceptor refreshes, playback continues.
- [ ] Append a `## Verification Results` section to the spec with findings (real bit-depth observed, refresh confirmed).

---

## Done criteria
- ARCOD resolves real clear Qobuz FLAC (download + progressive stream) for tracks after kennyy/squid miss, gated before YouTube.
- Supabase JWT auth via WebView login; token auto-refreshes; Bearer never leaves the arcod-derived client.
- All new unit tests green; `:app:assembleDebug` clean; on-device E2E confirmed (audible playback + a real downloaded FLAC).
