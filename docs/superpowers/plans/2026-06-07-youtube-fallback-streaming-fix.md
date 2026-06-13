# YouTube-Fallback Streaming Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make YouTube-fallback streaming fast (≈0.5 s, not 11 s), deep (full in-order queue, not 2 tracks), correct (no skipping streamable tracks), and higher quality (160 k Opus, not 128 k AAC).

**Architecture:** Resurrect the dead InnerTube fast path by fixing the `IOS` client to exactly replicate the proven-working player-endpoint spike (host `www.youtube.com`, version `21.02.3`, keyless, numeric client-name header, `contentCheckOk/racyCheckOk`), select Opus 251 preferentially, then split the single `allowYouTube` gate into independent fast (InnerTube) / slow (yt-dlp) lanes so background queue-fill uses the parallel cap-8 InnerTube lane to build the whole queue. yt-dlp stays the rare-case backstop.

**Tech Stack:** Kotlin, Media3/ExoPlayer, Hilt, OkHttp, kotlinx.serialization, JUnit, coroutines. Modules: `data:ytmusic`, `data:download`, `core:media`.

**Spec:** `docs/superpowers/specs/2026-06-07-youtube-fallback-streaming-fix-design.md`

---

## File Structure

| File | Responsibility | Change |
|------|----------------|--------|
| `data/ytmusic/.../InnerTubeClient.kt` | InnerTube HTTP + variants | Per-variant host + numeric client-name + keyless non-WEB; IOS config; `AUDIO_VARIANT_ORDER`; `player()` body flags |
| `data/download/.../preview/PreviewUrlExtractor.kt` | InnerTube↔yt-dlp race + format select | Opus-preferred selection; `allowYtDlp` fast/slow split |
| `core/media/.../streaming/YouTubeStreamResolver.kt` | Track→StreamUrl (YT) | Thread `allowYtDlp` |
| `core/media/.../streaming/StreamSourceRegistry.kt` | Resolver priority chain | Thread `allowYtDlp` |
| `core/media/.../PlayerRepositoryImpl.kt` | Queue build + prefetch | Thread `allowYtDlp`; background fill `allowYouTube=true, allowYtDlp=false` |

**Decision resolved (spec Component 3):** Do **not** deepen prefetch beyond 1-ahead. Background fill now resolves the entire queue via the fast lane, so deep prefetch is redundant; the 1-ahead prefetch remains only as the yt-dlp-allowed straggler safety net. `YTDLP_CONCURRENCY` stays `1`.

---

## Task 1: Fix the IOS InnerTube client to match the working spike

**Files:**
- Modify: `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/InnerTubeClient.kt`
- Test: `data/ytmusic/src/test/kotlin/com/stash/data/ytmusic/InnerTubeClientStatusTest.kt` (add cases) or a new `InnerTubeVariantTest.kt`

**Context:** Currently `InnerTubeVariant` has `clientName/clientVersion/userAgent/extraClientFields`. `player()` (line ~319) hardcodes `"$BASE_URL/player"` where `BASE_URL = https://music.youtube.com/youtubei/v1`. `executeRequestWithStatus` (line ~586) appends `key=$API_KEY` whenever unauthenticated, and sets `X-YouTube-Client-Name` to the string `variant.clientName`. The spike proved IOS works only with: `www.youtube.com` host, **no** key, numeric client-name header (`5`), current version, and `contentCheckOk/racyCheckOk` in the body.

- [ ] **Step 1: Write failing tests for variant config + host mapping**

Add to a variant test (pure, no Android deps needed for the enum):

```kotlin
@Test fun ios_uses_www_host_and_current_version() {
    val ios = InnerTubeVariant.IOS
    assertEquals("21.02.3", ios.clientVersion)
    assertEquals("https://www.youtube.com/youtubei/v1", ios.apiBase)
    assertEquals("5", ios.clientNameId)
    assertFalse(ios.sendsApiKey)   // keyless
}

@Test fun web_remix_stays_on_music_host() {
    val web = InnerTubeVariant.WEB_REMIX
    assertEquals("https://music.youtube.com/youtubei/v1", web.apiBase)
    assertTrue(web.sendsApiKey)
}

@Test fun audio_variant_order_is_ios_first_and_excludes_login_walled() {
    assertEquals(listOf(InnerTubeVariant.IOS), InnerTubeClient.AUDIO_VARIANT_ORDER)
}
```

- [ ] **Step 2: Run tests — expect FAIL** (`apiBase`/`clientNameId`/`sendsApiKey` unresolved; order mismatch)

Run: `./gradlew :data:ytmusic:testDebugUnitTest --tests "*InnerTubeVariant*"`
Expected: FAIL (compile / assertion).

- [ ] **Step 3: Add the new variant fields**

In `InnerTubeVariant` enum, add constructor params with defaults:

```kotlin
enum class InnerTubeVariant(
    val clientName: String,
    val clientVersion: String,
    val userAgent: String,
    val extraClientFields: Map<String, Any> = emptyMap(),
    /** API base host for THIS client's player calls. */
    val apiBase: String = "https://music.youtube.com/youtubei/v1",
    /** Numeric X-YouTube-Client-Name header value. */
    val clientNameId: String = "",
    /** Whether to append the YT-Music web API key (only WEB_REMIX). */
    val sendsApiKey: Boolean = false,
) {
```

- [ ] **Step 4: Update IOS + WEB_REMIX + audio order**

Replace the `IOS` entry:

```kotlin
IOS(
    clientName = "IOS",
    clientVersion = "21.02.3",
    userAgent = "com.google.ios.youtube/21.02.3 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
    extraClientFields = mapOf(
        "deviceMake" to "Apple",
        "deviceModel" to "iPhone16,2",
        "osName" to "iPhone",
        "osVersion" to "18.3.2.22D82",
    ),
    apiBase = "https://www.youtube.com/youtubei/v1",
    clientNameId = "5",
    sendsApiKey = false,
),
```

On `WEB_REMIX` add `clientNameId = "67", sendsApiKey = true` (67 = WEB_REMIX) and leave `apiBase` defaulted to music host. (ANDROID_VR/TVHTML5 may stay defined but are removed from the audio order.)

Change the order list:

```kotlin
val AUDIO_VARIANT_ORDER = listOf(InnerTubeVariant.IOS)
```

Make it visible to the test (`internal`/`@VisibleForTesting` on the companion `val` if not already).

- [ ] **Step 5: Make `player()` host-aware + add content flags**

In `player()` (line ~310), use the variant's host and add the gate-bypass flags:

```kotlin
val body = buildJsonObject {
    put("context", buildContext(variant))
    put("videoId", videoId)
    put("contentCheckOk", true)
    put("racyCheckOk", true)
}
executeRequest("${variant.apiBase}/player", body, cookie, variant)
```

- [ ] **Step 6: Keyless + numeric client-name in `executeRequestWithStatus`**

In `executeRequestWithStatus` (line ~586), gate the key on `variant.sendsApiKey`, and send the numeric client-name header when present:

```kotlin
val fullUrl = when {
    sapiSid != null -> "${url}${separator}prettyPrint=false"
    variant.sendsApiKey -> "${url}${separator}key=$API_KEY&prettyPrint=false"
    else -> "${url}${separator}prettyPrint=false"   // keyless (IOS etc.)
}
```

and:

```kotlin
.header("X-YouTube-Client-Name", variant.clientNameId.ifBlank { variant.clientName })
.header("X-YouTube-Client-Version", variant.currentVersion())
```

(The cookie/auth block already guards `variant == WEB_REMIX` — leave it; IOS stays cookie-less, matching the spike.)

- [ ] **Step 7: Run tests — expect PASS**

Run: `./gradlew :data:ytmusic:testDebugUnitTest --tests "*InnerTubeVariant*"`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/InnerTubeClient.kt data/ytmusic/src/test/kotlin/com/stash/data/ytmusic/
git commit -m "fix(yt): IOS InnerTube client on www host, v21.02.3, keyless (restores fast lane)"
```

---

## Task 2: Opus-preferred audio format selection

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/preview/PreviewUrlExtractor.kt` (`extractViaInnerTube`, lines ~352–369)
- Test: `data/download/src/test/kotlin/com/stash/data/download/preview/PreviewFormatSelectionTest.kt` (new)

**Context:** Today the selector is `sortedByDescending { bitrate }` — for `[AAC 132k, Opus 130k]` it picks AAC (worse). Opus 251 is YouTube's best free format and more efficient. yt-dlp's selector already prefers `251/250`.

- [ ] **Step 1: Write failing test for selection**

Extract the pure selection into a testable function `selectBestAudioUrl(formats: List<JsonObject>): String?` and test:

```kotlin
@Test fun prefers_opus_over_equal_or_higher_aac() {
    val formats = listOf(
        fmt(itag=140, mime="audio/mp4; codecs=\"mp4a.40.2\"", bitrate=132000, url="aac-url"),
        fmt(itag=251, mime="audio/webm; codecs=\"opus\"", bitrate=130000, url="opus-url"),
    )
    assertEquals("opus-url", selectBestAudioUrl(formats))
}

@Test fun falls_back_to_highest_aac_when_no_opus() {
    val formats = listOf(
        fmt(itag=139, mime="audio/mp4; codecs=\"mp4a.40.5\"", bitrate=50000, url="lo"),
        fmt(itag=140, mime="audio/mp4; codecs=\"mp4a.40.2\"", bitrate=132000, url="hi"),
    )
    assertEquals("hi", selectBestAudioUrl(formats))
}

@Test fun ignores_ciphered_formats_without_direct_url() {
    val formats = listOf(fmtNoUrl(itag=251, mime="audio/webm; codecs=\"opus\"", bitrate=160000))
    assertNull(selectBestAudioUrl(formats))
}
```

(`fmt`/`fmtNoUrl` build `JsonObject`s with `mimeType`, `bitrate`, optional `url`.)

- [ ] **Step 2: Run — expect FAIL** (`selectBestAudioUrl` undefined)

Run: `./gradlew :data:download:testDebugUnitTest --tests "*PreviewFormatSelection*"`
Expected: FAIL.

- [ ] **Step 3: Implement Opus-preferred selection**

Replace the body of the format pick in `extractViaInnerTube` with a call to a new private/internal function:

```kotlin
internal fun selectBestAudioUrl(formats: List<JsonObject>): String? {
    val audio = formats.filter { f ->
        (f["mimeType"]?.jsonPrimitive?.content ?: "").startsWith("audio/") && f["url"] != null
    }
    fun bitrate(f: JsonObject) = f["bitrate"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
    fun isOpus(f: JsonObject) = (f["mimeType"]?.jsonPrimitive?.content ?: "").contains("opus")
    val opus = audio.filter(::isOpus).maxByOrNull(::bitrate)
    val best = opus ?: audio.maxByOrNull(::bitrate)
    return best?.get("url")?.jsonPrimitive?.content
}
```

Wire `extractViaInnerTube` to use it (operating on `adaptiveFormats.filterIsInstance<JsonObject>()`), preserving the existing SUCCESS/empty logging.

- [ ] **Step 4: Run — expect PASS**

Run: `./gradlew :data:download:testDebugUnitTest --tests "*PreviewFormatSelection*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/preview/PreviewUrlExtractor.kt data/download/src/test/kotlin/com/stash/data/download/preview/
git commit -m "fix(yt): prefer Opus 251 over AAC in InnerTube audio selection"
```

---

## Task 3: Fast/slow split in the extractor (`allowYtDlp`)

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/preview/PreviewUrlExtractor.kt`
- Test: existing `PreviewUrlExtractor*Test.kt` (extend) + `raceForTest`/`extractStreamUrlForTest`

**Context:** `extractStreamUrl(videoId)` → `coalesce(videoId)` → `doExtract(videoId)` → `race(innerTube, ytDlp)`. We add an `allowYtDlp` flag: when false, run InnerTube only (no yt-dlp arm) and signal "no fast URL" by throwing so `YouTubeStreamResolver` maps it to null (track dropped from background fill, caught later by prefetch). Coalescing must not let a fast-only call share a full-race call's result, so the coalesce key includes the mode. The retry entry point `extractViaYtDlpForRetry` is independent and must keep reaching yt-dlp.

> **Implementer notes:** `TestHooks` is an *interface*, not a constructor — follow the existing `TestableExtractor` wrapper pattern in `PreviewUrlExtractorTest.kt` rather than the `TestHooks(...)` literal shown below. Also add `import kotlinx.coroutines.sync.withPermit` (only `Semaphore` is imported today) for the Step 3 snippet.

- [ ] **Step 1: Write failing tests**

```kotlin
@Test fun fastOnly_does_not_invoke_ytdlp_arm() = runTest {
    var ytCalled = false
    val hooks = TestHooks(
        innerTubeExtract = { null },                 // InnerTube misses
        ytDlpExtract = { ytCalled = true; "yt-url" },
    )
    // fast-only path must NOT fall back to yt-dlp; it throws/returns no-url
    assertFailsWith<NoFastStreamException> {
        extractor.extractStreamUrlForTest(hooks, "vid", allowYtDlp = false)
    }
    assertFalse(ytCalled)
}

@Test fun fullRace_still_falls_back_to_ytdlp() = runTest {
    val hooks = TestHooks(innerTubeExtract = { null }, ytDlpExtract = { "yt-url" })
    assertEquals("yt-url", extractor.extractStreamUrlForTest(hooks, "vid", allowYtDlp = true))
}

@Test fun retry_entrypoint_unaffected_by_slow_lane_gate() = runTest {
    // extractViaYtDlpForRetry must still reach yt-dlp regardless of allowYtDlp
    // (assert it calls the yt-dlp path; structural test)
}
```

- [ ] **Step 2: Run — expect FAIL**

Run: `./gradlew :data:download:testDebugUnitTest --tests "*PreviewUrlExtractor*"`
Expected: FAIL (param/exception undefined).

- [ ] **Step 3: Add the flag + fast-only path**

Add an exception and thread the flag:

```kotlin
class NoFastStreamException(videoId: String) : Exception("no fast (InnerTube) stream for $videoId")

suspend fun extractStreamUrl(videoId: String, allowYtDlp: Boolean = true): String =
    coalesce(coalesceKey(videoId, allowYtDlp)) { doExtract(videoId, allowYtDlp) }

private fun coalesceKey(videoId: String, allowYtDlp: Boolean) =
    if (allowYtDlp) videoId else "$videoId#fast"
```

(`coalesce` currently keys by raw videoId — change its `videoId` param to a `key: String`; callers pass the composed key. The in-flight map + cache logic is unchanged.)

In `doExtract(videoId, allowYtDlp)`:

```kotlin
if (!allowYtDlp) {
    // Fast lane only — InnerTube, no yt-dlp arm. Throw on miss so the
    // resolver maps to null (dropped from background fill, caught by prefetch).
    val url = withPermit(innerTubeSemaphore) { extractViaInnerTube(videoId) }
        ?: throw NoFastStreamException(videoId)
    Log.d("LATDIAG", "extract-end videoId=$videoId fastOnly=1")
    return url
}
// else: existing race(...) path unchanged
```

Update `extractStreamUrlForTest` to accept `allowYtDlp` and mirror this (InnerTube-only when false; otherwise `?:` to yt-dlp). Keep `extractViaYtDlpForRetry` untouched.

- [ ] **Step 4: Run — expect PASS**

Run: `./gradlew :data:download:testDebugUnitTest --tests "*PreviewUrlExtractor*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/preview/PreviewUrlExtractor.kt data/download/src/test/
git commit -m "feat(yt): add allowYtDlp fast/slow split to extractStreamUrl"
```

---

## Task 4: Thread `allowYtDlp` through the resolver chain

**Files:**
- Modify: `core/media/.../streaming/YouTubeStreamResolver.kt` (`resolve`, line ~70)
- Modify: `core/media/.../streaming/StreamSourceRegistry.kt` (`resolve`, line ~62)
- Test: `core/media/src/test/kotlin/com/stash/core/media/streaming/StreamSourceRegistryTest.kt` (**create** — does not exist yet; module has `YouTubeStreamResolverTest.kt` as a pattern reference)

- [ ] **Step 1: Write failing test**

```kotlin
@Test fun resolve_passes_allowYtDlp_to_youtube() = runTest {
    // youtube fake records the allowYtDlp it received
    registry.resolve(track, allowYouTube = true, allowYtDlp = false)
    assertEquals(false, fakeYouTube.lastAllowYtDlp)
}
```

- [ ] **Step 2: Run — expect FAIL**

Run: `./gradlew :core:media:testDebugUnitTest --tests "*StreamSourceRegistry*"`
Expected: FAIL.

- [ ] **Step 3: Add the param**

`YouTubeStreamResolver.resolve`:

```kotlin
suspend fun resolve(track: TrackEntity, allowYtDlp: Boolean = true): StreamUrl? {
    ...
    runCatching { urlExtractor.extractStreamUrl(videoId, allowYtDlp) }
    ...
}
```

`StreamSourceRegistry.resolve`:

```kotlin
suspend fun resolve(
    track: TrackEntity,
    allowYouTube: Boolean = true,
    allowYtDlp: Boolean = true,
): StreamUrl? {
    ...
    // youtube entry becomes:
    if (allowYouTube) add("youtube" to { t: TrackEntity -> youtube.resolve(t, allowYtDlp) })
    ...
}
```

(Apply in both the `forceYt` and normal branches.)

- [ ] **Step 4: Run — expect PASS**

Run: `./gradlew :core:media:testDebugUnitTest --tests "*StreamSourceRegistry*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/streaming/ core/media/src/test/
git commit -m "feat(yt): thread allowYtDlp through StreamSourceRegistry + YouTubeStreamResolver"
```

---

## Task 5: Wire PlayerRepositoryImpl — background fill uses the fast lane

**Files:**
- Modify: `core/media/.../PlayerRepositoryImpl.kt`

**Context:** `resolveTrackToMediaItem` (line ~638) and `buildMediaItemForTrack` (line ~939) call `streamResolver.resolve(entity, allowYouTube)`. `fillQueueAppend/Prepend` (line ~431/450) and `resolveBatchParallel` (line ~471) thread `allowYouTube`. `setQueue` (line ~345 tapped, ~402/403 fill) and `prefetchNextTrack` (line ~536) are the call sites.

- [ ] **Step 1: Thread `allowYtDlp` through the resolve helpers**

Add `allowYtDlp: Boolean` (default `true`) to `resolveTrackToMediaItem`, `buildMediaItemForTrack`, `fillQueueAppend`, `fillQueuePrepend`, `resolveBatchParallel`, passing it into `streamResolver.resolve(entity, allowYouTube, allowYtDlp)`.

- [ ] **Step 2: Set the lane per call site**

- `setQueue` tapped track (line ~345): `allowYouTube = true, allowYtDlp = true`.
- `setQueue` background fill (lines ~402–403): **`allowYouTube = true, allowYtDlp = false`** (was `allowYouTube = false`).
- `prefetchNextTrack` (line ~536): `allowYouTube = true, allowYtDlp = true` (straggler backstop).

Update the comment block at lines ~389–397 to reflect: background fill now uses the InnerTube fast lane (cap-8, parallel), only yt-dlp is withheld; iOS-miss tracks are skipped and recovered by the next-up prefetch.

- [ ] **Step 3: Build & full module tests**

Run: `./gradlew :core:media:testDebugUnitTest`
Expected: PASS (existing suite green; no regressions). Fix any signature-mismatch compile errors revealed.

- [ ] **Step 4: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/PlayerRepositoryImpl.kt
git commit -m "feat(yt): background queue-fill uses InnerTube fast lane (deep, in-order queue)"
```

---

## Task 6: Build, install, on-device LATDIAG verification

**Context:** Per `feedback_install_after_fix` — compile-pass is not enough; verify on the Pixel 6 Pro. This is the acceptance gate.

- [ ] **Step 1: Full build + unit suite**

Run: `./gradlew :data:ytmusic:testDebugUnitTest :data:download:testDebugUnitTest :core:media:testDebugUnitTest`
Expected: all PASS.

- [ ] **Step 2: Install debug**

Run: `./gradlew :app:installDebug`
Expected: BUILD SUCCESSFUL, app updated on device.

- [ ] **Step 3: Capture LATDIAG while playing a force-YT mix**

```bash
adb logcat -c
adb logcat -v time -s LATDIAG:D StashYT:D StashPlayer:D > /tmp/yt-verify.log &
# In app: Settings → Force YouTube fallback ON → play an all-streaming mix; let ~5 tracks play.
```

- [ ] **Step 4: Assert the four acceptance criteria from the log**
  - `won with variant=IOS` and `extract-end ... dt < 1500ms` (was ~11000 via yt-dlp).
  - For an all-YT mix, `controller.mediaItemCount` reflects the full queue (not 2) — confirm via the queue UI / state log, not a 2-item timeline.
  - A mixed downloaded+streamable mix plays streamable tracks **in order**, no skip-to-downloads.
  - Now Playing shows Opus (~160 k), not AAC 128 k.

- [ ] **Step 5: Turn Force-YT OFF; sanity-check normal lossless playback still works** (regression guard on the host/plumbing changes — search/library unaffected).

- [ ] **Step 6: Record results in the spec**

Append a `## Verification Results (2026-06-07)` section to the spec with pass/fail + observed `dt` numbers.

---

## Out of scope (do not implement here)
- Lossless/FLAC path (deferred per user).
- Download-path format order (`141/140/251`) quality fix.
- Honest "256 k" quality-label UI tweak.
- NewPipe extractor (reserve if iOS lane degrades).
- Codec/bitrate metadata richer return type (optional; current change already lets `YouTubeStreamResolver` report a better label only if cheaply threadable — leave the hardcoded `"aac"` if it requires a return-type change, to keep this plan scoped).
