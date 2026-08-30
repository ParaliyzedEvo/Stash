# Stash Lossless Relay — Plan A1: qbdlx goes tokenless + relay client — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the qbdlx (direct-Qobuz) source work with no shipped token pool: catalog calls go tokenless under the Qobuz web-player app_id, and a file URL comes from one router that tries the user's own account, then a custom endpoint, then the relays named in a signed runtime config — with the relay list pointing at nothing until Plan B ships.

**Architecture:** `QbdlxApiClient` becomes a plain HTTP client (tokenless catalog + one BYO-signed `getFileUrl`). A new `QbdlxFileUrlRouter` is the single seam that picks BYO-local / custom endpoint / config relay; `LosslessRelayClient` talks to a relay and owns its cooldowns; `LosslessConfigFetcher` pulls an ECDSA-signed `lossless.json`; `LosslessAvailability` is the one predicate class the source, downloads and Home consult. `QbdlxQobuzSource` loses every token loop. Nothing in `StreamSourceRegistry`'s chain, `StreamUrlCache`, or the download pipeline changes shape — the output is the same `etsp`-signed akamaized FLAC URL.

**Tech Stack:** Kotlin, Hilt, OkHttp 4 + MockWebServer, kotlinx.serialization, DataStore Preferences, Robolectric (for DataStore-backed stores), MockK, Truth, `java.security` ECDSA (no new dependency).

**Spec:** `docs/superpowers/specs/2026-08-29-stash-lossless-relay-design.md` (branch `design/lossless-relay`). Work in the worktree `.worktrees/lossless-relay` (already created; `local.properties` copied).

**Deliberate plan-level refinements of the spec (already reflected in the spec text):**
- Catalog tokenless calls need the **web-player app_id** (`712109809`), not the bundled one — verified live for all eight endpoints.
- Config signature is **ECDSA P-256** (`SHA256withECDSA`), not Ed25519 (absent from Android JCA below API 33).
- `QbdlxCredentialStore.allDead()` is **kept** in A1 because `SettingsViewModel` still drives the token UI with it; the source stops consulting it. It is deleted in Plan C with that UI.
- The pool code (`QbdlxPoolProvider`, `QbdlxPoolCipher`, `QbdlxRemotePool`, pool half of the store) stays in place in A1 — dead but present. Plan C deletes it.

---

## File structure

**New**
- `data/download/src/main/kotlin/com/stash/data/download/lossless/relay/LosslessRelayClient.kt` — HTTP to a relay's `/v1/qobuz/file`; per-base cooldown; maps status → `RelayMint`.
- `data/download/src/main/kotlin/com/stash/data/download/lossless/relay/LosslessConfigFetcher.kt` — fetch + ECDSA-verify + cache `lossless.json`; exposes `relays: StateFlow<List<RelayEntry>>`.
- `data/download/src/main/kotlin/com/stash/data/download/lossless/LosslessAvailability.kt` — the four predicates (Flow + suspend).
- `data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxFileUrlRouter.kt` — BYO → custom → relays.
- Tests beside each (`data/download/src/test/kotlin/.../relay/LosslessRelayClientTest.kt`, `LosslessConfigFetcherTest.kt`, `.../lossless/LosslessAvailabilityTest.kt`, `.../qbdlx/QbdlxFileUrlRouterTest.kt`).

**Modified**
- `data/download/build.gradle.kts` — delete `QBDLX_CONFIGURED`; add `LOSSLESS_CONFIG_URL`, `LOSSLESS_CONFIG_PUBKEY`.
- `data/download/.../lossless/LosslessSourceRegistry.kt:60` — drop the `QBDLX_CONFIGURED` filter.
- `core/media/.../streaming/StreamSourceRegistry.kt:170,226` — drop the gate + log term.
- `core/media/src/test/.../StreamSourceRegistryTest.kt`, `data/download/src/test/.../LosslessSourceRegistryTest.kt` — unconditional assertions.
- `data/download/.../qbdlx/QbdlxApiClient.kt` — tokenless catalog under `catalogAppId` with one self-heal; `getFileUrl(trackId, formatId, token)` unchanged.
- `data/download/.../qbdlx/HomeDiscoveryRepositoryImpl.kt`, `QobuzAlbumFetcherImpl.kt`, `QobuzDiscographyProvider.kt` — no token, no store.
- `core/model/.../QobuzDiscoveryStatus.kt`, `core/ui/.../QobuzDiscoveryBanner.kt` — delete `NO_TOKEN`.
- `data/download/.../lossless/LosslessSourcePreferences.kt` — delete `qbdlx_enabled`; add `customLosslessEndpoint`.
- `feature/settings/.../SettingsViewModel.kt` (~L1326-1340), `SettingsAudioQualityScreen.kt` (~L194-207) — remove the Direct Qobuz toggle.
- `data/download/.../qbdlx/QbdlxCredentialStore.kt` — `hasLogin` flow, `loginLive()`, pasted-token migration.
- `data/download/.../qbdlx/QbdlxQobuzSource.kt` — tokenless resolve path via the router + availability check.
- `app/src/main/kotlin/com/stash/app/StashApplication.kt` — start the config fetcher.
- Existing tests for each modified class.

**Baseline before you start:** `cd .worktrees/lossless-relay && ./gradlew :data:download:testDebugUnitTest :core:media:testDebugUnitTest --max-workers=3 -Dorg.gradle.jvmargs=-Xmx4096m` must be green except the two known-red `HomeViewModelTest` playHero matcher tests (in `feature/home`, not in these modules). Commit on branch `design/lossless-relay`. Never `git add -A` — stage explicit paths.

---

### Task 1: Delete `QBDLX_CONFIGURED`; add the config BuildConfig fields

**Files:**
- Modify: `data/download/build.gradle.kts:87,111` (and the property block ~L43-55)
- Modify: `data/download/src/main/kotlin/com/stash/data/download/lossless/LosslessSourceRegistry.kt:60`
- Modify: `core/media/src/main/kotlin/com/stash/core/media/streaming/StreamSourceRegistry.kt:170,226`
- Modify: `core/media/src/test/kotlin/com/stash/core/media/streaming/StreamSourceRegistryTest.kt:20,130,150,237`
- Modify: `data/download/src/test/kotlin/com/stash/data/download/lossless/LosslessSourceRegistryTest.kt:16,113`

- [ ] **Step 1: Make the registry tests assert unconditionally (they will fail to compile until Step 3 — that is the failing state)**

In `StreamSourceRegistryTest.kt` replace the three guarded lines:

```kotlin
// L130 and L237: was  if (BuildConfig.QBDLX_CONFIGURED) coVerify { qbdlx.resolve(track) }
coVerify { qbdlx.resolve(track) }
// L150: delete the line  assumeTrue("needs a qbdlx-configured build", BuildConfig.QBDLX_CONFIGURED)
```
Update the class KDoc at L20 to say: `qbdlx is no longer build-gated; it self-gates on LosslessAvailability.` The `BuildConfig` import stays (the ARCOD guards at ~L131/L239 still use it); `import org.junit.Assume.assumeTrue` (L13) becomes unused — delete it.

In `LosslessSourceRegistryTest.kt` delete L113 (`assumeTrue(... QBDLX_CONFIGURED)`) and fix the KDoc at L16. That file has no ARCOD guard, so both `import org.junit.Assume.assumeTrue` (L10) and `import com.stash.data.download.BuildConfig` (L9) become unused — delete them.

- [ ] **Step 2: Compile the test sources**

Run: `./gradlew :core:media:compileDebugUnitTestKotlin :data:download:compileDebugUnitTestKotlin --max-workers=3 -Dorg.gradle.jvmargs=-Xmx4096m`
Expected: BUILD SUCCESSFUL. Note: this task has no honest red state — on a machine whose `local.properties` carries qbdlx creds (this worktree does) `QBDLX_CONFIGURED` is already `true`, so the unconditional assertions pass before and after the gate deletion; they only go red on a credential-less build. The guard removal is a correctness change (a test that silently skips on CI is not a test), not a behaviour change here.

- [ ] **Step 3: Delete the gate everywhere**

`data/download/build.gradle.kts`:
```kotlin
// DELETE this line (~L87):
val qbdlxConfigured = qbdlxAppId.isNotBlank() && qbdlxAppSecret.isNotBlank() && qbdlxTokenPool.isNotBlank()
// DELETE this line (~L111):
buildConfigField("Boolean", "QBDLX_CONFIGURED", "$qbdlxConfigured")
// ADD after the qbdlxAppSecrets line (~L55):
// ── Stash Lossless Relay runtime config ────────────────────────────────────
// URL of the ECDSA-signed lossless.json (the app fetches `<url>` and `<url>.sig`)
// and the base64 X.509 SPKI public key that verifies it. Both empty → the
// fetcher is disabled and the app has no relay (BYO / custom endpoint only).
// The APK never contains a relay hostname; the list lives behind this URL.
val losslessConfigUrl = qbdlxProp("lossless.configUrl", "LOSSLESS_CONFIG_URL")
val losslessConfigPubKey = qbdlxProp("lossless.configPubKey", "LOSSLESS_CONFIG_PUBKEY")
// ADD inside defaultConfig next to the other buildConfigFields:
buildConfigField("String", "LOSSLESS_CONFIG_URL", "\"$losslessConfigUrl\"")
buildConfigField("String", "LOSSLESS_CONFIG_PUBKEY", "\"$losslessConfigPubKey\"")
```

`LosslessSourceRegistry.kt:60` — delete the line
```kotlin
.filterNot { it.id == "qbdlx_qobuz" && !com.stash.data.download.BuildConfig.QBDLX_CONFIGURED }
```
and trim the comment above it so it no longer claims qbdlx is build-gated.

`StreamSourceRegistry.kt:170`:
```kotlin
// was: if (allowYtDlp && BuildConfig.QBDLX_CONFIGURED) {
if (allowYtDlp) {
```
and at L226 remove `"qbdlxConfigured=${BuildConfig.QBDLX_CONFIGURED} " +`. Update the comment block above L170 (it explains the build gate) to: `// qbdlx self-gates on LosslessAvailability (BYO / custom endpoint / relay); no build gate.`

- [ ] **Step 4: Compile + run both registry test classes**

Run: `./gradlew :data:download:testDebugUnitTest --tests "*LosslessSourceRegistryTest" :core:media:testDebugUnitTest --tests "*StreamSourceRegistryTest" --max-workers=3 -Dorg.gradle.jvmargs=-Xmx4096m`
Expected: BUILD SUCCESSFUL, all tests PASS (`grep -rn QBDLX_CONFIGURED --include=*.kt --include=*.kts .` returns only the DownloadManager comment at `DownloadManager.kt:231` — leave that comment for Plan A2, which rewrites that block).

- [ ] **Step 5: Commit**

```bash
git add data/download/build.gradle.kts data/download/src/main/kotlin/com/stash/data/download/lossless/LosslessSourceRegistry.kt core/media/src/main/kotlin/com/stash/core/media/streaming/StreamSourceRegistry.kt core/media/src/test/kotlin/com/stash/core/media/streaming/StreamSourceRegistryTest.kt data/download/src/test/kotlin/com/stash/data/download/lossless/LosslessSourceRegistryTest.kt
git commit -m "refactor(lossless): drop the QBDLX_CONFIGURED build gate; add LOSSLESS_CONFIG_URL/PUBKEY fields"
```

---

### Task 2: `QbdlxApiClient` — tokenless catalog under the web-player app_id

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxApiClient.kt`
- Test: `data/download/src/test/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxApiClientTest.kt`

- [ ] **Step 1: Write the failing tests**

Replace the test `catalog calls send the token's own app_id, not the client default` and add two more. The constructor gains a `webCreds: QobuzWebCredentialsClient` parameter (mock it):

```kotlin
import io.mockk.coEvery
import io.mockk.mockk

// in setUp(): client = QbdlxApiClient(sharedClient = OkHttpClient(), signer = QbdlxSigner { 1000L },
//     signingResolver = { QbdlxSigning(appId = "798273057", appSecret = "secret") },
//     webCreds = webCreds).also { it.baseUrl = ...; it.appId = "798273057" }
private val webCreds: QobuzWebCredentialsClient = mockk()

@Test fun `catalog calls send the web app_id and NO user token`() = runTest {
    server.enqueue(MockResponse().setBody("""{"tracks":{"items":[]}}"""))
    client.search("anything")
    val req = server.takeRequest()
    assertThat(req.getHeader("X-App-Id")).isEqualTo(QbdlxApiClient.WEB_APP_ID)
    assertThat(req.path).contains("app_id=${QbdlxApiClient.WEB_APP_ID}")
    assertThat(req.getHeader("X-User-Auth-Token")).isNull()
    assertThat(req.path).doesNotContain("request_sig")
}

@Test fun `catalog 401 refreshes the web app_id once and retries`() = runTest {
    coEvery { webCreds.fetch() } returns QobuzWebCreds(appId = "999999999", appSecret = "s")
    server.enqueue(MockResponse().setResponseCode(401).setBody("""{"status":"error","code":401,"message":"User authentication is required."}"""))
    server.enqueue(MockResponse().setBody("""{"tracks":{"items":[{"id":7,"title":"x"}]}}"""))
    val items = client.search("anything")
    assertThat(items.single().id).isEqualTo(7)
    server.takeRequest()
    assertThat(server.takeRequest().getHeader("X-App-Id")).isEqualTo("999999999")
    assertThat(client.catalogAppId).isEqualTo("999999999")
}

@Test fun `catalog 401 with no fresh app_id throws QbdlxAuthException`() = runTest {
    coEvery { webCreds.fetch() } returns null
    server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
    try { client.search("anything"); org.junit.Assert.fail("expected QbdlxAuthException") }
    catch (e: QbdlxAuthException) { assertThat(e.status).isEqualTo(401) }
}
```
Existing `getFileUrl` tests keep the `token = "tok"` argument — that method is unchanged. **Update the nine existing catalog calls in this test file** (they still pass `token = "tok"`): `search(…, token = "tok")` at ~L33, L109, L122, L131; `getFeaturedAlbums(…, "tok", …)` at ~L139, L150; `getFeaturedPlaylists(…, "tok", …)` at ~L157, L164; `getPlaylist(…, "tok")` at ~L177 — drop the token argument from each. Three of them change meaning, not just arity:
- `403 USER_BLOCKED is a dead token, not a service failure` (~L102) and `other 403s remain transient api errors` (~L117) exercise the `USER_BLOCKED` branch of `get()` **through `search`**; the tokenless `catalogGetOnce` has no such branch. Re-point both at `client.getFileUrl(42, 27, token = "tok")` (which still uses `get()`), keeping their assertions.
- `search 401 throws TokenDead-signalling exception` (~L129) now enters `catalogGet`'s self-heal and calls `webCreds.fetch()` — add `coEvery { webCreds.fetch() } returns null` to it (an unstubbed `mockk()` throws `MockKException`, not the expected `QbdlxAuthException`). This test is now the same case as the new `catalog 401 with no fresh app_id throws QbdlxAuthException` — keep one, delete the other.
- `search parses track items` (~L31-37): its last assertion `getHeader("X-User-Auth-Token")).isEqualTo("tok")` inverts — tokenless catalog sends no such header. Change it to `.isNull()`.

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :data:download:testDebugUnitTest --tests "*QbdlxApiClientTest" --max-workers=3 -Dorg.gradle.jvmargs=-Xmx4096m`
Expected: FAIL to compile (`webCreds` param, `search(query)` without token, `WEB_APP_ID`, `catalogAppId` don't exist).

- [ ] **Step 3: Implement**

In `QbdlxApiClient.kt`:

```kotlin
@Singleton
class QbdlxApiClient @Inject constructor(
    sharedClient: OkHttpClient,
    private val signer: QbdlxSigner,
    private val signingResolver: QbdlxSigningResolver,
    private val webCreds: QobuzWebCredentialsClient,
) {
    /**
     * The app_id catalog calls run under, with NO user token. Qobuz's web player
     * browses its catalog logged-out under this id (verified live 2026-08-29: all
     * eight catalog endpoints answer 200 tokenless here and 401 under the bundled
     * Android-lineage id). Public — it sits in Qobuz's own JS bundle. Self-heals:
     * a 401 refreshes it once from the live bundle via [QobuzWebCredentialsClient].
     */
    @Volatile internal var catalogAppId: String = WEB_APP_ID
    internal var httpClient: OkHttpClient = sharedClient
    internal var baseUrl: String = ORIGIN
    internal var json: Json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
```

Change every catalog method to drop the `token` parameter and call `catalogGet(url)`. For example:

```kotlin
    suspend fun search(query: String, limit: Int = 10): List<QbdlxTrack> =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/api.json/0.2/catalog/search".toHttpUrl().newBuilder()
                .addQueryParameter("query", query)
                .addQueryParameter("type", "tracks")
                .addQueryParameter("limit", limit.toString())
                .build()
            val body = catalogGet(url.toString())
            runCatching { json.decodeFromString<QbdlxSearchResponse>(body).tracks.items }.getOrDefault(emptyList())
        }
```
Apply the same to `searchArtists(query, limit)`, `searchPlaylists(query, limit, offset)`, `getArtistAlbums(artistId, limit)`, `getAlbum(albumId)`, `getFeaturedAlbums(type, genreId, limit)`, `getFeaturedPlaylists(genreId, limit, offset)`, `getPlaylist(playlistId, limit)` — remove `.addQueryParameter("app_id", appId)` from each (`catalogGet` sets it). `getFileUrl(trackId, formatId, token)` stays exactly as is (it calls the existing `get(url, token, appIdHeader)`).

Add the tokenless getter:

```kotlin
    /** Catalog GET under [catalogAppId], no user token; one self-heal on 401. */
    private suspend fun catalogGet(url: String): String {
        try {
            return catalogGetOnce(url, catalogAppId)
        } catch (e: QbdlxAuthException) {
            val fresh = webCreds.fetch()?.appId?.takeIf { it.isNotBlank() && it != catalogAppId } ?: throw e
            android.util.Log.i(TAG, "catalog app_id rotated ${catalogAppId} -> $fresh after 401")
            catalogAppId = fresh
            return catalogGetOnce(url, fresh)
        }
    }

    private fun catalogGetOnce(url: String, appIdForCall: String): String {
        val req = Request.Builder()
            .url(url.toHttpUrl().newBuilder().setQueryParameter("app_id", appIdForCall).build())
            .header("X-App-Id", appIdForCall)
            .header("Accept", "application/json")
            .header("User-Agent", UA)
            .get().build()
        httpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code == 401) throw QbdlxAuthException(401, body.take(120))
            if (!resp.isSuccessful) {
                android.util.Log.w(TAG, "HTTP ${resp.code} on ${url.substringBefore('?').substringAfterLast('/')}: ${body.take(160)}")
                throw QbdlxApiException(resp.code, body.take(120))
            }
            return body
        }
    }
```
and in the companion: `const val WEB_APP_ID = "712109809"`. Delete the now-stale comment about "catalog/search answers 401 on an app_id mismatch" — replace with one line pointing at `catalogAppId`.

**Keep the module compiling (Gradle compiles ALL main + test sources of `:data:download` before any `--tests` filter applies).** These are arity-only edits — the semantic clean-up of the consumers is Task 3:
- `QbdlxQobuzSource.kt:115`: `apiClient.search(term, token)` → `apiClient.search(term)`. Leave the token loop otherwise untouched — Task 10 removes it; a 401 from the tokenless search still lands in the existing `catch (e: QbdlxAuthException)` and rotates harmlessly.
- `HomeDiscoveryRepositoryImpl.kt:47,52,58,64`: keep `withToken { … }` for now but stop passing the token: `withToken { _ -> client.getFeaturedPlaylists(genreId) }`, `withToken { _ -> client.getFeaturedPlaylists(genreId, limit, offset) }`, `withToken { _ -> client.searchPlaylists(query, limit, offset) }`, `withToken { _ -> client.getFeaturedAlbums(type, genreId) }`.
- `QobuzAlbumFetcherImpl.kt:25,51`: `apiClient.getAlbum(qobuzAlbumId)`, `apiClient.getPlaylist(playlistId)` (leave the `activeToken() ?: error(…)` lines; Task 3 deletes them).
- `QobuzDiscographyProvider.kt:41,67`: `apiClient.searchArtists(artistName)`, `apiClient.getArtistAlbums(best.id)`.
- Test stubs — drop the token argument only: `QbdlxQobuzSourceTest.kt` (~L69, 94, 110, 146, 158, 182, 200, 213) and `QbdlxBypassRateLimitTest.kt:52` (`apiClient.search(any(), …)` → `apiClient.search(any())`); `HomeDiscoveryRepositoryImplTest.kt:24,39,41,45,47,51,69` (`getFeaturedAlbums(x, y, "tok", any())`/`(any(), any(), any(), any())` → `getFeaturedAlbums(x, y, any())`/`(any(), any(), any())`; `getFeaturedPlaylists(133, "tok", 30, 60)` → `(133, 30, 60)`); `QobuzAlbumFetcherImplTest.kt:31,61` (`getAlbum("id", "tok")` → `getAlbum("id")`, same for `getPlaylist`). `QobuzDiscographyProviderTest` needs NO edit — every stub there is already `any(), any()`, which still matches `(String, Int = default)`.
- **Delete two token-rotation tests here, not later** — dropping the token makes their throwing stub and their returning stub the same `coEvery { … search(any()) }`, and MockK's last stub wins, so they cannot pass: `QbdlxQobuzSourceTest` `search auth failure marks token dead and rotates without tripping breaker` (~L124-133; Task 10 replaces it with `catalog 401 after self-heal is a plain miss`) and `HomeDiscoveryRepositoryImplTest` `401 rotates the token then retries once` (~L56-62).
- `QbdlxApiClient.kt:255`: `private companion object` → `internal companion object` (the tests reference `QbdlxApiClient.WEB_APP_ID`; the test source set already sees `internal` members like `baseUrl`).
- `QbdlxApiClient.appId` has no reader after this task (`getFileUrl` always passes `signing.appId`; `get()`'s fallback goes through `signingResolver`) — delete the field and its comment, and delete `it.appId = "798273057"` from both `QbdlxApiClientTest` constructions.

- [ ] **Step 4: Run the test class — and confirm the module compiles**

Run: `./gradlew :data:download:testDebugUnitTest --tests "*QbdlxApiClientTest" --tests "*QbdlxQobuzSourceTest" --max-workers=3 -Dorg.gradle.jvmargs=-Xmx4096m`
Expected: BUILD SUCCESSFUL, both classes PASS (the source tests still pass: their token stubs are unchanged except `search`).

- [ ] **Step 5: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxApiClient.kt data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxQobuzSource.kt data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/HomeDiscoveryRepositoryImpl.kt data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/QobuzAlbumFetcherImpl.kt data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/QobuzDiscographyProvider.kt data/download/src/test/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxApiClientTest.kt data/download/src/test/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxQobuzSourceTest.kt data/download/src/test/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxBypassRateLimitTest.kt data/download/src/test/kotlin/com/stash/data/download/lossless/qbdlx/HomeDiscoveryRepositoryImplTest.kt data/download/src/test/kotlin/com/stash/data/download/lossless/qbdlx/QobuzAlbumFetcherImplTest.kt data/download/src/test/kotlin/com/stash/data/download/lossless/qbdlx/QobuzDiscographyProviderTest.kt
git commit -m "feat(qbdlx): catalog calls go tokenless under the Qobuz web app_id with one self-heal"
```

---

### Task 3: Catalog consumers lose the token gate; delete `NO_TOKEN`

**Files:**
- Modify: `data/download/.../qbdlx/HomeDiscoveryRepositoryImpl.kt`, `QobuzAlbumFetcherImpl.kt`, `QobuzDiscographyProvider.kt`
- Modify: `core/model/src/main/kotlin/com/stash/core/model/QobuzDiscoveryStatus.kt`, `core/ui/src/main/kotlin/com/stash/core/ui/components/QobuzDiscoveryBanner.kt`
- Test: `HomeDiscoveryRepositoryImplTest.kt`, `QobuzAlbumFetcherImplTest.kt`, `QobuzDiscographyProviderTest.kt` (same directory)

- [ ] **Step 1: Update the tests to the tokenless contract**

`HomeDiscoveryRepositoryImplTest`: construct `HomeDiscoveryRepositoryImpl(client)` (no store; delete the `store` field and the `coEvery { store.activeToken() }` line in `setup()`); every `client.getFeaturedAlbums(type, genre, "tok", any())` stub becomes `client.getFeaturedAlbums(type, genre, any())`; the `browsePlaylists passes offset+limit through and maps` stub `client.getFeaturedPlaylists(133, "tok", 30, 60)` becomes `client.getFeaturedPlaylists(133, 30, 60)`; delete the test `no live token yields empty list` (~L63-66, stubs a collaborator that no longer exists; `401 rotates the token then retries once` was already deleted in Task 2); add:

```kotlin
@Test fun `auth failure yields empty list and OK status (no token concept)`() = runTest {
    coEvery { client.getFeaturedAlbums(any(), any(), any()) } throws QbdlxAuthException(401)
    assertThat(repo.newReleases(null)).isEmpty()
    assertThat(repo.status.value).isEqualTo(com.stash.core.model.discovery.QobuzDiscoveryStatus.OK)
}
@Test fun `IOException sets NO_INTERNET`() = runTest {
    coEvery { client.getFeaturedAlbums(any(), any(), any()) } throws java.io.IOException("offline")
    assertThat(repo.newReleases(null)).isEmpty()
    assertThat(repo.status.value).isEqualTo(com.stash.core.model.discovery.QobuzDiscoveryStatus.NO_INTERNET)
}
```
`QobuzAlbumFetcherImplTest`: construct with `(apiClient)` only; remove the `credentialStore.activeToken()` stubs at ~L30 and ~L60 (the `getAlbum("id")`/`getPlaylist("id")` stubs already lost their token arg in Task 2); delete the test `throws when no live token` (~L42-49).
`QobuzDiscographyProviderTest`: construct with `(apiClient)` only; delete the `source.isEnabledForStreaming()` and `credentialStore.activeToken()` stubs; delete BOTH gate tests — (a) the one asserting "source disabled → unchanged" and (b) `no active token returns yt lists unchanged and never fetches` (~L74-82). The `searchArtists(name)` / `getArtistAlbums(id)` stubs already lost their token arg in Task 2.

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :data:download:testDebugUnitTest --tests "*HomeDiscoveryRepositoryImplTest" --tests "*QobuzAlbumFetcherImplTest" --tests "*QobuzDiscographyProviderTest" --max-workers=3 -Dorg.gradle.jvmargs=-Xmx4096m`
Expected: compile FAIL (constructors / signatures).

- [ ] **Step 3: Implement**

`HomeDiscoveryRepositoryImpl.kt` — constructor `(private val client: QbdlxApiClient)`; replace `withToken` with:

```kotlin
    /** Fail-soft: IOException → NO_INTERNET; anything else → empty (status OK). */
    private suspend fun <T> safely(call: suspend () -> List<T>): List<T> =
        try {
            call().also { _status.value = QobuzDiscoveryStatus.OK }
        } catch (e: java.io.IOException) {
            _status.value = QobuzDiscoveryStatus.NO_INTERNET
            emptyList()
        }
```
and every call site: `safely { client.getFeaturedPlaylists(genreId) }`, `safely { client.getFeaturedPlaylists(genreId, limit, offset) }`, `safely { client.searchPlaylists(query, limit, offset) }`, `safely { client.getFeaturedAlbums(type, genreId) }`. (`cached` already turns any other throw into an empty, uncached result.) Fix the class KDoc ("via the qbdlx token pool" → "tokenless, under the web-player app_id").

`QobuzAlbumFetcherImpl.kt` — constructor `(private val apiClient: QbdlxApiClient)`; `val r = apiClient.getAlbum(qobuzAlbumId)`; `val p = apiClient.getPlaylist(playlistId)`; delete both `error("qbdlx: no live token")` lines and the store field.

`QobuzDiscographyProvider.kt` — constructor `(private val apiClient: QbdlxApiClient)`; delete the two gate lines (`if (!source.isEnabledForStreaming()) …` and `val token = …`); `apiClient.searchArtists(artistName)`; `apiClient.getArtistAlbums(best.id)`. Update the KDoc bullet "source off, no token" → "blank/VA name".

`QobuzDiscoveryStatus.kt`: `enum class QobuzDiscoveryStatus { OK, NO_INTERNET }`.
`QobuzDiscoveryBanner.kt`: delete the `NO_TOKEN ->` branch.

`di/QbdlxModule.kt` needs no change (all three still have `@Inject` constructors).

- [ ] **Step 4: Run the three test classes**

Run: same command as Step 2.
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/HomeDiscoveryRepositoryImpl.kt data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/QobuzAlbumFetcherImpl.kt data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/QobuzDiscographyProvider.kt core/model/src/main/kotlin/com/stash/core/model/QobuzDiscoveryStatus.kt core/ui/src/main/kotlin/com/stash/core/ui/components/QobuzDiscoveryBanner.kt data/download/src/test/kotlin/com/stash/data/download/lossless/qbdlx/HomeDiscoveryRepositoryImplTest.kt data/download/src/test/kotlin/com/stash/data/download/lossless/qbdlx/QobuzAlbumFetcherImplTest.kt data/download/src/test/kotlin/com/stash/data/download/lossless/qbdlx/QobuzDiscographyProviderTest.kt
git commit -m "refactor(qbdlx): discovery, album and discography consumers no longer need a token; drop NO_TOKEN"
```

---

### Task 4: Preferences — delete `qbdlx_enabled`, add the custom endpoint

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/lossless/LosslessSourcePreferences.kt:50,86-102`
- Modify: `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsViewModel.kt` (~L1326-1340)
- Modify: `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsAudioQualityScreen.kt` (~L90, ~L194-207)
- Test: `data/download/src/test/kotlin/com/stash/data/download/lossless/LosslessSourcePreferencesTest.kt` (create if absent; Robolectric like `ArcodCredentialStoreTest`)

- [ ] **Step 1: Write the failing test**

```kotlin
package com.stash.data.download.lossless

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.DownloadQueueDao
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LosslessSourcePreferencesTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = LosslessSourcePreferences(ctx, mockk<DownloadQueueDao>(relaxed = true))

    /** The preferencesDataStore delegate is process-wide; start each test clean (codebase convention). */
    @Before fun clear() = runBlocking { prefs.setCustomLosslessEndpoint(null) }

    @Test fun `custom endpoint is null by default, normalised on set, cleared on blank`() = runTest {
        assertThat(prefs.customLosslessEndpoint.first()).isNull()
        prefs.setCustomLosslessEndpoint("  https://relay.example.org/  ")
        assertThat(prefs.customLosslessEndpointNow()).isEqualTo("https://relay.example.org")
        prefs.setCustomLosslessEndpoint("http://insecure.example")   // not https → rejected
        assertThat(prefs.customLosslessEndpointNow()).isNull()
        prefs.setCustomLosslessEndpoint("https://relay.example.org")
        prefs.setCustomLosslessEndpoint("   ")
        assertThat(prefs.customLosslessEndpointNow()).isNull()
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :data:download:testDebugUnitTest --tests "*LosslessSourcePreferencesTest" --max-workers=3 -Dorg.gradle.jvmargs=-Xmx4096m`
Expected: compile FAIL (`customLosslessEndpoint` missing).

- [ ] **Step 3: Implement**

In `LosslessSourcePreferences.kt` delete `qbdlxEnabledKey`, the `qbdlxEnabled` flow, `qbdlxEnabledNow()`, `setQbdlxEnabled()` and their KDoc. Add:

```kotlin
    private val customLosslessEndpointKey = stringPreferencesKey("custom_lossless_endpoint")

    /**
     * A user-supplied lossless relay base URL (Settings › Audio › Advanced), or
     * null. Outranks the public relay from runtime config when set — an explicit
     * choice beats an implicit one. Stored normalised: https only, no trailing
     * slash, no query. The APK ships no default.
     */
    val customLosslessEndpoint: Flow<String?> = context.losslessDataStore.data.map { prefs ->
        prefs[customLosslessEndpointKey]?.takeIf { it.isNotBlank() }
    }

    suspend fun customLosslessEndpointNow(): String? = customLosslessEndpoint.first()

    suspend fun setCustomLosslessEndpoint(raw: String?) {
        val v = normaliseEndpoint(raw)
        context.losslessDataStore.edit { prefs ->
            if (v == null) prefs.remove(customLosslessEndpointKey) else prefs[customLosslessEndpointKey] = v
        }
    }

    companion object {
        /** https only; trims; strips trailing slashes and any query/fragment. Null when unusable. */
        fun normaliseEndpoint(raw: String?): String? {
            val t = raw?.trim()?.substringBefore('?')?.substringBefore('#')?.trimEnd('/') ?: return null
            return t.takeIf { it.startsWith("https://") && it.length > "https://".length }
        }
        // … existing DEFAULT_PRIORITY stays …
    }
```
(There is already a `companion object` — add `normaliseEndpoint` inside it.)

`SettingsViewModel.kt`: delete `val qbdlxEnabled: StateFlow<Boolean>` and `onQbdlxEnabledChange`. `SettingsAudioQualityScreen.kt`: delete the `val qbdlxEnabled by …` line (~L90) and the `SettingsToggleRow(title = "Direct Qobuz", …)` block; change `AnimatedVisibility(visible = qbdlxEnabled, …)` to `visible = true` (or unwrap it — keep the inner `Column`). The token/paste/picker UI inside stays for Plan C.

`feature/settings/src/test/kotlin/com/stash/feature/settings/SettingsViewModelTest.kt`: delete the `qbdlxEnabledFlow` field (~L37), the `every { it.qbdlxEnabled } returns qbdlxEnabledFlow` stub (~L39) and the whole test `onQbdlxEnabledChange persists via setQbdlxEnabled` (~L90-94).

**Keep the module compiling:** `QbdlxQobuzSource.kt:49,60` — remove the `losslessPrefs.qbdlxEnabledNow() &&` term from both `isEnabled()` and `isEnabledForStreaming()` (they gate on the breaker / `!credentialStore.allDead()` alone until Task 10 swaps in `LosslessAvailability`). In `QbdlxQobuzSourceTest.kt` remove **all five** `coEvery { prefs.qbdlxEnabledNow() } returns …` stubs (L58 in `enabledAndAcquired()`, and L140, L151, L170, L194), and **delete the test `disabled toggle blocks both download and streaming gates` (~L168-176)** — with the toggle term gone its `isFalse()` assertions cannot hold (Task 10 replaces it with `disabled when availability says no`). Remove the stub at `QbdlxBypassRateLimitTest.kt:46` too.

- [ ] **Step 4: Run tests in both modules**

Run: `./gradlew :data:download:testDebugUnitTest --tests "*LosslessSourcePreferencesTest" --tests "*QbdlxQobuzSourceTest" :feature:settings:testDebugUnitTest --tests "*SettingsViewModelTest" --max-workers=3 -Dorg.gradle.jvmargs=-Xmx4096m`
Expected: BUILD SUCCESSFUL, all PASS. Then `git grep -n "qbdlxEnabled" -- "*.kt"` → **no matches**.

- [ ] **Step 5: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/lossless/LosslessSourcePreferences.kt data/download/src/test/kotlin/com/stash/data/download/lossless/LosslessSourcePreferencesTest.kt data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxQobuzSource.kt data/download/src/test/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxQobuzSourceTest.kt data/download/src/test/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxBypassRateLimitTest.kt feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsViewModel.kt feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsAudioQualityScreen.kt feature/settings/src/test/kotlin/com/stash/feature/settings/SettingsViewModelTest.kt
git commit -m "feat(lossless): custom relay endpoint pref; delete the Direct Qobuz toggle (a stale false must not kill lossless)"
```

---

### Task 5: `LosslessRelayClient`

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/lossless/relay/LosslessRelayClient.kt`
- Test: `data/download/src/test/kotlin/com/stash/data/download/lossless/relay/LosslessRelayClientTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.stash.data.download.lossless.relay

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class LosslessRelayClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: LosslessRelayClient
    private var now = 1_000_000L
    private val base get() = server.url("/").toString().trimEnd('/')

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        client = LosslessRelayClient(OkHttpClient()).also { it.clock = { now } }
    }
    @After fun tearDown() { server.shutdown() }

    @Test fun `200 maps to Ok with Hz as sent and the protocol header`() = runTest {
        server.enqueue(MockResponse().setBody("""{"url":"https://cdn.example/f.flac?etsp=1","format_id":27,"bit_depth":24,"sample_rate":96000}"""))
        val r = client.mint(base, 42, 27)
        assertThat(r).isEqualTo(RelayMint.Ok("https://cdn.example/f.flac?etsp=1", 27, 24, 96_000))
        val req = server.takeRequest()
        assertThat(req.path).isEqualTo("/v1/qobuz/file?track_id=42&format_id=27")
        assertThat(req.getHeader("X-Stash-Version")).isEqualTo(LosslessRelayClient.PROTOCOL_VERSION)
        assertThat(client.isCooled(base)).isFalse()
    }

    @Test fun `404 is NoMatch with no cooldown`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"status":"no_match"}"""))
        assertThat(client.mint(base, 42, 27)).isEqualTo(RelayMint.NoMatch)
        assertThat(client.isCooled(base)).isFalse()
    }

    @Test fun `503 busy cools the base for 60s and skips the request while cooled`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"status":"busy","retry_after":30}"""))
        assertThat(client.mint(base, 42, 27)).isEqualTo(RelayMint.Unavailable)
        assertThat(client.isCooled(base)).isTrue()
        assertThat(client.mint(base, 43, 27)).isEqualTo(RelayMint.Unavailable)
        assertThat(server.requestCount).isEqualTo(1)
        now += LosslessRelayClient.BUSY_COOLDOWN_MS + 1
        assertThat(client.isCooled(base)).isFalse()
    }

    @Test fun `502 and unreachable cool the base for 5 minutes`() = runTest {
        server.enqueue(MockResponse().setResponseCode(502).setBody("""{"status":"upstream"}"""))
        assertThat(client.mint(base, 42, 27)).isEqualTo(RelayMint.Unavailable)
        now += LosslessRelayClient.BUSY_COOLDOWN_MS + 1
        assertThat(client.isCooled(base)).isTrue()
        now += LosslessRelayClient.UNAVAILABLE_COOLDOWN_MS
        assertThat(client.isCooled(base)).isFalse()

        server.shutdown()
        assertThat(client.mint(base, 42, 27)).isEqualTo(RelayMint.Unavailable)
        assertThat(client.isCooled(base)).isTrue()
    }

    @Test fun `200 with a non-https url is Unavailable`() = runTest {
        server.enqueue(MockResponse().setBody("""{"url":"http://cdn.example/f.flac","format_id":27,"bit_depth":16,"sample_rate":44100}"""))
        assertThat(client.mint(base, 42, 27)).isEqualTo(RelayMint.Unavailable)
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :data:download:testDebugUnitTest --tests "*LosslessRelayClientTest" --max-workers=3 -Dorg.gradle.jvmargs=-Xmx4096m`
Expected: compile FAIL (class missing).

- [ ] **Step 3: Implement**

```kotlin
package com.stash.data.download.lossless.relay

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Outcome of one relay mint. */
sealed interface RelayMint {
    /** [sampleRateHz] is already Hz — the relay converts Qobuz's kHz; never multiply here. */
    data class Ok(val url: String, val formatId: Int, val bitDepth: Int, val sampleRateHz: Int) : RelayMint
    /** 404: not streamable / region-locked for the relay's accounts. Next rung; no cooldown. */
    object NoMatch : RelayMint
    /** The base is unavailable right now and has been cooled; try the next base. */
    object Unavailable : RelayMint
}

@Serializable
internal data class RelayFileResponse(
    val url: String? = null,
    val format_id: Int = 0,
    val bit_depth: Int = 0,
    val sample_rate: Int = 0,
)

/**
 * Talks to a Stash lossless relay (`GET {base}/v1/qobuz/file`) and OWNS the
 * per-base cooldown: `busy` → 60 s, anything else non-2xx/404 or unreachable →
 * 5 min. Neither [com.stash.data.download.lossless.LosslessSourceHealthGate]
 * (fixed 5 min, not consulted by the streaming resolver) nor
 * `LosslessSourceHealth` (a miss counter) fit, and because both the streaming
 * and download paths reach a relay through this one @Singleton, the cooldown
 * covers both automatically.
 */
@Singleton
class LosslessRelayClient @Inject constructor(sharedClient: OkHttpClient) {
    internal var httpClient: OkHttpClient = sharedClient.newBuilder()
        .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .build()
    internal var clock: () -> Long = { System.currentTimeMillis() }
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private val cooledUntil = ConcurrentHashMap<String, Long>()

    /** True while [base] is inside a cooldown; expired entries are dropped on read. */
    fun isCooled(base: String): Boolean {
        val until = cooledUntil[base] ?: return false
        if (clock() < until) return true
        cooledUntil.remove(base)
        return false
    }

    suspend fun mint(base: String, trackId: Long, formatId: Int): RelayMint = withContext(Dispatchers.IO) {
        if (isCooled(base)) return@withContext RelayMint.Unavailable
        val url = "$base/v1/qobuz/file".toHttpUrl().newBuilder()
            .addQueryParameter("track_id", trackId.toString())
            .addQueryParameter("format_id", formatId.toString())
            .build()
        val req = Request.Builder().url(url)
            .header("X-Stash-Version", PROTOCOL_VERSION)
            .header("Accept", "application/json")
            .get().build()
        val resp = try {
            httpClient.newCall(req).execute()
        } catch (e: java.io.IOException) {
            Log.w(TAG, "relay unreachable (${e.javaClass.simpleName}) — cooling ${UNAVAILABLE_COOLDOWN_MS / 1000}s")
            cool(base, UNAVAILABLE_COOLDOWN_MS)
            return@withContext RelayMint.Unavailable
        }
        resp.use { r ->
            val body = r.body?.string().orEmpty()
            when (r.code) {
                200 -> {
                    val parsed = runCatching { json.decodeFromString<RelayFileResponse>(body) }.getOrNull()
                    val u = parsed?.url?.takeIf { it.startsWith("https://") }
                    if (parsed == null || u == null) {
                        Log.w(TAG, "relay 200 with an unusable body — cooling")
                        cool(base, UNAVAILABLE_COOLDOWN_MS)
                        RelayMint.Unavailable
                    } else {
                        RelayMint.Ok(u, parsed.format_id, parsed.bit_depth, parsed.sample_rate)
                    }
                }
                404 -> RelayMint.NoMatch
                503 -> {
                    Log.i(TAG, "relay busy — cooling ${BUSY_COOLDOWN_MS / 1000}s")
                    cool(base, BUSY_COOLDOWN_MS)
                    RelayMint.Unavailable
                }
                else -> {
                    Log.w(TAG, "relay HTTP ${r.code}: ${body.take(120)} — cooling ${UNAVAILABLE_COOLDOWN_MS / 1000}s")
                    cool(base, UNAVAILABLE_COOLDOWN_MS)
                    RelayMint.Unavailable
                }
            }
        }
    }

    private fun cool(base: String, ms: Long) { cooledUntil[base] = clock() + ms }

    companion object {
        private const val TAG = "LosslessRelay"
        private const val TIMEOUT_S = 8L
        /** Wire-protocol version sent as `X-Stash-Version` (the relay rejects requests without it). */
        const val PROTOCOL_VERSION = "1"
        const val BUSY_COOLDOWN_MS = 60_000L
        const val UNAVAILABLE_COOLDOWN_MS = 5 * 60_000L
    }
}
```

- [ ] **Step 4: Run the test class**

Run: `./gradlew :data:download:testDebugUnitTest --tests "*LosslessRelayClientTest" --max-workers=3 -Dorg.gradle.jvmargs=-Xmx4096m`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/lossless/relay/LosslessRelayClient.kt data/download/src/test/kotlin/com/stash/data/download/lossless/relay/LosslessRelayClientTest.kt
git commit -m "feat(lossless): relay client with per-base cooldowns (busy 60s, unavailable 5m)"
```

---

### Task 6: `LosslessConfigFetcher` — signed runtime relay list

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/lossless/relay/LosslessConfigFetcher.kt`
- Modify: `app/src/main/kotlin/com/stash/app/StashApplication.kt` (~L155-165 injections, ~L247 first `applicationScope.launch`)
- Test: `data/download/src/test/kotlin/com/stash/data/download/lossless/relay/LosslessConfigFetcherTest.kt`

- [ ] **Step 1: Write the failing tests** (Robolectric for the DataStore; the test generates its own EC keypair and signs)

```kotlin
package com.stash.data.download.lossless.relay

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LosslessConfigFetcherTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var server: MockWebServer
    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val pub = Base64.getEncoder().encodeToString(keys.public.encoded)
    private val body = """{"v":1,"relays":[{"base":"https://b.example","priority":2},{"base":"https://a.example/","priority":1}],"updated_at":1}"""

    private fun sign(bytes: ByteArray): String = Base64.getEncoder().encodeToString(
        Signature.getInstance("SHA256withECDSA").apply { initSign(keys.private); update(bytes) }.sign(),
    )
    private fun fetcher() = LosslessConfigFetcher(ctx, OkHttpClient()).also {
        it.configUrl = server.url("/stash/lossless.json").toString()
        it.publicKeyB64 = pub
    }

    @Before fun setUp() { server = MockWebServer(); server.start(); runBlocking { fetcher().clearForTest() } }
    @After fun tearDown() { server.shutdown() }

    @Test fun `valid signature applies and caches the list sorted by priority with bases normalised`() = runTest {
        server.enqueue(MockResponse().setBody(body))
        server.enqueue(MockResponse().setBody(sign(body.toByteArray())))
        val f = fetcher()
        assertThat(f.refresh()).isTrue()
        assertThat(f.relays.value.map { it.base }).containsExactly("https://a.example", "https://b.example").inOrder()
        assertThat(server.takeRequest().path).isEqualTo("/stash/lossless.json")
        assertThat(server.takeRequest().path).isEqualTo("/stash/lossless.json.sig")

        val cold = fetcher()
        cold.loadCached()
        assertThat(cold.relays.value.map { it.base }).containsExactly("https://a.example", "https://b.example").inOrder()
    }

    @Test fun `tampered body is ignored and the cached copy kept`() = runTest {
        server.enqueue(MockResponse().setBody(body)); server.enqueue(MockResponse().setBody(sign(body.toByteArray())))
        val f = fetcher(); f.refresh()
        val evil = body.replace("a.example", "evil.example")
        server.enqueue(MockResponse().setBody(evil)); server.enqueue(MockResponse().setBody(sign(body.toByteArray())))
        assertThat(f.refresh()).isFalse()
        assertThat(f.relays.value.map { it.base }).contains("https://a.example")
    }

    @Test fun `network failure keeps the cached copy, and no cache means no relays`() = runTest {
        val f = fetcher()
        f.loadCached()
        assertThat(f.relays.value).isEmpty()
        server.shutdown()
        assertThat(f.refresh()).isFalse()
        assertThat(f.relays.value).isEmpty()
    }

    @Test fun `disabled when url or key is blank`() = runTest {
        val f = fetcher().also { it.publicKeyB64 = "" }
        assertThat(f.enabled).isFalse()
        assertThat(f.refresh()).isFalse()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun `non-https or malformed bases are dropped`() = runTest {
        val b = """{"v":1,"relays":[{"base":"http://plain.example","priority":1},{"base":"https://ok.example?x=1","priority":1}],"updated_at":1}"""
        server.enqueue(MockResponse().setBody(b)); server.enqueue(MockResponse().setBody(sign(b.toByteArray())))
        val f = fetcher(); f.refresh()
        assertThat(f.relays.value.map { it.base }).containsExactly("https://ok.example")
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :data:download:testDebugUnitTest --tests "*LosslessConfigFetcherTest" --max-workers=3 -Dorg.gradle.jvmargs=-Xmx4096m`
Expected: compile FAIL.

- [ ] **Step 3: Implement**

```kotlin
package com.stash.data.download.lossless.relay

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stash.data.download.BuildConfig
import com.stash.data.download.lossless.LosslessSourcePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable data class RelayEntry(val base: String, val priority: Int = 1)
@Serializable data class LosslessConfig(val v: Int = 1, val relays: List<RelayEntry> = emptyList(), val updated_at: Long = 0)

private val Context.losslessRelayConfigDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "lossless_relay_config",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * The runtime relay list. The APK ships NO relay hostname: this fetches
 * `<configUrl>` + `<configUrl>.sig` (ECDSA P-256 over the exact JSON bytes),
 * verifies against the baked-in public key, caches the JSON, and exposes
 * [relays] sorted by priority. Invalid signature / network failure → the cached
 * copy stays; no cache → no relays. Both BuildConfig values empty → disabled.
 */
@Singleton
class LosslessConfigFetcher @Inject constructor(
    @ApplicationContext private val context: Context,
    sharedClient: OkHttpClient,
) {
    internal var configUrl: String = BuildConfig.LOSSLESS_CONFIG_URL
    internal var publicKeyB64: String = BuildConfig.LOSSLESS_CONFIG_PUBKEY
    internal var httpClient: OkHttpClient = sharedClient
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonKey = stringPreferencesKey("config_json")

    private val _relays = MutableStateFlow<List<RelayEntry>>(emptyList())
    val relays: StateFlow<List<RelayEntry>> = _relays.asStateFlow()

    val enabled: Boolean get() = configUrl.isNotBlank() && publicKeyB64.isNotBlank()

    /** Populate [relays] from the cached JSON, if any. Cheap; call before the first resolve. */
    suspend fun loadCached() {
        val cached = runCatching { context.losslessRelayConfigDataStore.data.first()[jsonKey] }.getOrNull() ?: return
        parse(cached)?.let { _relays.value = it }
    }

    /** Fetch + verify + apply. Returns true only when a fresh, valid config was applied. Never throws. */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext false
        val body = runCatching { getBytes(configUrl) }.getOrNull() ?: return@withContext false
        val sig = runCatching { String(getBytes("$configUrl.sig")).trim() }.getOrNull() ?: return@withContext false
        if (!verify(body, sig)) {
            Log.w(TAG, "lossless.json signature invalid — keeping the cached copy")
            return@withContext false
        }
        val text = String(body)
        val parsed = parse(text) ?: return@withContext false
        _relays.value = parsed
        runCatching { context.losslessRelayConfigDataStore.edit { it[jsonKey] = text } }
        Log.i(TAG, "lossless config applied: ${parsed.size} relay(s)")
        true
    }

    /** Load the cache, then refresh now and every [REFRESH_INTERVAL_MS]. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            loadCached()
            while (true) {
                refresh()
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    internal fun verify(bytes: ByteArray, sigB64: String): Boolean = runCatching {
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyB64)))
        Signature.getInstance("SHA256withECDSA").run { initVerify(pub); update(bytes); verify(Base64.getDecoder().decode(sigB64)) }
    }.getOrDefault(false)

    private fun parse(text: String): List<RelayEntry>? = runCatching {
        json.decodeFromString<LosslessConfig>(text).relays
            .mapNotNull { e -> LosslessSourcePreferences.normaliseEndpoint(e.base)?.let { RelayEntry(it, e.priority) } }
            .sortedBy { it.priority }
    }.getOrNull()

    private fun getBytes(url: String): ByteArray {
        val req = Request.Builder().url(url).header("Accept", "*/*").get().build()
        httpClient.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            return r.body?.bytes() ?: ByteArray(0)
        }
    }

    internal suspend fun clearForTest() {
        _relays.value = emptyList()
        context.losslessRelayConfigDataStore.edit { it.clear() }
    }

    private companion object {
        const val TAG = "LosslessConfig"
        const val REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}
```

`StashApplication.kt`: add `@Inject lateinit var losslessConfigFetcher: LosslessConfigFetcher` next to the other injected singletons (~L155-165) and, in `onCreate()` where the first `applicationScope.launch` block sits (~L247), add `losslessConfigFetcher.start(applicationScope)`.

- [ ] **Step 4: Run the test class and compile the app**

Run: `./gradlew :data:download:testDebugUnitTest --tests "*LosslessConfigFetcherTest" :app:compileDebugKotlin --max-workers=3 -Dorg.gradle.jvmargs=-Xmx4096m`
Expected: PASS (5 tests), BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/lossless/relay/LosslessConfigFetcher.kt data/download/src/test/kotlin/com/stash/data/download/lossless/relay/LosslessConfigFetcherTest.kt app/src/main/kotlin/com/stash/app/StashApplication.kt
git commit -m "feat(lossless): ECDSA-signed runtime relay config (no hostname in the APK)"
```

---

### Task 7: `QbdlxCredentialStore` — `hasLogin`, `loginLive()`, pasted-token migration

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxCredentialStore.kt:127-172,353-354`
- Test: `data/download/src/test/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxCredentialStoreTest.kt`

- [ ] **Step 1: Write the failing tests** (append to the existing Robolectric class)

```kotlin
    @Test fun `hasLogin reflects the connected account`() = runTest {
        val s = store("")
        assertThat(s.hasLogin.first()).isFalse()
        s.setUserCredential("tok", "798273057", "sec", email = "me@x")
        assertThat(s.hasLogin.first()).isTrue()
        assertThat(s.loginLive()).isTrue()
        s.markDead("tok")
        assertThat(s.loginLive()).isFalse()
        s.clearUserCredential()
        assertThat(s.hasLogin.first()).isFalse()
    }

    @Test fun `a pasted token is migrated into the login slot with the primary signing pair`() = runTest {
        val s0 = store("")
        s0.primaryAppId = "798273057"; s0.primaryAppSecret = "primary-secret"
        s0.setPastedToken("pasted-tok")
        val s = store("").also { it.primaryAppId = "798273057"; it.primaryAppSecret = "primary-secret" }
        val login = s.loginCredential()
        assertThat(login).isEqualTo(QbdlxLoginCredential("pasted-tok", "798273057", "primary-secret"))
        assertThat(s.connectedEmail()).isNull()
        assertThat(s.activeToken()).isEqualTo("pasted-tok")
        // the pasted key is gone: a fresh store with no login sees nothing
        s.clearUserCredential()
        assertThat(store("").loginCredential()).isNull()
    }
```
(`import kotlinx.coroutines.flow.first` at the top.)

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :data:download:testDebugUnitTest --tests "*QbdlxCredentialStoreTest" --max-workers=3 -Dorg.gradle.jvmargs=-Xmx4096m`
Expected: compile FAIL (`hasLogin`, `loginLive`).

- [ ] **Step 3: Implement**

In the "User-connected account" section:

```kotlin
    /** Live view of "a connected account exists" for the availability predicates. */
    val hasLogin: kotlinx.coroutines.flow.Flow<Boolean> =
        context.qbdlxCredentialsDataStore.data.map { p ->
            !p[loginTokenKey].isNullOrBlank() && !p[loginAppIdKey].isNullOrBlank() && !p[loginAppSecretKey].isNullOrBlank()
        }

    /** A connected account exists and is not inside a dead-cooldown. */
    suspend fun loginLive(): Boolean = loginCredential()?.let { !isDead(it.token) } ?: false

    suspend fun loginCredential(): QbdlxLoginCredential? {
        if (!loginLoaded) {
            val p = runCatching { context.qbdlxCredentialsDataStore.data.first() }.getOrNull()
            val t = p?.get(loginTokenKey); val a = p?.get(loginAppIdKey); val s = p?.get(loginAppSecretKey)
            cachedLogin = if (!t.isNullOrBlank() && !a.isNullOrBlank() && !s.isNullOrBlank())
                QbdlxLoginCredential(t, a, s) else null
            loginLoaded = true
            if (cachedLogin == null) migratePastedToken()
        }
        return cachedLogin
    }

    /**
     * One-shot upgrade path: a user who pasted a token before the pool left the
     * app keeps working. `pasted_token` is a lone string that always signed under
     * the primary BuildConfig pair (see [signingFor]'s fallback), so that pair is
     * what the migrated credential stores. Runs only when no login exists.
     */
    private suspend fun migratePastedToken() {
        val pasted = pastedToken() ?: return
        if (primaryAppId.isBlank() || primaryAppSecret.isBlank()) return
        Log.i(TAG, "migrating pasted token into the connected-account slot")
        setUserCredential(pasted, primaryAppId, primaryAppSecret, email = null)
        context.qbdlxCredentialsDataStore.edit { it.remove(pastedTokenKey) }
    }
```
(`import kotlinx.coroutines.flow.map`.) `pastedToken()` is defined further down; Kotlin allows forward references between members.

- [ ] **Step 4: Run the test class**

Run: same as Step 2. Expected: PASS (existing 14 + 2 new). `pasted token takes priority over pool` still passes unchanged: `activeToken()` calls `loginCredential()` first, the migration turns the pasted string into the login token, and that is exactly the value the assertion expects. The other pasted-token tests call `allDead()`/`activeToken()` before pasting, so `loginLoaded` is already true and migration never re-runs for them.

- [ ] **Step 5: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxCredentialStore.kt data/download/src/test/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxCredentialStoreTest.kt
git commit -m "feat(qbdlx): hasLogin/loginLive + migrate a pasted token into the connected-account slot"
```

---

### Task 8: `LosslessAvailability`

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/lossless/LosslessAvailability.kt`
- Test: `data/download/src/test/kotlin/com/stash/data/download/lossless/LosslessAvailabilityTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.stash.data.download.lossless

import com.google.common.truth.Truth.assertThat
import com.stash.data.download.lossless.arcod.ArcodCredentialStore
import com.stash.data.download.lossless.qbdlx.QbdlxCredentialStore
import com.stash.data.download.lossless.qbdlx.QbdlxLoginCredential
import com.stash.data.download.lossless.relay.LosslessConfigFetcher
import com.stash.data.download.lossless.relay.LosslessRelayClient
import com.stash.data.download.lossless.relay.RelayEntry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LosslessAvailabilityTest {
    private val login = MutableStateFlow(false)
    private val relays = MutableStateFlow<List<RelayEntry>>(emptyList())
    private val custom = MutableStateFlow<String?>(null)
    private val arcod = MutableStateFlow<String?>(null)
    private val store: QbdlxCredentialStore = mockk { every { hasLogin } returns login }
    private val config: LosslessConfigFetcher = mockk { every { this@mockk.relays } returns relays }
    private val prefs: LosslessSourcePreferences = mockk { every { customLosslessEndpoint } returns custom }
    private val relayClient: LosslessRelayClient = mockk()
    private val arcodStore: ArcodCredentialStore = mockk { every { accessToken } returns arcod }
    private val a = LosslessAvailability(store, config, prefs, relayClient, arcodStore)

    private fun stubNow(loginLive: Boolean = false, customNow: String? = null, cooled: Set<String> = emptySet()) {
        coEvery { store.loginLive() } returns loginLive
        coEvery { store.loginCredential() } returns if (loginLive) QbdlxLoginCredential("t", "a", "s") else null
        coEvery { prefs.customLosslessEndpointNow() } returns customNow
        every { relayClient.isCooled(any()) } answers { firstArg<String>() in cooled }
    }

    @Test fun `nothing configured - every predicate false`() = runTest {
        stubNow()
        assertThat(a.qbdlxEnabled.first()).isFalse()
        assertThat(a.fileUrlAvailableNow()).isFalse()
        assertThat(a.anyConfigured.first()).isFalse()
        assertThat(a.anyUserOwned.first()).isFalse()
    }

    @Test fun `relay configured - enabled and configured, but not user-owned`() = runTest {
        relays.value = listOf(RelayEntry("https://r.example", 1)); stubNow()
        assertThat(a.qbdlxEnabled.first()).isTrue()
        assertThat(a.fileUrlAvailableNow()).isTrue()
        assertThat(a.anyConfigured.first()).isTrue()
        assertThat(a.anyUserOwned.first()).isFalse()
    }

    @Test fun `a cooled relay is still configured but not available now`() = runTest {
        relays.value = listOf(RelayEntry("https://r.example", 1)); stubNow(cooled = setOf("https://r.example"))
        assertThat(a.qbdlxEnabled.first()).isTrue()
        assertThat(a.fileUrlAvailableNow()).isFalse()
    }

    @Test fun `BYO login is user-owned and available`() = runTest {
        login.value = true; stubNow(loginLive = true)
        assertThat(a.fileUrlAvailableNow()).isTrue()
        assertThat(a.anyUserOwned.first()).isTrue()
    }

    @Test fun `custom endpoint is user-owned; cooled custom is not available`() = runTest {
        custom.value = "https://mine.example"; stubNow(customNow = "https://mine.example", cooled = setOf("https://mine.example"))
        assertThat(a.anyUserOwned.first()).isTrue()
        assertThat(a.qbdlxEnabled.first()).isTrue()
        assertThat(a.fileUrlAvailableNow()).isFalse()
    }

    @Test fun `ARCOD alone counts as configured and user-owned but not qbdlx`() = runTest {
        arcod.value = "arcod-token"; stubNow()
        assertThat(a.qbdlxEnabled.first()).isFalse()
        assertThat(a.anyConfigured.first()).isTrue()
        assertThat(a.anyUserOwned.first()).isTrue()
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :data:download:testDebugUnitTest --tests "*LosslessAvailabilityTest" --max-workers=3 -Dorg.gradle.jvmargs=-Xmx4096m`
Expected: compile FAIL.

- [ ] **Step 3: Implement**

```kotlin
package com.stash.data.download.lossless

import com.stash.data.download.lossless.arcod.ArcodCredentialStore
import com.stash.data.download.lossless.qbdlx.QbdlxCredentialStore
import com.stash.data.download.lossless.relay.LosslessConfigFetcher
import com.stash.data.download.lossless.relay.LosslessRelayClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * The ONE place that answers "is lossless available, and whose is it?" — four
 * questions, each as a Flow (for Home's combine) and a suspend getter (for the
 * download pipeline). Defined once so the source, the download deferral reason
 * and the Home banner can never disagree.
 *
 *  - [qbdlxEnabled]       BYO login || relay configured || custom endpoint set
 *  - [fileUrlAvailableNow] the same, minus anything currently cooled (per-call)
 *  - [anyConfigured]      qbdlxEnabled || ARCOD connected  → download deferral reason
 *  - [anyUserOwned]       BYO login || custom endpoint || ARCOD → the Home banner
 *                         (a dead PUBLIC relay must not hide the "connect your
 *                         own account" banner — that is the outage it exists for)
 */
@Singleton
class LosslessAvailability @Inject constructor(
    private val credentialStore: QbdlxCredentialStore,
    private val config: LosslessConfigFetcher,
    private val prefs: LosslessSourcePreferences,
    private val relayClient: LosslessRelayClient,
    private val arcod: ArcodCredentialStore,
) {
    val qbdlxEnabled: Flow<Boolean> =
        combine(credentialStore.hasLogin, config.relays, prefs.customLosslessEndpoint) { login, relays, custom ->
            login || relays.isNotEmpty() || custom != null
        }

    suspend fun qbdlxEnabledNow(): Boolean = qbdlxEnabled.first()

    /** Point-in-time: is there a file-URL path that is not cooled right now? */
    suspend fun fileUrlAvailableNow(): Boolean {
        if (credentialStore.loginLive()) return true
        prefs.customLosslessEndpointNow()?.let { if (!relayClient.isCooled(it)) return true }
        return config.relays.value.any { !relayClient.isCooled(it.base) }
    }

    val anyConfigured: Flow<Boolean> = combine(qbdlxEnabled, arcod.accessToken) { q, a -> q || a != null }
    suspend fun anyConfiguredNow(): Boolean = anyConfigured.first()

    val anyUserOwned: Flow<Boolean> =
        combine(credentialStore.hasLogin, prefs.customLosslessEndpoint, arcod.accessToken) { l, c, a ->
            l || c != null || a != null
        }
    suspend fun anyUserOwnedNow(): Boolean = anyUserOwned.first()
}
```

- [ ] **Step 4: Run the test class** — Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/lossless/LosslessAvailability.kt data/download/src/test/kotlin/com/stash/data/download/lossless/LosslessAvailabilityTest.kt
git commit -m "feat(lossless): LosslessAvailability — one predicate class for source, downloads and Home"
```

---

### Task 9: `QbdlxFileUrlRouter` — BYO → custom → relays

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxFileUrlRouter.kt`
- Test: `data/download/src/test/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxFileUrlRouterTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.stash.data.download.lossless.qbdlx

import com.google.common.truth.Truth.assertThat
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.relay.LosslessConfigFetcher
import com.stash.data.download.lossless.relay.LosslessRelayClient
import com.stash.data.download.lossless.relay.RelayEntry
import com.stash.data.download.lossless.relay.RelayMint
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class QbdlxFileUrlRouterTest {
    private val api: QbdlxApiClient = mockk()
    private val store: QbdlxCredentialStore = mockk(relaxUnitFun = true)
    private val relay: LosslessRelayClient = mockk()
    private val relays = MutableStateFlow<List<RelayEntry>>(emptyList())
    private val config: LosslessConfigFetcher = mockk { every { this@mockk.relays } returns relays }
    private val prefs: LosslessSourcePreferences = mockk()
    private val router = QbdlxFileUrlRouter(api, store, relay, config, prefs)
    private val login = QbdlxLoginCredential("byo", "798273057", "sec")

    private fun noLogin() { coEvery { store.loginCredential() } returns null; coEvery { store.loginLive() } returns false }

    @Test fun `BYO signs locally and never touches a relay`() = runTest {
        coEvery { store.loginCredential() } returns login; coEvery { store.loginLive() } returns true
        coEvery { prefs.customLosslessEndpointNow() } returns "https://mine.example"
        coEvery { api.getFileUrl(42, 27, "byo") } returns QbdlxResolveResult.Ok("https://cdn/f?etsp=1", "flac", 16, 44_100)
        val r = router.getFileUrl(42, 27)
        assertThat(r).isInstanceOf(QbdlxResolveResult.Ok::class.java)
        coVerify(exactly = 0) { relay.mint(any(), any(), any()) }
        coVerify { store.recordAlive("byo") }
    }

    @Test fun `BYO TokenDead marks the login dead and does NOT fall through to the relay`() = runTest {
        coEvery { store.loginCredential() } returns login; coEvery { store.loginLive() } returns true
        coEvery { api.getFileUrl(42, 27, "byo") } returns QbdlxResolveResult.TokenDead
        assertThat(router.getFileUrl(42, 27)).isEqualTo(QbdlxResolveResult.TokenDead)
        coVerify { store.markDead("byo") }
        coVerify(exactly = 0) { relay.mint(any(), any(), any()) }
    }

    @Test fun `BYO 401 exception is treated as TokenDead`() = runTest {
        coEvery { store.loginCredential() } returns login; coEvery { store.loginLive() } returns true
        coEvery { api.getFileUrl(42, 27, "byo") } throws QbdlxAuthException(401)
        assertThat(router.getFileUrl(42, 27)).isEqualTo(QbdlxResolveResult.TokenDead)
        coVerify { store.markDead("byo") }
    }

    @Test fun `custom endpoint outranks config relays`() = runTest {
        noLogin()
        coEvery { prefs.customLosslessEndpointNow() } returns "https://mine.example"
        relays.value = listOf(RelayEntry("https://public.example", 1))
        every { relay.isCooled(any()) } returns false
        coEvery { relay.mint("https://mine.example", 42, 27) } returns RelayMint.Ok("https://cdn/f?etsp=1", 27, 24, 96_000)
        val r = router.getFileUrl(42, 27) as QbdlxResolveResult.Ok
        assertThat(r.sampleRateHz).isEqualTo(96_000)   // Hz passed through, not multiplied
        coVerify(exactly = 0) { relay.mint("https://public.example", any(), any()) }
    }

    @Test fun `unavailable base falls through to the next; NoMatch is RegionLocked; nothing left is null`() = runTest {
        noLogin(); coEvery { prefs.customLosslessEndpointNow() } returns null
        relays.value = listOf(RelayEntry("https://a.example", 1), RelayEntry("https://b.example", 2))
        every { relay.isCooled(any()) } returns false
        coEvery { relay.mint("https://a.example", 42, 27) } returns RelayMint.Unavailable
        coEvery { relay.mint("https://b.example", 42, 27) } returns RelayMint.NoMatch
        assertThat(router.getFileUrl(42, 27)).isEqualTo(QbdlxResolveResult.RegionLocked)

        every { relay.isCooled(any()) } returns true
        assertThat(router.getFileUrl(43, 27)).isNull()
        coVerify(exactly = 0) { relay.mint(any(), 43, any()) }
    }
}
```

- [ ] **Step 2: Run to verify they fail** — compile FAIL.

- [ ] **Step 3: Implement**

```kotlin
package com.stash.data.download.lossless.qbdlx

import android.util.Log
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.relay.LosslessConfigFetcher
import com.stash.data.download.lossless.relay.LosslessRelayClient
import com.stash.data.download.lossless.relay.RelayMint
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The ONE seam that turns a Qobuz track id into a signed FLAC URL. Tries, in
 * order: the user's own connected account (signed locally — never spends relay
 * budget), the user's custom endpoint, then the relays from runtime config by
 * priority. Returns null when no path is available right now (the source then
 * falls to the next rung).
 *
 * BYO outcomes do NOT fall through: a dead login is the user's account problem,
 * surfaced as [QbdlxResolveResult.TokenDead], not silently papered over by the
 * public relay.
 */
@Singleton
class QbdlxFileUrlRouter @Inject constructor(
    private val apiClient: QbdlxApiClient,
    private val credentialStore: QbdlxCredentialStore,
    private val relayClient: LosslessRelayClient,
    private val config: LosslessConfigFetcher,
    private val prefs: LosslessSourcePreferences,
) {
    suspend fun getFileUrl(trackId: Long, formatId: Int): QbdlxResolveResult? {
        val login = credentialStore.loginCredential()
        if (login != null && credentialStore.loginLive()) {
            val result = try {
                apiClient.getFileUrl(trackId, formatId, login.token)
            } catch (e: QbdlxAuthException) {
                QbdlxResolveResult.TokenDead
            }
            if (result is QbdlxResolveResult.TokenDead) {
                Log.w(TAG, "connected account rejected — marking dead")
                credentialStore.markDead(login.token)
            } else {
                credentialStore.recordAlive(login.token)
            }
            return result
        }

        val bases = buildList {
            prefs.customLosslessEndpointNow()?.let { add(it) }
            config.relays.value.forEach { add(it.base) }
        }
        for (base in bases) {
            if (relayClient.isCooled(base)) continue
            when (val m = relayClient.mint(base, trackId, formatId)) {
                is RelayMint.Ok -> return QbdlxResolveResult.Ok(m.url, "flac", m.bitDepth, m.sampleRateHz)
                RelayMint.NoMatch -> return QbdlxResolveResult.RegionLocked
                RelayMint.Unavailable -> Unit // cooled by the client; try the next base
            }
        }
        return null
    }

    private companion object { const val TAG = "QbdlxFileUrlRouter" }
}
```

- [ ] **Step 4: Run the test class** — Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxFileUrlRouter.kt data/download/src/test/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxFileUrlRouterTest.kt
git commit -m "feat(qbdlx): file-URL router — BYO local signing, then custom endpoint, then config relays"
```

---

### Task 10: `QbdlxQobuzSource` — tokenless resolve path

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxQobuzSource.kt` (full rewrite of the internals)
- Modify: `core/media/src/main/kotlin/com/stash/core/media/streaming/QbdlxStreamResolver.kt:43` (log text only)
- Test: `data/download/src/test/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxQobuzSourceTest.kt` (rewrite), `QbdlxBypassRateLimitTest.kt` (adjust constructor/stubs)

- [ ] **Step 1: Rewrite the test class**

```kotlin
package com.stash.data.download.lossless.qbdlx

import com.google.common.truth.Truth.assertThat
import com.stash.data.download.lossless.AggregatorRateLimiter
import com.stash.data.download.lossless.LosslessAvailability
import com.stash.data.download.lossless.LosslessQualityTier
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.RateLimitState
import com.stash.data.download.lossless.TrackQuery
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * [QbdlxQobuzSource] after the pool: catalog search is tokenless and the file URL
 * comes from [QbdlxFileUrlRouter]. The real QobuzCandidateMatcher scores real
 * [QbdlxTrack]s so matching runs end-to-end.
 */
class QbdlxQobuzSourceTest {
    private val apiClient: QbdlxApiClient = mockk()
    private val router: QbdlxFileUrlRouter = mockk()
    private val availability: LosslessAvailability = mockk()
    private val rateLimiter: AggregatorRateLimiter = mockk(relaxUnitFun = true)
    private val prefs: LosslessSourcePreferences = mockk()
    private fun source() = QbdlxQobuzSource(apiClient, router, availability, rateLimiter, prefs)
    private val sid = QbdlxQobuzSource.SOURCE_ID
    private val notBroken = RateLimitState(2.0, 0L, isCircuitBroken = false, msUntilUnblock = 0L, recentFailures = 0)
    private val query = TrackQuery(artist = "John Frusciante", title = "Murderers", isrc = "USWB10003085", durationMs = 160_000)
    private fun candidate(id: Long = 42) = QbdlxTrack(
        id = id, title = "Murderers", isrc = "USWB10003085", duration = 160, streamable = true,
        performer = QbdlxPerformer("John Frusciante"), maximumBitDepth = 16, maximumSamplingRate = 44.1f,
        album = QbdlxAlbum(QbdlxImage(large = "https://art/large.jpg")),
    )
    private fun ok(url: String = "https://cdn/file?fmt=27") = QbdlxResolveResult.Ok(url, "flac", 24, 96_000)

    private fun enabledAndAcquired() {
        coEvery { availability.qbdlxEnabledNow() } returns true
        coEvery { availability.fileUrlAvailableNow() } returns true
        coEvery { prefs.qualityTierNow() } returns LosslessQualityTier.MAX
        coEvery { rateLimiter.stateOf(sid) } returns notBroken
        coEvery { rateLimiter.acquire(sid) } returns true
    }

    @Test fun `match yields SourceResult with the response format`() = runTest {
        enabledAndAcquired()
        coEvery { apiClient.search(any()) } returns listOf(candidate())
        coEvery { router.getFileUrl(42, 27) } returns ok()
        val r = source().resolve(query)!!
        assertThat(r.sourceId).isEqualTo("qbdlx_qobuz")
        assertThat(r.downloadUrl).isEqualTo("https://cdn/file?fmt=27")
        assertThat(r.confidence).isEqualTo(0.95f)
        assertThat(r.format.bitsPerSample).isEqualTo(24)
        assertThat(r.format.sampleRateHz).isEqualTo(96_000)
        assertThat(r.coverArtUrl).isEqualTo("https://art/large.jpg")
        coVerify { rateLimiter.reportSuccess(sid) }
    }

    @Test fun `no file-url path available - returns null with ZERO catalog calls`() = runTest {
        enabledAndAcquired()
        coEvery { availability.fileUrlAvailableNow() } returns false
        assertThat(source().resolve(query)).isNull()
        coVerify(exactly = 0) { apiClient.search(any()) }
        coVerify(exactly = 0) { router.getFileUrl(any(), any()) }
    }

    @Test fun `disabled when availability says no`() = runTest {
        coEvery { availability.qbdlxEnabledNow() } returns false
        coEvery { rateLimiter.stateOf(sid) } returns notBroken
        assertThat(source().isEnabled()).isFalse()
        assertThat(source().isEnabledForStreaming()).isFalse()
    }

    @Test fun `TokenDead and RegionLocked both yield null (next rung)`() = runTest {
        enabledAndAcquired()
        coEvery { apiClient.search(any()) } returns listOf(candidate())
        coEvery { router.getFileUrl(42, 27) } returns QbdlxResolveResult.TokenDead
        assertThat(source().resolve(query)).isNull()
        coEvery { router.getFileUrl(42, 27) } returns QbdlxResolveResult.RegionLocked
        assertThat(source().resolve(query)).isNull()
    }

    @Test fun `router null (all bases cooled mid-resolve) yields null without a breaker failure`() = runTest {
        enabledAndAcquired()
        coEvery { apiClient.search(any()) } returns listOf(candidate())
        coEvery { router.getFileUrl(42, 27) } returns null
        assertThat(source().resolve(query)).isNull()
        coVerify(exactly = 0) { rateLimiter.reportFailure(sid) }
    }

    @Test fun `catalog 401 after self-heal is a plain miss, not a breaker failure`() = runTest {
        enabledAndAcquired()
        coEvery { apiClient.search(any()) } throws QbdlxAuthException(401)
        assertThat(source().resolve(query)).isNull()
        coVerify(exactly = 0) { rateLimiter.reportFailure(sid) }
    }

    @Test fun `streaming tier is honoured on resolveImmediate`() = runTest {
        enabledAndAcquired()
        coEvery { apiClient.search(any()) } returns listOf(candidate())
        coEvery { router.getFileUrl(42, 6) } returns ok()
        assertThat(source().resolveImmediate(query, requestedQuality = 6)).isNotNull()
        coVerify(exactly = 0) { rateLimiter.acquire(sid) }
    }

    @Test fun `no candidate over threshold - null, search reported success`() = runTest {
        enabledAndAcquired()
        coEvery { apiClient.search(any()) } returns listOf(candidate().copy(title = "Completely Different", isrc = null))
        assertThat(source().resolve(query)).isNull()
        coVerify(exactly = 0) { router.getFileUrl(any(), any()) }
    }
}
```
`QbdlxBypassRateLimitTest.kt`: update its constructor call and replace `credentialStore`/`prefs.qbdlxEnabledNow()` stubs with `availability.qbdlxEnabledNow()`/`fileUrlAvailableNow()` and `router.getFileUrl(...)` (read the file; the intent of each test stays).

- [ ] **Step 2: Run to verify they fail** — compile FAIL (constructor).

- [ ] **Step 3: Implement — replace the class body**

```kotlin
@Singleton
class QbdlxQobuzSource @Inject constructor(
    private val apiClient: QbdlxApiClient,
    private val router: QbdlxFileUrlRouter,
    private val availability: LosslessAvailability,
    private val rateLimiter: AggregatorRateLimiter,
    private val losslessPrefs: LosslessSourcePreferences,
) : LosslessSource {

    override val id: String = SOURCE_ID
    override val displayName: String = "Direct Qobuz"

    override suspend fun isEnabled(): Boolean =
        !rateLimiter.stateOf(id).isCircuitBroken && availability.qbdlxEnabledNow()

    /** Streaming gate: same predicate without the breaker (a user tap bypasses it). */
    suspend fun isEnabledForStreaming(): Boolean = availability.qbdlxEnabledNow()

    override suspend fun resolve(query: TrackQuery, bypassRateLimit: Boolean): SourceResult? {
        if (!isEnabled()) return null
        return resolveInternal(query, bypassRateLimit = bypassRateLimit, requestedQuality = null)
    }

    suspend fun resolveImmediate(query: TrackQuery, requestedQuality: Int? = null): SourceResult? {
        if (!isEnabledForStreaming()) return null
        return resolveInternal(query, bypassRateLimit = true, requestedQuality = requestedQuality)
    }

    override suspend fun rateLimitState(): RateLimitState = rateLimiter.stateOf(id)

    private suspend fun resolveInternal(query: TrackQuery, bypassRateLimit: Boolean, requestedQuality: Int?): SourceResult? {
        // Availability FIRST: a cooled relay / dead login must cost zero catalog HTTP.
        if (!availability.fileUrlAvailableNow()) {
            Log.d(TAG, "no file-url path available right now — skipping '${query.title}'")
            return null
        }
        val (track, conf) = search(query, bypassRateLimit) ?: return null
        val formatId = requestedQuality ?: losslessPrefs.qualityTierNow().qobuzCode
        val result = callLimited(bypassRateLimit) { router.getFileUrl(track.id, formatId) } ?: return null
        return when (result) {
            is QbdlxResolveResult.Ok -> build(track, conf, result)
            QbdlxResolveResult.TokenDead -> { Log.w(TAG, "connected account dead for '${query.title}'"); null }
            QbdlxResolveResult.RegionLocked -> { Log.d(TAG, "not streamable/region-locked: '${query.title}'"); null }
        }
    }

    /** Tokenless catalog search + match. Null when nothing crosses threshold or the catalog rejects us. */
    private suspend fun search(query: TrackQuery, bypassRateLimit: Boolean): Pair<QbdlxTrack, Float>? {
        try {
            for (term in query.searchTerms()) {
                val candidates = callLimited(bypassRateLimit) { apiClient.search(term) } ?: continue
                val match = candidates
                    .map { it to confidence(query, it) }
                    .filter { it.second >= QobuzCandidateMatcher.MIN_CONFIDENCE }
                    .maxByOrNull { it.second }
                if (match != null) return match
            }
            return null
        } catch (e: QbdlxAuthException) {
            // Catalog 401 after the client's own self-heal: nothing to rotate to.
            Log.w(TAG, "catalog auth-failed (${e.status}) even under the web app_id")
            return null
        }
    }
```
Keep `build()`, `confidence()`, `callLimited()` and the companion exactly as they are (the `Outcome` sealed interface, `resolveFile`, `resolveRegion`, `resolveOnce`, `MAX_TOKEN_ATTEMPTS` are deleted). `callLimited`'s contract is unchanged: a `QbdlxAuthException` from `router.getFileUrl` cannot occur (the router converts it), so the rethrow branch is now only reachable from `search`. Rewrite the class KDoc: no pool, no rotation; "tokenless catalog under the web app_id; file URL via [QbdlxFileUrlRouter]; [LosslessAvailability] gates".

`QbdlxStreamResolver.kt:43`: change the log to `"disabled id=${track.id} (no lossless path configured)"`; update its KDoc line about "every pooled token is dead".

- [ ] **Step 4: Run the whole `data/download` and `core/media` test suites**

Run: `./gradlew :data:download:testDebugUnitTest :core:media:testDebugUnitTest --max-workers=3 -Dorg.gradle.jvmargs=-Xmx4096m`
Expected: BUILD SUCCESSFUL. Any remaining compile error is a caller of a signature this plan changed — fix the call site to the new shape (do not add shims).

- [ ] **Step 5: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxQobuzSource.kt core/media/src/main/kotlin/com/stash/core/media/streaming/QbdlxStreamResolver.kt data/download/src/test/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxQobuzSourceTest.kt data/download/src/test/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxBypassRateLimitTest.kt
git commit -m "refactor(qbdlx): tokenless resolve path — availability check, tokenless search, one file-url call via the router"
```

---

### Task 11: Whole-repo gate, install, device verification

**Files:** none new.

- [ ] **Step 1: The release gate**

Run: `./gradlew compileDebugUnitTestKotlin --max-workers=3 -Dorg.gradle.jvmargs=-Xmx4096m`
Expected: BUILD SUCCESSFUL (this is the exact gate CI runs; `assembleDebug` alone is not enough — see memory `project_flac_live_probe_2026_08`).

- [ ] **Step 2: Full unit-test run**

Run: `./gradlew testDebugUnitTest --max-workers=3 -Dorg.gradle.jvmargs=-Xmx4096m`
Expected: green except the two known-red `HomeViewModelTest` playHero matcher tests. Anything else red is this plan's.

- [ ] **Step 3: Grep for leftovers**

Run: `git grep -n -E "QBDLX_CONFIGURED|qbdlxEnabledNow|NO_TOKEN|activeToken\(\)" -- "*.kt"`
Expected:
- `QBDLX_CONFIGURED` — only the `DownloadManager.kt:231` comment (Plan A2 rewrites that block).
- `qbdlxEnabledNow` — four files: `LosslessAvailability.kt` (its own method), `QbdlxQobuzSource.kt` (calling `availability.qbdlxEnabledNow()`), `QbdlxQobuzSourceTest.kt` and `QbdlxBypassRateLimitTest.kt` (stubbing it). No `LosslessSourcePreferences` hit.
- `NO_TOKEN` — no matches.
- `activeToken()` — only `QbdlxCredentialStore.kt` itself and the pool-era tests `QbdlxCredentialStoreTest`, `QbdlxPoolRefreshTest`, `QbdlxSigningTest` (all deleted with the pool in Plan C). No production caller outside the store.

- [ ] **Step 4: Install on the Pixel 5 rig and verify BYO still serves**

`./gradlew :app:installDebug` then `adb -s <redfin-serial> ...`. With a Qobuz account connected in Settings › Audio: tap a track → `adb logcat -d | grep -E "QbdlxStreamResolver|StreamSourceRegistry"` shows `resolved id=… origin=qbdlx` and `qbdlx served`; Now Playing shows the `FLAC` badge. With no account and an empty config: the same tap logs `no file-url path available` **before** any `catalog/search` call (check `QbdlxApiClient` has no `search` log line for that track) and the chain proceeds to JioSaavn/YouTube. Home discovery rows and a Qobuz album page still load with no account (tokenless catalog).

- [ ] **Step 5: Commit any device-found fix, then hand off**

If Step 4 found nothing to fix, there is nothing to commit. Report the logcat lines in the completion message; Plan A2 (download deferral reason) and Plan B (the relay server) follow.
