# Tag-Seeded Mix Engine + Deep Cuts Fix — Implementation Plan (Plan 1 of 2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild Stash Mix discovery so a recipe's tracks come from explicit **tags** (genres + moods + era), ranked and personalized — and re-point **Deep Cuts** onto that engine so it stops re-surfacing already-downloaded library tracks.

**Architecture:** Extend the existing recipe pipeline (`StashMixRecipeEntity` → `StashMixRefreshWorker` → `StashDiscoveryWorker` → `materializeMix`). A new tag-pool builder fetches `tag.getTopTracks` per tag, ranks candidates by tag-overlap + user-artist affinity, applies a deep-cut sampling window and a sparse-pool relaxation ladder, then queues them through the existing discovery/stub/materialize path. Built-in **Deep Cuts** is retuned to `TAG_GRAPH` (seeding from the user's top genres) via the existing tuning-version mechanism.

**Tech Stack:** Kotlin, Room (v29→v30 migration), Hilt, WorkManager (`@HiltWorker`), Last.fm API (`LastFmApiClient`), Robolectric + MockK + JUnit assertions.

**Spec:** `docs/superpowers/specs/2026-05-28-customizable-stash-mixes-design.md`

**Scope note:** Backend-only. The Mix Builder UI, the genre catalog resource, recipe CRUD from the UI, and Home "Create mix"/per-mix actions are **Plan 2**. This plan delivers the Deep Cuts fix and a tag-seeded engine that custom recipes (created later) will use. Genres reach the engine as `includeTagsCsv` strings; moods as `mood_keys_csv` ids.

---

## File Structure

**New files:**
- `core/data/src/main/kotlin/com/stash/core/data/mix/MoodTagMap.kt` — versioned mood-id → Last.fm-tags map + `expand(moodKeysCsv)`.
- `core/data/src/main/kotlin/com/stash/core/data/mix/RecipeTagResolver.kt` — resolves a recipe's effective tag set (genres ∪ expanded moods ∪ era decade tag; falls back to `computeUserTopTags()` when empty).
- `core/data/src/main/kotlin/com/stash/core/data/mix/TagPoolBuilder.kt` — fetches `getTagTopTracks` per tag, dedupes, ranks by tag-overlap + user-artist affinity, applies the deep-cut window.
- Test files mirroring each (see tasks).

**Modified files:**
- `core/data/.../db/entity/StashMixRecipeEntity.kt` — add `moodKeysCsv`, `tagSampleDepth` columns.
- `core/data/.../db/StashDatabase.kt` — `version = 30`, add `MIGRATION_29_30`.
- `core/data/.../db/di/DatabaseModule.kt` — register `MIGRATION_29_30`.
- `core/data/.../db/dao/StashMixRecipeDao.kt` — extend `retuneBuiltin` with the two new columns.
- `core/data/.../mix/StashMixDefaults.kt` — Deep Cuts → `TAG_GRAPH` + `tagSampleDepth`.
- `core/data/.../sync/workers/StashMixRefreshWorker.kt` — add `tagPoolBuilder` ctor param; create a `TAG_GRAPH` fork in `queueDiscoveryForRecipe` → new `queueTagSeededDiscovery`.
- All `StashMixRefreshWorker*Test.kt` files that construct the worker — add `tagPoolBuilder` to their `newWorker` helper (compile fix).
- `app/.../StashApplication.kt` — bump `STASH_MIX_RECIPE_TUNING_VERSION` to 2; pass new columns to **both** `retuneBuiltin` call sites.

**Convention reminders:** Room `@Query` defaults aren't honoured by kapt — pass all params explicitly. Re-throw `CancellationException` before `catch (Exception)` in workers/suspend funcs. `core:data` tests use **MockK** + JUnit `assertEquals/assertTrue`; Robolectric DAO tests use `Room.inMemoryDatabaseBuilder(...).allowMainThreadQueries()`. Worker tests construct the worker from class-level mock fields and drive behavior through the real `doWork()` entry point (there is **no** `@VisibleForTesting` internal-call wrapper — don't invent one).

---

## Task 1: Schema — add `moodKeysCsv` + `tagSampleDepth`

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/entity/StashMixRecipeEntity.kt`
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt`
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/di/DatabaseModule.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/db/MixRecipeMigration29To30Test.kt`

- [ ] **Step 1: Write the failing migration test**

```kotlin
package com.stash.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MixRecipeMigration29To30Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate29To30_addsMoodKeysAndSampleDepthWithDefaults() {
        helper.createDatabase(TEST_DB, 29).apply {
            execSQL(
                "INSERT INTO stash_mix_recipes " +
                    "(id, name, include_tags_csv, exclude_tags_csv, affinity_bias, discovery_ratio, " +
                    " freshness_window_days, target_length, is_builtin, is_active, created_at, seed_strategy) " +
                    "VALUES (1, 'Deep Cuts', '', '', 0.0, 0.85, 90, 40, 1, 1, 0, 'TRACK_SIMILAR')"
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 30, true, StashDatabase.MIGRATION_29_30)
        db.query("SELECT mood_keys_csv, tag_sample_depth FROM stash_mix_recipes WHERE id = 1").use { c ->
            c.moveToFirst()
            assertEquals("", c.getString(0))
            assertEquals(0, c.getInt(1))
        }
        db.close()
    }

    private companion object { const val TEST_DB = "migration-test-db" }
}
```

- [ ] **Step 2: Run it — expect FAIL**

Run: `./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.db.MixRecipeMigration29To30Test"`
Expected: FAIL — `MIGRATION_29_30` unresolved. (`androidx.room.testing` is already on the `core:data` test classpath — `room-testing` 2.7.1. If a missing-class error appears instead, add `testImplementation(libs.androidx.room.testing)` to `core/data/build.gradle.kts` and re-run; the failure must then be the missing migration.)

- [ ] **Step 3: Add the two columns to the entity**

In `StashMixRecipeEntity.kt`, after the `seedStrategy` property add:
```kotlin
    /**
     * v0.9.40: comma-separated mood ids the user picked (e.g. "chill,focus").
     * Expanded to Last.fm tags at refresh time via [com.stash.core.data.mix.MoodTagMap].
     */
    @ColumnInfo(name = "mood_keys_csv", defaultValue = "")
    val moodKeysCsv: String = "",

    /**
     * v0.9.40: how far DOWN each tag's top-tracks ranking to start sampling
     * (skip the top N most-popular). 0 = from the top (custom mixes default);
     * Deep Cuts uses a positive value for its "deeper cuts" identity.
     */
    @ColumnInfo(name = "tag_sample_depth", defaultValue = "0")
    val tagSampleDepth: Int = 0,
```

- [ ] **Step 4: Bump DB version + add the migration**

In `StashDatabase.kt`: change `version = 29` to `version = 30`. In the `companion object`, after `MIGRATION_28_29` add:
```kotlin
        val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stash_mix_recipes ADD COLUMN mood_keys_csv TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE stash_mix_recipes ADD COLUMN tag_sample_depth INTEGER NOT NULL DEFAULT 0")
            }
        }
```

- [ ] **Step 5: Register the migration**

In `DatabaseModule.kt`, append after `StashDatabase.MIGRATION_28_29,` in the `.addMigrations(...)` call:
```kotlin
            StashDatabase.MIGRATION_29_30,
```

- [ ] **Step 6: Run the migration test — expect PASS**

Run: `./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.db.MixRecipeMigration29To30Test"`
Also confirm no regression: `--tests "com.stash.core.data.db.dao.StashMixRecipeDaoRetuneTest"`.
(The gradle test task regenerates the v30 schema JSON from the bumped `@Database version` via kapt before running. If you hit a "no schema found for version 30" error, the build hasn't regenerated yet — just re-run the test task.)

- [ ] **Step 7: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/db/entity/StashMixRecipeEntity.kt \
        core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt \
        core/data/src/main/kotlin/com/stash/core/data/db/di/DatabaseModule.kt \
        core/data/src/test/kotlin/com/stash/core/data/db/MixRecipeMigration29To30Test.kt
git commit -m "feat(mix): add mood_keys_csv + tag_sample_depth recipe columns (db v30)"
```

---

## Task 2: Mood → tag map

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/mix/MoodTagMap.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/mix/MoodTagMapTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.stash.core.data.mix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoodTagMapTest {
    @Test fun `expand resolves known moods to their tag sets`() {
        val tags = MoodTagMap.expand("chill,focus")
        assertTrue(tags.contains("chill"))
        assertTrue(tags.contains("ambient"))
        assertEquals(tags, tags.map { it.lowercase() })
        assertEquals(tags.size, tags.toSet().size)
    }
    @Test fun `expand skips unknown mood ids gracefully`() {
        assertEquals(emptyList<String>(), MoodTagMap.expand("not_a_mood"))
    }
    @Test fun `expand handles blank input`() {
        assertEquals(emptyList<String>(), MoodTagMap.expand(""))
        assertEquals(emptyList<String>(), MoodTagMap.expand("  "))
    }
}
```

- [ ] **Step 2: Run it — expect FAIL** (`MoodTagMap` unresolved)

Run: `./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.mix.MoodTagMapTest"`

- [ ] **Step 3: Implement `MoodTagMap`**

```kotlin
package com.stash.core.data.mix

/**
 * Curated mood → Last.fm-tag map. Each mood id resolves to a small set of
 * canonical tags the TAG_GRAPH engine seeds from. Bump [VERSION] on change.
 */
object MoodTagMap {
    const val VERSION = 1

    val MAP: Map<String, List<String>> = mapOf(
        "chill"      to listOf("chill", "chillout", "mellow", "downtempo", "ambient"),
        "energetic"  to listOf("energetic", "upbeat", "dance", "high energy"),
        "focus"      to listOf("instrumental", "ambient", "post-rock", "study", "concentration"),
        "party"      to listOf("party", "dance", "club", "feel good"),
        "melancholy" to listOf("melancholy", "sad", "moody", "atmospheric"),
        "romantic"   to listOf("romantic", "love", "soul", "smooth"),
    )

    val ALL_MOODS: List<String> = MAP.keys.toList()

    fun expand(moodKeysCsv: String): List<String> =
        moodKeysCsv.split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .flatMap { MAP[it].orEmpty() }
            .distinct()
}
```

- [ ] **Step 4: Run it — expect PASS**

Run: `./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.mix.MoodTagMapTest"`

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/mix/MoodTagMap.kt \
        core/data/src/test/kotlin/com/stash/core/data/mix/MoodTagMapTest.kt
git commit -m "feat(mix): curated mood->tag map with expand()"
```

---

## Task 3: Recipe tag resolver

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/mix/RecipeTagResolver.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/mix/RecipeTagResolverTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.stash.core.data.mix

import com.stash.core.data.db.entity.StashMixRecipeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeTagResolverTest {
    private fun recipe(include: String = "", moods: String = "", eraStart: Int? = null) =
        StashMixRecipeEntity(
            name = "X", includeTagsCsv = include, moodKeysCsv = moods,
            eraStartYear = eraStart, seedStrategy = "TAG_GRAPH",
        )

    @Test fun `unions genres moods and era decade tag`() {
        val tags = RecipeTagResolver.resolve(
            recipe(include = "jazz,soul", moods = "chill", eraStart = 1990),
            userTopTags = emptyList(),
        )
        assertTrue(tags.containsAll(listOf("jazz", "soul")))
        assertTrue(tags.contains("chill"))
        assertTrue(tags.contains("90s"))
        assertEquals(tags.size, tags.toSet().size)
    }
    @Test fun `falls back to user top tags when recipe has no explicit tags`() {
        assertEquals(
            listOf("indie", "rock"),
            RecipeTagResolver.resolve(recipe(), userTopTags = listOf("indie", "rock")),
        )
    }
    @Test fun `maps era start year to its decade tag`() {
        assertEquals("70s", RecipeTagResolver.decadeTag(1974))
        assertEquals("2000s", RecipeTagResolver.decadeTag(2003))
        assertEquals("2010s", RecipeTagResolver.decadeTag(2015))
    }
}
```

- [ ] **Step 2: Run it — expect FAIL**

Run: `./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.mix.RecipeTagResolverTest"`

- [ ] **Step 3: Implement `RecipeTagResolver`**

```kotlin
package com.stash.core.data.mix

import com.stash.core.data.db.entity.StashMixRecipeEntity

/** Resolves a recipe's effective Last.fm tag set for TAG_GRAPH seeding. */
object RecipeTagResolver {
    fun resolve(recipe: StashMixRecipeEntity, userTopTags: List<String>): List<String> {
        val genres = recipe.includeTagsCsv.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val moods = MoodTagMap.expand(recipe.moodKeysCsv)
        val era = recipe.eraStartYear?.let { listOf(decadeTag(it)) }.orEmpty()
        val explicit = (genres + moods + era).distinct()
        return explicit.ifEmpty { userTopTags.map { it.lowercase() }.distinct() }
    }

    /** 1974 -> "70s", 2003 -> "2000s". */
    fun decadeTag(year: Int): String =
        if (year < 2000) "${(year % 100) / 10}0s" else "${(year / 10) * 10}s"
}
```

- [ ] **Step 4: Run it — expect PASS**

Run: `./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.mix.RecipeTagResolverTest"`

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/mix/RecipeTagResolver.kt \
        core/data/src/test/kotlin/com/stash/core/data/mix/RecipeTagResolverTest.kt
git commit -m "feat(mix): RecipeTagResolver (genres+moods+era, user-top-tags fallback)"
```

---

## Task 4: Tag-pool builder (overlap ranking + deep-cut window + affinity)

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/mix/TagPoolBuilder.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/mix/TagPoolBuilderTest.kt`

Fetches `getTagTopTracks(tag, FETCH_LIMIT)` per tag, drops the top `sampleDepth` per tag (deep-cut window), dedupes by canonical `artist|title`, ranks by **tag-overlap → user-artist affinity → playcount**, returns `List<MixGenerator.DiscoveryCandidate>` in ranked order. `seedArtist` carries `"tag:<bestTag>"`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.stash.core.data.mix

import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmTopTrack
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TagPoolBuilderTest {
    private val api = mockk<LastFmApiClient>()
    private val builder = TagPoolBuilder(api)

    @Test fun `ranks tracks appearing under more tags first`() = runTest {
        coEvery { api.getTagTopTracks("jazz", any()) } returns Result.success(
            listOf(LastFmTopTrack("A", "Both", 100), LastFmTopTrack("B", "JazzOnly", 90)),
        )
        coEvery { api.getTagTopTracks("chill", any()) } returns Result.success(
            listOf(LastFmTopTrack("A", "Both", 80), LastFmTopTrack("C", "ChillOnly", 70)),
        )
        val pool = builder.build(listOf("jazz", "chill"), sampleDepth = 0, userTopArtists = emptySet())
        assertEquals("Both", pool.first().title)
        assertEquals(3, pool.size)
    }
    @Test fun `deep-cut window drops the top N of each tag`() = runTest {
        coEvery { api.getTagTopTracks("jazz", any()) } returns Result.success(
            (1..5).map { LastFmTopTrack("Art$it", "T$it", 100 - it) },
        )
        val pool = builder.build(listOf("jazz"), sampleDepth = 2, userTopArtists = emptySet())
        assertTrue(pool.none { it.title == "T1" || it.title == "T2" })
        assertEquals(3, pool.size)
    }
    @Test fun `boosts tracks by artists the user already favours`() = runTest {
        coEvery { api.getTagTopTracks("jazz", any()) } returns Result.success(
            listOf(LastFmTopTrack("Stranger", "S", 100), LastFmTopTrack("Fave", "F", 50)),
        )
        val pool = builder.build(listOf("jazz"), sampleDepth = 0, userTopArtists = setOf("fave"))
        assertEquals("F", pool.first().title)
    }
    @Test fun `empty tags yields empty pool without calling the api`() = runTest {
        assertEquals(emptyList<Any>(), builder.build(emptyList(), 0, emptySet()))
    }
}
```

- [ ] **Step 2: Run it — expect FAIL**

Run: `./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.mix.TagPoolBuilderTest"`

- [ ] **Step 3: Implement `TagPoolBuilder`**

```kotlin
package com.stash.core.data.mix

import com.stash.core.data.lastfm.LastFmApiClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay

/**
 * Builds a ranked discovery-candidate pool from a set of Last.fm tags.
 * Ranking: tag-overlap (most of the chosen tags) -> user-artist affinity ->
 * Last.fm playcount. The deep-cut window drops the top [sampleDepth] of each
 * tag so "Deep Cuts" surfaces lesser-known tracks instead of the same hits.
 */
@Singleton
class TagPoolBuilder @Inject constructor(
    private val apiClient: LastFmApiClient,
) {
    private data class Agg(
        val artist: String, val title: String,
        var overlap: Int, var bestPlaycount: Int, var bestTag: String,
    )

    suspend fun build(
        tags: List<String>,
        sampleDepth: Int,
        userTopArtists: Set<String>,
    ): List<MixGenerator.DiscoveryCandidate> {
        if (tags.isEmpty()) return emptyList()
        val byKey = LinkedHashMap<String, Agg>()
        for (tag in tags.take(MAX_TAGS)) {
            val tracks = apiClient.getTagTopTracks(tag, FETCH_LIMIT).getOrNull().orEmpty()
                .drop(sampleDepth.coerceAtLeast(0))
            for (t in tracks) {
                val key = "${t.artist.trim().lowercase()}|${t.title.trim().lowercase()}"
                val agg = byKey[key]
                if (agg == null) {
                    byKey[key] = Agg(t.artist, t.title, 1, t.playcount, tag)
                } else {
                    agg.overlap += 1
                    if (t.playcount > agg.bestPlaycount) agg.bestPlaycount = t.playcount
                }
            }
            delay(REQUEST_INTERVAL_MS)
        }
        return byKey.values
            .sortedWith(
                compareByDescending<Agg> { it.overlap }
                    .thenByDescending { it.artist.trim().lowercase() in userTopArtists }
                    .thenByDescending { it.bestPlaycount },
            )
            .map { MixGenerator.DiscoveryCandidate(it.artist, it.title, "tag:${it.bestTag}") }
    }

    private companion object {
        const val FETCH_LIMIT = 50
        const val MAX_TAGS = 10
        const val REQUEST_INTERVAL_MS = 220L
    }
}
```

- [ ] **Step 4: Run it — expect PASS**

Run: `./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.mix.TagPoolBuilderTest"`

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/mix/TagPoolBuilder.kt \
        core/data/src/test/kotlin/com/stash/core/data/mix/TagPoolBuilderTest.kt
git commit -m "feat(mix): TagPoolBuilder — overlap ranking, deep-cut window, artist affinity"
```

---

## Task 5: New TAG_GRAPH fork in the refresh worker + relaxation ladder

**Files:**
- Modify: `core/data/.../sync/workers/StashMixRefreshWorker.kt` (add `tagPoolBuilder` ctor param; add a `strategy == TAG_GRAPH` fork in `queueDiscoveryForRecipe`; add private `queueTagSeededDiscovery`)
- Modify (compile fix): **every** `StashMixRefreshWorker*Test.kt` whose `newWorker` helper constructs the worker positionally. Find them first: `Grep "StashMixRefreshWorker(" core/data/src/test`. Expect `StashMixRefreshWorkerSeedFilterTest.kt`, `StashMixRefreshWorkerDedupTest.kt`, `StashMixRefreshWorkerPerRecipeDedupTest.kt` (+ any others the grep returns). `StashDiscoveryWorker*Test` is **not** affected.
- Test: `core/data/.../sync/workers/StashMixRefreshWorkerTagGraphTest.kt` (new)

**Critical context — read `queueDiscoveryForRecipe` (`StashMixRefreshWorker.kt:541-649`) before editing.** It is **strategy-agnostic today**: it builds persona seed inputs, calls `seedGenerator.generate(strategy, …)` once, and tops off with a TAG_GRAPH `seedGenerator.generate(…)` when the filtered pool is below `MIN_DISCOVERY_POOL_AFTER_FILTER`. We are **creating a new early fork**: if `strategy == TAG_GRAPH`, route to a new `queueTagSeededDiscovery(recipe)` and `return` — seeding from the recipe's *own* tags. **Do not change the existing path for ARTIST_SIMILAR/TRACK_SIMILAR** — `StashMixRefreshWorkerSeedFilterTest` depends on its persona seeding and TAG_GRAPH top-off. Re-throw `CancellationException` before any `catch`.

- [ ] **Step 1: Compile-fix the existing worker tests**

In each affected test file, add (with the other class-level mock fields):
```kotlin
import com.stash.core.data.mix.TagPoolBuilder
// ...
private val tagPoolBuilder = TagPoolBuilder(lastFmApiClient)
```
and add `tagPoolBuilder,` to the positional `StashMixRefreshWorker(...)` call in `newWorker`, immediately **before** `trackMatcher,` (match the ctor order from Step 3). These ARTIST_SIMILAR tests never exercise the builder, so behavior is unchanged.

- [ ] **Step 2: Write the failing TAG_GRAPH test** (drive `doWork()`, mirroring `StashMixRefreshWorkerSeedFilterTest`'s harness)

```kotlin
package com.stash.core.data.sync.workers

// Mirror ALL imports + the class-level mock fields + newWorker(recipeId) helper from
// StashMixRefreshWorkerSeedFilterTest.kt, then add:
//   import com.stash.core.data.lastfm.LastFmTopTrack
//   import com.stash.core.data.mix.TagPoolBuilder
//   import io.mockk.coVerify
//   import io.mockk.neq
//   import org.junit.Assert.assertTrue
// Add field:  private val tagPoolBuilder = TagPoolBuilder(lastFmApiClient)
// Pass tagPoolBuilder into the StashMixRefreshWorker(...) construction (before trackMatcher).

class StashMixRefreshWorkerTagGraphTest {
    // ...harness fields mirrored from SeedFilterTest, incl. tagPoolBuilder...

    @Test fun `TAG_GRAPH recipe seeds from its own genre tags via TagPoolBuilder`() = runTest {
        val recipe = StashMixRecipeEntity(
            id = 7, name = "Late Night Jazz", includeTagsCsv = "jazz", moodKeysCsv = "",
            discoveryRatio = 0.85f, targetLength = 40, seedStrategy = "TAG_GRAPH",
            isBuiltin = false, isActive = true,
        )
        coEvery { recipeDao.getById(7L) } returns recipe
        coEvery { recipeDao.getActive() } returns listOf(recipe)
        coEvery { lastFmApiClient.getTagTopTracks("jazz", any()) } returns Result.success(
            (1..30).map { LastFmTopTrack("Artist$it", "Jazz$it", 100 - it) },
        )
        coEvery { lastFmApiClient.getTagTopTracks(neq("jazz"), any()) } returns Result.success(emptyList())
        coEvery { trackDao.getLibraryCanonicalKeys() } returns emptyList()
        coEvery { trackSkipEventDao.getEarlySkipBannedCanonicalKeys(any(), any(), any()) } returns emptyList()

        val queued = slot<List<MixGenerator.DiscoveryCandidate>>()
        coEvery { mixGenerator.queueDiscoveryCandidates(recipe, capture(queued)) } returns Unit

        newWorker(recipeId = 7L).doWork()

        assertTrue(queued.isCaptured)
        assertTrue(queued.captured.isNotEmpty())
        assertTrue(queued.captured.all { it.seedArtist.startsWith("tag:jazz") })
        // The TAG_GRAPH fork must NOT use the persona seedGenerator path.
        coVerify(exactly = 0) { seedGenerator.generate(any(), any(), any(), any(), any()) }
    }
}
```

- [ ] **Step 3: Run it — expect FAIL** (no fork yet; ctor lacks `tagPoolBuilder`)

Run: `./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.sync.workers.StashMixRefreshWorkerTagGraphTest"`

- [ ] **Step 4: Add the ctor param, the fork, and the new method**

In the `@AssistedInject` constructor add `private val tagPoolBuilder: TagPoolBuilder,` immediately before `trackMatcher,`. Add imports `com.stash.core.data.mix.RecipeTagResolver` and `com.stash.core.data.mix.TagPoolBuilder`.

In `queueDiscoveryForRecipe`, right after the `if (strategy == MixSeedStrategy.NONE) return` line, insert:
```kotlin
        // TAG_GRAPH recipes seed from their OWN resolved tags (genres+moods+era),
        // not the user's global top tags — the custom-mix + retuned-Deep-Cuts path.
        // Other strategies keep the persona-seeded path below, unchanged.
        if (strategy == MixSeedStrategy.TAG_GRAPH) {
            queueTagSeededDiscovery(recipe)
            return
        }
```

Add this new private method (e.g. directly below `queueDiscoveryForRecipe`):
```kotlin
    /**
     * Tag-seeded discovery for TAG_GRAPH recipes (custom mixes + Deep Cuts).
     * Pool comes from the recipe's resolved tags via [TagPoolBuilder] — NOT from
     * tracks similar to the user's library — which is what fixes Deep Cuts. Applies
     * the same library/skip pre-filter as the persona path, plus a relaxation
     * ladder (drop era -> drop moods) when the filtered pool is below the floor.
     */
    private suspend fun queueTagSeededDiscovery(recipe: StashMixRecipeEntity) {
        val userTopTags = mixGenerator.computeUserTopTags(limit = 10)
        val userTopArtists = trackDao.getTopArtistsByTrackCount(TOP_ARTISTS_LIMIT)
            .map { it.trim().lowercase() }.toHashSet()

        val libraryKeys = trackDao.getLibraryCanonicalKeys().toHashSet()
        val skipBannedKeys = trackSkipEventDao.getEarlySkipBannedCanonicalKeys(
            minSkips = DISCOVERY_SKIP_BAN_MIN_COUNT,
            sinceMs = System.currentTimeMillis() - DISCOVERY_SKIP_BAN_WINDOW_MS,
            maxPositionMs = DISCOVERY_SKIP_BAN_MAX_POSITION_MS,
        ).toHashSet()

        fun filterNew(c: List<MixGenerator.DiscoveryCandidate>) = c.filter {
            val key = canonicalKey(it.artist, it.title)
            key !in libraryKeys && key !in skipBannedKeys
        }

        val baseTags = RecipeTagResolver.resolve(recipe, userTopTags)
        val pool = filterNew(tagPoolBuilder.build(baseTags, recipe.tagSampleDepth, userTopArtists))
            .toMutableList()
        val seen = pool.mapTo(HashSet()) { canonicalKey(it.artist, it.title) }

        if (pool.size < MIN_DISCOVERY_POOL_AFTER_FILTER) {
            val rungs = buildList {
                if (recipe.eraStartYear != null) add(recipe.copy(eraStartYear = null, eraEndYear = null))
                if (recipe.moodKeysCsv.isNotEmpty()) {
                    add(recipe.copy(eraStartYear = null, eraEndYear = null, moodKeysCsv = ""))
                }
            }
            for (rung in rungs) {
                if (pool.size >= MIN_DISCOVERY_POOL_AFTER_FILTER) break
                val rungTags = RecipeTagResolver.resolve(rung, userTopTags)
                val more = filterNew(tagPoolBuilder.build(rungTags, rung.tagSampleDepth, userTopArtists))
                    .filter { seen.add(canonicalKey(it.artist, it.title)) }
                pool.addAll(more)
                Log.i(TAG, "'${recipe.name}': relaxed tag pool to ${pool.size} (dropped era/mood rung)")
            }
        }

        if (pool.isEmpty()) {
            Log.w(TAG, "'${recipe.name}': tag-seeded pool empty after filtering; skipping queue")
            return
        }
        Log.i(TAG, "'${recipe.name}': queueing ${pool.size} tag-seeded candidates")
        mixGenerator.queueDiscoveryCandidates(recipe, pool)
    }
```

- [ ] **Step 5: Run new + all existing worker tests — expect PASS**

Run: `./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.sync.workers.StashMixRefreshWorker*"`
Expected: new test PASS; `SeedFilter`/`Dedup`/`PerRecipeDedup` still PASS (ARTIST_SIMILAR recipes don't enter the fork; only their `newWorker` gained the param).

- [ ] **Step 6: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorker.kt \
        core/data/src/test/kotlin/com/stash/core/data/sync/workers/
git commit -m "feat(mix): TAG_GRAPH fork seeds from recipe tags + sparse-pool relaxation ladder"
```

---

## Task 6: Retune Deep Cuts onto the tag engine

**Files:**
- Modify: `core/data/.../mix/StashMixDefaults.kt` (Deep Cuts recipe)
- Modify: `core/data/.../db/dao/StashMixRecipeDao.kt` (`retuneBuiltin` query + params)
- Modify: `app/.../StashApplication.kt` (bump version; update **both** `retuneBuiltin` call sites)
- Test: `core/data/.../db/dao/StashMixRecipeDaoRetuneTest.kt` (extend)

- [ ] **Step 1: Write the failing test** (extend the existing retune test)

```kotlin
@Test fun `retuneBuiltin repoints Deep Cuts to TAG_GRAPH with sample depth`() = runTest {
    dao.insert(
        StashMixRecipeEntity(
            name = "Deep Cuts", seedStrategy = "TRACK_SIMILAR",
            discoveryRatio = 0.85f, freshnessWindowDays = 90, targetLength = 40,
            isBuiltin = true, tagSampleDepth = 0,
        )
    )
    dao.retuneBuiltin(
        name = "Deep Cuts", discoveryRatio = 0.85f, freshnessWindowDays = 90,
        targetLength = 40, affinityBias = 0.0f, seedStrategy = "TAG_GRAPH",
        moodKeysCsv = "", tagSampleDepth = 15,
    )
    val row = dao.getActive().first { it.name == "Deep Cuts" }
    assertEquals("TAG_GRAPH", row.seedStrategy)
    assertEquals(15, row.tagSampleDepth)
}
```

- [ ] **Step 2: Run it — expect FAIL** (`retuneBuiltin` lacks the new params)

Run: `./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.db.dao.StashMixRecipeDaoRetuneTest"`

- [ ] **Step 3: Extend `retuneBuiltin`**

```kotlin
@Query("""
    UPDATE stash_mix_recipes
    SET discovery_ratio = :discoveryRatio,
        freshness_window_days = :freshnessWindowDays,
        target_length = :targetLength,
        affinity_bias = :affinityBias,
        seed_strategy = :seedStrategy,
        mood_keys_csv = :moodKeysCsv,
        tag_sample_depth = :tagSampleDepth
    WHERE is_builtin = 1 AND name = :name
""")
suspend fun retuneBuiltin(
    name: String,
    discoveryRatio: Float,
    freshnessWindowDays: Int,
    targetLength: Int,
    affinityBias: Float,
    seedStrategy: String,
    moodKeysCsv: String,
    tagSampleDepth: Int,
): Int
```

> **Compile note — update ALL existing callers.** Extending `retuneBuiltin` to 8 required params (no Kotlin defaults) breaks every current caller. Besides the two `StashApplication.kt` sites (Step 4), the test file `StashMixRecipeDaoRetuneTest.kt` already has **four pre-existing `retuneBuiltin` call sites** that must each be updated or the test module won't compile: one named-arg call (~lines 54-61) and three positional calls (~line 85, ~line 86, ~line 105). Add `moodKeysCsv = "", tagSampleDepth = 0` to the named call and append `, "", 0` to each of the three positional calls (don't miss line 105).

- [ ] **Step 4: Update the Deep Cuts default + BOTH call sites + bump the version**

1. `StashMixDefaults.kt` — Deep Cuts entry: `seedStrategy = "TAG_GRAPH"`, add `tagSampleDepth = 15`, keep `includeTagsCsv = ""` (so it seeds from the user's top genres via the resolver fallback).
2. `StashApplication.kt` — set `STASH_MIX_RECIPE_TUNING_VERSION = 2`.
3. `StashApplication.maybeRetuneStashMixes()` (~line 577) — add `moodKeysCsv = recipe.moodKeysCsv, tagSampleDepth = recipe.tagSampleDepth,` to the `retuneBuiltin(...)` call.
4. **`StashApplication.maybeRetuneStashDiscover()` (~line 542)** — this is the *second* `retuneBuiltin` call site (for the "Stash Discover" recipe). It must also pass the new params or the app module won't compile. Add `moodKeysCsv = "", tagSampleDepth = 0,` (Stash Discover keeps depth 0). Do **not** bump `STASH_DISCOVER_TUNING_VERSION`.

- [ ] **Step 5: Run the retune test + full core:data suite — expect PASS**

Run: `./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.db.dao.StashMixRecipeDaoRetuneTest"`
Then: `./gradlew :core:data:testDebugUnitTest` (a single pre-existing unrelated failure — `TrackDaoStreamableTest > getByPlaylist excludes unavailable even when streamable true` — may appear; confirm it's the *only* failure and predates this work).

- [ ] **Step 6: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/mix/StashMixDefaults.kt \
        core/data/src/main/kotlin/com/stash/core/data/db/dao/StashMixRecipeDao.kt \
        app/src/main/kotlin/com/stash/app/StashApplication.kt \
        core/data/src/test/kotlin/com/stash/core/data/db/dao/StashMixRecipeDaoRetuneTest.kt
git commit -m "feat(mix): retune Deep Cuts to TAG_GRAPH (deep-cut depth 15), tuning v2"
```

---

## Task 7: Deep Cuts regression test (end-to-end seeding shape)

**Files:**
- Test: `core/data/.../sync/workers/StashMixRefreshWorkerDeepCutsTest.kt` (new — mirror the SeedFilter harness, incl. the `tagPoolBuilder` field, drive `doWork()`)

Proves the fix: a Deep-Cuts-shaped recipe (`TAG_GRAPH`, empty `includeTagsCsv`, `tagSampleDepth = 15`) seeds from the user's **top genres** (via `computeUserTopTags`) and produces **fresh tag-sourced candidates**, never touching the old `TRACK_SIMILAR` similar-tracks path.

- [ ] **Step 1: Write the test** (drive `doWork()`)

```kotlin
@Test fun `Deep Cuts (TAG_GRAPH, no explicit tags) seeds from user top genres, depth 15`() = runTest {
    val recipe = StashMixRecipeEntity(
        id = 4, name = "Deep Cuts", includeTagsCsv = "", moodKeysCsv = "",
        seedStrategy = "TAG_GRAPH", discoveryRatio = 0.85f, targetLength = 40,
        freshnessWindowDays = 90, tagSampleDepth = 15, isBuiltin = true, isActive = true,
    )
    coEvery { recipeDao.getById(4L) } returns recipe
    coEvery { recipeDao.getActive() } returns listOf(recipe)
    coEvery { mixGenerator.computeUserTopTags(any()) } returns listOf("shoegaze")
    coEvery { lastFmApiClient.getTagTopTracks("shoegaze", any()) } returns Result.success(
        (1..30).map { LastFmTopTrack("Band$it", "Song$it", 100 - it) },
    )
    coEvery { lastFmApiClient.getTagTopTracks(neq("shoegaze"), any()) } returns Result.success(emptyList())
    coEvery { trackDao.getLibraryCanonicalKeys() } returns emptyList()
    coEvery { trackSkipEventDao.getEarlySkipBannedCanonicalKeys(any(), any(), any()) } returns emptyList()

    val queued = slot<List<MixGenerator.DiscoveryCandidate>>()
    coEvery { mixGenerator.queueDiscoveryCandidates(recipe, capture(queued)) } returns Unit

    newWorker(recipeId = 4L).doWork()

    assertTrue(queued.isCaptured)
    assertTrue(queued.captured.isNotEmpty())
    assertTrue(queued.captured.all { it.seedArtist.startsWith("tag:shoegaze") })
    assertEquals(15, queued.captured.size) // depth 15 dropped the top 15 of 30
    coVerify(exactly = 0) { lastFmApiClient.getSimilarTracks(any(), any(), any()) }
}
```
(Note: `mixGenerator` is a relaxed mock in the harness; `coEvery { mixGenerator.computeUserTopTags(any()) }` overrides its default for this test.)

- [ ] **Step 2: Run it — expect PASS** (the engine from Tasks 5–6 already supports this)

Run: `./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.sync.workers.StashMixRefreshWorkerDeepCutsTest"`
If candidate count is wrong, re-check the `sampleDepth` drop in `TagPoolBuilder`.

- [ ] **Step 3: Commit**

```bash
git add core/data/src/test/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorkerDeepCutsTest.kt
git commit -m "test(mix): Deep Cuts regression — seeds fresh tag candidates, not library-similar"
```

---

## Task 8: Build, install, on-device verification

**Files:** none (verification only).

- [ ] **Step 1: Full debug build + unit tests**

Run: `./gradlew :core:data:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL; only the known pre-existing `TrackDaoStreamableTest` failure, if any.

- [ ] **Step 2: Install on device** (per project convention — compile-pass isn't enough)

Reconnect the Pixel 6 Pro (`adb devices`), then: `./gradlew :app:installDebug`.

- [ ] **Step 3: Trigger Deep Cuts refresh + verify fresh stream-only tracks**

Drive via adb yourself (don't ask the user): launch the app, open Deep Cuts, tap Refresh, wait for the discovery worker, then inspect the DB:
```bash
adb exec-out run-as com.stash.app.debug cat databases/stash.db > /tmp/stash.db   # + -wal/-shm
# query with python3 sqlite3:
#   SELECT SUM(t.is_downloaded) downloaded,
#          SUM(CASE WHEN t.is_downloaded=0 AND t.is_streamable=1 THEN 1 ELSE 0 END) streamOnly
#   FROM playlists p JOIN playlist_tracks pt ON pt.playlist_id=p.id
#   JOIN tracks t ON t.id=pt.track_id
#   WHERE p.name='Deep Cuts' AND pt.removed_at IS NULL;
```
Expected after refresh: `streamOnly > 0`, not 100% downloaded. Caveat: the debug app needs Last.fm configured + listening history for `computeUserTopTags` to return genres; if the debug DB was freshly cleared, verify on a build with Last.fm connected or seed `listening_events`. Document what was observed.

- [ ] **Step 4: Commit any verification notes/fixtures** (if added)

```bash
git add -A && git commit -m "chore(mix): on-device verification notes for Deep Cuts fix"
```

---

## Done criteria (Plan 1)

- DB migrates 29→30 cleanly; recipes carry `moodKeysCsv` + `tagSampleDepth`.
- TAG_GRAPH recipes seed from resolved tags (genres+moods+era) with overlap ranking, deep-cut window, artist-affinity boost, and a sparse-pool relaxation ladder — via a new fork that leaves ARTIST_SIMILAR/TRACK_SIMILAR untouched.
- Deep Cuts is `TAG_GRAPH` (depth 15, seeds user top genres) after the v2 tuning bump; the regression test proves it produces fresh tag candidates, not library-similar ones; both `retuneBuiltin` call sites compile.
- All new unit tests pass; existing mix/worker tests still pass; on-device Deep Cuts refresh shows stream-only tracks.

**Next:** Plan 2 — Mix Builder UI (genre catalog resource + grid, mood emblems, builder ViewModel/screen, navigation, recipe CRUD repository, Home "Create mix" tile + per-mix edit/delete/pin, on-demand refresh wiring).
