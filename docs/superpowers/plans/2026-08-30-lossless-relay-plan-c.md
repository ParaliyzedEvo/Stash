# Plan C — Pool Deletion, Honesty UI, Custom Endpoint

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stash stops shipping anyone's Qobuz credentials, and every lossless surface tells the truth about what is actually configured.

**Architecture:** Subtraction first, then addition. Tasks 1–3 delete the shipped token pool (code, BuildConfig fields, CI steps) and the Settings controls that existed only for it; the branch compiles and the app works on BYO + runtime-config relays after each. Tasks 4–6 replace the hardcoded "Qobuz — active" row with state read from `LosslessAvailability`/`LosslessConfigFetcher`, add the Advanced custom-endpoint field, and generalise the ARCOD-rescue banner. Task 7 is README/comment hygiene; Task 8 is the release gate.

**Tech Stack:** Kotlin, Hilt, Compose (M3 + the project's glass/extended theme), DataStore, OkHttp, MockK/Robolectric/Truth, Gradle.

**Base:** `master` @ `4622a142` (Plan A1 merged + device-verified). Worktree `.worktrees/lossless-plan-c`, branch `feat/lossless-plan-c`.

**Spec:** `docs/superpowers/specs/2026-08-29-stash-lossless-relay-design.md` — Plan C is spec lines 134–148 (honesty UI + Settings), 159 (rollout step 3), 169 (hygiene).

---

## Scope decisions made when writing this plan

1. **Plan C runs before Plan B.** The spec gates Plan C on "a week of soft-launch data" from the relay. That gate's *intent* — don't delete the pool until the replacement is proven — is satisfied: the pool is provably dead on device (2026-08-30: `403 USER_BLOCKED`, four `TokenDead` classifications on one track), and the BYO replacement is device-verified serving **FLAC 24/96**. What genuinely needs Plan B is the relay **health readout** (`GET /v1/status` → `accounts_live`), which no relay exists to answer. So the "Stash lossless" row renders from local state only — *configured* (a signed config listing ≥1 relay) vs *not configured* vs *cooling* — and gains live health in Plan B. No `/v1/status` call is written in this plan.

2. **The bundled signing pair goes with the pool.** `QBDLX_APP_ID`/`QBDLX_APP_SECRET`/`QBDLX_APP_SECRETS` leave the APK. BYO is unaffected — a connected account signs with the pair `QobuzLoginClient` scraped at login and stored beside its token (`signingFor` already prefers that). The one consumer that needed the bundled pair is the legacy pasted-token migration, which is re-pointed at `QobuzWebCredentialsClient.fetch()` (the same live scrape login already uses, exercised successfully on device 2026-08-30).

3. **Failure mode accepted for legacy pasted tokens.** A pasted token was minted under the *Android* app_id; after this plan it is signed with the *web* pair. If Qobuz rejects that combination, `getFileUrl` returns `TokenDead`, the credential is marked dead, and Settings shows "no lossless source configured — connect your account". That is graceful and recoverable (the user connects their account), and it cannot be tested without a raw pasted token. **Do not** keep the bundled secrets alive just to hedge it — dropping shipped credentials is the point of this plan.

---

## File structure

| File | Change |
|---|---|
| `…/lossless/qbdlx/QbdlxCredentialStore.kt` | ~602 → ~230 lines: keep the login slot + dead-cooldown, delete every pool member |
| `…/lossless/qbdlx/QbdlxPoolProvider.kt`, `QbdlxPoolCipher.kt`, `QbdlxRemotePool.kt` | **delete** |
| `…/test/…/QbdlxPoolCipherTest.kt`, `QbdlxPoolRefreshTest.kt` | **delete** |
| `…/lossless/qbdlx/di/QbdlxModule.kt` | drop pool bindings/providers |
| `data/download/build.gradle.kts` | drop `QBDLX_APP_ID/SECRET/SECRETS/TOKEN_POOL/POOL_FP` + prop readers |
| `.github/workflows/release.yml` | delete the pool fetch/probe step and the pool dex-verification block |
| `feature/settings/…/SettingsViewModel.kt` | drop picker/paste members |
| `feature/settings/…/SettingsAudioQualityScreen.kt` | drop picker/paste/expired-badge; add custom-endpoint field |
| `feature/settings/…/components/LosslessRoutingStatus.kt` | hardcoded row → stateful source list |
| `feature/home/…/HomeViewModel.kt` | `arcodRescueFlow` → `losslessOfflineFlow` on `anyUserOwned` |
| `…/lossless/LosslessSourcePreferences.kt` | add `losslessOfflineDismissed` |
| `README.md` | truthful claims |

---

### Task 1: `QbdlxCredentialStore` sheds the pool

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxCredentialStore.kt`
- Delete: `…/qbdlx/QbdlxPoolProvider.kt`, `…/qbdlx/QbdlxPoolCipher.kt`, `…/qbdlx/QbdlxRemotePool.kt`
- Delete: `…/test/…/qbdlx/QbdlxPoolCipherTest.kt`, `…/test/…/qbdlx/QbdlxPoolRefreshTest.kt`
- Modify: `…/qbdlx/di/QbdlxModule.kt`
- Test: `…/test/…/qbdlx/QbdlxCredentialStoreTest.kt`, `QbdlxSigningTest.kt`

- [ ] **Step 1: Rewrite `QbdlxCredentialStoreTest` to the surviving surface**

Keep only tests that exercise: `hasLogin` (incl. a pasted token awaiting migration), `loginLive`, `loginCredential`, `setUserCredential`/`clearUserCredential`, `connectedEmail`, `markDead`/`recordAlive` cooldown behaviour, `setPastedToken` re-arm, and the migration. Delete every pool test (`activeToken` stickiness, `tokensForRegion`, `poolForPicker`, pinning, `allDead`). Rewrite the two migration tests so the pair comes from an injected fake web-credentials client, not BuildConfig:

```kotlin
    private val webCreds = QobuzWebCredentialsClient(OkHttpClient()).apply { /* replaced below */ }

    // Simplest seam: the store takes a `suspend () -> QobuzWebCreds?` lambda.
    private fun store(creds: QobuzWebCreds? = QobuzWebCreds("712109809", "web-secret")) =
        QbdlxCredentialStore(ctx) { creds }

    @Test
    fun `a pasted token is migrated using the scraped web pair`() = runTest {
        store().setPastedToken("pasted-tok")
        val s = store()
        assertThat(s.loginCredential())
            .isEqualTo(QbdlxLoginCredential("pasted-tok", "712109809", "web-secret"))
        assertThat(s.connectedEmail()).isNull()
        s.clearUserCredential()
        assertThat(store().loginCredential()).isNull()   // pasted key was consumed
    }

    @Test
    fun `migration is skipped and the pasted token kept when the scrape fails`() = runTest {
        store().setPastedToken("pasted-tok")
        val s = store(creds = null)
        assertThat(s.loginCredential()).isNull()
        // The key survives for the next attempt — a transient scrape failure must not eat it.
        assertThat(store().hasLogin.first()).isTrue()
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :data:download:testDebugUnitTest --tests "*QbdlxCredentialStoreTest" --max-workers=3 -Dorg.gradle.jvmargs=-Xmx4096m`
Expected: compile FAIL (constructor).

- [ ] **Step 3: Implement**

Constructor becomes `QbdlxCredentialStore @Inject constructor(@ApplicationContext context, private val webCreds: QobuzWebCredentials)` where `QobuzWebCredentials` is a `fun interface { suspend fun fetch(): QobuzWebCreds? }` bound in `QbdlxModule` to `QobuzWebCredentialsClient::fetch` (keeps the store testable without OkHttp and avoids a DI cycle — `QobuzWebCredentialsClient` injects only `OkHttpClient`).

**Delete** from the class: `poolProvider`/`remotePool` params, `poolRaw`, `cachedPoolKey`, `cacheLoaded`, `lastRefreshAttempt`, `ensureCacheLoaded()`, `refreshIfExhausted()`, `pool()`, `PoolEntry`, `poolAppId()`, `appSecretMap()`, `appSecretsRaw`, `primaryAppId`, `primaryAppSecret`, `pinnedTokenKey`, `pinnedToken()`, `setPinnedToken()`, `poolForPicker()`, `QbdlxTokenChoice`, `activeToken()`, `activePrimary`, `tokensForRegion()`, `MAX_REGION_TRIES`, `allDead()`, `lastFailedAt`, `authFailureStreak`, `REFRESH_FAILURE_STREAK`.

**Keep**: the login-slot keys and accessors, `hasLogin`, `loginLive()`, `loginCredential()`, `setUserCredential()`, `clearUserCredential()`, `connectedEmail()`, `deadUntil`/`isDead()`/`markDead()`/`recordAlive()`/`DEAD_COOLDOWN_MS`, `pastedTokenKey`/`pastedToken()`/`setPastedToken()`, `migratePastedToken()`, `clearPersistedForTest()`.

`migratePastedToken()` becomes:

```kotlin
    /**
     * One-shot upgrade path for a token pasted before the pool left the app. The
     * pair is scraped live ([QobuzWebCredentials]) rather than bundled — this app
     * ships no Qobuz app_secret. A failed scrape leaves `pasted_token` in place so
     * the next attempt can still migrate it.
     */
    private suspend fun migratePastedToken() {
        val pasted = pastedToken() ?: return
        val creds = webCreds.fetch() ?: run {
            Log.i(TAG, "pasted token not migrated: web credentials unavailable — will retry")
            return
        }
        Log.i(TAG, "migrating pasted token into the connected-account slot")
        setUserCredential(pasted, creds.appId, creds.appSecret, email = null)
        context.qbdlxCredentialsDataStore.edit { if (it[pastedTokenKey] == pasted) it.remove(pastedTokenKey) }
    }
```

`signingFor()` collapses to the connected account only:

```kotlin
    override suspend fun signingFor(token: String): QbdlxSigning {
        loginCredential()?.let { if (it.token == token) return QbdlxSigning(it.appId, it.appSecret) }
        // Unreachable in production: QbdlxFileUrlRouter only ever signs the connected
        // account's token. Log rather than fabricate a pair that would 401 silently.
        Log.w(TAG, "signingFor called for a token that is not the connected account")
        return QbdlxSigning("", "")
    }
```

Delete the three pool files and their two test files. In `QbdlxModule`: delete `bindQbdlxRemotePool`, `provideQbdlxPoolProvider`, and the `QbdlxPoolCipher`/`BuildConfig` imports; add the `QobuzWebCredentials` binding. Update `QbdlxSigningTest` — drop the pool/BuildConfig-fallback cases, keep the login-pair case.

- [ ] **Step 4: Run the module's qbdlx tests**

Run: `./gradlew :data:download:testDebugUnitTest --tests "*Qbdlx*" --max-workers=3 -Dorg.gradle.jvmargs=-Xmx4096m`
Expected: PASS. Any compile error is a caller of a deleted member — fix the call site, do not re-add the member. `SettingsViewModel` WILL break here (`allDead`, `poolForPicker`, `pinnedToken`); that is Task 2's job, so it is acceptable for `:feature:settings` to be red until Task 2 lands. Do not stage a half-fix into it.

- [ ] **Step 5: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/ data/download/src/test/kotlin/com/stash/data/download/lossless/qbdlx/
git commit -m "refactor(qbdlx): the credential store sheds the token pool"
```

---

### Task 2: Settings loses the pool controls

**Files:**
- Modify: `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsViewModel.kt`
- Modify: `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsAudioQualityScreen.kt`
- Test: `feature/settings/src/test/kotlin/com/stash/feature/settings/SettingsViewModelTest.kt`

- [ ] **Step 1: Delete the dead members**

VM: remove `_qbdlxTokenChoices`/`qbdlxTokenChoices`, `_qbdlxPinnedToken`/`qbdlxPinnedToken`, `onQbdlxTokenPinned`, `onQbdlxTokenPaste`, and every `poolForPicker()`/`pinnedToken()`/`setPastedToken()` call. Keep `qbdlxExpired` (already `!LosslessAvailability.qbdlxEnabled` since A1), `qobuzConnectedEmail`, `onConnectQobuz`, `onDisconnectQobuz`.

Screen: remove the `qbdlxTokenChoices.size > 1` picker block, the "Paste token" `OutlinedTextField` and its `draft`/`committed`/`wasFocused`/`commitToken`/`LaunchedEffect` state, and the now-unused imports. Reword the badge at the top of the section from `"No working token — connect your account below"` to `"No lossless source configured — connect your Qobuz account below"`.

- [ ] **Step 2: Update `SettingsViewModelTest`** — delete stubs for the removed members; the `qbdlxExpired` tests stay.

- [ ] **Step 3: Verify** — `./gradlew :feature:settings:testDebugUnitTest :feature:settings:compileDebugKotlin --max-workers=3 -Dorg.gradle.jvmargs=-Xmx4096m` → green.

- [ ] **Step 4: Commit**

```bash
git add feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsViewModel.kt feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsAudioQualityScreen.kt feature/settings/src/test/kotlin/com/stash/feature/settings/SettingsViewModelTest.kt
git commit -m "refactor(settings): drop the token picker and paste field with the pool"
```

---

### Task 3: BuildConfig and CI shed the pool secrets

**Files:**
- Modify: `data/download/build.gradle.kts`
- Modify: `.github/workflows/release.yml`

- [ ] **Step 1: `data/download/build.gradle.kts`** — delete `qbdlxAppId`, `qbdlxAppSecret`, `qbdlxAppSecrets`, `qbdlxTokenPool`, `qbdlxTokenPoolEnc`, `qbdlxPoolFp` and the five `buildConfigField` lines (`QBDLX_APP_ID`, `QBDLX_APP_SECRET`, `QBDLX_APP_SECRETS`, `QBDLX_TOKEN_POOL`, `QBDLX_POOL_FP`). Keep `qbdlxProp()` if `LOSSLESS_CONFIG_URL`/`LOSSLESS_CONFIG_PUBKEY` still use it; delete it if not. Keep every `ARCOD_*` field untouched.

- [ ] **Step 2: `.github/workflows/release.yml`** — delete the whole `Fetch qbdlx token pool` step (~L85–198) and, in the dex-verification step, the qbdlx block (app_id present / token encrypted / `QBDLX_POOL_FP` embedded — ~L264–295), leaving the ARCOD check and its `if`/`exit` structure valid. Remove `QBDLX_APP_ID`/`QBDLX_APP_SECRET`/`QBDLX_APP_SECRETS`/`QBDLX_TOKEN_POOL` from every `env:` block. **Read the whole file before editing** — a broken release workflow is only discovered at tag time.

- [ ] **Step 3: Verify** — `./gradlew :app:assembleDebug --max-workers=3 -Dorg.gradle.jvmargs=-Xmx4096m` → BUILD SUCCESSFUL, then confirm the field is gone:
`git grep -n "QBDLX_APP_ID\|QBDLX_TOKEN_POOL\|QBDLX_POOL_FP\|QBDLX_APP_SECRET" -- "*.kt" "*.kts" ".github/**"` → **no matches**.
Also run `yamllint` if available, else `python -c "import yaml,sys; yaml.safe_load(open('.github/workflows/release.yml'))"` to prove the workflow still parses.

- [ ] **Step 4: Commit**

```bash
git add data/download/build.gradle.kts .github/workflows/release.yml
git commit -m "chore(build): stop shipping Qobuz app credentials and the token pool"
```

---

### Task 4: `LosslessRoutingStatus` becomes stateful

**Files:**
- Modify: `feature/settings/src/main/kotlin/com/stash/feature/settings/components/LosslessRoutingStatus.kt`
- Modify: `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsViewModel.kt` (expose the state)
- Modify: `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsAudioQualityScreen.kt` (pass it)
- Test: `feature/settings/src/test/kotlin/com/stash/feature/settings/SettingsViewModelTest.kt`

- [ ] **Step 1: Write the failing VM test**

```kotlin
    @Test
    fun `routing rows report what is actually configured`() = runTest {
        // no account, no relay, no custom endpoint, no ARCOD
        val vm = newVm(qbdlxConfigured = false, connectedEmail = null, relays = emptyList(), arcod = null)
        vm.losslessRouting.test {           // or collect into a list
            val rows = awaitItem()
            assertThat(rows.first { it.id == "qobuz" }.state).isEqualTo(RoutingState.NOT_CONFIGURED)
            assertThat(rows.first { it.id == "relay" }.state).isEqualTo(RoutingState.NOT_CONFIGURED)
        }
    }
```
(Use whatever collection idiom `SettingsViewModelTest` already uses — it collects `StateFlow`s with a live collector because of `WhileSubscribed`.)

- [ ] **Step 2: Implement**

Add to the settings package:

```kotlin
enum class RoutingState { CONNECTED, CONFIGURED, NOT_CONFIGURED }

data class RoutingRowState(
    val id: String,
    val label: String,
    val detail: String,
    val state: RoutingState,
)
```

VM exposes:

```kotlin
    /**
     * What each lossless source actually is right now — no hardcoded "active".
     * Relay health (accounts_live) needs Plan B's /v1/status; until then a relay
     * is "configured" when the signed runtime config lists at least one.
     */
    val losslessRouting: StateFlow<List<RoutingRowState>> = combine(
        qobuzConnectedEmail,
        losslessConfigFetcher.relays,
        losslessPrefs.customLosslessEndpoint,
        arcodCredentialStore.accessToken,
    ) { email, relays, custom, arcodToken ->
        listOf(
            RoutingRowState("qobuz", "Your Qobuz account",
                email?.let { "connected as $it" } ?: "not connected",
                if (email != null) RoutingState.CONNECTED else RoutingState.NOT_CONFIGURED),
            RoutingRowState("relay", "Stash lossless",
                if (relays.isNotEmpty()) "configured" else "not configured",
                if (relays.isNotEmpty()) RoutingState.CONFIGURED else RoutingState.NOT_CONFIGURED),
        ) +
            (custom?.let {
                listOf(RoutingRowState("custom", "Custom endpoint", "configured", RoutingState.CONFIGURED))
            } ?: emptyList()) +
            listOf(RoutingRowState("arcod", "ARCOD",
                if (arcodToken != null) "connected" else "not connected",
                if (arcodToken != null) RoutingState.CONNECTED else RoutingState.NOT_CONFIGURED))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
```

`LosslessRoutingStatus(rows: List<RoutingRowState>, modifier: Modifier = Modifier)` renders the same visual language it has today (mono `ROUTING` header, `↳` rows, status dots) driven by `rows`. Keep the existing footer sentence but drop the "Lossless comes from Qobuz" claim in favour of: *"Lossless comes from your connected account or Stash's relay when one is configured. Misses try JioSaavn AAC 320 before falling back to YouTube, shown as \"via YT\" while it plays."* Update the file's KDoc: the honesty caveat now says health telemetry arrives with the relay's `/v1/status` (Plan B); rows describe configuration, not liveness.

- [ ] **Step 3: Verify** — `:feature:settings` tests + compile green.

- [ ] **Step 4: Commit**

```bash
git commit -m "feat(settings): the routing list reports real state instead of a hardcoded row"
```

---

### Task 5: Advanced — custom lossless endpoint

**Files:**
- Modify: `feature/settings/…/SettingsViewModel.kt`, `SettingsAudioQualityScreen.kt`
- Test: `feature/settings/…/SettingsViewModelTest.kt`

- [ ] **Step 1: Write the failing VM tests** — `onCustomEndpointCommitted("https://relay.example/")` stores the normalised `https://relay.example`; a non-https or malformed value clears the key and surfaces an error message; `onTestCustomEndpoint()` sets a result state.

- [ ] **Step 2: Implement**

VM: `val customEndpoint: StateFlow<String?>` from `losslessPrefs.customLosslessEndpoint`; `fun onCustomEndpointCommitted(raw: String)` → `losslessPrefs.setCustomLosslessEndpoint(raw)` plus a `customEndpointError: StateFlow<String?>` set when `LosslessSourcePreferences.normaliseEndpoint(raw)` returns null for a non-blank input ("Must be an https:// URL"). `fun onTestCustomEndpoint()` → `LosslessRelayClient.mint(base, PROBE_TRACK_ID, PROBE_FORMAT_ID)` is **not** used (it would spend relay budget and needs a real track); instead add `suspend fun probe(base: String): Boolean` to `LosslessRelayClient` performing `GET <base>/v1/status` and reporting reachability, and map the outcome to `customEndpointTest: StateFlow<TestState>` (`Idle`/`Testing`/`Reachable`/`Unreachable`). Keep the probe's cooldown behaviour out of `isCooled` — a manual test must not cool the base.

Screen: an "Advanced" section below the routing list with an `OutlinedTextField` (label "Custom lossless endpoint", placeholder `https://…`) and a "Test" `TextButton`. **The field commits on IME Done / focus loss, never per keystroke** — reuse exactly the draft/`committed`/`wasFocused` pattern from the (now deleted) paste field, which exists in git history at `a1262511`; a per-keystroke write would persist `https://re` as a base. Show `customEndpointError` under the field and the test result beside the button.

- [ ] **Step 3: Verify** — `:feature:settings` + `:data:download` tests green.

- [ ] **Step 4: Commit**

```bash
git commit -m "feat(settings): custom lossless endpoint with a reachability test"
```

---

### Task 6: The Home banner generalises

**Files:**
- Modify: `data/download/…/lossless/LosslessSourcePreferences.kt` (add `losslessOfflineDismissed`)
- Modify: `feature/home/src/main/kotlin/com/stash/feature/home/HomeViewModel.kt`
- Modify: `feature/home/src/main/kotlin/com/stash/feature/home/HomeScreen.kt` (copy + dismiss wiring)
- Test: `feature/home/src/test/kotlin/com/stash/feature/home/HomeViewModelTest.kt`

- [ ] **Step 1: Write the failing test** — banner shows when `qbdlxLooksDown` **and** `anyUserOwned == false` **and** lossless on **and** not dismissed; and specifically that a **BYO-connected** user does *not* see it (today's ARCOD-only check would show it to them).

- [ ] **Step 2: Implement** — add `losslessOfflineDismissed` (new key; do **not** reuse `arcodRescueDismissed`, so users who dismissed the old banner still see this one once) with its setter. Rename `arcodRescueFlow` → `losslessOfflineFlow` and swap the `arcodCredentialStore.accessToken` leg for `losslessAvailability.anyUserOwned`:

```kotlin
    private val losslessOfflineFlow = combine(
        losslessSourceHealth.qbdlxLooksDown,
        losslessAvailability.anyUserOwned,
        losslessPrefs.enabled,
        losslessPrefs.losslessOfflineDismissed,
    ) { down, userOwned, losslessOn, dismissed -> down && !userOwned && losslessOn && !dismissed }
```

Banner copy: *"Lossless is offline right now — Stash is playing YouTube audio. Connect your own Qobuz account to keep FLAC."* CTA navigates to Settings › Audio. Update the KDoc that still explains the ARCOD-rescue framing.

- [ ] **Step 3: Verify** — `:feature:home:testDebugUnitTest` green **except** the two known-red `HomeViewModelTest playHero` matcher tests (pre-existing on master; see `infra_preexisting_matcher_test_failures`). Do not fix or delete those here.

- [ ] **Step 4: Commit**

```bash
git commit -m "feat(home): the lossless-offline banner reflects any user-owned source"
```

---

### Task 7: README and comment hygiene

**Files:** `README.md`, plus the stale comments listed below.

- [ ] **Step 1: README** — delete the "FLAC backbone" claim; fix the "only Spotify and YouTube" claim; name every service the app contacts; state plainly that lossless requires the user's own Qobuz account (or a relay they configure) and that Stash ships no one else's credentials; add a contact address. Do not describe how to obtain credentials.

- [ ] **Step 2: Stale comments** — `data/download/src/test/…/qbdlx/QbdlxSigningTest.kt` and any surviving comment citing `MAX_TOKEN_ATTEMPTS`, the pool, or `QBDLX_CONFIGURED`; `DownloadManager.kt:231`'s `QBDLX_CONFIGURED` mention (leave the surrounding logic — Plan A2 owns it) — reword to name `LosslessAvailability`. Run `git grep -n -iE "token pool|pooled token|QbdlxPool|MAX_TOKEN_ATTEMPTS" -- "*.kt" "*.md"` and resolve every hit or explain why it stays.

- [ ] **Step 3: Commit**

```bash
git commit -m "docs: README states what Stash actually contacts and what lossless needs"
```

---

### Task 8: Release gate, full tests, device check

- [ ] **Step 1:** `./gradlew compileDebugUnitTestKotlin --max-workers=3 -Dorg.gradle.jvmargs=-Xmx4096m` → BUILD SUCCESSFUL (this is the gate CI runs).
- [ ] **Step 2:** `./gradlew testDebugUnitTest --max-workers=3 -Dorg.gradle.jvmargs=-Xmx4096m --continue` → green except the **11 known-red-on-master** tests (2 `HomeViewModelTest` playHero + 8 `:feature:search` + 1 `TrackDownloaderImplDeferredTest`). Anything else is this plan's.
- [ ] **Step 3:** Grep sweep — `git grep -n "QBDLX_APP_ID\|QBDLX_TOKEN_POOL\|allDead()\|activeToken()\|poolForPicker\|tokensForRegion"` → no production matches.
- [ ] **Step 4: Device check** (Pixel 6, debug build; **verify the APK is fresh** — gradle exit code, APK mtime, `dumpsys package com.stash.app.debug | grep versionName` — per `feedback_verify_apk_freshness_before_device_proof`): with the account still connected, play a track not touched this session (`StreamUrlCache` hides re-resolves) → `qbdlx served` + `FLAC` badge; Settings › Audio shows "Your Qobuz account · connected as …", "Stash lossless · not configured"; disconnect → the source drops out of the chain and the Home banner appears.
- [ ] **Step 5:** Report; then `superpowers:finishing-a-development-branch`.
