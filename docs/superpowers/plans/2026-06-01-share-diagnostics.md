# Share Diagnostics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An in-app "Share diagnostics" action that exports a redacted, human-readable diagnostics bundle (recent logs + app/device/auth/sync/queue state + crash reports) and shares it via the Android share sheet, after a preview screen.

**Architecture:** A `@Singleton LogcatCapture` tails the app's own logcat into a rotating cacheDir file (survives crashes). A `@Singleton DiagnosticsBundleBuilder` assembles a single `.txt` from existing DAOs/probes + crash files + the log tail, run through a pure `DiagnosticsRedactor`. A new `DiagnosticsPreviewScreen` (its own `@HiltViewModel`) shows the exact redacted text with Share/Copy/Back, reached from the existing Settings → Diagnostics card via a type-safe nav route. Reuses `CrashFileStore` (FileProvider + device-metadata header) and the existing `ACTION_SEND` pattern.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Hilt (constructor injection, no module needed), Room (Flows/suspend DAOs), kotlinx.serialization, type-safe Navigation Compose; tests = JUnit4 + MockK + Robolectric.

**Spec:** `docs/superpowers/specs/2026-06-01-share-diagnostics-design.md`

**Working dir:** all paths are under the worktree `C:/Users/theno/Projects/MP3APK/.worktrees/share-diagnostics`. Run `./gradlew` from there.

---

## File Structure

**New — `core/data/src/main/kotlin/com/stash/core/data/diagnostics/`:**
- `DiagnosticsRedactor.kt` — pure object; `redact(text): String` scrubs secrets. (Task 1)
- `LogcatCapture.kt` — `@Singleton`; tails own logcat → rotating file; `recentLogs(maxLines)`. (Task 3)
- `DiagnosticsBundleBuilder.kt` — `@Singleton`; `suspend fun build(): DiagnosticsBundle`. (Task 4)

**Modified:**
- `core/data/.../diagnostics/CrashFileStore.kt` — extract `internal fun deviceMetadataBlock()`. (Task 2)
- `app/src/main/kotlin/com/stash/app/StashApplication.kt` — start `LogcatCapture`. (Task 5)
- `app/src/main/res/xml/file_paths.xml` — add `diagnostics/` cache-path. (Task 6)
- `feature/settings/.../SettingsScreen.kt` — "Share diagnostics" button + nav callback. (Task 9)
- `app/.../navigation/TopLevelDestination.kt` + `StashNavHost.kt` — `DiagnosticsPreviewRoute`. (Task 9)

**New — `feature/settings/src/main/kotlin/com/stash/feature/settings/diagnostics/`:**
- `DiagnosticsPreviewViewModel.kt` — `@HiltViewModel`; builds the bundle, holds state. (Task 7)
- `DiagnosticsPreviewScreen.kt` — Compose preview + Share/Copy/Back. (Task 8)

**Tests (`core/data/src/test/.../diagnostics/`):** `DiagnosticsRedactorTest.kt`, `LogcatCaptureTest.kt`, `DiagnosticsBundleBuilderTest.kt`.

---

## Task 1: `DiagnosticsRedactor` (pure, foundational)

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/diagnostics/DiagnosticsRedactor.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/diagnostics/DiagnosticsRedactorTest.kt`

- [ ] **Step 1: Write the failing test** (JUnit4, plain — no Robolectric/MockK needed)

```kotlin
package com.stash.core.data.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsRedactorTest {
    @Test fun `strips spotify sp_dc cookie value`() {
        val out = DiagnosticsRedactor.redact("cookie: sp_dc=AQB1234secretValue; Path=/")
        assertFalse(out.contains("AQB1234secretValue"))
        assertTrue(out.contains("sp_dc=[REDACTED]"))
    }

    @Test fun `strips youtube google auth cookies`() {
        val out = DiagnosticsRedactor.redact("Cookie: SAPISID=abc123def; HSID=zzz999; SID=qqq111")
        listOf("abc123def", "zzz999", "qqq111").forEach { assertFalse(out.contains(it)) }
    }

    @Test fun `strips bearer and named tokens`() {
        val out = DiagnosticsRedactor.redact(
            "Authorization: Bearer ya29.A0AReallyLongToken\naccess_token=secretAccess refresh_token=\"secretRefresh\""
        )
        listOf("ya29.A0AReallyLongToken", "secretAccess", "secretRefresh").forEach {
            assertFalse(out.contains(it))
        }
    }

    @Test fun `strips email addresses`() {
        val out = DiagnosticsRedactor.redact("account: jane.doe+test@gmail.com synced")
        assertFalse(out.contains("jane.doe+test@gmail.com"))
        assertTrue(out.contains("[REDACTED_EMAIL]"))
    }

    @Test fun `leaves ordinary text and stack traces intact`() {
        val text = "java.lang.NoSuchMethodError at FFmpegBridge.kt:98\nrefreshing 13 Stash Mix(es)"
        assertTrue(DiagnosticsRedactor.redact(text) == text)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.diagnostics.DiagnosticsRedactorTest"`
Expected: FAIL — `DiagnosticsRedactor` unresolved.

- [ ] **Step 3: Implement**

```kotlin
package com.stash.core.data.diagnostics

/**
 * Scrubs secrets from a diagnostics bundle. Backstop only — the bundle reports
 * auth as booleans, never raw tokens — but error strings and log lines can embed
 * cookies/headers from upstream API failures. High-precision named patterns
 * (cookies, bearer/Authorization, named tokens) to avoid nuking stack traces.
 */
object DiagnosticsRedactor {
    private val patterns: List<Pair<Regex, String>> = listOf(
        // Spotify sp_dc + Google/YT auth cookies: keep the key, redact the value.
        Regex("""(?i)\b(sp_dc|SAPISID|APISID|HSID|SSID|SIDCC|SID|__Secure-[\w-]+|LOGIN_INFO)=[^;\s"']+""")
            to "$1=[REDACTED]",
        // Authorization / Bearer headers.
        Regex("""(?i)\bauthorization:\s*\S+""") to "Authorization: [REDACTED]",
        Regex("""(?i)\bbearer\s+[A-Za-z0-9._\-]+""") to "Bearer [REDACTED]",
        // Named token / key fields: token=... | "token": "..." | token: ...
        Regex("""(?i)\b(access_token|refresh_token|id_token|api_key|apikey|client_secret)["']?\s*[:=]\s*["']?[A-Za-z0-9._\-]+["']?""")
            to "$1=[REDACTED]",
        // Email addresses — account PII that can surface in errors/logs/source rows.
        Regex("""(?i)\b[\w.%+-]+@[\w.-]+\.[A-Za-z]{2,}\b""") to "[REDACTED_EMAIL]",
    )

    fun redact(text: String): String =
        patterns.fold(text) { acc, (re, repl) -> re.replace(acc, repl) }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.diagnostics.DiagnosticsRedactorTest"`
Expected: PASS (5 tests). If a capture-group replacement (`$1`) misbehaves, confirm the regex group indices.

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/diagnostics/DiagnosticsRedactor.kt core/data/src/test/kotlin/com/stash/core/data/diagnostics/DiagnosticsRedactorTest.kt
git commit -m "feat(diagnostics): DiagnosticsRedactor — scrub secrets from bundles"
```

---

## Task 2: Extract `deviceMetadataBlock()` from `CrashFileStore`

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/diagnostics/CrashFileStore.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/diagnostics/CrashFileStoreTest.kt` (existing — add one assertion)

Goal: a single source for the device/app metadata header so the crash report and the diagnostics bundle render identically. DRY; do not change the crash report's output.

- [ ] **Step 1:** Add an `internal fun deviceMetadataBlock(): String` that builds the Time/App version/Device/Manufacturer/Android/Build lines (reusing the existing private `appVersionInfo()`), and refactor `formatReport()` to call it. Keep `formatReport`'s `Thread:` line + stack trace appended after the block.

```kotlin
internal fun deviceMetadataBlock(): String {
    val versionInfo = appVersionInfo()
    return buildString {
        appendLine("Time:           ${TIMESTAMP_FORMAT.format(Date())} UTC")
        appendLine("App version:    ${versionInfo.versionName} (versionCode ${versionInfo.versionCode})")
        appendLine("Device:         ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Manufacturer:   ${Build.MANUFACTURER}")
        appendLine("Android:        ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("Build:          ${Build.DISPLAY}")
    }
}
```
Then in `formatReport`, replace the inline Time…Build lines with `append(deviceMetadataBlock())` followed by the existing `appendLine("Thread:           ${thread.name}")` etc. Verify the crash report text is unchanged (same lines, same order).

- [ ] **Step 2:** In the existing `CrashFileStoreTest.kt`, add:

```kotlin
@Test fun `deviceMetadataBlock contains version and android lines`() {
    val block = store.deviceMetadataBlock()
    assertTrue(block.contains("App version:"))
    assertTrue(block.contains("Android:"))
    assertFalse(block.contains("Thread:")) // header only, no crash-specific lines
}
```

- [ ] **Step 3: Run**

Run: `./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.diagnostics.CrashFileStoreTest"`
Expected: PASS (existing tests + the new one). The existing crash-report-format tests must still pass — that proves `formatReport` output is unchanged.

- [ ] **Step 4: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/diagnostics/CrashFileStore.kt core/data/src/test/kotlin/com/stash/core/data/diagnostics/CrashFileStoreTest.kt
git commit -m "refactor(diagnostics): extract deviceMetadataBlock() for reuse"
```

---

## Task 3: `LogcatCapture` (own-logcat tail → rotating file)

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/diagnostics/LogcatCapture.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/diagnostics/LogcatCaptureTest.kt`

Design: the rotation/append + tail logic is unit-testable (`append`/`recentLogs`); the `logcat` reader thread (`start()`) is I/O and verified on-device (Task 10). The reader calls `append(line)`.

- [ ] **Step 1: Write the failing test** (Robolectric + real `context.cacheDir`, mirroring `CrashFileStoreTest`)

```kotlin
package com.stash.core.data.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class LogcatCaptureTest {
    private lateinit var context: Context
    private lateinit var capture: LogcatCapture
    private lateinit var dir: File

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        capture = LogcatCapture(context)
        dir = File(context.cacheDir, "diagnostics")
        dir.deleteRecursively()
    }
    @After fun tearDown() { dir.deleteRecursively() }

    @Test fun `recentLogs returns the last N lines across rotation`() {
        repeat(50) { capture.append("line-$it") }
        val tail = capture.recentLogs(maxLines = 10)
        val lines = tail.trim().lines()
        assertEquals(10, lines.size)
        assertEquals("line-49", lines.last())
    }

    @Test fun `append rotates when the active file exceeds the cap`() {
        // Cap is small in tests via the constructor override below.
        val big = "x".repeat(1000)
        repeat(40) { capture.append(big) } // ~40 KB
        assertTrue(File(dir, "applog.1.txt").exists()) // rotation happened
        // Newest content still retrievable.
        capture.append("freshest")
        assertTrue(capture.recentLogs(5).contains("freshest"))
    }
}
```
Note: to make the rotation test fast, give `LogcatCapture` an internal/visible-for-test `maxBytes` constructor param defaulting to ~512 KB; the test constructs it with a small cap (e.g. add a secondary constructor or an `internal val maxBytes`). Adjust the test to pass the small cap if you add a param.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.diagnostics.LogcatCaptureTest"`
Expected: FAIL — `LogcatCapture` unresolved.

- [ ] **Step 3: Implement**

```kotlin
package com.stash.core.data.diagnostics

import android.content.Context
import android.os.Process
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Continuously tails THIS app's own logcat (no READ_LOGS needed — the log daemon
 * returns the caller's own UID lines) into a rotating file under
 * cacheDir/diagnostics, so a diagnostics bundle can include the lead-up to a
 * failure even after a crash/restart. Best-effort: if the OEM blocks the spawn,
 * it logs a warning and the bundle simply omits logs.
 */
@Singleton
open class LogcatCapture @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // Overridable in tests for a small rotation cap.
    internal open val maxBytes: Long = 512L * 1024

    private val dir: File get() = File(context.cacheDir, "diagnostics")
    private val active: File get() = File(dir, ACTIVE)
    private val rotated: File get() = File(dir, ROTATED)

    @Volatile private var started = false

    /** Start the background tail. Idempotent. Call once at app init. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        Thread {
            runCatching {
                dir.mkdirs()
                val process = Runtime.getRuntime().exec(
                    arrayOf("logcat", "-v", "threadtime", "--pid", Process.myPid().toString()),
                )
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        append(line)
                        line = reader.readLine()
                    }
                }
            }.onFailure { Log.w(TAG, "logcat capture unavailable; diagnostics will omit logs", it) }
        }.apply { isDaemon = true; name = "stash-logcat-capture"; start() }
    }

    /** Append one line; rotate active→rotated when the active file passes [maxBytes]. */
    @Synchronized
    internal fun append(line: String) {
        runCatching {
            dir.mkdirs()
            if (active.exists() && active.length() >= maxBytes) {
                rotated.delete()
                active.renameTo(rotated)
            }
            active.appendText(line + "\n")
        }
    }

    /** Return the last [maxLines] lines across the rotated + active files. */
    fun recentLogs(maxLines: Int = 1500): String = runCatching {
        val all = buildList {
            if (rotated.exists()) addAll(rotated.readLines())
            if (active.exists()) addAll(active.readLines())
        }
        all.takeLast(maxLines).joinToString("\n")
    }.getOrDefault("")

    companion object {
        private const val TAG = "LogcatCapture"
        private const val ACTIVE = "applog.txt"
        private const val ROTATED = "applog.1.txt"
    }
}
```
If the test passes a custom cap, expose it: e.g. add `internal constructor(context: Context, override val maxBytes: Long) : this(context)` — or make the test subclass `LogcatCapture(context)` overriding `maxBytes`. Pick whichever compiles cleanly; the production `@Inject` constructor stays single-arg.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.diagnostics.LogcatCaptureTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/diagnostics/LogcatCapture.kt core/data/src/test/kotlin/com/stash/core/data/diagnostics/LogcatCaptureTest.kt
git commit -m "feat(diagnostics): LogcatCapture — rotating own-logcat tail"
```

---

## Task 4: `DiagnosticsBundleBuilder`

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/diagnostics/DiagnosticsBundleBuilder.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/diagnostics/DiagnosticsBundleBuilderTest.kt`

**FIRST, confirm two bindings by reading the code:**
1. How `AuthHealthProbe` is provided to Hilt (look for a module under `core/data/.../di/` or `sync/auth/` with `@Binds @IntoSet` / `multibindings`). If a `Set<AuthHealthProbe>` multibinding exists, inject `probes: Set<@JvmSuppressWildcards AuthHealthProbe>`. If NOT, inject `tokenManager` only for the auth section and drop `isExpired()` (the sync-history HTTP codes already reveal auth failures — the primary signal for the re-auth bug). Note which you chose.
2. The default `kotlinx.serialization.json.Json` is used to decode `SyncStepResult` (the column was written with `Json.encodeToString`). Import `kotlinx.serialization.json.Json` + `kotlinx.serialization.decodeFromString`.

Builder injects (all already exist; all `@Singleton`/DAOs): `@ApplicationContext context`, `syncHistoryDao: SyncHistoryDao`, `downloadQueueDao: DownloadQueueDao`, `trackBlocklistDao: TrackBlocklistDao`, `sourceAccountDao: SourceAccountDao`, `tokenManager: TokenManager`, `crashFileStore: CrashFileStore`, `logcatCapture: LogcatCapture`, and (per the binding check) optionally the auth probes. Snapshot Flows with `.first()` (suspend), read StateFlows with `.value`.

- [ ] **Step 1: Write the failing test** (MockK + runTest; Robolectric only if the FileProvider URI is exercised — to keep it simple, test the TEXT assembly via an `internal suspend fun buildText(): String` and leave file/URI writing in `build()`).

```kotlin
package com.stash.core.data.diagnostics

import com.stash.core.data.db.dao.*
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsBundleBuilderTest {
    private val crashFileStore: CrashFileStore = mockk(relaxed = true) {
        every { deviceMetadataBlock() } returns "App version:    0.9.42 (versionCode 78)\nAndroid:        11 (SDK 30)\n"
        every { allCrashFiles() } returns emptyList()
    }
    private val logcatCapture: LogcatCapture = mockk { every { recentLogs(any()) } returns "log line 1\nlog line 2" }
    private val syncHistoryDao: SyncHistoryDao = mockk { every { getRecentSyncs(any()) } returns flowOf(emptyList()) }
    private val downloadQueueDao: DownloadQueueDao = mockk {
        coEvery { getStatusCounts() } returns emptyList()
        every { getFailedDownloads() } returns flowOf(emptyList())
        every { getUnmatchedCount() } returns flowOf(0)
    }
    private val trackBlocklistDao: TrackBlocklistDao = mockk { every { observeCount() } returns flowOf(3) }
    private val sourceAccountDao: SourceAccountDao = mockk { every { getAll() } returns flowOf(emptyList()) }
    // tokenManager + probes per the binding decision...

    private fun builder() = DiagnosticsBundleBuilder(/* context not needed for buildText */ mockk(relaxed = true),
        syncHistoryDao, downloadQueueDao, trackBlocklistDao, sourceAccountDao, /* tokenManager */ mockk(relaxed = true),
        crashFileStore, logcatCapture)

    @Test fun `bundle text includes header, sections, and logs`() = runTest {
        val text = builder().buildText()
        assertTrue(text.contains("App version:"))
        assertTrue(text.contains("log line 1"))
        assertTrue(text.contains("Blocklist")) // misc-counts section header
    }

    @Test fun `a failing data source degrades to a section-unavailable note, not a crash`() = runTest {
        coEvery { downloadQueueDao.getStatusCounts() } throws RuntimeException("db locked")
        val text = builder().buildText()
        assertTrue(text.contains("unavailable")) // section marked unavailable
        assertTrue(text.contains("log line 1"))   // rest of the bundle still built
    }
}
```
Adapt the constructor arg list/order to your final signature (and the tokenManager/probe args per the binding decision).

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.diagnostics.DiagnosticsBundleBuilderTest"`
Expected: FAIL — unresolved.

- [ ] **Step 3: Implement** `DiagnosticsBundleBuilder` with:
- `data class DiagnosticsBundle(val text: String, val file: java.io.File, val contentUri: android.net.Uri)`.
- `internal suspend fun buildText(): String` — assembles, in order: header (`crashFileStore.deviceMetadataBlock()`), Connection/auth, Recent sync history (decode `diagnostics` JSON → `List<SyncStepResult>`, render service·step·status·httpCode), Download state (`getStatusCounts()` + `getFailedDownloads().first()` capped ~20 + `getUnmatchedCount().first()`), Misc counts (`trackBlocklistDao.observeCount().first()` → "Blocklist: N"), Recent crash reports (`crashFileStore.allCrashFiles().take(2)` → file text), Recent logs (`logcatCapture.recentLogs(1500)`). **Wrap each section in `runCatching { … }.getOrElse { "[<section> unavailable: ${it.message}]" }`** and append. Finally `return DiagnosticsRedactor.redact(assembled)`.
- `suspend fun build(): DiagnosticsBundle` — `val text = buildText()`; write to `File(context.cacheDir, "diagnostics").apply{mkdirs()}` as `stash-diagnostics-<ts>.txt` (delete older bundle files, keep newest); `val uri = crashFileStore.shareUriFor(file)`; return the bundle. (Reusing `shareUriFor` keeps a single FileProvider authority.)
- For the auth section: render per service — Spotify/YouTube `Connected`/`Not connected` from `tokenManager.*AuthState.value` (match on `AuthState.Connected`), plus `connected_at`/`last_sync_at` from `sourceAccountDao.getAll().first()`. **Read ONLY `isConnected`/`connectedAt`/`lastSyncAt` from those rows — do NOT emit `email`, `displayName`, or `avatarUrl` (account PII; the email-redaction pattern is only a backstop).** **No token/cookie/userId values.** If probes were injected, add `expired=<isExpired()>`.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.diagnostics.DiagnosticsBundleBuilderTest"`
Expected: PASS (2 tests). Then run the whole module: `./gradlew :core:data:testDebugUnitTest` — green except the 4 known pre-existing failures (`TrackDaoStreamableTest`, `MusicRepositoryDownloadsMixTest`, 2× `StashDiscoveryWorkerStreamOnlyTest`).

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/diagnostics/DiagnosticsBundleBuilder.kt core/data/src/test/kotlin/com/stash/core/data/diagnostics/DiagnosticsBundleBuilderTest.kt
git commit -m "feat(diagnostics): DiagnosticsBundleBuilder — assemble + redact bundle"
```

---

## Task 5: Start `LogcatCapture` at app init

**Files:**
- Modify: `app/src/main/kotlin/com/stash/app/StashApplication.kt`

- [ ] **Step 1:** Add an injected field beside `crashReporter` and start it right after `crashReporter.install()`:

```kotlin
@Inject
lateinit var logcatCapture: com.stash.core.data.diagnostics.LogcatCapture
```
In `onCreate()`, immediately after `crashReporter.install()`:
```kotlin
logcatCapture.start()
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/stash/app/StashApplication.kt
git commit -m "feat(diagnostics): start LogcatCapture at app init"
```

---

## Task 6: Expose the `diagnostics/` cache path to FileProvider

**Files:**
- Modify: `app/src/main/res/xml/file_paths.xml`

- [ ] **Step 1:** Add inside `<paths>`:

```xml
    <cache-path name="diagnostics" path="diagnostics/" />
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/res/xml/file_paths.xml
git commit -m "feat(diagnostics): expose diagnostics/ cache path to FileProvider"
```

---

## Task 7: `DiagnosticsPreviewViewModel`

**Files:**
- Create: `feature/settings/src/main/kotlin/com/stash/feature/settings/diagnostics/DiagnosticsPreviewViewModel.kt`

(Check `feature/settings/build.gradle.kts` already depends on `core:data` — it does, via SettingsViewModel's `crashFileStore`.)

- [ ] **Step 1: Implement** (no unit test — thin VM over the tested builder; verified on-device)

```kotlin
package com.stash.feature.settings.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.diagnostics.DiagnosticsBundle
import com.stash.core.data.diagnostics.DiagnosticsBundleBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class DiagnosticsPreviewViewModel @Inject constructor(
    private val builder: DiagnosticsBundleBuilder,
) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        val text: String = "",
        val bundle: DiagnosticsBundle? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init { rebuild() }

    fun rebuild() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { builder.build() } }
            result.onSuccess { b -> _state.update { State(loading = false, text = b.text, bundle = b) } }
                  .onFailure { e -> _state.update { State(loading = false, error = e.message ?: "Failed to build diagnostics") } }
        }
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew :feature:settings:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add feature/settings/src/main/kotlin/com/stash/feature/settings/diagnostics/DiagnosticsPreviewViewModel.kt
git commit -m "feat(diagnostics): DiagnosticsPreviewViewModel"
```

---

## Task 8: `DiagnosticsPreviewScreen`

**Files:**
- Create: `feature/settings/src/main/kotlin/com/stash/feature/settings/diagnostics/DiagnosticsPreviewScreen.kt`

- [ ] **Step 1: Implement** a Compose screen following Stash's design system (reuse `GlassCard` / `StashTheme` — check a neighbor like `SettingsScreen.kt` for the exact theme accessors). Structure:
- A top row: back button + title "Diagnostics".
- If `loading` → centered progress. If `error` → message + Retry (`viewModel.rebuild()`).
- Else: scrollable monospace `Text(state.text, fontFamily = FontFamily.Monospace, style = labelSmall)` in a `GlassCard`, with bottom actions: **Share** and **Copy**.
- **Share** mirrors the existing crash-report `ACTION_SEND` exactly, using `state.bundle!!.contentUri` + subject "Stash diagnostics", wrapped in `runCatching` with a Toast fallback. (Copy the block from `SettingsScreen.kt` Diagnostics card.)
- **Copy** → `ClipboardManager`/`LocalClipboardManager.current.setText(AnnotatedString(state.text))` + a Toast "Copied".

```kotlin
@Composable
fun DiagnosticsPreviewScreen(
    onNavigateBack: () -> Unit,
    viewModel: DiagnosticsPreviewViewModel = hiltViewModel(),
) { /* per the structure above */ }
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew :feature:settings:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add feature/settings/src/main/kotlin/com/stash/feature/settings/diagnostics/DiagnosticsPreviewScreen.kt
git commit -m "feat(diagnostics): DiagnosticsPreviewScreen (preview + share + copy)"
```

---

## Task 9: Wire route + Settings entry

**Files:**
- Modify: `app/src/main/kotlin/com/stash/app/navigation/TopLevelDestination.kt`
- Modify: `app/src/main/kotlin/com/stash/app/navigation/StashNavHost.kt`
- Modify: `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsScreen.kt`

- [ ] **Step 1:** Add the route to `TopLevelDestination.kt`:

```kotlin
@Serializable data object DiagnosticsPreviewRoute
```

- [ ] **Step 2:** In `StashNavHost.kt`: pass the nav callback into the existing `SettingsScreen(...)` call and add the destination:

```kotlin
// inside composable<SettingsRoute> { SettingsScreen( ... ) }
onNavigateToDiagnosticsPreview = { navController.navigate(DiagnosticsPreviewRoute) },
// new destination:
composable<DiagnosticsPreviewRoute> {
    com.stash.feature.settings.diagnostics.DiagnosticsPreviewScreen(
        onNavigateBack = { navController.popBackStack() },
    )
}
```

- [ ] **Step 3:** In `SettingsScreen.kt`: add `onNavigateToDiagnosticsPreview: () -> Unit = {}` to the public `SettingsScreen(...)` signature, thread it through to `SettingsContent(...)` (add the param next to the other `onNavigateTo*`), pass it at the call site, and add a second `OutlinedButton` inside the existing Diagnostics `GlassCard` (after the crash-report button):

```kotlin
Spacer(modifier = Modifier.height(12.dp))
Text("Share diagnostics", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
Spacer(modifier = Modifier.height(2.dp))
Text(
    "Bundle recent logs, sync history, and connection status (no passwords) so a dev can debug your issue.",
    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
)
Spacer(modifier = Modifier.height(12.dp))
OutlinedButton(onClick = onNavigateToDiagnosticsPreview, modifier = Modifier.fillMaxWidth()) {
    Text("Share diagnostics")
}
```

- [ ] **Step 4: Verify compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/stash/app/navigation/TopLevelDestination.kt app/src/main/kotlin/com/stash/app/navigation/StashNavHost.kt feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsScreen.kt
git commit -m "feat(diagnostics): Settings entry + preview route wiring"
```

---

## Task 10: On-device verification + finish

- [ ] **Step 1: Full test suite**

Run: `./gradlew :core:data:testDebugUnitTest :feature:settings:testDebugUnitTest`
Expected: green except the 4 known pre-existing `core:data` failures.

- [ ] **Step 2: Install + verify on-device** (compile-pass is not enough on this project)

Run: `./gradlew :app:installDebug`
Verify: Settings → Diagnostics → **Share diagnostics** → preview screen renders the bundle (header with version/device, connection status, recent sync rows with HTTP codes, download counts, recent logs) → **Share** opens the chooser and the file shares (e.g. to a notes app / Discord) → **Copy** copies → **Back** returns. Confirm **no tokens/cookies** appear in the text (search the shared text for `sp_dc`, `Bearer`, `SAPISID` → should be `[REDACTED]` or absent). Force a sync beforehand so there's sync history to show.

- [ ] **Step 3:** Use superpowers:requesting-code-review before integrating.

- [ ] **Step 4:** Use superpowers:finishing-a-development-branch.

---

## Notes / follow-ups (non-blocking)
- The `AuthHealthProbe` binding shape (multibinding vs not) is the one thing to confirm in Task 4; the bundle is valuable either way because sync-history HTTP codes already expose auth failures.
- Log tail size (1500) and rotation cap (512 KB) are tunable.
- A future "auto-attach last diagnostics to a crash" could chain this with the crash reporter — out of scope here.
