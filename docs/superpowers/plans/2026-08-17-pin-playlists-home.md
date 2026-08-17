# Pin Playlists to Home (+ Liked Songs card) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users opt their created/imported playlists and a merged Liked Songs card onto a new top-of-Home "Your playlists" rail, draggable/hideable like every other Home section.

**Architecture:** One nullable `pinned_to_home_at` column on `playlists` (NULL = off, default) drives an opt-in rail derived beside Home's existing mix classification; a `showLikedOnHome` DataStore boolean drives a merged Liked card that tab-switches to Library ▸ Liked via a new `LibraryDeepLinkController` (mirror of `SettingsDeepLinkController` — `LibraryRoute` stays a `data object`, no route-shape change). Toggle surfaces: a "Show on Home" row in Library's playlist long-press sheet, and a switch in Settings > Appearance > Home layout.

**Spec:** `docs/superpowers/specs/2026-08-17-pin-playlists-home-design.md` — read it first; it records the decisions and the rejected approaches.

**Tech Stack:** Kotlin, Room (manual migrations, `exportSchema=true`), Hilt, Compose, DataStore, JUnit4 + Robolectric + Mockito-Kotlin.

**Branch:** create `feat/pin-playlists-home` off master before Task 1: `git checkout -b feat/pin-playlists-home`

**Gradle notes:** Use the daemon (never `--no-daemon`). Targeted module suites only — the whole-repo test run OOMs the 2GB daemon. Pre-existing master failures live in other modules (ytmusic matcher, PreviewPrefetcher, etc.) — the `--tests` filters below avoid them.

---

### Task 1: Schema — `pinned_to_home_at` column, migration 41→42, model + mapper

**Files:**
- Modify: `core/model/src/main/kotlin/com/stash/core/model/Playlist.kt` (data class, after `pinned`)
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/entity/PlaylistEntity.kt` (after `pinned`, line ~86)
- Modify: `core/data/src/main/kotlin/com/stash/core/data/mapper/PlaylistMapper.kt` (both directions)
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt` (`version = 41` → `42` at line 95; new `MIGRATION_41_42` next to `MIGRATION_40_41` at line ~1024)
- Modify: `core/data/src/main/kotlin/com/stash/core/data/di/DatabaseModule.kt` (append `StashDatabase.MIGRATION_41_42` to `.addMigrations(...)`, line ~74)
- Test: `core/data/src/test/kotlin/com/stash/core/data/db/MigrationV41V42Test.kt` (create)

- [ ] **Step 1: Write the failing migration test**

Mirror `MigrationV40V41Test.kt` exactly (same rule, runner, `@Config`). The v41 `playlists` schema requires only the NOT-NULL-without-SQL-default columns; `sync_enabled`/`date_added`/`hide_from_home`/`pinned` have SQL defaults and nullable columns can be omitted.

```kotlin
package com.stash.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies migration v41 -> v42: `playlists` gains nullable
 * `pinned_to_home_at`; existing rows read back NULL (not pinned to Home)
 * and the column is writable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV41V42Test {

    private val DB_NAME = "migration-v41v42-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `existing playlists read back NULL pinned_to_home_at and the column is writable`() {
        helper.createDatabase(DB_NAME, 41).use { db ->
            db.execSQL(
                """
                INSERT INTO playlists (id, name, source, source_id, type, track_count, is_active)
                VALUES (1, 'Gym', 'SPOTIFY', 'sp1', 'CUSTOM', 10, 1)
                """.trimIndent(),
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            DB_NAME, 42, true, StashDatabase.MIGRATION_41_42,
        )

        migrated.query("SELECT pinned_to_home_at FROM playlists WHERE id = 1").use { c ->
            assertTrue(c.moveToNext())
            assertTrue("existing rows must not be pinned", c.isNull(0))
        }

        migrated.execSQL("UPDATE playlists SET pinned_to_home_at = 1723900000000 WHERE id = 1")
        migrated.query("SELECT pinned_to_home_at FROM playlists WHERE id = 1").use { c ->
            assertTrue(c.moveToNext())
            assertEquals(1723900000000L, c.getLong(0))
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core:data:testDebugUnitTest --tests com.stash.core.data.db.MigrationV41V42Test`
Expected: FAIL — `MIGRATION_41_42` unresolved (compile error). That is the failing state for a migration test.

- [ ] **Step 3: Implement the schema change**

`PlaylistEntity.kt` — append after `pinned` (keep the KDoc):

```kotlin
    /**
     * Epoch-millis when the user put this playlist on the Home "Your
     * playlists" rail; NULL = not on Home (the default). Doubles as the
     * rail's stable sort key (first pinned renders first). Independent of
     * [pinned] (Library grid sort) and [hideFromHome] (mixes-only flow).
     */
    @ColumnInfo(name = "pinned_to_home_at")
    val pinnedToHomeAt: Long? = null,
```

`Playlist.kt` — append after `dateAdded` inside the data class:

```kotlin
    /**
     * Epoch-millis when this playlist was pinned to Home's "Your playlists"
     * rail; null = not on Home. Also the rail's sort key.
     */
    val pinnedToHomeAt: Long? = null,
```

`PlaylistMapper.kt` — add `pinnedToHomeAt = pinnedToHomeAt,` to BOTH `toDomain()` and `toEntity()` constructor calls.

`StashDatabase.kt` — bump `version = 41` to `version = 42` (line 95) and add above `MIGRATION_40_41`:

```kotlin
        val MIGRATION_41_42 = object : Migration(41, 42) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playlists ADD COLUMN pinned_to_home_at INTEGER")
            }
        }
```

`DatabaseModule.kt` — append `StashDatabase.MIGRATION_41_42` after `MIGRATION_40_41` in `.addMigrations(...)` (there is no `fallbackToDestructiveMigration`; forgetting this line crashes on startup by design).

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :core:data:testDebugUnitTest --tests com.stash.core.data.db.MigrationV41V42Test`
Expected: PASS. The build also regenerates `core/data/schemas/com.stash.core.data.db.StashDatabase/42.json` (`exportSchema = true`) — it must be committed.

- [ ] **Step 5: Commit**

```bash
git add core/model/src/main/kotlin/com/stash/core/model/Playlist.kt core/data/src/main/kotlin/com/stash/core/data/db/entity/PlaylistEntity.kt core/data/src/main/kotlin/com/stash/core/data/mapper/PlaylistMapper.kt core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt core/data/src/main/kotlin/com/stash/core/data/di/DatabaseModule.kt core/data/src/test/kotlin/com/stash/core/data/db/MigrationV41V42Test.kt core/data/schemas/com.stash.core.data.db.StashDatabase/42.json
git commit -m "feat(db): add playlists.pinned_to_home_at (migration 41->42)"
```

---

### Task 2: DAO setter + repository pass-through

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/dao/PlaylistDao.kt` (next to `setPinned`, line ~495)
- Modify: `core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepository.kt` (next to `setPlaylistPinned`)
- Modify: `core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepositoryImpl.kt` (next to `setPlaylistPinned`, line ~587)
- Test: `core/data/src/test/kotlin/com/stash/core/data/db/dao/PlaylistDaoPinnedToHomeTest.kt` (create)

- [ ] **Step 1: Write the failing DAO test**

Copy the in-memory `StashDatabase` fixture from `core/data/src/test/kotlin/com/stash/core/data/db/dao/PlaylistDaoMixVisibilityTest.kt` (same DAO, same entity fixtures) (`Room.inMemoryDatabaseBuilder(...).allowMainThreadQueries().build()`, `@RunWith(RobolectricTestRunner::class)`, same `@Config`). Body:

```kotlin
    @Test
    fun `setPinnedToHome round-trips and clears`() = runBlocking {
        val id = dao.insert(
            PlaylistEntity(name = "Gym", source = MusicSource.SPOTIFY, sourceId = "sp1"),
        )

        dao.setPinnedToHome(id, 1723900000000L)
        assertEquals(
            1723900000000L,
            dao.getAllActive().first().single { it.id == id }.pinnedToHomeAt,
        )

        dao.setPinnedToHome(id, null)
        assertNull(dao.getAllActive().first().single { it.id == id }.pinnedToHomeAt)
    }
```

- [ ] **Step 2: Run it — expect FAIL** (`setPinnedToHome` unresolved)

Run: `./gradlew :core:data:testDebugUnitTest --tests com.stash.core.data.db.dao.PlaylistDaoPinnedToHomeTest`

- [ ] **Step 3: Implement**

`PlaylistDao.kt`, directly under `setPinned`:

```kotlin
    /** Pin/unpin a playlist on Home's "Your playlists" rail (null = off). */
    @Query("UPDATE playlists SET pinned_to_home_at = :pinnedAt WHERE id = :playlistId")
    suspend fun setPinnedToHome(playlistId: Long, pinnedAt: Long?)
```

`MusicRepository.kt` interface, next to `setPlaylistPinned`:

```kotlin
    /** Pin/unpin a playlist on Home's "Your playlists" rail. Null clears the pin. */
    suspend fun setPlaylistPinnedToHome(playlistId: Long, pinnedAt: Long?)
```

`MusicRepositoryImpl.kt`, next to the `setPlaylistPinned` override (line ~587):

```kotlin
    override suspend fun setPlaylistPinnedToHome(playlistId: Long, pinnedAt: Long?) {
        playlistDao.setPinnedToHome(playlistId, pinnedAt)
    }
```

`MusicRepositoryImpl` is the sole implementation (tests use Mockito/MockK mocks) — no other overrides needed.

- [ ] **Step 4: Run it — expect PASS**, plus module compile

Run: `./gradlew :core:data:testDebugUnitTest --tests com.stash.core.data.db.dao.PlaylistDaoPinnedToHomeTest :core:data:compileDebugKotlin`

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/db/dao/PlaylistDao.kt core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepository.kt core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepositoryImpl.kt core/data/src/test/kotlin/com/stash/core/data/db/dao/PlaylistDaoPinnedToHomeTest.kt
git commit -m "feat(data): setPlaylistPinnedToHome DAO + repository"
```

(If test fakes elsewhere needed the new override, `git add` those exact files too.)

---

### Task 3: `HomeSection.YOUR_PLAYLISTS` + `showLikedOnHome` pref + Settings UI

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/prefs/HomeSectionsPreference.kt`
- Modify: `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsAppearanceScreen.kt` (Home layout section, lines ~127-155)
- Modify: `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsViewModel.kt` (big combine, lines ~516-610; actions ~1217)
- Modify: `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsUiState.kt`
- Modify: `feature/home/src/main/kotlin/com/stash/feature/home/HomeScreen.kt` (only if the `when(section)` must compile — add a temporary `HomeSection.YOUR_PLAYLISTS -> Unit` branch; Task 6 replaces it)
- Test: `core/data/src/test/kotlin/com/stash/core/data/prefs/HomeSectionOrderTest.kt` (extend)

- [ ] **Step 1: Write the failing order tests** (append to `HomeSectionOrderTest`)

```kotlin
    @Test
    fun `your playlists leads the default order`() {
        assertEquals(HomeSection.YOUR_PLAYLISTS, resolveHomeSectionOrder(emptyList()).first())
    }

    @Test
    fun `saved order without your playlists appends it, preserving the arrangement`() {
        val saved = listOf("made_for_you", "new_releases")
        val resolved = resolveHomeSectionOrder(saved)
        assertEquals(HomeSection.MADE_FOR_YOU, resolved[0])
        assertEquals(HomeSection.NEW_RELEASES, resolved[1])
        // New-in-an-update section can't be hidden by a stale pref: appended.
        assertTrue(HomeSection.YOUR_PLAYLISTS in resolved)
        assertTrue(resolved.indexOf(HomeSection.YOUR_PLAYLISTS) >= 2)
    }
```

- [ ] **Step 2: Run — expect FAIL** (`YOUR_PLAYLISTS` unresolved)

Run: `./gradlew :core:data:testDebugUnitTest --tests com.stash.core.data.prefs.HomeSectionOrderTest`

- [ ] **Step 3: Implement the enum + pref**

`HomeSectionsPreference.kt` — declare the new value FIRST (declaration order = default order):

```kotlin
enum class HomeSection(val key: String) {
    YOUR_PLAYLISTS("your_playlists"),
    NEW_RELEASES("new_releases"),
    ...
```

Same file — add the Liked-card boolean (import `androidx.datastore.preferences.core.booleanPreferencesKey`); inside the class next to the other keys:

```kotlin
    private val showLikedKey = booleanPreferencesKey("show_liked_on_home")

    /** Merged Liked Songs card on the "Your playlists" rail. Off by default. */
    val showLikedOnHome: Flow<Boolean> = context.homeSectionsDataStore.data.map { prefs ->
        prefs[showLikedKey] ?: false
    }

    suspend fun setShowLikedOnHome(shown: Boolean) {
        context.homeSectionsDataStore.edit { prefs -> prefs[showLikedKey] = shown }
    }
```

- [ ] **Step 4: Update the two EXISTING order tests, then run — expect PASS.**

Two existing tests in `HomeSectionOrderTest` assert exact 6-element lists and legitimately change with the new section — this is a fixture update, not a regression. The appended block preserves ENUM DECLARATION order, and `YOUR_PLAYLISTS` is declared first, so where it lands differs per test:
- `saved permutation is honored`: append `HomeSection.YOUR_PLAYLISTS` at the END of the expected list (the saved order covers all six old sections, so the appended block is just the new one).
- `unknown keys are dropped and missing sections appended in default order`: the expected list becomes `[RADIOS, TOP_ALBUMS, YOUR_PLAYLISTS, NEW_RELEASES, QOBUZ_PLAYLISTS, MADE_FOR_YOU, MOOD_DECADES]` — `YOUR_PLAYLISTS` at index 2, FIRST of the appended block, not last.

The other two tests use `HomeSection.entries` and survive unchanged.

Run: `./gradlew :core:data:testDebugUnitTest --tests com.stash.core.data.prefs.HomeSectionOrderTest`
Expected: PASS (all tests, including the two updated ones). Then fix the two `when` exhaustiveness breaks the enum causes:

Then `./gradlew :feature:home:compileDebugKotlin :feature:settings:compileDebugKotlin` — expected failures: `displayLabel()` in `SettingsAppearanceScreen.kt` (~line 148) and the `when (section)` in `HomeScreen.kt` (~line 480). Fix:
- `displayLabel()`: add `com.stash.core.data.prefs.HomeSection.YOUR_PLAYLISTS -> "Your playlists"` as the first branch.
- `HomeScreen.kt`: add a placeholder first branch `HomeSection.YOUR_PLAYLISTS -> Unit` (Task 6 replaces it).

- [ ] **Step 5: Wire the Settings switch**

`SettingsViewModel.kt`:
- In the big combine's flow list, append `homeSectionsPreference.showLikedOnHome,` directly after `homeSectionsPreference.hidden,` (line ~517).
- In the read block, directly after `homeSectionsHidden` and BEFORE `v.requireExhausted()`: `val showLikedOnHome = v.next<Boolean>()`.
- Pass `showLikedOnHome = showLikedOnHome,` in the `SettingsUiState(...)` construction next to `homeSectionsHidden = homeSectionsHidden,`.
- Next to `onHomeSectionHiddenChanged` (~line 1222):

```kotlin
    fun onShowLikedOnHomeChanged(shown: Boolean) {
        viewModelScope.launch { homeSectionsPreference.setShowLikedOnHome(shown) }
    }
```

`SettingsUiState.kt`: add `val showLikedOnHome: Boolean = false,` next to `homeSectionsHidden`.

`SettingsAppearanceScreen.kt` — after the `homeSectionOrder.forEachIndexed { ... }` block (line ~145), inside the same section:

```kotlin
        SettingsToggleRow(
            title = "Show Liked Songs on Home",
            subtitle = "A merged Liked Songs card on the Your playlists rail",
            checked = uiState.showLikedOnHome,
            onCheckedChange = viewModel::onShowLikedOnHomeChanged,
        )
```

- [ ] **Step 6: Compile + settings tests**

Run: `./gradlew :feature:settings:compileDebugKotlin :feature:home:compileDebugKotlin :core:data:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. If `SettingsViewModel` has a unit-test fixture asserting the combine (check `feature/settings/src/test/`), run that module's suite and update any `Values`-order assertions.

- [ ] **Step 7: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/prefs/HomeSectionsPreference.kt core/data/src/test/kotlin/com/stash/core/data/prefs/HomeSectionOrderTest.kt feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsAppearanceScreen.kt feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsViewModel.kt feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsUiState.kt feature/home/src/main/kotlin/com/stash/feature/home/HomeScreen.kt
git commit -m "feat(home): YOUR_PLAYLISTS section + show-liked-on-home pref + settings switch"
```

---

### Task 4: `LibraryDeepLinkController` + Library-side consume

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/navigation/LibraryDeepLinkController.kt`
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/LibraryViewModel.kt` (constructor + one function)
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/LibraryScreen.kt` (one `LaunchedEffect` in the stateful wrapper, ~line 150)
- Modify: existing `LibraryViewModel*Test.kt` fixtures (constructor gains a param)
- Test: `core/data/src/test/kotlin/com/stash/core/data/navigation/LibraryDeepLinkControllerTest.kt` (create)

- [ ] **Step 1: Write the failing controller test**

```kotlin
package com.stash.core.data.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryDeepLinkControllerTest {

    @Test
    fun `consume returns the pending focus exactly once`() {
        val controller = LibraryDeepLinkController()
        controller.request(LibraryFocus.LIKED)
        assertEquals(LibraryFocus.LIKED, controller.consume())
        assertNull("second consume must be empty", controller.consume())
    }

    @Test
    fun `consume with nothing pending is null`() {
        assertNull(LibraryDeepLinkController().consume())
    }
}
```

- [ ] **Step 2: Run — expect FAIL** (class unresolved)

Run: `./gradlew :core:data:testDebugUnitTest --tests com.stash.core.data.navigation.LibraryDeepLinkControllerTest`

- [ ] **Step 3: Implement the controller** (mirror `SettingsDeepLinkController.kt` in the same package — same singleton/one-shot rationale; keep a short KDoc pointing at it)

```kotlin
package com.stash.core.data.navigation

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Cross-feature handoff for "switch to the Library tab and land on a
 * specific sub-tab". Same shape and rationale as
 * [SettingsDeepLinkController]: `LibraryRoute` is a `data object` and the
 * bottom bar matches tabs by route type, so no route argument — the caller
 * queues a one-shot focus here, then performs a normal tab switch, and
 * LibraryScreen reads + clears it on entry.
 */
@Singleton
class LibraryDeepLinkController @Inject constructor() {
    private val _focus = MutableStateFlow<LibraryFocus?>(null)

    /** Caller-side: queue a focus request just before switching tabs. */
    fun request(focus: LibraryFocus) {
        _focus.value = focus
    }

    /** Library-side: read the pending focus (if any) and clear it atomically. */
    fun consume(): LibraryFocus? {
        var taken: LibraryFocus? = null
        _focus.update { current ->
            taken = current
            null
        }
        return taken
    }
}

/** Library surfaces a deep-link can target. */
enum class LibraryFocus {
    LIKED,
}
```

- [ ] **Step 4: Run — expect PASS**

Run: `./gradlew :core:data:testDebugUnitTest --tests com.stash.core.data.navigation.LibraryDeepLinkControllerTest`

- [ ] **Step 5: Library consume wiring**

`LibraryViewModel.kt` — add constructor param `private val libraryDeepLinkController: com.stash.core.data.navigation.LibraryDeepLinkController,` (append after the last existing param). Next to `selectTab` (~line 431):

```kotlin
    /**
     * One-shot deep-link from Home (Liked card). Screen calls this on every
     * entry — not `init` — so repeat taps work on this retained tab ViewModel.
     */
    fun consumeDeepLinkFocus(): com.stash.core.data.navigation.LibraryFocus? =
        libraryDeepLinkController.consume()
```

`LibraryScreen.kt` — in the stateful wrapper, after the `collectAsStateWithLifecycle` block (~line 145):

```kotlin
    // Home's Liked card queued a focus before the tab switch: land on Liked.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (viewModel.consumeDeepLinkFocus() == com.stash.core.data.navigation.LibraryFocus.LIKED) {
            viewModel.selectTab(LibraryTab.LIKED)
        }
    }
```

Fix the constructor in every `LibraryViewModel*Test.kt` fixture (`LibraryViewModelSortTest`, `LibraryViewModelMixTest`, `LibraryViewModelLikedSearchTest`, `LibraryViewModelShuffleLikedTest`, `LibrarySearchFieldQueryTest`, and any other the compiler names): pass a real `LibraryDeepLinkController()` — it's a plain class, no mock needed.

- [ ] **Step 6: Run the library suites — expect PASS (same set as master)**

Run: `./gradlew :feature:library:testDebugUnitTest --tests "com.stash.feature.library.LibraryViewModel*" --tests "com.stash.feature.library.LibrarySearchFieldQueryTest"`

- [ ] **Step 7: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/navigation/LibraryDeepLinkController.kt core/data/src/test/kotlin/com/stash/core/data/navigation/LibraryDeepLinkControllerTest.kt feature/library/src/main/kotlin/com/stash/feature/library/LibraryViewModel.kt feature/library/src/main/kotlin/com/stash/feature/library/LibraryScreen.kt feature/library/src/test/kotlin/com/stash/feature/library/
git commit -m "feat(library): LibraryDeepLinkController + Liked-tab focus consume"
```

---

### Task 5: HomeViewModel — rail derivation, Liked card state, pin action

**Files:**
- Modify: `feature/home/src/main/kotlin/com/stash/feature/home/MixRail.kt` (pure rail function)
- Modify: `feature/home/src/main/kotlin/com/stash/feature/home/HomeUiState.kt` (`yourPlaylists`, `likedCard`, `LikedCardState`)
- Modify: `feature/home/src/main/kotlin/com/stash/feature/home/HomeViewModel.kt`
- Modify: `feature/home/src/test/kotlin/com/stash/feature/home/HomeViewModelTest.kt` (constructor fixture)
- Test: `feature/home/src/test/kotlin/com/stash/feature/home/YourPlaylistsRailTest.kt` (create)

- [ ] **Step 1: Write the failing pure-function test**

```kotlin
package com.stash.feature.home

import com.stash.core.model.MusicSource
import com.stash.core.model.Playlist
import com.stash.core.model.PlaylistType
import org.junit.Assert.assertEquals
import org.junit.Test

class YourPlaylistsRailTest {

    private fun playlist(
        id: Long,
        type: PlaylistType = PlaylistType.CUSTOM,
        pinnedToHomeAt: Long? = null,
        name: String = "P$id",
    ) = Playlist(
        id = id, name = name, source = MusicSource.SPOTIFY, type = type,
        pinnedToHomeAt = pinnedToHomeAt,
    )

    @Test
    fun `only pinned non-mix playlists survive, ordered by pin time`() {
        val rail = yourPlaylistsRail(
            listOf(
                playlist(1, pinnedToHomeAt = 300),
                playlist(2, pinnedToHomeAt = null),                      // not pinned
                playlist(3, PlaylistType.DAILY_MIX, pinnedToHomeAt = 50), // mix: excluded even if stamped
                playlist(4, PlaylistType.STASH_MIX, pinnedToHomeAt = 60), // mix: excluded
                playlist(5, PlaylistType.STASH_LIKED, pinnedToHomeAt = 100),
                playlist(6, pinnedToHomeAt = 200),
            ),
        )
        assertEquals(listOf(5L, 6L, 1L), rail.map { it.id })
    }

    @Test
    fun `empty in, empty out`() {
        assertEquals(emptyList<Playlist>(), yourPlaylistsRail(emptyList()))
    }
}
```

- [ ] **Step 2: Run — expect FAIL** (`yourPlaylistsRail` unresolved)

Run: `./gradlew :feature:home:testDebugUnitTest --tests com.stash.feature.home.YourPlaylistsRailTest`

- [ ] **Step 3: Implement in `MixRail.kt`** (bottom of file)

```kotlin
/**
 * Home's "Your playlists" rail: playlists the user explicitly pinned
 * (`pinnedToHomeAt != null`), excluding anything that already lives on a
 * mix rail. Ordered by pin time — first pinned renders first; stable, no
 * reshuffling (the Your-mixes stable-order precedent).
 *
 * Reads the UNFILTERED playlist list on purpose: membership here depends
 * only on the pin stamp, never on the mixes' `hideFromHome` flow.
 */
fun yourPlaylistsRail(playlists: List<Playlist>): List<Playlist> =
    playlists
        .filter { it.pinnedToHomeAt != null && mixRail(it) == null }
        .sortedBy { it.pinnedToHomeAt }
```

- [ ] **Step 4: Run — expect PASS**

Run: `./gradlew :feature:home:testDebugUnitTest --tests com.stash.feature.home.YourPlaylistsRailTest`

- [ ] **Step 5: State + ViewModel wiring**

`HomeUiState.kt`:
- Add to `HomeUiState` next to `yourMixes`: `val yourPlaylists: List<HomeMix> = emptyList(),` and `val likedCard: LikedCardState? = null,`
- Add at file bottom:

```kotlin
/** Merged Liked Songs card on the "Your playlists" rail (null = toggle off). */
data class LikedCardState(val trackCount: Int)
```

`HomeViewModel.kt`:
1. Constructor: add `private val libraryDeepLinkController: com.stash.core.data.navigation.LibraryDeepLinkController,` (after `settingsDeepLinkController`).
2. `HomePlaylistData`: add `val yourPlaylists: List<HomeMix>,`. In the `homePlaylistFlow` combine body, before the final `HomePlaylistData(...)`: derive `val pinnedRail = yourPlaylistsRail(playlists).map { it.toHomeMix() }` — note `playlists` here is the combine's raw (unfiltered) parameter, NOT the `filter { !it.hideFromHome }` loop source — and pass `yourPlaylists = pinnedRail,`.
3. Liked card flow (place after `homePlaylistFlow`):

```kotlin
    /**
     * Merged Liked Songs card: pref-gated; when on, the count is the
     * de-duped union across the STASH_LIKED + LIKED_SONGS playlists —
     * exactly what Library's Liked tab shows (its distinctBy { id } merge).
     * Costs zero queries while the toggle is off.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val likedCardFlow: kotlinx.coroutines.flow.Flow<LikedCardState?> =
        homeSectionsPreference.showLikedOnHome.flatMapLatest { shown ->
            if (!shown) {
                kotlinx.coroutines.flow.flowOf(null)
            } else {
                combine(
                    musicRepository.getPlaylistsByType(com.stash.core.model.PlaylistType.STASH_LIKED),
                    musicRepository.getPlaylistsByType(com.stash.core.model.PlaylistType.LIKED_SONGS),
                ) { stash, external -> stash + external }
                    .flatMapLatest { likedPlaylists ->
                        if (likedPlaylists.isEmpty()) {
                            kotlinx.coroutines.flow.flowOf(LikedCardState(trackCount = 0))
                        } else {
                            combine(
                                likedPlaylists.map { musicRepository.getTracksByPlaylist(it.id) },
                            ) { arrays ->
                                LikedCardState(
                                    trackCount = arrays.flatMap { it.toList() }.distinctBy { it.id }.size,
                                )
                            }
                        }
                    }
            }
        }
```

4. Keep the `uiState` combine at 5 typed sources: replace its `homePlaylistFlow` input with `combine(homePlaylistFlow, likedCardFlow) { home, liked -> home to liked }`, destructure `(home, likedCard)` in the transform, and pass `yourPlaylists = home.yourPlaylists, likedCard = likedCard,` into `HomeUiState(...)`.
5. Actions, next to `setHideFromHome` (~line 644):

```kotlin
    /** Pin/unpin a playlist on the "Your playlists" rail (Home-side unpin sheet). */
    fun setPinnedToHome(playlistId: Long, pinned: Boolean) {
        viewModelScope.launch {
            musicRepository.setPlaylistPinnedToHome(
                playlistId,
                if (pinned) System.currentTimeMillis() else null,
            )
        }
    }

    /** Queue the Liked focus, then the caller performs the Library tab switch. */
    fun requestLibraryLikedFocus() {
        libraryDeepLinkController.request(com.stash.core.data.navigation.LibraryFocus.LIKED)
    }
```

`getPlaylistsByType` already exists on `MusicRepository` (LibraryViewModel uses it at line 342).

- [ ] **Step 6: Fix `HomeViewModelTest` fixture and run home suites**

Add the `LibraryDeepLinkController()` argument (real instance) wherever the fixture constructs `HomeViewModel`, and stub BOTH new flow reads — an unstubbed (null) flow NPEs the combine and uiState never emits, so the test HANGS rather than failing (the fixture documents this exact failure mode at `HomeViewModelTest.kt:231-233`):
- On the `homeSectionsPreference` mock (currently stubs only `visibleSections`, ~line 294): add `on { showLikedOnHome } doReturn flowOf(false)`.
- On the repository mock: stub `getPlaylistsByType` to `flowOf(emptyList())` for both types (mirror how the fixture stubs `getAllPlaylists`).

Run: `./gradlew :feature:home:testDebugUnitTest --tests "com.stash.feature.home.*"`
Expected: PASS — same set as master (TickerTape/HomeMixOrder/etc. untouched).

- [ ] **Step 7: Commit**

```bash
git add feature/home/src/main/kotlin/com/stash/feature/home/MixRail.kt feature/home/src/main/kotlin/com/stash/feature/home/HomeUiState.kt feature/home/src/main/kotlin/com/stash/feature/home/HomeViewModel.kt feature/home/src/test/kotlin/com/stash/feature/home/YourPlaylistsRailTest.kt feature/home/src/test/kotlin/com/stash/feature/home/HomeViewModelTest.kt
git commit -m "feat(home): your-playlists rail + liked-card state in HomeViewModel"
```

---

### Task 6: Home UI — rail render, LikedHomeCard, unpin sheet, tab-switch nav

**Files:**
- Create: `feature/home/src/main/kotlin/com/stash/feature/home/LikedHomeCard.kt`
- Modify: `feature/home/src/main/kotlin/com/stash/feature/home/HomeScreen.kt` (params ~156; replace the Task-3 placeholder branch ~480; new sheet next to the mix action sheet ~617)
- Modify: `app/src/main/kotlin/com/stash/app/navigation/StashScaffold.kt` (extract `navigateToTab`, lines ~165-220)
- Modify: `app/src/main/kotlin/com/stash/app/navigation/StashNavHost.kt` (new param + HomeRoute wiring, lines ~44-97)

- [ ] **Step 1: `LikedHomeCard.kt`** — sibling of `MixRailCard`, same 140dp geometry, heart-on-gradient cover (no param creep on the shared card):

```kotlin
package com.stash.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.stash.core.common.extensions.pluralize
import com.stash.core.ui.components.motion.pressScale
import com.stash.core.ui.theme.StashTheme

/**
 * The merged Liked Songs card on Home's "Your playlists" rail. Mirrors
 * [MixRailCard]'s 140dp column; the cover is a heart on the Stash purple
 * gradient instead of art. Tap switches to Library ▸ Liked.
 */
@Composable
fun LikedHomeCard(
    trackCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .width(140.dp)
            .pressScale(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
    ) {
        val colors = StashTheme.extendedColors
        Box(
            modifier = Modifier
                .size(140.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Brush.linearGradient(listOf(colors.purpleLight, colors.purpleDark))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(48.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(text = "Liked Songs", style = MaterialTheme.typography.labelLarge)
        Text(
            text = pluralize(trackCount, "song"),
            style = MaterialTheme.typography.labelSmall,
            color = StashTheme.extendedColors.textTertiary,
        )
    }
}
```

(`pluralize` lives in `:core:common` (`com.stash.core.common.extensions.pluralize`) — the import above is correct as written; `HomeScreen.kt:137` imports the same one.)

- [ ] **Step 2: HomeScreen params + rail branch**

Signature (~line 156): add `onNavigateToLibrary: () -> Unit = {},` after `onNavigateToPlaylist`. Below the `actionSheetMixId` state declaration, add `var pinnedSheetPlaylistId by remember { mutableStateOf<Long?>(null) }`.

Replace the Task-3 placeholder branch with (FIRST branch of the `when`, so the section keys stay stable):

```kotlin
                HomeSection.YOUR_PLAYLISTS ->
                    if (uiState.yourPlaylists.isNotEmpty() || uiState.likedCard != null) {
                        item(key = "section_your_playlists") {
                            CardRail(title = "Your playlists") {
                                uiState.likedCard?.let { liked ->
                                    item(key = "liked_card") {
                                        LikedHomeCard(
                                            trackCount = liked.trackCount,
                                            onClick = {
                                                viewModel.requestLibraryLikedFocus()
                                                onNavigateToLibrary()
                                            },
                                        )
                                    }
                                }
                                items(uiState.yourPlaylists.take(HOME_RAIL_LIMIT), key = { it.id }) { p ->
                                    MixRailCard(
                                        title = p.title, artUrl = p.artUrl, source = p.source,
                                        onClick = { onNavigateToPlaylist(p.id) },
                                        onLongPress = { pinnedSheetPlaylistId = p.id },
                                    )
                                }
                            }
                        }
                    }
```

- [ ] **Step 3: Unpin sheet** — directly after the existing mix action sheet block (~line 617), same guard-render pattern:

```kotlin
    // ── Pinned-playlist action sheet (long-press a "Your playlists" card) ──
    pinnedSheetPlaylistId?.let { id ->
        val pinned = uiState.yourPlaylists.firstOrNull { it.id == id }
        if (pinned != null) {
            val sheetState = rememberModalBottomSheetState()
            ModalBottomSheet(
                onDismissRequest = { pinnedSheetPlaylistId = null },
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 8.dp),
                ) {
                    Text(
                        text = pinned.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                MixActionRow(
                    icon = Icons.AutoMirrored.Filled.ArrowForward,
                    label = "Open",
                    onClick = {
                        pinnedSheetPlaylistId = null
                        onNavigateToPlaylist(id)
                    },
                )
                MixActionRow(
                    icon = Icons.Default.PlayArrow,
                    label = "Play All",
                    onClick = {
                        pinnedSheetPlaylistId = null
                        viewModel.playMix(id)
                    },
                )
                MixActionRow(
                    icon = Icons.Filled.RemoveCircleOutline,
                    label = "Remove from Home",
                    onClick = {
                        pinnedSheetPlaylistId = null
                        viewModel.setPinnedToHome(id, pinned = false)
                    },
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }
```

(`MixActionRow` is the existing private row at `HomeScreen.kt:755` — signature `(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color = ...)`. `PlayArrow` and `RemoveCircleOutline` are already imported in the file — matching the Library sheet's "Play All" and the mix sheet's remove-row house style; add the `androidx.compose.material.icons.automirrored.filled.ArrowForward` import for "Open".)

- [ ] **Step 4: Scaffold seam + NavHost wiring**

`StashScaffold.kt` — the tab-switch logic currently lives inline in `StashBottomBar(onNavigate = { dest -> ... })` (lines ~167-207). Extract it verbatim into a local `fun navigateToTab(dest: TopLevelDestination) { ... }` declared before the `Scaffold(...)` call (it closes over `navController`), then:
- `StashBottomBar(currentRoute = currentRoute, onNavigate = ::navigateToTab)`
- `StashNavHost(..., onNavigateToTab = ::navigateToTab)`

`StashNavHost.kt`:
- Signature: add `onNavigateToTab: (TopLevelDestination) -> Unit = {},`
- HomeRoute block: add

```kotlin
                onNavigateToLibrary = { onNavigateToTab(TopLevelDestination.LIBRARY) },
```

- [ ] **Step 5: Compile everything that changed**

Run: `./gradlew :feature:home:compileDebugKotlin :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add feature/home/src/main/kotlin/com/stash/feature/home/LikedHomeCard.kt feature/home/src/main/kotlin/com/stash/feature/home/HomeScreen.kt app/src/main/kotlin/com/stash/app/navigation/StashScaffold.kt app/src/main/kotlin/com/stash/app/navigation/StashNavHost.kt
git commit -m "feat(home): render Your playlists rail + LikedHomeCard + unpin sheet + Library tab switch"
```

---

### Task 7: Library long-press sheet — "Show on Home"

**Files:**
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/LibraryViewModel.kt` (one function, next to `togglePlaylistPinned` ~line 764)
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/LibraryScreen.kt` (thread one callback through the same four sites as `onTogglePlaylistPinned`: ~195, ~442, ~627, ~1061; new sheet row after the Pin row ~1247)
- Test: `feature/library/src/test/kotlin/com/stash/feature/library/LibraryViewModelPinToHomeTest.kt` (create)

- [ ] **Step 1: Write the failing ViewModel test**

Copy the smallest existing fixture (`LibraryViewModelShuffleLikedTest` or `LibraryViewModelSortTest`) — same mocks + the Task-4 `LibraryDeepLinkController()` param. Body:

```kotlin
    @Test
    fun `toggle pins an unpinned playlist with a timestamp`() = runTest {
        val playlist = Playlist(id = 7, name = "Gym", source = MusicSource.SPOTIFY)

        viewModel.togglePlaylistOnHome(playlist)
        advanceUntilIdle()

        verifyBlocking(musicRepository) {
            setPlaylistPinnedToHome(eq(7L), argThat { this != null && this > 0L })
        }
    }

    @Test
    fun `toggle unpins a pinned playlist with null`() = runTest {
        val playlist = Playlist(id = 7, name = "Gym", source = MusicSource.SPOTIFY, pinnedToHomeAt = 123L)

        viewModel.togglePlaylistOnHome(playlist)
        advanceUntilIdle()

        verifyBlocking(musicRepository) { setPlaylistPinnedToHome(7L, null) }
    }
```

(Suspend-fun verification uses mockito-kotlin's `verifyBlocking` — the repo convention, see `LibraryViewModelShuffleLikedTest.kt:35`. If the mock needs stubbing, use `onBlocking`.)

- [ ] **Step 2: Run — expect FAIL** (`togglePlaylistOnHome` unresolved)

Run: `./gradlew :feature:library:testDebugUnitTest --tests com.stash.feature.library.LibraryViewModelPinToHomeTest`

- [ ] **Step 3: Implement**

`LibraryViewModel.kt`, next to `togglePlaylistPinned`:

```kotlin
    /** Pin/unpin [playlist] on Home's "Your playlists" rail. */
    fun togglePlaylistOnHome(playlist: Playlist) {
        viewModelScope.launch {
            musicRepository.setPlaylistPinnedToHome(
                playlist.id,
                if (playlist.pinnedToHomeAt == null) System.currentTimeMillis() else null,
            )
        }
    }
```

`LibraryScreen.kt` — thread `onTogglePlaylistOnHome: (Playlist) -> Unit` exactly like `onTogglePlaylistPinned` (add beside it at each of the four sites; wire `viewModel::togglePlaylistOnHome` at ~195). In the context sheet, directly after the Pin `BottomSheetActionRow` (~line 1247):

```kotlin
            BottomSheetActionRow(
                icon = if (playlist.pinnedToHomeAt != null) Icons.Filled.Home else Icons.Outlined.Home,
                label = if (playlist.pinnedToHomeAt != null) "Remove from Home" else "Show on Home",
                onClick = {
                    onTogglePlaylistOnHome(playlist)
                    selectedPlaylist = null
                },
            )
```

(Add the `androidx.compose.material.icons.filled.Home` / `androidx.compose.material.icons.outlined.Home` imports; the file already imports both icon styles for PushPin.)

- [ ] **Step 4: Run — expect PASS, plus module compile**

Run: `./gradlew :feature:library:testDebugUnitTest --tests com.stash.feature.library.LibraryViewModelPinToHomeTest :feature:library:compileDebugKotlin`

- [ ] **Step 5: Commit**

```bash
git add feature/library/src/main/kotlin/com/stash/feature/library/LibraryViewModel.kt feature/library/src/main/kotlin/com/stash/feature/library/LibraryScreen.kt feature/library/src/test/kotlin/com/stash/feature/library/LibraryViewModelPinToHomeTest.kt
git commit -m "feat(library): Show on Home action in the playlist sheet"
```

---

### Task 8: Full targeted suites, APK, device smoke (Pixel 5 rig)

**Files:** none (verification)

- [ ] **Step 0: Confirm pins survive sync (spec Edge-cases requirement — verified once already, re-confirm cheaply)**

The spec requires pins to survive sync the way `pinned`/`hideFromHome` do. Verified during planning: no sync path rewrites a whole playlist row — `DiffWorker.kt` and `StashMixRefreshWorker.kt` only call targeted column updaters (`updateArtUrl`/`updateName`/`updateLastSynced`/`updateTrackCount`/`updateSnapshotId`), and `PlaylistDao.insert` is `OnConflictStrategy.ABORT` (never REPLACE). Re-confirm nothing changed underneath:

Run: `grep -rn "OnConflictStrategy.REPLACE" core/data/src/main/kotlin/com/stash/core/data/db/dao/PlaylistDao.kt`
Expected: matches only on `insertCrossRef`/`insertAllCrossRefs` (the membership table), never a whole-playlist insert. If a whole-row REPLACE has appeared, stop and carry `pinned_to_home_at` through that path before shipping.

- [ ] **Step 1: Run every touched module's suite**

Run: `./gradlew :core:data:testDebugUnitTest :feature:home:testDebugUnitTest :feature:library:testDebugUnitTest :feature:settings:testDebugUnitTest --max-workers=3 -Dorg.gradle.jvmargs=-Xmx4096m`
Expected: `:core:data` has 4 documented pre-existing failures on master (plus 3 POSIX-only MetadataEmbedder on Windows) — the pass/fail set must match master exactly; everything new passes. If unsure of master's set, compare against the memory note, not zero.

- [ ] **Step 2: Build + install on the Pixel 5 rig**

Run: `./gradlew :app:assembleDebug` then `adb devices` — TWO devices may be attached; ALWAYS `adb -s <pixel5-serial>`. The rig is wireless (`192.168.137.35`, ephemeral port — ask the user for this session's port if it isn't connected).
`adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk`

- [ ] **Step 3: Smoke script (drive via `adb shell input` + `screencap`, rig's touch is dead)**

1. Library ▸ Playlists ▸ long-press a playlist → sheet shows "Show on Home" → tap it.
2. Home → "Your playlists" rail at the top under the hero, card present. Pin a second playlist → it appends after the first (pin order).
3. Settings ▸ Appearance ▸ Home layout → "Your playlists" row first, movers work; toggle "Show Liked Songs on Home" on.
4. Home → Liked card leads the rail with the merged count; tap → lands on Library ▸ Liked tab; bottom bar highlights Library; Back returns to Home (tab-switch semantics).
5. Long-press a pinned card → Open / Play All / Remove from Home; Remove drops the card live.
6. Unpin everything + toggle Liked off → rail gone, Home byte-identical to before.
7. Migration proof: this install upgraded a v41 DB in place — confirm Library/Home load with no crash in `adb -s <serial> logcat -d | grep -i "Migration\|SQLite"`.

- [ ] **Step 4: Screenshot the rail for the user** (`adb -s <serial> exec-out screencap -p > scratchpad/home_rail.png`) — the human eye overrules every judge.

- [ ] **Step 5: Finish the branch** — invoke superpowers:finishing-a-development-branch (merge/PR decision belongs to the user).
