# Streaming Data Efficiency — Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split streaming FLAC quality from download quality so users can pick a separate tier per network (Wi-Fi vs Cellular), plus a Save Data master that forces the CD floor — cutting cellular data ~5× with no audible loss.

**Architecture:** Introduce a single `StreamQualityPolicy` chokepoint in `:core:media` that decides the streaming tier from network type + a new `StreamingQualityPreferences` store. Lift the quality decision out of `QobuzSource`/`KennyySource` by threading an optional `requestedQuality` into their resolve path; downloads keep their own tier untouched. Wire the two stream resolvers to ask the policy, and surface the controls in the existing Audio & Quality settings screen.

**Tech Stack:** Kotlin, Coroutines/Flow, Jetpack DataStore (Preferences), Hilt, Media3 (ExoPlayer), Jetpack Compose (Material 3), JUnit4 + MockK + kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-06-18-streaming-data-efficiency-design.md`

**Scope:** Phase 1 only (quality control). Phase 2 (self-metered cellular budget + YouTube fallback) is a separate later plan; the policy is shaped so the `ForceYouTube` branch can be added without rework.

---

## File Structure

**Create:**
- `data/download/src/main/kotlin/com/stash/data/download/prefs/StreamingQualityPreferences.kt` — new DataStore for `wifiTier`, `cellularTier`, `saveData`; owns the one-shot migration from the download tier.
- `data/download/src/test/kotlin/com/stash/data/download/prefs/StreamingQualityPreferencesTest.kt`
- `core/media/src/main/kotlin/com/stash/core/media/streaming/StreamQualityPolicy.kt` — decides the streaming tier.
- `core/media/src/test/kotlin/com/stash/core/media/streaming/StreamQualityPolicyTest.kt`

**Modify:**
- `data/download/src/main/kotlin/com/stash/data/download/lossless/qobuz/QobuzSource.kt` — thread `requestedQuality: Int?` through `resolveImmediate` / `resolveInternal`.
- `data/download/src/main/kotlin/com/stash/data/download/lossless/kennyy/KennyySource.kt` — same.
- `core/media/src/main/kotlin/com/stash/core/media/streaming/QobuzStreamResolver.kt` — inject policy, pass tier code.
- `core/media/src/main/kotlin/com/stash/core/media/streaming/KennyyStreamResolver.kt` — same.
- `app/src/main/kotlin/com/stash/app/StashApplication.kt` — call the one-shot migration at startup.
- `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsUiState.kt` — add three fields.
- `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsViewModel.kt` — combine the three new flows + add setters.
- `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsAudioQualityScreen.kt` — re-label the lossless picker "Download quality" and add the "Streaming" block.

**Module-boundary note:** `LosslessQualityTier` lives in `:data:download`. `:core:data`'s `StreamingPreference` therefore *cannot* hold typed tiers. `:core:media` and `:feature:settings` both already depend on `:data:download`, so the new `StreamingQualityPreferences` is placed there and both consumers can use it directly.

---

## Task 1: StreamingQualityPreferences (new DataStore + migration)

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/prefs/StreamingQualityPreferences.kt`
- Test: `data/download/src/test/kotlin/com/stash/data/download/prefs/StreamingQualityPreferencesTest.kt`

Model this on the existing `data/download/src/main/kotlin/com/stash/data/download/prefs/QualityPreferencesManager.kt` (DataStore extension property + `@Singleton` + `@Inject`). Use `LosslessQualityTier` directly (same module).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.stash.data.download.prefs

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.data.download.lossless.LosslessQualityTier
import com.stash.data.download.lossless.LosslessSourcePreferences
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class StreamingQualityPreferencesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // The extension-property DataStore is a single shared instance for the
    // whole test process — delete its backing file before each test so
    // writes from one case don't bleed into another (the established
    // :data:download pattern, e.g. LosslessSourcePreferences tests).
    @Before fun clean() {
        File(context.filesDir, "datastore/streaming_quality_preferences.preferences_pb").delete()
    }

    private fun newPrefs(downloadTier: LosslessQualityTier): StreamingQualityPreferences {
        val lossless = mockk<LosslessSourcePreferences>()
        coEvery { lossless.qualityTierNow() } returns downloadTier
        return StreamingQualityPreferences(context, lossless)
    }

    @Test
    fun `defaults before migration`() = runTest {
        val prefs = newPrefs(LosslessQualityTier.MAX)
        assertThat(prefs.wifiTier.first()).isEqualTo(LosslessQualityTier.HI_RES)
        assertThat(prefs.cellularTier.first()).isEqualTo(LosslessQualityTier.CD)
        assertThat(prefs.saveData.first()).isFalse()
    }

    @Test
    fun `migrateIfNeeded seeds wifi from download tier and cellular to CD`() = runTest {
        val prefs = newPrefs(LosslessQualityTier.MAX)
        prefs.migrateIfNeeded()
        assertThat(prefs.wifiTier.first()).isEqualTo(LosslessQualityTier.MAX)
        assertThat(prefs.cellularTier.first()).isEqualTo(LosslessQualityTier.CD)
    }

    @Test
    fun `migrateIfNeeded is a no-op once a tier is explicitly set`() = runTest {
        val prefs = newPrefs(LosslessQualityTier.MAX)
        prefs.setWifiTier(LosslessQualityTier.CD)
        prefs.migrateIfNeeded() // download tier is MAX, but user already chose CD
        assertThat(prefs.wifiTier.first()).isEqualTo(LosslessQualityTier.CD)
    }

    @Test
    fun `setters round-trip`() = runTest {
        val prefs = newPrefs(LosslessQualityTier.HI_RES)
        prefs.setCellularTier(LosslessQualityTier.HI_RES)
        prefs.setSaveData(true)
        assertThat(prefs.cellularTier.first()).isEqualTo(LosslessQualityTier.HI_RES)
        assertThat(prefs.saveData.first()).isTrue()
    }
}
```

> Note: the extension-property DataStore is process-wide, so the `@Before` file-delete above is **required** — without it the write-then-read tests are order-dependent and flaky. This is the same isolation pattern the existing `LosslessSourcePreferences` tests in this module use (delete the `.preferences_pb` file in setup), not a temp-file helper.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :data:download:testDebugUnitTest --tests "com.stash.data.download.prefs.StreamingQualityPreferencesTest" --no-daemon`
Expected: FAIL — `StreamingQualityPreferences` unresolved.

- [ ] **Step 3: Implement `StreamingQualityPreferences`**

```kotlin
package com.stash.data.download.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stash.data.download.lossless.LosslessQualityTier
import com.stash.data.download.lossless.LosslessSourcePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.streamingQualityDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "streaming_quality_preferences",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Streaming-specific lossless quality, split from the download tier.
 *
 *  - [wifiTier] / [cellularTier] — tier requested when streaming on the
 *    respective transport. Cellular defaults to CD (the data-saving floor).
 *  - [saveData] — master override; when true, callers should force CD on
 *    every network. The override itself lives in [StreamQualityPolicy].
 *
 * Lives in :data:download (not :core:data's StreamingPreference) because
 * [LosslessQualityTier] is defined here and :core:data cannot depend on
 * this module.
 */
@Singleton
class StreamingQualityPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
    private val losslessPrefs: LosslessSourcePreferences,
) {
    private val wifiKey = stringPreferencesKey("streaming_wifi_tier")
    private val cellularKey = stringPreferencesKey("streaming_cellular_tier")
    private val saveDataKey = booleanPreferencesKey("streaming_save_data")

    val wifiTier: Flow<LosslessQualityTier> = context.streamingQualityDataStore.data.map { prefs ->
        prefs[wifiKey]?.let { runCatching { LosslessQualityTier.valueOf(it) }.getOrNull() }
            ?: LosslessQualityTier.HI_RES
    }

    val cellularTier: Flow<LosslessQualityTier> = context.streamingQualityDataStore.data.map { prefs ->
        prefs[cellularKey]?.let { runCatching { LosslessQualityTier.valueOf(it) }.getOrNull() }
            ?: LosslessQualityTier.CD
    }

    val saveData: Flow<Boolean> = context.streamingQualityDataStore.data.map { prefs ->
        prefs[saveDataKey] ?: false
    }

    suspend fun wifiTierNow(): LosslessQualityTier = wifiTier.first()
    suspend fun cellularTierNow(): LosslessQualityTier = cellularTier.first()
    suspend fun saveDataNow(): Boolean = saveData.first()

    suspend fun setWifiTier(tier: LosslessQualityTier) {
        context.streamingQualityDataStore.edit { it[wifiKey] = tier.name }
    }

    suspend fun setCellularTier(tier: LosslessQualityTier) {
        context.streamingQualityDataStore.edit { it[cellularKey] = tier.name }
    }

    suspend fun setSaveData(value: Boolean) {
        context.streamingQualityDataStore.edit { it[saveDataKey] = value }
    }

    /**
     * One-shot migration: when no streaming Wi-Fi tier has been written
     * yet, seed it from the user's current download tier (so Wi-Fi keeps
     * what they had) and leave cellular at its CD default. Idempotent —
     * a present key short-circuits. Call once at startup.
     */
    suspend fun migrateIfNeeded() {
        val already = context.streamingQualityDataStore.data.first()[wifiKey] != null
        if (already) return
        val downloadTier = losslessPrefs.qualityTierNow()
        context.streamingQualityDataStore.edit { prefs ->
            prefs[wifiKey] = downloadTier.name
            if (prefs[cellularKey] == null) prefs[cellularKey] = LosslessQualityTier.CD.name
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :data:download:testDebugUnitTest --tests "com.stash.data.download.prefs.StreamingQualityPreferencesTest" --no-daemon`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/prefs/StreamingQualityPreferences.kt \
        data/download/src/test/kotlin/com/stash/data/download/prefs/StreamingQualityPreferencesTest.kt
git commit -m "feat(streaming): StreamingQualityPreferences (per-network tiers + save-data) with download-tier migration"
```

---

## Task 2: Thread requestedQuality through QobuzSource

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/lossless/qobuz/QobuzSource.kt`
- Test: `data/download/src/test/kotlin/com/stash/data/download/lossless/qobuz/QobuzSourceTest.kt`

Goal: `resolveImmediate` accepts an optional explicit quality; downloads (`resolve`) keep reading the download pref. Behavior-preserving when no quality is passed.

- [ ] **Step 1: Write the failing test** (append to `QobuzSourceTest`)

```kotlin
@Test fun `resolveImmediate with explicit quality requests that format_id`() = runTest {
    // Search returns a candidate (reuse the existing happy-path search stub helper).
    stubSearchReturnsTrack(id = 1L)
    coEvery { apiClient.getFileUrl(1L, any(), any()) } returns download()
    // Download tier pref is MAX; explicit request is CD (6) — explicit must win.
    coEvery { losslessPrefs.qualityTierNow() } returns LosslessQualityTier.MAX

    source.resolveImmediate(query(), requestedQuality = QobuzQuality.FLAC_CD)

    coVerify { apiClient.getFileUrl(1L, QobuzQuality.FLAC_CD, any()) }
}

@Test fun `resolveImmediate without quality falls back to download tier`() = runTest {
    stubSearchReturnsTrack(id = 1L)
    coEvery { apiClient.getFileUrl(1L, any(), any()) } returns download()
    coEvery { losslessPrefs.qualityTierNow() } returns LosslessQualityTier.MAX // → 27

    source.resolveImmediate(query()) // no quality

    coVerify { apiClient.getFileUrl(1L, QobuzQuality.FLAC_HIRES_192, any()) }
}
```

> Use the existing test's helpers/fixtures (`download()`, `query()`, the search stub). If `stubSearchReturnsTrack` doesn't exist, inline the same `apiClient.search(...)` stub the existing happy-path test uses. `QobuzQuality.FLAC_CD == 6`, `FLAC_HIRES_192 == 27` — confirm constant names in `QobuzModels.kt`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :data:download:testDebugUnitTest --tests "com.stash.data.download.lossless.qobuz.QobuzSourceTest" --no-daemon`
Expected: FAIL — `resolveImmediate` has no `requestedQuality` parameter.

- [ ] **Step 3: Implement the threading**

In `QobuzSource.kt`:

```kotlin
// resolveImmediate gains an optional explicit quality.
suspend fun resolveImmediate(
    query: TrackQuery,
    requestedQuality: Int? = null,
): SourceResult? =
    resolveInternal(query, bypassRateLimit = true, requestedQuality = requestedQuality)

// resolve() (download) is unchanged in behavior — passes null → uses download pref.
override suspend fun resolve(query: TrackQuery): SourceResult? =
    resolveInternal(query, bypassRateLimit = false, requestedQuality = null)
```

Change `resolveInternal`'s signature and the quality resolution line:

```kotlin
private suspend fun resolveInternal(
    query: TrackQuery,
    bypassRateLimit: Boolean,
    requestedQuality: Int?,
): SourceResult? {
    // ... unchanged search/scoring ...

    // 3. Resolve to a signed download URL.
    val requestedQualityCode = requestedQuality ?: losslessPrefs.qualityTierNow().qobuzCode
    Log.d(TAG, "squid_qobuz: requested quality=$requestedQualityCode")
    val download = callLimited(bypassRateLimit) {
        apiClient.getFileUrl(best.first.id, requestedQualityCode)
    } ?: return null
    // ... replace every remaining use of `requestedQuality` (the old local)
    //     with `requestedQualityCode`: urlInspector.isDegraded(download.url,
    //     requestedQualityCode) and the codec mapping
    //     `if (requestedQualityCode == QobuzQuality.MP3_320) "mp3" else "flac"`.
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :data:download:testDebugUnitTest --tests "com.stash.data.download.lossless.qobuz.QobuzSourceTest" --no-daemon`
Expected: PASS — new tests green AND all pre-existing `QobuzSourceTest` cases still green (download path unchanged).

- [ ] **Step 5: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/lossless/qobuz/QobuzSource.kt \
        data/download/src/test/kotlin/com/stash/data/download/lossless/qobuz/QobuzSourceTest.kt
git commit -m "refactor(lossless): thread optional requestedQuality through QobuzSource.resolveImmediate"
```

---

## Task 3: Thread requestedQuality through KennyySource

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/lossless/kennyy/KennyySource.kt`
- Test: `data/download/src/test/kotlin/com/stash/data/download/lossless/kennyy/KennyySourceTest.kt`

Mirror Task 2 exactly. `KennyySource.kt:136-140` is the same `tier = losslessPrefs.qualityTierNow(); requestedQuality = tier.qobuzCode; apiClient.getFileUrl(best.first.id, requestedQuality)` shape.

- [ ] **Step 1: Write the failing tests** — same two cases as Task 2, adapted to `KennyySourceTest` fixtures. **Critical arity difference:** `KennyyApiClient.getFileUrl(id, quality)` takes **2 args** (no `tokenCountry`), so the stub/verify must be 2-arg, e.g. `coEvery { apiClient.getFileUrl(42L, any()) } returns ...` and `coVerify { apiClient.getFileUrl(42L, QobuzQuality.FLAC_CD) }`. Do not copy Task 2's 3-arg form.
- [ ] **Step 2: Run → FAIL**

Run: `./gradlew :data:download:testDebugUnitTest --tests "com.stash.data.download.lossless.kennyy.KennyySourceTest" --no-daemon`

- [ ] **Step 3: Implement** the same `resolveImmediate(query, requestedQuality: Int? = null)` + `resolveInternal(..., requestedQuality: Int?)` threading; `val requestedQualityCode = requestedQuality ?: losslessPrefs.qualityTierNow().qobuzCode`.
- [ ] **Step 4: Run → PASS** (new + pre-existing).
- [ ] **Step 5: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/lossless/kennyy/KennyySource.kt \
        data/download/src/test/kotlin/com/stash/data/download/lossless/kennyy/KennyySourceTest.kt
git commit -m "refactor(lossless): thread optional requestedQuality through KennyySource.resolveImmediate"
```

---

## Task 4: StreamQualityPolicy (the chokepoint)

**Files:**
- Create: `core/media/src/main/kotlin/com/stash/core/media/streaming/StreamQualityPolicy.kt`
- Test: `core/media/src/test/kotlin/com/stash/core/media/streaming/StreamQualityPolicyTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import com.stash.data.download.lossless.LosslessQualityTier
import com.stash.data.download.prefs.StreamingQualityPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class StreamQualityPolicyTest {

    private val connectivity = mockk<ConnectivityMonitor>()
    private val prefs = mockk<StreamingQualityPreferences>()
    private val policy = StreamQualityPolicy(connectivity, prefs)

    private fun setup(
        cellular: Boolean,
        wifi: LosslessQualityTier = LosslessQualityTier.MAX,
        cell: LosslessQualityTier = LosslessQualityTier.CD,
        saveData: Boolean = false,
    ) {
        every { connectivity.isCellular() } returns cellular
        coEvery { prefs.wifiTierNow() } returns wifi
        coEvery { prefs.cellularTierNow() } returns cell
        coEvery { prefs.saveDataNow() } returns saveData
    }

    @Test fun `wifi uses wifi tier`() = runTest {
        setup(cellular = false, wifi = LosslessQualityTier.MAX)
        assertThat(policy.streamingTier()).isEqualTo(LosslessQualityTier.MAX)
    }

    @Test fun `cellular uses cellular tier`() = runTest {
        setup(cellular = true, cell = LosslessQualityTier.CD)
        assertThat(policy.streamingTier()).isEqualTo(LosslessQualityTier.CD)
    }

    @Test fun `save data forces CD on wifi`() = runTest {
        setup(cellular = false, wifi = LosslessQualityTier.MAX, saveData = true)
        assertThat(policy.streamingTier()).isEqualTo(LosslessQualityTier.CD)
    }

    @Test fun `save data forces CD on cellular`() = runTest {
        setup(cellular = true, cell = LosslessQualityTier.HI_RES, saveData = true)
        assertThat(policy.streamingTier()).isEqualTo(LosslessQualityTier.CD)
    }
}
```

- [ ] **Step 2: Run → FAIL**

Run: `./gradlew :core:media:testDebugUnitTest --tests "com.stash.core.media.streaming.StreamQualityPolicyTest" --no-daemon`
Expected: FAIL — `StreamQualityPolicy` unresolved.

- [ ] **Step 3: Implement**

```kotlin
package com.stash.core.media.streaming

import com.stash.data.download.lossless.LosslessQualityTier
import com.stash.data.download.prefs.StreamingQualityPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single decision point for "what lossless tier should a streaming
 * resolve request right now?". Streaming resolvers call this instead of
 * reading the download tier; downloads never call it.
 *
 * Phase 1 returns just a tier. Phase 2 will widen the return to a
 * StreamDecision (Tier | ForceYouTube) for the cellular budget without
 * changing this class's callers' shape beyond the new branch.
 */
@Singleton
class StreamQualityPolicy @Inject constructor(
    private val connectivity: ConnectivityMonitor,
    private val prefs: StreamingQualityPreferences,
) {
    suspend fun streamingTier(): LosslessQualityTier {
        if (prefs.saveDataNow()) return LosslessQualityTier.CD
        return if (connectivity.isCellular()) prefs.cellularTierNow() else prefs.wifiTierNow()
    }
}
```

- [ ] **Step 4: Run → PASS**

Run: `./gradlew :core:media:testDebugUnitTest --tests "com.stash.core.media.streaming.StreamQualityPolicyTest" --no-daemon`

- [ ] **Step 5: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/streaming/StreamQualityPolicy.kt \
        core/media/src/test/kotlin/com/stash/core/media/streaming/StreamQualityPolicyTest.kt
git commit -m "feat(streaming): StreamQualityPolicy decides per-network streaming tier"
```

---

## Task 5: Wire the policy into both stream resolvers

**Files:**
- Modify: `core/media/src/main/kotlin/com/stash/core/media/streaming/QobuzStreamResolver.kt`
- Modify: `core/media/src/main/kotlin/com/stash/core/media/streaming/KennyyStreamResolver.kt`
- Test: `core/media/src/test/kotlin/com/stash/core/media/streaming/QobuzStreamResolverTest.kt`, `KennyyStreamResolverTest.kt`

- [ ] **Step 1: Write the failing test** (Qobuz; mirror for Kennyy)

Add a `StreamQualityPolicy` mock to the existing resolver test setup and assert the resolved quality reaches `source.resolveImmediate`:

```kotlin
@Test fun `passes the policy tier code to resolveImmediate`() = runTest {
    coEvery { policy.streamingTier() } returns LosslessQualityTier.CD
    coEvery { source.isEnabledForStreaming() } returns true
    coEvery { healthGate.isDegraded(any()) } returns false
    coEvery { source.resolveImmediate(any(), any()) } returns stubSourceResult(
        downloadUrl = "https://cdn/x.flac?etsp=9999999999",
    )

    resolver.resolve(track())

    coVerify { source.resolveImmediate(any(), QobuzQuality.FLAC_CD) }
}
```

> Update the existing `coEvery { source.resolveImmediate(any()) }` stubs in these test files to `resolveImmediate(any(), any())` since the call site now passes a second arg.

- [ ] **Step 2: Run → FAIL**

Run: `./gradlew :core:media:testDebugUnitTest --tests "com.stash.core.media.streaming.QobuzStreamResolverTest" --no-daemon`

- [ ] **Step 3: Implement**

`QobuzStreamResolver` — add the policy to the constructor and pass the code:

```kotlin
@Singleton
class QobuzStreamResolver @Inject constructor(
    private val source: QobuzSource,
    private val healthGate: LosslessSourceHealthGate,
    private val qualityPolicy: StreamQualityPolicy,
) {
    suspend fun resolve(track: TrackEntity): StreamUrl? {
        // ... unchanged degraded / isEnabledForStreaming guards + query build ...
        val requestedQuality = qualityPolicy.streamingTier().qobuzCode
        val result = source.resolveImmediate(query, requestedQuality) ?: run {
            Log.d(TAG, "no_result id=${track.id}")
            return null
        }
        // ... unchanged etsp parse + StreamUrl build ...
    }
}
```

`KennyyStreamResolver` — same: add `qualityPolicy`, compute `requestedQuality` before the `withTimeout`, and call `source.resolveImmediate(query, requestedQuality)` inside it. Also delete the now-stale "v1 note" KDoc paragraph (lines 78-83) about streaming quality following the download preference.

- [ ] **Step 4: Run → PASS** (both resolver test classes)

Run: `./gradlew :core:media:testDebugUnitTest --tests "com.stash.core.media.streaming.QobuzStreamResolverTest" --tests "com.stash.core.media.streaming.KennyyStreamResolverTest" --no-daemon`

- [ ] **Step 5: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/streaming/QobuzStreamResolver.kt \
        core/media/src/main/kotlin/com/stash/core/media/streaming/KennyyStreamResolver.kt \
        core/media/src/test/kotlin/com/stash/core/media/streaming/QobuzStreamResolverTest.kt \
        core/media/src/test/kotlin/com/stash/core/media/streaming/KennyyStreamResolverTest.kt
git commit -m "feat(streaming): resolvers request the per-network policy tier"
```

---

## Task 6: One-shot migration at startup

**Files:**
- Modify: `app/src/main/kotlin/com/stash/app/StashApplication.kt`

The migration must run once so existing users' Wi-Fi tier inherits their download tier. `StashApplication` already runs startup one-shots (e.g. `purgeAntraCredentials`, `purgeRetiredKeys`) — follow that exact pattern.

- [ ] **Step 1: Inject + call**

Find the existing startup coroutine in `StashApplication` that calls the `purge*` one-shots. Inject `StreamingQualityPreferences` (Hilt) and add:

```kotlin
streamingQualityPreferences.migrateIfNeeded()
```

next to the existing purge calls (same `applicationScope.launch { ... }` / dispatcher they use).

- [ ] **Step 2: Build to verify wiring**

Run: `./gradlew :app:compileDebugKotlin --no-daemon`
Expected: BUILD SUCCESSFUL (Hilt resolves the new dependency).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/stash/app/StashApplication.kt
git commit -m "feat(streaming): run streaming-tier migration once at startup"
```

> No unit test: this is a one-line wiring call into an existing startup block. The migration logic itself is covered by Task 1.

---

## Task 7: Settings state + ViewModel

**Files:**
- Modify: `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsUiState.kt`
- Modify: `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsViewModel.kt`

- [ ] **Step 1: Add UiState fields**

In `SettingsUiState`, add:

```kotlin
val streamingWifiTier: LosslessQualityTier = LosslessQualityTier.HI_RES,
val streamingCellularTier: LosslessQualityTier = LosslessQualityTier.CD,
val streamingSaveData: Boolean = false,
```

- [ ] **Step 2: Wire flows + setters in ViewModel**

Inject `StreamingQualityPreferences`. Add its three flows (`wifiTier`, `cellularTier`, `saveData`) to the big `combine(...)` in `uiState`, read them by index in the lambda, and set the new `SettingsUiState` fields. Add setter methods:

```kotlin
fun onStreamingWifiTierChanged(tier: LosslessQualityTier) =
    viewModelScope.launch { streamingQualityPrefs.setWifiTier(tier) }
fun onStreamingCellularTierChanged(tier: LosslessQualityTier) =
    viewModelScope.launch { streamingQualityPrefs.setCellularTier(tier) }
fun onStreamingSaveDataChanged(value: Boolean) =
    viewModelScope.launch { streamingQualityPrefs.setSaveData(value) }
```

> `combine` here is the vararg form (`values: Array<*>`), so adding three flows is mechanical — append them and add three indexed reads. Keep the index order consistent between the flow list and the destructuring.

- [ ] **Step 3: Build the module**

Run: `./gradlew :feature:settings:compileDebugKotlin --no-daemon`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsUiState.kt \
        feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsViewModel.kt
git commit -m "feat(settings): expose streaming per-network tiers + save-data in Settings state"
```

> If `SettingsViewModel` has an existing unit test that constructs the VM, update its constructor call / fakes to include `StreamingQualityPreferences` and keep it green: `./gradlew :feature:settings:testDebugUnitTest --no-daemon`.

---

## Task 8: Settings UI — Download relabel + Streaming block

**Files:**
- Modify: `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsAudioQualityScreen.kt`

- [ ] **Step 1: Re-label the existing lossless picker**

In the lossless card, change the `Text("Lossless quality")` heading to `Text("Download quality")`. (The picker still binds to `losslessQualityTier` / `onLosslessQualityTierChanged` — downloads unchanged.)

- [ ] **Step 2: Add the Streaming block**

After the lossless card (and only meaningful when `losslessEnabled`), add a new section using existing components (`SettingsSectionLabel`, `GlassCard`, `SettingsPickerRow`, `SettingsToggleRow`). Pattern:

```kotlin
SettingsSectionLabel("Streaming")
GlassCard {
    Column(Modifier.fillMaxWidth()) {
        Text("On Wi-Fi", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface)
        Column(Modifier.selectableGroup()) {
            listOf(LosslessQualityTier.MAX, LosslessQualityTier.HI_RES, LosslessQualityTier.CD).forEach { tier ->
                SettingsPickerRow(
                    selected = uiState.streamingWifiTier == tier,
                    title = tier.displayLabel,
                    subtitle = tier.sizeHint,
                    enabled = !uiState.streamingSaveData,           // greyed when Save Data on
                    onClick = { viewModel.onStreamingWifiTierChanged(tier) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("On cellular", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface)
        Column(Modifier.selectableGroup()) {
            listOf(LosslessQualityTier.MAX, LosslessQualityTier.HI_RES, LosslessQualityTier.CD).forEach { tier ->
                SettingsPickerRow(
                    selected = uiState.streamingCellularTier == tier,
                    title = tier.displayLabel,
                    subtitle = tier.sizeHint,
                    enabled = !uiState.streamingSaveData,
                    onClick = { viewModel.onStreamingCellularTierChanged(tier) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        SettingsToggleRow(
            title = "Save Data",
            subtitle = "Force CD quality on every network to minimize data.",
            checked = uiState.streamingSaveData,
            onCheckedChange = viewModel::onStreamingSaveDataChanged,
        )
    }
}
```

> Verify `SettingsPickerRow` actually has an `enabled` parameter; if not, add it (default `true`) and apply it to the row's `clickable`/alpha — or, if simpler, wrap the two pickers in an `AnimatedVisibility`/alpha that dims them when `streamingSaveData` is true. The requirement is only that Save Data visibly overrides the pickers.

- [ ] **Step 3: Build the module**

Run: `./gradlew :feature:settings:compileDebugKotlin --no-daemon`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsAudioQualityScreen.kt
git commit -m "feat(settings): Streaming quality block (Wi-Fi/Cellular tiers + Save Data) and Download relabel"
```

---

## Task 9: Full-module verification + on-device smoke test

- [ ] **Step 1: Compile the touched modules + run their unit tests**

Run:
```
./gradlew :data:download:testDebugUnitTest :core:media:testDebugUnitTest :feature:settings:compileDebugKotlin --no-daemon
```
Expected: BUILD SUCCESSFUL; all targeted tests green. (Skip the known-flaky `:core:media` network test by relying on the targeted `--tests` runs above for new classes if the full module test hangs — see prior sessions.)

- [ ] **Step 2: Install + on-device smoke test**

Run: `./gradlew :app:installDebug --no-daemon` (or `adb install -r` the built APK).
Manually verify on device:
- Settings → Audio & Quality shows **Download quality** + a **Streaming** block with Wi-Fi/Cellular pickers + Save Data.
- Set **Cellular = CD**, **Wi-Fi = Max**. On Wi-Fi, play a track → Now Playing shows the higher resolution; toggle the phone to cellular (or set Wi-Fi tier to CD to prove the picker takes effect) and confirm the requested `format_id` changes in logcat (`QobuzSource`/`KennyySource`: "requested quality=…").
- Toggle **Save Data ON** → pickers greyed; streaming logs show CD (6) regardless of the per-network selection.

- [ ] **Step 3: Final commit (if any smoke-test fixes)**

```bash
git add -A && git commit -m "fix(streaming): phase-1 on-device smoke-test fixes"
```

---

## Notes for the implementer

- **TDD:** every logic task writes the failing test first. UI/wiring tasks (6, 8) are verified by compile + the on-device smoke test in Task 9, since they're glue, not logic.
- **Don't touch downloads:** `resolve()` on both sources must keep passing `requestedQuality = null` so it reads the download tier. The pre-existing source tests are the guardrail — keep them green.
- **Don't create a second DataStore name collision:** the new store is `streaming_quality_preferences`, distinct from `:core:data`'s `streaming_preference`.
- **Phase 2 hooks (do NOT build now):** `StreamQualityPolicy` is where the budget's `ForceYouTube` branch and the `streamOnCellular` precedence will later live; leave it a clean single method.
