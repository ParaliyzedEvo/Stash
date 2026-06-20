# AmzSource (amz.squid.wtf Amazon Music) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `amz.squid.wtf` (Amazon Music) as an independent lossless source in Stash — download + progressive streaming of FLAC — slotted after kennyy/squid and before YouTube.

**Architecture:** A new `amz` package mirroring `qobuz`/`squid`. A host-scoped `AmzCaptchaInterceptor` on the shared OkHttp client mints + attaches an `x-captcha-token` (reusing the existing `AltchaSolver` PoW) and re-mints on stale-token responses — serving the API client, the download fetch, and the streaming data source from one seam. `AmzSource : LosslessSource` does search→track→stream; `AmzStreamResolver` plus a new `origin == "amz"` routing branch in `StashMediaSourceFactory` (Media3 `OkHttpDataSource`) gives authed progressive playback.

**Tech Stack:** Kotlin, OkHttp, kotlinx.serialization, Hilt, Media3 (`media3-datasource-okhttp`), JUnit + MockWebServer + MockK + Truth.

**Spec:** `docs/superpowers/specs/2026-06-15-amz-lossless-source-design.md`

**Key reference patterns (read before starting):**
- `data/download/.../lossless/squid/NativeSquidCaptchaSolver.kt` — challenge→solve→verify shape
- `data/download/.../lossless/squid/AltchaSolver.kt` — `solve(ChallengeParameters): Solution` (reused as-is)
- `data/download/.../lossless/squid/SquidWtfCaptchaInterceptor.kt` — host-scoped interceptor
- `data/download/.../lossless/qobuz/QobuzApiClient.kt` + `QobuzSource.kt` — API client + LosslessSource
- `data/download/.../lossless/LosslessSource.kt` — interface + `SourceResult`/`AudioFormat`/`TrackQuery`
- `data/download/.../lossless/AggregatorRateLimiter.kt` — per-source `configs[...]`
- `core/media/.../streaming/StashMediaSourceFactory.kt` + `StreamingMediaSourceFactory.kt` + `StreamSourceRegistry.kt` + `QobuzStreamResolver.kt`
- `core/network/.../di/NetworkModule.kt` — shared `OkHttpClient` provider

**Conventions:** TDD (test → fail → impl → pass → commit). Run unit tests with the module-scoped Gradle task. Commit after every green step. Re-throw `CancellationException` before any `catch (Exception)` in suspend funcs.

---

## Phase 0 — Dependency + on-device recon

### Task 0.1: Add the Media3 OkHttp data source artifact

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `core/media/build.gradle.kts`

- [ ] **Step 1: Add the version-catalog entry.** Under `[libraries]` in `gradle/libs.versions.toml`, next to the existing `media3-datasource` line, add:

```toml
media3-datasource-okhttp = { group = "androidx.media3", name = "media3-datasource-okhttp", version.ref = "media3" }
```

- [ ] **Step 2: Add the dependency to core:media.** In `core/media/build.gradle.kts`, next to the existing `media3-datasource` dependency:

```kotlin
implementation(libs.media3.datasource.okhttp)
```

- [ ] **Step 3: Verify it resolves.** Run: `./gradlew :core:media:compileDebugKotlin`. Expected: BUILD SUCCESSFUL (no new code uses it yet; this just proves the artifact resolves at media3 1.9.x).

- [ ] **Step 4: Commit.**

```bash
git add gradle/libs.versions.toml core/media/build.gradle.kts
git commit -m "build: add media3-datasource-okhttp for amz authed streaming"
```

### Task 0.2: On-device recon (informs later tasks; no code)

Run these once against `amz.squid.wtf` from the device/emulator (a fresh token via the browser HAR is fine) and record answers in this plan before Phase 7 / Phase 5 format handling:

- [ ] **Range support:** `curl -H 'x-captcha-token: <t>' -H 'Range: bytes=0-1023' 'https://amz.squid.wtf/api/stream?asin=<asin>&country=US&tier=best' -i`. Record: `206 Partial Content` + `Content-Range`? (→ seek-ahead responsive) or `200` ignoring Range? (→ far-seek re-fetches).
- [ ] **Stale-token code:** call `/api/search` with a bogus `x-captcha-token`. Record the status (expected 401; could be 403) — this is the interceptor's re-mint trigger (Task 2.x).
- [ ] **`/api/track` fields:** confirm whether the metadata includes a duration field and/or an available-format/tier indicator (informs Task 4 duration check + Task 5 format pre-gate).

Record findings here:
```
Range: <fill in>
Stale-token status: <fill in>
/api/track duration field: <fill in>   format/tier field: <fill in>
```

---

## Phase 1 — Captcha client

> **CORRECTION (during implementation, 2026-06-15): amz does NOT use squid's `AltchaSolver`.** amz's challenge `algorithm` is literally `"PBKDF2/SHA-256"` — real PBKDF2-HMAC-SHA256, not squid's iterated-truncated-SHA256 chain. Reusing `AltchaSolver` never matches the `keyPrefix` and hangs the solve to the 1M cap. The implemented solution is a dedicated **`AmzAltchaSolver`** (`PBKDF2(nonceBytes‖uint32_BE(counter), salt, iterations=cost, dkLen=keyLength)`, matched on `keyPrefix`), reverse-engineered from a live HAR vector and locked by `AmzAltchaSolverTest` (counter=563). Done + committed (`d009f23d`). The challenge/verify round-trip logic below still applies: echo amz's `parameters` block **verbatim** (amz omits `expiresAt`), do NOT reuse `NativeSquidCaptchaSolver`'s fixed-field builder.

### Task 1.1: `AmzCaptchaClient` — challenge → solve → verify → token

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/lossless/amz/AmzCaptchaClient.kt`
- Test: `data/download/src/test/kotlin/com/stash/data/download/lossless/amz/AmzCaptchaClientTest.kt`

- [ ] **Step 1: Write the failing test** (MockWebServer; lock the verify payload against the captured HAR — challenge has NO `expiresAt`).

```kotlin
package com.stash.data.download.lossless.amz

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class AmzCaptchaClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: AmzCaptchaClient

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        client = AmzCaptchaClient(OkHttpClient()).apply { baseUrl = server.url("/api").toString() }
    }
    @After fun tearDown() = server.shutdown()

    @Test fun `mint solves challenge and returns token`() = runTest {
        // Real captured challenge shape — NOTE: no expiresAt field.
        server.enqueue(MockResponse().setBody(
            """{"parameters":{"algorithm":"PBKDF2/SHA-256","cost":1000,"keyLength":32,""" +
            """"keyPrefix":"0d0301ca60ab63b9e18c9dc2c288e183","nonce":"f9f25960b45813de33fd107f596c3961",""" +
            """"salt":"fa12ba0b892664a85bc038b9d370bdd6"},"signature":"e7a947cd680676ad5b85bb000cb70cc6a4e82f2b302edbc17aaf131444d6ad87"}"""
        ))
        server.enqueue(MockResponse().setBody("""{"token":"1b52301ee504cf7464586d91ddecb1935ecd2eda432b9a81"}"""))

        val token = client.mint()

        assertThat(token).isEqualTo("1b52301ee504cf7464586d91ddecb1935ecd2eda432b9a81")
        server.takeRequest() // challenge GET
        val verify = server.takeRequest()
        assertThat(verify.path).endsWith("/captcha/verify")
        // The payload is base64-encoded inside {"payload":"<b64>"} — DECODE it
        // before asserting, or a raw-body substring check passes trivially.
        val b64 = Json.parseToJsonElement(verify.body.readUtf8())
            .jsonObject["payload"]!!.jsonPrimitive.content
        val decoded = String(java.util.Base64.getDecoder().decode(b64))
        // Requirement 1: the echoed parameters block must NOT contain expiresAt.
        assertThat(decoded).doesNotContain("expiresAt")
        assertThat(decoded).contains(""""keyPrefix":"0d0301ca60ab63b9e18c9dc2c288e183"""")
    }

    @Test fun `mint returns null on challenge http error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        assertThat(client.mint()).isNull()
    }
}
```

- [ ] **Step 2: Run — expect FAIL** (unresolved `AmzCaptchaClient`).
Run: `./gradlew :data:download:testDebugUnitTest --tests "*AmzCaptchaClientTest"`

- [ ] **Step 3: Implement `AmzCaptchaClient`.** Reuse `AltchaSolver`; echo amz's raw `parameters` substring verbatim into the verify payload; parse `{token}`.

```kotlin
package com.stash.data.download.lossless.amz

import android.util.Base64
import android.util.Log
import com.stash.data.download.lossless.squid.AltchaSolver
import com.stash.data.download.lossless.squid.ChallengeParameters
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Mints an `x-captcha-token` for amz.squid.wtf: GET /api/captcha/challenge →
 * [AltchaSolver] PoW → POST /api/captcha/verify → {token}.
 *
 * Uses the BARE shared OkHttpClient (no AmzCaptchaInterceptor) — and the
 * interceptor also bypasses /api/captcha/* — so minting never recurses.
 *
 * Requirement 1: amz omits `expiresAt`; we re-embed amz's raw `parameters`
 * JSON verbatim (only appending the solution) so the server HMAC `signature`
 * still verifies. Do NOT reuse NativeSquidCaptchaSolver's fixed-field builder.
 */
@Singleton
class AmzCaptchaClient @Inject constructor(
    private val bareClient: OkHttpClient,
) {
    internal var baseUrl: String = "https://amz.squid.wtf/api"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun mint(): String? = withContext(Dispatchers.IO) {
        try {
            val challengeRaw = get("$baseUrl/captcha/challenge") ?: return@withContext null
            val root = json.parseToJsonElement(challengeRaw).jsonObject
            val paramsObj = root["parameters"]?.jsonObject ?: return@withContext null
            // Raw parameters substring, byte-for-byte, for the verify echo.
            val paramsRaw = paramsObj.toString()
            val signature = root["signature"]?.jsonPrimitive?.contentOrNull ?: return@withContext null

            val p = paramsObj
            val params = ChallengeParameters(
                algorithm = p["algorithm"]!!.jsonPrimitive.content,
                cost = p["cost"]!!.jsonPrimitive.int,
                expiresAt = 0L, // amz omits it; AltchaSolver ignores it for the PoW
                keyLength = p["keyLength"]!!.jsonPrimitive.int,
                keyPrefix = p["keyPrefix"]!!.jsonPrimitive.content,
                nonce = p["nonce"]!!.jsonPrimitive.content,
                salt = p["salt"]!!.jsonPrimitive.content,
            )
            val sol = AltchaSolver.solve(params)
            val solutionJson =
                """{"counter":${sol.counter},"derivedKey":"${sol.derivedKey}","time":${sol.timeMs}}"""
            val payloadJson =
                """{"challenge":{"parameters":$paramsRaw,"signature":"$signature"},"solution":$solutionJson}"""
            val payloadB64 = Base64.encodeToString(payloadJson.toByteArray(), Base64.NO_WRAP)
            val verifyRaw = post("$baseUrl/captcha/verify", """{"payload":"$payloadB64"}""")
                ?: return@withContext null
            json.parseToJsonElement(verifyRaw).jsonObject["token"]?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            Log.w(TAG, "mint failed", e); null
        }
    }

    private fun get(url: String): String? = bareClient.newCall(
        Request.Builder().url(url).header("User-Agent", UA).header("Referer", REFERER).get().build()
    ).execute().use { if (it.isSuccessful) it.body?.string() else null }

    private fun post(url: String, body: String): String? = bareClient.newCall(
        Request.Builder().url(url).header("User-Agent", UA).header("Referer", REFERER)
            .post(body.toRequestBody(JSON_MT)).build()
    ).execute().use { if (it.isSuccessful) it.body?.string() else null }

    private companion object {
        const val TAG = "AmzCaptchaClient"
        const val UA = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36"
        const val REFERER = "https://amz.squid.wtf/"
        val JSON_MT = "application/json; charset=utf-8".toMediaType()
    }
}
```

> NOTE: `AltchaSolver` is `internal object` and `ChallengeParameters`/`Solution` are public `data class`es, all in the `squid` package of the **same module** (`data:download`). Kotlin `internal` = module-scoped, so all three are reachable from the sibling `amz` package — no visibility change needed.
>
> NOTE on `time`: `Solution.timeMs` is a `Long`; the code emits `"time":<long>`. The existing `NativeSquidCaptchaSolver` sends `time` as a `Double`. amz's server treats `time` as non-security-critical (logging/scoring only), so either is accepted — but the locked-payload assertion must match whatever shape the code emits (here: integer). Keep the test's expected payload consistent with the `Long` form.

- [ ] **Step 4: Run — expect PASS.** Run: `./gradlew :data:download:testDebugUnitTest --tests "*AmzCaptchaClientTest"`

- [ ] **Step 5: Commit.** `git add … && git commit -m "feat(amz): captcha client (reuse AltchaSolver, verbatim params echo)"`

---

## Phase 2 — Captcha interceptor (the one auth seam)

### Task 2.1: `AmzCaptchaInterceptor` — attach token, single-flight mint, retry on stale

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/lossless/amz/AmzCaptchaInterceptor.kt`
- Test: `data/download/src/test/kotlin/com/stash/data/download/lossless/amz/AmzCaptchaInterceptorTest.kt`

Behaviour:
1. host != `amz.squid.wtf` → proceed untouched.
2. path startsWith `/api/captcha` → proceed untouched (no token; avoids mint recursion).
3. else: ensure a token (single-flight `Mutex`; mint via `AmzCaptchaClient` if absent), attach `x-captcha-token`. If the response is the stale-token code (from Task 0.2; default 401), mint once more and retry the request once.

- [ ] **Step 1: Failing test** (MockK the `AmzCaptchaClient`; use a real OkHttp call against MockWebServer is heavier — instead unit-test the `intercept` contract with a fake `Chain`). Provide tests:
  - off-host request passes through, no mint;
  - `/api/captcha/*` passes through, no mint;
  - `/api/search` with no token → mints once, attaches header;
  - stale-token response → mints again + retries once;
  - concurrent calls → single mint (verify `coVerify(exactly = 1)`-style via a counting fake).

(Full test body mirrors `SquidWtfCaptchaInterceptor` test style; use a fake `Interceptor.Chain` capturing the request and returning a queued `Response`.)

- [ ] **Step 2: Run — expect FAIL.** `./gradlew :data:download:testDebugUnitTest --tests "*AmzCaptchaInterceptorTest"`

- [ ] **Step 3: Implement.**

```kotlin
package com.stash.data.download.lossless.amz

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import okhttp3.Response

@Singleton
class AmzCaptchaInterceptor @Inject constructor(
    private val captchaClient: AmzCaptchaClient,
) : Interceptor {

    @Volatile private var token: String? = null
    private val mintMutex = Mutex()

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (req.url.host != HOST) return chain.proceed(req)
        if (req.url.encodedPath.startsWith("/api/captcha")) return chain.proceed(req)

        val current = token ?: mintBlocking()
        if (current == null) return chain.proceed(req) // let it 401; caller fails over
        val resp = chain.proceed(req.newBuilder().header("x-captcha-token", current).build())
        if (resp.code != STALE_CODE) return resp

        // Stale token: mint a fresh one and retry ONCE.
        resp.close()
        val fresh = mintBlocking(force = true) ?: return chain.proceed(req)
        return chain.proceed(req.newBuilder().header("x-captcha-token", fresh).build())
    }

    private fun mintBlocking(force: Boolean = false): String? = runBlocking {
        mintMutex.withLock {
            if (!force) token?.let { return@withLock it } // another caller already minted
            captchaClient.mint()?.also { token = it }
        }
    }

    private companion object {
        const val HOST = "amz.squid.wtf"
        const val STALE_CODE = 401 // confirm via Task 0.2; change to 403 if needed
    }
}
```

> If Task 0.2 shows the stale-token status is 403 (not 401), change `STALE_CODE` **and** the queued response code in the Task 2.1 retry test together — otherwise the test encodes the wrong trigger and passes against the wrong behaviour.

- [ ] **Step 4: Run — expect PASS.**
- [ ] **Step 5: Commit.** `… -m "feat(amz): captcha interceptor (single-flight mint, retry-on-stale)"`

### Task 2.2: Register `AmzCaptchaInterceptor` on the SHARED OkHttpClient (multibinding — mandatory)

**Files:**
- Modify: `core/network/.../di/NetworkModule.kt`
- Create: `data/download/.../lossless/amz/di/AmzInterceptorModule.kt` (the `@IntoSet` binding)

> **Why this seam and not a derived client.** The token must ride THREE call sites: `AmzApiClient`'s catalog calls, the **download byte-fetch** (`LosslessUrlDownloader` — verified: it injects the *bare* shared `OkHttpClient` and applies `SourceResult.downloadHeaders` as static headers, so it has no per-source interceptor hook), and the **streaming `OkHttpDataSource`** (Task 7.3, built on the injected shared client). A derived `@AmzClient` given only to `AmzApiClient` would reach NEITHER the download fetch nor the stream → amz silently fails both (Requirement 3). So the interceptor MUST live on the shared client. Because it's host-scoped (`amz.squid.wtf` only) and bypasses `/api/captcha/*`, it's a no-op for every other host/caller — safe on the shared client by construction. `core:network` must not depend on `data:download`, so use a Hilt `Set<Interceptor>` multibinding: `NetworkModule` consumes the set; `data:download` contributes the amz interceptor `@IntoSet`.

- [ ] **Step 1: Failing test** — assert the provided shared `OkHttpClient`'s `interceptors` list contains an `AmzCaptchaInterceptor` (Hilt test or a direct `provideOkHttpClient(setOf(amzInterceptor))` unit call). Expected: FAIL (provider takes no set yet).

- [ ] **Step 2: Modify `NetworkModule.provideOkHttpClient`** to accept the multibound set and add each interceptor. Also declare the multibinding so the set resolves to empty before any contributor exists:

```kotlin
// In NetworkModule (object → must add an abstract companion module for @Multibinds,
// OR convert: simplest is a separate @Module interface):
@Module @InstallIn(SingletonComponent::class)
interface NetworkInterceptorsModule {
    @Multibinds fun appInterceptors(): Set<@JvmSuppressWildcards okhttp3.Interceptor>
}

// provideOkHttpClient gains a parameter:
@Provides @Singleton
fun provideOkHttpClient(
    appInterceptors: Set<@JvmSuppressWildcards okhttp3.Interceptor>,
): OkHttpClient {
    // … existing builder …
    return OkHttpClient.Builder()
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .addInterceptor(loggingInterceptor)
        .apply { appInterceptors.forEach { addInterceptor(it) } }
        .build()
}
```

- [ ] **Step 3: Bind the amz interceptor `@IntoSet`** from `data:download`:

```kotlin
package com.stash.data.download.lossless.amz.di
@Module @InstallIn(SingletonComponent::class)
abstract class AmzInterceptorModule {
    @Binds @IntoSet
    abstract fun bindAmzCaptchaInterceptor(impl: AmzCaptchaInterceptor): okhttp3.Interceptor
}
```

- [ ] **Step 4: Run test + `./gradlew :core:network:compileDebugKotlin :data:download:compileDebugKotlin`.** Expected: PASS / BUILD SUCCESSFUL. Verify (test or instrumented) the interceptor fires for an `amz.squid.wtf` request and passes other hosts through untouched.

- [ ] **Step 5: Commit.** `… -m "feat(amz): register captcha interceptor on shared client via multibinding"`

---

## Phase 3 — API models + client

### Task 3.1: `AmzApiModels`

**Files:**
- Create: `data/download/.../lossless/amz/AmzApiModels.kt`
- Test: `data/download/.../lossless/amz/AmzApiModelsTest.kt`

- [ ] DTOs (lenient `Json`, `ignoreUnknownKeys`): `AmzSearchResponse(trackList: List<AmzSearchItem>)`, `AmzSearchItem(asin, title, primaryArtistName, artistName, albumArtistName, album: AmzAlbum?)`, `AmzAlbum(title, image)`, `AmzTrack(metadata: AmzTrackMeta)`, `AmzTrackMeta(asin, title, artist, album, isrc, is_explicit, cover, cover_cdn, /* duration if present per Task 0.2 */)`.
- [ ] Test: parse the real captured search + track JSON (copy verbatim from the HAR). Assert ASIN/ISRC fields parse.
- [ ] Commit.

### Task 3.2: `AmzApiClient` — search / track / streamUrl

**Files:**
- Create: `data/download/.../lossless/amz/AmzApiClient.kt`
- Test: `data/download/.../lossless/amz/AmzApiClientTest.kt`

- [ ] Uses the interceptor-bearing shared client (token attached automatically). Methods:
  - `suspend fun search(query: String, limit: Int = 25): List<AmzSearchItem>?` → `POST /api/search {"query","country":"US","content_type":"TRACK","limit"}`.
  - `suspend fun track(asin: String): AmzTrackMeta?` → `POST /api/track {"asin","tier":"best","country":"US"}`.
  - `fun streamUrl(asin: String): String` → `"$BASE/stream?asin=$asin&country=US&tier=best"`.
  - Throw an `AmzRateLimitedException` on 429 (mirror `AntraRateLimitedException` pattern) so the source reports a backoff, not a failure.
- [ ] Test against MockWebServer: search returns parsed list; 429 throws; `streamUrl` shape.
- [ ] Commit.

---

## Phase 4 — Matching

### Task 4.1: `AmzMatcher` — pick the ASIN, confirm via ISRC

**Files:**
- Create: `data/download/.../lossless/amz/AmzMatcher.kt`
- Test: `data/download/.../lossless/amz/AmzMatcherTest.kt`

- [ ] Pure function: given `TrackQuery` + `List<AmzSearchItem>`, score each on artist+title similarity. Port the token-set / normalization helpers from `com.stash.data.download.matching.MatchScorer` (a dedicated, testable unit) — do NOT reach into `QobuzSource.Companion`'s `internal` `normalize`/`jaccard`/`artistSimilarity` (not a shared API) and do NOT reinvent. Return the best candidate + a base confidence. (ISRC confirmation happens in `AmzSource` after `/api/track`, since search has no ISRC.)
- [ ] Requirement 2: the matcher is fed a `"<artist> <title>"` text query — never the bare ISRC. Add a test asserting an ISRC-bearing query still produces a text search term, not the ISRC.
- [ ] Tests: exact match scores high; wrong-artist scores low/None; live-vs-studio handled if duration available (Task 0.2).
- [ ] Commit.

---

## Phase 5 — Download source

### Task 5.1: `AmzSource : LosslessSource`

**Files:**
- Create: `data/download/.../lossless/amz/AmzSource.kt`
- Test: `data/download/.../lossless/amz/AmzSourceTest.kt`

- [ ] `id = "amz"`, `displayName = "Amazon Music (amz.squid.wtf)"`.
- [ ] `resolve(query)`:
  1. `isEnabled()` (rate-limiter not circuit-broken) else null; `rateLimiter.acquire("amz")` else null.
  2. `client.search("<artist> <title>")` (Requirement 2 — never ISRC).
  3. `AmzMatcher.best(query, results)` → candidate or null.
  4. `client.track(asin)` → confirm: when `query.isrc != null` require `meta.isrc == query.isrc` for confidence ≥ 0.95; else use matcher confidence; reject on duration mismatch if duration present (Task 0.2).
  5. Return `SourceResult(sourceId = "amz", downloadUrl = client.streamUrl(asin), downloadHeaders = emptyMap(), format = AudioFormat(codec = "flac", bitrateKbps = 0, sampleRateHz = 0, bitsPerSample = 0), confidence, coverArtUrl = meta.cover_cdn ?: meta.cover, sourceTrackId = asin)`. (Requirement 5: no assumed bits; token rides the interceptor so `downloadHeaders` stays empty — Requirement 3.)
  6. 429 → `rateLimiter.reportRateLimited`; success → `reportSuccess`; failure → `reportFailure`; **re-throw `CancellationException`** before `catch (Exception)` (Requirement 6).
- [ ] `rateLimitState()` delegates to the limiter.
- [ ] Tests (MockK client/matcher/limiter): disabled→null; no-match→null; ISRC-confirm→high confidence; ISRC-mismatch handling; 429→reportRateLimited+null; cancellation re-throw; `SourceResult` shape incl. empty headers.
- [ ] Commit.

### Task 5.2: AggregatorRateLimiter config for "amz"

**Files:**
- Modify: `data/download/.../lossless/AggregatorRateLimiter.kt` (init block)
- Test: extend `AggregatorRateLimiterTest`

- [ ] Add a conservative `configs["amz"]` (e.g. `tokensPerSecond = 1.0/2.0`, `burstCapacity = 2.0`, `backoff429Ms = 10_000`, `circuitBreakAfter = 5`, `circuitBreakDurationMs = 5*60_000`). Captcha challenge/verify are not catalog calls and are not gated here.
- [ ] Test: amz config present + 429 backoff behaviour.
- [ ] Commit.

---

## Phase 6 — DI + download-chain wiring

### Task 6.1: `AmzModule` + DEFAULT_PRIORITY + registry placement

**Files:**
- Create: `data/download/.../lossless/amz/di/AmzModule.kt`
- Modify: `data/download/.../lossless/LosslessSourcePreferences.kt` (`DEFAULT_PRIORITY`)
- Modify (if multibinding seam chosen in 2.2): the DI module binding `AmzCaptchaInterceptor @IntoSet Interceptor`
- Test: `LosslessSourceRegistryTest` (amz placement)

- [ ] `@Binds @IntoSet AmzSource as LosslessSource`. Provide `AmzCaptchaClient`/`AmzApiClient`/`AmzCaptchaInterceptor` (constructor-injected `@Singleton` — minimal `@Provides` needed; mirror `QobuzApiClient` if a derived client is used).
- [ ] Append `"amz"` to `DEFAULT_PRIORITY` (after `squid_qobuz`, `kennyy_qobuz`) — last lossless, before YouTube.
- [ ] Test: registry orders amz last among lossless; amz appears in `Set<LosslessSource>`.
- [ ] Commit.

---

## Phase 7 — Streaming

### Task 7.1: `AmzStreamResolver`

**Files:**
- Create: `core/media/.../streaming/AmzStreamResolver.kt`
- Test: `core/media/.../streaming/AmzStreamResolverTest.kt`

- [ ] `resolve(track): StreamUrl?` — mirror `QobuzStreamResolver` but: search→match→track→ return `StreamUrl(url = client.streamUrl(asin), expiresAtMs = <far future or short>, codec = "flac", origin = "amz", coverArtUrl = …)`. NOTE: the bytes URL, NOT a downloaded file. (Reuse `AmzApiClient`/`AmzMatcher` from `data:download` — confirm `core:media` already depends on `data:download` as `QobuzStreamResolver` does.)
- [ ] Test: returns amz-origin StreamUrl on match; null when no match / not confident.
- [ ] Commit.

### Task 7.2: Register `AmzStreamResolver` in `StreamSourceRegistry`

**Files:**
- Modify: `core/media/.../streaming/StreamSourceRegistry.kt`
- Test: `StreamSourceRegistryTest`

- [ ] Add `amz` to the **normal** branch, after `squid`, before `youtube` (NOT in the forceYt branch). Constructor gets `AmzStreamResolver`.
- [ ] Test: amz resolved after kennyy/squid miss, before youtube; absent under forceYt.
- [ ] Commit.

### Task 7.3: amz routing branch in `StashMediaSourceFactory` (the new authed data source)

**Files:**
- Modify: `core/media/.../service/StashPlaybackService.kt` (the `streamingTrackId` predicate / add an `isAmzOrigin` predicate)
- Modify: `core/media/.../streaming/StashMediaSourceFactory.kt`
- Create: `core/media/.../streaming/AmzMediaSourceFactory.kt` (or inline factory)
- Test: a unit test asserting amz-origin items route to the OkHttp-backed factory, others unchanged

- [ ] Add a per-item predicate that detects `streamOrigin == "amz"` (read the same stream-origin MediaItem extra the service already sets, e.g. `EXTRA_STREAM_ORIGIN`).
- [ ] In `StashMediaSourceFactory.createMediaSource`: if amz-origin → use `amzStreamingFactory`; else existing youtube/local branches.
- [ ] `amzStreamingFactory` = `DefaultMediaSourceFactory(context).setDataSourceFactory(OkHttpDataSource.Factory(sharedOkHttpClient))` (the shared client carries `AmzCaptchaInterceptor`; token + re-mint per range request). No `RefreshingDataSource`, no `CacheDataSource`.
- [ ] Test: MediaItem with amz origin → media source built via the OkHttp factory; youtube origin still → refresh chain; local → default.
- [ ] Commit.

---

## Phase 8 — On-device end-to-end verification

- [ ] **Build + install:** `./gradlew :app:assembleDebug` then install (ASK the user before installing — see project memory on device data). Confirm the lossless/streaming unit suites are green: `./gradlew :data:download:testDebugUnitTest :core:media:testDebugUnitTest` (filter to the touched classes if the full run is flaky).
- [ ] **Download path:** with kennyy/squid forced to miss (or a track only on Amazon), trigger a download; confirm in logcat amz solves the captcha once, search→track→stream succeed, and a FLAC lands in the library; quality probe sets real bits/rate.
- [ ] **Streaming path:** play an amz-served track; confirm progressive start (no full pre-download), and that a long track survives a mid-stream token expiry (interceptor re-mints). Seek behaviour matches the Range finding from Task 0.2.
- [ ] **Failover:** disconnect amz (bad token / offline) → confirm clean failover to YouTube, no user-facing error.
- [ ] Append a `## Verification Results` section to the spec with findings (Range, stale-token code, real FLAC bit-depth observed).

---

## Done criteria

- amz resolves real Amazon FLAC for tracks kennyy/squid miss (download + progressive stream), gated after the Qobuz proxies and before YouTube.
- Captcha solved headless; token re-minted transparently on staleness (download + mid-stream).
- All new unit tests green; `:app:assembleDebug` clean; on-device E2E confirmed.
