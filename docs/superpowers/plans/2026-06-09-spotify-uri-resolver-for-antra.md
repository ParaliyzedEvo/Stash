# Spotify-URI Resolver for antra — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let non-Spotify (YouTube-sourced) tracks use the antra lossless FLAC source by finding their correct Spotify link on demand, under a conservative "bulletproof" acceptance rule (never attach a wrong recording).

**Architecture:** A new `SpotifySearchScorer` (the bulletproof boolean gate, mirrors `MatchScorer` in reverse) + a cache-first `SpotifyUriResolver` orchestrator + a `SpotifyApiClient.searchTracks()` Web API call + a Room side-table cache (`spotify_resolution`, MATCHED/NO_MATCH/TRANSIENT TTLs). The two antra call sites (`AntraSource` download, `AntraStreamResolver` stream) consult the resolver when a track has no `spotify_uri`.

**Tech Stack:** Kotlin, Hilt, Room (DB v31→32), Retrofit/OkHttp (Spotify Web API), kotlinx.coroutines, JUnit + mockk + Robolectric (in-memory Room) + Truth.

**Spec:** `docs/superpowers/specs/2026-06-09-spotify-uri-resolver-for-antra-design.md` (read it first).

**Gradle note:** this box hits a `BindException` on the daemon. Always run `./gradlew --stop` once before a `--no-daemon` test/build run if you see it. Use `@superpowers:test-driven-development` for every task — watch each test fail for the right reason before implementing.

---

## File Structure

**Create:**
- `data/spotify/src/main/kotlin/com/stash/data/spotify/SpotifyTrackCandidate.kt` — search-result DTO + `SpotifyRateLimitException`.
- `data/download/src/main/kotlin/com/stash/data/download/matching/SpotifySearchScorer.kt` — the bulletproof acceptance rule.
- `data/download/src/main/kotlin/com/stash/data/download/lossless/spotifyresolve/SpotifyUriResolver.kt` — cache-first orchestrator.
- `core/data/src/main/kotlin/com/stash/core/data/db/entity/SpotifyResolutionEntity.kt`
- `core/data/src/main/kotlin/com/stash/core/data/db/dao/SpotifyResolutionDao.kt`
- Tests mirroring each.

**Create:**
- `data/download/.../matching/ArtistMatching.kt` — `internal object` holding `ARTIST_PART_SEPARATOR` + `artistParts` + `containsRun`, extracted from `MatchScorer` so both `MatchScorer` and `SpotifySearchScorer` call it statically (the helpers are private *instance* methods today — a separate scorer can't reach them, and we won't duplicate the logic).

**Modify:**
- `data/download/build.gradle.kts` — add `implementation(project(":data:spotify"))` (the module does NOT depend on `:data:spotify` today; the resolver/scorer need it). Verified acyclic.
- `data/download/.../matching/MatchScorer.kt` — delegate its artist-part logic to `ArtistMatching` (no behavior change).
- `data/spotify/.../SpotifyApiClient.kt` — add `searchTracks()`; `Mutex`-guard token refresh.
- `core/data/.../db/StashDatabase.kt` — register entity+DAO, version 31→32, define `MIGRATION_31_32`.
- `core/data/.../di/DatabaseModule.kt` — register `MIGRATION_31_32` in `.addMigrations(...)` AND add `@Provides fun provideSpotifyResolutionDao(db) = db.spotifyResolutionDao()` (every DAO is hand-provided here; nothing is auto-provided).
- `data/download/.../lossless/LosslessSource.kt` — add `trackId` to `TrackQuery`; add `resolvedSpotifyTrackUrl` extension.
- `data/download/.../lossless/antra/AntraSource.kt` — use the extension; inject resolver.
- `data/download/.../DownloadManager.kt:398` — add `trackId = track.id` to the `TrackQuery`.
- `core/media/.../streaming/AntraStreamResolver.kt:70` — thread `trackId`+`durationMs`+`album`; inject + use resolver; update the existing `AntraStreamResolverTest` internal-constructor call.
- `SpotifyUriResolver`/`SpotifySearchScorer`/`SpotifyApiClient` are all `@Inject @Singleton` → constructor-injectable, no new DI module needed beyond the DAO provider above.

---

## Task 1: Candidate DTO + new exception + extract ArtistMatching + Gradle edge

**Files:**
- Create: `data/spotify/src/main/kotlin/com/stash/data/spotify/SpotifyTrackCandidate.kt`
- Create: `data/download/src/main/kotlin/com/stash/data/download/matching/ArtistMatching.kt`
- Modify: `data/download/src/main/kotlin/com/stash/data/download/matching/MatchScorer.kt`
- Modify: `data/download/build.gradle.kts`

- [ ] **Step 1: Create the DTO + exception** (no test needed — pure data declarations)

```kotlin
package com.stash.data.spotify

/** One track from GET /v1/search?type=track. */
data class SpotifyTrackCandidate(
    val id: String,             // bare id → "spotify:track:$id"
    val name: String,
    val artists: List<String>,  // artists[].name, primary first
    val albumName: String,
    val durationMs: Long,
    val isrc: String?,          // external_ids.isrc (often null on /search)
    val explicit: Boolean,
)

/** Thrown on Spotify 429 so callers can back off (vs a genuine no-match). */
class SpotifyRateLimitException(val retryAfterSeconds: Long?) :
    Exception("Spotify rate limited (Retry-After=${retryAfterSeconds}s)")
```

- [ ] **Step 2: Extract `ArtistMatching` and delegate from MatchScorer**

`MatchScorer.artistParts(...)` (≈line 317) and `containsRun(...)` (≈line 303) are private **instance** methods, and `ARTIST_PART_SEPARATOR` is in its companion — a separate `SpotifySearchScorer` cannot reach private instance methods just by marking them `internal`. Extract them into a shared, stateless helper so both classes call it statically and the logic lives once:

```kotlin
package com.stash.data.download.matching

internal object ArtistMatching {
    val ARTIST_PART_SEPARATOR: Regex = /* move the exact regex from MatchScorer */
    fun artistParts(raw: String): List<String> = /* move body verbatim */
    fun containsRun(haystackTokens: List<String>, needleTokens: List<String>): Boolean = /* move body verbatim — match the real signature */
}
```

In `MatchScorer.kt`, replace the bodies of the (now-removed-or-thin-wrapper) private methods with calls to `ArtistMatching.*`, leaving `MatchScorer`'s public behavior identical. Copy the EXACT signatures and bodies from the current `MatchScorer` — do not re-derive.

- [ ] **Step 3: Add the Gradle dependency edge**

In `data/download/build.gradle.kts`, add `implementation(project(":data:spotify"))` to the `dependencies { }` block. (The module does not depend on `:data:spotify` today; the new scorer/resolver reference `SpotifyTrackCandidate`/`SpotifyApiClient`.)

- [ ] **Step 4: Compile both modules**

Run: `./gradlew --stop; ./gradlew --no-daemon :data:download:compileDebugKotlin :data:spotify:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. Run `./gradlew --no-daemon :data:download:testDebugUnitTest --tests "*MatchScorer*"` to confirm the `ArtistMatching` extraction didn't change `MatchScorer` behavior.

- [ ] **Step 5: Commit**

```bash
git add data/spotify/src/main/kotlin/com/stash/data/spotify/SpotifyTrackCandidate.kt data/download/src/main/kotlin/com/stash/data/download/matching/ArtistMatching.kt data/download/src/main/kotlin/com/stash/data/download/matching/MatchScorer.kt data/download/build.gradle.kts
git commit -m "feat(spotify): add SpotifyTrackCandidate DTO + RateLimitException; extract ArtistMatching; add :data:spotify edge"
```

---

## Task 2: SpotifySearchScorer — the bulletproof acceptance rule (TDD, the crux)

This is the heart of the feature. Build it test-first with one test per wrong-match trap. Read the spec's "Resolution algorithm" + "Edge cases" sections.

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/matching/SpotifySearchScorer.kt`
- Test: `data/download/src/test/kotlin/com/stash/data/download/matching/SpotifySearchScorerTest.kt`

- [ ] **Step 1: Write the first failing test (happy path + the core gates)**

```kotlin
package com.stash.data.download.matching

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.sync.TrackMatcher
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.spotify.SpotifyTrackCandidate
import org.junit.Test

class SpotifySearchScorerTest {
    private val scorer = SpotifySearchScorer(TrackMatcher())   // TrackMatcher is a plain class; if it needs deps, construct accordingly

    private fun track(title: String, artist: String, durMs: Long) =
        TrackQuery(artist = artist, title = title, durationMs = durMs)

    private fun cand(name: String, artists: List<String>, durMs: Long, id: String = "abc") =
        SpotifyTrackCandidate(id = id, name = name, artists = artists, albumName = "Album", durationMs = durMs, isrc = null, explicit = false)

    @Test fun `accepts exact title+artist within duration tolerance`() {
        val t = track("Crosstown Traffic", "Jimi Hendrix", 145_000)
        val c = cand("Crosstown Traffic", listOf("Jimi Hendrix"), 146_000)
        assertThat(scorer.pick(t, listOf(c)).accepted?.id).isEqualTo("abc")
    }
}
```

- [ ] **Step 2: Run it, watch it fail** (unresolved `SpotifySearchScorer`)

Run: `./gradlew --no-daemon :data:download:testDebugUnitTest --tests "com.stash.data.download.matching.SpotifySearchScorerTest"`
Expected: FAIL — compile error, `SpotifySearchScorer` not defined.

- [ ] **Step 3: Implement `SpotifySearchScorer` to the spec**

Implement per the spec's "Acceptance rule": signals `titleSim` (Jaro-Winkler over `canonicalTitle`), `artistOk` (per-element via `ArtistMatching.artistParts`/`ArtistMatching.containsRun` called statically + `jaroWinkler ≥ 0.85`; `SpotifySearchScorer` injects only `TrackMatcher`), `durKnown` (require `track.durationMs != null && > 0 && cand.durationMs > 0`), `durDeltaSec` (≤ 4), `versionConflict` (**raw lowercased titles**, word-boundary tokens, symmetric presence over the explicit disqualifying set). Accept iff `durKnown && durDeltaSec ≤ 4 && titleSim ≥ 0.92 && artistOk && !versionConflict`. Among survivors pick min `durDeltaSec` (tie: max `titleSim`); apply the **ambiguity-abstain** (reject all if two survivors within 0.02 titleSim AND 2s durDeltaSec). Return `Decision(accepted, reason)`. Define the disqualifying token set as a private `val` exactly as the spec lists it.

- [ ] **Step 4: Run, watch it pass**

Run the same command. Expected: PASS.

- [ ] **Step 5: Commit, then add the trap tests one at a time**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/matching/SpotifySearchScorer.kt data/download/src/test/kotlin/com/stash/data/download/matching/SpotifySearchScorerTest.kt
git commit -m "feat(spotify): SpotifySearchScorer bulletproof acceptance rule (happy path)"
```

- [ ] **Step 6: Add one failing test per trap, implement-if-needed, commit** (TDD each)

Add these tests (each asserts the WRONG candidate is rejected → `accepted == null`, given only the wrong candidate; and where noted, the RIGHT one is accepted when both are present):
- `rejects live version` — `cand("Crosstown Traffic (Live)", ...)`, duration +40s → reject (duration gate AND versionConflict).
- `rejects live version even within 4s` — `cand("Song - Live", ...)`, duration +2s → reject via versionConflict over RAW title (this is the load-bearing case — verify it does NOT accept).
- `rejects remaster` — `"Song - 2011 Remaster"`, same duration → reject via versionConflict.
- `rejects cover by different artist` — same title, `artists=listOf("Some Cover Band")` → reject via artistOk.
- `rejects sped up` — `"Song (Sped Up)"`, duration −30s → reject.
- `rejects karaoke / instrumental` → reject via versionConflict.
- `accepts multi-artist in different order` — track artist "A, B", candidate `artists=listOf("B","A")`, same title+duration → ACCEPT.
- `accepts feat in title vs artists[]` — track "Song (feat. X)", candidate name "Song", `artists=listOf("Primary","X")`, same duration → ACCEPT.
- `rejects when our duration unknown` — `track(dur=0)` → reject (durKnown false) regardless of a perfect candidate.
- `ambiguity abstain` — two candidates, both pass gates, within 0.02 titleSim & 2s → reject all.
- `rejects different song with similar title` — titleSim < 0.92 → reject.
- `rejects extended/club mix` — `"Song - Extended Mix"`, +70s → reject.

For each: write the test, run it (`--tests "*SpotifySearchScorerTest"`), confirm fail/pass, then commit in small batches:
```bash
git add -A && git commit -m "test(spotify): SpotifySearchScorer rejects <trap>"
```

---

## Task 3: Room side-table — entity, DAO, migration 31→32 (TDD with in-memory Room)

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/db/entity/SpotifyResolutionEntity.kt`
- Create: `core/data/src/main/kotlin/com/stash/core/data/db/dao/SpotifyResolutionDao.kt`
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt`
- Modify: `core/data/src/main/kotlin/com/stash/core/data/di/DatabaseModule.kt` (register migration + provide DAO)
- Test: `core/data/src/test/kotlin/com/stash/core/data/db/dao/SpotifyResolutionDaoTest.kt`

- [ ] **Step 1: Create the entity** (per spec §Persistence)

```kotlin
package com.stash.core.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "spotify_resolution")
data class SpotifyResolutionEntity(
    @PrimaryKey val trackId: Long,
    val status: String,          // "MATCHED" | "NO_MATCH" | "TRANSIENT"
    val spotifyUri: String?,     // "spotify:track:<id>" when MATCHED
    val matchedIsrc: String?,
    val titleSim: Float?,
    val durDeltaSec: Int?,
    val resolvedAtMs: Long,
    val expiresAtMs: Long,
    val attempts: Int = 1,
)
```

- [ ] **Step 2: Create the DAO**

```kotlin
package com.stash.core.data.db.dao

import androidx.room.*
import com.stash.core.data.db.entity.SpotifyResolutionEntity

@Dao
interface SpotifyResolutionDao {
    @Query("SELECT * FROM spotify_resolution WHERE trackId = :trackId")
    suspend fun get(trackId: Long): SpotifyResolutionEntity?

    @Upsert suspend fun upsert(entity: SpotifyResolutionEntity)

    @Query("DELETE FROM spotify_resolution WHERE trackId IN (:trackIds)")
    suspend fun deleteByTrackIds(trackIds: List<Long>)
}
```

- [ ] **Step 3: Register in StashDatabase** — add `SpotifyResolutionEntity::class` to the `entities` list, bump `version = 32`, add `abstract fun spotifyResolutionDao(): SpotifyResolutionDao`, and define the migration object in the companion:

```kotlin
val MIGRATION_31_32 = object : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS spotify_resolution (
                trackId INTEGER NOT NULL PRIMARY KEY,
                status TEXT NOT NULL,
                spotifyUri TEXT,
                matchedIsrc TEXT,
                titleSim REAL,
                durDeltaSec INTEGER,
                resolvedAtMs INTEGER NOT NULL,
                expiresAtMs INTEGER NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 1
            )
        """.trimIndent())
    }
}
```

- [ ] **Step 3b: Wire DatabaseModule** — in `core/data/.../di/DatabaseModule.kt`: add `StashDatabase.MIGRATION_31_32` to the `.addMigrations(...)` chain (where the other migrations are registered, ≈line 36), and add a provider next to the other DAO providers:
```kotlin
@Provides fun provideSpotifyResolutionDao(db: StashDatabase): SpotifyResolutionDao = db.spotifyResolutionDao()
```
Without this, Hilt cannot inject the DAO and the migration won't run.

- [ ] **Step 4: Write the failing DAO test** (in-memory Room, mirror `DownloadQueueDaoPartitionTest` setup)

```kotlin
@Test fun `upsert then get round-trips, and status drives lookup`() = runTest {
    dao.upsert(SpotifyResolutionEntity(trackId = 1, status = "MATCHED", spotifyUri = "spotify:track:x",
        matchedIsrc = null, titleSim = 0.99f, durDeltaSec = 1, resolvedAtMs = 1000, expiresAtMs = Long.MAX_VALUE))
    assertThat(dao.get(1)?.spotifyUri).isEqualTo("spotify:track:x")
    assertThat(dao.get(2)).isNull()
}
```

- [ ] **Step 5: Run → fail (table/dao missing), then build to green**

Run: `./gradlew --stop; ./gradlew --no-daemon :core:data:testDebugUnitTest --tests "com.stash.core.data.db.dao.SpotifyResolutionDaoTest"`
Expected: FAIL first (KSP/compile), then PASS after Steps 1-3 are in place. Room will also generate `schemas/...32.json` — commit it.

- [ ] **Step 6: Add a migration test** — open DB at 31, run `MIGRATION_31_32`, assert the table exists (follow any existing migration test pattern; if none, assert via `db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='spotify_resolution'")`).

- [ ] **Step 7: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/db/entity/SpotifyResolutionEntity.kt core/data/src/main/kotlin/com/stash/core/data/db/dao/SpotifyResolutionDao.kt core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt core/data/schemas/ core/data/src/test/kotlin/com/stash/core/data/db/dao/SpotifyResolutionDaoTest.kt
git commit -m "feat(db): spotify_resolution side-table + DAO + migration 31->32"
```

---

## Task 4: SpotifyApiClient.searchTracks() + token-refresh mutex

**Files:**
- Modify: `data/spotify/src/main/kotlin/com/stash/data/spotify/SpotifyApiClient.kt`
- Test: `data/spotify/src/test/kotlin/com/stash/data/spotify/SpotifyApiClientSearchTest.kt` (parser test against a JSON fixture)

- [ ] **Step 1: Write the failing parser test** — feed a captured `/v1/search?type=track` JSON body (include one track WITH `external_ids.isrc` and one WITHOUT) through the parse path, assert it maps to `List<SpotifyTrackCandidate>` with correct `id/name/artists/durationMs/isrc`. Use the existing test style in `:data:spotify` (e.g. how `SpotifyTrackParser` is tested) — parse the JSON directly via the same kotlinx.serialization path the client uses.

- [ ] **Step 2: Run → fail** (no `searchTracks`).
Run: `./gradlew --no-daemon :data:spotify:testDebugUnitTest --tests "com.stash.data.spotify.SpotifyApiClientSearchTest"` — Expected: FAIL.

- [ ] **Step 3: Implement `searchTracks(query, limit=8, market="US")`** — `GET https://api.spotify.com/v1/search?type=track&limit=$limit&market=$market&q=${URLEncoder.encode(query)}` with `Authorization: Bearer ${getClientCredentialsToken()}`. Parse `tracks.items[]` → `SpotifyTrackCandidate` (`external_ids.isrc` via the same approach `SpotifyTrackParser` uses). On HTTP 429 → throw `SpotifyRateLimitException(retryAfterSeconds = header "Retry-After")`. On other non-2xx → throw `SpotifyApiException`. On parse-empty → `emptyList()`. Reuse the existing 401-refresh-once pattern.

- [ ] **Step 4: Mutex-guard the token refresh** — wrap the refresh inside `getClientCredentialsToken()` in a `private val tokenMutex = Mutex()` + `tokenMutex.withLock { ... }` (double-checked: re-read the cached token inside the lock before acquiring). Keep behavior identical otherwise.

- [ ] **Step 5: Run → pass.** Same command. Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add data/spotify/src/main/kotlin/com/stash/data/spotify/SpotifyApiClient.kt data/spotify/src/test/kotlin/com/stash/data/spotify/SpotifyApiClientSearchTest.kt
git commit -m "feat(spotify): searchTracks() + mutex-guarded token refresh"
```

---

## Task 5: SpotifyUriResolver — cache-first orchestrator (TDD with mockk)

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/lossless/spotifyresolve/SpotifyUriResolver.kt`
- Test: `data/download/src/test/kotlin/com/stash/data/download/lossless/spotifyresolve/SpotifyUriResolverTest.kt`

- [ ] **Step 1: Write failing tests** (mockk the `SpotifyApiClient`, `SpotifySearchScorer`, `SpotifyResolutionDao`; inject a fake `clock`):
  - `cache MATCHED returns url without searching` — dao.get returns MATCHED → assert returns `https://open.spotify.com/track/<id>`, `coVerify(exactly = 0) { spotify.searchTracks(any(), any(), any()) }`.
  - `cache NO_MATCH within TTL returns null without searching`.
  - `cache TRANSIENT within TTL returns null without searching`.
  - `miss → search → accept → upsert MATCHED → return url`.
  - `miss → search → no candidate passes → upsert NO_MATCH (30d) → null`.
  - `429 → upsert TRANSIENT (short ttl), NOT NO_MATCH → null` (assert stored status == "TRANSIENT").
  - `unknown duration → returns null, NO search, NO upsert` (`coVerify(exactly=0)` on both search and upsert).
  - `concurrent resolves of same track issue ONE search` (in-flight coalescing — use the `AntraStreamResolverTest` `runCurrent()` race pattern).
  - `TRANSIENT promotes to NO_MATCH after attempts>5`.

- [ ] **Step 2: Run → fail.**
Run: `./gradlew --no-daemon :data:download:testDebugUnitTest --tests "*SpotifyUriResolverTest"` — Expected: FAIL.

- [ ] **Step 3: Implement `SpotifyUriResolver.resolveUrl(trackId, track)`** per spec §3 read-path: unknown-duration → null (no search/no cache); cache read by status+TTL; on miss build the query priority list (primary-artist, field-filtered, album-qualified, unfielded fallback — mirror `TrackQuery.searchTerms()` for primary-artist extraction), call `searchTracks`, run `scorer.pick`, upsert MATCHED/NO_MATCH/TRANSIENT with the right `expiresAtMs`, return the URL. Add per-`trackId` in-flight coalescing (`Mutex` + `HashMap<Long, CompletableDeferred<String?>>`, mirror `AntraStreamResolver`). Re-throw `CancellationException` before the catch. The TTL constants: MATCHED=`Long.MAX_VALUE`, NO_MATCH=`30 days`, TRANSIENT=`max(15min, retryAfter)` with exponential backoff and `attempts>5 → NO_MATCH`.

- [ ] **Step 4: Run → pass.** Same command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/lossless/spotifyresolve/ data/download/src/test/kotlin/com/stash/data/download/lossless/spotifyresolve/
git commit -m "feat(spotify): SpotifyUriResolver cache-first orchestrator + TTL/coalescing"
```

---

## Task 6: TrackQuery.trackId + resolvedSpotifyTrackUrl extension

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/lossless/LosslessSource.kt`
- Test: `data/download/src/test/kotlin/com/stash/data/download/lossless/ResolvedSpotifyTrackUrlTest.kt`

- [ ] **Step 1: Write failing tests** for the extension:
  - `returns existing url when spotifyUri present, never calls resolver`.
  - `returns resolver url when spotifyUri null and trackId present`.
  - `returns null when spotifyUri null and trackId null, never calls resolver`.
  (mockk the `SpotifyUriResolver`.)

- [ ] **Step 2: Run → fail.**
Run: `./gradlew --no-daemon :data:download:testDebugUnitTest --tests "*ResolvedSpotifyTrackUrlTest"` — Expected: FAIL.

- [ ] **Step 3: Implement** — add `val trackId: Long? = null` to `TrackQuery`; add:
```kotlin
suspend fun TrackQuery.resolvedSpotifyTrackUrl(resolver: SpotifyUriResolver): String? =
    spotifyTrackUrl() ?: trackId?.let { id -> resolver.resolveUrl(id, this) }
```

- [ ] **Step 4: Run → pass.** Commit.
```bash
git add data/download/src/main/kotlin/com/stash/data/download/lossless/LosslessSource.kt data/download/src/test/kotlin/com/stash/data/download/lossless/ResolvedSpotifyTrackUrlTest.kt
git commit -m "feat(spotify): TrackQuery.trackId + resolvedSpotifyTrackUrl extension"
```

---

## Task 7: Wire into antra (both call sites) + DI

**Files:**
- Modify: `data/download/.../lossless/antra/AntraSource.kt`
- Modify: `data/download/.../DownloadManager.kt:398`
- Modify: `core/media/.../streaming/AntraStreamResolver.kt`
- Modify: DI (expose `spotifyResolutionDao()` from the DB module if not already auto-provided; ensure `SpotifyUriResolver`/`SpotifySearchScorer`/`SpotifyApiClient` are injectable — all are `@Inject`/`@Singleton`, so likely no module needed beyond the DAO provider).

- [ ] **Step 1: DownloadManager** — add `trackId = track.id` to the `TrackQuery(...)` at line ~398. (No new test; covered by existing DownloadManager tests staying green + the integration test below.)

- [ ] **Step 2: AntraSource** — inject `SpotifyUriResolver`; change line ~57 from `query.spotifyTrackUrl() ?: return null` to `query.resolvedSpotifyTrackUrl(resolver) ?: return null`.

- [ ] **Step 3: AntraStreamResolver** — inject `SpotifyUriResolver`; change the `TrackQuery(...)` at line ~70 to include `trackId = track.id, durationMs = track.durationMs, album = track.album`; change `.spotifyTrackUrl() ?: return null` to `.resolvedSpotifyTrackUrl(resolver) ?: return null`. Add the resolver to the `@Inject` constructor AND the `internal` test constructor. **Update the existing `AntraStreamResolverTest.kt:44` call** `AntraStreamResolver(client, store, cacheDir, AntraJobGate())` to pass a `mockk(relaxed=true)` resolver (and have the resolver default to returning null so existing tests are unaffected) — otherwise that suite won't compile.

- [ ] **Step 4: Write an integration test** (mockk resolver) in the existing `AntraStreamResolverTest` / a new `AntraSourceTest`:
  - `no spotifyUri + resolver returns url → antra proceeds to job` (assert `createJob` called).
  - `no spotifyUri + resolver returns null → returns null, no job`.
  - `has spotifyUri → resolver never consulted` (`coVerify(exactly=0)`).

- [ ] **Step 5: Build everything + run affected suites**

Run: `./gradlew --stop; ./gradlew --no-daemon :data:download:testDebugUnitTest :core:media:testDebugUnitTest :data:spotify:testDebugUnitTest :core:data:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all green. Fix any DI graph errors (missing binding for the DAO/resolver).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(spotify): wire SpotifyUriResolver into AntraSource + AntraStreamResolver"
```

---

## Task 8: Build, install, and on-device verification

- [ ] **Step 1: Install**

Run: `./gradlew --no-daemon :app:installDebug` (reconnect adb if the wireless port rotated: `adb connect 192.168.137.251:<port>`).

- [ ] **Step 2: Verify the resolver fires for a YouTube track**

With antra connected and (optionally) the "Force antra only" drill ON, tap/download a **YouTube-sourced** track that has a clear Spotify equivalent (title/artist/duration). Watch logcat for the resolver issuing a Spotify search and antra proceeding with a job. Confirm a `spotify_resolution` row is written (`MATCHED` with a `spotifyUri`) by pulling the DB:
```
adb exec-out run-as com.stash.app.debug cat databases/stash.db > /tmp/stashdb/stash.db   # + -wal,-shm
sqlite3 /tmp/stashdb/stash.db "SELECT trackId,status,spotifyUri,titleSim,durDeltaSec FROM spotify_resolution LIMIT 20;"
```

- [ ] **Step 3: Verify bulletproofing on a hard case**

Tap a track likely to mismatch (a live-only YouTube upload, a sped-up edit, or an obscure track Spotify lacks). Confirm it resolves to `NO_MATCH` (or rejects) and falls back to YouTube — NOT a wrong FLAC. Spot-check 5-10 `MATCHED` rows by opening their `spotify:track:<id>` and confirming they're the correct recording.

- [ ] **Step 4: Verify no regression for Spotify tracks + idempotent cache**

A Spotify-synced track still resolves instantly (resolver not consulted). A second play of a now-`MATCHED` YouTube track hits the cache (no new search in logcat).

- [ ] **Step 5: Turn the antra drill OFF (if used); final commit / notes**

```bash
git commit --allow-empty -m "test(spotify): on-device verification of YouTube->Spotify resolution"
```

---

## Deferred (explicit — out of scope this round)
- **`"spotify_search"` token-bucket** (spec §Failure "search-burst safety net"): on-demand resolution drips one search per uncached track, so the bucket is a backstop, not a throttle. Defer until a real 429 pattern appears; the `TRANSIENT` cache already absorbs bursts.
- **Wiring `SpotifyResolutionDao.deleteByTrackIds` into track-orphan cleanup:** a deleted track leaves a harmless stale `spotify_resolution` row (the table is independently clearable). The method is created for future use; wire it into the existing track-deletion path in a follow-up if orphan rows become a concern.

## Done criteria
- All new unit tests green; existing `:data:download` / `:core:media` / `:core:data` / `:data:spotify` suites green (minus the known pre-existing InnerTube/canonicalizer failures).
- On-device: a YouTube track with a confident Spotify equivalent plays/downloads via antra (FLAC); an uncertain one falls back to YouTube; Spotify tracks unaffected; cache prevents re-search.
- No wrong-recording matches found in a spot-check of `MATCHED` rows.
