# v0.9.35 Metadata + Album-Art Embedding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **Every subagent dispatch must use `model: "opus"`** (project convention).

**Goal:** Embed clean TITLE / ARTIST / ALBUMARTIST / ALBUM / ISRC / ENCODER tags plus cover-art picture into every downloaded audio file (yt-dlp + lossless paths), and one-shot backfill the existing library so files opened in Plex / Foobar / Symfonium / car USB no longer render blank. Closes [#76](https://github.com/rawnaldclark/Stash/issues/76) part 1 and [#90](https://github.com/rawnaldclark/Stash/issues/90).

**Architecture:** Both download paths converge on `MetadataEmbedder.embedMetadata(file, track, art)`. A new `AlbumArtCache` lazily fetches the cover JPEG once per album into `cacheDir/albumart/`. A new `tracks.metadata_embedded_at` Room column (v26 → v27 migration) provides idempotency. `MetadataBackfillWorker` drains all `metadata_embedded_at IS NULL` rows once per binary version, driven by `MetadataBackfillScheduler` from `StashApplication.onCreate`. A new independent banner field on `HomeUiState` surfaces progress, mirroring the existing `WaitingForLosslessBannerState` shape.

**Tech Stack:** Kotlin / Hilt / Room / WorkManager / OkHttp / DataStore (Preferences) / Jetpack Compose / ffmpeg (bundled via youtubedl-android). Tests use JUnit4 + mockk + kotlinx-coroutines-test + Robolectric (for DataStore tests).

**Spec:** [docs/superpowers/specs/2026-05-23-metadata-embedding-design.md](../specs/2026-05-23-metadata-embedding-design.md)

---

## File Map

### Created

- `core/common/src/main/kotlin/com/stash/core/common/AppVersionProvider.kt` — interface
- `app/src/main/kotlin/com/stash/app/di/AppVersionModule.kt` — Hilt `@Module` providing `AppVersionProvider` from `BuildConfig`
- `core/model/src/test/kotlin/com/stash/core/model/TrackMetadataFieldsTest.kt` — pure unit tests for the new Track fields
- `data/download/src/main/kotlin/com/stash/data/download/files/AlbumArtCache.kt` — fetches + caches cover JPEGs
- `data/download/src/test/kotlin/com/stash/data/download/files/AlbumArtCacheTest.kt`
- `data/download/src/test/kotlin/com/stash/data/download/files/MetadataEmbedderArgsTest.kt` — argv construction tests (factor out the buildList into a testable helper)
- `data/download/src/main/kotlin/com/stash/data/download/backfill/MetadataBackfillState.kt` — DataStore-backed observable
- `data/download/src/main/kotlin/com/stash/data/download/backfill/BackfillVersionTracker.kt` — version-gated trigger gate
- `data/download/src/main/kotlin/com/stash/data/download/backfill/di/BackfillModule.kt` — Hilt module for the shared DataStore + bindings
- `data/download/src/main/kotlin/com/stash/data/download/backfill/MetadataBackfillWorker.kt` — `@HiltWorker`
- `data/download/src/main/kotlin/com/stash/data/download/backfill/MetadataBackfillScheduler.kt` — enqueues the worker
- `data/download/src/test/kotlin/com/stash/data/download/backfill/MetadataBackfillStateTest.kt`
- `data/download/src/test/kotlin/com/stash/data/download/backfill/BackfillVersionTrackerTest.kt`
- `data/download/src/test/kotlin/com/stash/data/download/backfill/MetadataBackfillWorkerTest.kt`
- `feature/home/src/main/kotlin/com/stash/feature/home/banner/MetadataBackfillBannerState.kt` — sealed type + pure mapper
- `feature/home/src/test/kotlin/com/stash/feature/home/banner/MetadataBackfillBannerStateTest.kt`
- `feature/home/src/main/kotlin/com/stash/feature/home/banner/MetadataBackfillBanner.kt` — composable
- `data/download/src/androidTest/kotlin/com/stash/data/download/MetadataEmbeddingIntegrationTest.kt` — on-device ffmpeg round-trip
- `data/download/src/androidTest/assets/sample_opus.opus`, `sample_m4a.m4a`, `sample_flac.flac`, `sample_art.jpg` — minimal test fixtures
- `core/data/schemas/com.stash.core.data.db.StashDatabase/27.json` — auto-generated after Room compile

### Modified

- `core/model/src/main/kotlin/com/stash/core/model/Track.kt` — add `albumArtist: String = ""` and `metadataEmbeddedAt: Long? = null`
- `core/data/src/main/kotlin/com/stash/core/data/db/entity/TrackEntity.kt` — add `metadataEmbeddedAt` column
- `core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt` — bump `version = 26` → `27`, add `MIGRATION_26_27`
- `core/data/src/main/kotlin/com/stash/core/data/mapper/TrackMapper.kt` — propagate both new fields in both directions
- `core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt` — add `setMetadataEmbeddedAt`, `getTracksNeedingEmbed`, `observeTracksNeedingEmbedCount`
- `data/download/src/main/kotlin/com/stash/data/download/files/MetadataEmbedder.kt` — expanded tag set, accept albumArtist, inject `AppVersionProvider`
- `data/download/src/main/kotlin/com/stash/data/download/shared/TrackFinalizer.kt` — inject `AlbumArtCache`, pass art through, stamp embed timestamp (delegated back to caller)
- `data/download/src/main/kotlin/com/stash/data/download/DownloadManager.kt` — yt-dlp path embed call + post-success `setMetadataEmbeddedAt`; same stamp on lossless path
- `data/download/src/main/kotlin/com/stash/data/download/files/LocalImportCoordinator.kt` — stamp `setMetadataEmbeddedAt = now` on import
- `data/download/src/main/kotlin/com/stash/data/download/prefs/QualityPreferencesManager.kt` — update the KDoc on `toYtDlpArgs` to note that the `--embed-metadata` flag is now a fallback, not the primary tag source
- `data/download/src/main/kotlin/com/stash/data/download/files/FileOrganizer.kt` — no behavioural change; `getAlbumArtDir()` and `getAlbumArtFile(albumId)` are already exposed and used by the new cache
- `feature/home/src/main/kotlin/com/stash/feature/home/HomeUiState.kt` — add `metadataBackfillBanner: MetadataBackfillBannerState = Hidden`
- `feature/home/src/main/kotlin/com/stash/feature/home/HomeViewModel.kt` — combine `MetadataBackfillState.snapshot` into the existing assembly
- `feature/home/src/main/kotlin/com/stash/feature/home/HomeScreen.kt` — render the new banner under `WaitingForLosslessBanner`
- `app/src/main/kotlin/com/stash/app/StashApplication.kt` — call `MetadataBackfillScheduler.scheduleIfNeeded()` from `onCreate` after the Hilt graph is up
- `core/data/src/main/kotlin/com/stash/core/data/di/DatabaseModule.kt` — register `StashDatabase.MIGRATION_26_27` in the `addMigrations(...)` chain
- `app/build.gradle.kts` — bump `versionCode` and `versionName` to 0.9.35

---

## Conventions

- **TDD throughout.** Failing test → run it → minimal implementation → run it → commit. Watch the failure for the right reason; don't skip that step.
- **One commit per task** unless the task explicitly says otherwise. Commit messages follow the project's `type(scope): summary` style and include the trailer:
  ```
  Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
  ```
- **Every subagent dispatch uses `model: "opus"`.** Project memory `feedback_subagent_model` is unambiguous about this.
- **`:app:installDebug` after every UI-touching task.** Per `feedback_install_after_fix`, compile-pass alone is not enough on this project.
- **"Ship" = tag + push, not local install.** Per `feedback_ship_terminology`. This plan stops one step short of shipping — the user gates the final tag.
- **Worktree-local `local.properties`.** `git worktree add` does not copy it; copy manually before the first Gradle build or Last.fm shows "Not configured" in debug builds.
- **No scope creep.** This plan does NOT add custom filename patterns (separate issue), does NOT add trackNumber/discNumber/year columns to Track (separate issue), does NOT touch the lossless captcha cookie path, and does NOT migrate SAF-located files (skipped + surfaced via banner).
- **Pre-existing master test failures** noted in prior plans still apply — ignore them in the test sweep at Task 15.

---

## Worktree setup (do this once, before Task 1)

- [ ] **Step 1: Create the worktree from current master**

```bash
cd /c/Users/theno/Projects/MP3APK
git fetch origin
git worktree add .worktrees/metadata-embedding -b feat/metadata-embedding origin/master
```

After fetch, `origin/master` should include the spec commits (`34cfb7c`, `549692b`, `d93e2e8`, `4307b3f`) since those landed during brainstorming. Verify the worktree HEAD with `git -C .worktrees/metadata-embedding log --oneline -1` — the topmost commit should be the round-3 spec advisory commit (`4307b3f`).

- [ ] **Step 2: Copy `local.properties` into the worktree**

```bash
cp local.properties .worktrees/metadata-embedding/local.properties
```

- [ ] **Step 3: cd into the worktree**

All subsequent paths in this plan are relative to the worktree root.

```bash
cd .worktrees/metadata-embedding
```

---

# Phase 1 — Foundation (AppVersionProvider + Track domain fields)

Phase end state: `core/common` exposes `AppVersionProvider`, `core/model/Track` carries `albumArtist` and `metadataEmbeddedAt`, all module unit tests still green. No behaviour change yet — these tasks just give Phase 2-7 the vocabulary they need.

## Task 1: `AppVersionProvider` interface + Hilt impl

**Why this is small:** Pure scaffolding. No tests beyond a compile-check; the value is "every consumer further down the plan can inject the interface."

**Files:**
- Create: `core/common/src/main/kotlin/com/stash/core/common/AppVersionProvider.kt`
- Create: `app/src/main/kotlin/com/stash/app/di/AppVersionModule.kt`

- [ ] **Step 1: Verify the existing pattern**

Sanity-check that the precedent referenced in the spec actually exists, so the new interface lines up with it:

```bash
grep -n "versionCodeProvider" core/data/src/main/kotlin/com/stash/core/data/youtube/YouTubeHistoryScrobbler.kt
```

Expected: a constructor parameter `versionCodeProvider: () -> Int` at line ~146. If absent, the project layout has drifted; surface to the user before proceeding.

- [ ] **Step 2: Create the interface**

`core/common/src/main/kotlin/com/stash/core/common/AppVersionProvider.kt`:

```kotlin
package com.stash.core.common

/**
 * Read-only accessor for the binary's identity. Lives in `:core:common`
 * so modules that can't depend on `:app` (and therefore can't see
 * the generated `BuildConfig`) — like `:data:download` — can still
 * read the version name + code via Hilt injection.
 *
 * Generalises the existing `versionCodeProvider: () -> Int` lambda
 * pattern from `YouTubeHistoryScrobbler` to also surface the version
 * name string used in tag-writing.
 */
interface AppVersionProvider {
    /** Human-readable version, e.g. `"0.9.35"`. Matches `app/build.gradle.kts` versionName. */
    val versionName: String

    /** Monotonically-increasing integer version. Matches `app/build.gradle.kts` versionCode. */
    val versionCode: Int
}
```

- [ ] **Step 3: Create the Hilt module in `:app`**

`app/src/main/kotlin/com/stash/app/di/AppVersionModule.kt`:

```kotlin
package com.stash.app.di

import com.stash.app.BuildConfig
import com.stash.core.common.AppVersionProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppVersionModule {

    @Provides
    @Singleton
    fun provideAppVersionProvider(): AppVersionProvider = object : AppVersionProvider {
        override val versionName: String = BuildConfig.VERSION_NAME
        override val versionCode: Int = BuildConfig.VERSION_CODE
    }
}
```

- [ ] **Step 4: Verify the build still compiles**

```bash
./gradlew :app:compileDebugKotlin :core:common:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add core/common/src/main/kotlin/com/stash/core/common/AppVersionProvider.kt \
        app/src/main/kotlin/com/stash/app/di/AppVersionModule.kt
git commit -m "$(cat <<'EOF'
feat(common): AppVersionProvider for cross-module version reads

Scaffolding for v0.9.35 metadata embedding. :data:download cannot
read BuildConfig directly (no buildFeatures.buildConfig flag), so
the embedder + the backfill version tracker inject this interface
instead. Hilt binding lives in :app where BuildConfig is generated.
Generalises the existing versionCodeProvider lambda pattern.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Track domain model — `albumArtist` + `metadataEmbeddedAt`

**Files:**
- Modify: `core/model/src/main/kotlin/com/stash/core/model/Track.kt`
- Test: `core/model/src/test/kotlin/com/stash/core/model/TrackMetadataFieldsTest.kt`

- [ ] **Step 1: Write the failing test**

`core/model/src/test/kotlin/com/stash/core/model/TrackMetadataFieldsTest.kt`:

```kotlin
package com.stash.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackMetadataFieldsTest {

    @Test fun `albumArtist defaults to empty string`() {
        val track = stubTrack()
        assertEquals("", track.albumArtist)
    }

    @Test fun `albumArtist preserves explicit value`() {
        val track = stubTrack(albumArtist = "Drake")
        assertEquals("Drake", track.albumArtist)
    }

    @Test fun `metadataEmbeddedAt defaults to null`() {
        val track = stubTrack()
        assertNull(track.metadataEmbeddedAt)
    }

    @Test fun `metadataEmbeddedAt preserves explicit value`() {
        val track = stubTrack(metadataEmbeddedAt = 1716000000000L)
        assertEquals(1716000000000L, track.metadataEmbeddedAt)
    }

    private fun stubTrack(
        albumArtist: String = "",
        metadataEmbeddedAt: Long? = null,
    ) = Track(
        id = 0L,
        title = "",
        artist = "",
        albumArtist = albumArtist,
        metadataEmbeddedAt = metadataEmbeddedAt,
    )
}
```

- [ ] **Step 2: Verify it fails**

```bash
./gradlew :core:model:test --tests "*TrackMetadataFieldsTest*"
```

Expected: FAIL with `Unresolved reference: albumArtist` / `Unresolved reference: metadataEmbeddedAt`.

- [ ] **Step 3: Add the fields to `Track`**

Edit `core/model/src/main/kotlin/com/stash/core/model/Track.kt`. Append to the constructor parameter list (between `streamOrigin` and the closing paren):

```kotlin
    /**
     * Album-level artist for grouping multi-artist releases in Plex /
     * Foobar / Symfonium. Distinct from [artist] (the track-level
     * credit) — a feature credit may say `artist = "Drake, 21 Savage"`
     * while `albumArtist = "Drake"`. Empty string when unknown.
     * Mirror of `TrackEntity.album_artist`, added v0.9.26 but only
     * surfaced through the domain layer in v0.9.35.
     */
    val albumArtist: String = "",
    /**
     * Epoch-millis of the most recent successful tag + art embedding
     * pass. NULL = never tagged (legacy v0.9.34 rows, or rows whose
     * embed step failed); 0L = backfill tried and failed
     * unrecoverably (file missing, ffmpeg error). Non-null non-zero
     * = the file on disk carries the v0.9.35 tag set.
     */
    val metadataEmbeddedAt: Long? = null,
```

- [ ] **Step 4: Verify the test passes**

```bash
./gradlew :core:model:test --tests "*TrackMetadataFieldsTest*"
```

Expected: PASS.

- [ ] **Step 5: Run all `:core:model` tests to confirm no regressions**

```bash
./gradlew :core:model:test
```

Expected: PASS (or fail only on pre-existing failures called out in the conventions section).

- [ ] **Step 6: Commit**

```bash
git add core/model/src/main/kotlin/com/stash/core/model/Track.kt \
        core/model/src/test/kotlin/com/stash/core/model/TrackMetadataFieldsTest.kt
git commit -m "$(cat <<'EOF'
feat(model): surface albumArtist + add metadataEmbeddedAt on Track

Two new fields on the domain Track. albumArtist mirrors a column
that has lived on TrackEntity since v0.9.26 but never made it past
the mapper — needed so MetadataEmbedder can write the Plex-friendly
ALBUMARTIST tag without falling back to track.artist for every row.
metadataEmbeddedAt is the v0.9.35 idempotency timestamp: the
backfill worker queries WHERE this IS NULL.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Phase 2 — Persistence (TrackEntity column + Room v26 → v27 migration)

Phase end state: schema bumped to 27, `tracks.metadata_embedded_at` column exists, `TrackDao` exposes the new queries, mapper propagates both directions, migration test green.

## Task 3: TrackEntity column + migration + DAO + mapper

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/entity/TrackEntity.kt`
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt`
- Modify: `core/data/src/main/kotlin/com/stash/core/data/di/DatabaseModule.kt` — **register the new migration here, not on StashDatabase**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt`
- Modify: `core/data/src/main/kotlin/com/stash/core/data/mapper/TrackMapper.kt`
- Test: `core/data/src/androidTest/kotlin/com/stash/core/data/db/Migration26To27Test.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/mapper/TrackMapperMetadataFieldsTest.kt`

- [ ] **Step 1: Find the right insertion points**

```bash
grep -n "metadata_embedded_at\|val metadataEmbeddedAt\|@ColumnInfo" core/data/src/main/kotlin/com/stash/core/data/db/entity/TrackEntity.kt
grep -n "version = 26\|MIGRATION_25_26" core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt
grep -n "album_artist\|albumArtPath" core/data/src/main/kotlin/com/stash/core/data/mapper/TrackMapper.kt
```

Note where existing `@ColumnInfo` lines sit on the entity, where the `MIGRATION_25_26` (or whatever the latest is) lives, and how the mapper currently lists fields.

- [ ] **Step 2: Write the failing mapper test**

`core/data/src/test/kotlin/com/stash/core/data/mapper/TrackMapperMetadataFieldsTest.kt`:

```kotlin
package com.stash.core.data.mapper

import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackMapperMetadataFieldsTest {

    @Test fun `entity to domain propagates albumArtist + metadataEmbeddedAt`() {
        val entity = stubEntity(
            albumArtist = "Drake",
            metadataEmbeddedAt = 1716000000000L,
        )
        val track = entity.toDomain()
        assertEquals("Drake", track.albumArtist)
        assertEquals(1716000000000L, track.metadataEmbeddedAt)
    }

    @Test fun `domain to entity propagates albumArtist + metadataEmbeddedAt`() {
        val track = Track(
            id = 1, title = "", artist = "",
            albumArtist = "Drake",
            metadataEmbeddedAt = 1716000000000L,
        )
        val entity = track.toEntity()
        assertEquals("Drake", entity.albumArtist)
        assertEquals(1716000000000L, entity.metadataEmbeddedAt)
    }

    @Test fun `metadataEmbeddedAt null round-trips as null`() {
        val entity = stubEntity(metadataEmbeddedAt = null)
        assertNull(entity.toDomain().metadataEmbeddedAt)
    }

    private fun stubEntity(
        albumArtist: String = "",
        metadataEmbeddedAt: Long? = null,
    ) = TrackEntity(
        id = 1L, title = "", artist = "",
        albumArtist = albumArtist,
        metadataEmbeddedAt = metadataEmbeddedAt,
        // TrackEntity has ~30 fields. Search core/data/src/test first
        // for an existing factory (`TrackEntityFactory`,
        // `stubTrackEntity`, or similar in any *Test.kt file) — most
        // fields have @ColumnInfo defaultValues and Kotlin defaults so
        // a minimal constructor call usually compiles. If you get
        // "missing arg <fieldName>", read TrackEntity.kt line-by-line
        // and supply empty/zero/false for every remaining required
        // field. Do NOT add new defaults to TrackEntity itself just to
        // shrink the call site — that would silently change schema
        // generation.
    )
}
```

If the project already has a `TrackEntityFactory` or similar test helper, use it instead of hand-rolling `stubEntity`.

- [ ] **Step 3: Verify the test fails**

```bash
./gradlew :core:data:testDebugUnitTest --tests "*TrackMapperMetadataFieldsTest*"
```

Expected: FAIL with `Unresolved reference: metadataEmbeddedAt` on TrackEntity.

- [ ] **Step 4: Add the entity column**

Open `core/data/src/main/kotlin/com/stash/core/data/db/entity/TrackEntity.kt`. After the existing `albumArtist` field (line ~58-59), add:

```kotlin
    /**
     * v0.9.35: epoch-millis of the most recent successful tag + art
     * embedding pass on the file at [filePath]. NULL = never tagged
     * (legacy v0.9.34 rows + any pre-v0.9.35 download). 0L = backfill
     * tried and gave up irrecoverably (file missing on disk, ffmpeg
     * error, SAF row we can't operate on in place). Non-null non-zero
     * = the on-disk file carries the v0.9.35 tag set.
     *
     * The backfill worker queries `WHERE is_downloaded = 1 AND
     * file_path IS NOT NULL AND metadata_embedded_at IS NULL`. Both
     * success and failure stamps remove the row from the result set
     * so the worker terminates.
     */
    @ColumnInfo(name = "metadata_embedded_at")
    val metadataEmbeddedAt: Long? = null,
```

- [ ] **Step 5: Bump the database version + define the migration**

Open `core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt`.

Change `version = 26` to `version = 27`.

Find the `companion object` where the existing migrations are defined (`MIGRATION_3_4`, `MIGRATION_4_5`, … `MIGRATION_25_26`) and add the new migration:

```kotlin
val MIGRATION_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tracks ADD COLUMN metadata_embedded_at INTEGER")
    }
}
```

- [ ] **Step 5b: Register the migration in `DatabaseModule.kt`**

The migrations are **defined** on `StashDatabase` but **registered** in `core/data/src/main/kotlin/com/stash/core/data/di/DatabaseModule.kt` via the `.addMigrations(...)` call on the `Room.databaseBuilder`. Without this registration the app crashes on first launch (the module deliberately does NOT call `fallbackToDestructiveMigration`).

Find the `.addMigrations(...)` chain and append:

```kotlin
                StashDatabase.MIGRATION_26_27,
```

immediately after the last existing `StashDatabase.MIGRATION_25_26,` entry, preserving the trailing-comma + indentation style.

- [ ] **Step 6: Update the mapper in both directions**

Open `core/data/src/main/kotlin/com/stash/core/data/mapper/TrackMapper.kt`.

Find the existing `albumArtPath = albumArtPath,` line in `TrackEntity.toDomain()` and add immediately after:

```kotlin
    albumArtist = albumArtist,
    metadataEmbeddedAt = metadataEmbeddedAt,
```

Do the same in `Track.toEntity()`. The mapper is a simple field-by-field copy; mirror the order.

- [ ] **Step 7: Verify the mapper test passes**

```bash
./gradlew :core:data:testDebugUnitTest --tests "*TrackMapperMetadataFieldsTest*"
```

Expected: PASS.

- [ ] **Step 8: Add the new DAO methods**

Open `core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt`. Add three methods alongside the existing queries:

```kotlin
@Query("UPDATE tracks SET metadata_embedded_at = :timestamp WHERE id = :trackId")
suspend fun setMetadataEmbeddedAt(trackId: Long, timestamp: Long)

@Query(
    """
    SELECT * FROM tracks
    WHERE is_downloaded = 1
      AND file_path IS NOT NULL
      AND metadata_embedded_at IS NULL
    ORDER BY id ASC
    LIMIT :limit OFFSET :offset
    """
)
suspend fun getTracksNeedingEmbed(limit: Int, offset: Int): List<TrackEntity>

@Query(
    """
    SELECT COUNT(*) FROM tracks
    WHERE is_downloaded = 1
      AND file_path IS NOT NULL
      AND metadata_embedded_at IS NULL
    """
)
fun observeTracksNeedingEmbedCount(): Flow<Int>
```

- [ ] **Step 9: Write the migration test**

`core/data/src/androidTest/kotlin/com/stash/core/data/db/Migration26To27Test.kt`:

```kotlin
package com.stash.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration26To27Test {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
    )

    @Test fun migrate26To27_addsMetadataEmbeddedAtColumn() {
        helper.createDatabase(TEST_DB, 26).apply {
            execSQL(
                """
                INSERT INTO tracks (id, title, artist, album, album_artist, duration_ms,
                    is_downloaded, file_path)
                VALUES (1, 'Title', 'Artist', '', '', 0, 1, '/tmp/x.opus')
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 27, true, MIGRATION_26_27)
        val cursor = db.query("SELECT metadata_embedded_at FROM tracks WHERE id = 1")
        assertTrue(cursor.moveToFirst())
        assertTrue("metadata_embedded_at should default to NULL", cursor.isNull(0))
        cursor.close()
    }

    companion object {
        private const val TEST_DB = "migration-test"
    }
}
```

Adapt the `tracks` schema columns in the INSERT statement to match the actual v26 schema (read `core/data/schemas/com.stash.core.data.db.StashDatabase/26.json` to see the exact column list — fill in NOT-NULL fields with empty/zero defaults).

- [ ] **Step 10: Verify the migration test passes**

```bash
./gradlew :core:data:connectedAndroidTest --tests "*Migration26To27Test*"
```

This requires a connected device or emulator. Expected: PASS.

If no device is currently connected, document the failure and surface it — the migration test cannot be skipped.

- [ ] **Step 11: Regenerate the v27 schema export**

Room will generate `core/data/schemas/com.stash.core.data.db.StashDatabase/27.json` on the next compile. Trigger it:

```bash
./gradlew :core:data:compileDebugKotlin
ls core/data/schemas/com.stash.core.data.db.StashDatabase/27.json
```

Expected: the file exists. Commit it together with the other changes.

- [ ] **Step 12: Run all `:core:data` unit tests**

```bash
./gradlew :core:data:testDebugUnitTest
```

Expected: PASS (pre-existing failures aside).

- [ ] **Step 13: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/db/entity/TrackEntity.kt \
        core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt \
        core/data/src/main/kotlin/com/stash/core/data/di/DatabaseModule.kt \
        core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt \
        core/data/src/main/kotlin/com/stash/core/data/mapper/TrackMapper.kt \
        core/data/schemas/com.stash.core.data.db.StashDatabase/27.json \
        core/data/src/androidTest/kotlin/com/stash/core/data/db/Migration26To27Test.kt \
        core/data/src/test/kotlin/com/stash/core/data/mapper/TrackMapperMetadataFieldsTest.kt
git commit -m "$(cat <<'EOF'
feat(db): add tracks.metadata_embedded_at + Room v26->v27 migration

v0.9.35 idempotency signal for the tag + art backfill. NULL = needs
tagging, non-null timestamp = success, 0L = irrecoverable failure
(file missing, SAF row). DAO surface: setMetadataEmbeddedAt,
getTracksNeedingEmbed (paginated), observeTracksNeedingEmbedCount
(reactive count for the Home banner). Mapper now also propagates
albumArtist, which has lived on the entity since v0.9.26 but was
never surfaced through the domain layer.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Phase 3 — Embedder + Art cache

Phase end state: `AlbumArtCache` fetches + dedupes JPEGs; `MetadataEmbedder` writes the full v0.9.35 tag set and accepts cover art. Both verified by unit tests. No call-site changes yet — the existing call from `TrackFinalizer` will still pass `null` for art until Phase 4.

## Task 4: `AlbumArtCache`

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/files/AlbumArtCache.kt`
- Test: `data/download/src/test/kotlin/com/stash/data/download/files/AlbumArtCacheTest.kt`

- [ ] **Step 0: Verify the FileOrganizer methods the cache will reuse**

```bash
grep -n "getAlbumArtDir\|getAlbumArtFile" data/download/src/main/kotlin/com/stash/data/download/files/FileOrganizer.kt
```

Expected: two lines referencing `getAlbumArtDir(): File` and `getAlbumArtFile(albumId: String): File`. Confirmed exists in master at lines 65 + 68 — but verify in case the file drifted. If absent, the cache implementation has to define them itself; surface to the user before proceeding.

- [ ] **Step 1: Write the failing tests**

`data/download/src/test/kotlin/com/stash/data/download/files/AlbumArtCacheTest.kt`:

```kotlin
package com.stash.data.download.files

import com.stash.core.model.Track
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class AlbumArtCacheTest {

    private val cacheDir = File(System.getProperty("java.io.tmpdir"), "albumart-test-${System.nanoTime()}")
    private val fileOrganizer: FileOrganizer = mockk()
    private val httpClient: OkHttpClient = mockk()
    private lateinit var subject: AlbumArtCache

    @Before fun setUp() {
        cacheDir.mkdirs()
        coEvery { fileOrganizer.getAlbumArtDir() } returns cacheDir
        subject = AlbumArtCache(fileOrganizer, httpClient)
    }

    @After fun tearDown() {
        cacheDir.deleteRecursively()
    }

    @Test fun `returns null when track has no albumArtUrl`() = runTest {
        val result = subject.resolveArt(stubTrack(albumArtUrl = null))
        assertNull(result)
    }

    @Test fun `fetches and caches a fresh URL`() = runTest {
        val body = ByteArray(1024) { 0xFF.toByte() }
        stubHttp200(body)
        val result = subject.resolveArt(stubTrack(albumArtUrl = "https://i.scdn.co/image/abc"))
        assertNotNull(result)
        assertEquals(1024L, result!!.length())
    }

    @Test fun `second call hits cache`() = runTest {
        val body = ByteArray(2048)
        val call = stubHttp200(body)
        subject.resolveArt(stubTrack(albumArtUrl = "https://i.scdn.co/image/abc"))
        subject.resolveArt(stubTrack(albumArtUrl = "https://i.scdn.co/image/abc"))
        coVerify(exactly = 1) { httpClient.newCall(any()) }
    }

    @Test fun `404 returns null`() = runTest {
        stubHttp(code = 404, body = ByteArray(0))
        val result = subject.resolveArt(stubTrack(albumArtUrl = "https://i.scdn.co/image/missing"))
        assertNull(result)
    }

    @Test fun `non-image content-type returns null`() = runTest {
        stubHttp(code = 200, body = ByteArray(64), contentType = "text/html")
        val result = subject.resolveArt(stubTrack(albumArtUrl = "https://i.scdn.co/image/wrong"))
        assertNull(result)
    }

    @Test fun `zero-byte response returns null`() = runTest {
        stubHttp200(body = ByteArray(0))
        val result = subject.resolveArt(stubTrack(albumArtUrl = "https://i.scdn.co/image/empty"))
        assertNull(result)
    }

    @Test fun `same album URL with different size suffixes shares the cache`() = runTest {
        val body = ByteArray(512)
        stubHttp200(body)
        subject.resolveArt(stubTrack(albumArtUrl = "https://lh3.googleusercontent.com/abc=w64-h64"))
        subject.resolveArt(stubTrack(albumArtUrl = "https://lh3.googleusercontent.com/abc=w640-h640"))
        // Both URLs canonicalise to the same 640px form so we should fetch once
        coVerify(exactly = 1) { httpClient.newCall(any()) }
    }

    // ─── helpers ───────────────────────────────────────────────────────

    private fun stubTrack(albumArtUrl: String?) = Track(
        id = 1, title = "T", artist = "A", albumArtUrl = albumArtUrl,
    )

    private fun stubHttp200(body: ByteArray): Call = stubHttp(200, body, "image/jpeg")

    private fun stubHttp(code: Int, body: ByteArray, contentType: String = "image/jpeg"): Call {
        val call = mockk<Call>()
        val response = Response.Builder()
            .request(Request.Builder().url("https://example.test/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("OK")
            .body(body.toResponseBody(contentType.toMediaTypeOrNullSafe()))
            .build()
        coEvery { httpClient.newCall(any()) } returns call
        coEvery { call.execute() } returns response
        return call
    }
}

private fun String.toMediaTypeOrNullSafe() = okhttp3.MediaType.parse(this)
```

(Above test imports may need small adjustments per the project's OkHttp version — verify `MediaType.parse` vs `toMediaType` once you've checked the imported version. Match the style of any existing OkHttp-based test in the module.)

- [ ] **Step 2: Verify the tests fail**

```bash
./gradlew :data:download:testDebugUnitTest --tests "*AlbumArtCacheTest*"
```

Expected: FAIL with `Unresolved reference: AlbumArtCache`.

- [ ] **Step 3: Implement `AlbumArtCache`**

`data/download/src/main/kotlin/com/stash/data/download/files/AlbumArtCache.kt`:

```kotlin
package com.stash.data.download.files

import android.util.Log
import com.stash.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lazily fetches and caches album-art JPEGs on the local filesystem so
 * [MetadataEmbedder] can attach them to downloaded audio files.
 *
 * Storage: [FileOrganizer.getAlbumArtDir] (= `cacheDir/albumart/`).
 * Filenames are SHA-1 over the canonicalised `albumArtUrl` (first 16
 * hex chars + `.jpg`), so two tracks on the same album resolve to the
 * same cache file regardless of the URL's size suffix. Network /
 * decode / missing-URL failures all return `null` — embedding proceeds
 * without art. We never block a download because the art server is
 * slow.
 *
 * Lifecycle: the cache dir lives under `cacheDir/`, so Android may
 * evict files under storage pressure. If a cached file disappears
 * between calls we just re-download.
 */
@Singleton
class AlbumArtCache @Inject constructor(
    private val fileOrganizer: FileOrganizer,
    private val httpClient: OkHttpClient,
) {
    /**
     * Returns a local JPEG file for [track]'s album cover. Returns
     * null when the URL is missing, the response is non-2xx, the
     * response Content-Type isn't an image, the file body is empty,
     * or any IO/network error fires.
     */
    suspend fun resolveArt(track: Track): File? = withContext(Dispatchers.IO) {
        val url = track.albumArtUrl?.takeIf { it.isNotBlank() } ?: return@withContext null
        val canonical = canonicaliseUrl(url)
        val cacheFile = File(fileOrganizer.getAlbumArtDir(), hashFilename(canonical))

        if (cacheFile.exists() && cacheFile.length() > 0) return@withContext cacheFile

        runCatching {
            val response = httpClient.newCall(Request.Builder().url(canonical).build()).execute()
            response.use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val contentType = resp.header("Content-Type").orEmpty()
                if (!contentType.startsWith("image/")) return@withContext null
                val bytes = resp.body?.bytes() ?: return@withContext null
                if (bytes.isEmpty()) return@withContext null
                cacheFile.writeBytes(bytes)
                cacheFile
            }
        }.getOrElse { e ->
            Log.w(TAG, "art fetch failed for $canonical: ${e.message}")
            cacheFile.delete()
            null
        }
    }

    /**
     * Spotify (`i.scdn.co/image/<hash>`) URLs are already
     * size-agnostic. YT-Music URLs (`lh3.googleusercontent.com/...
     * =w<size>-h<size>`) normalise to the 640px tier so requests for
     * 64px and 640px share a cache entry.
     */
    private fun canonicaliseUrl(url: String): String =
        url.replace(Regex("=w\\d+-h\\d+(-[a-z]+)?"), "=w640-h640")

    private fun hashFilename(canonical: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(canonical.toByteArray())
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "${hex.take(16)}.jpg"
    }

    companion object {
        private const val TAG = "AlbumArtCache"
    }
}
```

- [ ] **Step 4: Verify the tests pass**

```bash
./gradlew :data:download:testDebugUnitTest --tests "*AlbumArtCacheTest*"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/files/AlbumArtCache.kt \
        data/download/src/test/kotlin/com/stash/data/download/files/AlbumArtCacheTest.kt
git commit -m "$(cat <<'EOF'
feat(download): AlbumArtCache — dedup album JPEGs in cacheDir/albumart/

One HTTP fetch per album, not per track. Keyed by SHA-1 of the
canonicalised URL (YT-Music size suffix normalised to w640-h640
before hashing so different-resolution requests share a cache
entry). All failure modes (missing URL, 4xx/5xx, non-image
Content-Type, zero-byte body) return null so MetadataEmbedder
proceeds without art rather than blocking the download.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: `MetadataEmbedder` — expanded tag set + AppVersionProvider

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/files/MetadataEmbedder.kt`
- Test: `data/download/src/test/kotlin/com/stash/data/download/files/MetadataEmbedderArgsTest.kt`

**Why this task is structured for testability:** The existing `MetadataEmbedder.embedMetadata` is hard to unit-test because it shells out to ffmpeg. The refactor below extracts the argv `buildList` block into a pure helper `buildFfmpegArgs(audioFile, outputFile, track, albumArtFile)` that we can unit-test directly. The outer `embedMetadata` continues to call ffmpeg and is exercised by the androidTest integration suite in Task 14.

- [ ] **Step 1: Write the failing tests for `buildFfmpegArgs`**

`data/download/src/test/kotlin/com/stash/data/download/files/MetadataEmbedderArgsTest.kt`:

```kotlin
package com.stash.data.download.files

import com.stash.core.common.AppVersionProvider
import com.stash.core.model.Track
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MetadataEmbedderArgsTest {

    private val versionProvider = object : AppVersionProvider {
        override val versionName: String = "0.9.35"
        override val versionCode: Int = 71
    }

    @Test fun `writes ALBUMARTIST + album_artist when track has albumArtist`() {
        val args = MetadataEmbedder.buildFfmpegArgs(
            audioFile = File("/tmp/in.opus"),
            outputFile = File("/tmp/out.opus"),
            track = Track(
                id = 1, title = "T", artist = "Drake, 21 Savage",
                albumArtist = "Drake",
            ),
            albumArtFile = null,
            appVersion = versionProvider,
        )
        assertTrue("ALBUMARTIST=Drake" in args.zipMetadataValues())
        assertTrue("album_artist=Drake" in args.zipMetadataValues())
    }

    @Test fun `falls back to artist when albumArtist is blank`() {
        val args = MetadataEmbedder.buildFfmpegArgs(
            audioFile = File("/tmp/in.opus"),
            outputFile = File("/tmp/out.opus"),
            track = Track(
                id = 1, title = "T", artist = "Drake",
                albumArtist = "",
            ),
            albumArtFile = null,
            appVersion = versionProvider,
        )
        assertTrue("ALBUMARTIST=Drake" in args.zipMetadataValues())
    }

    @Test fun `writes ISRC when present`() {
        val args = MetadataEmbedder.buildFfmpegArgs(
            audioFile = File("/tmp/in.opus"),
            outputFile = File("/tmp/out.opus"),
            track = Track(id = 1, title = "T", artist = "A", isrc = "USRC17607839"),
            albumArtFile = null,
            appVersion = versionProvider,
        )
        assertTrue("ISRC=USRC17607839" in args.zipMetadataValues())
    }

    @Test fun `omits ISRC when blank or null`() {
        val args = MetadataEmbedder.buildFfmpegArgs(
            audioFile = File("/tmp/in.opus"),
            outputFile = File("/tmp/out.opus"),
            track = Track(id = 1, title = "T", artist = "A", isrc = null),
            albumArtFile = null,
            appVersion = versionProvider,
        )
        assertFalse(args.zipMetadataValues().any { it.startsWith("ISRC=") })
    }

    @Test fun `writes ENCODER with versionName`() {
        val args = MetadataEmbedder.buildFfmpegArgs(
            audioFile = File("/tmp/in.opus"),
            outputFile = File("/tmp/out.opus"),
            track = Track(id = 1, title = "T", artist = "A"),
            albumArtFile = null,
            appVersion = versionProvider,
        )
        assertTrue("ENCODER=Stash 0.9.35" in args.zipMetadataValues())
    }

    @Test fun `attaches art when albumArtFile is non-null and exists`() {
        val art = File.createTempFile("art", ".jpg").apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte())) }
        val args = MetadataEmbedder.buildFfmpegArgs(
            audioFile = File("/tmp/in.opus"),
            outputFile = File("/tmp/out.opus"),
            track = Track(id = 1, title = "T", artist = "A"),
            albumArtFile = art,
            appVersion = versionProvider,
        )
        assertTrue(args.contains("-disposition:v:0"))
        assertTrue(args.contains("attached_pic"))
        assertTrue(args.contains(art.absolutePath))
        art.delete()
    }

    @Test fun `sanitises control characters from values`() {
        val args = MetadataEmbedder.buildFfmpegArgs(
            audioFile = File("/tmp/in.opus"),
            outputFile = File("/tmp/out.opus"),
            track = Track(id = 1, title = "Hello\u0000World", artist = "Evil\u0001\u001F"),
            albumArtFile = null,
            appVersion = versionProvider,
        )
        assertTrue("title=HelloWorld" in args.zipMetadataValues())
        assertTrue("artist=Evil" in args.zipMetadataValues())
    }

    // Pairs every `-metadata` flag with the value that follows it.
    private fun List<String>.zipMetadataValues(): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        while (i < size - 1) {
            if (this[i] == "-metadata") result.add(this[i + 1])
            i++
        }
        return result
    }
}
```

- [ ] **Step 2: Verify the tests fail**

```bash
./gradlew :data:download:testDebugUnitTest --tests "*MetadataEmbedderArgsTest*"
```

Expected: FAIL — `buildFfmpegArgs` doesn't exist yet, `Track` constructor doesn't take `albumArtist` until Phase 1 commits land (already done at this point per task order).

- [ ] **Step 3: Refactor + expand `MetadataEmbedder`**

Open `data/download/src/main/kotlin/com/stash/data/download/files/MetadataEmbedder.kt`. Replace the file content. **Note:** the existing constructor takes `fileOrganizer: FileOrganizer` but `embedMetadata` never uses it (only `context.applicationInfo.nativeLibraryDir`). Drop the parameter — it's dead weight and simplifies the integration test setup in Task 14.

```kotlin
package com.stash.data.download.files

import android.content.Context
import com.stash.core.common.AppVersionProvider
import com.stash.core.model.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Embeds metadata (TITLE, ARTIST, ALBUMARTIST, ALBUM, ISRC, ENCODER)
 * and optional cover art into audio files using ffmpeg. Container-
 * agnostic — `-c copy` muxes the new metadata + picture stream into
 * the original codec without re-encoding.
 *
 * The ffmpeg binary is bundled by youtubedl-android as a native .so.
 * If tagging fails for any reason, the original untagged file is
 * preserved — an untagged download is preferable to a missing one.
 *
 * Vorbis-comment casing: writes each key twice (canonical
 * `ALBUMARTIST` + legacy `album_artist`) so both strict Vorbis
 * readers (Symfonium, some car head units) and ID3-style readers
 * (Plex, Foobar) see the value. ffmpeg normalises both forms to a
 * single atom/frame for M4A and MP3 containers, so the duplicate
 * is a no-op there.
 */
@Singleton
class MetadataEmbedder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appVersion: AppVersionProvider,
) {
    suspend fun embedMetadata(
        audioFile: File,
        track: Track,
        albumArtFile: File? = null,
    ): File = withContext(Dispatchers.IO) {
        val outputFile = File(
            audioFile.parent,
            "${audioFile.nameWithoutExtension}_tagged.${audioFile.extension}",
        )

        val args = buildFfmpegArgs(audioFile, outputFile, track, albumArtFile, appVersion)

        try {
            val ffmpegPath = resolveFfmpegBinary() ?: return@withContext audioFile
            val process = ProcessBuilder(listOf(ffmpegPath.absolutePath) + args)
                .redirectErrorStream(true)
                .start()
            process.waitFor()

            if (outputFile.exists() && outputFile.length() > 0) {
                audioFile.delete()
                outputFile.renameTo(audioFile)
            }
        } catch (_: Exception) {
            outputFile.delete()
        }

        audioFile
    }

    private fun resolveFfmpegBinary(): File? {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val candidates = listOf("libffmpeg.so", "libffmpeg.zip.so")
        return candidates.map { File(nativeDir, it) }.firstOrNull { it.exists() }
    }

    companion object {
        private fun sanitize(value: String): String =
            value.replace(Regex("[\\x00-\\x1f]"), "")

        /**
         * Pure helper — builds the ffmpeg argv list for the embed
         * pass. Extracted so unit tests can exercise the tag-writing
         * logic without spawning a process. Internal `companion`
         * visibility intentional: only the embedder + its tests
         * need this.
         */
        fun buildFfmpegArgs(
            audioFile: File,
            outputFile: File,
            track: Track,
            albumArtFile: File?,
            appVersion: AppVersionProvider,
        ): List<String> = buildList {
            add("-i"); add(audioFile.absolutePath)

            if (albumArtFile != null && albumArtFile.exists()) {
                add("-i"); add(albumArtFile.absolutePath)
                add("-map"); add("0:a")
                add("-map"); add("1:0")
                add("-disposition:v:0"); add("attached_pic")
            }

            add("-metadata"); add("title=${sanitize(track.title)}")
            add("-metadata"); add("artist=${sanitize(track.artist)}")

            if (track.album.isNotEmpty()) {
                add("-metadata"); add("album=${sanitize(track.album)}")
            }

            val effectiveAlbumArtist = track.albumArtist.ifBlank { track.artist }
            if (effectiveAlbumArtist.isNotEmpty()) {
                add("-metadata"); add("ALBUMARTIST=${sanitize(effectiveAlbumArtist)}")
                add("-metadata"); add("album_artist=${sanitize(effectiveAlbumArtist)}")
            }

            track.isrc?.takeIf { it.isNotBlank() }?.let {
                add("-metadata"); add("ISRC=${sanitize(it)}")
            }

            add("-metadata"); add("ENCODER=Stash ${appVersion.versionName}")

            add("-c"); add("copy")
            add("-y")
            add(outputFile.absolutePath)
        }
    }
}
```

- [ ] **Step 4: Verify the unit tests pass**

```bash
./gradlew :data:download:testDebugUnitTest --tests "*MetadataEmbedderArgsTest*"
```

Expected: PASS.

- [ ] **Step 5: Update the KDoc on yt-dlp's `--embed-metadata`**

Open `data/download/src/main/kotlin/com/stash/data/download/prefs/QualityPreferencesManager.kt`. Find the KDoc block ending at line ~82 that says "`--embed-metadata` tells yt-dlp to write title/artist/album tags into the file during download, eliminating a separate ffmpeg metadata pass." Replace with:

```kotlin
 * `--embed-metadata` tells yt-dlp to write whatever YouTube reports
 * (uploader, video title, description) into the file as a fallback
 * tag layer. As of v0.9.35 the primary tag write happens after the
 * download via [MetadataEmbedder.embedMetadata], which overwrites
 * the noisy YouTube-derived tags with Stash's clean
 * TITLE/ARTIST/ALBUMARTIST/ALBUM/ISRC set plus embedded cover art.
 * The yt-dlp flag stays as a safety net — if our ffmpeg pass fails
 * for any reason, the file still has yt-dlp's tags rather than none.
```

- [ ] **Step 6: Run all `:data:download` unit tests**

```bash
./gradlew :data:download:testDebugUnitTest
```

Expected: PASS (excluding any pre-existing failures from prior plans).

- [ ] **Step 7: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/files/MetadataEmbedder.kt \
        data/download/src/test/kotlin/com/stash/data/download/files/MetadataEmbedderArgsTest.kt \
        data/download/src/main/kotlin/com/stash/data/download/prefs/QualityPreferencesManager.kt
git commit -m "$(cat <<'EOF'
feat(download): MetadataEmbedder writes Plex-friendly tag set + ENCODER

Expanded tag set: TITLE / ARTIST / ALBUMARTIST (canonical uppercase
plus lowercase legacy alias) / ALBUM / ISRC / ENCODER. ALBUMARTIST
prefers track.albumArtist (surfaced in Phase 1) and falls back to
track.artist when blank. ENCODER pulls the binary version from a
new AppVersionProvider so :data:download doesn't need its own
BuildConfig.

Refactor: the ffmpeg argv buildList is extracted into a pure
companion helper buildFfmpegArgs so the tag-writing logic is
unit-testable without spawning a process. The outer embedMetadata
suspends shell-out is unchanged and still covered by the
androidTest integration suite. Dropped the unused FileOrganizer
constructor parameter — the embed pass only needed Context and now
AppVersionProvider.

KDoc on QualityPreferencesManager.toYtDlpArgs now describes the
--embed-metadata flag accurately as a fallback tag layer rather
than the primary writer.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Phase 4 — New-download tag wiring

Phase end state: both yt-dlp and lossless download paths embed clean tags + cover art at finalize time and stamp `metadata_embedded_at` on success. Local imports auto-stamp at import time so backfill skips them.

## Task 6: `TrackFinalizer` — accept art, embed it, leave stamping to the caller

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/shared/TrackFinalizer.kt`
- Test: `data/download/src/test/kotlin/com/stash/data/download/shared/TrackFinalizerTest.kt` (modify if it exists; create if not)

- [ ] **Step 1: Discover the existing test file (if any)**

```bash
ls data/download/src/test/kotlin/com/stash/data/download/shared/ 2>/dev/null
```

If `TrackFinalizerTest.kt` exists, modify it; otherwise create it. The new test we need:

- [ ] **Step 2: Write the failing test**

Add to or create `TrackFinalizerTest.kt`:

```kotlin
@Test fun `finalizeFile resolves art and passes it to the embedder`() = runTest {
    val art = File.createTempFile("art", ".jpg").apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte())) }
    coEvery { albumArtCache.resolveArt(any()) } returns art
    coEvery { metadataEmbedder.embedMetadata(any(), any(), any()) } answers {
        // Returns the input audio file untouched — what the real impl
        // returns on success.
        firstArg()
    }
    coEvery { fileOrganizer.commitDownload(any(), any(), any(), any(), any()) } returns
        FileOrganizer.CommittedTrack("/library/Drake/x.flac", 100)
    coEvery { audioExtractor.extract(any()) } returns null

    val result = subject.finalizeFile(
        sourceFile = File.createTempFile("src", ".flac"),
        track = stubTrack(),
        format = AudioFormat(codec = "flac", bitsPerSample = 16, sampleRateHz = 44100),
    )

    coVerify { metadataEmbedder.embedMetadata(any(), any(), eq(art)) }
    assertTrue(result is TrackFinalizer.FinalizeResult.Success)
    art.delete()
}

@Test fun `finalizeFile proceeds when art resolve returns null`() = runTest {
    coEvery { albumArtCache.resolveArt(any()) } returns null
    coEvery { metadataEmbedder.embedMetadata(any(), any(), any()) } answers { firstArg() }
    coEvery { fileOrganizer.commitDownload(any(), any(), any(), any(), any()) } returns
        FileOrganizer.CommittedTrack("/library/Drake/x.flac", 100)
    coEvery { audioExtractor.extract(any()) } returns null

    val result = subject.finalizeFile(
        sourceFile = File.createTempFile("src", ".flac"),
        track = stubTrack(),
        format = AudioFormat(codec = "flac", bitsPerSample = 16, sampleRateHz = 44100),
    )

    coVerify { metadataEmbedder.embedMetadata(any(), any(), eq(null)) }
    assertTrue(result is TrackFinalizer.FinalizeResult.Success)
}
```

Fill in the `subject = TrackFinalizer(...)` instantiation and mocks (`albumArtCache`, `metadataEmbedder`, `fileOrganizer`, `audioExtractor`) following the existing test fixtures if present.

- [ ] **Step 3: Verify it fails**

```bash
./gradlew :data:download:testDebugUnitTest --tests "*TrackFinalizerTest*"
```

Expected: FAIL — `TrackFinalizer` doesn't yet accept `AlbumArtCache`.

- [ ] **Step 4: Modify `TrackFinalizer`**

Open `data/download/src/main/kotlin/com/stash/data/download/shared/TrackFinalizer.kt`. Add `private val albumArtCache: AlbumArtCache` to the constructor, then update `finalizeFile`:

```kotlin
suspend fun finalizeFile(
    sourceFile: File,
    track: Track,
    format: AudioFormat,
    embedMetadata: Boolean = true,
): FinalizeResult = runCatching {
    if (embedMetadata) {
        val art = runCatching { albumArtCache.resolveArt(track) }
            .onFailure { Log.w(TAG, "art resolve failed: ${it.message}") }
            .getOrNull()
        runCatching { metadataEmbedder.embedMetadata(sourceFile, track, art) }
            .onFailure { Log.w(TAG, "metadata embed failed: ${it.message}") }
    }
    val committed: CommittedTrack = fileOrganizer.commitDownload(
        tempFile = sourceFile,
        artist = track.artist,
        album = track.album.takeIf { it.isNotBlank() },
        title = track.title,
        format = format.codec.ifBlank { "flac" },
    )
    val meta: AudioMetadata? = audioExtractor.extract(committed.filePath)
    FinalizeResult.Success(committed, meta)
}.getOrElse { e ->
    FinalizeResult.Failed(e.message ?: "finalize failed")
}
```

Note: `setMetadataEmbeddedAt` is **NOT** called here. Per the spec §4.2, the DB-write stays in the caller because `TrackFinalizer` is intentionally DB-free.

- [ ] **Step 5: Verify the test passes**

```bash
./gradlew :data:download:testDebugUnitTest --tests "*TrackFinalizerTest*"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/shared/TrackFinalizer.kt \
        data/download/src/test/kotlin/com/stash/data/download/shared/TrackFinalizerTest.kt
git commit -m "$(cat <<'EOF'
feat(download): TrackFinalizer resolves cover art and passes it to embedder

Until v0.9.35 the lossless path embedded clean tags but always
called embedMetadata with albumArtFile=null, so files came out
tag-correct but visually blank in Plex/Foobar. Wires AlbumArtCache
into the finalizer; the embedder now sees a JPEG. Art-resolve
failures are non-fatal (the file is still tagged, just without a
picture).

DB stamp deliberately NOT moved here — TrackFinalizer stays
DB-free per its existing contract. Callers (DownloadManager,
SearchDownloadCoordinator) stamp metadata_embedded_at after a
successful finalize in the next commit.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: `DownloadManager` — yt-dlp embed step + DB stamps on both paths

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/DownloadManager.kt`
- Test: `data/download/src/test/kotlin/com/stash/data/download/DownloadManagerTest.kt` (modify if exists; create if not)

- [ ] **Step 1: Survey the existing test infrastructure**

```bash
ls data/download/src/test/kotlin/com/stash/data/download/ 2>/dev/null
```

Find any existing `DownloadManagerTest.kt`. If present, extend it; otherwise the new tests can live in `DownloadManagerEmbedStampTest.kt` to keep the diff small.

- [ ] **Step 2: Write the failing tests**

`data/download/src/test/kotlin/com/stash/data/download/DownloadManagerEmbedStampTest.kt`:

```kotlin
package com.stash.data.download

import com.stash.core.data.db.dao.TrackDao
import com.stash.core.model.Track
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DownloadManagerEmbedStampTest {
    // Sketch — fill in dependencies following the existing patterns in
    // the module. The point is: on the yt-dlp success path,
    // metadataEmbedder.embedMetadata is called BEFORE
    // fileOrganizer.commitDownload, and trackDao.setMetadataEmbeddedAt
    // is called AFTER a successful commit, with a non-zero timestamp.
    // Same stamp behaviour on the lossless success path.

    @Test fun `yt-dlp success path embeds then stamps metadata_embedded_at`() = runTest {
        // ...arrange mocks for the full pipeline...
        // ...act: subject.downloadTrack(stubTrack())...
        // coVerifyOrder {
        //     metadataEmbedder.embedMetadata(any(), any(), any())
        //     fileOrganizer.commitDownload(any(), any(), any(), any(), any())
        //     trackDao.setMetadataEmbeddedAt(eq(1L), match { it > 0L })
        // }
    }

    @Test fun `lossless success path stamps metadata_embedded_at`() = runTest {
        // Similar shape — tryLosslessDownload returns Success,
        // then setMetadataEmbeddedAt is called with the same trackId.
    }
}
```

This task's tests are necessarily a sketch — the real test setup mirrors however the codebase exercises `DownloadManager` today. If no integration-style harness exists yet, prefer scoped tests on the helper methods over a full pipeline mock.

- [ ] **Step 3: Verify the tests fail**

```bash
./gradlew :data:download:testDebugUnitTest --tests "*DownloadManagerEmbedStamp*"
```

Expected: FAIL.

- [ ] **Step 4: Modify `DownloadManager.executeDownload`**

Open `data/download/src/main/kotlin/com/stash/data/download/DownloadManager.kt`. Add two constructor injections:

```kotlin
private val metadataEmbedder: com.stash.data.download.files.MetadataEmbedder,
private val albumArtCache: com.stash.data.download.files.AlbumArtCache,
```

Find the yt-dlp branch — after `downloadExecutor.download(...)` returns a `DownloadResult.Success(file)` (currently line ~224-242), and BEFORE the call to `fileOrganizer.commitDownload(...)` (line ~250-256). Insert:

```kotlin
// Embed clean Stash-side tags + cover art into the file before
// commit. yt-dlp's --embed-metadata leaves YouTube-flavoured tags
// (uploader, video title); our pass overwrites them with the clean
// Spotify/YT-Music identity already on the Track row. Failure is
// non-fatal: the file remains playable and yt-dlp's fallback tags
// stay in place.
val art = runCatching { albumArtCache.resolveArt(effectiveTrack) }.getOrNull()
runCatching { metadataEmbedder.embedMetadata(downloadedFile, effectiveTrack, art) }
    .onFailure { Log.w(TAG, "metadata embed failed for ${track.id}: ${it.message}") }
```

After the existing `Log.i(TAG, "Downloaded: ..." ...)` line and before `return TrackDownloadResult.Success(...)`, stamp the DB:

```kotlin
runCatching { trackDao.setMetadataEmbeddedAt(track.id, System.currentTimeMillis()) }
    .onFailure { Log.w(TAG, "setMetadataEmbeddedAt failed for ${track.id}: ${it.message}") }
```

- [ ] **Step 5: Stamp on the lossless success path**

In the same file, find `tryLosslessDownload`'s `TrackFinalizer.FinalizeResult.Success` branch (currently line ~365-407). After the `Log.i(TAG, "Lossless downloaded ..." ...)` line and BEFORE `emitProgress(track.id, 1f, DownloadStatus.COMPLETED)`, add:

```kotlin
runCatching { trackDao.setMetadataEmbeddedAt(track.id, System.currentTimeMillis()) }
    .onFailure { Log.w(TAG, "setMetadataEmbeddedAt failed for ${track.id}: ${it.message}") }
```

- [ ] **Step 6: Remove the stale "Metadata is now embedded by yt-dlp" comment**

Around line ~244, the comment `// Metadata is now embedded by yt-dlp via --embed-metadata flag. // No separate ffmpeg step needed.` is now incorrect. Replace with:

```kotlin
// Metadata + cover art written above by MetadataEmbedder. yt-dlp's
// --embed-metadata still runs as a fallback layer (see toYtDlpArgs).
```

- [ ] **Step 7: Verify the tests pass**

```bash
./gradlew :data:download:testDebugUnitTest --tests "*DownloadManagerEmbedStamp*"
./gradlew :data:download:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 8: Build the APK to confirm wiring works**

```bash
./gradlew :app:installDebug
```

Expected: BUILD SUCCESSFUL + APK installs on a connected device. Per project memory: compile-pass alone isn't enough. The Hilt DI graph might have rejected the new injections — `installDebug` flushes that out.

- [ ] **Step 9: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/DownloadManager.kt \
        data/download/src/test/kotlin/com/stash/data/download/DownloadManagerEmbedStampTest.kt
git commit -m "$(cat <<'EOF'
feat(download): yt-dlp path embeds clean tags + stamps embedded_at

Inserts a MetadataEmbedder + AlbumArtCache pass between
downloadExecutor.download and fileOrganizer.commitDownload on the
yt-dlp path. This was the load-bearing gap for #76 / #90: that
path used to rely entirely on yt-dlp's --embed-metadata which
writes YouTube-flavoured tags. The post-download pass overwrites
them with the clean Spotify/YT-Music identity from the Track row.

Both yt-dlp and lossless success paths now stamp
trackDao.setMetadataEmbeddedAt so the backfill worker skips them.
Stamp failures are non-fatal (warn-only) — the file is committed
and playable regardless.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: `LocalImportCoordinator` — stamp on import

**Why:** Locally-imported files already have correct tags (the importer reads them out of the file at import time). Stamping `metadata_embedded_at = now` at import keeps them out of the backfill queue.

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/files/LocalImportCoordinator.kt`

- [ ] **Step 1: Locate the insert/upsert call**

```bash
grep -n "trackDao\.\|TrackEntity(" data/download/src/main/kotlin/com/stash/data/download/files/LocalImportCoordinator.kt | head -20
```

Find where the importer writes the new track row (typically a `trackDao.upsert(...)` or `TrackEntity(...)` constructor call around line ~180-190 based on the earlier grep showing `albumArtPath = albumArtPath, ...`).

- [ ] **Step 2: Pass `metadataEmbeddedAt = System.currentTimeMillis()` into the entity construction**

Add the field to the `TrackEntity(...)` call. The exact insertion point depends on the file's current shape — keep it ordered alongside `albumArtPath`.

- [ ] **Step 3: Compile-check + test sweep**

```bash
./gradlew :data:download:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/files/LocalImportCoordinator.kt
git commit -m "$(cat <<'EOF'
feat(download): stamp metadata_embedded_at on local file imports

LocalImportCoordinator reads tags out of the user's existing file
at import time, so they're already correct. Stamping the timestamp
at insert keeps imported tracks out of the v0.9.35 backfill queue —
re-embedding them with our generic tag set would overwrite the
real ones from the source file.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Phase 5 — Backfill worker

Phase end state: `MetadataBackfillState` and `BackfillVersionTracker` are persistent, the `MetadataBackfillWorker` drains the queue end-to-end on unit tests, the scheduler enqueues once per binary version. No UI yet.

## Task 9: `MetadataBackfillState` (Preferences DataStore)

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/backfill/MetadataBackfillState.kt`
- Create: `data/download/src/main/kotlin/com/stash/data/download/backfill/di/BackfillModule.kt`
- Test: `data/download/src/test/kotlin/com/stash/data/download/backfill/MetadataBackfillStateTest.kt`

- [ ] **Step 1: Survey the existing DataStore pattern + test pattern**

```bash
head -80 data/download/src/main/kotlin/com/stash/data/download/lossless/LosslessSourcePreferences.kt
ls data/download/src/test/kotlin/com/stash/data/download/lossless/ | grep -i preferences
```

Mirror the file/class shape: top-level `private val Context.dataStore = preferencesDataStore(name = "metadata_backfill_state")` extension, plus a `@Singleton` class with the public API.

**Test isolation caveat:** `preferencesDataStore(name = ...)` is a delegate that caches a single static `SingleProcessDataStore` instance per process. Across Robolectric tests, that cache survives — instantiating a second `MetadataBackfillState(context)` in a later test against the same file path raises `IllegalStateException: There are multiple DataStores active for the same file`. The existing `LosslessSourcePreferences*Test` files in the same module already solved this. **Open one** (`LosslessSourcePreferencesYoutubeFallbackTest.kt` is a likely candidate per the build.gradle comment) and mirror its setup/teardown — typically a `@BeforeClass` that initialises the subject once, or a `@get:Rule` test rule that scopes the delegate per-test. Do not invent a new isolation strategy.

- [ ] **Step 2: Write the failing test (Robolectric-driven)**

`data/download/src/test/kotlin/com/stash/data/download/backfill/MetadataBackfillStateTest.kt`:

```kotlin
package com.stash.data.download.backfill

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MetadataBackfillStateTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val subject = MetadataBackfillState(context)

    @After fun tearDown() {
        // Clear the test datastore between runs
        context.dataStoreFile("metadata_backfill_state.preferences_pb").delete()
    }

    @Test fun `defaults to IDLE with zero counts`() = runTest {
        val snapshot = subject.snapshot.first()
        assertEquals(MetadataBackfillState.State.IDLE, snapshot.state)
        assertEquals(0, snapshot.processed)
        assertEquals(0, snapshot.total)
        assertEquals(0, snapshot.safSkipped)
    }

    @Test fun `markStarted transitions to RUNNING and sets total`() = runTest {
        subject.markStarted(50)
        val snapshot = subject.snapshot.first()
        assertEquals(MetadataBackfillState.State.RUNNING, snapshot.state)
        assertEquals(50, snapshot.total)
        assertEquals(0, snapshot.processed)
    }

    @Test fun `publishProgress updates processed and total`() = runTest {
        subject.markStarted(50)
        subject.publishProgress(processed = 23, total = 50)
        val snapshot = subject.snapshot.first()
        assertEquals(23, snapshot.processed)
        assertEquals(50, snapshot.total)
    }

    @Test fun `incrementSafSkipped accumulates`() = runTest {
        subject.markStarted(50)
        subject.incrementSafSkipped()
        subject.incrementSafSkipped()
        subject.incrementSafSkipped()
        val snapshot = subject.snapshot.first()
        assertEquals(3, snapshot.safSkipped)
    }

    @Test fun `markFinished transitions to FINISHED`() = runTest {
        subject.markStarted(50)
        subject.publishProgress(50, 50)
        subject.markFinished()
        val snapshot = subject.snapshot.first()
        assertEquals(MetadataBackfillState.State.FINISHED, snapshot.state)
    }

    @Test fun `markFinishedAcknowledged transitions back to IDLE`() = runTest {
        subject.markStarted(50)
        subject.markFinished()
        subject.markFinishedAcknowledged()
        val snapshot = subject.snapshot.first()
        assertEquals(MetadataBackfillState.State.IDLE, snapshot.state)
    }
}
```

- [ ] **Step 3: Verify the test fails**

```bash
./gradlew :data:download:testDebugUnitTest --tests "*MetadataBackfillStateTest*"
```

Expected: FAIL — `MetadataBackfillState` doesn't exist.

- [ ] **Step 4: Implement `MetadataBackfillState`**

`data/download/src/main/kotlin/com/stash/data/download/backfill/MetadataBackfillState.kt`:

```kotlin
package com.stash.data.download.backfill

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// Top-level extension — single instance enforced by DataStore. Shared
// with BackfillVersionTracker; both classes resolve to the same
// DataStore<Preferences> via the property delegate.
private val Context.backfillDataStore by preferencesDataStore(name = "metadata_backfill_state")

@Singleton
class MetadataBackfillState @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store: DataStore<Preferences> get() = context.backfillDataStore

    val snapshot: Flow<BackfillSnapshot> = store.data.map { prefs ->
        BackfillSnapshot(
            state = State.valueOf(prefs[KEY_STATE] ?: State.IDLE.name),
            processed = prefs[KEY_PROCESSED] ?: 0,
            total = prefs[KEY_TOTAL] ?: 0,
            safSkipped = prefs[KEY_SAF_SKIPPED] ?: 0,
            finishedAt = prefs[KEY_FINISHED_AT],
        )
    }

    suspend fun markStarted(total: Int) {
        store.edit {
            it[KEY_STATE] = State.RUNNING.name
            it[KEY_TOTAL] = total
            it[KEY_PROCESSED] = 0
            it[KEY_SAF_SKIPPED] = 0
            it.remove(KEY_FINISHED_AT)
        }
    }

    suspend fun publishProgress(processed: Int, total: Int) {
        store.edit {
            it[KEY_PROCESSED] = processed
            it[KEY_TOTAL] = total
        }
    }

    suspend fun incrementSafSkipped() {
        store.edit {
            it[KEY_SAF_SKIPPED] = (it[KEY_SAF_SKIPPED] ?: 0) + 1
        }
    }

    suspend fun markFinished() {
        store.edit {
            it[KEY_STATE] = State.FINISHED.name
            it[KEY_FINISHED_AT] = System.currentTimeMillis()
        }
    }

    suspend fun markFinishedAcknowledged() {
        store.edit {
            it[KEY_STATE] = State.IDLE.name
        }
    }

    enum class State { IDLE, RUNNING, FINISHED }

    data class BackfillSnapshot(
        val state: State,
        val processed: Int,
        val total: Int,
        val safSkipped: Int,
        val finishedAt: Long?,
    )

    companion object {
        private val KEY_STATE = stringPreferencesKey("state")
        private val KEY_PROCESSED = intPreferencesKey("processed")
        private val KEY_TOTAL = intPreferencesKey("total")
        private val KEY_SAF_SKIPPED = intPreferencesKey("saf_skipped")
        private val KEY_FINISHED_AT = longPreferencesKey("finished_at")
    }
}
```

- [ ] **Step 5: Verify the test passes**

```bash
./gradlew :data:download:testDebugUnitTest --tests "*MetadataBackfillStateTest*"
```

Expected: PASS. The module's `build.gradle.kts` already has `isIncludeAndroidResources = true` which Robolectric needs.

- [ ] **Step 6: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/backfill/MetadataBackfillState.kt \
        data/download/src/test/kotlin/com/stash/data/download/backfill/MetadataBackfillStateTest.kt
git commit -m "$(cat <<'EOF'
feat(download): MetadataBackfillState DataStore for backfill progress

Preferences DataStore-backed observable powering the Home re-tagging
banner. Tracks state (IDLE/RUNNING/FINISHED), processed/total
counts, and a separate safSkipped counter for content:// rows the
worker can't operate on in place. State file is
metadata_backfill_state.preferences_pb — shared with
BackfillVersionTracker in the next commit.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 10: `BackfillVersionTracker`

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/backfill/BackfillVersionTracker.kt`
- Test: `data/download/src/test/kotlin/com/stash/data/download/backfill/BackfillVersionTrackerTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@RunWith(AndroidJUnit4::class)
class BackfillVersionTrackerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val versionProvider = object : AppVersionProvider {
        override val versionName: String = "0.9.35"
        override val versionCode: Int = 71
    }
    private val subject = BackfillVersionTracker(context, versionProvider)

    @After fun tearDown() {
        context.dataStoreFile("metadata_backfill_state.preferences_pb").delete()
    }

    @Test fun `runs for current version when never run`() = runTest {
        assertTrue(subject.shouldRunForCurrentVersion())
    }

    @Test fun `markEnqueued then shouldRun returns false at same version`() = runTest {
        subject.markEnqueuedForCurrentVersion()
        assertFalse(subject.shouldRunForCurrentVersion())
    }

    @Test fun `bumping version re-arms the tracker`() = runTest {
        subject.markEnqueuedForCurrentVersion()
        val newerProvider = object : AppVersionProvider {
            override val versionName: String = "0.9.36"
            override val versionCode: Int = 72
        }
        val subject2 = BackfillVersionTracker(context, newerProvider)
        assertTrue(subject2.shouldRunForCurrentVersion())
    }
}
```

- [ ] **Step 2: Verify it fails, then implement**

```kotlin
@Singleton
class BackfillVersionTracker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appVersion: AppVersionProvider,
) {
    private val store get() = context.backfillDataStore  // same file as MetadataBackfillState

    suspend fun shouldRunForCurrentVersion(): Boolean {
        val last = store.data.first()[KEY_ENQUEUED_VERSION] ?: -1
        return last < appVersion.versionCode
    }

    suspend fun markEnqueuedForCurrentVersion() {
        store.edit { it[KEY_ENQUEUED_VERSION] = appVersion.versionCode }
    }

    private companion object {
        private val KEY_ENQUEUED_VERSION = intPreferencesKey("backfill_enqueued_for_version")
    }
}
```

The top-level `Context.backfillDataStore` extension is declared in `MetadataBackfillState.kt`; importing it here gives the single shared `DataStore<Preferences>` instance.

- [ ] **Step 3: Verify the test passes, then commit**

```bash
./gradlew :data:download:testDebugUnitTest --tests "*BackfillVersionTrackerTest*"
git add data/download/src/main/kotlin/com/stash/data/download/backfill/BackfillVersionTracker.kt \
        data/download/src/test/kotlin/com/stash/data/download/backfill/BackfillVersionTrackerTest.kt
git commit -m "$(cat <<'EOF'
feat(download): BackfillVersionTracker — once-per-version enqueue gate

Stores backfill_enqueued_for_version in the shared
metadata_backfill_state DataStore. shouldRunForCurrentVersion()
returns true exactly once per new versionCode — re-installing the
same binary does NOT re-enqueue. Re-runs naturally happen on every
version bump going forward, giving us a clean upgrade lever for
any future tagging fix.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 11: `MetadataBackfillWorker`

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/backfill/MetadataBackfillWorker.kt`
- Test: `data/download/src/test/kotlin/com/stash/data/download/backfill/MetadataBackfillWorkerTest.kt`

- [ ] **Step 0: Survey existing worker test patterns**

```bash
ls data/download/src/test/kotlin/com/stash/data/download/lossless/ | grep -i worker
ls data/download/src/test/kotlin/com/stash/data/download/backfill/ 2>/dev/null
```

If `LosslessRetryWorkerTest.kt` exists in the lossless dir, **read it before writing the new test** — it's the precedent for testing a `@HiltWorker` with `@AssistedInject` in this project. The two viable strategies are:

1. **Direct constructor instantiation:** `MetadataBackfillWorker(context, params, ...)` directly, bypassing the assisted factory. Works for `@AssistedInject` constructors because the assisted parameters (`context`, `params`) are positional. Use `TestListenableWorkerBuilder` only to manufacture the `params`.
2. **TestListenableWorkerBuilder with a custom `WorkerFactory`:** more ceremony, used when you need to verify the Hilt factory wiring itself.

Strategy 1 is correct for what we're testing here (behaviour, not wiring). Pick that and mirror `LosslessRetryWorkerTest` if it uses Strategy 1; otherwise document the deviation.

- [ ] **Step 1: Write the failing tests**

```kotlin
class MetadataBackfillWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val trackDao: TrackDao = mockk()
    private val metadataEmbedder: MetadataEmbedder = mockk()
    private val albumArtCache: AlbumArtCache = mockk()
    private val backfillState: MetadataBackfillState = mockk(relaxUnitFun = true)

    private fun buildSubject(): MetadataBackfillWorker {
        val params = TestListenableWorkerBuilder<MetadataBackfillWorker>(context).buildWorkerParameters()
        return MetadataBackfillWorker(context, params, trackDao, metadataEmbedder, albumArtCache, backfillState)
    }

    @Test fun `empty library returns success without doing work`() = runTest {
        coEvery { trackDao.observeTracksNeedingEmbedCount() } returns flowOf(0)
        coEvery { trackDao.getTracksNeedingEmbed(any(), any()) } returns emptyList()

        val result = buildSubject().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify { backfillState.markStarted(0) }
        coVerify { backfillState.markFinished() }
    }

    @Test fun `each row gets a setMetadataEmbeddedAt stamp on success`() = runTest {
        val sampleFile = File.createTempFile("track", ".opus").apply { writeBytes(ByteArray(64)) }
        val rows = listOf(
            stubEntity(id = 1, filePath = sampleFile.absolutePath),
            stubEntity(id = 2, filePath = sampleFile.absolutePath),
        )
        coEvery { trackDao.observeTracksNeedingEmbedCount() } returns flowOf(2)
        coEvery { trackDao.getTracksNeedingEmbed(any(), any()) } returnsMany listOf(rows, emptyList())
        coEvery { albumArtCache.resolveArt(any()) } returns null
        coEvery { metadataEmbedder.embedMetadata(any(), any(), any()) } answers { firstArg() }

        buildSubject().doWork()

        coVerify { trackDao.setMetadataEmbeddedAt(eq(1L), match { it > 0L }) }
        coVerify { trackDao.setMetadataEmbeddedAt(eq(2L), match { it > 0L }) }
        sampleFile.delete()
    }

    @Test fun `SAF row stamps 0L and increments safSkipped`() = runTest {
        val rows = listOf(stubEntity(id = 5, filePath = "content://com.android.externalstorage.documents/blah"))
        coEvery { trackDao.observeTracksNeedingEmbedCount() } returns flowOf(1)
        coEvery { trackDao.getTracksNeedingEmbed(any(), any()) } returnsMany listOf(rows, emptyList())

        buildSubject().doWork()

        coVerify { trackDao.setMetadataEmbeddedAt(eq(5L), eq(0L)) }
        coVerify { backfillState.incrementSafSkipped() }
        coVerify(exactly = 0) { metadataEmbedder.embedMetadata(any(), any(), any()) }
    }

    @Test fun `missing file stamps 0L without incrementing safSkipped`() = runTest {
        val rows = listOf(stubEntity(id = 9, filePath = "/nope/missing.opus"))
        coEvery { trackDao.observeTracksNeedingEmbedCount() } returns flowOf(1)
        coEvery { trackDao.getTracksNeedingEmbed(any(), any()) } returnsMany listOf(rows, emptyList())

        buildSubject().doWork()

        coVerify { trackDao.setMetadataEmbeddedAt(eq(9L), eq(0L)) }
        coVerify(exactly = 0) { backfillState.incrementSafSkipped() }
    }
}
```

The `TestListenableWorkerBuilder` helper construction is standard WorkManager test boilerplate — mirror an existing worker test in the codebase (e.g., `LosslessRetryWorkerTest` if present).

- [ ] **Step 2: Verify the tests fail, then implement**

`data/download/src/main/kotlin/com/stash/data/download/backfill/MetadataBackfillWorker.kt`:

```kotlin
package com.stash.data.download.backfill

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.mapper.toDomain
import com.stash.data.download.files.AlbumArtCache
import com.stash.data.download.files.MetadataEmbedder
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.io.File

@HiltWorker
class MetadataBackfillWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val trackDao: TrackDao,
    private val metadataEmbedder: MetadataEmbedder,
    private val albumArtCache: AlbumArtCache,
    private val backfillState: MetadataBackfillState,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val total = trackDao.observeTracksNeedingEmbedCount().first()
        backfillState.markStarted(total)

        var processed = 0
        while (true) {
            // OFFSET is always 0: each row leaves the result set via
            // the metadata_embedded_at stamp (success OR 0L sentinel).
            val batch = trackDao.getTracksNeedingEmbed(BATCH_SIZE, offset = 0)
            if (batch.isEmpty()) break
            for (entity in batch) {
                processEntity(entity)
                processed++
                backfillState.publishProgress(processed, total)
            }
        }

        backfillState.markFinished()
        return Result.success()
    }

    private suspend fun processEntity(entity: com.stash.core.data.db.entity.TrackEntity) {
        val track = entity.toDomain()
        val pathString = track.filePath ?: return markFailed(track.id)

        // SAF check MUST precede File() construction. A `content://`
        // URI passed to File(...) gives `File.exists() == false`, which
        // would collapse SAF rows into the "file missing" branch and
        // break the safSkipped counter the Home banner reads.
        if (pathString.startsWith("content://")) {
            backfillState.incrementSafSkipped()
            return markFailed(track.id)
        }

        val file = File(pathString)
        if (!file.exists()) return markFailed(track.id)

        val art = runCatching { albumArtCache.resolveArt(track) }.getOrNull()
        val embedded = runCatching { metadataEmbedder.embedMetadata(file, track, art) }.isSuccess
        if (embedded) {
            trackDao.setMetadataEmbeddedAt(track.id, System.currentTimeMillis())
        } else {
            markFailed(track.id)
        }
    }

    private suspend fun markFailed(trackId: Long) {
        runCatching { trackDao.setMetadataEmbeddedAt(trackId, 0L) }
            .onFailure { Log.w(TAG, "markFailed update failed for $trackId: ${it.message}") }
    }

    companion object {
        private const val TAG = "MetadataBackfillWorker"
        private const val BATCH_SIZE = 50
        const val UNIQUE_WORK_NAME = "metadata_backfill"
    }
}
```

- [ ] **Step 3: Verify the tests pass, then commit**

```bash
./gradlew :data:download:testDebugUnitTest --tests "*MetadataBackfillWorkerTest*"
git add data/download/src/main/kotlin/com/stash/data/download/backfill/MetadataBackfillWorker.kt \
        data/download/src/test/kotlin/com/stash/data/download/backfill/MetadataBackfillWorkerTest.kt
git commit -m "$(cat <<'EOF'
feat(download): MetadataBackfillWorker drains the un-tagged library

Once-per-version one-shot worker. Walks tracks WHERE
metadata_embedded_at IS NULL in batches of 50 (OFFSET always 0 —
stamped rows drop out of the result set), embeds clean tags + art
via MetadataEmbedder, stamps timestamp on success or 0L sentinel on
irrecoverable failure (file missing, SAF row, ffmpeg threw). SAF
rows additionally increment safSkipped so the Home banner can
surface that detail to the user.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 12: `MetadataBackfillScheduler` + StashApp wiring

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/backfill/MetadataBackfillScheduler.kt`
- Modify: `app/src/main/kotlin/com/stash/app/StashApplication.kt`

- [ ] **Step 1: Implement the scheduler**

**WorkManager is not Hilt-injectable in this project.** Existing usages in `StashApplication.kt` (lines ~240, 276, 654, 677) all call `WorkManager.getInstance(applicationContext)` directly. Follow that pattern — inject `Context`, resolve WorkManager from it.

```kotlin
@Singleton
class MetadataBackfillScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val versionTracker: BackfillVersionTracker,
) {
    suspend fun scheduleIfNeeded() {
        if (!versionTracker.shouldRunForCurrentVersion()) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            MetadataBackfillWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<MetadataBackfillWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build(),
        )
        versionTracker.markEnqueuedForCurrentVersion()
    }
}
```

- [ ] **Step 2: Wire it into `StashApplication.onCreate`**

Open `app/src/main/kotlin/com/stash/app/StashApplication.kt`. Add `@Inject lateinit var metadataBackfillScheduler: MetadataBackfillScheduler`. Find the existing `applicationScope` (which exists as `CoroutineScope(SupervisorJob() + Dispatchers.IO)` — confirm by grepping). In `onCreate()` after any existing Hilt-dependent calls, add:

```kotlin
// Auto-enqueue the v0.9.35 metadata backfill once per version.
// Idempotent: re-installing the same binary does not re-enqueue.
applicationScope.launch { metadataBackfillScheduler.scheduleIfNeeded() }
```

If `applicationScope` is named differently in the current source, grep for `CoroutineScope(SupervisorJob` in the file and use that name. Surface to the user if neither exists.

- [ ] **Step 3: Build + installDebug to flush the Hilt graph**

```bash
./gradlew :app:installDebug
```

Expected: APK installs on connected device. Check `adb logcat -d | grep -i 'metadata_backfill\|MetadataBackfill'` after first launch — you should see no crash, even if the library is small (the worker still runs once with zero rows and finishes cleanly).

- [ ] **Step 4: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/backfill/MetadataBackfillScheduler.kt \
        app/src/main/kotlin/com/stash/app/StashApplication.kt
git commit -m "$(cat <<'EOF'
feat(app): auto-enqueue MetadataBackfillWorker on app launch

StashApplication.onCreate kicks the scheduler once per binary version via
BackfillVersionTracker. WorkManager is the queue; CONNECTED is the
only constraint (no charging required — ffmpeg -c copy is mux-only,
no transcode). Expedited with non-expedited fallback so user-
visible progress kicks in immediately when the WorkManager quota
allows.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Phase 6 — Home banner

Phase end state: while the worker is processing, the Home screen renders a "Re-tagging library — 47/612" banner under the existing Waiting-for-lossless slot; on completion it shows "Library re-tagged" for 2 seconds and disappears. SAF-skip footnote appears in the finished copy when applicable.

## Task 13: `MetadataBackfillBannerState` + composable + HomeViewModel wire

**Files:**
- Create: `feature/home/src/main/kotlin/com/stash/feature/home/banner/MetadataBackfillBannerState.kt`
- Create: `feature/home/src/main/kotlin/com/stash/feature/home/banner/MetadataBackfillBanner.kt`
- Create: `feature/home/src/test/kotlin/com/stash/feature/home/banner/MetadataBackfillBannerStateTest.kt`
- Modify: `feature/home/src/main/kotlin/com/stash/feature/home/HomeUiState.kt`
- Modify: `feature/home/src/main/kotlin/com/stash/feature/home/HomeViewModel.kt`
- Modify: `feature/home/src/main/kotlin/com/stash/feature/home/HomeScreen.kt`

- [ ] **Step 1: Write the failing test for the pure mapper**

`feature/home/src/test/kotlin/com/stash/feature/home/banner/MetadataBackfillBannerStateTest.kt`:

```kotlin
package com.stash.feature.home.banner

import com.stash.data.download.backfill.MetadataBackfillState
import com.stash.data.download.backfill.MetadataBackfillState.BackfillSnapshot
import com.stash.data.download.backfill.MetadataBackfillState.State
import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataBackfillBannerStateTest {

    @Test fun `IDLE maps to Hidden`() {
        val state = metadataBackfillBannerStateFor(snapshot(state = State.IDLE))
        assertEquals(MetadataBackfillBannerState.Hidden, state)
    }

    @Test fun `RUNNING with zero total maps to Hidden`() {
        // No library = no banner; do not render "Re-tagging library 0/0"
        val state = metadataBackfillBannerStateFor(snapshot(state = State.RUNNING, total = 0))
        assertEquals(MetadataBackfillBannerState.Hidden, state)
    }

    @Test fun `RUNNING with non-zero total maps to Running`() {
        val state = metadataBackfillBannerStateFor(snapshot(state = State.RUNNING, processed = 23, total = 50))
        assertEquals(MetadataBackfillBannerState.Running(processed = 23, total = 50), state)
    }

    @Test fun `FINISHED maps to Finished`() {
        val state = metadataBackfillBannerStateFor(
            snapshot(state = State.FINISHED, processed = 50, total = 50, safSkipped = 3)
        )
        assertEquals(MetadataBackfillBannerState.Finished(total = 50, safSkipped = 3), state)
    }

    private fun snapshot(
        state: State = State.IDLE,
        processed: Int = 0,
        total: Int = 0,
        safSkipped: Int = 0,
    ) = BackfillSnapshot(state, processed, total, safSkipped, finishedAt = null)
}
```

- [ ] **Step 2: Verify it fails**

```bash
./gradlew :feature:home:testDebugUnitTest --tests "*MetadataBackfillBannerStateTest*"
```

Expected: FAIL.

- [ ] **Step 3: Implement the sealed type + pure mapper**

`feature/home/src/main/kotlin/com/stash/feature/home/banner/MetadataBackfillBannerState.kt`:

```kotlin
package com.stash.feature.home.banner

import com.stash.data.download.backfill.MetadataBackfillState.BackfillSnapshot
import com.stash.data.download.backfill.MetadataBackfillState.State

/**
 * Discrete states for the Home "re-tagging library" banner. [Hidden]
 * is the dominant state in the steady-state install — only rendered
 * while the v0.9.35+ backfill worker is actively processing rows, and
 * for a short "Done" pulse after completion.
 */
sealed interface MetadataBackfillBannerState {
    data object Hidden : MetadataBackfillBannerState
    data class Running(val processed: Int, val total: Int) : MetadataBackfillBannerState
    data class Finished(val total: Int, val safSkipped: Int) : MetadataBackfillBannerState
}

/**
 * Pure mapping from MetadataBackfillState.BackfillSnapshot into the
 * banner sealed type. Extracted so HomeViewModel doesn't carry the
 * branching logic inline.
 */
fun metadataBackfillBannerStateFor(snapshot: BackfillSnapshot): MetadataBackfillBannerState =
    when (snapshot.state) {
        State.IDLE -> MetadataBackfillBannerState.Hidden
        State.RUNNING ->
            if (snapshot.total <= 0) MetadataBackfillBannerState.Hidden
            else MetadataBackfillBannerState.Running(snapshot.processed, snapshot.total)
        State.FINISHED ->
            MetadataBackfillBannerState.Finished(snapshot.total, snapshot.safSkipped)
    }
```

- [ ] **Step 4: Verify the test passes**

```bash
./gradlew :feature:home:testDebugUnitTest --tests "*MetadataBackfillBannerStateTest*"
```

Expected: PASS.

- [ ] **Step 5: Add the field to `HomeUiState`**

Open `feature/home/src/main/kotlin/com/stash/feature/home/HomeUiState.kt`. After the existing `waitingForLosslessBanner` field, add:

```kotlin
    /**
     * v0.9.35: state of the "re-tagging library" banner. Hidden in
     * the steady state — only renders while [MetadataBackfillWorker]
     * is actively processing rows, and for a 2-second "Done" pulse
     * after completion.
     */
    val metadataBackfillBanner: MetadataBackfillBannerState =
        MetadataBackfillBannerState.Hidden,
```

Import `MetadataBackfillBannerState` at the top of the file.

- [ ] **Step 6: Wire it into `HomeViewModel`**

Open `feature/home/src/main/kotlin/com/stash/feature/home/HomeViewModel.kt`. Add `private val metadataBackfillState: MetadataBackfillState` to the constructor.

Find the existing state assembly (the `combine(...)` that produces `HomeUiState`). Add the new flow alongside the existing `bannerStateFlow`:

```kotlin
val metadataBackfillBannerFlow: Flow<MetadataBackfillBannerState> =
    metadataBackfillState.snapshot.map { metadataBackfillBannerStateFor(it) }
```

Then include it in the combine. The exact merge mechanics depend on the existing assembly — match how `waitingForLosslessBanner` is combined in. If the assembly uses `combine` with a large lambda, add this as another input; if it uses `MutableStateFlow.update`, follow that pattern.

Also add an action method called by the screen's `LaunchedEffect` when the Done pulse expires:

```kotlin
fun onMetadataBackfillFinishedAcknowledged() {
    viewModelScope.launch { metadataBackfillState.markFinishedAcknowledged() }
}
```

- [ ] **Step 7: Implement the composable**

`feature/home/src/main/kotlin/com/stash/feature/home/banner/MetadataBackfillBanner.kt`:

```kotlin
package com.stash.feature.home.banner

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun MetadataBackfillBanner(
    state: MetadataBackfillBannerState,
    onFinishedAcknowledged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        MetadataBackfillBannerState.Hidden -> Unit
        is MetadataBackfillBannerState.Running -> {
            Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 1.dp) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "Re-tagging library — ${state.processed}/${state.total}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "Adding album art and metadata to existing files",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LinearProgressIndicator(
                        progress = { state.processed.toFloat() / state.total.toFloat() },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
        }
        is MetadataBackfillBannerState.Finished -> {
            LaunchedEffect(state) {
                delay(2_000)
                onFinishedAcknowledged()
            }
            Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 1.dp) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "Library re-tagged",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (state.safSkipped > 0) {
                        Text(
                            text = "${state.safSkipped} tracks on external storage will be tagged on next download",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
```

The actual visual treatment should match Stash's `GlassCard` / Material3-extended design system — see `feedback_stash_design_system` memory. The above sketch uses plain Material3 components; **adapt the wrapper to whatever `WaitingForLosslessBanner.kt` does** so the two banners are visually consistent. Open `feature/home/src/main/kotlin/com/stash/feature/home/banner/WaitingForLosslessBanner.kt` and mirror its container shape, padding, colour tokens.

- [ ] **Step 8: Render the banner in `HomeScreen.kt`**

Find where `WaitingForLosslessBanner` is rendered (search `WaitingForLosslessBanner(` in `HomeScreen.kt`) and add the new banner immediately below it:

```kotlin
MetadataBackfillBanner(
    state = uiState.metadataBackfillBanner,
    onFinishedAcknowledged = { viewModel.onMetadataBackfillFinishedAcknowledged() },
)
```

- [ ] **Step 9: Build + installDebug + manual verify**

```bash
./gradlew :app:installDebug
```

Expected: BUILD SUCCESSFUL + APK installs.

Manual verification — **after running this, hand the manual matrix below back to the user**:

1. Open the app. On first launch after this commit, observe the banner. With a small library it may flash too fast to read — that's expected (worker drains in <1s for an empty/small library).
2. If you have a pre-v0.9.35 snapshot library available (e.g., from a backup APK on a separate device), install the new APK over it and verify the banner shows real progress as the worker drains.
3. Verify no crash if `metadataBackfillBanner` is `Hidden` (the default path).

- [ ] **Step 10: Commit**

```bash
git add feature/home/src/main/kotlin/com/stash/feature/home/banner/MetadataBackfillBannerState.kt \
        feature/home/src/main/kotlin/com/stash/feature/home/banner/MetadataBackfillBanner.kt \
        feature/home/src/test/kotlin/com/stash/feature/home/banner/MetadataBackfillBannerStateTest.kt \
        feature/home/src/main/kotlin/com/stash/feature/home/HomeUiState.kt \
        feature/home/src/main/kotlin/com/stash/feature/home/HomeViewModel.kt \
        feature/home/src/main/kotlin/com/stash/feature/home/HomeScreen.kt
git commit -m "$(cat <<'EOF'
feat(home): Re-tagging library banner — Running + Finished states

New independent HomeUiState field metadataBackfillBanner driven by
MetadataBackfillState.snapshot through a pure mapper. Renders under
the existing waiting-for-lossless banner slot. 2-second "Done"
pulse on Finished, then markFinishedAcknowledged() flips the state
back to IDLE and the banner hides. SAF-skip footnote appears in
the Finished copy when safSkipped > 0.

Visually mirrors WaitingForLosslessBanner's container so the two
banners look like siblings on screen.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Phase 7 — Integration test + ship prep

Phase end state: on-device integration test verifies opus/m4a/flac tag + art round-trip; version bumped; user can run the manual smoke matrix; the worktree is ready for the user to merge + tag.

## Task 14: Integration test — opus / m4a / flac round-trip

**Files:**
- Create: `data/download/src/androidTest/kotlin/com/stash/data/download/MetadataEmbeddingIntegrationTest.kt`
- Create: `data/download/src/androidTest/assets/sample_opus.opus`
- Create: `data/download/src/androidTest/assets/sample_m4a.m4a`
- Create: `data/download/src/androidTest/assets/sample_flac.flac`
- Create: `data/download/src/androidTest/assets/sample_art.jpg`

- [ ] **Step 1: Source the fixture audio files**

Use any short (≤2 s) public-domain or self-recorded audio samples. ffmpeg one-liner to generate from a 1 kHz sine wave:

```bash
mkdir -p data/download/src/androidTest/assets
ffmpeg -f lavfi -i "sine=frequency=1000:duration=2" -c:a libopus -b:a 96k \
    data/download/src/androidTest/assets/sample_opus.opus
ffmpeg -f lavfi -i "sine=frequency=1000:duration=2" -c:a aac -b:a 96k \
    data/download/src/androidTest/assets/sample_m4a.m4a
ffmpeg -f lavfi -i "sine=frequency=1000:duration=2" -c:a flac \
    data/download/src/androidTest/assets/sample_flac.flac
```

For the JPEG, a tiny solid-colour image is fine:

```bash
ffmpeg -f lavfi -i "color=red:size=100x100" -frames:v 1 \
    data/download/src/androidTest/assets/sample_art.jpg
```

These files should be tiny (a few KB each); commit them with the test.

- [ ] **Step 2: Write the integration test**

`data/download/src/androidTest/kotlin/com/stash/data/download/MetadataEmbeddingIntegrationTest.kt`:

```kotlin
package com.stash.data.download

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.stash.core.common.AppVersionProvider
import com.stash.core.model.Track
import com.stash.data.download.files.FileOrganizer
import com.stash.data.download.files.MetadataEmbedder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MetadataEmbeddingIntegrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val versionProvider = object : AppVersionProvider {
        override val versionName: String = "0.9.35-test"
        override val versionCode: Int = 71
    }
    // Task 5 drops FileOrganizer from MetadataEmbedder's constructor
    // because the embed pass never used it. Only `context` and
    // `appVersion` are needed here.
    private val embedder = MetadataEmbedder(context, versionProvider)

    private lateinit var artFile: File

    @Before fun setUp() {
        artFile = copyAssetToCache("sample_art.jpg")
    }

    @Test fun embedsTagsAndArtIntoOpus() = runBlocking {
        verifyRoundTrip("sample_opus.opus")
    }

    @Test fun embedsTagsAndArtIntoM4a() = runBlocking {
        verifyRoundTrip("sample_m4a.m4a")
    }

    @Test fun embedsTagsAndArtIntoFlac() = runBlocking {
        verifyRoundTrip("sample_flac.flac")
    }

    private suspend fun verifyRoundTrip(asset: String) {
        val source = copyAssetToCache(asset)
        val track = Track(
            id = 1, title = "Test Title", artist = "Test Artist",
            albumArtist = "Test AlbumArtist", album = "Test Album",
            isrc = "USTEST0000001",
        )

        val result = embedder.embedMetadata(source, track, artFile)

        MediaMetadataRetriever().apply {
            setDataSource(result.absolutePath)
            assertEquals("Test Title", extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE))
            assertEquals("Test Artist", extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST))
            assertEquals("Test Album", extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM))
            assertEquals("Test AlbumArtist", extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST))
            val art = embeddedPicture
            assertNotNull("Expected an embedded picture for $asset", art)
            assertTrue("Embedded picture should be non-empty for $asset", art!!.isNotEmpty())
            release()
        }
    }

    private fun copyAssetToCache(name: String): File {
        val target = File(context.cacheDir, "intgtest_$name")
        context.assets.open(name).use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        return target
    }
}
```

No `FileOrganizer` stub needed — Task 5 removed that parameter from `MetadataEmbedder`'s constructor.

- [ ] **Step 3: Run the integration test on a connected device**

```bash
./gradlew :data:download:connectedAndroidTest --tests "*MetadataEmbeddingIntegrationTest*"
```

Expected: BUILD SUCCESSFUL + 3 tests pass.

If the Opus test fails with "embedded picture is null", that confirms the spec's open-question about `attached_pic` disposition on Opus. The fallback if Opus genuinely doesn't carry an attached picture in this ffmpeg version: pass `-metadata METADATA_BLOCK_PICTURE=<base64>` for opus only. **Surface this to the user before patching** — don't silently switch strategies.

- [ ] **Step 4: Commit**

```bash
git add data/download/src/androidTest/kotlin/com/stash/data/download/MetadataEmbeddingIntegrationTest.kt \
        data/download/src/androidTest/assets/sample_opus.opus \
        data/download/src/androidTest/assets/sample_m4a.m4a \
        data/download/src/androidTest/assets/sample_flac.flac \
        data/download/src/androidTest/assets/sample_art.jpg
git commit -m "$(cat <<'EOF'
test(download): on-device tag + art round-trip across opus/m4a/flac

Asserts that MetadataEmbedder.embedMetadata produces a file readable
by MediaMetadataRetriever with TITLE/ARTIST/ALBUM/ALBUMARTIST tags
matching the Track input and a non-empty embedded picture. Runs
against the actual ffmpeg .so bundled by youtubedl-android, so it's
the only place we verify the Opus attached_pic + Vorbis-comment
casing claims from the spec hold on a real Android runtime.

Fixtures are 2-second 1 kHz sine waves; total <50 KB.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 15: Version bump + full test sweep + manual smoke matrix + commit

**Files:**
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Bump versionCode + versionName**

Read the current values from `app/build.gradle.kts` (they may have drifted since the spec was written):

```bash
grep -n "versionCode\|versionName" app/build.gradle.kts | head -5
```

Bump `versionCode` by +1 and set `versionName = "0.9.35"`. Whatever the previous value was, +1.

- [ ] **Step 2: Run the full test suite for all changed modules**

```bash
./gradlew \
  :core:common:testDebugUnitTest \
  :core:model:testDebugUnitTest \
  :core:data:testDebugUnitTest \
  :data:download:testDebugUnitTest \
  :feature:home:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL. Pre-existing master failures (per the conventions section) are not introduced by this branch; they should fail in the same shape as before.

- [ ] **Step 3: Run instrumentation tests on connected device**

```bash
./gradlew \
  :core:data:connectedAndroidTest \
  :data:download:connectedAndroidTest
```

Expected: BUILD SUCCESSFUL. Specifically `Migration26To27Test` and the three `MetadataEmbeddingIntegrationTest` cases must pass.

- [ ] **Step 4: Build + installDebug**

```bash
./gradlew :app:installDebug
```

Expected: APK installs on the connected device.

- [ ] **Step 5: Document the manual smoke matrix for the user**

DO NOT execute this step yourself. Hand the matrix to the user when you mark Task 15 ready for review.

```
Manual smoke test — v0.9.35 metadata embedding

Pre-conditions:
  - A library that includes at least one Spotify-synced track (any
    non-FLAC track is fine), one lossless FLAC track, and one
    locally-imported track.
  - Plex (or any equivalent third-party music player) accessible via
    USB or shared storage.
  - Optionally: a second device with a pre-v0.9.35 Stash install
    (for end-to-end backfill verification on a real legacy library).

1. NEW DOWNLOAD — yt-dlp path
   Sync a Spotify Daily Mix. Wait for any new track to finish
   downloading. Pull the file off device (adb pull or USB MTP).
   Open in Plex / Foobar / any tag-aware reader. Verify:
     - TITLE matches the Spotify title (not "Artist - Song [Official Music Video]")
     - ARTIST matches the Spotify artist
     - ALBUMARTIST is present (Plex groups correctly under that artist)
     - ALBUM is the Spotify album
     - Cover art is embedded and renders

2. NEW DOWNLOAD — lossless path
   Enable lossless. Sync any track that has a Qobuz match. Pull the
   FLAC file. Open in same readers. Verify same fields as above.
   This regression-checks the lossless path that was always tagged
   but never carried art.

3. BACKFILL — fresh install over existing library
   Use the legacy-install device. After install, observe the Home
   banner: "Re-tagging library — N/M". Wait for it to finish (it
   may take minutes for a real library). Verify "Library re-tagged"
   appears for ~2 seconds and disappears. If you have SAF-stored
   tracks: verify the footnote "X tracks on external storage will
   be tagged on next download" appears.

4. BACKFILL — re-install same version
   Uninstall, reinstall without bumping the version. Observe: no
   banner. The version-tracker should have memory of the prior
   enqueue (DataStore survives via app data; clears on full uninstall
   though, so this step may show the banner again — that's
   expected on full uninstall, not on simple re-install).

5. SETTINGS / REGRESSIONS
   Hit every settings screen. Play tracks from Library, Recently
   Added, and Now Playing. No regressions in the existing UI.
   Check the Now Playing flag-icon dialog still works (Find in FLAC
   from v0.9.18, find-a-better-match, delete, etc.).

If any test fails, stop and document — do NOT push or tag.
```

- [ ] **Step 6: Commit the version bump**

```bash
git add app/build.gradle.kts
git commit -m "$(cat <<'EOF'
chore: bump versionCode and versionName to 0.9.35

v0.9.35 — Metadata + album-art embedding. Every downloaded file
exits the pipeline with clean TITLE / ARTIST / ALBUMARTIST / ALBUM
/ ISRC / ENCODER tags and an embedded cover-art picture, sourced
from the Track row. A one-time backfill auto-runs on first launch
after upgrade to re-tag the existing library. Closes #76 (part 1)
and #90.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 7: STOP — do not push or tag**

The user gates push + tag. After this commit the branch is ready for the user to:

1. Run the manual smoke matrix from Step 5 above.
2. If it passes, merge to master:
   ```bash
   git -C /c/Users/theno/Projects/MP3APK fetch origin
   git -C /c/Users/theno/Projects/MP3APK checkout master
   git -C /c/Users/theno/Projects/MP3APK merge --ff-only feat/metadata-embedding
   ```
3. Tag + push:
   ```bash
   git tag -a v0.9.35 -m "v0.9.35 — Metadata + album-art embedding"
   git push origin master
   git push origin v0.9.35
   ```

The GitHub release workflow takes over from there.

---

## Notes for the executor

- **Always use `model: "opus"` for every Agent / subagent dispatch.** No exceptions on this project.
- **`:app:installDebug` after every UI-touching task — compile-pass alone isn't enough.** This catches Hilt-graph rejections and Compose runtime mismatches that the unit-test suite misses.
- **Pre-existing master test failures still apply.** If the test sweep at Task 15 fails on tests this plan never touched, those are likely the same long-standing failures noted in earlier release plans. Document them, don't try to fix them in this branch.
- **The `--embed-metadata` yt-dlp flag stays on.** It's a fallback layer behind our MetadataEmbedder pass, not a competitor. Do not remove it from `toYtDlpArgs` even though Task 5 updates the KDoc.
- **`MetadataEmbedder.companion.buildFfmpegArgs` is intentionally public on the companion** so unit tests can reach it without reflection. Don't tighten its visibility.
- **SAF skip is not "broken behaviour."** Per the spec, SAF files are explicitly out of scope for backfill. The Home banner's footnote informs the user; do not invent a new code path that copies SAF files into cache for in-place ffmpeg muxing — that's a much bigger scope.
- **If the Opus integration test fails on `embeddedPicture == null`**, surface to the user before patching. The fix may be Opus-specific (`METADATA_BLOCK_PICTURE` base64 instead of `attached_pic`), which the spec explicitly flags as an open question.
- **Per `feedback_worktree_local_properties`:** if you ever switch to a fresh worktree mid-execution, copy `local.properties` over manually. Without it, Last.fm-credential builds show "Not configured" in debug logs.
- **Repo is `rawnaldclark/Stash`, NOT `rawnaldclark/MP3APK`.** All issue references and the gh-cli `--repo` flag use `rawnaldclark/Stash`.
