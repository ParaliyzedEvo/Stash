# Lossless Degradation-Detection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Detect when a Qobuz proxy (`kennyy`, `squid`) silently degrades to a 30-second preview sample or a lossy downgrade, treat that as a resolve **failure** so the failover chain advances (streaming *and* downloading), and skip the degraded source for a per-source cooldown with automatic recovery.

**Architecture:** A pure URL classifier (`LosslessUrlInspector`) plus a per-source cooldown gate (`LosslessSourceHealthGate`) are injected into both `KennyySource` and `QobuzSource`. Right after `getFileUrl` returns a URL, the source inspects it; a degraded URL records the source as degraded and returns `null`, which the existing registries already treat as a miss → free failover for both paths. The registries/resolvers consult the gate up-front to skip a degraded source cheaply. A download-only duration backstop in `DownloadManager.tryLosslessDownload` catches the rare degraded URL that lacks the marker.

**Tech Stack:** Kotlin, Android, Hilt DI (constructor injection + `@IntoSet` multibinding), JUnit4 + MockK 1.13.13 + Google Truth + `kotlinx-coroutines-test`. Multi-module Gradle.

**Spec:** `docs/superpowers/specs/2026-06-05-lossless-degradation-detection-design.md`

---

## File Structure

**New files (all in `data\download\src\main\kotlin\com\stash\data\download\lossless\`):**
- `LosslessUrlInspector.kt` — pure classifier: `range=` preview marker + lossy-`fmt` downgrade.
- `LosslessSourceHealthGate.kt` — per-source `degradedUntil` map keyed by `LosslessSource.id` (String), `@Singleton`, injectable clock.

**Modified:**
- `…\lossless\kennyy\KennyySource.kt` — add inspector+gate ctor deps; reject degraded URL after `getFileUrl`.
- `…\lossless\qobuz\QobuzSource.kt` — same.
- `…\lossless\LosslessSourceRegistry.kt` — skip a degraded source in the resolve loop (inject gate).
- `core\media\…\streaming\KennyyStreamResolver.kt` — skip when gate degraded.
- `core\media\…\streaming\QobuzStreamResolver.kt` — skip when gate degraded.
- `…\download\DownloadManager.kt` — duration backstop loop in `tryLosslessDownload`.

**New tests:**
- `data\download\src\test\…\lossless\LosslessUrlInspectorTest.kt`
- `data\download\src\test\…\lossless\LosslessSourceHealthGateTest.kt`
- `data\download\src\test\…\lossless\kennyy\KennyySourceTest.kt` (new — none exists today)
- `data\download\src\test\…\lossless\LosslessSourceRegistryTest.kt` (new)
- `core\media\src\test\…\streaming\QobuzStreamResolverTest.kt` (new) + extend `KennyyStreamResolverTest.kt`

**Modified tests:**
- `data\download\src\test\…\lossless\qobuz\QobuzSourceTest.kt` — update `source()` factory for new ctor deps.

**Key design decisions (recorded so the implementer doesn't re-litigate):**
1. **Passive canary recovery.** When `degradedUntil` lapses the source is simply retried by the next *real* resolve; if it's still serving a sample the inspector re-gates it and the registry fails over. We do **not** add a dedicated hardcoded canary track or a circular `gate→source` call. This matches the spec's accepted cost ("one extra resolve when both are down, paid once per cooldown") and keeps the gate a pure, dependency-free, unit-testable timer. The existing `KennyyHealthProbe` (network health) is untouched.
2. **Gate keyed by `LosslessSource.id` String** ("kennyy_qobuz", "squid_qobuz") — consistent with the rate-limiter / prefs keying.
3. **Independent per source** — a `Map<String, Long>`; degrading one id never affects another.
4. Inspector takes the **requested quality Int** (`tier.qobuzCode`, already computed just above `getFileUrl`), not the tier enum — keeps it a pure string+int function.

---

### Task 1: `LosslessUrlInspector` (pure classifier)

**Files:**
- Create: `data\download\src\main\kotlin\com\stash\data\download\lossless\LosslessUrlInspector.kt`
- Test: `data\download\src\test\kotlin\com\stash\data\download\lossless\LosslessUrlInspectorTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.stash.data.download.lossless

import com.google.common.truth.Truth.assertThat
import com.stash.data.download.lossless.qobuz.QobuzQuality
import org.junit.Test

class LosslessUrlInspectorTest {

    private val inspector = LosslessUrlInspector()

    // Quality codes: 5=MP3_320, 6=FLAC_CD, 7=FLAC_HIRES_96, 27=FLAC_HIRES_192

    @Test
    fun `preview sample with range marker is degraded`() {
        val url = "https://cdn.example/file?fmt=27&profile=raw&range=20-30&etsp=9999999999"
        assertThat(inspector.isDegraded(url, requestedQuality = QobuzQuality.FLAC_HIRES_192)).isTrue()
    }

    @Test
    fun `range marker is degraded regardless of requested quality`() {
        val url = "https://cdn.example/file?range=0-30&etsp=9999999999"
        assertThat(inspector.isDegraded(url, requestedQuality = QobuzQuality.FLAC_CD)).isTrue()
    }

    @Test
    fun `lossy fmt while lossless requested is degraded`() {
        val url = "https://cdn.example/file?fmt=5&profile=raw&etsp=9999999999"
        assertThat(inspector.isDegraded(url, requestedQuality = QobuzQuality.FLAC_HIRES_192)).isTrue()
    }

    @Test
    fun `lossy fmt when lossy requested is NOT degraded`() {
        // User explicitly chose MP3 tier — fmt=5 is what they asked for.
        val url = "https://cdn.example/file?fmt=5&profile=raw&etsp=9999999999"
        assertThat(inspector.isDegraded(url, requestedQuality = QobuzQuality.MP3_320)).isFalse()
    }

    @Test
    fun `healthy full flac url is NOT degraded`() {
        val url = "https://cdn.example/file?fmt=27&profile=raw&etsp=9999999999"
        assertThat(inspector.isDegraded(url, requestedQuality = QobuzQuality.FLAC_HIRES_192)).isFalse()
    }

    @Test
    fun `url without fmt param and no range is NOT degraded`() {
        // Defensive: an unrecognised but range-free URL is treated as healthy.
        val url = "https://cdn.example/file?profile=raw&etsp=9999999999"
        assertThat(inspector.isDegraded(url, requestedQuality = QobuzQuality.FLAC_HIRES_192)).isFalse()
    }

    @Test
    fun `range substring inside another value does not false-positive`() {
        // "arrange=..." must not match the range= marker.
        val url = "https://cdn.example/file?arrange=20-30&fmt=27&etsp=9999999999"
        assertThat(inspector.isDegraded(url, requestedQuality = QobuzQuality.FLAC_HIRES_192)).isFalse()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew :data:download:testDebugUnitTest --tests "com.stash.data.download.lossless.LosslessUrlInspectorTest"`
Expected: FAIL — `LosslessUrlInspector` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.stash.data.download.lossless

import com.stash.data.download.lossless.qobuz.QobuzQuality
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure classifier over a resolved Qobuz-proxy CDN URL. A degraded source
 * does NOT error — it returns a signed URL for a 30-second preview
 * (`…&range=20-30&…`) or a lossy downgrade (`fmt=5` MP3 when FLAC was
 * requested). Both look like success to the download/stream pipeline, so
 * failover never fires. This classifier turns "degraded" into a detectable
 * signal the source layer can reject. No I/O — fully fixture-testable.
 *
 * See docs/superpowers/specs/2026-06-05-lossless-degradation-detection-design.md
 */
@Singleton
class LosslessUrlInspector @Inject constructor() {

    /**
     * True when [url] is a preview sample (primary signal) or a lossy
     * downgrade relative to [requestedQuality] (secondary signal).
     *
     * @param requestedQuality the Qobuz format_id the app asked for
     *   ([QobuzQuality], e.g. 27 = hi-res FLAC). Pass the same value handed
     *   to `getFileUrl`.
     */
    fun isDegraded(url: String, requestedQuality: Int): Boolean =
        isPreviewSample(url) || isDowngraded(url, requestedQuality)

    /** A `range=<start>-<end>` window marker — the decisive preview signal. */
    fun isPreviewSample(url: String): Boolean = RANGE_REGEX.containsMatchIn(url)

    /**
     * URL serves a lossy `fmt` while a lossless tier was requested — the
     * backing account lost lossless entitlement. No-op when the user
     * deliberately requested a lossy tier (then `fmt=5` is expected).
     */
    fun isDowngraded(url: String, requestedQuality: Int): Boolean {
        if (requestedQuality in LOSSY_CODES) return false
        val servedFmt = FMT_REGEX.find(url)?.groupValues?.get(1)?.toIntOrNull() ?: return false
        return servedFmt in LOSSY_CODES
    }

    private companion object {
        /** Lossy Qobuz format_ids. Today only MP3 320. */
        val LOSSY_CODES = setOf(QobuzQuality.MP3_320)
        val RANGE_REGEX = Regex("""[?&]range=\d+-\d+""")
        val FMT_REGEX = Regex("""[?&]fmt=(\d+)""")
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew :data:download:testDebugUnitTest --tests "com.stash.data.download.lossless.LosslessUrlInspectorTest"`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/lossless/LosslessUrlInspector.kt \
        data/download/src/test/kotlin/com/stash/data/download/lossless/LosslessUrlInspectorTest.kt
git commit -m "feat(lossless): add pure LosslessUrlInspector for degraded-URL detection"
```

---

### Task 2: `LosslessSourceHealthGate` (per-source cooldown)

**Files:**
- Create: `data\download\src\main\kotlin\com\stash\data\download\lossless\LosslessSourceHealthGate.kt`
- Test: `data\download\src\test\kotlin\com\stash\data\download\lossless\LosslessSourceHealthGateTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.stash.data.download.lossless

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LosslessSourceHealthGateTest {

    private var now = 1_000_000L
    private fun gate() = LosslessSourceHealthGate(nowMs = { now })

    @Test
    fun `fresh source is not degraded`() {
        assertThat(gate().isDegraded("kennyy_qobuz")).isFalse()
    }

    @Test
    fun `recordDegraded marks source degraded within cooldown`() {
        val g = gate()
        g.recordDegraded("kennyy_qobuz")
        assertThat(g.isDegraded("kennyy_qobuz")).isTrue()
    }

    @Test
    fun `source recovers after cooldown lapses`() {
        val g = gate()
        g.recordDegraded("kennyy_qobuz")
        now += LosslessSourceHealthGate.COOLDOWN_MS + 1
        assertThat(g.isDegraded("kennyy_qobuz")).isFalse()
    }

    @Test
    fun `still degraded at the instant before cooldown ends`() {
        val g = gate()
        g.recordDegraded("kennyy_qobuz")
        now += LosslessSourceHealthGate.COOLDOWN_MS - 1
        assertThat(g.isDegraded("kennyy_qobuz")).isTrue()
    }

    @Test
    fun `degrading one source does NOT gate another (independence)`() {
        val g = gate()
        g.recordDegraded("kennyy_qobuz")
        assertThat(g.isDegraded("kennyy_qobuz")).isTrue()
        assertThat(g.isDegraded("squid_qobuz")).isFalse()
    }

    @Test
    fun `re-degrading extends the cooldown from the new instant`() {
        val g = gate()
        g.recordDegraded("kennyy_qobuz")
        now += LosslessSourceHealthGate.COOLDOWN_MS - 10
        g.recordDegraded("kennyy_qobuz") // re-degraded — window restarts
        now += 20 // past the ORIGINAL window, before the new one
        assertThat(g.isDegraded("kennyy_qobuz")).isTrue()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew :data:download:testDebugUnitTest --tests "com.stash.data.download.lossless.LosslessSourceHealthGateTest"`
Expected: FAIL — `LosslessSourceHealthGate` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.stash.data.download.lossless

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-source content-health gate. When a source is observed serving a
 * degraded URL (preview sample / lossy downgrade — see [LosslessUrlInspector])
 * it is marked degraded for [COOLDOWN_MS], so subsequent resolves skip it
 * cheaply instead of paying a wasted round-trip per track.
 *
 * **Independent per source** — kennyy and squid share a leaked Qobuz app_id
 * so they often degrade together, but they recover independently (observed
 * 2026-06-04: squid recovered while kennyy stayed 401). Coupling would
 * wrongly keep a recovered source disabled, so each id has its own entry.
 *
 * **Passive recovery** — when the cooldown lapses the source is simply
 * retried by the next real resolve; if it's still degraded the inspector
 * re-marks it. No dedicated canary track is needed because the inspector
 * rejects a degraded recovery attempt before the user ever sees it. This is
 * the content-health layer; Kennyy's network-health [KennyyHealthMonitor]
 * is separate and unchanged.
 *
 * The injectable [nowMs] clock keeps this fully unit-testable; Hilt uses the
 * no-arg secondary constructor (System time).
 */
@Singleton
class LosslessSourceHealthGate(
    private val nowMs: () -> Long,
) {
    @Inject constructor() : this(nowMs = { System.currentTimeMillis() })

    /** sourceId → epoch-ms until which the source is considered degraded. */
    private val degradedUntil = ConcurrentHashMap<String, Long>()

    /** Mark [sourceId] degraded for [COOLDOWN_MS] from now. */
    fun recordDegraded(sourceId: String) {
        degradedUntil[sourceId] = nowMs() + COOLDOWN_MS
    }

    /** True while [sourceId] is within its cooldown window. */
    fun isDegraded(sourceId: String): Boolean {
        val until = degradedUntil[sourceId] ?: return false
        if (nowMs() >= until) {
            // Lapsed — clear so the map doesn't grow unbounded and the next
            // real resolve acts as the passive canary.
            degradedUntil.remove(sourceId, until)
            return false
        }
        return true
    }

    companion object {
        /** Cooldown before a degraded source is retried. ~5 minutes. */
        const val COOLDOWN_MS = 5 * 60 * 1000L
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew :data:download:testDebugUnitTest --tests "com.stash.data.download.lossless.LosslessSourceHealthGateTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/lossless/LosslessSourceHealthGate.kt \
        data/download/src/test/kotlin/com/stash/data/download/lossless/LosslessSourceHealthGateTest.kt
git commit -m "feat(lossless): add per-source LosslessSourceHealthGate with cooldown"
```

---

### Task 3: Wire detection into `KennyySource`

**Files:**
- Modify: `data\download\src\main\kotlin\com\stash\data\download\lossless\kennyy\KennyySource.kt:40-44` (ctor) and `:139-142` (post-getFileUrl guard)
- Test: `data\download\src\test\kotlin\com\stash\data\download\lossless\kennyy\KennyySourceTest.kt` (new)

- [ ] **Step 1: Write the failing test**

```kotlin
package com.stash.data.download.lossless.kennyy

import com.google.common.truth.Truth.assertThat
import com.stash.data.download.lossless.AggregatorRateLimiter
import com.stash.data.download.lossless.LosslessQualityTier
import com.stash.data.download.lossless.LosslessSourceHealthGate
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.LosslessUrlInspector
import com.stash.data.download.lossless.RateLimitState
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.qobuz.QobuzDownloadData
import com.stash.data.download.lossless.qobuz.QobuzImage
import com.stash.data.download.lossless.qobuz.QobuzAlbum
import com.stash.data.download.lossless.qobuz.QobuzPerformer
import com.stash.data.download.lossless.qobuz.QobuzSearchData
import com.stash.data.download.lossless.qobuz.QobuzSearchTracks
import com.stash.data.download.lossless.qobuz.QobuzTrack
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class KennyySourceTest {

    private val apiClient: KennyyApiClient = mockk()
    private val rateLimiter: AggregatorRateLimiter = mockk(relaxUnitFun = true)
    private val losslessPrefs: LosslessSourcePreferences = mockk()
    private val inspector = LosslessUrlInspector() // real pure classifier
    private val healthGate: LosslessSourceHealthGate = mockk(relaxUnitFun = true)

    private fun source() = KennyySource(apiClient, rateLimiter, losslessPrefs, inspector, healthGate)

    private val query = TrackQuery(artist = "Daft Punk", title = "Get Lucky", durationMs = 369_000L)

    private fun stubHappyPathTo(url: String) {
        coEvery { rateLimiter.acquire(any()) } returns true
        coEvery { rateLimiter.stateOf(any()) } returns RateLimitState(false, 0, null)
        coEvery { losslessPrefs.qualityTierNow() } returns LosslessQualityTier.MAX // → fmt 27
        val track = QobuzTrack(
            id = 42L,
            title = "Get Lucky",
            duration = 369,
            isrc = null,
            performer = QobuzPerformer(name = "Daft Punk"),
            album = QobuzAlbum(image = QobuzImage(large = "http://art")),
            maximumBitDepth = 24,
            maximumSamplingRate = 44.1f,
            streamable = true,
        )
        coEvery { apiClient.search(any()) } returns
            QobuzSearchData(tracks = QobuzSearchTracks(items = listOf(track)))
        coEvery { apiClient.getFileUrl(42L, any()) } returns QobuzDownloadData(url = url)
    }

    @Test
    fun `degraded preview-sample url returns null and records degraded`() = runTest {
        stubHappyPathTo("https://cdn/file?fmt=27&range=20-30&etsp=9999999999")
        val result = source().resolve(query)
        assertThat(result).isNull()
        coVerify { healthGate.recordDegraded(KennyySource.SOURCE_ID) }
    }

    @Test
    fun `healthy full url returns a result and does not record degraded`() = runTest {
        stubHappyPathTo("https://cdn/file?fmt=27&etsp=9999999999")
        val result = source().resolve(query)
        assertThat(result).isNotNull()
        assertThat(result!!.downloadUrl).contains("fmt=27")
        coVerify(exactly = 0) { healthGate.recordDegraded(any()) }
    }
}
```

> **Note for implementer:** the `QobuzTrack` / `QobuzSearchData` / `QobuzImage` constructor shapes above are illustrative — confirm the exact field names/defaults in `QobuzModels.kt` and the existing `QobuzSourceTest.kt` builders, and mirror those. If `QobuzSourceTest` has a shared track-fixture helper, prefer reusing its pattern.

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew :data:download:testDebugUnitTest --tests "com.stash.data.download.lossless.kennyy.KennyySourceTest"`
Expected: FAIL — `KennyySource` constructor does not accept `inspector`/`healthGate`.

- [ ] **Step 3: Write minimal implementation**

Add the two ctor params (`KennyySource.kt:40-44`):

```kotlin
@Singleton
class KennyySource @Inject constructor(
    private val apiClient: KennyyApiClient,
    private val rateLimiter: AggregatorRateLimiter,
    private val losslessPrefs: LosslessSourcePreferences,
    private val urlInspector: com.stash.data.download.lossless.LosslessUrlInspector,
    private val healthGate: com.stash.data.download.lossless.LosslessSourceHealthGate,
) : LosslessSource {
```

(Prefer adding top-of-file imports instead of FQNs — `import com.stash.data.download.lossless.LosslessUrlInspector` and `import com.stash.data.download.lossless.LosslessSourceHealthGate`.)

Replace the empty-URL guard (`KennyySource.kt:139-142`) with degradation-aware logic:

```kotlin
        if (download.url.isNullOrEmpty()) {
            Log.d(TAG, "download-music returned empty url for ${best.first.id}")
            return null
        }
        if (urlInspector.isDegraded(download.url, requestedQuality)) {
            Log.w(
                TAG,
                "degraded url for ${best.first.id} (sample/downgrade) — failing over; " +
                    "url=${download.url.take(80)}",
            )
            healthGate.recordDegraded(id)
            return null
        }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew :data:download:testDebugUnitTest --tests "com.stash.data.download.lossless.kennyy.KennyySourceTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/lossless/kennyy/KennyySource.kt \
        data/download/src/test/kotlin/com/stash/data/download/lossless/kennyy/KennyySourceTest.kt
git commit -m "feat(lossless): reject degraded URLs in KennyySource, record cooldown"
```

---

### Task 4: Wire detection into `QobuzSource`

**Files:**
- Modify: `data\download\src\main\kotlin\com\stash\data\download\lossless\qobuz\QobuzSource.kt:40-45` (ctor) and `:176-179` (post-getFileUrl guard)
- Test: `data\download\src\test\kotlin\com\stash\data\download\lossless\qobuz\QobuzSourceTest.kt` (update `source()` + add 2 tests)

- [ ] **Step 1: Update the failing test**

In `QobuzSourceTest.kt`, add fields and update the factory:

```kotlin
private val urlInspector = com.stash.data.download.lossless.LosslessUrlInspector()
private val healthGate: com.stash.data.download.lossless.LosslessSourceHealthGate =
    io.mockk.mockk(relaxUnitFun = true)

private fun source() =
    QobuzSource(apiClient, rateLimiter, captchaExpiredNotifier, losslessPrefs, urlInspector, healthGate)
```

Add two tests mirroring Task 3 (reuse the file's existing happy-path stubbing — `stubLimiterReady()` etc.):

```kotlin
@Test
fun `degraded preview-sample url returns null and records degraded`() = runTest {
    // …existing happy-path stubs, but getFileUrl returns a range= sample URL…
    coEvery { apiClient.getFileUrl(any(), any()) } returns
        QobuzDownloadData(url = "https://cdn/file?fmt=27&range=20-30&etsp=9999999999")
    assertThat(source().resolve(query)).isNull()
    coVerify { healthGate.recordDegraded(QobuzSource.SOURCE_ID) }
}

@Test
fun `healthy url returns result and does not record degraded`() = runTest {
    coEvery { apiClient.getFileUrl(any(), any()) } returns
        QobuzDownloadData(url = "https://cdn/file?fmt=27&etsp=9999999999")
    assertThat(source().resolve(query)).isNotNull()
    coVerify(exactly = 0) { healthGate.recordDegraded(any()) }
}
```

> Reuse `QobuzSourceTest`'s existing query/track fixtures and `stubLimiterReady()` helper rather than re-deriving stubs.

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew :data:download:testDebugUnitTest --tests "com.stash.data.download.lossless.qobuz.QobuzSourceTest"`
Expected: FAIL — ctor arity mismatch / `healthGate` unresolved.

- [ ] **Step 3: Write minimal implementation**

Add ctor params (`QobuzSource.kt:40-45`, after `losslessPrefs`):

```kotlin
    private val urlInspector: com.stash.data.download.lossless.LosslessUrlInspector,
    private val healthGate: com.stash.data.download.lossless.LosslessSourceHealthGate,
```

(Add the two imports at top of file.)

Replace the empty-URL guard (`QobuzSource.kt:176-179`):

```kotlin
        if (download.url.isNullOrEmpty()) {
            Log.d(TAG, "download-music returned empty url for ${best.first.id}")
            return null
        }
        if (urlInspector.isDegraded(download.url, requestedQuality)) {
            Log.w(
                TAG,
                "degraded url for ${best.first.id} (sample/downgrade) — failing over; " +
                    "url=${download.url.take(80)}",
            )
            healthGate.recordDegraded(id)
            return null
        }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew :data:download:testDebugUnitTest --tests "com.stash.data.download.lossless.qobuz.QobuzSourceTest"`
Expected: PASS (existing tests + 2 new).

- [ ] **Step 5: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/lossless/qobuz/QobuzSource.kt \
        data/download/src/test/kotlin/com/stash/data/download/lossless/qobuz/QobuzSourceTest.kt
git commit -m "feat(lossless): reject degraded URLs in QobuzSource, record cooldown"
```

---

### Task 5: Skip a degraded source in the download registry

**Files:**
- Modify: `data\download\src\main\kotlin\com\stash\data\download\lossless\LosslessSourceRegistry.kt:24-27` (ctor) and `:40-41` (skip loop)
- Test: `data\download\src\test\kotlin\com\stash\data\download\lossless\LosslessSourceRegistryTest.kt` (new)

- [ ] **Step 1: Write the failing test**

```kotlin
package com.stash.data.download.lossless

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LosslessSourceRegistryTest {

    private val prefs: LosslessSourcePreferences = mockk()
    private val healthGate: LosslessSourceHealthGate = mockk(relaxUnitFun = true)

    private fun fakeSource(srcId: String, result: SourceResult?): LosslessSource =
        mockk(relaxed = true) {
            coEvery { id } returns srcId
            coEvery { isEnabled() } returns true
            coEvery { resolve(any()) } returns result
            coEvery { rateLimitState() } returns RateLimitState(false, 0, null)
        }

    private val query = TrackQuery(artist = "A", title = "B")

    private fun acceptAnyQuality() {
        coEvery { prefs.priorityOrderNow() } returns emptyList()
        coEvery { prefs.minQualityNow() } returns mockk {
            coEvery { accepts(any()) } returns true
        }
    }

    @Test
    fun `degraded source is skipped without resolving and the next source wins`() = runTest {
        acceptAnyQuality()
        val kennyy = fakeSource("kennyy_qobuz", mockk(relaxed = true) {
            coEvery { format } returns AudioFormat("flac", 0, 44100, 16)
        })
        val squidResult: SourceResult = mockk(relaxed = true) {
            coEvery { format } returns AudioFormat("flac", 0, 44100, 16)
        }
        val squid = fakeSource("squid_qobuz", squidResult)
        coEvery { healthGate.isDegraded("kennyy_qobuz") } returns true
        coEvery { healthGate.isDegraded("squid_qobuz") } returns false

        val registry = LosslessSourceRegistry(setOf(kennyy, squid), prefs, healthGate)
        val result = registry.resolve(query)

        assertThat(result).isEqualTo(squidResult)
        coVerify(exactly = 0) { kennyy.resolve(any()) } // skipped before resolving
        coVerify(exactly = 1) { squid.resolve(any()) }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew :data:download:testDebugUnitTest --tests "com.stash.data.download.lossless.LosslessSourceRegistryTest"`
Expected: FAIL — ctor doesn't accept `healthGate`.

- [ ] **Step 3: Write minimal implementation**

Add ctor dep (`LosslessSourceRegistry.kt:24-27`):

```kotlin
class LosslessSourceRegistry @Inject constructor(
    private val sources: Set<@JvmSuppressWildcards LosslessSource>,
    private val prefs: LosslessSourcePreferences,
    private val healthGate: LosslessSourceHealthGate,
) {
```

Add the skip at the top of the resolve loop (`LosslessSourceRegistry.kt:40`, before `if (!source.isEnabled())`):

```kotlin
        for (source in ordered) {
            if (healthGate.isDegraded(source.id)) {
                Log.d(TAG, "skipping ${source.id}: degraded (content-health cooldown)")
                continue
            }
            if (!source.isEnabled()) continue
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew :data:download:testDebugUnitTest --tests "com.stash.data.download.lossless.LosslessSourceRegistryTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/lossless/LosslessSourceRegistry.kt \
        data/download/src/test/kotlin/com/stash/data/download/lossless/LosslessSourceRegistryTest.kt
git commit -m "feat(lossless): skip degraded sources in download registry"
```

---

### Task 6: Skip a degraded source in the streaming resolvers

**Files:**
- Modify: `core\media\…\streaming\KennyyStreamResolver.kt:85-93` (ctor + early skip)
- Modify: `core\media\…\streaming\QobuzStreamResolver.kt:30-38` (ctor + early skip)
- Test: extend `core\media\src\test\…\streaming\KennyyStreamResolverTest.kt`; create `QobuzStreamResolverTest.kt`

> **Why here, not the streaming registry:** `StreamSourceRegistry` hard-codes `resolver::resolve` refs and has no per-source `isEnabled` loop — each resolver self-gates. So the gate check belongs inside each resolver, mirroring the existing `healthMonitor.isHealthy` / `isEnabledForStreaming` self-gates.

- [ ] **Step 1: Write the failing tests**

Add to `KennyyStreamResolverTest.kt`:

```kotlin
@Test
fun `returns null without resolving when health gate reports degraded`() = runTest {
    coEvery { healthGate.isDegraded(KennyySource.SOURCE_ID) } returns true
    // healthMonitor stubbed healthy so we prove the GATE is what blocks it
    val result = resolver.resolve(track)
    assertThat(result).isNull()
    coVerify(exactly = 0) { source.resolveImmediate(any()) }
}
```

…and add `healthGate` to that test's setup (a `mockk(relaxed = true)` with `isDegraded(any())` defaulting to `false`, passed into the resolver ctor; default existing tests to not-degraded).

Create `QobuzStreamResolverTest.kt` with the analogous degraded-skip test plus a healthy pass-through test (gate not degraded + `isEnabledForStreaming()` true + a `range`-free etsp URL → non-null).

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew :core:media:testDebugUnitTest --tests "com.stash.core.media.streaming.KennyyStreamResolverTest" --tests "com.stash.core.media.streaming.QobuzStreamResolverTest"`
Expected: FAIL — resolver ctors don't accept `healthGate`.

- [ ] **Step 3: Write minimal implementation**

`KennyyStreamResolver.kt` — add dep and skip:

```kotlin
@Singleton
class KennyyStreamResolver @Inject constructor(
    private val source: KennyySource,
    private val healthMonitor: KennyyHealthMonitor,
    private val healthGate: com.stash.data.download.lossless.LosslessSourceHealthGate,
) {
    suspend fun resolve(track: TrackEntity): StreamUrl? {
        if (healthGate.isDegraded(KennyySource.SOURCE_ID)) {
            Log.d(TAG, "skip id=${track.id} (kennyy content-degraded)")
            return null
        }
        if (!healthMonitor.isHealthy.value) {
            Log.d(TAG, "skip id=${track.id} (kennyy unhealthy)")
            return null
        }
        // …unchanged…
```

`QobuzStreamResolver.kt` — add dep and skip:

```kotlin
@Singleton
class QobuzStreamResolver @Inject constructor(
    private val source: QobuzSource,
    private val healthGate: com.stash.data.download.lossless.LosslessSourceHealthGate,
) {
    suspend fun resolve(track: TrackEntity): StreamUrl? {
        if (healthGate.isDegraded(QobuzSource.SOURCE_ID)) {
            Log.d(TAG, "skip id=${track.id} (squid content-degraded)")
            return null
        }
        Log.d(TAG, "resolve attempt id=${track.id} title='${track.title}'")
        if (!source.isEnabledForStreaming()) {
            // …unchanged…
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew :core:media:testDebugUnitTest --tests "com.stash.core.media.streaming.KennyyStreamResolverTest" --tests "com.stash.core.media.streaming.QobuzStreamResolverTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/streaming/KennyyStreamResolver.kt \
        core/media/src/main/kotlin/com/stash/core/media/streaming/QobuzStreamResolver.kt \
        core/media/src/test/kotlin/com/stash/core/media/streaming/KennyyStreamResolverTest.kt \
        core/media/src/test/kotlin/com/stash/core/media/streaming/QobuzStreamResolverTest.kt
git commit -m "feat(lossless): skip content-degraded sources in stream resolvers"
```

---

### Task 7: Duration backstop in the download path

Catches a degraded download whose URL lacked the `range=` marker. After a finished lossless fetch, if the probed duration is sample-length (far short of the track's known duration), reject the file, record the source degraded, and re-resolve — the now-gated source is skipped, so the registry advances to the next lossless source (or falls through to yt-dlp).

**Files:**
- Modify: `data\download\…\DownloadManager.kt:338-380` (`tryLosslessDownload` resolve+fetch+probe loop). Add `AudioDurationExtractor` and `LosslessSourceHealthGate` deps if not already injected (verify the existing ctor; `loudnessMeasurer`/`trackFinalizer` are already there — `healthGate` and an audio extractor are the likely additions).
- Test: extend `DownloadManager`'s existing test (find it under `data\download\src\test\…`) — if `tryLosslessDownload` has no isolated test seam, add a focused unit test around a small extracted helper `isDurationDegraded(probedMs, expectedMs)`.

> **Implementer:** read `DownloadManager`'s constructor and existing tests first. Prefer extracting the comparison into a **pure** helper so it's trivially testable, then call it from the loop. Keep the loop bounded (`maxAttempts = orderedSources count`, or a constant like 3) so a pathologically degrading pair can't spin.

- [ ] **Step 1: Write the failing test (pure helper)**

```kotlin
// In DownloadManager test (or a small DurationBackstop unit if extracted):
@Test fun `30s file against 4min expected is degraded`() {
    assertThat(isDurationDegraded(probedMs = 30_000L, expectedMs = 240_000L)).isTrue()
}
@Test fun `full-length file is not degraded`() {
    assertThat(isDurationDegraded(probedMs = 238_000L, expectedMs = 240_000L)).isFalse()
}
@Test fun `unknown expected duration is never degraded`() {
    assertThat(isDurationDegraded(probedMs = 30_000L, expectedMs = 0L)).isFalse()
}
@Test fun `unreadable probe is not degraded`() {
    assertThat(isDurationDegraded(probedMs = null, expectedMs = 240_000L)).isFalse()
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew :data:download:testDebugUnitTest --tests "*DownloadManager*"`
Expected: FAIL — `isDurationDegraded` unresolved.

- [ ] **Step 3: Write minimal implementation**

Add the pure helper (top-level or private in `DownloadManager`, but visible to the test — `internal`):

```kotlin
/**
 * True when a finished lossless download is a preview stub: a readable
 * duration that is both absolutely short (≤ [SAMPLE_MAX_MS]) AND far below
 * the track's known duration (< [SAMPLE_FRACTION] of expected). Requires a
 * known expected duration (> 0) and a readable probe; otherwise returns
 * false (never reject on missing data).
 */
internal fun isDurationDegraded(probedMs: Long?, expectedMs: Long): Boolean {
    if (probedMs == null || probedMs <= 0L) return false
    if (expectedMs <= 0L) return false
    return probedMs <= SAMPLE_MAX_MS && probedMs < expectedMs * SAMPLE_FRACTION
}
// companion: const val SAMPLE_MAX_MS = 35_000L; const val SAMPLE_FRACTION = 0.5
```

Then wrap the resolve→download→probe in a bounded loop in `tryLosslessDownload`. After `losslessUrlDownloader.download(...)` succeeds and before `trackFinalizer.finalizeFile`, probe and back-stop:

```kotlin
val probedMs = audioExtractor.extractMs(fetched.absolutePath)
if (isDurationDegraded(probedMs, track.durationMs)) {
    Log.w(TAG, "duration backstop: ${match.sourceId} returned ${probedMs}ms vs " +
        "expected ${track.durationMs}ms — rejecting + failing over")
    healthGate.recordDegraded(match.sourceId)
    runCatching { fetched.delete() }
    continue // re-resolve: registry now skips the degraded source
}
```

The surrounding `repeat(maxAttempts)` / labeled loop re-calls `losslessRegistry.resolve(query)` each iteration; exhausting attempts returns `null` (existing yt-dlp fallthrough).

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew :data:download:testDebugUnitTest --tests "*DownloadManager*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/DownloadManager.kt \
        data/download/src/test/kotlin/com/stash/data/download/DownloadManagerTest.kt
git commit -m "feat(lossless): duration backstop rejects preview-stub downloads + fails over"
```

---

### Task 8: Full build, module test sweep, on-device verify

**Files:** none (verification only)

- [ ] **Step 1: Full unit-test sweep for both touched modules**

Run:
```
.\gradlew :data:download:testDebugUnitTest :core:media:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL, no regressions (watch for the pre-existing red DAO test noted in memory — confirm it's unrelated).

- [ ] **Step 2: Assemble + install the debug APK (per project rule — compile-pass is not enough)**

Run:
```
.\gradlew :app:installDebug
```
Expected: BUILD SUCCESSFUL; app installs on the connected device.

- [ ] **Step 3: On-device degradation behavior check (drive adb yourself)**

- Confirm normal FLAC streaming + download still works on a known-good track (no false-positive rejection of healthy `fmt=27` URLs).
- In logcat, confirm the new log lines appear only on genuinely degraded URLs:
  `adb logcat -s KennyySource QobuzSource LosslessRegistry KennyyStreamResolver QobuzStreamResolver`
  Look for `degraded url … failing over` and `skipping … degraded (content-health cooldown)`.
- If a live degraded source is available, confirm streaming fails over (kennyy → squid → youtube) instead of playing ~30s then stopping, and that a degraded source is skipped for the cooldown then retried after it.

- [ ] **Step 4: Commit any device-verification notes / final touch-ups**

```bash
git commit --allow-empty -m "test(lossless): on-device degradation failover verified"
```

---

## Definition of Done

- [ ] `LosslessUrlInspector` + `LosslessSourceHealthGate` exist with green unit tests.
- [ ] Both sources reject degraded URLs and record the source degraded.
- [ ] Download registry **and** both stream resolvers skip a degraded source.
- [ ] Duration backstop rejects preview stubs on the download path and fails over.
- [ ] `:data:download` and `:core:media` unit tests green; no new regressions.
- [ ] `:app:installDebug` succeeds and on-device FLAC streaming/downloading unaffected for healthy sources; failover observed for degraded ones.
- [ ] Healthy `fmt=27`/`fmt=7`/`fmt=6` FLAC URLs are never rejected (no false positives).

## Out of scope (separate specs)

- Adding new independent lossless sources (the "never-down-on-FLAC" roadmap #2).
- Active canary-track probing (passive recovery chosen — see design decision #1).
- Changing failover order or fixing upstream proxies.
