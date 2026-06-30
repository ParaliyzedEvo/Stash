# qbdlx Qobuz Lossless Source — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Spec: `docs/superpowers/specs/2026-06-29-qbdlx-qobuz-source-design.md`.

**Goal:** Add a 5th lossless source that calls the Qobuz API **directly** (MD5 request signing + `X-App-Id`/`X-User-Auth-Token` + a rotating token pool from qbdlx.launchpd.cloud), for FLAC download and progressive streaming.

**Architecture:** Direct-to-`www.qobuz.com` source mirroring the existing `QobuzSource`/`QobuzStreamResolver` pattern, but with its own signing + token rotation (the existing `QobuzApiClient` talks to a *proxy* and does no signing). Reuses `LosslessSource`, `AggregatorRateLimiter`, `LosslessSourceHealthGate`, the download `LosslessSourceRegistry` (Hilt `Set` multibinding) and stream `StreamSourceRegistry` (hardcoded `buildList`). The Qobuz matcher is extracted from `QobuzSource` into a shared helper so both sources reuse one scorer.

**Tech Stack:** Kotlin, Hilt/Dagger, OkHttp, kotlinx.serialization, DataStore, Media3 (stream side), JUnit + MockWebServer + Truth/MockK.

**Module map:**
- `data:download` — package `com.stash.data.download.lossless.qbdlx`: `QbdlxSigner`, `QbdlxQobuzModels`, `QbdlxApiClient`, `QbdlxCredentialStore`, `QbdlxQobuzSource`, `di/QbdlxModule`. Plus extracted `lossless.qobuz.QobuzCandidateMatcher`. Plus `BuildConfig` fields + `LosslessSourcePreferences` additions + `AggregatorRateLimiter` config.
- `core:media` — package `streaming`: `QbdlxStreamResolver` + one line in `StreamSourceRegistry`.
- `feature:settings` — toggle + paste-token row + all-dead notice.

**Verified facts (from spec §2) the code depends on:**
- Sign: `request_sig = md5(objectMethod + sortedParamsConcat + request_ts + app_secret)`. For getFileUrl: `md5("trackgetFileUrl" + "format_id"+fmt + "intentstream" + "track_id"+id + ts + secret)`. For lyricsUrl: `md5("tracklyricsUrl" + "track_id"+id + ts + secret)`.
- `app_id = 798273057`, `app_secret = abb21364945c0583309667d13ca3d93a`.
- Search (no sig): `GET https://www.qobuz.com/api.json/0.2/catalog/search?query=&type=tracks&limit=&app_id=` + header `X-User-Auth-Token`. Returns `tracks.items[]`.
- Resolve (signed): `GET .../track/getFileUrl?track_id=&format_id=&app_id=&request_ts=&request_sig=&intent=stream` + headers `X-App-Id` + `X-User-Auth-Token`. Returns `{url, format_id, bit_depth, sampling_rate, restrictions[], sample}`.
- Dead token → `restrictions:[{code:"UserUnauthenticated"}]`, `sample:true`, `format_id:5`. Format-restricted → `restrictions:[{code:"FormatRestrictedByFormatAvailability"}]` + downgraded `format_id` (still accept if ≥6/FLAC).
- One real verified token (build input): `jM-6F2QcDpfG7fj1RRPq7bAa7tBVCykt__5HD1K25v2yFq0c9_-SmXEhG-74moNpN5YQTmFFyyMq2F70h1G17A` (country FR).

**Pinned signature test vectors (HAR-captured, app_id 798273057, secret above):**
| object/method | params (in sig order) | request_ts | expected request_sig |
|---|---|---|---|
| track/getFileUrl | format_id27intentstreamtrack_id2841459 | 1782781652 | 013c10042c5e15ca5f1d85610bdd62ad |
| track/getFileUrl | format_id6intentstreamtrack_id3144087 | 1782781565 | ff083dedd464374d86affbb22daeae01 |
| track/lyricsUrl | track_id2841459 | 1782781552 | e8149d392b9654ade72856fa3150d5a9 |
| file/url | format_id27intentstreamtrack_id2841459 | 1782781652 | 8a5581ebe575c768f41f8bcf7de031ce |

**Build/test environment notes (this Windows box):**
- Use the Gradle daemon; on a back-to-back `BindException` run `./gradlew --stop` once and retry (see infra memory). Always filter tests with `--tests`.
- Run module-scoped unit tests, e.g. `./gradlew :data:download:testDebugUnitTest --tests "com.stash.data.download.lossless.qbdlx.*"`.
- Pre-existing reds to ignore (not ours): `InnerTubeSearchExecutorTest`, `YtLibraryCanonicalizerTest`, and `MusicRepositoryDownloadsMixTest.linkTrackToDownloadsMix seeds...`.

---

## Phase 0 — Build config + credential plumbing

### Task 0.1: BuildConfig fields for qbdlx credentials + token pool

**Files:**
- Modify: `data/download/build.gradle.kts` (mirror the existing `ARCOD_STREAM_BASE` block ~lines 10-36)
- Modify: `local.properties` (gitignored — the build input)

- [ ] **Step 1:** In `data/download/build.gradle.kts`, near the ARCOD block, read three values from `local.properties`/env with empty defaults:

```kotlin
// ── qbdlx (direct-Qobuz) credentials + token pool ──────────────────────────
// Bundled at build time from local.properties / env. APP_ID + APP_SECRET are
// public (shown on qbdlx's login page). TOKEN_POOL is a comma-separated list of
// "user_auth_token:ISO2COUNTRY" pairs. Empty is valid — an unconfigured build
// simply has no bundled tokens and relies on a user-pasted token.
val qbdlxProps = java.util.Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun qbdlxProp(key: String, env: String) =
    qbdlxProps.getProperty(key) ?: System.getenv(env).orEmpty()
val qbdlxAppId = qbdlxProp("qbdlx.appId", "QBDLX_APP_ID")
val qbdlxAppSecret = qbdlxProp("qbdlx.appSecret", "QBDLX_APP_SECRET")
val qbdlxTokenPool = qbdlxProp("qbdlx.tokenPool", "QBDLX_TOKEN_POOL")
```

- [ ] **Step 2:** In the same `defaultConfig` block that has `buildConfigField("String", "ARCOD_STREAM_BASE", ...)`, add:

```kotlin
buildConfigField("String", "QBDLX_APP_ID", "\"$qbdlxAppId\"")
buildConfigField("String", "QBDLX_APP_SECRET", "\"$qbdlxAppSecret\"")
buildConfigField("String", "QBDLX_TOKEN_POOL", "\"$qbdlxTokenPool\"")
```

- [ ] **Step 3:** Add to `local.properties` (the build input; the FR token is the one verified value, user appends the rest later):

```
qbdlx.appId=798273057
qbdlx.appSecret=abb21364945c0583309667d13ca3d93a
qbdlx.tokenPool=jM-6F2QcDpfG7fj1RRPq7bAa7tBVCykt__5HD1K25v2yFq0c9_-SmXEhG-74moNpN5YQTmFFyyMq2F70h1G17A:FR
```

- [ ] **Step 4:** Build the module to generate BuildConfig:

Run: `./gradlew :data:download:compileDebugKotlin`
Expected: BUILD SUCCESSFUL; `BuildConfig.QBDLX_APP_ID` etc. now exist.

- [ ] **Step 5:** Commit.

```bash
git add data/download/build.gradle.kts
git commit -m "build(qbdlx): BuildConfig fields for app_id/secret/token pool"
```

---

## Phase 1 — Signer (pure, TDD against pinned vectors)

### Task 1.1: `QbdlxSigner` — MD5 request signing with injectable clock

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxSigner.kt`
- Test: `data/download/src/test/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxSignerTest.kt`

- [ ] **Step 1: Write the failing test** (reproduces 3 of the 4 pinned vectors — the two getFileUrl and the lyricsUrl):

```kotlin
package com.stash.data.download.lossless.qbdlx

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class QbdlxSignerTest {
    private val secret = "abb21364945c0583309667d13ca3d93a"

    @Test fun `getFileUrl signature matches HAR vector (fmt27)`() {
        val signer = QbdlxSigner(secret) { 1782781652L }
        val sig = signer.signGetFileUrl(trackId = 2841459, formatId = 27)
        assertThat(sig).isEqualTo("013c10042c5e15ca5f1d85610bdd62ad")
    }

    @Test fun `getFileUrl signature matches HAR vector (fmt6)`() {
        val signer = QbdlxSigner(secret) { 1782781565L }
        val sig = signer.signGetFileUrl(trackId = 3144087, formatId = 6)
        assertThat(sig).isEqualTo("ff083dedd464374d86affbb22daeae01")
    }

    @Test fun `lyricsUrl signature matches HAR vector`() {
        val signer = QbdlxSigner(secret) { 1782781552L }
        val sig = signer.signLyricsUrl(trackId = 2841459)
        assertThat(sig).isEqualTo("e8149d392b9654ade72856fa3150d5a9")
    }

    @Test fun `ts comes from the injected clock`() {
        val signer = QbdlxSigner(secret) { 1782781652L }
        assertThat(signer.requestTs()).isEqualTo(1782781652L)
    }
}
```

- [ ] **Step 2: Run it — verify it fails** (`QbdlxSigner` undefined).

Run: `./gradlew :data:download:testDebugUnitTest --tests "com.stash.data.download.lossless.qbdlx.QbdlxSignerTest"`
Expected: FAIL (unresolved reference QbdlxSigner).

- [ ] **Step 3: Implement minimal code:**

```kotlin
package com.stash.data.download.lossless.qbdlx

import java.security.MessageDigest

/**
 * Signs Qobuz API requests. Qobuz validates `request_sig = md5(object+method
 * + params-in-fixed-order + request_ts + app_secret)`. The param order and
 * literal concatenation per endpoint were reverse-engineered from qbdlx's JS
 * and locked by [QbdlxSignerTest] against real HAR vectors. [clock] returns
 * epoch SECONDS (injectable so the vectors are reproducible).
 */
class QbdlxSigner(
    private val appSecret: String,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000L },
) {
    fun requestTs(): Long = clock()

    /** ts is returned alongside the sig so the caller sends the SAME ts it signed. */
    fun signGetFileUrl(trackId: Long, formatId: Int): String {
        val ts = clock()
        return md5("trackgetFileUrl" + "format_id$formatId" + "intentstream" + "track_id$trackId" + ts + appSecret)
    }

    fun signLyricsUrl(trackId: Long): String {
        val ts = clock()
        return md5("tracklyricsUrl" + "track_id$trackId" + ts + appSecret)
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
```

> NOTE: the sig and ts must come from the SAME `clock()` read. Refactor `signGetFileUrl` to return a `Signed(ts, sig)` pair so the caller can't drift them — do this in Task 2.2 when wiring the client. For now the test pins the formula.

- [ ] **Step 4: Run — verify pass.** Expected: 4 tests PASS.

- [ ] **Step 5: Commit.**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxSigner.kt data/download/src/test/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxSignerTest.kt
git commit -m "feat(qbdlx): MD5 request signer (TDD, pinned HAR vectors)"
```

---

## Phase 2 — Wire models + direct-Qobuz API client

### Task 2.1: `QbdlxQobuzModels` — direct Qobuz response models

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxQobuzModels.kt`

These are the RAW Qobuz shapes (NOT the squid `{success,data}` envelope). Model only consumed fields; `ignoreUnknownKeys = true`.

- [ ] **Step 1:** Create the file:

```kotlin
package com.stash.data.download.lossless.qbdlx

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── catalog/search ─────────────────────────────────────────────────────
@Serializable
data class QbdlxSearchResponse(val tracks: QbdlxTrackList = QbdlxTrackList())

@Serializable
data class QbdlxTrackList(val items: List<QbdlxTrack> = emptyList())

@Serializable
data class QbdlxTrack(
    val id: Long = 0,
    val title: String = "",
    val isrc: String? = null,
    val duration: Int = 0,                        // seconds
    val streamable: Boolean = true,
    val performer: QbdlxPerformer? = null,
    @SerialName("maximum_bit_depth") val maximumBitDepth: Int = 0,
    @SerialName("maximum_sampling_rate") val maximumSamplingRate: Float = 0f,  // kHz
    val album: QbdlxAlbum? = null,
)

@Serializable data class QbdlxPerformer(val name: String = "")
@Serializable data class QbdlxAlbum(val image: QbdlxImage? = null)
@Serializable data class QbdlxImage(val large: String? = null, val small: String? = null, val thumbnail: String? = null)

// ── track/getFileUrl ───────────────────────────────────────────────────
@Serializable
data class QbdlxFileUrl(
    val url: String? = null,
    @SerialName("format_id") val formatId: Int = 0,
    @SerialName("bit_depth") val bitDepth: Int = 0,
    @SerialName("sampling_rate") val samplingRate: Float = 0f, // kHz
    val sample: Boolean = false,
    val restrictions: List<QbdlxRestriction> = emptyList(),
)

@Serializable data class QbdlxRestriction(val code: String = "")
```

- [ ] **Step 2:** Compile. Run: `./gradlew :data:download:compileDebugKotlin` → BUILD SUCCESSFUL.
- [ ] **Step 3:** Commit. `git commit -m "feat(qbdlx): direct-Qobuz wire models"`

### Task 2.2: `QbdlxApiClient` — search + getFileUrl + response classification

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxApiClient.kt`
- Test: `data/download/src/test/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxApiClientTest.kt`

The client classifies a getFileUrl response into a sealed result. Token-death is detected from the JSON body (spec §2), NOT a URL regex.

- [ ] **Step 1: Write the failing test** (MockWebServer; classification is the core logic):

```kotlin
package com.stash.data.download.lossless.qbdlx

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class QbdlxApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: QbdlxApiClient

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        client = QbdlxApiClient(
            sharedClient = OkHttpClient(),
            signer = QbdlxSigner("secret") { 1000L },
            appId = "798273057",
        ).also { it.baseUrl = server.url("/").toString().trimEnd('/') }
    }
    @After fun tearDown() { server.shutdown() }

    @Test fun `search parses track items`() = runTest {
        server.enqueue(MockResponse().setBody("""{"tracks":{"items":[{"id":42,"title":"Murderers","isrc":"USWB10003085","duration":160,"performer":{"name":"John Frusciante"},"maximum_bit_depth":16,"maximum_sampling_rate":44.1}]}}"""))
        val items = client.search("John Frusciante Murderers", token = "tok")
        assertThat(items).hasSize(1)
        assertThat(items[0].id).isEqualTo(42)
        assertThat(server.takeRequest().getHeader("X-User-Auth-Token")).isEqualTo("tok")
    }

    @Test fun `getFileUrl Ok when url present and not restricted`() = runTest {
        server.enqueue(MockResponse().setBody("""{"url":"https://cdn/file?fmt=6","format_id":6,"bit_depth":16,"sampling_rate":44.1,"sample":false,"restrictions":[]}"""))
        val r = client.getFileUrl(trackId = 42, formatId = 27, token = "tok")
        assertThat(r).isInstanceOf(QbdlxResolveResult.Ok::class.java)
        val ok = r as QbdlxResolveResult.Ok
        assertThat(ok.url).contains("cdn/file")
        assertThat(ok.bitDepth).isEqualTo(16)
        val req = server.takeRequest()
        assertThat(req.getHeader("X-App-Id")).isEqualTo("798273057")
        assertThat(req.path).contains("request_sig=")
    }

    @Test fun `getFileUrl TokenDead on UserUnauthenticated preview`() = runTest {
        server.enqueue(MockResponse().setBody("""{"url":"https://cdn/file?fmt=5&range=20-30","format_id":5,"sample":true,"restrictions":[{"code":"UserUnauthenticated"}]}"""))
        val r = client.getFileUrl(trackId = 42, formatId = 27, token = "tok")
        assertThat(r).isInstanceOf(QbdlxResolveResult.TokenDead::class.java)
    }

    @Test fun `getFileUrl RegionLocked when restricted with no usable url`() = runTest {
        server.enqueue(MockResponse().setBody("""{"format_id":6,"restrictions":[{"code":"TrackRestrictedByRights"}]}"""))
        val r = client.getFileUrl(trackId = 42, formatId = 27, token = "tok")
        assertThat(r).isInstanceOf(QbdlxResolveResult.RegionLocked::class.java)
    }

    @Test fun `getFileUrl accepts format-downgrade to CD FLAC`() = runTest {
        server.enqueue(MockResponse().setBody("""{"url":"https://cdn/file?fmt=6","format_id":6,"restrictions":[{"code":"FormatRestrictedByFormatAvailability"}]}"""))
        val r = client.getFileUrl(trackId = 42, formatId = 27, token = "tok")
        assertThat(r).isInstanceOf(QbdlxResolveResult.Ok::class.java)  // fmt6 is still lossless
    }

    @Test fun `search 401 throws TokenDead-signalling exception`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"status":"error","code":401}"""))
        try { client.search("x", token = "tok"); assertThat(false).isTrue() }
        catch (e: QbdlxAuthException) { assertThat(e.status).isEqualTo(401) }
    }
}
```

- [ ] **Step 2: Run — verify fail** (unresolved `QbdlxApiClient`, `QbdlxResolveResult`, `QbdlxAuthException`).

- [ ] **Step 3: Implement:**

```kotlin
package com.stash.data.download.lossless.qbdlx

import com.stash.data.download.lossless.AudioFormat
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Outcome of a getFileUrl call, classified from the JSON body (spec §2). */
sealed interface QbdlxResolveResult {
    data class Ok(val url: String, val codec: String, val bitDepth: Int, val sampleRateHz: Int) : QbdlxResolveResult
    /** Token is dead/unauthenticated (preview/sample/fmt5). Caller marks it dead + rotates. */
    object TokenDead : QbdlxResolveResult
    /** Track unavailable for this token's region/rights. Caller tries other tokens. */
    object RegionLocked : QbdlxResolveResult
}

/** Thrown on an HTTP 401 (auth) — distinct so the source can markDead + rotate. */
class QbdlxAuthException(val status: Int, message: String? = null) : RuntimeException(message)
/** Thrown on any other non-2xx / network failure — transient, do NOT mark dead. */
class QbdlxApiException(val status: Int, message: String? = null) : RuntimeException(message)

@Singleton
class QbdlxApiClient @Inject constructor(
    sharedClient: OkHttpClient,
    private val signer: QbdlxSigner,
    private val appId: String,
) {
    internal var httpClient: OkHttpClient = sharedClient  // direct www.qobuz.com; no interceptor
    internal var baseUrl: String = ORIGIN
    internal var json: Json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /** Search the Qobuz catalog. Throws [QbdlxAuthException] on 401, [QbdlxApiException] otherwise. */
    suspend fun search(query: String, token: String, limit: Int = 10): List<QbdlxTrack> =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/api.json/0.2/catalog/search".toHttpUrl().newBuilder()
                .addQueryParameter("query", query)
                .addQueryParameter("type", "tracks")
                .addQueryParameter("limit", limit.toString())
                .addQueryParameter("app_id", appId)
                .build()
            val body = get(url.toString(), token)
            runCatching { json.decodeFromString<QbdlxSearchResponse>(body).tracks.items }.getOrDefault(emptyList())
        }

    /** Resolve a track id to a signed FLAC URL, classified. */
    suspend fun getFileUrl(trackId: Long, formatId: Int, token: String): QbdlxResolveResult =
        withContext(Dispatchers.IO) {
            // Sign + ts must be one atomic read. signer.requestTs() and signGetFileUrl
            // both call clock(); read ts once, then sign with that exact ts.
            val ts = signer.requestTs()
            val sig = signer.signGetFileUrl(trackId, formatId) // NOTE: must sign with `ts`; see refactor below
            val url = "$baseUrl/api.json/0.2/track/getFileUrl".toHttpUrl().newBuilder()
                .addQueryParameter("track_id", trackId.toString())
                .addQueryParameter("format_id", formatId.toString())
                .addQueryParameter("app_id", appId)
                .addQueryParameter("request_ts", ts.toString())
                .addQueryParameter("request_sig", sig)
                .addQueryParameter("intent", "stream")
                .build()
            val raw = get(url.toString(), token)
            classify(json.decodeFromString<QbdlxFileUrl>(raw))
        }

    private fun classify(f: QbdlxFileUrl): QbdlxResolveResult {
        val dead = f.sample || f.formatId == 5 ||
            f.restrictions.any { it.code.equals("UserUnauthenticated", ignoreCase = true) }
        if (dead) return QbdlxResolveResult.TokenDead
        if (f.url.isNullOrBlank() || f.formatId < 6) return QbdlxResolveResult.RegionLocked
        val codec = if (f.formatId == 5) "mp3" else "flac"
        return QbdlxResolveResult.Ok(f.url, codec, f.bitDepth, (f.samplingRate * 1000f).toInt())
    }

    private fun get(url: String, token: String): String {
        val req = Request.Builder().url(url)
            .header("X-App-Id", appId)
            .header("X-User-Auth-Token", token)
            .header("Accept", "application/json")
            .header("User-Agent", UA)
            .get().build()
        httpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code == 401) throw QbdlxAuthException(401, body.take(120))
            if (!resp.isSuccessful) throw QbdlxApiException(resp.code, body.take(120))
            return body
        }
    }

    private companion object {
        const val ORIGIN = "https://www.qobuz.com"
        const val UA = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36"
    }
}
```

- [ ] **Step 3a: Fix the ts/sig drift** (the inline NOTE): refactor `QbdlxSigner.signGetFileUrl`/`signLyricsUrl` to take an explicit `ts: Long` param and return only the sig; the client reads `val ts = signer.requestTs()` once and passes it in. Update `QbdlxSignerTest` to call `signer.signGetFileUrl(ts = 1782781652L, trackId = ..., formatId = ...)`. Re-run Phase-1 tests — still green against the vectors.

- [ ] **Step 4: Run — verify pass.** Run the QbdlxApiClientTest filter. Expected: all PASS.

- [ ] **Step 5: Commit.** `git commit -m "feat(qbdlx): direct-Qobuz API client w/ JSON-body classification"`

---

## Phase 3 — Credential store (token pool, rotation, persisted death, region)

### Task 3.1: `QbdlxCredentialStore`

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxCredentialStore.kt`
- Modify: `data/download/src/main/kotlin/com/stash/data/download/lossless/LosslessSourcePreferences.kt` (add a pasted-token key + a dead-token-set key, mirroring `captchaCookieKey` storage)
- Test: `data/download/src/test/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxCredentialStoreTest.kt`

Behavior (spec §3.2):
- `activeToken()` → pasted token if set, else round-robin over **live** (non-dead) pool tokens; null if all dead.
- `tokensForRegion(country?)` → ordered live tokens, country-match first, capped at `MAX_REGION_TRIES = 3`.
- `markDead(token)` → add to a persisted dead set (DataStore string set/CSV); on `recordAlive(token)` clear it.
- `allDead()` → pasted (if any) dead AND every pool token dead.
- Pool parsed from `BuildConfig.QBDLX_TOKEN_POOL` ("token:country,token:country").

- [ ] **Step 1: Write failing tests** (use a fake prefs interface so DataStore isn't needed — see existing `AntraCredentialStoreTest` / `ArcodCredentialStoreTest` for the pattern). Cover: pasted-priority; round-robin advances; markDead skips dead + persists; region country-first + bounded; allDead true only when everything dead.

```kotlin
// Sketch — implement against the chosen prefs seam.
@Test fun `pasted token takes priority over pool`() { ... assertThat(store.activeToken()).isEqualTo("pasted") }
@Test fun `round-robin advances across live pool tokens`() { ... }
@Test fun `markDead skips token and persists across reload`() { ... }
@Test fun `tokensForRegion puts country match first and caps at 3`() { ... }
@Test fun `allDead true only when pasted and all pool dead`() { ... }
```

- [ ] **Step 2:** Run — verify fail.
- [ ] **Step 3:** Add to `LosslessSourcePreferences`: `qbdlxPastedTokenKey = stringPreferencesKey("qbdlx_pasted_token")`, `qbdlxDeadTokensKey = stringPreferencesKey("qbdlx_dead_tokens")` (CSV); flows + `…Now()` reads + `setQbdlxPastedToken(String?)` + `addQbdlxDeadToken(String)` + `clearQbdlxDeadToken(String)`, mirroring the `captchaCookie` methods (lines ~137-162).
- [ ] **Step 4:** Implement `QbdlxCredentialStore` (parse pool from `BuildConfig.QBDLX_TOKEN_POOL`; round-robin index in-memory `AtomicInteger`; dead set + pasted token from prefs). Run — verify pass.
- [ ] **Step 5:** Commit. `git commit -m "feat(qbdlx): credential store — pool rotation, persisted death, region"`

---

## Phase 4 — Extract the shared Qobuz matcher (refactor, no behavior change)

### Task 4.1: `QobuzCandidateMatcher`

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/lossless/qobuz/QobuzCandidateMatcher.kt`
- Modify: `data/download/src/main/kotlin/com/stash/data/download/lossless/qobuz/QobuzSource.kt` (delegate to it; keep behavior identical)
- Test: `data/download/src/test/kotlin/com/stash/data/download/lossless/qobuz/QobuzCandidateMatcherTest.kt`

The reviewer flagged that `normalize/jaccard/artistSimilarity` are `internal` and `confidence/MIN_CONFIDENCE` `private` — not reusable. Extract a **public** scorer operating on neutral fields (not the squid `QobuzTrack`), so both `QobuzSource` and `QbdlxQobuzSource` reuse it.

- [ ] **Step 1: Write the failing test** — port the existing confidence cases as a characterization test (ISRC→0.95; title×artist×duration; streamable=false→0). Use neutral params:

```kotlin
val score = QobuzCandidateMatcher.confidence(
    query = TrackQuery(artist = "John Frusciante", title = "Murderers", isrc = "USWB10003085", durationMs = 160_000),
    candTitle = "Murderers", candArtist = "John Frusciante",
    candIsrc = "USWB10003085", candDurationSec = 160, candStreamable = true,
)
assertThat(score).isEqualTo(0.95f)
```

- [ ] **Step 2:** Run — verify fail.
- [ ] **Step 3:** Move `normalize`/`jaccard`/`artistSimilarity`/`confidence`/`MIN_CONFIDENCE` into `object QobuzCandidateMatcher` as **public**, with `confidence(query, candTitle, candArtist, candIsrc, candDurationSec, candStreamable)`. In `QobuzSource`, replace the private `confidence(query, candidate: QobuzTrack)` body with a call to `QobuzCandidateMatcher.confidence(query, candidate.title, candidate.performer?.name.orEmpty(), candidate.isrc, candidate.duration, candidate.streamable)` and reference `QobuzCandidateMatcher.MIN_CONFIDENCE`. Keep the `internal` test hooks delegating if other tests use them.
- [ ] **Step 4:** Run the FULL qobuz test suite to prove no regression:

Run: `./gradlew :data:download:testDebugUnitTest --tests "com.stash.data.download.lossless.qobuz.*"`
Expected: all PASS (existing `QobuzSourceTest` unchanged + new matcher test).

- [ ] **Step 5:** Commit. `git commit -m "refactor(qobuz): extract shared QobuzCandidateMatcher (no behavior change)"`

---

## Phase 5 — `QbdlxQobuzSource`

### Task 5.1: The LosslessSource implementation

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxQobuzSource.kt`
- Test: `data/download/src/test/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxQobuzSourceTest.kt`

Mirror `QobuzSource`: `resolve` (rate-limited) + `resolveImmediate` (bypass breaker, for stream) + `isEnabled()`/`isEnabledForStreaming()`. Flow per spec §4. Format read from the getFileUrl response. On `TokenDead` → `markDead` + rotate (bounded); on `RegionLocked` → `tokensForRegion` (bounded); on `QbdlxAuthException` from search → `markDead` + rotate.

- [ ] **Step 1: Write failing tests** (MockK the client/credentialStore/rateLimiter/healthGate): match→SourceResult with response-format; preview→TokenDead→markDead+rotate→success on 2nd token; region-locked→tries country token; whole-pool-dead→`isEnabled()` false & resolve null; `resolveImmediate` ignores circuit-broken.
- [ ] **Step 2:** Run — verify fail.
- [ ] **Step 3:** Implement. Key points:
  - `id = "qbdlx_qobuz"`, `displayName = "Qobuz (via qbdlx)"`.
  - `isEnabled()` = `!rateLimiter.stateOf(id).isCircuitBroken && !credentialStore.allDead()`.
  - `isEnabledForStreaming()` = `!credentialStore.allDead()` (NO breaker).
  - `resolve` → rate-limit acquire then `resolveWith(token = activeToken())`; `resolveImmediate` → bypass acquire, breaker-independent gate.
  - Search via `apiClient.search`; on `QbdlxAuthException` → `markDead(token)` + try next live token (bounded loop, ≤ pool size); match via `QobuzCandidateMatcher`; `getFileUrl` → classify; `Ok` → build `SourceResult(format from Ok.codec/bitDepth/sampleRateHz)`; `TokenDead` → markDead + next; `RegionLocked` → iterate `tokensForRegion(query.country)` (note: `TrackQuery` has no country today — pass `null`, country-matching is a v2 refinement; bounded retry across live tokens still applies); report success/failure to `rateLimiter`.
  - `recordAlive(token)` on any `Ok` so a previously-dead-then-recovered token clears.
- [ ] **Step 4:** Run — verify pass.
- [ ] **Step 5:** Commit. `git commit -m "feat(qbdlx): QbdlxQobuzSource (resolve + streaming bypass + rotation)"`

---

## Phase 6 — Download chain wiring

### Task 6.1: DI binding + default priority + rate-limit config

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/di/QbdlxModule.kt`
- Modify: `data/download/src/main/kotlin/com/stash/data/download/lossless/LosslessSourcePreferences.kt` (`DEFAULT_PRIORITY` += `"qbdlx_qobuz"` last)
- Modify: `data/download/src/main/kotlin/com/stash/data/download/lossless/AggregatorRateLimiter.kt` (`configs["qbdlx_qobuz"]` in init)

- [ ] **Step 1:** `QbdlxModule`: `@Binds @IntoSet bindQbdlxAsLosslessSource(impl: QbdlxQobuzSource): LosslessSource` (mirror `QobuzModule`). Also a `@Provides` for `QbdlxSigner` (secret from `BuildConfig.QBDLX_APP_SECRET`) and for `QbdlxApiClient`'s `appId` (`BuildConfig.QBDLX_APP_ID`) — or construct these inside the module. Provide `QbdlxCredentialStore` (it's `@Inject` constructed; needs prefs + BuildConfig pool — read pool in the store from `BuildConfig.QBDLX_TOKEN_POOL`).
- [ ] **Step 2:** `LosslessSourcePreferences.DEFAULT_PRIORITY`: append `"qbdlx_qobuz"` after `"amz"` (last). Update the KDoc list (it numbers the sources).
- [ ] **Step 3:** `AggregatorRateLimiter` init: add
```kotlin
configs["qbdlx_qobuz"] = Config(
    tokensPerSecond = 1.0 / 3.0,   // slow — direct-to-Qobuz on shared real accounts
    burstCapacity = 2.0,
    backoff429Ms = 60_000L,
    circuitBreakAfter = 5,
    circuitBreakDurationMs = 10 * 60_000L,
    rateLimitTripsBreaker = false, // a Qobuz 429 = "slow down", not "broken"
)
```
- [ ] **Step 4: Verify the full Hilt graph compiles** (DI cycles only surface in the app Java-compile — see lossless memory's DI-cycle lesson):

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (qbdlx now in the `Set<LosslessSource>`).

- [ ] **Step 5:** Commit. `git commit -m "feat(qbdlx): register download source (IntoSet + priority + rate limit)"`

---

## Phase 7 — Stream resolver wiring

### Task 7.1: `QbdlxStreamResolver` + registry

**Files:**
- Create: `core/media/src/main/kotlin/com/stash/core/media/streaming/QbdlxStreamResolver.kt`
- Modify: `core/media/src/main/kotlin/com/stash/core/media/streaming/StreamSourceRegistry.kt`
- Test: `core/media/src/test/kotlin/com/stash/core/media/streaming/QbdlxStreamResolverTest.kt`

Mirror `QobuzStreamResolver` exactly — delegate to `QbdlxQobuzSource.resolveImmediate`, parse `etsp`, wrap as `StreamUrl(origin = "qbdlx")`. No matching duplicated.

- [ ] **Step 1: Write failing test:** delegates to source; returns null when `isEnabledForStreaming()` false; null when no `etsp`; healthGate-degraded → null.
- [ ] **Step 2:** Run — verify fail.
- [ ] **Step 3:** Implement `QbdlxStreamResolver` (copy `QobuzStreamResolver`, swap source type, `ORIGIN = "qbdlx"`, `SOURCE_ID = QbdlxQobuzSource.SOURCE_ID`). Add `resolveImmediate(query, requestedQuality)` to `QbdlxQobuzSource` if not already present (Phase 5 should have it).
- [ ] **Step 4:** Wire into `StreamSourceRegistry`: constructor-inject `qbdlx: QbdlxStreamResolver`; in the normal (`else`) branch, **inside `if (allowYtDlp)`**, add after amz: `if (allowYtDlp) add("qbdlx" to qbdlx::resolve)`. Update the class KDoc's "Current order" list.
- [ ] **Step 5:** Run `core:media` test filter + `./gradlew :app:assembleDebug`. Expected: PASS + BUILD SUCCESSFUL.
- [ ] **Step 6:** Commit. `git commit -m "feat(qbdlx): stream resolver wired (allowYtDlp-gated)"`

---

## Phase 8 — Settings: enable toggle, paste-token, all-dead notice

### Task 8.1: Settings surface

**Files:**
- Modify: `feature/settings/.../SettingsViewModel.kt` + the lossless-sources screen (find the row that renders squid's "Connect"/cookie + the per-source toggles; mirror it).

- [ ] **Step 1:** Add a "Qobuz (via qbdlx)" row: an enable toggle (persist via a new `LosslessSourcePreferences` boolean key `qbdlx_enabled`, default true) + a "Paste token" text field calling `setQbdlxPastedToken`. Gate `QbdlxQobuzSource.isEnabled()` on the new boolean too.
- [ ] **Step 2:** All-dead notice: expose `QbdlxCredentialStore.allDead()` (or a flow) to the screen; when true, show "qbdlx tokens expired — paste a fresh one" (mirror squid's `lastKnownBadCookie` "Expired" badge pattern).
- [ ] **Step 3:** `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
- [ ] **Step 4:** Commit. `git commit -m "feat(qbdlx): Settings toggle + paste-token + expired notice"`

---

## Phase 9 — On-device E2E verification

### Task 9.1: Real-device validation (requires the device + user consent to install)

- [ ] **Step 1:** `./gradlew :app:installDebug`.
- [ ] **Step 2: Download** — enable qbdlx, disable other lossless sources (or use a track only it has), trigger a download of a Qobuz-catalog track; pull the file and byte-verify magic `fLaC` (see the `adb exec-out run-as` method in memory). Confirm `tracks.is_downloaded=1` + `file_format=flac`.
- [ ] **Step 3: Stream** — play a track that routes to qbdlx (`StreamSourceRegistry` logs `qbdlx served <id>`); confirm audible playback + the quality badge.
- [ ] **Step 4: Rotation/degradation** — temporarily set `qbdlx.tokenPool` to a junk token + the real one; confirm a junk token is marked dead and rotation reaches the live one (logcat). Set ALL junk → confirm `isEnabled()` false + the Settings "expired" notice + clean fall-through to the next source.
- [ ] **Step 5:** Capture results in a `## Verification Results` section appended to this plan; commit.

---

## Final: finishing the branch
- [ ] Run the full touched-module suites green: `./gradlew :data:download:testDebugUnitTest :core:media:testDebugUnitTest` (ignore the known pre-existing reds listed in the header).
- [ ] Use superpowers:finishing-a-development-branch to decide merge/PR.
- [ ] **User build input still owed:** append the other 4–5 pool tokens + countries to `local.properties` `qbdlx.tokenPool` before a release build (the plan ships with the one verified FR token).

## Open follow-ups (not v1)
- `TrackQuery.country` for true region-aware token selection (currently `tokensForRegion(null)` just iterates live tokens bounded).
- Signed `catalog/search` fallback if Qobuz starts requiring a sig on search.
- Secure segmented `file/url` instant-stream path (out of scope; plain progressive streaming suffices).
