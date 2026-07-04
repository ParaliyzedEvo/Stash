# qbdlx Multi-Token Pool Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the whole live qbdlx Qobuz token pool (not just the 1 bundled FR token) — fetched fresh at build, AES-encrypted in the APK, served sticky-primary with auto-failover, and pickable in Settings as "Token N".

**Architecture:** A `QbdlxPoolProvider` fun-interface seam supplies the pool string; the default impl decrypts an AES-256-GCM blob from `BuildConfig` (encrypted at build in `build.gradle.kts`, decrypted at runtime in `QbdlxPoolCipher`). `QbdlxCredentialStore` switches from round-robin to a sticky `@Volatile` primary pointer with pasted > pinned > sticky-auto priority, and exposes an anonymized picker list. CI fetches the live pool JSON and a fingerprint (`QBDLX_POOL_FP`) guards that the current, non-blank, encrypted pool actually shipped.

**Tech Stack:** Kotlin, Hilt (`@Provides`/`fun interface`), `javax.crypto` (AES-GCM) + `java.util.Base64` (API 26+, no new dependency), DataStore Preferences, Compose (reuse `SettingsPickerRow`), JUnit/Robolectric/Truth, GitHub Actions + `jq`.

**Spec:** `docs/superpowers/specs/2026-07-03-qbdlx-multi-token-pool-design.md`

---

## Conventions for every task

- **Test runs use the Gradle daemon + a `--tests` filter** (per repo memory: `--no-daemon` throws BindExceptions on back-to-back runs here; unfiltered runs pull in the flaky `:core:media` network test).
  - Data module: `./gradlew :data:download:testDebugUnitTest --tests "<filter>"`
  - Settings module: `./gradlew :feature:settings:testDebugUnitTest --tests "<filter>"`
- **Do NOT restructure existing UI** — reuse `SettingsPickerRow` and the existing `qbdlxEnabled` `AnimatedVisibility` block.
- **Fields written from `init{}` must be declared ABOVE the `init` block** (qbdlx settings-crash lesson: `Dispatchers.Main.immediate` + cache-warm DataStore runs init synchronously).
- Commit after each task's tests pass. Branch: `feat/qbdlx-multi-token-pool`.

---

## Task 0: Branch setup

- [ ] **Step 1: Create the feature branch from master**

```bash
git checkout master
git checkout -b feat/qbdlx-multi-token-pool
```

Inline execution (subagents can't run on Fable in this harness), no worktree — avoids the `local.properties` copy hassle.

---

## Task 1: `QbdlxPoolCipher` — AES-256-GCM decrypt (+ encrypt reference)

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxPoolCipher.kt`
- Test: `data/download/src/test/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxPoolCipherTest.kt`

- [ ] **Step 1: Write the cipher (runtime decrypt + a build-mirrored encrypt for tests/reference)**

```kotlin
package com.stash.data.download.lossless.qbdlx

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM for the bundled qbdlx token pool. The plaintext `token:country,...`
 * pool is encrypted at BUILD time (data/download/build.gradle.kts runs the same
 * scheme) and decrypted here at runtime, so `strings`/`dex-grep` on the released
 * APK don't surface the tokens — the casual attack, and literally how we found
 * the pool ourselves.
 *
 * NOT a security wall: the key ships in the app (assembled from fragments below),
 * so Frida / static analysis still recover it. Goal = raise cost past casual grep.
 *
 * ⚠️ TWO COPIES OF THIS SCHEME. The encrypt half is duplicated in
 * data/download/build.gradle.kts (Gradle can't depend on this module's classes).
 * If you change key derivation, IV size, tag length, or byte layout you MUST
 * change BOTH and regenerate FIXTURE_BLOB in QbdlxPoolCipherTest — otherwise
 * shipped builds embed a blob this runtime can't decrypt → silent empty pool.
 * QBDLX_POOL_FP + the release.yml verify step + on-device verify are the guards.
 *
 * ponytail: fragment-concat key, not real key management — matches the
 * "past casual grep" bar; a real KMS is the deferred broker's job.
 */
object QbdlxPoolCipher {
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    private fun key(): SecretKeySpec {
        val pass = "stash" + "-qbdlx-" + "pool-" + "v1"
        val digest = MessageDigest.getInstance("SHA-256").digest(pass.toByteArray())
        return SecretKeySpec(digest, "AES")
    }

    /** Decrypt a base64(IV‖ciphertext‖GCM-tag) blob. Blank/malformed → "" (never throws). */
    fun decrypt(blob: String): String {
        if (blob.isBlank()) return ""
        return try {
            val bytes = Base64.getDecoder().decode(blob)
            if (bytes.size <= IV_LEN) return ""
            val iv = bytes.copyOfRange(0, IV_LEN)
            val body = bytes.copyOfRange(IV_LEN, bytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (e: Exception) {
            "" // malformed base64, bad tag, wrong key → empty pool → Settings paste path
        }
    }

    /**
     * Reference encrypt — MUST match data/download/build.gradle.kts's encryptPool.
     * Used by tests (and handy for regenerating the fixture). Java GCM appends the
     * 16-byte tag to the ciphertext, so the layout is IV ‖ (ciphertext‖tag).
     */
    fun encrypt(plain: String): String {
        if (plain.isBlank()) return ""
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(plain.toByteArray()))
    }
}
```

- [ ] **Step 2: Write the failing test (round-trip + fixture drift guard + never-throws)**

```kotlin
package com.stash.data.download.lossless.qbdlx

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure-JVM (no Robolectric): QbdlxPoolCipher uses only javax.crypto + java.util.Base64. */
class QbdlxPoolCipherTest {

    private val pool = "tokA:FR,tokB:GB,tokC:NO"

    @Test
    fun `round-trips a multi-entry pool`() {
        assertThat(QbdlxPoolCipher.decrypt(QbdlxPoolCipher.encrypt(pool))).isEqualTo(pool)
    }

    /**
     * DRIFT GUARD. FIXTURE_BLOB was produced ONCE by the build-side scheme and is
     * checked in. If either copy of the scheme (this class OR build.gradle.kts)
     * changes without the other, this fails. Regenerate only by intentionally
     * re-running the encrypt and pasting the new blob here.
     */
    @Test
    fun `decrypts the checked-in fixture blob`() {
        assertThat(QbdlxPoolCipher.decrypt(FIXTURE_BLOB)).isEqualTo(FIXTURE_PLAINTEXT)
    }

    @Test
    fun `blank and malformed inputs return empty, never throw`() {
        assertThat(QbdlxPoolCipher.decrypt("")).isEmpty()
        assertThat(QbdlxPoolCipher.decrypt("   ")).isEmpty()
        assertThat(QbdlxPoolCipher.decrypt("not base64 @@@")).isEmpty()
        assertThat(QbdlxPoolCipher.decrypt("QQ==")).isEmpty() // valid b64, too short for IV
    }

    @Test
    fun `a tampered tag fails to empty`() {
        val blob = QbdlxPoolCipher.encrypt(pool)
        val tampered = blob.dropLast(2) + (if (blob.last() == 'A') "BB" else "AA")
        assertThat(QbdlxPoolCipher.decrypt(tampered)).isEmpty()
    }

    private companion object {
        const val FIXTURE_PLAINTEXT = "fixtureTok:FR,otherTok:GB"
        // Generated once via QbdlxPoolCipher.encrypt(FIXTURE_PLAINTEXT) — see Step 4.
        const val FIXTURE_BLOB = "PASTE_IN_STEP_4"
    }
}
```

- [ ] **Step 3: Run the round-trip + never-throws tests (fixture test still red)**

Run: `./gradlew :data:download:testDebugUnitTest --tests "*QbdlxPoolCipherTest*"`
Expected: round-trip / never-throws / tampered PASS; `decrypts the checked-in fixture blob` FAILS (placeholder blob).

- [ ] **Step 4: Generate the fixture blob and paste it in**

Print a real blob using the compiled class (a tiny throwaway test), then paste it into `FIXTURE_BLOB`:

```bash
# Temporarily add this test, run it, copy the printed blob, then delete it:
#   @Test fun gen() = println("BLOB=" + QbdlxPoolCipher.encrypt("fixtureTok:FR,otherTok:GB"))
./gradlew :data:download:testDebugUnitTest --tests "*QbdlxPoolCipherTest.gen*" -i 2>&1 | grep BLOB=
```
Replace `"PASTE_IN_STEP_4"` with the printed base64 (strip the `BLOB=` prefix), then remove the `gen()` helper.

- [ ] **Step 5: Run all cipher tests — all pass**

Run: `./gradlew :data:download:testDebugUnitTest --tests "*QbdlxPoolCipherTest*"`
Expected: PASS (all 5).

- [ ] **Step 6: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxPoolCipher.kt \
        data/download/src/test/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxPoolCipherTest.kt
git commit -m "feat(qbdlx): AES-256-GCM pool cipher (decrypt + fixture-guarded)"
```

---

## Task 2: `QbdlxPoolProvider` seam + wire into store & module

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxPoolProvider.kt`
- Modify: `data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/di/QbdlxModule.kt`
- Modify: `data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxCredentialStore.kt` (constructor + `poolRaw` init)
- Modify: `data/download/src/test/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxCredentialStoreTest.kt` (add provider to constructor calls)

> `QBDLX_POOL_FP` doesn't exist as a BuildConfig field until Task 6 wires `build.gradle.kts`. To keep this task compiling and green on its own, the module's fp sanity-warning is added in Task 6, not here. Here the provider just decrypts.
>
> ⚠️ **Transient state between Task 2 and Task 6:** `BuildConfig.QBDLX_TOKEN_POOL` is still *plaintext* until Task 6 encrypts it at build. `decrypt(plaintext)` returns `""` (plaintext isn't valid base64/GCM), so a *real* app build in this window has an empty pool and shows the paste prompt. **Unit tests are unaffected** (they override `poolRaw`). Do not ship a build between these tasks; Task 6's device verify is where the real pool comes back.

- [ ] **Step 1: Create the seam**

```kotlin
package com.stash.data.download.lossless.qbdlx

/**
 * The ONE place the token pool's origin is defined. Today: decrypt the bundled
 * BuildConfig blob. A future runtime/Worker broker swaps only the @Provides in
 * QbdlxModule — failover, picker, and the source never change.
 *
 * ponytail: rawPool() is synchronous. A broker fetch is async, but the embedded
 * path is sync and adding suspend now speculates on an API that doesn't exist.
 */
fun interface QbdlxPoolProvider {
    fun rawPool(): String
}
```

- [ ] **Step 2: Provide the default impl in `QbdlxModule` (companion `@Provides`)**

Add to the `companion object` in `QbdlxModule.kt` (alongside `provideQbdlxSigner`):

```kotlin
        @Provides
        @Singleton
        fun provideQbdlxPoolProvider(): QbdlxPoolProvider =
            QbdlxPoolProvider { QbdlxPoolCipher.decrypt(BuildConfig.QBDLX_TOKEN_POOL) }
```

Add import: `import com.stash.data.download.lossless.qbdlx.QbdlxPoolCipher` and `import com.stash.data.download.lossless.qbdlx.QbdlxPoolProvider` (same package — no import needed if the module were in the same package, but it's in `.di`, so import both).

- [ ] **Step 3: Inject the provider into the store; init `poolRaw` from it**

In `QbdlxCredentialStore.kt`, change the constructor and `poolRaw`:

```kotlin
@Singleton
class QbdlxCredentialStore @Inject constructor(
    @ApplicationContext private val context: Context,
    poolProvider: QbdlxPoolProvider,
) {
    ...
    /**
     * Test seam: the raw `token:country,token:country` pool. Defaults to the
     * decrypted BuildConfig blob (via [QbdlxPoolProvider]); tests override it.
     */
    internal var poolRaw: String = poolProvider.rawPool()
```

(Delete the old `= BuildConfig.QBDLX_TOKEN_POOL` initializer and its now-unused `BuildConfig` import if nothing else uses it — check first.)

- [ ] **Step 4: Update the test constructor calls**

`QbdlxPoolProvider` is a `fun interface`, so a trailing lambda works. In `QbdlxCredentialStoreTest.kt`:

```kotlin
    private fun store(pool: String) =
        QbdlxCredentialStore(ctx) { "" }.also { it.poolRaw = pool }

    @Before
    fun setUp() {
        runBlocking { QbdlxCredentialStore(ctx) { "" }.clearPersistedForTest() }
    }
```

- [ ] **Step 5: Run the existing store tests — all still pass (behavior unchanged this task)**

Run: `./gradlew :data:download:testDebugUnitTest --tests "*QbdlxCredentialStoreTest*"`
Expected: PASS (all existing tests — this task is pure plumbing).

- [ ] **Step 6: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxPoolProvider.kt \
        data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/di/QbdlxModule.kt \
        data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxCredentialStore.kt \
        data/download/src/test/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxCredentialStoreTest.kt
git commit -m "feat(qbdlx): QbdlxPoolProvider seam (decrypt-by-default), broker-ready"
```

---

## Task 3: Sticky-primary failover (replace round-robin)

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxCredentialStore.kt`
- Modify: `data/download/src/test/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxCredentialStoreTest.kt`

- [ ] **Step 1: Write the failing sticky tests (replace the round-robin test, rewrite the cooldown test)**

Delete `round-robin advances across live pool tokens` and `dead token is retried after the cooldown elapses`; add:

```kotlin
    @Test
    fun `activeToken is sticky - same token until it dies, then advances`() = runTest {
        val s = store("a:FR,b:GB")
        val first = s.activeToken()
        assertThat(s.activeToken()).isEqualTo(first)   // sticky: no rotation
        assertThat(s.activeToken()).isEqualTo(first)
        s.markDead(first!!)
        val second = s.activeToken()
        assertThat(second).isNotEqualTo(first)          // advanced to the other live token
        assertThat(s.activeToken()).isEqualTo(second)   // sticky on the new primary
    }

    @Test
    fun `a token recovers as a candidate after its cooldown elapses`() = runTest {
        var now = 1_000L
        val s = store("a:FR,b:GB").also { it.clock = { now } }
        val primary = s.activeToken()                   // canonical-first live token
        s.markDead(primary!!)
        val other = s.activeToken()
        assertThat(other).isNotEqualTo(primary)         // advanced; primary in cooldown
        now += QbdlxCredentialStore.DEAD_COOLDOWN_MS + 1
        s.markDead(other!!)                             // now-primary dies; original cooldown elapsed
        assertThat(s.activeToken()).isEqualTo(primary)  // original live again → reused
    }
```

- [ ] **Step 2: Run — new tests fail (still round-robin)**

Run: `./gradlew :data:download:testDebugUnitTest --tests "*QbdlxCredentialStoreTest*"`
Expected: the two new tests FAIL (round-robin returns alternating tokens, not sticky).

- [ ] **Step 3: Implement sticky primary**

In `QbdlxCredentialStore.kt`:
- Remove `private val rrIndex = AtomicInteger(0)` and its `import java.util.concurrent.atomic.AtomicInteger`.
- Add near the other in-memory state:

```kotlin
    /**
     * Sticky primary: the token we keep using until it dies (replaces round-robin).
     * @Volatile for visibility only — two concurrent resolves both picking a live
     * token is benign (last write wins, no corruption). Nulled on markDead of the
     * primary so the next call advances. In-memory (per process).
     */
    @Volatile
    private var activePrimary: String? = null
```

- Replace `activeToken()`:

```kotlin
    /**
     * The token to use now (sticky, not round-robin):
     *   1. pasted token if live (the user's own / monthly-refresh path — wins);
     *   2. pinned pool token if live AND still a member of the current pool
     *      (a pin to a since-removed token is ignored → falls through to auto);
     *   3. the sticky [activePrimary] if still live;
     *   4. else the first live token in canonical order → pinned as the new primary.
     * Null when nothing is live.
     */
    suspend fun activeToken(): String? {
        pastedToken()?.let { if (!isDead(it)) return it }
        pinnedToken()?.let { p ->
            if (!isDead(p) && pool().any { it.first == p }) return p
        }
        activePrimary?.let { if (!isDead(it)) return it }
        val next = pool().map { it.first }
            .filter { !isDead(it) }
            .sortedWith(compareBy({ it.hashCode() }, { it }))
            .firstOrNull() ?: return null
        activePrimary = next
        return next
    }
```

- Update `markDead` to drop the primary when it dies:

```kotlin
    fun markDead(token: String) {
        deadUntil[token] = clock() + DEAD_COOLDOWN_MS
        if (token == activePrimary) activePrimary = null
    }
```

(`pinnedToken()` is added in Task 4; to keep THIS task compiling, add the `pinnedTokenKey` + `pinnedToken()` reader now — it's a couple of lines and Task 4 builds on it. Add:)

```kotlin
    private val pinnedTokenKey = stringPreferencesKey("pinned_token")

    /** The picker-pinned pool token, or null for Auto. */
    suspend fun pinnedToken(): String? =
        context.qbdlxCredentialsDataStore.data.first()[pinnedTokenKey]?.takeIf { it.isNotBlank() }
```

- [ ] **Step 4: Run — all store tests pass**

Run: `./gradlew :data:download:testDebugUnitTest --tests "*QbdlxCredentialStoreTest*"`
Expected: PASS. (`markDead skips dead token within cooldown` still passes: markDead runs before any activeToken, so `a` dead → first live is `b`.)

- [ ] **Step 5: Update the store's class KDoc** (the header comment still says "round-robin across the live pool") to describe sticky priority. Small doc-only edit.

- [ ] **Step 6: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxCredentialStore.kt \
        data/download/src/test/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxCredentialStoreTest.kt
git commit -m "feat(qbdlx): sticky-primary failover (pasted>pinned>sticky-auto)"
```

---

## Task 4: Pin persistence + `poolForPicker` (anonymized "Token N")

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxCredentialStore.kt`
- Modify: `data/download/src/test/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxCredentialStoreTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
    @Test
    fun `pasted beats pinned beats sticky-auto`() = runTest {
        val s = store("a:FR,b:GB")
        s.setPinnedToken("b")
        assertThat(s.activeToken()).isEqualTo("b")      // pinned over auto
        s.setPastedToken("p")
        assertThat(s.activeToken()).isEqualTo("p")      // pasted over pinned
    }

    @Test
    fun `dead pinned token advances to auto`() = runTest {
        val s = store("a:FR,b:GB")
        s.setPinnedToken("a"); s.markDead("a")
        assertThat(s.activeToken()).isEqualTo("b")      // pinned dead → auto picks live
    }

    @Test
    fun `pin to a token not in the pool is ignored`() = runTest {
        val s = store("a:FR,b:GB")
        s.setPinnedToken("ghost")
        assertThat(s.activeToken()).isAnyOf("a", "b")   // stale pin ignored, auto used
    }

    @Test
    fun `poolForPicker labels stable under input reordering; live reflects deadUntil`() = runTest {
        val s1 = store("a:FR,b:GB")
        val s2 = store("b:GB,a:FR")                     // reversed input
        assertThat(s1.poolForPicker().map { it.label to it.token })
            .isEqualTo(s2.poolForPicker().map { it.label to it.token })
        s1.markDead("a")
        assertThat(s1.poolForPicker().first { it.token == "a" }.live).isFalse()
        assertThat(s1.poolForPicker().first { it.token == "b" }.live).isTrue()
    }

    @Test
    fun `poolForPicker is empty for an empty pool`() = runTest {
        assertThat(store("").poolForPicker()).isEmpty()
    }
```

- [ ] **Step 2: Run — fail (no `setPinnedToken`/`poolForPicker`/`QbdlxTokenChoice`)**

Run: `./gradlew :data:download:testDebugUnitTest --tests "*QbdlxCredentialStoreTest*"`
Expected: compile failure / FAIL — symbols missing.

- [ ] **Step 3: Implement `QbdlxTokenChoice`, `setPinnedToken`, `poolForPicker`**

`pinnedToken()` + `pinnedTokenKey` already added in Task 3. Add the writer + picker + the data class in `QbdlxCredentialStore.kt`:

```kotlin
    /** Pin a pool token for the Settings picker, or clear (null) for Auto. */
    suspend fun setPinnedToken(token: String?) {
        val t = token?.trim()
        context.qbdlxCredentialsDataStore.edit { prefs ->
            if (t.isNullOrEmpty()) prefs.remove(pinnedTokenKey) else prefs[pinnedTokenKey] = t
        }
    }

    /**
     * The pool as anonymized picker choices, in stable canonical order
     * (by token hash, so "Token 2" is the same account across pool refreshes —
     * NOT array position, and createdAt is dropped in the build-time flatten).
     * The raw token is the id behind the label only; it never becomes UI text.
     * [live] is a point-in-time hint (isDead at compute time), not a live flow.
     */
    suspend fun poolForPicker(): List<QbdlxTokenChoice> =
        pool().sortedWith(compareBy({ it.first.hashCode() }, { it.first }))
            .mapIndexed { i, (token, country) ->
                QbdlxTokenChoice(
                    label = "Token ${i + 1}",
                    token = token,
                    country = country,
                    live = !isDead(token),
                )
            }
```

At the top of the file (top-level, after imports, before the DataStore delegate or above the class):

```kotlin
/** One anonymized pool token for the Settings picker. `token` is the id only, never shown. */
data class QbdlxTokenChoice(
    val label: String,
    val token: String,
    val country: String,
    val live: Boolean,
)
```

- [ ] **Step 4: Run — all store tests pass**

Run: `./gradlew :data:download:testDebugUnitTest --tests "*QbdlxCredentialStoreTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxCredentialStore.kt \
        data/download/src/test/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxCredentialStoreTest.kt
git commit -m "feat(qbdlx): pin persistence + anonymized poolForPicker (stable Token N)"
```

---

## Task 5: Log strip — rename the "Qobuz" TAG

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxQobuzSource.kt`
- Check: `data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxApiClient.kt` (messages)

- [ ] **Step 1: Rename the source TAG**

In `QbdlxQobuzSource.kt` change:

```kotlin
        private const val TAG = "QbdlxSource"   // was "QbdlxQobuzSource" — drop "Qobuz" from logs
```

- [ ] **Step 2: Audit message strings for the word Qobuz/qobuz**

```bash
grep -rn -i "qobuz" data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/*.kt | grep -i "log\." 
```
Expected: no matches (current messages log endpoint tails like `getFileUrl`/`search` + status codes, already clean). If any log message contains "qobuz", reword to drop the word. Class names, KDoc, and `www.qobuz.com` URLs stay (not logs).

- [ ] **Step 3: Build the module to confirm it still compiles**

Run: `./gradlew :data:download:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxQobuzSource.kt
git commit -m "chore(qbdlx): drop 'Qobuz' from log tag (shared-diagnostics scrub)"
```

---

## Task 6: `build.gradle.kts` — encrypt pool at build + `QBDLX_POOL_FP` + runtime fp sanity log

**Files:**
- Modify: `data/download/build.gradle.kts`
- Modify: `data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/di/QbdlxModule.kt` (add fp-mismatch warning to the provider)

> This is build-config wiring — verified by an actual build + on-device, not a unit test (there's no runnable assertion over `buildConfigField` output). The cipher's fixture test (Task 1) already guards the scheme itself.

- [ ] **Step 1: Add `encryptPool` + `poolFp` helpers and encrypt before `buildConfigField`**

In `data/download/build.gradle.kts`, after the `val qbdlxTokenPool = ...` line, add:

```kotlin
// AES-256-GCM encrypt the pool at build time (mirrors the runtime
// QbdlxPoolCipher — keep the two in sync; QbdlxPoolCipherTest's fixture guards
// drift). Blank pool → emit "" so an unconfigured build still hits the paste
// path (not a blob that decrypts to "").
fun encryptPool(plain: String): String {
    if (plain.isBlank()) return ""
    val pass = "stash" + "-qbdlx-" + "pool-" + "v1"
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(pass.toByteArray())
    val key = javax.crypto.spec.SecretKeySpec(digest, "AES")
    val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
    val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, iv))
    return java.util.Base64.getEncoder().encodeToString(iv + cipher.doFinal(plain.toByteArray()))
}
// sha256(plaintextPool)[:8], lowercase hex — matches `printf %s "$POOL" | sha256sum | head -c 8`
// in release.yml. Non-secret; the CI verify step greps the dex for it to prove the
// CURRENT, non-blank pool actually shipped (the v0.9.69 blank/stale failure class).
fun poolFp(plain: String): String =
    if (plain.isBlank()) "" else
        java.security.MessageDigest.getInstance("SHA-256").digest(plain.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }.take(8)
// NOTE: `it.toInt() and 0xFF` is REQUIRED — a bare "%02x".format(byte) sign-extends
// negative bytes to 8 hex chars ("ffffffff"), so the fp would never match
// `sha256sum` and the CI gate would false-fail every build.

val qbdlxTokenPoolEnc = encryptPool(qbdlxTokenPool)
val qbdlxPoolFp = poolFp(qbdlxTokenPool)
```

Change the buildConfig fields:

```kotlin
        buildConfigField("String", "QBDLX_TOKEN_POOL", "\"$qbdlxTokenPoolEnc\"")
        buildConfigField("String", "QBDLX_POOL_FP", "\"$qbdlxPoolFp\"")
```

(Base64-basic alphabet is `A–Z a–z 0–9 + / =` — none of those break a Kotlin `"\"...\""` literal.)

- [ ] **Step 2: Add the runtime fp-mismatch warning to the provider**

In `QbdlxModule.kt`, replace the `provideQbdlxPoolProvider` body from Task 2 with:

```kotlin
        @Provides
        @Singleton
        fun provideQbdlxPoolProvider(): QbdlxPoolProvider = QbdlxPoolProvider {
            val pool = QbdlxPoolCipher.decrypt(BuildConfig.QBDLX_TOKEN_POOL)
            val fp = BuildConfig.QBDLX_POOL_FP
            if (fp.isNotBlank()) {
                val actual = if (pool.isBlank()) "" else
                    java.security.MessageDigest.getInstance("SHA-256")
                        .digest(pool.toByteArray())
                        .joinToString("") { "%02x".format(it.toInt() and 0xFF) }.take(8)
                if (actual != fp) {
                    android.util.Log.w("QbdlxPool", "pool fp mismatch — embed/runtime crypto drift?")
                }
            }
            pool
        }
```

(Catches decrypt-to-empty when the fp says the pool was non-empty — a named signal instead of a silent empty pool.)

- [ ] **Step 3: Configure a local pool + build the release APK**

Ensure `local.properties` has the plaintext pool (`qbdlx.tokenPool=tokA:FR,tokB:GB,...`), then:

Run: `./gradlew :app:assembleRelease`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Verify the dex is encrypted + fp is present (mirrors the CI gate)**

```bash
APK=$(find app/build/outputs/apk/release -name "*.apk" | head -n1)
DEX=$(mktemp -d); unzip -o -q "$APK" 'classes*.dex' -d "$DEX"
# tr -d '\r' guards against a CRLF local.properties on Windows (Properties strips
# line endings at build time, so a stray \r here would break the fp match).
POOLVAL=$(sed -n 's/.*qbdlx.tokenPool=//p' local.properties | tr -d '\r')
FIRST_TOKEN="${POOLVAL%%:*}"
FP=$(printf %s "$POOLVAL" | sha256sum | head -c 8)
grep -a -q -F -- "798273057" "$DEX"/classes*.dex && echo "app_id present: OK"
grep -a -q -F -- "$FIRST_TOKEN" "$DEX"/classes*.dex && echo "PLAINTEXT TOKEN LEAKED (BAD)" || echo "token absent (encrypted): OK"
grep -a -q -F -- "$FP" "$DEX"/classes*.dex && echo "fp present: OK" || echo "fp MISSING (BAD)"
```
Expected: `app_id present: OK`, `token absent (encrypted): OK`, `fp present: OK`.

- [ ] **Step 5: Device-verify qbdlx still resolves (real behavior, per the verify skill)**

Install the release APK, force-qbdlx or normal fallback, and confirm a FLAC resolves/plays (no "paste a token" badge). This is the backstop for crypto drift — an empty pool would show immediately as the paste prompt.

```bash
./gradlew :app:installRelease   # or adb install the built APK
# then drive playback and check: adb logcat | grep -iE "QbdlxSource|QbdlxPool|qbdlx served"
```
Expected: qbdlx serves a stream / lands a FLAC; NO `pool fp mismatch` warning; NO all-dead badge.

- [ ] **Step 6: Commit**

```bash
git add data/download/build.gradle.kts \
        data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/di/QbdlxModule.kt
git commit -m "feat(qbdlx): encrypt bundled pool + QBDLX_POOL_FP + runtime drift warning"
```

---

## Task 7: Settings ViewModel — token choices + pinned flows

**Files:**
- Modify: `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsViewModel.kt`

- [ ] **Step 1: Add the flows ABOVE the `init` block**

Near the existing `_qbdlxExpired` declaration (which is already above `init` for the same NPE reason), add:

```kotlin
    private val _qbdlxTokenChoices =
        MutableStateFlow<List<com.stash.data.download.lossless.qbdlx.QbdlxTokenChoice>>(emptyList())
    val qbdlxTokenChoices:
        StateFlow<List<com.stash.data.download.lossless.qbdlx.QbdlxTokenChoice>> = _qbdlxTokenChoices

    private val _qbdlxPinnedToken = MutableStateFlow<String?>(null)
    val qbdlxPinnedToken: StateFlow<String?> = _qbdlxPinnedToken
```

- [ ] **Step 2: Refresh them in `init` and after paste/pin**

In `init{}`, alongside `refreshQbdlxExpired()`, add `refreshQbdlxTokens()`. Add the function near `refreshQbdlxExpired`:

```kotlin
    private fun refreshQbdlxTokens() {
        viewModelScope.launch {
            _qbdlxTokenChoices.value = qbdlxCredentialStore.poolForPicker()
            _qbdlxPinnedToken.value = qbdlxCredentialStore.pinnedToken()
        }
    }

    /** Pin a specific pool token (or null = Auto), then refresh the picker state. */
    fun onQbdlxTokenPinned(token: String?) {
        viewModelScope.launch {
            qbdlxCredentialStore.setPinnedToken(token)
            _qbdlxPinnedToken.value = qbdlxCredentialStore.pinnedToken()
            _qbdlxTokenChoices.value = qbdlxCredentialStore.poolForPicker()
        }
    }
```

Extend `onQbdlxTokenPaste` to also refresh choices (paste can flip a live dot):

```kotlin
    fun onQbdlxTokenPaste(token: String) {
        viewModelScope.launch {
            qbdlxCredentialStore.setPastedToken(token.ifBlank { null })
            _qbdlxExpired.value = qbdlxCredentialStore.allDead()
            _qbdlxTokenChoices.value = qbdlxCredentialStore.poolForPicker()
        }
    }
```

- [ ] **Step 3: Build the module — compiles**

Run: `./gradlew :feature:settings:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run the existing settings VM tests — still green**

Run: `./gradlew :feature:settings:testDebugUnitTest --tests "*SettingsViewModelTest*"`
Expected: PASS (no existing test touches the new flows; the qbdlxCredentialStore mock returns defaults — `poolForPicker` default is an empty list via the relaxed mock, which is fine).

> If `SettingsViewModelTest` uses a strict mock for `qbdlxCredentialStore`, add stubs: `coEvery { qbdlxCredentialStore.poolForPicker() } returns emptyList()` and `coEvery { qbdlxCredentialStore.pinnedToken() } returns null` (mirrors the existing `allDead()` stub pattern — check the test's setup).

- [ ] **Step 5: Commit**

```bash
git add feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsViewModel.kt \
        feature/settings/src/test/kotlin/com/stash/feature/settings/SettingsViewModelTest.kt
git commit -m "feat(settings): expose qbdlx token choices + pinned-token flow"
```

---

## Task 8: Settings UI — the "Token N" picker

**Files:**
- Modify: `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsAudioQualityScreen.kt`

- [ ] **Step 1: Collect the new flows**

Near the existing `qbdlxEnabled` / `qbdlxExpired` collectors (lines ~78-79):

```kotlin
    val qbdlxTokenChoices by viewModel.qbdlxTokenChoices.collectAsStateWithLifecycle()
    val qbdlxPinnedToken by viewModel.qbdlxPinnedToken.collectAsStateWithLifecycle()
```

- [ ] **Step 2: Add the picker inside the existing `qbdlxEnabled` AnimatedVisibility block, above the paste field**

Inside the `Column` at line ~156 (after the `qbdlxExpired` badge, before the paste `OutlinedTextField`), add:

```kotlin
                                if (qbdlxTokenChoices.size > 1) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Account",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Column(modifier = Modifier.selectableGroup()) {
                                        SettingsPickerRow(
                                            selected = qbdlxPinnedToken == null,
                                            title = "Auto",
                                            subtitle = "Recommended — uses a working account and fails over",
                                            onClick = { viewModel.onQbdlxTokenPinned(null) },
                                        )
                                        qbdlxTokenChoices.forEach { choice ->
                                            SettingsPickerRow(
                                                selected = qbdlxPinnedToken == choice.token,
                                                title = choice.label,
                                                subtitle = choice.country +
                                                    if (choice.live) "" else " · offline",
                                                onClick = { viewModel.onQbdlxTokenPinned(choice.token) },
                                            )
                                        }
                                    }
                                }
```

Add the import: `import com.stash.feature.settings.components.SettingsPickerRow` (if not already imported in this file — check; it's used elsewhere in the screen so likely present).

> Shown only when there's more than one token (`> 1`) — a single-token or tokenless build keeps just the paste field + badge, so the picker never offers a pointless one-item choice. A pinned token not in the current list leaves "Auto" selected (no `choice.token` matches), matching the store's stale-pin fallback.

- [ ] **Step 3: Build the module — compiles**

Run: `./gradlew :feature:settings:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Device-verify the picker**

Install a debug build with a ≥2-token pool, open Settings → Audio Quality → enable qbdlx, confirm: "Auto" + "Token 1 · GB", "Token 2 · FR"… render; tapping one pins it (persists across app restart); "Auto" clears it.

```bash
./gradlew :app:installDebug
```
Expected: picker renders with anonymized labels; selection persists.

- [ ] **Step 5: Commit**

```bash
git add feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsAudioQualityScreen.kt
git commit -m "feat(settings): qbdlx 'Token N' account picker (Auto default)"
```

---

## Task 9: `release.yml` — CI auto-fetch pool + rewrite verify gate

**Files:**
- Modify: `.github/workflows/release.yml`

- [ ] **Step 1: Add a fetch step BEFORE "Assemble release APK"**

Insert after "Decode keystore from secret" and before "Assemble release APK":

```yaml
      - name: Fetch qbdlx token pool
        env:
          QBDLX_TOKEN_POOL_SECRET: ${{ secrets.QBDLX_TOKEN_POOL }}
        run: |
          # Fetch the CURRENT shared pool so every release ships live tokens.
          # Reachable from datacenter IPs (verified). Any failure → fall back to
          # the QBDLX_TOKEN_POOL secret; the build must NEVER break on this.
          UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
          POOL=$(curl -sf -A "$UA" \
                   -H "Origin: https://qbdlx.launchpd.cloud" \
                   -H "Referer: https://qbdlx.launchpd.cloud/" \
                   https://citegptapi.f5.si/webhook/qbdlx/shared \
                 | jq -r '[.[] | select(.token!=null and .token!="" and (.app_id|tostring)=="798273057"
                          and .country!=null and .country!="")]
                          | unique_by(.token) | map(.token+":"+.country) | join(",")' 2>/dev/null) || POOL=""
          if [ -n "$POOL" ]; then
            echo "Fetched $(echo "$POOL" | tr ',' '\n' | wc -l) qbdlx tokens from the shared pool"
            echo "QBDLX_TOKEN_POOL=$POOL" >> "$GITHUB_ENV"
          else
            echo "::warning::qbdlx pool fetch failed/empty — falling back to the QBDLX_TOKEN_POOL secret"
            echo "QBDLX_TOKEN_POOL=$QBDLX_TOKEN_POOL_SECRET" >> "$GITHUB_ENV"
          fi
```

- [ ] **Step 2: Drop the hardcoded pool env from the assemble step**

In "Assemble release APK", **remove** the line:

```yaml
          QBDLX_TOKEN_POOL: ${{ secrets.QBDLX_TOKEN_POOL }}
```

Keep `QBDLX_APP_ID` and `QBDLX_APP_SECRET` as `secrets.*`. `QBDLX_TOKEN_POOL` now flows from `$GITHUB_ENV` (fetched or fallback), which the runner exposes to the Gradle process automatically.

- [ ] **Step 3: Rewrite the "Verify bundled credentials embedded in APK" step**

Replace its `env:` and `run:` with:

```yaml
      - name: Verify bundled credentials embedded in APK
        env:
          QBDLX_APP_ID: ${{ secrets.QBDLX_APP_ID }}
          # QBDLX_TOKEN_POOL comes from $GITHUB_ENV (the fetch step) — do NOT
          # redeclare it as the secret here, or the fingerprint would be computed
          # over the secret while the dex carries the fetched pool → false-fail.
        run: |
          if [ -z "$QBDLX_APP_ID" ] || [ -z "$QBDLX_TOKEN_POOL" ]; then
            echo "::error::QBDLX_APP_ID / QBDLX_TOKEN_POOL not available"
            exit 1
          fi
          DEX_DIR="$RUNNER_TEMP/dexcheck"
          mkdir -p "$DEX_DIR"
          unzip -o -q "${{ steps.find_apk.outputs.apk_path }}" 'classes*.dex' -d "$DEX_DIR"

          # 1. app_id must be present (BuildConfig wired; still plaintext).
          if ! grep -a -q -F -- "$QBDLX_APP_ID" "$DEX_DIR"/classes*.dex; then
            echo "::error::app_id missing from APK dex — blank BuildConfig?"
            exit 1
          fi
          # 2. The plaintext first token must be ABSENT (encryption actually ran).
          FIRST_TOKEN="${QBDLX_TOKEN_POOL%%:*}"
          if grep -a -q -F -- "$FIRST_TOKEN" "$DEX_DIR"/classes*.dex; then
            echo "::error::plaintext qbdlx token found in dex — pool was NOT encrypted"
            exit 1
          fi
          # 3. The pool fingerprint must be present (current, non-blank pool shipped).
          FP=$(printf %s "$QBDLX_TOKEN_POOL" | sha256sum | head -c 8)
          if ! grep -a -q -F -- "$FP" "$DEX_DIR"/classes*.dex; then
            echo "::error::QBDLX_POOL_FP ($FP) missing from dex — stale/blank/mismatched pool"
            exit 1
          fi
          echo "qbdlx pool verified: app_id present, token encrypted, fp $FP embedded"
```

- [ ] **Step 4: Lint the YAML**

```bash
python -c "import yaml,sys; yaml.safe_load(open('.github/workflows/release.yml')); print('YAML OK')"
```
Expected: `YAML OK`.

- [ ] **Step 5: Dry-run the fetch + fingerprint locally (no secrets needed)**

```bash
UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
POOL=$(curl -sf -A "$UA" -H "Origin: https://qbdlx.launchpd.cloud" -H "Referer: https://qbdlx.launchpd.cloud/" \
        https://citegptapi.f5.si/webhook/qbdlx/shared \
      | jq -r '[.[] | select(.token!=null and .token!="" and (.app_id|tostring)=="798273057"
               and .country!=null and .country!="")] | unique_by(.token)
               | map(.token+":"+.country) | join(",")')
echo "tokens: $(echo "$POOL" | tr ',' '\n' | wc -l)"
echo "countries: $(echo "$POOL" | tr ',' '\n' | sed 's/.*://' | sort -u | tr '\n' ' ')"
echo "fp: $(printf %s "$POOL" | sha256sum | head -c 8)"
```
Expected: ~4 tokens across AR/FR/GB/NO, an 8-char fp. (Confirms the jq filter + dedupe work against the live endpoint. Do NOT print `$POOL` itself.)

- [ ] **Step 6: Commit**

```bash
git add .github/workflows/release.yml
git commit -m "ci(qbdlx): auto-fetch live pool at build + fp-verified encrypted dex gate"
```

---

## Task 10: Full-suite check + secret sync note

- [ ] **Step 1: Run the full data:download + settings unit suites (filtered to avoid the flaky core:media network test)**

```bash
./gradlew :data:download:testDebugUnitTest --tests "*Qbdlx*"
./gradlew :feature:settings:testDebugUnitTest --tests "*SettingsViewModel*"
```
Expected: all PASS.

- [ ] **Step 2: Confirm the `QBDLX_TOKEN_POOL` Actions secret is a current fallback**

The CI fetch is primary, but the secret is the fallback when the webhook is down. Ensure it holds a currently-valid pool (plaintext `token:country,...`). If stale, refresh it:

```bash
# (run by the user — needs gh auth) update BOTH local.properties and the secret
gh secret set QBDLX_TOKEN_POOL --body "tokA:FR,tokB:GB,tokC:AR,tokD:NO"
```
This is a note, not an automated step — flag it to the user at execution end.

- [ ] **Step 3: Final on-device smoke (release build from Task 6) — qbdlx plays, no badge, no fp warning.** Already covered in Task 6 Step 5; re-confirm after the UI + CI changes if a fresh release build was produced.

---

## Done criteria

- All `*Qbdlx*` unit tests + `SettingsViewModel` tests green.
- Release APK: app_id present, plaintext token absent, `QBDLX_POOL_FP` present in dex.
- Device: qbdlx resolves/plays; Settings picker shows anonymized "Token N" + Auto, selection persists, no `pool fp mismatch` warning, no all-dead badge.
- `release.yml`: fetch step present, pool env removed from assemble, verify gate rewritten; YAML valid; live fetch dry-run yields ~4 tokens.

## Post-merge memory update
Update `project_qbdlx_multitoken_pool.md` → shipped state (files, the sticky/pinned/encrypt/CI-fetch behavior, the two-copy-crypto + `$GITHUB_ENV` gotchas), and cross-link `project_qbdlx_source` + `project_lossless_redundancy_roadmap`.
