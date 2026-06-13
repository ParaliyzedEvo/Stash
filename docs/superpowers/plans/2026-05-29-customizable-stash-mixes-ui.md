# Mix Builder UI Implementation Plan (Plan 2 of 2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users create/edit/delete their own Stash Mixes by picking genres, curated moods, an optional era, and a discovery level — materialized through the Plan 1 tag-seeded engine and shown on Home alongside the built-ins.

**Architecture:** A Compose "Mix Builder" screen + ViewModel writes `StashMixRecipeEntity` rows (`seedStrategy = "TAG_GRAPH"`, `isBuiltin = false`) via `StashMixRecipeDao`, then enqueues `StashMixRefreshWorker.enqueueOneTime(context, recipeId)` to materialize the recipe into a `PlaylistType.STASH_MIX` playlist (which Home renders reactively). Genres come from a bundled `GenreCatalog` Kotlin object; moods from the existing `MoodTagMap`. The builder lives in `feature/library` (already holds PlaylistDetail + SavedStateHandle-arg precedents and depends on `:core:data`). Custom mixes get Edit/Delete on Home; refresh is on-demand (open-if-stale or explicit Refresh).

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Hilt, Room, WorkManager, type-safe Navigation-Compose. Tests: Robolectric (DAO) + MockK + mockito-kotlin (VM) + JUnit assertions.

**Spec:** `docs/superpowers/specs/2026-05-28-customizable-stash-mixes-design.md`
**Builds on:** Plan 1 (`docs/superpowers/plans/2026-05-28-customizable-stash-mixes-engine.md`) — the tag-seeded engine + `moodKeysCsv`/`tagSampleDepth` columns + `MoodTagMap`/`RecipeTagResolver`/`TagPoolBuilder`. Plan 1 must be implemented first.

**Branch:** continue on `feat/customizable-stash-mixes`.

---

## Design system (match exactly — wireframes in `.superpowers/brainstorm/` are reference)

Tokens (from `core/ui/.../theme/Color.kt`, `Type.kt`, `Shape.kt`, `components/GlassCard.kt`):
- bg `#06060C`; glass fill `Color.White.copy(alpha=0.04f)`; glass border `1.dp` `White@0.06`; bright border `White@0.14`.
- primary `#8B5CF6` (StashPurple); secondary/cyan `#06B6D4`; `StashTheme.extendedColors` for glass/cyan/textTertiary.
- Fonts: Space Grotesk (titles), Inter (body) — already the Material3 Typography. Shapes: small 8 / medium 12 / large 16 / xl 20 dp.
- Reuse `GlassCard` (16dp, glass fill, 1dp border, 16dp padding). Chips: selected = `surfaceVariant`/accent-soft fill + bright border; unselected = transparent + `textTertiary`.
- **Mood emblems = Material Outlined icons (NOT emoji)** — the established iconography (the app uses `Icons.*` via `libs.compose.material.icons.extended`, already on feature modules). See Task 6's `MoodEmblems` map.

---

## File Structure

**New — `core/data/.../mix/`:**
- `GenreCatalog.kt` — bundled curated genre families (object, like `MoodTagMap`/`StashMixDefaults`).
- `MixRecipeForm.kt` — pure data class for builder form state + a `toRecipe(existing): StashMixRecipeEntity` mapper (testable, no Android deps).

**New — `feature/library/.../mixbuilder/`:**
- `MixBuilderViewModel.kt` — `@HiltViewModel`; form state, catalog, save/delete, loads existing recipe for edit.
- `MixBuilderScreen.kt` — Compose screen (chips, mood emblems, slider, save).
- `MoodEmblems.kt` — mood-id → `ImageVector` map.

**Modified:**
- `core/data/.../db/dao/StashMixRecipeDao.kt` — add `deleteCustom(id)`.
- `app/.../navigation/TopLevelDestination.kt` — add `MixBuilderRoute(recipeId: Long? = null)`.
- `app/.../navigation/StashNavHost.kt` — register `composable<MixBuilderRoute>` + pass new Home lambdas.
- `feature/home/.../HomeScreen.kt` — "Create mix" tile in the Stash Mixes row + Edit/Delete actions in the context sheet for custom mixes; new `onNavigateToMixBuilder` lambda.
- `feature/home/.../HomeViewModel.kt` + `HomeUiState.kt` — add `customMixPlaylistIds: Set<Long>` (from `recipeDao.observeAll()`); add `deleteCustomMix(playlist)`; on-demand stale-refresh on open.
- `feature/library/build.gradle.kts` — add `implementation(libs.work.runtime.ktx)` (the builder VM enqueues the refresh worker; `:core:data` is already a dep).

**Conventions:** feature modules auto-get `:core:ui/:core:model/:core:common`, Hilt, navigation-compose, `lifecycle-runtime-compose` via the `stash.android.feature` convention plugin. Room `@Query` params explicit. VM uiState = `combine(...).stateIn(viewModelScope, WhileSubscribed(5_000), initial)`; screens collect via `collectAsStateWithLifecycle()`. SavedStateHandle args via `savedStateHandle.get<T>("argName")` (NOT `toRoute` — not used in this repo).

---

# PHASE A — Data & plumbing (headless, fully unit-tested)

## Task 1: GenreCatalog (bundled curated families)

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/mix/GenreCatalog.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/mix/GenreCatalogTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.stash.core.data.mix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreCatalogTest {
    @Test fun `families are non-empty and each has genres`() {
        assertTrue(GenreCatalog.FAMILIES.isNotEmpty())
        GenreCatalog.FAMILIES.forEach { f ->
            assertTrue("family '${f.name}' has no genres", f.genres.isNotEmpty())
        }
    }
    @Test fun `all genre tags are lowercase, trimmed, unique across the catalog`() {
        val all = GenreCatalog.FAMILIES.flatMap { it.genres }
        assertEquals(all, all.map { it.trim().lowercase() })
        assertEquals("duplicate genre tag in catalog", all.size, all.toSet().size)
    }
    @Test fun `allGenres exposes the flattened set`() {
        assertEquals(GenreCatalog.FAMILIES.flatMap { it.genres }.toSet(), GenreCatalog.allGenres())
    }
}
```

- [ ] **Step 2: Run it — expect FAIL**

Run: `./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.mix.GenreCatalogTest"`

- [ ] **Step 3: Implement `GenreCatalog`** (starter curation; the tag strings ARE the Last.fm tags). Keep it comprehensive but clean — this is the menu, not the music.

```kotlin
package com.stash.core.data.mix

/**
 * Curated, bundled genre catalog for the Mix Builder — the menu of selectable
 * genres, grouped into families for browsing. Tag strings are Last.fm tags
 * (the actual tracks are always fetched live; see TagPoolBuilder). Bump
 * [VERSION] when curation changes; refresh from Last.fm chart.getTopTags during
 * curation, then hand-clean (drop non-genre junk) — do NOT fetch live.
 */
object GenreCatalog {
    const val VERSION = 1

    data class Family(val name: String, val genres: List<String>)

    val FAMILIES: List<Family> = listOf(
        Family("Electronic", listOf("house", "techno", "ambient", "lo-fi", "drum and bass", "synthwave", "idm", "trance")),
        Family("Hip-Hop & R&B", listOf("hip-hop", "rap", "trap", "r&b", "neo-soul", "boom bap")),
        Family("Rock", listOf("rock", "indie rock", "classic rock", "punk", "post-rock", "garage rock", "shoegaze")),
        Family("Metal", listOf("metal", "heavy metal", "death metal", "black metal", "doom metal")),
        Family("Pop", listOf("pop", "indie pop", "synth pop", "dream pop", "k-pop", "art pop")),
        Family("Jazz & Soul", listOf("jazz", "soul", "funk", "blues", "bossa nova", "fusion")),
        Family("Folk & Country", listOf("folk", "country", "americana", "singer-songwriter", "bluegrass")),
        Family("Classical", listOf("classical", "piano", "orchestral", "contemporary classical")),
        Family("Reggae & Dub", listOf("reggae", "dub", "dancehall", "ska")),
        Family("Latin", listOf("latin", "salsa", "reggaeton", "bossa nova", "cumbia")),
        Family("World", listOf("afrobeat", "world", "celtic", "flamenco")),
        Family("Experimental", listOf("experimental", "noise", "drone", "avant-garde")),
    ).map { it.copy(genres = it.genres.map { g -> g.trim().lowercase() }.distinct()) }

    fun allGenres(): Set<String> = FAMILIES.flatMap { it.genres }.toSet()
}
```
> NOTE: "bossa nova" appears under Jazz & Soul and Latin in the draft above — that violates the cross-catalog-uniqueness test. During implementation, de-dupe: keep each tag in ONE family (e.g. remove "bossa nova" from Latin). The test enforces this; adjust the lists until it passes. The exact curation is a content decision — the structure + invariants are what matter.

- [ ] **Step 4: Run it — expect PASS** (after de-duping). Run the same test command.

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/mix/GenreCatalog.kt \
        core/data/src/test/kotlin/com/stash/core/data/mix/GenreCatalogTest.kt
git commit -m "feat(mix): bundled curated GenreCatalog (families -> Last.fm tags)"
```

---

## Task 2: `deleteCustom` DAO method

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/dao/StashMixRecipeDao.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/db/dao/StashMixRecipeDaoDeleteCustomTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.StashMixRecipeEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class StashMixRecipeDaoDeleteCustomTest {
    private lateinit var db: StashDatabase
    private lateinit var dao: StashMixRecipeDao
    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), StashDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.stashMixRecipeDao()
    }
    @After fun tearDown() { db.close() }

    @Test fun `deleteCustom removes a custom recipe`() = runTest {
        val id = dao.insert(StashMixRecipeEntity(name = "My Mix", isBuiltin = false))
        assertNotNull(dao.getById(id))
        dao.deleteCustom(id)
        assertNull(dao.getById(id))
    }
    @Test fun `deleteCustom does NOT delete builtins`() = runTest {
        val id = dao.insert(StashMixRecipeEntity(name = "Deep Cuts", isBuiltin = true))
        dao.deleteCustom(id)
        assertEquals("builtin must survive", "Deep Cuts", dao.getById(id)?.name)
    }
}
```

- [ ] **Step 2: Run it — expect FAIL** (`deleteCustom` unresolved)

Run: `./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.db.dao.StashMixRecipeDaoDeleteCustomTest"`

- [ ] **Step 3: Add `deleteCustom`** to `StashMixRecipeDao`:

```kotlin
/** Deletes a USER recipe (never a builtin). Caller deletes the materialized
 *  playlist separately (FK is SET_NULL, not CASCADE). */
@Query("DELETE FROM stash_mix_recipes WHERE id = :id AND is_builtin = 0")
suspend fun deleteCustom(id: Long)
```

- [ ] **Step 4: Run it — expect PASS.** Same command.

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/db/dao/StashMixRecipeDao.kt \
        core/data/src/test/kotlin/com/stash/core/data/db/dao/StashMixRecipeDaoDeleteCustomTest.kt
git commit -m "feat(mix): StashMixRecipeDao.deleteCustom (custom recipes only)"
```

---

## Task 3: `MixRecipeForm` + `toRecipe` mapper (pure, testable)

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/mix/MixRecipeForm.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/mix/MixRecipeFormTest.kt`

Pure form→entity mapping so the builder VM stays thin and the mapping is unit-tested.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.stash.core.data.mix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MixRecipeFormTest {
    @Test fun `maps genres moods era discovery to a TAG_GRAPH custom recipe`() {
        val form = MixRecipeForm(
            name = "Late Night Jazz",
            genreTags = setOf("jazz", "soul"),
            moodKeys = setOf("chill"),
            eraStartYear = 1990, eraEndYear = 1999,
            discoveryRatio = 0.85f,
        )
        val r = form.toRecipe(existingId = null)
        assertEquals("Late Night Jazz", r.name)
        assertEquals("jazz,soul", r.includeTagsCsv)
        assertEquals("chill", r.moodKeysCsv)
        assertEquals(1990, r.eraStartYear); assertEquals(1999, r.eraEndYear)
        assertEquals(0.85f, r.discoveryRatio, 0f)
        assertEquals("TAG_GRAPH", r.seedStrategy)
        assertFalse(r.isBuiltin)
        assertTrue(r.isActive)
        assertEquals(0L, r.id) // new -> autogen
    }
    @Test fun `preserves id when editing`() {
        val r = MixRecipeForm(name = "X", genreTags = setOf("rock")).toRecipe(existingId = 42L)
        assertEquals(42L, r.id)
    }
    @Test fun `isValid requires a name and at least one genre or mood`() {
        assertFalse(MixRecipeForm(name = "", genreTags = setOf("rock")).isValid)
        assertFalse(MixRecipeForm(name = "X").isValid)
        assertTrue(MixRecipeForm(name = "X", genreTags = setOf("rock")).isValid)
        assertTrue(MixRecipeForm(name = "X", moodKeys = setOf("chill")).isValid)
    }
}
```

- [ ] **Step 2: Run it — expect FAIL.** `./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.mix.MixRecipeFormTest"`

- [ ] **Step 3: Implement `MixRecipeForm`**

```kotlin
package com.stash.core.data.mix

import com.stash.core.data.db.entity.StashMixRecipeEntity

/** UI-agnostic builder form state + mapping to a custom TAG_GRAPH recipe. */
data class MixRecipeForm(
    val name: String = "",
    val genreTags: Set<String> = emptySet(),
    val moodKeys: Set<String> = emptySet(),
    val eraStartYear: Int? = null,
    val eraEndYear: Int? = null,
    val discoveryRatio: Float = 0.85f,
    val targetLength: Int = 40,
) {
    val isValid: Boolean
        get() = name.isNotBlank() && (genreTags.isNotEmpty() || moodKeys.isNotEmpty())

    fun toRecipe(existingId: Long?): StashMixRecipeEntity = StashMixRecipeEntity(
        id = existingId ?: 0L,
        name = name.trim(),
        includeTagsCsv = genreTags.joinToString(","),
        moodKeysCsv = moodKeys.joinToString(","),
        eraStartYear = eraStartYear,
        eraEndYear = eraEndYear,
        discoveryRatio = discoveryRatio,
        targetLength = targetLength,
        seedStrategy = "TAG_GRAPH",
        isBuiltin = false,
        isActive = true,
    )

    companion object {
        /** Rebuild form state from an existing recipe (for edit). */
        fun fromRecipe(r: StashMixRecipeEntity): MixRecipeForm = MixRecipeForm(
            name = r.name,
            genreTags = r.includeTagsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
            moodKeys = r.moodKeysCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
            eraStartYear = r.eraStartYear, eraEndYear = r.eraEndYear,
            discoveryRatio = r.discoveryRatio, targetLength = r.targetLength,
        )
    }
}
```
> NOTE: `genreTags`/`moodKeys` are `Set` (unordered) — `joinToString` order isn't guaranteed. The test uses `setOf("jazz","soul")` and asserts `"jazz,soul"`; to keep it deterministic, either make the fields `LinkedHashSet`/`List` preserving insertion order, OR change the assertion to compare `.split(",").toSet()`. Pick the order-preserving option (use `List`-backed ordered sets in the VM) so the stored CSV is stable; adjust the field types + test accordingly during implementation.

- [ ] **Step 4: Run it — expect PASS.** Same command.

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/mix/MixRecipeForm.kt \
        core/data/src/test/kotlin/com/stash/core/data/mix/MixRecipeFormTest.kt
git commit -m "feat(mix): MixRecipeForm + toRecipe/fromRecipe mapping"
```

---

# PHASE B — Mix Builder UI + Home integration

## Task 4: MixBuilderViewModel

**Files:**
- Create: `feature/library/src/main/kotlin/com/stash/feature/library/mixbuilder/MixBuilderViewModel.kt`
- Modify: `feature/library/build.gradle.kts` (add `implementation(libs.work.runtime.ktx)`)
- Test: `feature/library/src/test/kotlin/com/stash/feature/library/mixbuilder/MixBuilderViewModelTest.kt`

VM holds `MixRecipeForm` state + the catalog; `save()` builds the recipe, inserts/updates via `StashMixRecipeDao`, enqueues `StashMixRefreshWorker.enqueueOneTime(context, id)` to materialize it, and signals completion (one-shot event) so the screen navigates back. For edit, loads the recipe via `recipeId` from `SavedStateHandle`.

- [ ] **Step 1: Write the failing test** (mockito-kotlin, mirror `feature/library` VM test harness e.g. `LikedSongsDetailViewModelTest`). Focus on the testable surface: form mutations + save persists the right recipe. The WorkManager enqueue is a static side-effect — isolate it behind an injected `(Long) -> Unit` `materialize` lambda so the VM is testable without WorkManager.

```kotlin
package com.stash.feature.library.mixbuilder

import androidx.lifecycle.SavedStateHandle
import com.stash.core.data.db.dao.StashMixRecipeDao
import com.stash.core.data.db.entity.StashMixRecipeEntity
import com.stash.core.data.repository.MusicRepository
/* StandardTestDispatcher harness as in LikedSongsDetailViewModelTest */
import io.mockk... or org.mockito.kotlin...
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MixBuilderViewModelTest {
    @Test fun `save inserts a new TAG_GRAPH custom recipe and materializes it`() = runTest {
        // recipeDao.insert(any) returns 7L; capture the inserted entity.
        // build VM with empty SavedStateHandle (create mode), a fake materialize lambda capturing the id.
        // vm.setName("Late Night Jazz"); vm.toggleGenre("jazz"); vm.toggleMood("chill")
        // vm.save()
        // assert: recipeDao.insert called with name="Late Night Jazz", includeTagsCsv="jazz",
        //         moodKeysCsv="chill", seedStrategy="TAG_GRAPH", isBuiltin=false
        // assert: materialize lambda invoked with 7L
        // assert: a "saved" one-shot event emitted
    }
    @Test fun `edit mode loads the existing recipe into the form`() = runTest {
        // SavedStateHandle("recipeId" to 42L); recipeDao.getById(42L) returns a recipe with includeTagsCsv="rock"
        // assert uiState.form.name + genreTags reflect the loaded recipe
    }
    @Test fun `save with invalid form (no name) does not insert`() = runTest {
        // vm.toggleGenre("jazz"); vm.save(); verify recipeDao.insert NEVER called; an error event/flag set
    }
}
```
> Flesh out the harness by copying `feature/library/src/test/kotlin/com/stash/feature/library/LikedSongsDetailViewModelTest.kt` (mockito-kotlin, `Dispatchers.setMain(StandardTestDispatcher())`, `runCurrent()/advanceUntilIdle()`). Capture `recipeDao.insert` args with an argument captor.

- [ ] **Step 2: Run it — expect FAIL.** `./gradlew :feature:library:testDebugUnitTest --tests "com.stash.feature.library.mixbuilder.MixBuilderViewModelTest"`

- [ ] **Step 3: Implement `MixBuilderViewModel`**

```kotlin
package com.stash.feature.library.mixbuilder

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.db.dao.StashMixRecipeDao
import com.stash.core.data.mix.GenreCatalog
import com.stash.core.data.mix.MixRecipeForm
import com.stash.core.data.mix.MoodTagMap
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.workers.StashMixRefreshWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MixBuilderUiState(
    val form: MixRecipeForm = MixRecipeForm(),
    val families: List<GenreCatalog.Family> = GenreCatalog.FAMILIES,
    val moods: List<String> = MoodTagMap.ALL_MOODS,
    val isEditing: Boolean = false,
    val isLoading: Boolean = false,
) { val canSave: Boolean get() = form.isValid }

@HiltViewModel
class MixBuilderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val recipeDao: StashMixRecipeDao,
    private val musicRepository: MusicRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val recipeId: Long? = savedStateHandle.get<Long>("recipeId")

    private val _uiState = MutableStateFlow(MixBuilderUiState(isEditing = recipeId != null))
    val uiState: StateFlow<MixBuilderUiState> = _uiState.asStateFlow()

    private val _saved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val saved = _saved.asSharedFlow()

    init { recipeId?.let { loadExisting(it) } }

    private fun loadExisting(id: Long) {
        viewModelScope.launch {
            recipeDao.getById(id)?.let { r ->
                _uiState.value = _uiState.value.copy(form = MixRecipeForm.fromRecipe(r))
            }
        }
    }

    private fun update(block: (MixRecipeForm) -> MixRecipeForm) {
        _uiState.value = _uiState.value.copy(form = block(_uiState.value.form))
    }
    fun setName(name: String) = update { it.copy(name = name) }
    fun toggleGenre(tag: String) = update { it.copy(genreTags = it.genreTags.toggle(tag)) }
    fun toggleMood(key: String) = update { it.copy(moodKeys = it.moodKeys.toggle(key)) }
    fun setEra(startYear: Int?, endYear: Int?) = update { it.copy(eraStartYear = startYear, eraEndYear = endYear) }
    fun setDiscoveryRatio(value: Float) = update { it.copy(discoveryRatio = value) }

    fun save() {
        val form = _uiState.value.form
        if (!form.isValid) return
        viewModelScope.launch {
            val recipe = form.toRecipe(existingId = recipeId)
            val id = if (recipeId != null) { recipeDao.update(recipe); recipeId } else recipeDao.insert(recipe)
            StashMixRefreshWorker.enqueueOneTime(context, id)
            _saved.tryEmit(Unit)
        }
    }

    private fun <T> Set<T>.toggle(item: T): Set<T> =
        if (contains(item)) this - item else this + item
}
```
> For testability of `save()` without WorkManager: prefer injecting the enqueue as a seam. Simplest in-repo-consistent approach is to leave `StashMixRefreshWorker.enqueueOneTime(context, id)` inline (matches `HomeViewModel`), and in the test verify the DAO insert/update + `saved` emission, NOT the enqueue (HomeViewModel's enqueue is likewise untested). If the reviewer wants the enqueue tested, extract a `MixMaterializer` interface in `:core:data` and inject it. Decide during implementation; do not block.

- [ ] **Step 4: Run it — expect PASS.** Same command. `:feature:library` already has `mockito-kotlin` + `kotlinx-coroutines-test` (it hosts `LikedSongsDetailViewModelTest`), which is all this test needs (argument captors + direct flow reads). **No turbine** — it's not on this module's classpath, so don't import it.

- [ ] **Step 5: Commit**

```bash
git add feature/library/src/main/kotlin/com/stash/feature/library/mixbuilder/MixBuilderViewModel.kt \
        feature/library/src/test/kotlin/com/stash/feature/library/mixbuilder/MixBuilderViewModelTest.kt \
        feature/library/build.gradle.kts
git commit -m "feat(mix): MixBuilderViewModel — create/edit custom recipes + materialize"
```

---

## Task 5: Mood emblems

**Files:**
- Create: `feature/library/src/main/kotlin/com/stash/feature/library/mixbuilder/MoodEmblems.kt`

- [ ] **Step 1: Implement** (Material Outlined icons — real iconography, NOT emoji; matches the app's `Icons.*` usage). No test (pure static UI map); verified visually in Task 6.

```kotlin
package com.stash.feature.library.mixbuilder

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Celebration
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Grain
import androidx.compose.ui.graphics.vector.ImageVector

/** Mood id -> emblem icon, in Stash's Material-outlined icon language. */
val MoodEmblems: Map<String, ImageVector> = mapOf(
    "chill" to Icons.Outlined.Bedtime,
    "energetic" to Icons.Outlined.Bolt,
    "focus" to Icons.Outlined.CenterFocusStrong,
    "party" to Icons.Outlined.Celebration,
    "melancholy" to Icons.Outlined.Grain,
    "romantic" to Icons.Outlined.Favorite,
)

/** Title-case label for a mood id. */
fun moodLabel(key: String): String = key.replaceFirstChar { it.uppercase() }
```
> Verify each `Icons.Outlined.*` name resolves in `compose.material.icons.extended` (it's on the classpath). If a name differs, pick the nearest outlined equivalent. These map 1:1 to the brainstorm emblems (crescent/bolt/target/sparkle/raincloud/heart).

- [ ] **Step 2: Build to confirm icons resolve.** `./gradlew :feature:library:compileDebugKotlin`

- [ ] **Step 3: Commit**

```bash
git add feature/library/src/main/kotlin/com/stash/feature/library/mixbuilder/MoodEmblems.kt
git commit -m "feat(mix): mood emblem icon map (Material outlined, no emoji)"
```

---

## Task 6: MixBuilderScreen (Compose)

**Files:**
- Create: `feature/library/src/main/kotlin/com/stash/feature/library/mixbuilder/MixBuilderScreen.kt`

UI-heavy; verified by build + on-device (Task 9), not unit tests. Mirror `EqualizerScreen` structure (`hiltViewModel()`, `collectAsStateWithLifecycle()`, `onBack`). Match the design system + the `.superpowers/brainstorm/mix-builder-v3.html` mockup.

- [ ] **Step 1: Implement the screen.** Structure:

```kotlin
@Composable
fun MixBuilderScreen(
    onBack: () -> Unit,
    viewModel: MixBuilderViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.saved.collect { onBack() } }  // navigate back on save
    Scaffold(topBar = { /* TopAppBar: back chevron + "Create a Mix" / "Edit Mix", transparent */ }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            // 1. Name TextField (glass; transparent indicators; placeholder "Mix name")
            // 2. EyebrowLabel("Moods") + FlowRow of mood chips: MoodEmblems[key] icon + moodLabel(key),
            //    selected = state.form.moodKeys.contains(key); onClick = viewModel.toggleMood(key)
            // 3. EyebrowLabel("Genres") + per-family: family.name subhead + FlowRow of genre chips,
            //    selected = state.form.genreTags.contains(tag); onClick = viewModel.toggleGenre(tag)
            //    ("Show all families" expansion optional; render all families in a scroll for v1)
            // 4. EyebrowLabel("Era · optional") + chips Any/70s/80s/90s/2000s/2010s ->
            //    viewModel.setEra(start,end) (Any -> null,null; "90s" -> 1990,1999)
            // 5. EyebrowLabel("Discovery level") + Slider(value=form.discoveryRatio, 0f..1f) ->
            //    viewModel.setDiscoveryRatio; caption "~${(r*100).roundToInt()}% fresh streaming"
            // 6. Summary GlassCard (name + "genres · moods · era · N tracks")
            // 7. Button("Create mix"/"Save", enabled = state.canSave, onClick = viewModel::save) — filled primary
        }
    }
}
```
Reuse a private `MixChip(label, icon?, selected, onClick)` styled per the design system (selected = `surfaceVariant`/accent-soft fill + bright border; unselected = transparent + textTertiary). Use `FlowRow` (`androidx.compose.foundation.layout.FlowRow`) for chip wrapping — it's available via compose-foundation but still experimental, so annotate the composable (or file) with `@OptIn(ExperimentalLayoutApi::class)` (`androidx.compose.foundation.layout.ExperimentalLayoutApi`); it isn't used elsewhere in the feature modules yet. Match `GlassCard`, Space Grotesk titles, the cyan→purple slider colors. Keep the file focused — if it grows past ~300 lines, extract `MixChip`/`EraSelector` into small private composables in the same file.

- [ ] **Step 2: Build.** `./gradlew :feature:library:compileDebugKotlin` — expect SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add feature/library/src/main/kotlin/com/stash/feature/library/mixbuilder/MixBuilderScreen.kt
git commit -m "feat(mix): MixBuilderScreen — genre/mood/era/discovery builder UI"
```

---

## Task 7: Navigation route

**Files:**
- Modify: `app/src/main/kotlin/com/stash/app/navigation/TopLevelDestination.kt`
- Modify: `app/src/main/kotlin/com/stash/app/navigation/StashNavHost.kt`

- [ ] **Step 1: Add the route.** In `TopLevelDestination.kt` (alongside the other `@Serializable` routes):
```kotlin
@Serializable data class MixBuilderRoute(val recipeId: Long? = null)
```

- [ ] **Step 2: Register + wire.** In `StashNavHost.kt`:
```kotlin
composable<MixBuilderRoute> {
    MixBuilderScreen(onBack = { navController.popBackStack() })
}
```
And add to the `HomeScreen(...)` call a new lambda:
```kotlin
onNavigateToMixBuilder = { recipeId -> navController.navigate(MixBuilderRoute(recipeId)) },
```
(import `com.stash.feature.library.mixbuilder.MixBuilderScreen`.)

- [ ] **Step 3: Build.** `./gradlew :app:compileDebugKotlin` (will fail until Task 8 adds the `onNavigateToMixBuilder` param to `HomeScreen` — do Task 8 next, then this compiles). Acceptable to commit Tasks 7+8 together if the compile gate requires it.

- [ ] **Step 4: Commit** (may be combined with Task 8 — see note):
```bash
git add app/src/main/kotlin/com/stash/app/navigation/TopLevelDestination.kt \
        app/src/main/kotlin/com/stash/app/navigation/StashNavHost.kt
git commit -m "feat(mix): MixBuilderRoute + nav registration"
```

---

## Task 8: Home integration — Create tile + Edit/Delete + on-demand refresh

**Files:**
- Modify: `feature/home/.../HomeViewModel.kt`, `HomeUiState.kt`, `HomeScreen.kt`

- [ ] **Step 1: Add `customMixPlaylistIds` to Home state.** In `HomeUiState` add `val customMixPlaylistIds: Set<Long> = emptySet()`. Compute it from recipes:
```kotlin
val customMixPlaylistIds = recipes
    .filter { !it.isBuiltin && it.playlistId != null }
    .mapNotNull { it.playlistId }.toSet()
```
⚠️ **Do NOT add `recipeDao.observeAll()` as a 6th positional arg to the `uiState` `combine`** — that combine is already at exactly 5 sources (`musicDataFlow, promptsFlow, _playlistSortOrder, tipJarRepository.state, bannersInfoFlow`), the max for Kotlin's typed `combine` overload; a 6th won't compile. Instead **fold `recipeDao.observeAll()` into `musicDataFlow`** (the existing 3-flow holder pattern that already builds `MusicData` from playlists/etc.) and derive `customMixPlaylistIds` there alongside `playlists` — that's its natural home. (`recipeDao` is already injected.)

- [ ] **Step 2: Add VM actions.**
```kotlin
fun deleteCustomMix(playlist: Playlist) {
    viewModelScope.launch {
        val recipe = recipeDao.findByPlaylistId(playlist.id)
        musicRepository.deletePlaylistWithCascade(playlist.id, alsoBlacklist = false)
        recipe?.let { recipeDao.deleteCustom(it.id) }
        _userMessages.tryEmit("Deleted “${playlist.name}”")
    }
}
fun recipeIdForPlaylist(playlistId: Long, onResult: (Long?) -> Unit) {
    viewModelScope.launch { onResult(recipeDao.findByPlaylistId(playlistId)?.id) }
}
```
On-demand stale refresh: in `refreshMixIfStale(playlistId)` (call from the mix card's onClick before navigating, OR keep refresh purely manual for v1):
```kotlin
fun refreshMixIfStale(playlistId: Long) {
    viewModelScope.launch {
        val r = recipeDao.findByPlaylistId(playlistId) ?: return@launch
        val stale = (r.lastRefreshedAt ?: 0L) < System.currentTimeMillis() - STALE_MIX_MS
        if (!r.isBuiltin && stale) refreshMix(playlistId)
    }
}
```
with `private const val STALE_MIX_MS = 24L * 60 * 60 * 1000` (or in companion). Wire `refreshMixIfStale` into the mix card `onClick` alongside `onNavigateToPlaylist`.

- [ ] **Step 2b (test):** add a `HomeViewModel` test for `deleteCustomMix` (verifies `deletePlaylistWithCascade` + `deleteCustom` both called) and `customMixPlaylistIds` derivation. NOTE: `feature/home/build.gradle.kts` currently has only `testImplementation("junit:junit:4.13.2")` — add turbine/mockito-kotlin/coroutines-test (mirror `feature/search/build.gradle.kts`) to host the test. If wiring a full HomeViewModel test harness proves heavy, report DONE_WITH_CONCERNS and cover `deleteCustomMix` logic via a thin extracted helper instead.

- [ ] **Step 3: Add the "Create mix" tile + Edit/Delete actions in `HomeScreen.kt`.**
  - Add `onNavigateToMixBuilder: (Long?) -> Unit` to `HomeScreen` params.
  - In the Stash Mixes `LazyRow`, append a final item after the mixes: a `CreateMixCard` (mirror `CreatePlaylistCard`, dashed glass, "＋ Create mix") → `onClick = { onNavigateToMixBuilder(null) }`. ⚠️ The whole Stash Mixes block is currently wrapped in `if (uiState.stashMixes.isNotEmpty())` (HomeScreen.kt ~line 435), so the Create tile would be hidden when there are zero mixes. **Intended behavior: "Create mix" must always be reachable.** Relax that guard so the row (or at least the Create tile) renders even when `stashMixes` is empty — e.g. render the header+row whenever the section is applicable, with the Create tile always present as the last item. Keep the built-ins-present case visually identical.
  - In the long-press `ModalBottomSheet`, when `uiState.customMixPlaylistIds.contains(playlist.id)`, add two `HomeBottomSheetActionRow`s:
    - Edit (`Icons.Default.Edit`) → look up recipe id then `onNavigateToMixBuilder(recipeId)`; close sheet.
    - Delete (`Icons.Default.Delete`, tint = error) → `viewModel.deleteCustomMix(playlist)`; close sheet. (Reuse the existing delete-confirmation dialog pattern if present.)

- [ ] **Step 4: Build + test.** `./gradlew :app:assembleDebug :feature:home:testDebugUnitTest` — expect SUCCESS (app compiles end-to-end with Task 7; new home test passes).

- [ ] **Step 5: Commit**

```bash
git add feature/home/ app/src/main/kotlin/com/stash/app/navigation/
git commit -m "feat(mix): Home Create-mix tile + Edit/Delete custom mixes + stale-refresh"
```

---

## Task 9: Build, install, on-device verification

- [ ] **Step 1:** `./gradlew :app:assembleDebug` — BUILD SUCCESSFUL.
- [ ] **Step 2:** Ensure the debug build has the Last.fm API key in `local.properties` (`lastFmConfigured` must be true or discovery is gated off — see Plan 1), then `./gradlew :app:installDebug` (reconnect device first).
- [ ] **Step 3:** On device: Home → "Create mix" → pick e.g. Jazz + Chill → Create. Confirm: a new card appears in Stash Mixes; opening it shows fresh stream-only tracks (needs network + the app's Last.fm key — NOT a user Last.fm account). Long-press the custom mix → Edit (loads the form) and Delete (removes card + recipe). Also confirm built-in Deep Cuts + First Listen still populate (Plan 1 verification, still owed). Document observations.
- [ ] **Step 4:** Commit any verification notes.

---

## Done criteria (Plan 2)
- Users can create a custom mix (name + genres + moods + era + discovery), it materializes into a Stash Mix card on Home, opens to fresh tag-seeded streaming tracks, and can be edited/deleted.
- Custom mixes refresh on-demand (stale-on-open or manual); built-ins keep their daily cadence.
- `GenreCatalog`, `MixRecipeForm`, `deleteCustom` unit-tested; the engine path is exercised end-to-end on device.

**After Plan 2:** the full customizable-mixes feature is user-complete. Then: on-device Deep Cuts/First Listen verification (Plan 1 Task 8, still owed), decide on shipping, and handle the stashed offline-mixes-visibility fix.
