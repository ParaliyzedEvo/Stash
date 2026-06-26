# ARCOD Streaming Single-GET Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `ArcodStreamResolver`'s slow create-job→poll→download-URL streaming flow with the arcod dev's new single stream-URL GET, so a tapped/next-up ARCOD stream resolves in one fast request instead of a multi-second render+poll.

**Architecture:** Add one method to the existing `ArcodClient` (`streamUrl(trackId, quality)`) that hits `GET <ARCOD_STREAM_BASE>/{trackId}?quality={code}` and tolerantly parses the URL (plain-text body, flat JSON, or arcod's `{success,data}` envelope, with optional `expiresIn`). Rewire `ArcodStreamResolver` to keep its search→match→trackId head but call the new method for the tail, threading the user's per-network quality tier and dropping the now-unneeded job gate. Downloads (`ArcodSource`) are untouched. ARCOD stays last + foreground-only in `StreamSourceRegistry` — no registry/DI/media-factory changes beyond the resolver's own constructor.

**Tech Stack:** Kotlin, OkHttp (derived host-scoped client), kotlinx.serialization (lenient JSON), Hilt, JUnit4 + MockWebServer + mockk + Truth, coroutines.

**Spec:** `docs/superpowers/specs/2026-06-24-arcod-streaming-single-get-design.md`

**Branch:** create `feat/arcod-streaming-single-get` off `master` before Task 1.

---

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `data/download/.../lossless/arcod/ArcodApiModels.kt` | Modify | Add the plain `ArcodStreamResult(url, expiresInSec)` parse-output type next to the existing models. |
| `data/download/.../lossless/arcod/ArcodClient.kt` | Modify | Add `streamUrl(trackId, quality)` (new `streamBaseUrl` host seam) + a private tolerant `parseStreamResult(body)`. Leave `search`/`createJob`/`pollStatus`/`downloadUrlFrom` untouched (downloads keep them). |
| `data/download/.../lossless/arcod/ArcodClientTest.kt` | Modify | Add MockWebServer tests for `streamUrl` across all three body shapes + 429/non-2xx/unparseable. |
| `core/media/.../streaming/ArcodStreamResolver.kt` | Modify | Swap the job-flow tail for `client.streamUrl(...)`; inject `StreamQualityPolicy`, drop `ArcodJobGate`, drop the album-id gate, derive TTL from `expiresInSec`. |
| `core/media/.../streaming/ArcodStreamResolverTest.kt` | Modify | Rewrite to mock `client.streamUrl` + `qualityPolicy.streamingTier()`; verify code threading, no-album resolve, null cases, TTL. |

Absolute paths:
- `C:\Users\theno\Projects\MP3APK\data\download\src\main\kotlin\com\stash\data\download\lossless\arcod\ArcodApiModels.kt`
- `C:\Users\theno\Projects\MP3APK\data\download\src\main\kotlin\com\stash\data\download\lossless\arcod\ArcodClient.kt`
- `C:\Users\theno\Projects\MP3APK\data\download\src\test\kotlin\com\stash\data\download\lossless\arcod\ArcodClientTest.kt`
- `C:\Users\theno\Projects\MP3APK\core\media\src\main\kotlin\com\stash\core\media\streaming\ArcodStreamResolver.kt`
- `C:\Users\theno\Projects\MP3APK\core\media\src\test\kotlin\com\stash\core\media\streaming\ArcodStreamResolverTest.kt`

## Notes for the implementer (read before starting)

- **Windows / Gradle:** run the **specific test class** with a `--tests` filter, never the whole module — `:core:media:testDebugUnitTest` has a flaky network test that hangs forever (it's not our test). For `:data:download`, add `--no-daemon` to avoid a lingering test-worker JVM locking `build/test-results/...` between runs (known Windows gotcha).
- **`ArcodMatcher` is a real Kotlin `object`, not mocked** in the resolver test — the `matchingItem()` fixture is crafted to score above the matcher's 0.5 threshold (ISRC equal + duration within 5s). Keep it that way.
- **Hilt:** `StreamQualityPolicy` is already `@Singleton @Inject` and injected by `AmzStreamResolver`, so adding it to `ArcodStreamResolver` needs no module change. `ArcodJobGate` stays in the graph (used by `ArcodSource`). Per the recurring lesson, a green `:x:compileDebugKotlin` does NOT prove the Hilt graph — gate the constructor change on `:app:assembleDebug` (Task 3).
- The dev's example URL wrote the path placeholder as `%7BtrackId%7D`; the real call is `<ARCOD_STREAM_BASE>/<numeric id>` (the `ArcodTrackItem.id` Long, no encoding needed).

---

## Task 1: `ArcodClient.streamUrl()` + tolerant parser

**Files:**
- Modify: `data\download\src\main\kotlin\com\stash\data\download\lossless\arcod\ArcodApiModels.kt`
- Modify: `data\download\src\main\kotlin\com\stash\data\download\lossless\arcod\ArcodClient.kt`
- Test: `data\download\src\test\kotlin\com\stash\data\download\lossless\arcod\ArcodClientTest.kt`

- [ ] **Step 1: Add the parse-output model**

In `ArcodApiModels.kt`, after `ArcodUrlResponse`, add a plain (non-`@Serializable`) data class — it's the manual parse output of `streamUrl`, not a wire DTO:

```kotlin
/**
 * Parsed result of the single stream-URL GET (`<ARCOD_STREAM_BASE>/<id>`).
 * [url] is the playable, open, Range-capable link; [expiresInSec] is the
 * server-stated lifetime in seconds when the response carried one (used to size
 * the resolver's cache TTL), else null.
 */
data class ArcodStreamResult(
    val url: String,
    val expiresInSec: Int? = null,
)
```

- [ ] **Step 2: Write the failing client tests**

In `ArcodClientTest.kt`, first extend `setUp()` to also point the new host seam at the mock server (add the line right after the existing `baseUrl = …` assignment, inside the same `.apply { }`):

```kotlin
            baseUrl = server.url("/api").toString().trimEnd('/')
            streamBaseUrl = server.url("").toString().trimEnd('/')
```

Then add these tests:

```kotlin
    @Test fun `streamUrl hits v2 stream path with quality and parses plain-text url`() = runTest {
        val url = "https://dl.arcod.xyz/stream/abc.flac?token=xyz"
        server.enqueue(MockResponse().setResponseCode(200).setBody(url))

        val result = client.streamUrl(8767428L, 27)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("<ARCOD_STREAM_BASE>/8767428?quality=27", request.path)
        assertNotNull(result)
        assertEquals(url, result!!.url)
        assertNull(result.expiresInSec)
    }

    @Test fun `streamUrl parses flat json url`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"url":"https://dl.arcod.xyz/s/x.flac"}"""),
        )

        val result = client.streamUrl(1L, 6)

        assertEquals("https://dl.arcod.xyz/s/x.flac", result!!.url)
        assertNull(result.expiresInSec)
    }

    @Test fun `streamUrl parses enveloped json with expiresIn`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"success":true,"data":{"url":"https://dl.arcod.xyz/s/y.flac","expiresIn":120}}"""),
        )

        val result = client.streamUrl(1L, 7)

        assertEquals("https://dl.arcod.xyz/s/y.flac", result!!.url)
        assertEquals(120, result.expiresInSec)
    }

    @Test fun `streamUrl throws on 429`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("rate limited"))
        try {
            client.streamUrl(1L, 27)
            fail("expected ArcodRateLimitedException")
        } catch (_: ArcodRateLimitedException) {
            // expected
        }
    }

    @Test fun `streamUrl returns null on non-2xx`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        assertNull(client.streamUrl(1L, 27))
    }

    @Test fun `streamUrl returns null on unparseable body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not a url or json {"))
        assertNull(client.streamUrl(1L, 27))
    }
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :data:download:testDebugUnitTest --tests "com.stash.data.download.lossless.arcod.ArcodClientTest" --no-daemon`
Expected: FAIL — `streamUrl` / `streamBaseUrl` / `ArcodStreamResult` unresolved (compile error).

- [ ] **Step 4: Implement `streamUrl` + `parseStreamResult` + the host seam**

In `ArcodClient.kt`:

a) Add the new imports near the existing kotlinx.serialization import:

```kotlin
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
```

b) Add the host seam next to the existing `baseUrl`:

```kotlin
    /** Test seam: the single stream-URL GET lives on a DIFFERENT host
     *  (`api.arcod.xyz`, no `/api` segment) than [baseUrl]'s `arcod.xyz/api`. */
    internal var streamBaseUrl = "https://api.arcod.xyz"
```

c) Add the method (place it after `downloadUrlFrom`):

```kotlin
    /**
     * Resolve a single playable stream URL for a Qobuz [trackId] at the given
     * Qobuz [quality] format_id (6=CD, 7=hi-res, 27=max). One GET — no job
     * render/poll. 429 throws [ArcodRateLimitedException]; any other failure
     * (non-2xx or unparseable body) returns null so the caller fails over.
     */
    suspend fun streamUrl(trackId: Long, quality: Int): ArcodStreamResult? =
        withContext(Dispatchers.IO) {
            val request = arcodRequest("$streamBaseUrl<ARCOD_STREAM_BASE>/$trackId?quality=$quality")
                .get().build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    if (response.code == 429) throw ArcodRateLimitedException()
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body?.string().orEmpty()
                    parseStreamResult(body) ?: run {
                        // First-run shape probe: the dev gave no sample body, so
                        // log the raw payload once when nothing parsed.
                        Log.d(TAG, "stream parse miss trackId=$trackId body='${body.take(200)}'")
                        null
                    }
                }
            } catch (e: ArcodRateLimitedException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

    /**
     * Tolerant parse of the stream GET body. Accepts (a) a bare URL as the whole
     * body, or (b) JSON with the URL at the top level OR one level under `data`
     * (arcod's usual `{success,data:{…}}` envelope), in any of the `url` /
     * `streamUrl` / `downloadUrl` keys, plus an optional `expiresIn`.
     */
    private fun parseStreamResult(body: String): ArcodStreamResult? {
        val trimmed = body.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return ArcodStreamResult(url = trimmed)
        }
        return try {
            val root = ArcodJson.parseToJsonElement(trimmed).jsonObject
            val obj = (root["data"] as? JsonObject) ?: root
            val url = (obj["url"] ?: obj["streamUrl"] ?: obj["downloadUrl"])
                ?.jsonPrimitive?.contentOrNull ?: return null
            val expiresIn = (obj["expiresIn"] ?: root["expiresIn"])
                ?.jsonPrimitive?.intOrNull
            ArcodStreamResult(url = url, expiresInSec = expiresIn)
        } catch (e: Exception) {
            null
        }
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :data:download:testDebugUnitTest --tests "com.stash.data.download.lossless.arcod.ArcodClientTest" --no-daemon`
Expected: PASS (all existing + 6 new `streamUrl` tests).

- [ ] **Step 6: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/lossless/arcod/ArcodApiModels.kt \
        data/download/src/main/kotlin/com/stash/data/download/lossless/arcod/ArcodClient.kt \
        data/download/src/test/kotlin/com/stash/data/download/lossless/arcod/ArcodClientTest.kt
git commit -m "feat(arcod): add single-GET streamUrl to ArcodClient

GET <ARCOD_STREAM_BASE>/{trackId}?quality= with a tolerant parser
(plain-text URL, flat JSON, or {success,data} envelope + optional
expiresIn). Downloads' job flow is untouched.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: Swap `ArcodStreamResolver` to the single GET

**Files:**
- Modify: `core\media\src\main\kotlin\com\stash\core\media\streaming\ArcodStreamResolver.kt`
- Test: `core\media\src\test\kotlin\com\stash\core\media\streaming\ArcodStreamResolverTest.kt`

- [ ] **Step 1: Rewrite the resolver test (failing)**

Replace the whole body of `ArcodStreamResolverTest.kt` with the new flow. Key changes: drop the `ArcodJob`/`ArcodJobGate` imports, construct with a mocked `StreamQualityPolicy`, mock `client.streamUrl(...)`, and assert the quality code is threaded through.

```kotlin
package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.download.lossless.LosslessQualityTier
import com.stash.data.download.lossless.arcod.ArcodAlbum
import com.stash.data.download.lossless.arcod.ArcodClient
import com.stash.data.download.lossless.arcod.ArcodImage
import com.stash.data.download.lossless.arcod.ArcodNamed
import com.stash.data.download.lossless.arcod.ArcodStreamResult
import com.stash.data.download.lossless.arcod.ArcodTrackItem
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ArcodStreamResolverTest {

    private val client: ArcodClient = mockk()
    private val qualityPolicy: StreamQualityPolicy = mockk()

    private fun resolver() = ArcodStreamResolver(client, qualityPolicy)

    private fun stubTrack(): TrackEntity = TrackEntity(
        id = 7L,
        title = "Some Song",
        artist = "Some Artist",
        album = "Some Album",
        durationMs = 210_000L,
        isrc = "USRC17607839",
    )

    /** A catalog item that ArcodMatcher (real object) will confidently match. */
    private fun matchingItem(albumId: String? = "0093624804567"): ArcodTrackItem = ArcodTrackItem(
        id = 8767428L,
        title = "Some Song",
        isrc = "USRC17607839",
        duration = 210, // seconds — within the matcher's 5s duration guard
        performer = ArcodNamed(name = "Some Artist", id = 99L),
        album = ArcodAlbum(
            id = albumId,
            title = "Some Album",
            artist = ArcodNamed(name = "Some Artist", id = 42L),
            image = ArcodImage(large = "https://arcod.xyz/cover.jpg"),
            releaseDate = "2020-01-01",
            tracksCount = 12,
        ),
    )

    @Test
    fun resolve_happyPath_threadsQualityCode_andReturnsArcodStreamUrl() = runTest {
        val url = "https://dl.arcod.xyz/stream/abc.flac?token=xyz"
        coEvery { client.search(any()) } returns listOf(matchingItem())
        coEvery { qualityPolicy.streamingTier() } returns LosslessQualityTier.MAX // qobuzCode 27
        coEvery { client.streamUrl(any(), any()) } returns ArcodStreamResult(url)

        val before = System.currentTimeMillis()
        val result = resolver().resolve(stubTrack())
        val after = System.currentTimeMillis()

        coVerify { client.streamUrl(8767428L, 27) }
        assertThat(result).isNotNull()
        assertThat(result!!.origin).isEqualTo("arcod")
        assertThat(result.url).isEqualTo(url)
        assertThat(result.codec).isEqualTo("flac")
        assertThat(result.coverArtUrl).isEqualTo("https://arcod.xyz/cover.jpg")
        // No expiresIn -> conservative 280s default TTL.
        assertThat(result.expiresAtMs).isAtLeast(before + 280_000L)
        assertThat(result.expiresAtMs).isAtMost(after + 280_000L)
    }

    @Test
    fun resolve_cdTier_sendsQobuzCode6() = runTest {
        coEvery { client.search(any()) } returns listOf(matchingItem())
        coEvery { qualityPolicy.streamingTier() } returns LosslessQualityTier.CD // qobuzCode 6
        coEvery { client.streamUrl(any(), any()) } returns ArcodStreamResult("https://x/y.flac")

        resolver().resolve(stubTrack())

        coVerify { client.streamUrl(8767428L, 6) }
    }

    @Test
    fun resolve_usesExpiresIn_forTtl_whenPresent() = runTest {
        coEvery { client.search(any()) } returns listOf(matchingItem())
        coEvery { qualityPolicy.streamingTier() } returns LosslessQualityTier.MAX
        coEvery { client.streamUrl(any(), any()) } returns
            ArcodStreamResult("https://x/y.flac", expiresInSec = 120)

        val before = System.currentTimeMillis()
        val result = resolver().resolve(stubTrack())
        val after = System.currentTimeMillis()

        // 120s lifetime minus a 20s safety margin = ~100s TTL.
        assertThat(result!!.expiresAtMs).isAtLeast(before + 100_000L)
        assertThat(result.expiresAtMs).isAtMost(after + 100_000L)
    }

    @Test
    fun resolve_resolvesEvenWhenAlbumIdMissing() = runTest {
        coEvery { client.search(any()) } returns listOf(matchingItem(albumId = null))
        coEvery { qualityPolicy.streamingTier() } returns LosslessQualityTier.MAX
        coEvery { client.streamUrl(any(), any()) } returns ArcodStreamResult("https://x/y.flac")

        val result = resolver().resolve(stubTrack())

        assertThat(result).isNotNull()
        coVerify { client.streamUrl(8767428L, 27) }
    }

    @Test
    fun resolve_returnsNull_whenNoMatch() = runTest {
        coEvery { client.search(any()) } returns emptyList()
        coEvery { qualityPolicy.streamingTier() } returns LosslessQualityTier.MAX

        assertThat(resolver().resolve(stubTrack())).isNull()
    }

    @Test
    fun resolve_returnsNull_whenStreamUrlNull() = runTest {
        coEvery { client.search(any()) } returns listOf(matchingItem())
        coEvery { qualityPolicy.streamingTier() } returns LosslessQualityTier.MAX
        coEvery { client.streamUrl(any(), any()) } returns null

        assertThat(resolver().resolve(stubTrack())).isNull()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core:media:testDebugUnitTest --tests "com.stash.core.media.streaming.ArcodStreamResolverTest" --no-daemon`
Expected: FAIL — `ArcodStreamResolver` constructor still takes `ArcodJobGate`; `client.streamUrl` unresolved (compile error).

- [ ] **Step 3: Rewrite the resolver**

Replace the body of `ArcodStreamResolver.kt`. Drop the `ArcodJobGate` / `ArcodJobRequest` imports; add nothing new from `:data:download` except `ArcodClient`/`ArcodMatcher`/`TrackQuery` (already imported). New constructor `(client, qualityPolicy)`.

```kotlin
package com.stash.core.media.streaming

import android.util.Log
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.arcod.ArcodClient
import com.stash.data.download.lossless.arcod.ArcodMatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * Stream-URL resolver backed by ARCOD's single stream-URL GET via [ArcodClient].
 *
 * Flow: open `get-music` search → [ArcodMatcher.best] → the matched Qobuz track id
 * → one `GET <ARCOD_STREAM_BASE>/<id>?quality=<code>` that returns an open,
 * Range-capable, short-lived FLAC URL. No job render/poll (that path is kept only
 * for downloads). The URL plays through the default media-source factory.
 *
 * The streaming quality tier comes from [StreamQualityPolicy] (per-network /
 * Save-Data), mirroring [AmzStreamResolver] — so ARCOD streaming respects the
 * user's cellular/Wi-Fi tier instead of always pulling max.
 *
 * Sits LAST among the lossless streaming sources and foreground-only (gated in
 * [StreamSourceRegistry]); reached only when kennyy and squid both miss.
 *
 * Returns null when search has no confident match, or the stream GET fails.
 */
@Singleton
class ArcodStreamResolver @Inject constructor(
    private val client: ArcodClient,
    private val qualityPolicy: StreamQualityPolicy,
) {
    suspend fun resolve(track: TrackEntity): StreamUrl? {
        Log.d(TAG, "resolve attempt id=${track.id} title='${track.title}'")
        val query = TrackQuery(
            artist = track.artist,
            title = track.title,
            album = track.album.takeIf { it.isNotBlank() },
            isrc = track.isrc?.takeIf { it.isNotBlank() },
            durationMs = track.durationMs,
        )
        return try {
            val items = client.search("${query.artist} ${query.title}".trim())
            val match = ArcodMatcher.best(query, items) ?: run {
                Log.d(TAG, "no_match id=${track.id}")
                return null
            }
            val item = match.item
            val code = qualityPolicy.streamingTier().qobuzCode
            val stream = client.streamUrl(item.id, code) ?: run {
                Log.d(TAG, "no_stream_url id=${track.id} trackId=${item.id} quality=$code")
                return null
            }
            val ttlMs = stream.expiresInSec
                ?.let { it.toLong() * 1000L - EXPIRY_SAFETY_MS }
                ?.takeIf { it > 0L }
                ?: DEFAULT_TTL_MS
            Log.d(TAG, "resolved id=${track.id} origin=$ORIGIN quality=$code")
            StreamUrl(
                url = stream.url,
                expiresAtMs = System.currentTimeMillis() + ttlMs,
                codec = "flac",
                origin = ORIGIN,
                coverArtUrl = item.album?.image?.large?.takeIf { it.isNotBlank() },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "resolve failed id=${track.id}", e)
            null
        }
    }

    companion object {
        const val ORIGIN = "arcod"
        private const val TAG = "ArcodStreamResolver"
        /** No parseable expiry on the URL — conservative reuse window. */
        private const val DEFAULT_TTL_MS = 280_000L
        /** Re-resolve this much before a server-stated expiry to avoid a 403. */
        private const val EXPIRY_SAFETY_MS = 20_000L
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :core:media:testDebugUnitTest --tests "com.stash.core.media.streaming.ArcodStreamResolverTest" --no-daemon`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/streaming/ArcodStreamResolver.kt \
        core/media/src/test/kotlin/com/stash/core/media/streaming/ArcodStreamResolverTest.kt
git commit -m "feat(arcod): stream via single GET instead of job render

ArcodStreamResolver now resolves the playable URL with one
client.streamUrl() call, threading the per-network quality tier
(fixes hardcoded quality=27). Drops the album-id gate and the
ArcodJobGate dependency (downloads keep the job flow). TTL derives
from expiresIn when present, else 280s.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: Verify the Hilt graph + full module builds

**Files:** none (verification only).

- [ ] **Step 1: Assemble the debug APK (proves the Dagger graph)**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. This is the real gate for the resolver's constructor change — `:core:media:compileDebugKotlin` passing does NOT prove the full Hilt component compiles (KSP misses multibinding-mediated issues; only the component Java-compile in `assembleDebug` does). `StreamQualityPolicy` is already provided and `ArcodJobGate` is still bound via `ArcodSource`, so no module edit should be needed — if this fails on a missing/unused binding, fix the DI here, not by reverting the resolver.

- [ ] **Step 2: Run both touched test classes once more together**

Run:
```
./gradlew :data:download:testDebugUnitTest --tests "com.stash.data.download.lossless.arcod.*" --no-daemon
./gradlew :core:media:testDebugUnitTest --tests "com.stash.core.media.streaming.ArcodStreamResolverTest" --no-daemon
```
Expected: PASS. (Do NOT run the whole `:core:media` suite — it has an unrelated flaky network test that hangs.)

- [ ] **Step 3: Commit (only if any DI fix was needed in Step 1)**

```bash
git add -A && git commit -m "fix(arcod): DI wiring for streaming single-GET resolver

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: Now Playing quality display (added after on-device verification)

On-device verification proved arcod streams true hi-res (RAM → 88.2 kHz) but Now
Playing shows bare "FLAC" because `ArcodStreamResolver` leaves
`bitsPerSample`/`sampleRateHz` null. The single GET returns no format, so derive
the delivered quality from the search item's catalog maximums clamped to the
requested tier (the Qobuz contract). No extra network call.

**Files:**
- Modify: `data\download\src\main\kotlin\com\stash\data\download\lossless\arcod\ArcodApiModels.kt` (add `maxSamplingRate` to `ArcodTrackItem`)
- Test: `data\download\src\test\kotlin\com\stash\data\download\lossless\arcod\ArcodApiModelsTest.kt` (parse the new field)
- Create: `core\media\src\main\kotlin\com\stash\core\media\streaming\ArcodDeliveredQuality.kt` (pure clamp helper)
- Test: `core\media\src\test\kotlin\com\stash\core\media\streaming\ArcodDeliveredQualityTest.kt`
- Modify: `core\media\src\main\kotlin\com\stash\core\media\streaming\ArcodStreamResolver.kt` (set the two fields)
- Test: `core\media\src\test\kotlin\com\stash\core\media\streaming\ArcodStreamResolverTest.kt` (assert the fields per tier)

- [ ] **Step 1: Add `maxSamplingRate` to `ArcodTrackItem` (TDD)**

In `ArcodApiModelsTest.kt` extend the existing track-item parse test (or add one) to assert `maximum_sampling_rate` parses to `maxSamplingRate`. Then add the field to `ArcodTrackItem` (after `maxBitDepth`):

```kotlin
    @SerialName("maximum_sampling_rate") val maxSamplingRate: Double? = null,
```

(It's a JSON number in kHz, e.g. `88.2`. Lenient `ArcodJson` already tolerates it.)

- [ ] **Step 2: Pure clamp helper (TDD)**

Write `ArcodDeliveredQualityTest.kt` first, then `ArcodDeliveredQuality.kt`:

```kotlin
package com.stash.core.media.streaming

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Derives the *delivered* lossless quality of an arcod stream from the catalog
 * maximums clamped to the requested Qobuz tier. The single stream GET returns
 * only a URL, so — per the deterministic Qobuz contract (delivered = master
 * clamped to the tier ceiling) — this reconstructs what was actually served for
 * Now Playing, with no extra network call.
 */
internal object ArcodDeliveredQuality {

    data class Delivered(val bitsPerSample: Int?, val sampleRateHz: Int?)

    /**
     * @param qobuzCode requested tier code (6=CD, 7=hi-res, 27=max; any other
     *   value is treated as 27 — matching the dev's "unknown defaults to 27").
     * @param maxBitDepth catalog `maximum_bit_depth` (null when absent).
     * @param maxSamplingRateKhz catalog `maximum_sampling_rate` in kHz (null when absent).
     */
    fun of(qobuzCode: Int, maxBitDepth: Int?, maxSamplingRateKhz: Double?): Delivered {
        val (ceilBits, ceilRateHz) = when (qobuzCode) {
            6 -> 16 to 44_100
            7 -> 24 to 96_000
            else -> 24 to 192_000 // 27 and any unknown code
        }
        val bits = maxBitDepth?.let { min(it, ceilBits) }
        val rate = maxSamplingRateKhz
            ?.let { (it * 1000).roundToInt() }
            ?.let { min(it, ceilRateHz) }
        return Delivered(bitsPerSample = bits, sampleRateHz = rate)
    }
}
```

Tests to cover: MAX(27)+24/88.2 → 24/88200; MAX+16/44.1 → 16/44100; CD(6)+24/192 → 16/44100 (both clamped); HI_RES(7)+24/192 → 24/96000 (rate clamped); null catalog fields → null/null; an unknown code (e.g. 0) behaves like 27.

- [ ] **Step 3: Wire into `ArcodStreamResolver` (TDD)**

In `ArcodStreamResolverTest.kt`, extend the happy-path/CD tests to assert the new fields (e.g. MAX + `matchingItem()` carrying `maxBitDepth=24, maxSamplingRate=88.2` → `bitsPerSample=24, sampleRateHz=88200`; a CD-tier test → `16/44100`). Then in `ArcodStreamResolver.resolve()`, after computing `code` and resolving `stream`, derive and pass the fields:

```kotlin
            val delivered = ArcodDeliveredQuality.of(code, item.maxBitDepth, item.maxSamplingRate)
            // ... in StreamUrl(...):
                bitsPerSample = delivered.bitsPerSample,
                sampleRateHz = delivered.sampleRateHz,
```

(Leave `bitrateKbps` unset/null — FLAC is variable and the catalog item has no bitrate.) Update the `matchingItem()` fixture to include `maxBitDepth`/`maxSamplingRate` so the assertions are meaningful.

- [ ] **Step 4: Run tests**

```
./gradlew :data:download:testDebugUnitTest --tests "com.stash.data.download.lossless.arcod.ArcodApiModelsTest"
./gradlew :core:media:testDebugUnitTest --tests "com.stash.core.media.streaming.ArcodDeliveredQualityTest"
./gradlew :core:media:testDebugUnitTest --tests "com.stash.core.media.streaming.ArcodStreamResolverTest"
```
Expected: all PASS. Use the daemon (NOT `--no-daemon` — it BindExceptions on this Windows setup); never run the whole `:core:media` suite (flaky hanging test).

- [ ] **Step 5: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/lossless/arcod/ArcodApiModels.kt \
        data/download/src/test/kotlin/com/stash/data/download/lossless/arcod/ArcodApiModelsTest.kt \
        core/media/src/main/kotlin/com/stash/core/media/streaming/ArcodDeliveredQuality.kt \
        core/media/src/test/kotlin/com/stash/core/media/streaming/ArcodDeliveredQualityTest.kt \
        core/media/src/main/kotlin/com/stash/core/media/streaming/ArcodStreamResolver.kt \
        core/media/src/test/kotlin/com/stash/core/media/streaming/ArcodStreamResolverTest.kt
git commit -m "feat(arcod): show real bit-depth/sample-rate in Now Playing

Derive delivered quality from the catalog maximums clamped to the
requested tier and set bitsPerSample/sampleRateHz on the StreamUrl, so
arcod shows e.g. 24-bit/88.2 kHz instead of bare FLAC (matching
kennyy/squid). No extra network call.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## On-device verification (post-merge, with the user — NOT part of the TDD tasks)

Carry these onto the device checklist; they need the user's ARCOD-connected device and the force-arcod-only toggle. The make-or-break one is first.

1. **Returned URL plays through the default factory with NO Bearer.** ExoPlayer's default `DataSource` won't send the arcod token. Force-arcod-only + logcat; confirm audible FLAC. If it 401s, the URL needs the header → escalate to a header-injecting `DataSource` + a `StashMediaSourceFactory` routing branch like the amz `OkHttpDataSource` branch (named fallback, not built here).
2. **HI_RES tier (code 7) streams audibly** — the dev cited only 6/27; 7 is expected-good but unverified.
3. **Seeking late in a long track still works** (Range + URL still valid).
4. **The first `stream parse miss` log never appears** (or, if it does, read the raw body and tighten `parseStreamResult`).
5. **Failover** to amz/YouTube when ARCOD misses or the stream GET fails.
6. **(Task 4) Now Playing shows the real quality** — at Max, a 24/88.2 master (e.g.
   Daft Punk *RAM*) reads "24-bit/88.2 kHz"; a CD-only master or CD tier reads
   "16-bit/44.1 kHz" (no longer bare "FLAC").

**Verification status (2026-06-24, device Pixel 6 Pro):** Items 1, 4 PASSED — stream
GET 200, played audibly via the default factory with no Bearer, parser clean (no
parse-miss). Quality confirmed via decoder: `quality=27` + Daft Punk *RAM* →
88200 Hz (24-bit hi-res); Melvins *Houdini* → 44100 Hz (CD-only master, correct).
Item 2 (code `7`) not separately exercised (Max/27 + CD/6 both confirmed). Found a
**pre-existing, out-of-scope ARCOD auth bug**: the Supabase refresh token gets
rotated out from under the app when the Connect-ARCOD WebView is open ("Invalid
Refresh Token: Already Used"), clearing credentials — affects downloads too;
worth its own follow-up. Reconnecting ARCOD restored auth and all playback.

## Out of scope (v1)

- ARCOD download path (job flow stays).
- Re-ordering/promoting ARCOD out of last/foreground-only.
- Header-injecting `DataSource` (only if device step 1 fails).
- Rate-limiter/health-gate on the stream path (revisit when the dev adds limits).
