# Credential Encryption at Rest — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every credential Stash stores is encrypted at rest, so `SECURITY.md` can make one claim instead of two.

**Architecture:** `EncryptedTokenStore` already encrypts Spotify and YouTube blobs with Tink AES-256-GCM under an Android Keystore master key. Seven other credentials sit in plain `preferencesDataStore`. This plan extracts the encrypt/decrypt/migrate mechanism into a reusable helper, then routes each remaining credential through it, one store per task, each with a one-shot migration that re-encrypts what's already on disk and deletes the plaintext.

**Tech Stack:** Kotlin, Hilt, DataStore Preferences, Google Tink (`AndroidKeysetManager`, AES256_GCM), Robolectric.

**Base:** whatever `master` is after Plan C merges. Do this in its own worktree/branch.

---

## Why this is not urgent, and what it actually buys

Be honest in the commit messages and the docs: this is **defence in depth, not a hole being closed**.

- `android:allowBackup="false"` and `android:fullBackupContent="false"` are already set (`app/src/main/AndroidManifest.xml`), so `adb backup` cannot pull these files.
- App-private storage is unreadable by other apps on a non-rooted device, and modern Android is full-disk-encrypted.
- So Tink adds protection against: **a rooted device, physical/forensic extraction, and a future bug that widens file permissions.**

The stronger argument is consistency. Two-encrypted-and-seven-not is the asymmetry that produced the false `SECURITY.md` storage claim Plan C had to correct — one bullet said "all tokens", the bullet below it enumerated credentials the first bullet didn't cover. Uniformity removes a class of documentation defect, not just an attack.

---

## What to encrypt, and in what order

| Priority | Credential | Store | Why this rank |
|---|---|---|---|
| 1 | Qobuz `login_token`, `login_app_secret`, `login_app_id`, `login_email`, `pasted_token` | `QbdlxCredentialStore` | A live credential for a **paid** subscription. Extraction lets someone stream on the user's account — which is how accounts get banned (it is what killed the shipped pool). Now the primary lossless path. |
| 2 | `arcod_refresh_token`, `arcod_access_token` | `ArcodCredentialStore` | The refresh token is long-lived and mints new access tokens, so it outranks the access token. |
| 3 | Last.fm `session_key` | `LastFmSessionPreference` | Last.fm session keys do not expire and can scrobble and modify the user's library. Not financially damaging; persistent. |
| 4 | ListenBrainz `user_token` | `ListenBrainzPreference` | Same shape as 3, lower impact. |
| 5 | `yt_client_secret` (and `yt_client_id`) | `YouTubeCredentialsStore` | The user's **own** Google OAuth app credentials, typed in by hand. Worth care because they handed them over expecting it. |

**Explicitly NOT in scope:**
- `antra_session_cookie` / `antra_cf_clearance_cookie` (`LosslessSourcePreferences`) — the source is gone; these keys exist only so `purgeAntraCredentials()` can delete them. **Delete them, don't encrypt them.** Once telemetry-free confidence exists that the purge has run everywhere, remove the keys entirely.
- The yt-dlp cookie file — already written to `noBackupFilesDir` and deleted after use by both call sites (`DownloadExecutor`, `PreviewUrlExtractor`). Verified; leave it.
- `EncryptedTokenStore` itself — already correct; it becomes the first consumer of the extracted helper.

---

## The design crux: what happens when decryption fails

`EncryptedTokenStore` currently maps a decrypt failure to `null` — indistinguishable from "never set". For Spotify that is survivable: the user re-logs in. **For a Qobuz subscription token it is not**: the user's paid account would silently appear disconnected, lossless would quietly stop, and Settings would say "not connected" — a false statement, which is exactly what Plan C spent itself removing.

`TinkEncryptionManager` uses `AndroidKeysetManager` **without** `setUserAuthenticationRequired`, so the master key is *not* invalidated by a lock-screen change — the realistic loss modes are app-data clear, a corrupted keyset in `stash_auth_keyset_prefs`, and OEM Keystore quirks. Rare, but not zero, and the blast radius is a paid account.

**So every store this plan touches must distinguish three states, not two:**

```kotlin
sealed interface StoredSecret<out T> {
    data object Absent : StoredSecret<Nothing>              // never set — show "connect"
    data class Present<T>(val value: T) : StoredSecret<T>
    data object Undecryptable : StoredSecret<Nothing>       // on disk, key gone — show "reconnect"
}
```

`Undecryptable` must surface in the UI as *"Your Qobuz account needs reconnecting"*, never as "not connected". `LosslessAvailability.hasLogin` must treat it as **not usable but present**, so the Home banner does not tell a paying user to connect an account they already have.

---

## File structure

| File | Change |
|---|---|
| `core/auth/.../crypto/SecretPreference.kt` | **new** — the reusable encrypt/decrypt/migrate helper |
| `core/auth/.../crypto/StoredSecret.kt` | **new** — the three-state result |
| `core/auth/.../store/EncryptedTokenStore.kt` | refactor onto the helper; return `StoredSecret` |
| `data/download/.../qbdlx/QbdlxCredentialStore.kt` | encrypt the login triple + email + pasted token |
| `data/download/.../arcod/ArcodCredentialStore.kt` | encrypt both tokens |
| `core/data/.../lastfm/LastFmSessionPreference.kt` | encrypt the session key |
| `core/data/.../listenbrainz/ListenBrainzPreference.kt` | encrypt the user token |
| `core/auth/.../youtube/YouTubeCredentialsStore.kt` | encrypt the client secret |
| `app/.../StashApplication.kt` | nothing — migrations are lazy, per store (see Task 1) |
| `SECURITY.md` | one storage claim, true |

---

### Task 1: `SecretPreference` — the reusable mechanism

**Files:** create `core/auth/src/main/kotlin/com/stash/core/auth/crypto/StoredSecret.kt`, `SecretPreference.kt`; test `core/auth/src/test/.../SecretPreferenceTest.kt`.

- [ ] **Step 1: Write the failing tests** (Robolectric + a real temp DataStore): a value written through the helper is **not** readable as plaintext in the raw preferences; reading it back returns `Present`; a key holding *legacy plaintext* is migrated on first read (returns `Present`, and the raw store afterwards holds ciphertext, not the original string); a key holding ciphertext that fails to decrypt returns `Undecryptable` **and is left on disk** (do not auto-delete — that would destroy recoverable data if the failure is transient); an absent key returns `Absent`.
- [ ] **Step 2: Run — expect compile failure.**
- [ ] **Step 3: Implement.** `SecretPreference` wraps `TinkEncryptionManager` + a `DataStore<Preferences>` + a `Preferences.Key<String>`. API: `suspend fun read(): StoredSecret<String>`, `suspend fun write(value: String?)` (null clears), and `fun flow(): Flow<StoredSecret<String>>` with the project's standing `.distinctUntilChanged().catch { … }` treatment. Ciphertext is Base64 in the same `String` key — **no key rename**, so the migration is in-place and a downgrade to an older build simply fails to read it rather than crashing.
  **Legacy detection:** try Base64-decode + `decrypt`; if either throws, treat the raw string as legacy plaintext, re-write it encrypted, and return `Present`. Document the one ambiguity: a legacy plaintext value that happens to be valid Base64 *and* decrypts is impossible in practice (AES-GCM authenticates), so the heuristic is safe.
- [ ] **Step 4: Run tests → green.**
- [ ] **Step 5: Commit** — `feat(crypto): reusable encrypted preference with in-place legacy migration`

---

### Task 2: Move `EncryptedTokenStore` onto the helper

Proves the helper against the one store that already works, before touching anything unencrypted.

- [ ] Refactor `EncryptedTokenStore` to use `SecretPreference` for both keys; its public API returns `StoredSecret<ServiceToken>` instead of nullable. Update every caller (`grep spotifyToken|youtubeToken`) — a call site that previously saw `null` for both "absent" and "corrupt" must now handle `Undecryptable` explicitly; where a caller genuinely cannot act on it, map it to `Absent` **at that call site with a comment**, not in the store.
- [ ] Existing `EncryptedTokenStore` tests must pass unchanged in behaviour; add one for `Undecryptable`.
- [ ] **Commit** — `refactor(auth): EncryptedTokenStore on SecretPreference, three-state reads`

---

### Task 3: Qobuz — the one that matters

- [ ] `QbdlxCredentialStore`: route `login_token`, `login_app_id`, `login_app_secret`, `login_email`, `pasted_token` through `SecretPreference`. Keep the existing single-flight `loadMutex` and the in-memory `cachedLogin`; the added cost is one decrypt per process, not per read.
- [ ] `loginCredential()` currently returns `QbdlxLoginCredential?`. Add `suspend fun loginState(): StoredSecret<QbdlxLoginCredential>` and keep the nullable accessor delegating to it, so the resolve path is untouched and only the UI learns the new state.
- [ ] **`rejectLogin` must not fire on `Undecryptable`.** Its terminal branch keys on `connectedEmail() == null`, and an undecryptable email reads as null — which would **delete a paying user's credential because the keystore hiccuped**. Guard it: only `Absent` (a genuinely email-less migrated token) is terminal.
- [ ] `LosslessAvailability.hasLogin` treats `Undecryptable` as present-but-unusable: `qbdlxEnabled` true (so the Home banner stays down), `fileUrlAvailableNow()` false.
- [ ] Settings shows *"Your Qobuz account needs reconnecting"* with the Disconnect button available.
- [ ] Tests: migration from plaintext; `Undecryptable` does not disconnect; the routing row and banner read correctly in that state.
- [ ] **Commit** — `feat(qbdlx): encrypt the connected account at rest`

---

### Task 4: ARCOD, Last.fm, ListenBrainz, YouTube client secret

One commit each, same shape as Task 3, in that order. Each: route through `SecretPreference`, add a migration test, and check whether any UI keys "connected" off a nullable read that can now be `Undecryptable`.

**If the ARCOD source has been removed by then, skip it** and instead delete `ArcodCredentialStore` with the rest — do not encrypt a store nothing reads.

---

### Task 5: Make `SECURITY.md` say one thing

- [ ] Replace the two-tier storage bullet with a single true claim: every credential encrypted at rest with Tink AES-256-GCM under an Android Keystore master key. Name the failure mode honestly — if the keyset is lost the credential cannot be recovered and the app asks the user to reconnect.
- [ ] Delete the "Other service credentials … without an additional encryption layer" sentence Plan C added.
- [ ] **Commit** — `docs(security): one storage claim, now that it's true`

---

### Task 6: Gate

- [ ] `./gradlew compileDebugUnitTestKotlin` → BUILD SUCCESSFUL.
- [ ] `./gradlew testDebugUnitTest --continue` → diff failures against a baseline captured **before** starting (the known-red set changes over time; measure, don't assume).
- [ ] Device check on an **upgrade**, not a fresh install: install the previous build, connect a Qobuz account, install this build, confirm the account still works and that `qbdlx_creds.preferences_pb` no longer contains the token as readable text (`adb shell run-as … cat … | strings | grep <prefix>`).
- [ ] **The upgrade path is the whole risk of this plan.** A fresh install proves nothing here.
