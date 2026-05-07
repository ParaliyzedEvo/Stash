# Blocklist Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the `tracks.is_blacklisted` flag with a dedicated identity-keyed `track_blocklist` table + `BlocklistGuard` that gates every track-source flow, so blocking a track means it's deleted from disk and permanently rejected at every future entry point (sync, mix, discovery, search, download, swap, failed-match).

**Architecture:** A new `track_blocklist` table holds canonical-identity rows that survive `tracks` table churn. A `BlocklistGuard` singleton injected into every track-source flow consults the table BEFORE any insert / queue / link / download operation. The block action becomes atomic: insert into blocklist + tear down all `playlist_tracks` rows + delete download_queue rows + delete file + delete `tracks` row, all in one Room transaction. A one-shot integrity worker runs on next launch to clean up tracks that already leaked back during the broken-flag era.

**Tech Stack:** Kotlin, Room 2.x, Hilt, kotlinx-coroutines, JUnit4, mockk, Compose UI (for BlockedSongsViewModel rebinding).

---

## Background — Confirmed leak paths (the bugs we're closing)

The current `is_blacklisted` flag leaks via 16 paths grouped by mechanism:

| Group | Paths |
|---|---|
| **Re-download** | `DownloadQueueDao.getUnqueuedTrackIds`, `TrackDownloadWorker.doWork`, `markBlacklistedAndClear` doesn't clean `playlist_tracks` |
| **Mix re-link** | `StashMixRefreshWorker.materializeMix` Discovery DONE re-link, `DiscoveryQueueDao.getDoneTrackIdsForRecipe` no filter, `MixGenerator.queueDiscoveryCandidates` no check, `StashDiscoveryWorker.handle` no guard |
| **Sync upsert clobber** | `Track.toEntity()` drops the flag, `DiffWorker` UNIQUE-index REPLACE silently clears flag |
| **UI/queue rendering** | `TrackDao.search`, `TrackDao.getByPlaylist`, `MusicRepositoryImpl.getAllTracks` no filter |
| **Resurrection** | `SearchDownloadCoordinator.upsertSearchTrack`, `SwapCoordinator.swap`, `FailedMatchesViewModel.approveMatch` |
| **UX bug** | `removeTrackFromPlaylistAndMaybeDelete` silently skips `alsoBlacklist` when track is in another playlist |

The architectural fix below closes ALL of these by moving the source of truth out of `tracks` and gating all flows at a single guard.

---

## File Structure

### Created files

| Path | Responsibility |
|---|---|
| `core/data/src/main/kotlin/com/stash/core/data/db/entity/TrackBlocklistEntity.kt` | Room entity for the new `track_blocklist` table |
| `core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackBlocklistDao.kt` | DAO: insert / delete / observe / query-by-identity |
| `core/data/src/main/kotlin/com/stash/core/data/blocklist/BlocklistGuard.kt` | Singleton: `isBlocked()`, `block()`, `unblock()`, `observeBlocklist()` |
| `core/data/src/main/kotlin/com/stash/core/data/blocklist/BlocklistKey.kt` | Tiny value class: canonical-key derivation from (artist, title) |
| `core/data/src/main/kotlin/com/stash/core/data/blocklist/FileDeleter.kt` | Indirection over `File.delete` so `BlocklistGuard` is unit-testable |
| `core/data/src/main/kotlin/com/stash/core/data/sync/workers/BlocklistIntegrityWorker.kt` | One-shot cleanup worker for v0.9.13/14-era leaked tracks |
| `core/data/src/test/kotlin/com/stash/core/data/blocklist/BlocklistKeyTest.kt` | Unit tests for canonical-key derivation |
| `core/data/src/test/kotlin/com/stash/core/data/blocklist/BlocklistGuardTest.kt` | Unit tests for guard logic + atomic block |
| `core/data/src/test/kotlin/com/stash/core/data/db/MigrationV18V19Test.kt` | Migration test: v18 → v19 backfill from `is_blacklisted=1` rows |

### Modified files

| Path | Change |
|---|---|
| `core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt` | bump version 18 → 19, register `TrackBlocklistEntity`, add `MIGRATION_18_19`, add `trackBlocklistDao()` |
| `core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepositoryImpl.kt:454-460` | `blacklistTrack` delegates to `BlocklistGuard.block` |
| `core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepositoryImpl.kt:345-407` | `removeTrackFromPlaylistAndMaybeDelete` always honors `alsoBlacklist` |
| `core/data/src/main/kotlin/com/stash/core/data/db/dao/DownloadQueueDao.kt:213-230` | `getUnqueuedTrackIds` LEFT JOIN `track_blocklist`, exclude matches |
| `core/data/src/main/kotlin/com/stash/core/data/sync/workers/TrackDownloadWorker.kt:216-227` | guard before `trackDownloader.downloadTrack` |
| `core/data/src/main/kotlin/com/stash/core/data/sync/workers/DiffWorker.kt:260-410` | guard before `findExistingTrack`; remove the now-redundant `existingTrack.isBlacklisted` short-circuit |
| `core/data/src/main/kotlin/com/stash/core/data/mix/MixGenerator.kt:80,267-282` | `getAllDownloadedNonBlacklisted` keeps for now; add guard in `queueDiscoveryCandidates` |
| `core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashDiscoveryWorker.kt:161-232` | guard at top of `handle` |
| `core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorker.kt:230-243` | filter `getDoneTrackIdsForRecipe` results through guard before re-linking |
| `data/download/src/main/kotlin/com/stash/data/download/search/SearchDownloadCoordinator.kt:266-320` | guard at entry of `upsertSearchTrack` |
| `data/download/src/main/kotlin/com/stash/data/download/files/SwapCoordinator.kt:79-130` | guard at entry of `swap` |
| `feature/sync/src/main/kotlin/com/stash/feature/sync/FailedMatchesViewModel.kt:380-395` | guard before re-marking downloaded |
| `feature/settings/src/main/kotlin/com/stash/feature/settings/BlockedSongsViewModel.kt` | rebind to `BlocklistGuard.observeBlocklist()` |
| `core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt` | (Phase 3) drop `is_blacklisted`-related queries; column removed in migration |
| `core/data/src/main/kotlin/com/stash/core/data/db/entity/TrackEntity.kt:115-116` | (Phase 3) remove `isBlacklisted` field after migration v19→v20 drops the column |
| `app/src/main/kotlin/com/stash/app/StashApplication.kt` | enqueue `BlocklistIntegrityWorker` on cold start (one-shot, idempotent) |
| `app/build.gradle.kts:75-76` | versionCode 52 → 53, versionName "0.9.14" → "0.9.15" |

---

## Phase 0 — Branch + schema verification (prerequisites)

### Task 0.1: Verify branch + schema

- [ ] **Step 1: Confirm we're on the right branch**

```bash
git branch --show-current
```
Expected: `feat/blocklist-redesign`. If not, the worktree may have been created against a different base — stop and ask.

- [ ] **Step 2: Read the v18 schema export**

Open `core/data/schemas/com.stash.core.data.db.StashDatabase/18.json` and confirm the `tracks` table has these column names exactly: `canonical_artist`, `canonical_title`, `spotify_uri`, `youtube_id`, `date_added`, `is_blacklisted`. If the column names differ from what Task 1.4's seed query assumes, update the seed SQL accordingly before running the migration test.

```bash
ls core/data/schemas/com.stash.core.data.db.StashDatabase/
grep -E '"name": "(canonical_artist|canonical_title|spotify_uri|youtube_id|date_added|is_blacklisted)"' core/data/schemas/com.stash.core.data.db.StashDatabase/18.json
```

Expected: all six column names present. Document the exact `notNull` and `defaultValue` for each as a comment in `MIGRATION_18_19` so the seed query's COALESCE/CASE handling matches reality.

---

## Phase 1 — Schema + guard scaffolding (no behavior change yet)

Builds the new table, DAO, guard class, and migration. Behavior is unchanged for users — nothing calls the guard yet. Ships as a single PR or a chain of small commits. End state after Phase 1: `track_blocklist` table exists, has migrated data from `is_blacklisted=1` rows, guard is callable but not called.

> **Note on the "sync upsert clobber" leak path** (DiffWorker REPLACE clobbering `is_blacklisted` to 0 via UNIQUE-index conflict): in the new design, the source of truth moves OUT of the `tracks` table entirely. The `is_blacklisted` flag on a `tracks` row becomes irrelevant — once Phase 2 wires the guard at the snapshot loop in DiffWorker (Task 2.3), every snapshot is rejected at the door BEFORE any insert/REPLACE happens. There is nothing to clobber because the row never gets written. Phase 3 then drops the column entirely. This is intentional — do NOT add belt-and-braces preserve-flag-on-upsert logic.

### Task 1.1: TrackBlocklistEntity + DAO

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/db/entity/TrackBlocklistEntity.kt`
- Create: `core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackBlocklistDao.kt`
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt:50-93` (register entity + add abstract dao)

- [ ] **Step 1: Create the entity**

```kotlin
// core/data/src/main/kotlin/com/stash/core/data/db/entity/TrackBlocklistEntity.kt
package com.stash.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * v0.9.15: Identity-keyed blocklist. Replaces the prior `tracks.is_blacklisted`
 * flag, which leaked because (a) the flag could be silently cleared by REPLACE
 * upserts and the Track↔Entity mapper, and (b) every read path that forgot to
 * filter the flag would surface blocked tracks. The blocklist is now keyed by
 * canonical identity (artist + title), so blocked tracks can't reappear via a
 * different `tracks` row, a different source, or a canonical normaliser
 * disagreement.
 *
 * Identity precedence at lookup time is: [canonicalKey] (always present) →
 * [spotifyUri] / [youtubeId] (belt-and-braces match for cross-source dupes).
 */
@Entity(
    tableName = "track_blocklist",
    indices = [
        Index(value = ["spotify_uri"], unique = false),
        Index(value = ["youtube_id"], unique = false),
    ],
)
data class TrackBlocklistEntity(
    /** Canonical key: `${canonicalArtist}|${canonicalTitle}`. Primary key. */
    @PrimaryKey
    @ColumnInfo(name = "canonical_key")
    val canonicalKey: String,

    @ColumnInfo(name = "artist")
    val artist: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "spotify_uri")
    val spotifyUri: String? = null,

    @ColumnInfo(name = "youtube_id")
    val youtubeId: String? = null,

    @ColumnInfo(name = "blocked_at")
    val blockedAt: Long,

    /**
     * Attribution: where the block originated. Used for telemetry and the
     * Settings UI's "blocked from N" label. Values: NOW_PLAYING, CONTEXT_MENU,
     * PLAYLIST_DELETE, MIGRATION_V19, INTEGRITY_WORKER, OTHER.
     */
    @ColumnInfo(name = "blocked_from")
    val blockedFrom: String,
)
```

- [ ] **Step 2: Create the DAO**

```kotlin
// core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackBlocklistDao.kt
package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stash.core.data.db.entity.TrackBlocklistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackBlocklistDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: TrackBlocklistEntity)

    @Query("DELETE FROM track_blocklist WHERE canonical_key = :canonicalKey")
    suspend fun deleteByKey(canonicalKey: String)

    @Query("SELECT * FROM track_blocklist WHERE canonical_key = :canonicalKey LIMIT 1")
    suspend fun findByKey(canonicalKey: String): TrackBlocklistEntity?

    @Query("SELECT * FROM track_blocklist WHERE spotify_uri = :spotifyUri LIMIT 1")
    suspend fun findBySpotifyUri(spotifyUri: String): TrackBlocklistEntity?

    @Query("SELECT * FROM track_blocklist WHERE youtube_id = :youtubeId LIMIT 1")
    suspend fun findByYoutubeId(youtubeId: String): TrackBlocklistEntity?

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM track_blocklist
            WHERE canonical_key = :canonicalKey
               OR (spotify_uri IS NOT NULL AND spotify_uri = :spotifyUri)
               OR (youtube_id  IS NOT NULL AND youtube_id  = :youtubeId)
        )
        """
    )
    suspend fun isBlocked(
        canonicalKey: String,
        spotifyUri: String?,
        youtubeId: String?,
    ): Boolean

    @Query("SELECT canonical_key FROM track_blocklist")
    suspend fun getAllKeys(): List<String>

    @Query("SELECT * FROM track_blocklist ORDER BY blocked_at DESC")
    fun observeAll(): Flow<List<TrackBlocklistEntity>>

    @Query("SELECT COUNT(*) FROM track_blocklist")
    fun observeCount(): Flow<Int>
}
```

- [ ] **Step 3: Register entity + DAO in StashDatabase**

```kotlin
// StashDatabase.kt:50-67 — add TrackBlocklistEntity::class to entities list
@Database(
    entities = [
        TrackEntity::class,
        // ... existing entities ...
        DiscoveryQueueEntity::class,
        TrackBlocklistEntity::class,  // NEW
    ],
    version = 19,                       // bump 18 → 19
    exportSchema = true,
)

// StashDatabase.kt:73-93 — add abstract method
abstract fun trackBlocklistDao(): TrackBlocklistDao
```

- [ ] **Step 4: Verify build compiles**

```bash
./gradlew :core:data:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/db/entity/TrackBlocklistEntity.kt \
        core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackBlocklistDao.kt \
        core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt
git commit -m "feat(blocklist): add TrackBlocklistEntity + DAO + schema v19"
```

---

### Task 1.2: BlocklistKey value class + tests

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/blocklist/BlocklistKey.kt`
- Create: `core/data/src/test/kotlin/com/stash/core/data/blocklist/BlocklistKeyTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
// core/data/src/test/kotlin/com/stash/core/data/blocklist/BlocklistKeyTest.kt
package com.stash.core.data.blocklist

import com.stash.core.data.sync.TrackMatcher
import org.junit.Assert.assertEquals
import org.junit.Test

class BlocklistKeyTest {
    private val matcher = TrackMatcher()

    @Test
    fun `canonical key combines artist and title with pipe separator`() {
        val key = BlocklistKey.of("Arctic Monkeys", "505", matcher)
        assertEquals("arctic monkeys|505", key)
    }

    @Test
    fun `canonical key strips parenthetical version suffixes`() {
        val key = BlocklistKey.of("Arctic Monkeys", "505 (Remastered)", matcher)
        assertEquals("arctic monkeys|505", key)
    }

    @Test
    fun `canonical key normalises featured-artist syntax in artist field`() {
        val key1 = BlocklistKey.of("Drake feat. Future", "Way 2 Sexy", matcher)
        val key2 = BlocklistKey.of("Drake (ft. Future)", "Way 2 Sexy", matcher)
        assertEquals(key1, key2)
    }

    @Test
    fun `canonical key is case insensitive`() {
        val key1 = BlocklistKey.of("ARCTIC MONKEYS", "505", matcher)
        val key2 = BlocklistKey.of("arctic monkeys", "505", matcher)
        assertEquals(key1, key2)
    }
}
```

- [ ] **Step 2: Run tests, expect FAIL**

```bash
./gradlew :core:data:test --tests "com.stash.core.data.blocklist.BlocklistKeyTest"
```
Expected: FAIL — `BlocklistKey` does not exist.

- [ ] **Step 3: Write the minimal implementation**

```kotlin
// core/data/src/main/kotlin/com/stash/core/data/blocklist/BlocklistKey.kt
package com.stash.core.data.blocklist

import com.stash.core.data.sync.TrackMatcher

/**
 * v0.9.15: Identity key used to look up a (artist, title) pair in
 * `track_blocklist`. Reuses the same canonicalisers [TrackMatcher] uses
 * for sync deduplication so a track blocked here is recognised under
 * the same identity rules sync uses to merge cross-source duplicates.
 *
 * Format: `"${canonicalArtist}|${canonicalTitle}"` (lowercase, normalised).
 */
object BlocklistKey {
    fun of(artist: String, title: String, matcher: TrackMatcher): String {
        val canonicalArtist = matcher.canonicalArtist(artist)
        val canonicalTitle = matcher.canonicalTitle(title)
        return "$canonicalArtist|$canonicalTitle"
    }
}
```

- [ ] **Step 4: Run tests, expect PASS**

```bash
./gradlew :core:data:test --tests "com.stash.core.data.blocklist.BlocklistKeyTest"
```
Expected: PASS, 4/4.

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/blocklist/BlocklistKey.kt \
        core/data/src/test/kotlin/com/stash/core/data/blocklist/BlocklistKeyTest.kt
git commit -m "feat(blocklist): add canonical-key derivation"
```

---

### Task 1.3: BlocklistGuard + tests

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/blocklist/BlocklistGuard.kt`
- Create: `core/data/src/test/kotlin/com/stash/core/data/blocklist/BlocklistGuardTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
// core/data/src/test/kotlin/com/stash/core/data/blocklist/BlocklistGuardTest.kt
package com.stash.core.data.blocklist

import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.TrackBlocklistDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackBlocklistEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.sync.TrackMatcher
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlocklistGuardTest {

    @Test
    fun `isBlocked returns true when canonical key matches`() = runTest {
        val dao = mockk<TrackBlocklistDao>(relaxed = true)
        coEvery { dao.isBlocked("arctic monkeys|505", null, null) } returns true
        val guard = buildGuard(blocklistDao = dao)

        val result = guard.isBlocked(artist = "Arctic Monkeys", title = "505", spotifyUri = null, youtubeId = null)

        assertTrue(result)
    }

    @Test
    fun `isBlocked returns false when no identity matches`() = runTest {
        val dao = mockk<TrackBlocklistDao>(relaxed = true)
        coEvery { dao.isBlocked(any(), any(), any()) } returns false
        val guard = buildGuard(blocklistDao = dao)

        val result = guard.isBlocked(artist = "Arctic Monkeys", title = "Brianstorm", spotifyUri = null, youtubeId = null)

        assertFalse(result)
    }

    @Test
    fun `block runs atomically: insert blocklist row + delete file + clean playlist_tracks + delete track row`() = runTest {
        val blocklistDao = mockk<TrackBlocklistDao>(relaxed = true)
        val trackDao = mockk<TrackDao>(relaxed = true)
        val playlistDao = mockk<PlaylistDao>(relaxed = true)
        val downloadQueueDao = mockk<DownloadQueueDao>(relaxed = true)
        val fileDeleter = mockk<FileDeleter>(relaxed = true)
        val track = TrackEntity(
            id = 42L, title = "505", artist = "Arctic Monkeys",
            album = "Favourite Worst Nightmare", source = com.stash.core.model.MusicSource.SPOTIFY,
            sourceId = "spotify:track:abc", filePath = "/sdcard/505.flac",
            albumArtPath = "/sdcard/505.jpg", isDownloaded = true,
            canonicalArtist = "arctic monkeys", canonicalTitle = "505",
        )
        coEvery { trackDao.getById(42L) } returns track
        val captured = slot<TrackBlocklistEntity>()
        coEvery { blocklistDao.insert(capture(captured)) } returns Unit

        val guard = buildGuard(
            blocklistDao = blocklistDao,
            trackDao = trackDao,
            playlistDao = playlistDao,
            downloadQueueDao = downloadQueueDao,
            fileDeleter = fileDeleter,
        )
        guard.block(track, BlockSource.NOW_PLAYING)

        assertEquals("arctic monkeys|505", captured.captured.canonicalKey)
        assertEquals("NOW_PLAYING", captured.captured.blockedFrom)
        coVerify(exactly = 1) { fileDeleter.delete("/sdcard/505.flac") }
        coVerify(exactly = 1) { fileDeleter.delete("/sdcard/505.jpg") }
        coVerify(exactly = 1) { playlistDao.deleteAllCrossRefsForTrack(42L) }
        coVerify(exactly = 1) { downloadQueueDao.deleteByTrackId(42L) }
        coVerify(exactly = 1) { trackDao.deleteById(42L) }
    }

    @Test
    fun `unblock removes the row by canonical key`() = runTest {
        val dao = mockk<TrackBlocklistDao>(relaxed = true)
        val guard = buildGuard(blocklistDao = dao)

        guard.unblock("arctic monkeys|505")

        coVerify(exactly = 1) { dao.deleteByKey("arctic monkeys|505") }
    }

    private fun buildGuard(
        blocklistDao: TrackBlocklistDao = mockk(relaxed = true),
        trackDao: TrackDao = mockk(relaxed = true),
        playlistDao: PlaylistDao = mockk(relaxed = true),
        downloadQueueDao: DownloadQueueDao = mockk(relaxed = true),
        fileDeleter: FileDeleter = mockk(relaxed = true),
        matcher: TrackMatcher = TrackMatcher(),
    ): BlocklistGuard = BlocklistGuard(
        blocklistDao = blocklistDao,
        trackDao = trackDao,
        playlistDao = playlistDao,
        downloadQueueDao = downloadQueueDao,
        fileDeleter = fileDeleter,
        matcher = matcher,
    )
}
```

- [ ] **Step 2: Add a tiny `FileDeleter` abstraction** (so unit tests don't need a real filesystem)

```kotlin
// core/data/src/main/kotlin/com/stash/core/data/blocklist/FileDeleter.kt
package com.stash.core.data.blocklist

import javax.inject.Inject
import javax.inject.Singleton
import java.io.File

/** Indirection over `File.delete` so [BlocklistGuard] is unit-testable. */
@Singleton
class FileDeleter @Inject constructor() {
    /** Best-effort delete. No-op if [path] is null or the file doesn't exist. */
    fun delete(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching { File(path).delete() }
    }
}
```

- [ ] **Step 3: Add the new helper queries on existing DAOs**

`core/data/src/main/kotlin/com/stash/core/data/db/dao/PlaylistDao.kt` — add:
```kotlin
@Query("DELETE FROM playlist_tracks WHERE track_id = :trackId")
suspend fun deleteAllCrossRefsForTrack(trackId: Long)
```

`core/data/src/main/kotlin/com/stash/core/data/db/dao/DownloadQueueDao.kt` — add:
```kotlin
@Query("DELETE FROM download_queue WHERE track_id = :trackId")
suspend fun deleteByTrackId(trackId: Long)
```

`core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt` — verify `deleteById(trackId: Long)` exists; if not, add:
```kotlin
@Query("DELETE FROM tracks WHERE id = :trackId")
suspend fun deleteById(trackId: Long)
```

- [ ] **Step 4: Run tests, expect FAIL (compile error)**

```bash
./gradlew :core:data:test --tests "com.stash.core.data.blocklist.BlocklistGuardTest"
```
Expected: FAIL — `BlocklistGuard` not found.

- [ ] **Step 5: Implement BlocklistGuard**

```kotlin
// core/data/src/main/kotlin/com/stash/core/data/blocklist/BlocklistGuard.kt
package com.stash.core.data.blocklist

import androidx.room.withTransaction
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.TrackBlocklistDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackBlocklistEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.sync.TrackMatcher
import com.stash.core.model.Track
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

enum class BlockSource {
    NOW_PLAYING, CONTEXT_MENU, PLAYLIST_DELETE, MIGRATION_V19, INTEGRITY_WORKER, OTHER
}

/**
 * v0.9.15: Single chokepoint for blocklist enforcement. Every track-source
 * flow (sync, mix, discovery, search, download, swap, failed-match) consults
 * this guard via [isBlocked] before inserting / queueing / linking. The block
 * action [block] runs atomically inside a Room transaction so the user can't
 * end up with an orphaned file or stale playlist_tracks rows.
 *
 * Identity-keyed: a block survives `tracks` row churn, source-switches, and
 * canonical-normaliser disagreements that produce duplicate rows.
 */
@Singleton
class BlocklistGuard @Inject constructor(
    private val database: StashDatabase,
    private val blocklistDao: TrackBlocklistDao,
    private val trackDao: TrackDao,
    private val playlistDao: PlaylistDao,
    private val downloadQueueDao: DownloadQueueDao,
    private val fileDeleter: FileDeleter,
    private val matcher: TrackMatcher,
) {

    /** Identity check by raw fields. Used by sync workers reading from snapshots. */
    suspend fun isBlocked(
        artist: String,
        title: String,
        spotifyUri: String?,
        youtubeId: String?,
    ): Boolean {
        val key = BlocklistKey.of(artist, title, matcher)
        return blocklistDao.isBlocked(key, spotifyUri, youtubeId)
    }

    /** Identity check by trackId (looks up the row, then forwards). */
    suspend fun isBlockedByTrackId(trackId: Long): Boolean {
        val track = trackDao.getById(trackId) ?: return false
        return isBlocked(track.artist, track.title, track.spotifyUri, track.youtubeId)
    }

    /**
     * Atomic block. Inserts a blocklist entry, hard-deletes every
     * `playlist_tracks` row referencing this track id, deletes any
     * `download_queue` rows, deletes the audio + art files, and deletes the
     * `tracks` row itself. All in one Room transaction.
     */
    suspend fun block(track: TrackEntity, source: BlockSource) {
        val key = BlocklistKey.of(track.artist, track.title, matcher)
        val entry = TrackBlocklistEntity(
            canonicalKey = key,
            artist = track.artist,
            title = track.title,
            spotifyUri = track.spotifyUri,
            youtubeId = track.youtubeId,
            blockedAt = System.currentTimeMillis(),
            blockedFrom = source.name,
        )
        // Delete files first — outside the transaction to avoid holding a
        // write lock across slow I/O. If file delete fails (e.g., storage
        // unmounted), the row deletes still proceed and we accept an orphaned
        // file on disk. The current integrity worker does NOT walk the music
        // directory looking for orphans — if that ever becomes a real issue,
        // add a periodic file-orphan sweep separately.
        fileDeleter.delete(track.filePath)
        fileDeleter.delete(track.albumArtPath)

        database.withTransaction {
            blocklistDao.insert(entry)
            playlistDao.deleteAllCrossRefsForTrack(track.id)
            downloadQueueDao.deleteByTrackId(track.id)
            trackDao.deleteById(track.id)
        }
    }

    /** Remove the block. Used by the Settings unblock action. */
    suspend fun unblock(canonicalKey: String) {
        blocklistDao.deleteByKey(canonicalKey)
    }

    /** UI feed for the Blocked Songs viewer. */
    fun observeBlocklist(): Flow<List<TrackBlocklistEntity>> = blocklistDao.observeAll()
}
```

- [ ] **Step 6: Run tests, expect PASS**

```bash
./gradlew :core:data:test --tests "com.stash.core.data.blocklist.BlocklistGuardTest"
```
Expected: PASS, 4/4.

- [ ] **Step 7: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/blocklist/ \
        core/data/src/test/kotlin/com/stash/core/data/blocklist/BlocklistGuardTest.kt \
        core/data/src/main/kotlin/com/stash/core/data/db/dao/PlaylistDao.kt \
        core/data/src/main/kotlin/com/stash/core/data/db/dao/DownloadQueueDao.kt \
        core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt
git commit -m "feat(blocklist): BlocklistGuard with atomic block transaction"
```

---

### Task 1.4: Migration v18 → v19 (create table + backfill from is_blacklisted=1)

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt:96-403` (add `MIGRATION_18_19`)
- Create: `core/data/src/test/kotlin/com/stash/core/data/db/MigrationV18V19Test.kt` (Robolectric-flavored migration test)

- [ ] **Step 1: Write the migration**

```kotlin
// StashDatabase.kt — add inside the companion object, after MIGRATION_17_18

/**
 * v18 → v19: introduce identity-keyed `track_blocklist` table and seed it
 * from existing `tracks.is_blacklisted = 1` rows. The `is_blacklisted`
 * column is NOT yet dropped (column drop happens in v19→v20 once all read
 * paths have been removed in Phase 3) — this migration is purely additive
 * to keep rollback-on-failure safe.
 *
 * Rationale: see docs/superpowers/specs/2026-05-07-blocklist-redesign.md.
 */
val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS track_blocklist (
                canonical_key TEXT NOT NULL PRIMARY KEY,
                artist        TEXT NOT NULL,
                title         TEXT NOT NULL,
                spotify_uri   TEXT,
                youtube_id    TEXT,
                blocked_at    INTEGER NOT NULL,
                blocked_from  TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_track_blocklist_spotify_uri ON track_blocklist(spotify_uri)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_track_blocklist_youtube_id ON track_blocklist(youtube_id)"
        )

        // Seed from any existing flag-marked rows. canonical_artist and
        // canonical_title are NOT NULL DEFAULT '' on tracks — we use them
        // directly as the key components. blocked_at falls back to date_added
        // (also NOT NULL DEFAULT 0); the worst case is a 1970 timestamp,
        // acceptable for migration metadata.
        db.execSQL(
            """
            INSERT OR IGNORE INTO track_blocklist (canonical_key, artist, title, spotify_uri, youtube_id, blocked_at, blocked_from)
            SELECT
                canonical_artist || '|' || canonical_title AS canonical_key,
                artist,
                title,
                spotify_uri,
                youtube_id,
                CASE WHEN date_added > 0 THEN date_added ELSE strftime('%s','now') * 1000 END,
                'MIGRATION_V19'
            FROM tracks
            WHERE is_blacklisted = 1
            """.trimIndent()
        )
    }
}
```

- [ ] **Step 2: Wire the migration into the Room builder**

Find `Room.databaseBuilder` in the Hilt module that constructs `StashDatabase` (likely `core/data/src/main/kotlin/com/stash/core/data/di/DatabaseModule.kt`) and append `.addMigrations(StashDatabase.MIGRATION_18_19)` to the existing chain.

```bash
grep -rn "addMigrations" core/data/src/main/kotlin/
```

- [ ] **Step 3: Write the migration test (Robolectric)**

```kotlin
// core/data/src/test/kotlin/com/stash/core/data/db/MigrationV18V19Test.kt
package com.stash.core.data.db

import android.content.ContentValues
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV18V19Test {

    private val DB_NAME = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `migrate from v18 to v19 creates blocklist table and seeds from is_blacklisted rows`() {
        helper.createDatabase(DB_NAME, 18).use { db ->
            db.insert("tracks", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE, ContentValues().apply {
                put("id", 1L)
                put("title", "505")
                put("artist", "Arctic Monkeys")
                put("album", "Favourite Worst Nightmare")
                put("source", "SPOTIFY")
                put("source_id", "abc")
                put("spotify_uri", "spotify:track:abc")
                put("canonical_title", "505")
                put("canonical_artist", "arctic monkeys")
                put("is_blacklisted", 1)
                put("date_added", 1700000000L)
                // ... other NOT NULL columns with sensible defaults
            })
            // Insert a non-blacklisted track to verify it does NOT migrate
            db.insert("tracks", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE, ContentValues().apply {
                put("id", 2L)
                put("title", "Brianstorm")
                put("artist", "Arctic Monkeys")
                put("canonical_title", "brianstorm")
                put("canonical_artist", "arctic monkeys")
                put("is_blacklisted", 0)
                // ... defaults
            })
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 19, true, StashDatabase.MIGRATION_18_19)

        val cursor = db.query("SELECT canonical_key, artist, blocked_from FROM track_blocklist")
        cursor.use {
            assertEquals(1, it.count)
            it.moveToFirst()
            assertEquals("arctic monkeys|505", it.getString(0))
            assertEquals("Arctic Monkeys", it.getString(1))
            assertEquals("MIGRATION_V19", it.getString(2))
        }
    }
}
```

- [ ] **Step 4: Run the migration test, expect PASS**

```bash
./gradlew :core:data:test --tests "com.stash.core.data.db.MigrationV18V19Test"
```
Expected: PASS, 1/1.

- [ ] **Step 5: Run the full app build to verify everything compiles**

```bash
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt \
        core/data/src/main/kotlin/com/stash/core/data/di/DatabaseModule.kt \
        core/data/src/test/kotlin/com/stash/core/data/db/MigrationV18V19Test.kt
git commit -m "feat(blocklist): migration v18->v19 + backfill from is_blacklisted=1"
```

---

## Phase 2 — Wire chokepoints (the real fix)

After Phase 2, blocking actually sticks. Each task is one chokepoint and should be one commit.

### Task 2.1: DownloadQueueDao filter

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/dao/DownloadQueueDao.kt:213-230` (`getUnqueuedTrackIds`), `:68-82` (`getAllPendingBySources`), `:92-106` (`getRetryableBySources`)

- [ ] **Step 1: Add the LEFT JOIN exclusion to `getUnqueuedTrackIds`**

Replace the existing query with:
```sql
SELECT t.id
FROM tracks t
LEFT JOIN track_blocklist bl
    ON bl.canonical_key = (t.canonical_artist || '|' || t.canonical_title)
    OR (bl.spotify_uri IS NOT NULL AND bl.spotify_uri = t.spotify_uri)
    OR (bl.youtube_id  IS NOT NULL AND bl.youtube_id  = t.youtube_id)
WHERE t.is_downloaded = 0
  AND t.match_dismissed = 0
  AND t.source IN (:sources)
  AND bl.canonical_key IS NULL
  AND EXISTS (...)            -- existing playlist EXISTS clause unchanged
  AND t.id NOT IN (...)        -- existing download_queue NOT IN clause unchanged
```

- [ ] **Step 2: Apply the same `LEFT JOIN ... bl.canonical_key IS NULL` to `getAllPendingBySources` and `getRetryableBySources`**

- [ ] **Step 3: Write a test**

```kotlin
// new test file or extend existing DownloadQueueDaoTest
@Test
fun `getUnqueuedTrackIds excludes blocklisted tracks`() = runTest {
    // Insert a track + a sync-enabled playlist + cross-ref + blocklist row
    // Call getUnqueuedTrackIds(...)
    // Assert: returned list does NOT include the blocklisted track id
}
```

- [ ] **Step 4: Run tests, expect PASS**

- [ ] **Step 5: Commit**

```bash
git commit -m "fix(blocklist): exclude blocklisted tracks from download queue feeders"
```

---

### Task 2.2: TrackDownloadWorker guard

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/sync/workers/TrackDownloadWorker.kt:216-227`

- [ ] **Step 1: Inject BlocklistGuard**

Hilt-inject `private val blocklistGuard: BlocklistGuard` via the worker's `@AssistedInject` or constructor pattern (match what other workers do).

- [ ] **Step 2: Add the guard right after fetching the track entity**

```kotlin
val trackEntity = trackDao.getById(queueItem.trackId) ?: continue

if (blocklistGuard.isBlocked(
        artist = trackEntity.artist, title = trackEntity.title,
        spotifyUri = trackEntity.spotifyUri, youtubeId = trackEntity.youtubeId,
    )) {
    Log.d(TAG, "Skipping blocked track ${trackEntity.id} (${trackEntity.artist} - ${trackEntity.title})")
    downloadQueueDao.deleteByTrackId(trackEntity.id)
    continue
}
```

- [ ] **Step 3: Write a test**

```kotlin
@Test
fun `doWork skips blocklisted tracks and removes them from the queue`() = runTest {
    coEvery { blocklistGuard.isBlocked(any(), any(), any(), any()) } returns true
    // ... setup queueItem ...
    worker.doWork()
    coVerify(exactly = 0) { trackDownloader.downloadTrack(any()) }
    coVerify(exactly = 1) { downloadQueueDao.deleteByTrackId(42L) }
}
```

- [ ] **Step 4: Run tests, expect PASS**

- [ ] **Step 5: Commit**

---

### Task 2.3: DiffWorker guard

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/sync/workers/DiffWorker.kt:260-410`

- [ ] **Step 1: Inject BlocklistGuard**

- [ ] **Step 2: Add the guard at the TOP of `processPlaylist`'s per-snapshot loop, BEFORE `findExistingTrack`**

```kotlin
for (trackSnapshot in trackSnapshots) {
    if (blocklistGuard.isBlocked(
            artist = trackSnapshot.artist, title = trackSnapshot.title,
            spotifyUri = trackSnapshot.spotifyUri, youtubeId = trackSnapshot.youtubeId,
        )) {
        Log.d(TAG, "Skipping blocklisted snapshot: ${trackSnapshot.artist} - ${trackSnapshot.title}")
        continue
    }
    // ... existing logic ...
}
```

- [ ] **Step 3: Remove the now-redundant `existingTrack.isBlacklisted` short-circuit at line 315** (keep the column for now; just remove the read from this worker, since the guard runs first).

- [ ] **Step 4: Write a test**

```kotlin
// core/data/src/test/kotlin/com/stash/core/data/sync/workers/DiffWorkerBlocklistTest.kt
@Test
fun `processPlaylist skips snapshots whose identity is in the blocklist`() = runTest {
    val blocklistGuard = mockk<BlocklistGuard>()
    val trackDao = mockk<TrackDao>(relaxed = true)
    val playlistDao = mockk<PlaylistDao>(relaxed = true)
    val downloadQueueDao = mockk<DownloadQueueDao>(relaxed = true)
    val snapshot = RemoteTrackSnapshot(
        artist = "Arctic Monkeys", title = "505",
        spotifyUri = "spotify:track:abc", youtubeId = null,
        // ... other required fields ...
    )
    coEvery {
        blocklistGuard.isBlocked("Arctic Monkeys", "505", "spotify:track:abc", null)
    } returns true

    val worker = buildDiffWorker(
        blocklistGuard = blocklistGuard, trackDao = trackDao,
        playlistDao = playlistDao, downloadQueueDao = downloadQueueDao,
    )
    worker.processPlaylist(playlistId = 1L, snapshots = listOf(snapshot))

    // Assert: no insert, no playlist link, no download queue entry
    coVerify(exactly = 0) { trackDao.insert(any()) }
    coVerify(exactly = 0) { playlistDao.insertCrossRef(any()) }
    coVerify(exactly = 0) { downloadQueueDao.insert(any()) }
}

@Test
fun `processPlaylist proceeds normally when snapshot is not in blocklist`() = runTest {
    val blocklistGuard = mockk<BlocklistGuard>()
    coEvery { blocklistGuard.isBlocked(any(), any(), any(), any()) } returns false
    // ... usual happy-path setup ...
    coVerify(atLeast = 1) { trackDao.insert(any()) }
}
```

- [ ] **Step 5: Run tests, expect PASS**

```bash
./gradlew :core:data:test --tests "com.stash.core.data.sync.workers.DiffWorkerBlocklistTest"
```

- [ ] **Step 6: Commit**

```bash
git commit -m "fix(blocklist): DiffWorker rejects blocklisted snapshots at the door"
```

---

### Task 2.4: Mix + Discovery guards (3 sites)

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/mix/MixGenerator.kt:267-282` (`queueDiscoveryCandidates`)
- Modify: `core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashDiscoveryWorker.kt:161-232` (`handle`)
- Modify: `core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorker.kt:230-243` (Discovery DONE re-link)

- [ ] **Step 1: MixGenerator.queueDiscoveryCandidates** — guard each candidate before insert:

```kotlin
candidates.forEach { c ->
    if (blocklistGuard.isBlocked(c.artist, c.title, c.spotifyUri, c.youtubeId)) return@forEach
    discoveryQueueDao.insert(...)
}
```

- [ ] **Step 2: StashDiscoveryWorker.handle** — at the top:

```kotlin
if (blocklistGuard.isBlocked(queueItem.artist, queueItem.title, null, null)) {
    Log.d(TAG, "Skipping blocked discovery candidate: ${queueItem.artist} - ${queueItem.title}")
    discoveryQueueDao.markFailed(queueItem.id, "BLOCKED")
    return
}
```

- [ ] **Step 3: StashMixRefreshWorker.materializeMix** — filter `getDoneTrackIdsForRecipe` results:

```kotlin
val discoveryTrackIds = discoveryQueueDao
    .getDoneTrackIdsForRecipe(recipe.id)
    .filter { it !in librarySet }
    .filterNot { trackId -> blocklistGuard.isBlockedByTrackId(trackId) }
```

- [ ] **Step 4: Write tests for each (3 tests)**

```kotlin
// MixGeneratorBlocklistTest.kt
@Test
fun `queueDiscoveryCandidates skips candidates whose identity is blocked`() = runTest {
    coEvery { blocklistGuard.isBlocked("Arctic Monkeys", "505", any(), any()) } returns true
    coEvery { blocklistGuard.isBlocked(neq("Arctic Monkeys"), any(), any(), any()) } returns false
    val candidates = listOf(
        DiscoveryCandidate("Arctic Monkeys", "505", null, null),
        DiscoveryCandidate("Arctic Monkeys", "Brianstorm", null, null),
    )
    generator.queueDiscoveryCandidates(recipe, candidates)
    coVerify(exactly = 1) { discoveryQueueDao.insert(match { it.title == "Brianstorm" }) }
    coVerify(exactly = 0) { discoveryQueueDao.insert(match { it.title == "505" }) }
}

// StashDiscoveryWorkerBlocklistTest.kt
@Test
fun `handle marks discovery item failed when its identity is blocked`() = runTest {
    coEvery { blocklistGuard.isBlocked("Arctic Monkeys", "505", null, null) } returns true
    worker.handle(queueItem = item)
    coVerify(exactly = 1) { discoveryQueueDao.markFailed(item.id, "BLOCKED") }
    coVerify(exactly = 0) { trackDao.insert(any()) }
}

// StashMixRefreshWorkerBlocklistTest.kt
@Test
fun `materializeMix excludes blocklisted track ids from Discovery DONE re-link`() = runTest {
    coEvery { discoveryQueueDao.getDoneTrackIdsForRecipe(recipeId) } returns listOf(1L, 2L, 3L)
    coEvery { blocklistGuard.isBlockedByTrackId(1L) } returns true
    coEvery { blocklistGuard.isBlockedByTrackId(2L) } returns false
    coEvery { blocklistGuard.isBlockedByTrackId(3L) } returns false

    worker.materializeMix(recipe, libraryTracks = emptyList(), now = 0L)

    coVerify(exactly = 0) { playlistDao.insertCrossRef(match { it.trackId == 1L }) }
    coVerify(exactly = 1) { playlistDao.insertCrossRef(match { it.trackId == 2L }) }
    coVerify(exactly = 1) { playlistDao.insertCrossRef(match { it.trackId == 3L }) }
}
```

- [ ] **Step 5: Run tests, expect PASS for all three**

- [ ] **Step 6: Commit (one commit per file is fine; can also be one combined commit)**

---

### Task 2.5: Search + Swap + FailedMatch guards

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/search/SearchDownloadCoordinator.kt:266-320`
- Modify: `data/download/src/main/kotlin/com/stash/data/download/files/SwapCoordinator.kt:79-130`
- Modify: `feature/sync/src/main/kotlin/com/stash/feature/sync/FailedMatchesViewModel.kt:380-395`

- [ ] **Step 1: Inject `BlocklistGuard` into all three classes.**

- [ ] **Step 2: SearchDownloadCoordinator.upsertSearchTrack** — guard at the very top:

```kotlin
suspend fun upsertSearchTrack(searchResult: SearchResult): Long? {
    if (blocklistGuard.isBlocked(searchResult.artist, searchResult.title, null, searchResult.youtubeId)) {
        Log.d(TAG, "Search download blocked: ${searchResult.artist} - ${searchResult.title}")
        return null
    }
    // ... existing logic ...
}
```

- [ ] **Step 3: SwapCoordinator.swap** — guard before any disk I/O or DB write:

```kotlin
suspend fun swap(...): SwapResult {
    if (blocklistGuard.isBlockedByTrackId(trackId)) return SwapResult.Blocked
    // ... existing logic ...
}
```

Add a new `Blocked` variant to `SwapResult`. UI doesn't need to surface anything special — the swap silently no-ops, which is the correct behavior for a blocked track.

- [ ] **Step 4: FailedMatchesViewModel.approveMatch** — guard before re-marking downloaded:

```kotlin
fun approveMatch(trackId: Long, matchVideoId: String) {
    viewModelScope.launch {
        if (blocklistGuard.isBlockedByTrackId(trackId)) return@launch
        // ... existing logic ...
    }
}
```

- [ ] **Step 5: Write tests**

```kotlin
// SearchDownloadCoordinatorBlocklistTest.kt
@Test
fun `upsertSearchTrack returns null when identity is blocked`() = runTest {
    coEvery { blocklistGuard.isBlocked("Arctic Monkeys", "505", null, "vidId") } returns true
    val result = coordinator.upsertSearchTrack(SearchResult(artist = "Arctic Monkeys", title = "505", youtubeId = "vidId", ...))
    assertNull(result)
    coVerify(exactly = 0) { trackDao.insert(any()) }
}

// SwapCoordinatorBlocklistTest.kt — verify Blocked result + no markAsDownloaded.
// FailedMatchesViewModelBlocklistTest.kt — verify approve is a no-op when blocked.
```

- [ ] **Step 6: Run tests + commit**

---

### Task 2.6: UI/queue rendering filter (close leak group D)

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt` — `search` (line 619), `getByPlaylist` (line 184), `getAllByDateAdded` (line 174)
- Modify: `core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepositoryImpl.kt` — `getAllTracks` (line 131), `search`

**Why this task exists:** Phase 1+2 ships before Phase 3 deletes the `tracks` row for blocked tracks. During the rollout window AND during the Phase 4 integrity-worker race (between cold-start and worker-completes), `tracks` rows for blocked identities can still exist. Without this filter, those rows render in the Library "All Songs" view, the Search tab, and any Playlist detail screen — letting the user re-tap "play" or "download" on a ghost row. Once Phase 3 has run and the integrity worker has cleaned up, this filter becomes redundant but harmless.

- [ ] **Step 1: Add a LEFT-JOIN exclusion clause to each query**

`TrackDao.search` (FTS) — wrap the existing query:
```kotlin
@Query("""
    SELECT t.* FROM tracks t
    JOIN tracks_fts ON t.id = tracks_fts.docid
    LEFT JOIN track_blocklist bl
        ON bl.canonical_key = (t.canonical_artist || '|' || t.canonical_title)
        OR (bl.spotify_uri IS NOT NULL AND bl.spotify_uri = t.spotify_uri)
        OR (bl.youtube_id  IS NOT NULL AND bl.youtube_id  = t.youtube_id)
    WHERE tracks_fts MATCH :query AND bl.canonical_key IS NULL
    ORDER BY rank
""")
fun search(query: String): Flow<List<TrackEntity>>
```

`TrackDao.getByPlaylist` — add the same LEFT JOIN + `AND bl.canonical_key IS NULL`.

`TrackDao.getAllByDateAdded` — same.

- [ ] **Step 2: `MusicRepositoryImpl.getAllTracks` — no SQL change needed if it delegates to a DAO query that's now filtered. Verify.**

- [ ] **Step 3: Write tests**

```kotlin
// TrackDaoBlocklistFilterTest.kt
@Test
fun `getByPlaylist excludes tracks whose identity is in the blocklist`() = runTest {
    // Insert 2 tracks, 1 playlist, 2 cross-refs, blocklist row matching track #1
    val results = trackDao.getByPlaylist(playlistId).first()
    assertEquals(1, results.size)
    assertEquals(2L, results[0].id)
}

// Plus 2 similar tests for search and getAllByDateAdded.
```

- [ ] **Step 4: Run tests, expect PASS**

- [ ] **Step 5: Commit**

```bash
git commit -m "fix(blocklist): filter blocked identities from UI list/search/playlist queries"
```

---

### Task 2.7: Build + on-device smoke test (Phase 2 acceptance)

- [ ] Run `./gradlew :app:installDebug`.
- [ ] On Pixel 6 Pro: block a track from Now Playing, force-stop the app, reopen, trigger a sync. Verify the track does NOT reappear.
- [ ] In a Stash Mix recipe, verify a previously-Discovery-DONE blocked track does NOT re-appear after `StashMixRefreshWorker` runs.
- [ ] Commit any fixes; tag this commit `phase-2-acceptance`.

---

## Phase 3 — Replace block-action paths + drop the column

### Task 3.1: blacklistTrack delegates to BlocklistGuard.block

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepositoryImpl.kt:454-460`

- [ ] **Step 1: Replace the body of `blacklistTrack(trackId)`**

The `BlockSource` parameter should be passed in by callers so attribution is meaningful. Update the `MusicRepository` interface signature:

```kotlin
// MusicRepository interface
suspend fun blacklistTrack(trackId: Long, source: BlockSource = BlockSource.OTHER)
```

```kotlin
// MusicRepositoryImpl
override suspend fun blacklistTrack(trackId: Long, source: BlockSource) {
    val track = trackDao.getById(trackId) ?: return
    blocklistGuard.block(track, source)
    _trackDeletions.tryEmit(trackId)
}
```

Update the two callers to pass the right source:
- `feature/library/.../LibraryViewModel.kt:279` `deleteTrack(...)` → pass `BlockSource.CONTEXT_MENU`
- `feature/nowplaying/.../NowPlayingViewModel.kt:270` `deleteCurrentTrack(...)` → pass `BlockSource.NOW_PLAYING`

- [ ] **Step 2: Replace the body of `unblacklistTrack`** — derive the canonical key from the `tracks` row if it still exists, or accept a key directly:

```kotlin
override suspend fun unblacklistTrack(trackId: Long) {
    // Once Phase 3 ships and Phase 4 cleans up, the tracks row is gone for
    // blocked identities, so this lookup will always be null. The call still
    // works because BlockedSongsViewModel now drives unblock by canonical_key
    // directly (see Task 3.3) — this method exists only for backward compat
    // and can be removed in a later cleanup pass.
    val track = trackDao.getById(trackId) ?: return
    val key = BlocklistKey.of(track.artist, track.title, trackMatcher)
    blocklistGuard.unblock(key)
}
```

- [ ] **Step 3: Test**

```kotlin
@Test
fun `blacklistTrack delegates to BlocklistGuard with the supplied source`() = runTest {
    val track = TrackEntity(id = 42L, ...)
    coEvery { trackDao.getById(42L) } returns track
    val sourceCaptured = slot<BlockSource>()
    coEvery { blocklistGuard.block(track, capture(sourceCaptured)) } just Runs

    repo.blacklistTrack(42L, BlockSource.NOW_PLAYING)

    assertEquals(BlockSource.NOW_PLAYING, sourceCaptured.captured)
}
```

- [ ] **Step 4: Commit**

---

### Task 3.2: removeTrackFromPlaylistAndMaybeDelete cascade-bug fix

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepositoryImpl.kt:345-407`

- [ ] **Step 1: Change semantics**

If `alsoBlacklist == true`, ALWAYS apply the block regardless of whether the track is in protected or other playlists. The protection logic was a safety net for *delete-without-block*; an explicit block overrides it.

```kotlin
override suspend fun removeTrackFromPlaylistAndMaybeDelete(
    trackId: Long, fromPlaylistId: Long, alsoBlacklist: Boolean,
): CascadeRemovalSummary {
    if (alsoBlacklist) {
        val track = trackDao.getById(trackId) ?: return CascadeRemovalSummary.empty()
        blocklistGuard.block(track, BlockSource.PLAYLIST_DELETE)
        _trackDeletions.tryEmit(trackId)
        return CascadeRemovalSummary(deleted = 1, keptProtected = 0, keptElsewhere = 0, blacklisted = 1)
    }
    // ... existing protection-aware logic for the non-block case ...
}
```

- [ ] **Step 2: Update the snackbar message** in `PlaylistDetailViewModel.kt:200-208` to say "Blocked. Removed from all playlists."

- [ ] **Step 3: Test**

```kotlin
@Test
fun `removeTrackFromPlaylistAndMaybeDelete with alsoBlacklist=true blocks even when track is in protected playlist`() = runTest {
    coEvery { trackDao.getById(42L) } returns TrackEntity(id = 42L, ...)
    val summary = repo.removeTrackFromPlaylistAndMaybeDelete(
        trackId = 42L, fromPlaylistId = 1L, alsoBlacklist = true,
    )
    assertEquals(1, summary.deleted)
    assertEquals(1, summary.blacklisted)
    coVerify(exactly = 1) { blocklistGuard.block(any(), BlockSource.PLAYLIST_DELETE) }
}
```

- [ ] **Step 4: Commit**

---

### Task 3.3: BlockedSongsViewModel rebinds to BlocklistGuard

**Files:**
- Modify: `feature/settings/src/main/kotlin/com/stash/feature/settings/BlockedSongsViewModel.kt`
- Modify: the corresponding BlockedSongs Compose screen (find via `grep -rn "BlockedSongsViewModel" feature/`)

- [ ] **Step 1: Replace `musicRepository.getBlacklistedTracks()` with `blocklistGuard.observeBlocklist()`.**

- [ ] **Step 2: Replace `musicRepository.unblacklistTrack(trackId)` with `blocklistGuard.unblock(canonicalKey)`.**

- [ ] **Step 3: Update the UI to render `TrackBlocklistEntity` (artist, title, blockedAt, blockedFrom) instead of `TrackEntity`.**

> **Note: album art on the Blocked Songs list.** `TrackBlocklistEntity` does not carry `albumArtPath` — by design, since the row may have been blocked from a snapshot before any download happened. The current Blocked Songs UI uses `GlassCard` with track art on the row. After this rebind, rows render with a placeholder music-note icon instead. Acceptable: blocked tracks are intentionally context-poor (no playback, no detail). If we ever want art back, lazy-fetch via canonical key from a shared art cache. Not in scope for this plan.

- [ ] **Step 4: Test**

```kotlin
@Test
fun `viewModel emits blocklist entries from the guard`() = runTest {
    coEvery { blocklistGuard.observeBlocklist() } returns flowOf(
        listOf(TrackBlocklistEntity("arctic monkeys|505", "Arctic Monkeys", "505", null, null, 1700000000L, "NOW_PLAYING"))
    )
    val vm = BlockedSongsViewModel(blocklistGuard)
    vm.uiState.test {
        val state = awaitItem()
        assertEquals(1, state.entries.size)
        assertEquals("Arctic Monkeys", state.entries[0].artist)
    }
}

@Test
fun `unblock delegates to guard with the entry's canonical key`() = runTest {
    val vm = BlockedSongsViewModel(blocklistGuard)
    vm.unblock("arctic monkeys|505")
    coVerify(exactly = 1) { blocklistGuard.unblock("arctic monkeys|505") }
}
```

- [ ] **Step 5: Commit**

---

### Task 3.4: Drop is_blacklisted column (migration v19→v20)

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt` (bump to v20, add MIGRATION_19_20)
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/entity/TrackEntity.kt:115-116` (remove the field)
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt` (remove queries that reference the column)

- [ ] **Step 1: Generate the v20 schema first** (so the migration uses verbatim DDL, not hand-written SQL that drifts from Room's expectations)

```bash
# 1. In TrackEntity.kt: remove the @ColumnInfo(name = "is_blacklisted") field.
# 2. In StashDatabase.kt: bump version 19 -> 20 (without adding the migration yet).
# 3. Build to regenerate the schema:
./gradlew :core:data:assembleDebug
# 4. Open core/data/schemas/com.stash.core.data.db.StashDatabase/20.json
# 5. Find the "tracks" table block and copy the `createSql` value verbatim.
# 6. Note every index that appears in the "indices" array for the tracks table — each becomes a CREATE INDEX in the migration.
```

- [ ] **Step 2: Write MIGRATION_19_20** using the verbatim DDL from step 1

```kotlin
val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // SQLite ALTER TABLE DROP COLUMN was added in 3.35 (API 31+) and Room's
        // schema validator wants exact DDL match anyway, so use the recreate-
        // table dance. The CREATE TABLE statement below is COPIED VERBATIM
        // from core/data/schemas/.../20.json#tracks.createSql — do NOT
        // hand-edit it.

        // 1. Rename old table out of the way.
        db.execSQL("ALTER TABLE tracks RENAME TO tracks_v19")

        // 2. Create the new table with the v20 schema (verbatim from 20.json).
        db.execSQL("""<paste createSql from 20.json verbatim here>""")

        // 3. Re-create every index from 20.json (each entry in the "indices" array).
        //    Example shape: db.execSQL("CREATE INDEX `index_tracks_X` ON tracks (X)")
        //    Enumerate ALL of them — missing one will fail runMigrationsAndValidate.

        // 4. Copy data forward, enumerating EVERY column except is_blacklisted.
        //    DO NOT use SELECT * — it brings the dropped column along.
        db.execSQL("""
            INSERT INTO tracks (
                id, title, artist, album, source, source_id, spotify_uri, youtube_id,
                file_path, file_size_bytes, album_art_url, album_art_path, duration_ms,
                date_added, play_count, last_played_at, is_downloaded, canonical_title,
                canonical_artist, match_dismissed, codec, sample_rate_hz, bits_per_sample,
                bitrate_kbps, match_flagged, spotify_saved_at, ytmusic_saved_at, stash_liked_at
                /* enumerate every column from v19 EXCEPT is_blacklisted */
            )
            SELECT
                id, title, artist, album, source, source_id, spotify_uri, youtube_id,
                file_path, file_size_bytes, album_art_url, album_art_path, duration_ms,
                date_added, play_count, last_played_at, is_downloaded, canonical_title,
                canonical_artist, match_dismissed, codec, sample_rate_hz, bits_per_sample,
                bitrate_kbps, match_flagged, spotify_saved_at, ytmusic_saved_at, stash_liked_at
            FROM tracks_v19
        """.trimIndent())

        // 5. Drop the old table.
        db.execSQL("DROP TABLE tracks_v19")
    }
}
```

> **About foreign keys:** `playlist_tracks.track_id` and `download_queue.track_id` reference `tracks.id`. Verify in the v19 schema JSON whether they declare `ON DELETE CASCADE` or are non-FK references. If they are real FKs with cascade, the rename + drop sequence above will trigger cascading deletes and wipe `playlist_tracks` / `download_queue` entirely. If that's the case, change the migration to (a) wrap in `db.beginTransaction()` (b) use `PRAGMA foreign_keys=OFF` for the duration. Verify before writing.

- [ ] **Step 3: Remove `isBlacklisted` from TrackEntity.** (Already done in Step 1's schema generation.)

- [ ] **Step 4: Remove `getBlacklistedTracks`, `getBlacklistedCount`, `updateBlacklisted`, `markBlacklistedAndClear` from TrackDao** — all unused now.

- [ ] **Step 5: Rename `getAllDownloadedNonBlacklisted` → `getAllDownloaded`** and drop the `is_blacklisted = 0` clause from its SQL. Update call sites accordingly (the explorer agent's leak map identified them).

- [ ] **Step 6: Add MIGRATION_19_20 to the Room builder's `addMigrations(...)` chain.**

- [ ] **Step 7: Write a migration test**

```kotlin
// MigrationV19V20Test.kt
@Test
fun `migrate from v19 to v20 drops is_blacklisted column and preserves data`() {
    helper.createDatabase(DB_NAME, 19).use { db ->
        // Insert a track with is_blacklisted = 0 and a track with is_blacklisted = 1.
        // (After Phase 3, the latter shouldn't exist — but the migration must
        //  handle stale data left over from devices that didn't run integrity worker.)
    }
    val db = helper.runMigrationsAndValidate(DB_NAME, 20, true, StashDatabase.MIGRATION_19_20)
    val cursor = db.query("PRAGMA table_info(tracks)")
    val columns = cursor.use {
        val names = mutableListOf<String>()
        while (it.moveToNext()) names += it.getString(1)
        names
    }
    assertFalse("is_blacklisted column should be dropped", columns.contains("is_blacklisted"))
    val count = db.query("SELECT COUNT(*) FROM tracks").use { it.moveToFirst(); it.getInt(0) }
    assertEquals(2, count)
}
```

- [ ] **Step 8: Run migration test, expect PASS. Run full `./gradlew :core:data:test` to make sure no DAO test references the dropped column.**

- [ ] **Step 9: Commit**

---

## Phase 4 — Integrity worker

### Task 4.1: BlocklistIntegrityWorker

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/sync/workers/BlocklistIntegrityWorker.kt`
- Modify: `app/src/main/kotlin/com/stash/app/StashApplication.kt` (enqueue once on cold start)

- [ ] **Step 1: Implement the worker**

> **Critical: key consistency.** The migration v18→v19 derives the canonical key as `canonical_artist || '|' || canonical_title` directly from the stored columns (SQL string concat). The runtime guard derives it via `BlocklistKey.of(...)` which calls `TrackMatcher.canonicalArtist/canonicalTitle` again. If the stored canonical fields were generated by an older `TrackMatcher` version, these two derivations will silently disagree and the integrity worker will fail to find the leaked rows. To stay consistent, this worker MUST use the SQL-style concatenation (`"${t.canonicalArtist}|${t.canonicalTitle}"`) — NOT `BlocklistKey.of(...)`. The `block()` call inside the worker also uses the stored canonical fields, since it derives the key from `track.artist`/`track.title` via `BlocklistKey.of`. To prevent a key-derivation skew, the worker compares using stored-canonical concatenation, then trusts `block()` to derive the new key the same way the migration did — meaning we need a small helper that the migration AND `block()` share. See Step 1b.

- [ ] **Step 1a: Add a `keyFromStoredCanonicals` helper to `BlocklistKey`**

```kotlin
// BlocklistKey.kt — add alongside `of(...)`:
/**
 * Derives a key from a [TrackEntity]'s ALREADY-NORMALISED canonical_artist
 * and canonical_title columns. Use this when the canonical fields are
 * already in the row (e.g., migration, integrity worker). Use [of] when
 * you have raw artist/title strings that need normalising.
 *
 * The migration uses SQL string concat over the same fields, so this
 * helper produces an identical key — important for integrity-worker
 * cleanup of pre-migration leaked rows.
 */
fun fromStoredCanonicals(canonicalArtist: String, canonicalTitle: String): String =
    "$canonicalArtist|$canonicalTitle"
```

- [ ] **Step 1b: Implement the worker using the stored-canonical helper**

```kotlin
/**
 * v0.9.15: One-shot cleanup for v0.9.13/14-era leaked tracks. Walks every
 * `tracks` row whose stored-canonical identity matches a `track_blocklist`
 * row, deletes the file + tears down playlist_tracks + deletes the row.
 *
 * Why we don't just call [BlocklistGuard.block]: that method derives the
 * NEW blocklist row's key by running `TrackMatcher.canonicalArtist/Title`
 * over the raw artist/title — but the existing blocklist row in the DB
 * was keyed by the SQL migration using stored canonical_artist /
 * canonical_title. If the stored canonicals were generated by an older
 * matcher, the two keys disagree and the worker would insert a duplicate
 * row instead of being a no-op. So the worker uses
 * [BlocklistKey.fromStoredCanonicals] for the lookup, and skips the
 * [BlocklistGuard.block] insert step entirely (the row is already there).
 *
 * Schedules itself with [ExistingWorkPolicy.KEEP] so it runs exactly once
 * per install + upgrade.
 */
@HiltWorker
class BlocklistIntegrityWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val database: StashDatabase,
    private val trackDao: TrackDao,
    private val blocklistDao: TrackBlocklistDao,
    private val playlistDao: PlaylistDao,
    private val downloadQueueDao: DownloadQueueDao,
    private val fileDeleter: FileDeleter,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val keys = blocklistDao.getAllKeys().toSet()
        if (keys.isEmpty()) return Result.success()

        var cleaned = 0
        // Snapshot iterate; we mutate the table.
        val candidates = trackDao.getAllForIntegrityScan()
        for (t in candidates) {
            val key = BlocklistKey.fromStoredCanonicals(t.canonicalArtist, t.canonicalTitle)
            if (key in keys) {
                fileDeleter.delete(t.filePath)
                fileDeleter.delete(t.albumArtPath)
                database.withTransaction {
                    playlistDao.deleteAllCrossRefsForTrack(t.id)
                    downloadQueueDao.deleteByTrackId(t.id)
                    trackDao.deleteById(t.id)
                }
                cleaned++
            }
        }
        Log.i(TAG, "BlocklistIntegrityWorker cleaned $cleaned leaked tracks")
        return Result.success()
    }
    companion object { private const val TAG = "BlocklistIntegrity" }
}
```

- [ ] **Step 2: Add the helper query**

`TrackDao.kt`:
```kotlin
@Query("SELECT * FROM tracks")
suspend fun getAllForIntegrityScan(): List<TrackEntity>
```

- [ ] **Step 3: Enqueue from StashApplication.onCreate**

```kotlin
WorkManager.getInstance(this).enqueueUniqueWork(
    "blocklist_integrity_v1",
    ExistingWorkPolicy.KEEP,
    OneTimeWorkRequestBuilder<BlocklistIntegrityWorker>().build(),
)
```

- [ ] **Step 4: Test**

```kotlin
// BlocklistIntegrityWorkerTest.kt
@Test
fun `worker is a no-op when blocklist is empty`() = runTest {
    coEvery { blocklistDao.getAllKeys() } returns emptyList()
    val result = worker.doWork()
    assertEquals(Result.success(), result)
    coVerify(exactly = 0) { trackDao.getAllForIntegrityScan() }
}

@Test
fun `worker cleans leaked tracks whose stored-canonical key matches a blocklist row`() = runTest {
    coEvery { blocklistDao.getAllKeys() } returns listOf("arctic monkeys|505")
    val leaked = TrackEntity(
        id = 42L, artist = "Arctic Monkeys", title = "505",
        canonicalArtist = "arctic monkeys", canonicalTitle = "505",
        filePath = "/sdcard/505.flac", albumArtPath = "/sdcard/505.jpg", ...,
    )
    val safe = TrackEntity(
        id = 43L, artist = "Arctic Monkeys", title = "Brianstorm",
        canonicalArtist = "arctic monkeys", canonicalTitle = "brianstorm", ...,
    )
    coEvery { trackDao.getAllForIntegrityScan() } returns listOf(leaked, safe)

    worker.doWork()

    coVerify(exactly = 1) { fileDeleter.delete("/sdcard/505.flac") }
    coVerify(exactly = 1) { fileDeleter.delete("/sdcard/505.jpg") }
    coVerify(exactly = 1) { trackDao.deleteById(42L) }
    coVerify(exactly = 0) { trackDao.deleteById(43L) }
}

@Test
fun `worker is idempotent when called twice`() = runTest {
    coEvery { blocklistDao.getAllKeys() } returns listOf("arctic monkeys|505")
    coEvery { trackDao.getAllForIntegrityScan() } returnsMany listOf(
        listOf(TrackEntity(id = 42L, canonicalArtist = "arctic monkeys", canonicalTitle = "505", ...)),
        emptyList(),  // second call: row already cleaned
    )
    worker.doWork()
    worker.doWork()
    coVerify(exactly = 1) { trackDao.deleteById(42L) }
}
```

- [ ] **Step 5: Commit**

---

## Phase 5 — Acceptance + ship

### Task 5.1: Version bump + release

- [ ] **Step 1: Bump versionCode + versionName**

`app/build.gradle.kts:75-76`:
```kotlin
versionCode = 53
versionName = "0.9.15"
```

- [ ] **Step 2: Full clean build + on-device acceptance**

```bash
./gradlew clean :app:installDebug
```

On-device test sequence:
1. Block 505 by Arctic Monkeys from Now Playing.
2. Verify it appears in Settings → Blocked Songs.
3. Force-stop the app, restart, trigger sync. 505 must NOT reappear in any playlist or mix.
4. Re-like 505 on Spotify (server-side). Trigger sync. 505 must NOT reappear in playlists, mixes, or library list views.
5. Search for "505 arctic monkeys" in Search tab. The search result list may still show it (the Search tab queries YouTube directly, not the local DB) — but tapping download silently no-ops because `SearchDownloadCoordinator.upsertSearchTrack` returns null. Confirm no `tracks` row, no file, no playlist link is created. (A user-facing "blocked" toast in the Search tab is OUT OF SCOPE for this plan — track separately.)
6. Unblock 505 from Settings. Block list is empty. Re-like on Spotify. Sync. 505 should now reappear normally.

- [ ] **Step 3: Commit version bump, tag v0.9.15, push, gh release**

```bash
git commit -am "chore: bump versionCode 52->53, versionName 0.9.14->0.9.15"
git tag -a v0.9.15 -m "v0.9.15 - Blocklist redesign"
git push origin HEAD       # whatever the current branch is named (was feat/blocklist-redesign at plan-write time)
git push origin v0.9.15
```

(GitHub Action auto-creates the release with APK on tag push.)

---

## Test plan summary

| Layer | Tests |
|---|---|
| Unit (canonical key) | BlocklistKeyTest — 4 cases |
| Unit (guard) | BlocklistGuardTest — 4 cases (isBlocked match, isBlocked no-match, atomic block, unblock) |
| Migration | MigrationV18V19Test — 1 case (seed from is_blacklisted=1) + MigrationV19V20Test — 1 case (column drop) |
| Worker integration | TrackDownloadWorkerBlocklistTest, DiffWorkerBlocklistTest, MixRefreshBlocklistTest, DiscoveryBlocklistTest — 1 case each |
| On-device acceptance | Manual 6-step sequence in Task 5.1 Step 2 |

## Roll-back plan

Each phase is reversible:
- **Phase 1 only ships:** no behavior change, fully backward-compatible.
- **Phase 2 only ships:** revert by removing guard calls; the blocklist table stays as a no-op.
- **Phase 3 only ships:** can't re-introduce `is_blacklisted` without another migration, so this phase is a one-way door — only ship after Phase 2 acceptance passes.
- **Phase 4 only ships:** disable by removing the `enqueueUniqueWork` call.

---

## Out of scope (deferred)

- A "Block by artist" or "Block by album" feature. The current design is per-track-identity. A future enhancement could add wildcard blocklist rows.
- Restoring blocked tracks' play history. `listening_events` rows reference track id — once the row is deleted, those rows orphan. Acceptable; play history isn't a documented feature surface.
- Cross-device blocklist sync. The blocklist is local-only; no Stash backend.
