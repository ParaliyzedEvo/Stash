# Plan C — Pool Deletion, Honesty UI, Custom Endpoint

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stash stops shipping anyone's Qobuz credentials, and every lossless surface tells the truth about what is actually configured.

**Architecture:** Callers first, then the thing they call. Task 1 removes the Settings controls that exist only for the pool; Task 2 then deletes pool members that nothing references; Task 3 strips the secrets from the build and CI. **Every task leaves the whole repo compiling** — no task may commit a red tree. Tasks 4–6 replace the hardcoded "Qobuz — active" row with state derived from `LosslessAvailability`, add the Advanced custom-endpoint field, and generalise the ARCOD-rescue banner. Task 7 is README/comment hygiene; Task 8 is the release gate.

**Tech Stack:** Kotlin, Hilt, Compose (M3 + the project's glass/extended theme), DataStore, OkHttp, MockK/Robolectric/Truth, Gradle.

**Base:** `master` @ `4622a142` (Plan A1 merged + device-verified). Worktree `.worktrees/lossless-plan-c`, branch `feat/lossless-plan-c`.

**Spec:** `docs/superpowers/specs/2026-08-29-stash-lossless-relay-design.md` — Plan C is spec lines ~134–148 (honesty UI + Settings), 159 (rollout step 3), 169 (hygiene).

---

## Scope decisions made when writing this plan

1. **Plan C runs before Plan B.** The spec gates Plan C on "a week of soft-launch data" from the relay. That gate's *intent* — don't delete the pool until the replacement is proven — is satisfied: the pool is provably dead on device (2026-08-30: `403 USER_BLOCKED`, four `TokenDead` classifications on one track), and the BYO replacement is device-verified serving **FLAC 24/96**. What genuinely needs Plan B is the relay **health** readout (`GET /v1/status` → `accounts_live`), which no relay exists to answer. So the "Stash lossless" row renders from local state only — *configured* (a signed config listing ≥1 relay) vs *not configured* — and gains live health in Plan B. **No `/v1/status` response is parsed in this plan**; Task 5's reachability probe treats *any* HTTP reply as "reachable" precisely because nothing serves that path yet.

2. **What users actually get when this ships.** `LOSSLESS_CONFIG_URL`/`LOSSLESS_CONFIG_PUBKEY` have never been wired into `release.yml`, so shipped APKs carry empty values, `LosslessConfigFetcher` is disabled, and `relays` stays empty. Until Plan B publishes a config, **lossless works for users with their own Qobuz account or a custom endpoint, and for nobody else** — the "Stash lossless" row reads "not configured" for everyone. Task 3 wires the two secrets through so publishing later needs no code change. The release note must say *"lossless comes from your own Qobuz account"* — **not** the spec's "from Stash's own relay when available", which would be false on the day this ships.

3. **The bundled signing pair goes with the pool.** `QBDLX_APP_ID`/`QBDLX_APP_SECRET`/`QBDLX_APP_SECRETS` leave the APK. BYO is unaffected — a connected account signs with the pair `QobuzLoginClient` scraped at login and stored beside its token (`signingFor` already prefers it, `QbdlxCredentialStore.kt:124`). The one consumer that needed the bundled pair is the legacy pasted-token migration, re-pointed at `QobuzWebCredentialsClient.fetch()` (the same live scrape login uses, exercised successfully on device 2026-08-30).

4. **A migrated pasted token that Qobuz rejects must self-clear.** A pasted token was minted under the *Android* app_id; after this plan it is signed with the *web* pair, which Qobuz may reject. `markDead()` only sets a **60-second** cooldown and never touches the login keys, so without a fix the result would be a permanent 60 s retry loop with Settings claiming a source is configured forever — reintroducing exactly the dishonest UI this plan deletes. Task 2 therefore makes `TokenDead` **terminal for a migrated (email-less) credential**: it is cleared, and the user is told to connect an account. A real connected account keeps the deliberate 60 s cooldown.

5. **Out of scope (spec:169 non-code hygiene):** moving the repo to an org, publishing an Obtainium release JSON, and moving the tipjar Worker off the real-name subdomain are ops tasks, not code, and are not part of this plan.

---

## File structure

| File | Change |
|---|---|
| `feature/settings/…/SettingsViewModel.kt` | drop picker/paste members; later gain routing rows + custom endpoint |
| `feature/settings/…/SettingsAudioQualityScreen.kt` | drop picker/paste/badge copy; later gain the Advanced field |
| `…/lossless/qbdlx/QbdlxCredentialStore.kt` | ~602 → ~230 lines: keep the login slot + dead-cooldown, delete every pool member |
| `…/lossless/qbdlx/QbdlxPoolProvider.kt`, `QbdlxPoolCipher.kt`, `QbdlxRemotePool.kt` | **delete** |
| `…/test/…/QbdlxPoolCipherTest.kt`, `QbdlxPoolRefreshTest.kt`, `QbdlxSigningTest.kt` | **delete** (one surviving case folds into `QbdlxCredentialStoreTest`) |
| `…/lossless/qbdlx/QbdlxFileUrlRouter.kt` | `TokenDead` is terminal for an email-less credential |
| `…/lossless/qbdlx/di/QbdlxModule.kt` | drop pool bindings; add the `QobuzWebCredentials` provider |
| `data/download/build.gradle.kts` | drop the five `QBDLX_*` fields, their prop readers, and the pool crypto helpers |
| `.github/workflows/release.yml` | delete the pool fetch step + pool dex checks; wire `LOSSLESS_CONFIG_*` |
| `…/lossless/LosslessAvailability.kt` | expose `routingRows` (it already injects every input) |
| `feature/settings/…/components/LosslessRoutingStatus.kt` | hardcoded row → stateful list |
| `…/lossless/relay/LosslessRelayClient.kt` | add `probe(base)` |
| `…/lossless/LosslessSourcePreferences.kt` | add `losslessOfflineDismissed` |
| `feature/home/…/HomeViewModel.kt`, `HomeUiState.kt`, `HomeScreen.kt` | banner generalises |
| `README.md` | truthful claims |

---

### Task 0: Capture the red baseline

**Why:** this plan's Task 8 must distinguish "pre-existing" from "mine". The A1 plan document says two known-red tests; a full run on `4622a142` on 2026-08-30 produced **eleven** (2 `HomeViewModelTest` playHero + 8 `:feature:search` ViewModel tests + 1 `TrackDownloaderImplDeferredTest`), all confirmed red on a detached `master` worktree and all the same `#418` 3-arg `setQueue` Mockito matcher-arity family. Do not trust either number — measure.

- [ ] **Step 1:** From the worktree at its base commit, run and save:
```bash
S="$SCRATCH"   # the session scratchpad dir; /tmp is not portable on this win32 box
./gradlew testDebugUnitTest --max-workers=3 -Dorg.gradle.jvmargs=-Xmx4096m --continue \
  > "$S/plan-c-baseline.log" 2>&1
grep -E "^[A-Za-z].* FAILED$" "$S/plan-c-baseline.log" | sort -u > "$S/plan-c-baseline-failures.txt"
```
If gradle dies with `java.net.BindException: Address already in use: bind` inside `FileLockCommunicator`, that is the shell sandbox, not a wedged daemon — rerun with the sandbox disabled. Record the count under Task 8 and keep `plan-c-baseline-failures.txt` for the final diff. Do not fix any of them here.

**Measured on `4622a142`, 2026-08-30: 11 failures** — `HomeViewModelTest` playHero ×2, `:feature:search` `AlbumDiscoveryViewModel*`/`ArtistProfileViewModelTest` ×8, `TrackDownloaderImplDeferredTest` ×1. Re-measure anyway; treat this line as a sanity check, not the source of truth.

---

### Task 1: Settings loses the pool controls

Runs **first** so Task 2 deletes members nothing references, keeping every commit green.

**Files:**
- Modify: `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsViewModel.kt`
- Modify: `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsAudioQualityScreen.kt`
- Test: `feature/settings/src/test/kotlin/com/stash/feature/settings/SettingsViewModelTest.kt`

- [ ] **Step 1: Delete the dead members**

VM: remove `_qbdlxTokenChoices`/`qbdlxTokenChoices`, `_qbdlxPinnedToken`/`qbdlxPinnedToken`, `onQbdlxTokenPinned`, `onQbdlxTokenPaste`, **and `refreshQbdlxTokens()` together with its call from `init`** (~`:1332-1337`, called ~`:446`). That removes every `poolForPicker()` / `pinnedToken()` / `setPinnedToken()` / `setPastedToken()` call. Keep `qbdlxExpired` (already `!LosslessAvailability.qbdlxEnabled` since A1), `qobuzConnectedEmail`, `onConnectQobuz`, `onDisconnectQobuz`. **Keep the `qbdlxCredentialStore` constructor param** even though it has no remaining use after this task — Task 4 needs it to expose `hasLogin` for the connect-form key. Removing it here would mean editing `newVm` twice.

Screen: remove the `qbdlxTokenChoices.size > 1` picker block (~`:290-314`), the "Paste token" `OutlinedTextField` and all of its `qbdlxToken`/`committed`/`wasFocused`/`commitToken`/`LaunchedEffect` state (~`:315-365`), and the imports that become unused. Reword the badge (~`:205`) from `"No working token — connect your account below"` to `"No lossless source configured — connect your Qobuz account below"`.

- [ ] **Step 2: Update `SettingsViewModelTest`** — delete the two `coVerify { …setPastedToken(…) }` tests (~`:101`, `:108`) and any stub for a removed member. The `qbdlxExpired` tests stay.

- [ ] **Step 3: Verify** — `./gradlew :feature:settings:testDebugUnitTest :feature:settings:compileDebugKotlin --max-workers=3 -Dorg.gradle.jvmargs=-Xmx4096m` → green.

- [ ] **Step 4: Commit**

```bash
git add feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsViewModel.kt feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsAudioQualityScreen.kt feature/settings/src/test/kotlin/com/stash/feature/settings/SettingsViewModelTest.kt
git commit -m "refactor(settings): drop the token picker and paste field with the pool"
```

---

### Task 2: `QbdlxCredentialStore` sheds the pool

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxCredentialStore.kt`
- Modify: `…/qbdlx/QbdlxFileUrlRouter.kt` (scope decision #4)
- Modify: `…/qbdlx/di/QbdlxModule.kt`
- Delete: `…/qbdlx/QbdlxPoolProvider.kt`, `QbdlxPoolCipher.kt`, `QbdlxRemotePool.kt`
- Delete: `…/test/…/qbdlx/QbdlxPoolCipherTest.kt`, `QbdlxPoolRefreshTest.kt`, `QbdlxSigningTest.kt`
- Test: `…/test/…/qbdlx/QbdlxCredentialStoreTest.kt`, `QbdlxFileUrlRouterTest.kt`

- [ ] **Step 1: Rewrite `QbdlxCredentialStoreTest` to the surviving surface**

Keep only: `hasLogin` (including a pasted token awaiting migration), `loginLive`, `loginCredential`, `setUserCredential`/`clearUserCredential`, `connectedEmail`, the `markDead`/`recordAlive` cooldown, `setPastedToken` re-arm, and migration. Delete every pool test. Fold in `QbdlxSigningTest`'s one surviving case (*a connected account signs with its own stored pair*), then delete that file. The store's new seam is a `fun interface`, so the fake is a lambda:

```kotlin
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
        assertThat(store().loginCredential()).isNull()   // the pasted key was consumed
    }

    @Test
    fun `migration is skipped and the pasted token kept when the scrape fails`() = runTest {
        store().setPastedToken("pasted-tok")
        assertThat(store(creds = null).loginCredential()).isNull()
        assertThat(store().hasLogin.first()).isTrue()    // key survives for the next attempt
    }
```

- [ ] **Step 2: Run to verify they fail** — compile FAIL (constructor).

- [ ] **Step 3: Implement**

Constructor: `QbdlxCredentialStore @Inject constructor(@ApplicationContext context, private val webCreds: QobuzWebCredentials)`, with a new `fun interface QobuzWebCredentials { suspend fun fetch(): QobuzWebCreds? }` beside `QobuzWebCredentialsClient`. Bind it with a `@Provides` in `QbdlxModule`'s **companion object** (`@Binds` cannot express it — the client does not implement the interface), using a bound reference:

```kotlin
        @Provides @Singleton
        fun provideQobuzWebCredentials(c: QobuzWebCredentialsClient) = QobuzWebCredentials(c::fetch)
```
No Hilt cycle: `QobuzWebCredentialsClient` injects only `OkHttpClient`, whose provider reaches `AmzCaptchaInterceptor`/`AmzCaptchaClient` and never the store.

**Delete** from the class: `poolProvider`/`remotePool` params, `poolRaw`, `cachedPoolKey`, `cacheLoaded`, `lastRefreshAttempt`, `ensureCacheLoaded()`, `refreshIfExhausted()`, `pool()`, `PoolEntry`, `poolAppId()`, `appSecretMap()`, `appSecretsRaw`, `primaryAppId`, `primaryAppSecret`, `pinnedTokenKey`, `pinnedToken()`, `setPinnedToken()`, `poolForPicker()`, `QbdlxTokenChoice`, `activeToken()`, `activePrimary`, `tokensForRegion()`, `MAX_REGION_TRIES`, `allDead()`, `lastFailedAt`, `authFailureStreak`, `REFRESH_FAILURE_STREAK`, **`REFRESH_MIN_INTERVAL_MS`**, **`PRIOR_GENERATION_STAMP`**.

**Keep** (some need rewriting): the login keys and accessors, `hasLogin`, `loginLive()`, `loginCredential()`, `setUserCredential()`, `clearUserCredential()`, `connectedEmail()`, `deadUntil`/`isDead()`/`DEAD_COOLDOWN_MS`, **`clock`** (`isDead` needs it), `pastedTokenKey`/`pastedToken()`/`setPastedToken()` (mark `setPastedToken` `internal` — after Task 1 it has no production caller and exists only for the migration tests), `migratePastedToken()`, `clearPersistedForTest()`. **`markDead()` and `recordAlive()` must be rewritten** — they currently touch `lastFailedAt`, `authFailureStreak` and `activePrimary`, all deleted; each collapses to one or two lines over `deadUntil`.

`migratePastedToken()` becomes:

```kotlin
    /**
     * One-shot upgrade path for a token pasted before the pool left the app. The
     * pair is scraped live ([QobuzWebCredentials]) rather than bundled — this app
     * ships no Qobuz app_secret. A failed scrape leaves `pasted_token` in place so
     * a later attempt can still migrate it.
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

Add the self-clearing rule from scope decision #4. Put the policy in the store so the router stays a router:

```kotlin
    /**
     * A connected account's token was rejected. A real account (it has an email)
     * gets the deliberate [DEAD_COOLDOWN_MS] cooldown — transient 401s must not
     * disconnect someone. A MIGRATED pasted token (no email) has no credentials to
     * re-mint from and was signed with a scraped pair Qobuz may simply refuse, so
     * rejection is terminal: clear it, and let Settings say "not configured".
     */
    suspend fun rejectLogin(token: String) {
        if (connectedEmail() == null) clearUserCredential() else markDead(token)
    }
```
and in `QbdlxFileUrlRouter` replace `credentialStore.markDead(login.token)` with `credentialStore.rejectLogin(login.token)`. Add a `QbdlxFileUrlRouterTest` case: an email-less login returning `TokenDead` calls `clearUserCredential`, and one with an email calls `markDead`.

Delete the three pool files and the three test files. In `QbdlxModule`: delete `bindQbdlxRemotePool`, `provideQbdlxPoolProvider`, and the `QbdlxPoolCipher`/`BuildConfig` imports.

**Rewrite two KDocs that Task 3's grep gate would otherwise fail on** (they name BuildConfig fields and both become false here): `QbdlxModule.kt:38-43` — "the ONLY thing this module @Provides is the [QbdlxSigner] (it needs the bundled app secret)" and "[QbdlxCredentialStore] reads `BuildConfig.QBDLX_APP_ID` itself for signing" (after this task the store reads no BuildConfig at all, and the module provides a second thing); and `QbdlxCredentialStore.kt:50` — "pairs (from [BuildConfig.QBDLX_TOKEN_POOL]) plus an optional user-pasted token", which still describes the pool as the class's architecture. Neither is caught by Task 7's hygiene grep (`TOKEN_POOL` is not `token pool`).

- [ ] **Step 4: Verify** — `./gradlew :data:download:testDebugUnitTest :feature:settings:compileDebugKotlin --max-workers=3 -Dorg.gradle.jvmargs=-Xmx4096m` → green (Task 1 already removed the callers, so nothing should be red).

- [ ] **Step 5: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/ data/download/src/test/kotlin/com/stash/data/download/lossless/qbdlx/
git commit -m "refactor(qbdlx): the credential store sheds the token pool"
```

---

### Task 3: BuildConfig and CI shed the pool secrets

**Files:** `data/download/build.gradle.kts`, `.github/workflows/release.yml`

- [ ] **Step 1: `data/download/build.gradle.kts`** — delete `qbdlxAppId`, `qbdlxAppSecret`, `qbdlxAppSecrets`, `qbdlxTokenPool`, `qbdlxTokenPoolEnc`, `qbdlxPoolFp`, the five `buildConfigField` lines (`QBDLX_APP_ID`, `QBDLX_APP_SECRET`, `QBDLX_APP_SECRETS`, `QBDLX_TOKEN_POOL`, `QBDLX_POOL_FP`), **the pool crypto helpers at ~`:72-90`** and the imports they were the only users of (`MessageDigest`, `SecretKeySpec`, `Cipher`, `GCMParameterSpec`, `SecureRandom`, `Base64`). **Keep** `qbdlxProp()` (`:62-63` still use it for `LOSSLESS_CONFIG_*`) and every `ARCOD_*` field.

- [ ] **Step 2: `.github/workflows/release.yml` — read lines 78–312 before editing.** Exact edits:
  - **Delete lines 84–199** — the blank line plus the whole `Fetch qbdlx token pool` step. The step ends at `fi` on **199**, not 198; cutting to 198 strands that `fi`, which then continues the *previous* step's `run:` block and fails the release with `syntax error near unexpected token 'fi'`. YAML still parses in that state, so a YAML-only check will not catch it.
  - In `Verify bundled credentials embedded in APK`: delete `264` (`QBDLX_APP_ID:`) and `266–268` (the `QBDLX_TOKEN_POOL` comment); **keep `265`** (`ARCOD_STASH_KEY:`) and `269` (`run: |`). Delete `270–273` (the `QBDLX_*` guard). **Keep `274–276` verbatim** — `DEX_DIR=…`, `mkdir`, `unzip` — the surviving ARCOD `grep` at `:305` depends on them. Delete `277–295` (blank + checks 1/2/3 + their echo). Keep `296–309`, renumbering its `# 4.` to `# 1.` and dropping "same class of bug as the blank qbdlx creds above".
  - Remove `QBDLX_APP_ID`/`QBDLX_APP_SECRET`/`QBDLX_APP_SECRETS`/`QBDLX_TOKEN_POOL` from every remaining `env:` block (notably `Assemble release APK`, ~`:232-247`).
  - **Add** to `Assemble release APK`'s `env:` (scope decision #2): `LOSSLESS_CONFIG_URL: ${{ secrets.LOSSLESS_CONFIG_URL }}` and `LOSSLESS_CONFIG_PUBKEY: ${{ secrets.LOSSLESS_CONFIG_PUBKEY }}`. Unset secrets yield empty strings, which the fetcher already treats as disabled — no build break before Plan B publishes anything.

- [ ] **Step 3: Verify**
  - `./gradlew :app:assembleDebug --max-workers=3 -Dorg.gradle.jvmargs=-Xmx4096m` → BUILD SUCCESSFUL.
  - `git grep -n "QBDLX_APP_ID\|QBDLX_APP_SECRET\|QBDLX_TOKEN_POOL\|QBDLX_POOL_FP" -- "*.kt" "*.kts" ".github/**"` → **no matches**.
  - YAML parses **and** the shell is valid: `python -c "import yaml;d=yaml.safe_load(open('.github/workflows/release.yml'))"`, then extract each step's `run:` and check it with `bash -n`. YAML-parse alone is not sufficient (see Step 2).

- [ ] **Step 4: Commit**

```bash
git add data/download/build.gradle.kts .github/workflows/release.yml
git commit -m "chore(build): stop shipping Qobuz app credentials and the token pool"
```

---

### Task 4: The routing list reports real state

**Design note:** the row list is built in `LosslessAvailability`, **not** in `SettingsViewModel`. `LosslessAvailability` already injects every input (`QbdlxCredentialStore`, `LosslessConfigFetcher`, `LosslessSourcePreferences`, `ArcodCredentialStore`) and exists precisely so the source, downloads and UI cannot disagree. `SettingsViewModel` already has `LosslessAvailability` (added in A1), so this needs **no new Settings constructor dependency**. It does need two **strict-mockk stubs**, because `losslessRouting`/`routingRows` are eager `val` initialisers read at construction — an unstubbed member fails every test in the file, not just the new one:
- `SettingsViewModelTest.kt:68-70` — add `every { routingRows } returns flowOf(emptyList())` to the `losslessAvailability` mockk.
- `LosslessAvailabilityTest.kt:21` — add `every { connectedEmailFlow } returns MutableStateFlow(null)` to the `store` mockk (`connectedEmailFlow` is a **fifth** input; `combine` has a 5-arg overload).

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/lossless/LosslessAvailability.kt`
- Modify: `feature/settings/…/SettingsViewModel.kt`, `SettingsAudioQualityScreen.kt`, `components/LosslessRoutingStatus.kt`
- Test: `data/download/src/test/…/lossless/LosslessAvailabilityTest.kt`

- [ ] **Step 1: Write the failing test** in `LosslessAvailabilityTest` (stub `connectedEmailFlow` on the store mockk first — see the design note): nothing configured → the `qobuz` and `relay` rows are `NOT_CONFIGURED`; a connected account → `qobuz` is `CONNECTED` with the email as detail; a **migrated token (no email)** → `qobuz` is `CONNECTED` with detail `"connected (token)"`; a config listing one relay → `relay` is `CONFIGURED`; a custom endpoint adds its own row.

- [ ] **Step 2: Implement**

In `data/download/…/lossless/`:

```kotlin
enum class RoutingState { CONNECTED, CONFIGURED, NOT_CONFIGURED }

data class RoutingRow(val id: String, val label: String, val detail: String, val state: RoutingState)
```

and on `LosslessAvailability`:

```kotlin
    /**
     * What each lossless source actually is right now — no hardcoded "active".
     * Liveness is deliberately absent: relay health needs Plan B's /v1/status, and
     * a BYO account inside its 60 s dead-cooldown must not flicker to "offline".
     */
    val routingRows: Flow<List<RoutingRow>> = combine(
        credentialStore.hasLogin, connectedEmailFlow, config.relays,
        prefs.customLosslessEndpoint, arcod.accessToken,
    ) { hasLogin, email, relays, custom, arcodToken -> … }
```

Key the Qobuz row on **`hasLogin`, not the email** — a migrated pasted token has no email, and keying on the email would render "not connected" while `qbdlxExpired` on the same screen says a source *is* configured. Detail is `email ?: "connected (token)"` (spec:138). `connectedEmailFlow` is new: a `Flow<String?>` over `loginEmailKey` in the store, mirroring `hasLogin` (`.map{}.distinctUntilChanged().catch { emit(null) }`).

`SettingsViewModel` exposes `val losslessRouting: StateFlow<List<RoutingRow>> = losslessAvailability.routingRows.stateIn(viewModelScope, WhileSubscribed(5_000), emptyList())`.

`LosslessRoutingStatus(rows: List<RoutingRow>, modifier: Modifier = Modifier)` keeps its current visual language (mono `ROUTING` header, `↳` rows, status dots) but renders `rows`. Replace the footer's "Lossless comes from Qobuz" with: *"Lossless comes from your connected account, or a relay you've configured. Misses try JioSaavn AAC 320 before falling back to YouTube, shown as \"via YT\" while it plays."* Update the file KDoc: rows describe **configuration**, not liveness; health arrives with the relay's `/v1/status` in Plan B.

**Also fix the connect form's visibility key** (`SettingsAudioQualityScreen.kt` ~`:224-288`): it currently keys on `qobuzConnectedEmail`, so a migrated token shows the full email/password form and **no Disconnect button** — the user cannot remove a dead token. Key it on `hasLogin` too, and label it `email ?: "Connected (token)"`.

- [ ] **Step 3: Verify** — `:data:download` and `:feature:settings` tests + compile green. Test idiom note: `:feature:settings` has **no turbine** — collect `StateFlow`s with a live `launch { … }` + `advanceUntilIdle()` job as `SettingsViewModelTest.kt:110-116` already does.

- [ ] **Step 4: Commit** — `git commit -m "feat(settings): the routing list reports real state instead of a hardcoded row"`

---

### Task 5: Advanced — custom lossless endpoint

**Files:**
- Modify: `data/download/…/lossless/relay/LosslessRelayClient.kt` (add `probe`)
- Modify: `feature/settings/…/SettingsViewModel.kt`, `SettingsAudioQualityScreen.kt`
- Test: `data/download/src/test/…/relay/LosslessRelayClientTest.kt`, `feature/settings/…/SettingsViewModelTest.kt`

**Note:** `SettingsViewModel` gains a `LosslessRelayClient` constructor parameter — add it to `SettingsViewModelTest.newVm` (which spells out every argument, ~`:42-86`).

- [ ] **Step 1: Write the failing tests** — `LosslessRelayClientTest`: `probe` returns true on 200, **true on 404/400** (any HTTP reply proves DNS+TLS+routing, which is all that is verifiable before Plan B serves `/v1/status`), false on connection failure, and **never cools the base** (`isCooled(base)` stays false afterwards) nor early-returns when the base is already cooled. `SettingsViewModelTest`: committing `"https://relay.example/"` stores `https://relay.example`; a non-https value clears the key and sets an error.

- [ ] **Step 2: Implement**

`LosslessRelayClient.probe(base: String): Boolean` — `GET <base>/v1/status`, any response ⇒ true, `IOException` ⇒ false. It must **not** call the private `cool()` and must **not** consult `isCooled()` first (a user testing a just-cooled base deserves a real answer). KDoc: this reports *reachability*, not health; Plan B upgrades it to parse `accounts_live`.

VM: `customEndpoint: StateFlow<String?>` from `losslessPrefs.customLosslessEndpoint`; `onCustomEndpointCommitted(raw: String)` → `setCustomLosslessEndpoint(raw)`, setting `customEndpointError` ("Must be an https:// URL") when `normaliseEndpoint(raw)` returns null for non-blank input; `onTestCustomEndpoint()` → `probe`, mapped to `customEndpointTest: StateFlow<TestState>` (`Idle`/`Testing`/`Reachable`/`Unreachable`).

Screen: an "Advanced" section under the routing list with an `OutlinedTextField` (label "Custom lossless endpoint", placeholder `https://…`) and a "Test" `TextButton` showing the result. **The field commits on IME Done / focus loss, never per keystroke** — reuse the draft/`committed`/`wasFocused` pattern from the paste field deleted in Task 1 (recover it from `git show a1262511`). A per-keystroke write would persist `https://re` as a base.

- [ ] **Step 3: Verify** — `:data:download` + `:feature:settings` green.

- [ ] **Step 4: Commit** — `git commit -m "feat(settings): custom lossless endpoint with a reachability test"`

---

### Task 6: The Home banner generalises

**Files:**
- Modify: `data/download/…/lossless/LosslessSourcePreferences.kt` (add `losslessOfflineDismissed` + setter)
- Modify: `feature/home/…/HomeViewModel.kt`, `HomeUiState.kt`, `HomeScreen.kt`
- Test: `feature/home/src/test/…/HomeViewModelTest.kt`

**Note:** `HomeViewModel` gains a `LosslessAvailability` parameter; `arcodCredentialStore` becomes unused once its only use (~`:354`) is swapped — remove it and update `HomeViewModelTest`'s builder.

- [ ] **Step 1: Write the failing test** — the banner shows when `qbdlxLooksDown && !anyUserOwned && losslessOn && !dismissed`, and specifically that a **BYO-connected** user does *not* see it (today's ARCOD-only check would show it to them).

- [ ] **Step 2: Implement** — add `losslessOfflineDismissed` as a **new** key (do not reuse `arcodRescueDismissed`: users who dismissed the old ARCOD banner must still see this one once). Rename `arcodRescueFlow` → `losslessOfflineFlow`:

```kotlin
    private val losslessOfflineFlow = combine(
        losslessSourceHealth.qbdlxLooksDown,
        losslessAvailability.anyUserOwned,
        losslessPrefs.enabled,
        losslessPrefs.losslessOfflineDismissed,
    ) { down, userOwned, losslessOn, dismissed -> down && !userOwned && losslessOn && !dismissed }
```

Rename through the UI: `HomeUiState.showArcodRescue` → `showLosslessOffline` (~`HomeUiState.kt:40`), `ArcodRescueBanner` → `LosslessOfflineBanner` (`HomeScreen.kt` ~`:335,338,340,940`), `dismissArcodRescue` → `dismissLosslessOffline` (`HomeViewModel.kt` ~`:466,471,500-502`). Copy: *"Lossless is offline right now — Stash is playing YouTube audio. Connect your own Qobuz account to keep FLAC."* CTA → Settings › Audio. Update the KDoc that still explains the ARCOD-rescue framing.

- [ ] **Step 3: Verify** — `:feature:home:testDebugUnitTest` green except the baseline failures from Task 0.

- [ ] **Step 4: Commit** — `git commit -m "feat(home): the lossless-offline banner reflects any user-owned source"`

---

### Task 7: README and comment hygiene

- [ ] **Step 1: README** — delete the "FLAC backbone" claim; fix the "only Spotify and YouTube" claim; name every service the app contacts; state plainly that lossless needs the user's own Qobuz account (or a relay they configure) and that Stash ships no one else's credentials; add a contact address. Do not describe how to obtain credentials.

- [ ] **Step 2: Stale copy and comments** — including the two the obvious grep misses: `SettingsAudioQualityScreen.kt:239` *"your account, not the shared pool"* and `:142` *"Studio-quality FLAC via Qobuz."* Then run, **scoped away from historical plan docs** (which legitimately describe the pool and must not be edited):
```bash
git grep -n -iE "token pool|pooled token|shared pool|QbdlxPool|MAX_TOKEN_ATTEMPTS|QBDLX_CONFIGURED" -- '*.kt' '*.kts' 'README.md'
```
Resolve every hit or explain why it stays. `DownloadManager.kt:231`'s `QBDLX_CONFIGURED` mention: reword to name `LosslessAvailability`, leave the surrounding logic to Plan A2.

- [ ] **Step 3: Commit** — `git commit -m "docs: README states what Stash actually contacts and what lossless needs"`

---

### Task 8: Release gate, full tests, device check

- [ ] **Step 1:** `./gradlew compileDebugUnitTestKotlin --max-workers=3 -Dorg.gradle.jvmargs=-Xmx4096m` → BUILD SUCCESSFUL (the gate CI runs).
- [ ] **Step 2:** `./gradlew testDebugUnitTest --max-workers=3 -Dorg.gradle.jvmargs=-Xmx4096m --continue`, then **diff the failures against `plan-c-baseline-failures.txt` from Task 0** (same scratchpad path). Any failure not in that file is this plan's.
- [ ] **Step 3:** `git grep -n "QBDLX_APP_ID\|QBDLX_TOKEN_POOL\|allDead()\|activeToken()\|poolForPicker\|tokensForRegion" -- '*.kt' '*.kts'` → no production matches.
- [ ] **Step 4: Device check** (Pixel 6, debug build). **Prove the APK is fresh first** — gradle exit code, APK mtime, `adb shell dumpsys package com.stash.app.debug | grep versionName` (per `feedback_verify_apk_freshness_before_device_proof`; a silent build failure once made a three-week-old APK look like a passing test). Then, with the account still connected: play a track **not touched this session** (`StreamUrlCache` hides re-resolves) → `qbdlx served` + `FLAC` badge; Settings shows "Your Qobuz account · connected as …" and "Stash lossless · not configured"; Disconnect → qbdlx leaves the chain and the Home banner appears.
- [ ] **Step 5:** Report, then `superpowers:finishing-a-development-branch`.
