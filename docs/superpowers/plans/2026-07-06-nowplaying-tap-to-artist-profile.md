# Now Playing → Artist Profile Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tapping the track title/artist on the full Now Playing screen resolves the playing artist's name to a YouTube Music browseId and opens the existing Artist Profile, scrolled to and highlighting the currently-playing album.

**Architecture:** The playing `Track` has only name strings (no artist/album browseId), so a tap runs an artist-filtered InnerTube search (`YTMusicApiClient.resolveArtist`) to get the browseId, then navigates to the existing `SearchArtistRoute`/`ArtistProfileScreen`. The artist name passed in is `albumArtist` (the clean album-level credit) with a fallback to `artist`, sent **raw** — YT Music's own fuzzy search resolves multi-artist and band-name credits correctly (verified live: "Tyler, the Creator", "Simon & Garfunkel", "Calvin Harris feat. Rihanna" all resolve to the right artist), so no fragile client-side name-splitting is done. A new optional `focusAlbum` nav arg drives a two-axis scroll (outer `LazyColumn` to the Albums/Singles section, inner `LazyRow` to the card) plus a brief highlight. All pure logic (album-focus matching) is unit-tested; the Compose scroll/highlight glue and the tap wiring are verified on-device.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Coroutines/Flow. Tests: `:data:ytmusic` uses **Mockito-Kotlin** (`mock`/`whenever`); `:feature:nowplaying` and `:feature:search` use **MockK** (`mockk`/`coEvery`/`coVerify`) + Turbine + Robolectric. Gradle multi-module.

**Spec:** `docs/superpowers/specs/2026-07-06-nowplaying-tap-to-artist-profile-design.md`

**Reference skills:** @superpowers:test-driven-development, @superpowers:verification-before-completion

**Known v1 behavior:** For a track with a blank `albumArtist` and a comma-joined `artist` like "Drake, 21 Savage", YT's search may return the featured artist rather than the lead. That still opens a relevant artist the user is hearing (never a crash or wrong-domain result). `albumArtist`, when present, already carries the clean lead credit, so this only affects legacy rows without it. Accepted for v1.

---

## File Structure

**Create:**
- `data/ytmusic/src/test/kotlin/com/stash/data/ytmusic/ResolveArtistTest.kt` — `resolveArtist` parser-contract tests.
- `data/ytmusic/src/test/resources/fixtures/search_artists_filter.json` — synthetic artists-shelf fixture.
- `feature/search/src/main/kotlin/com/stash/feature/search/AlbumFocus.kt` — album-focus matcher.
- `feature/search/src/test/kotlin/com/stash/feature/search/AlbumFocusTest.kt` — focus-match unit tests.

**Modify:**
- `data/ytmusic/.../YTMusicApiClient.kt` — add `ARTISTS_FILTER`, `resolveArtist(name)`.
- `app/.../navigation/TopLevelDestination.kt` — add `focusAlbum` to `SearchArtistRoute`.
- `app/.../navigation/StashNavHost.kt` — thread `focusAlbum`; give `NowPlayingRoute` an `onNavigateToArtist`.
- `feature/nowplaying/build.gradle.kts` — add `implementation(project(":data:ytmusic"))`.
- `feature/nowplaying/.../NowPlayingViewModel.kt` — inject `YTMusicApiClient`; add `resolvingArtist` state, `artistNavEvents`, `onTrackInfoTapped()`.
- `feature/nowplaying/.../NowPlayingScreen.kt` — clickable track block + chevron + spinner; collect `artistNavEvents`; new `onNavigateToArtist` param.
- `feature/nowplaying/.../NowPlayingViewModelTest.kt` — new tap tests + update existing VM factories for the new ctor arg.
- `feature/search/.../ArtistProfileUiState.kt` — add `focusAlbum: String?`.
- `feature/search/.../ArtistProfileViewModel.kt` — read `focusAlbum` from `SavedStateHandle` into state.
- `feature/search/.../ArtistProfileScreen.kt` — hoist outer `LazyListState`; drive two-axis scroll.
- `feature/search/.../AlbumsRow.kt` and `SinglesRow.kt` — accept `focusIndex`; highlight the matched card.

---

## Task 1: `resolveArtist(name)` on YTMusicApiClient

Artist-filtered search → top `ArtistSummary` (browseId + name + avatar), or null.

**Files:**
- Modify: `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/YTMusicApiClient.kt`
- Create: `data/ytmusic/src/test/resources/fixtures/search_artists_filter.json`
- Test: `data/ytmusic/src/test/kotlin/com/stash/data/ytmusic/ResolveArtistTest.kt`

**Context:** `InnerTubeClient.search(query, params)` exists. The verified live artists-filter param (returns the clean `musicShelfRenderer` "Artists" shelf, top row = Official Artist Channel) is `EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D`. Parsing reuses the existing `parseArtistsShelf(shelfRenderer): List<ArtistSummary>` in `SearchResponseParser.kt`, which already extracts the `UC…` browseId. The JSON navigation helpers (`navigatePath`, `asArray`, `firstArray`, `asObject`) are the same ones `searchAll` uses. Tests mock `InnerTubeClient` like `YTMusicApiClientTest.fakeClient` — note `resolveArtist` calls the two-arg `search`, so stub `search(any(), any())`.

- [ ] **Step 1: Create the fixture** `data/ytmusic/src/test/resources/fixtures/search_artists_filter.json`

```json
{
  "contents": { "tabbedSearchResultsRenderer": { "tabs": [ { "tabRenderer": { "content": { "sectionListRenderer": { "contents": [
    { "musicShelfRenderer": {
      "title": { "runs": [ { "text": "Artists" } ] },
      "contents": [
        { "musicResponsiveListItemRenderer": {
          "navigationEndpoint": { "browseEndpoint": { "browseId": "UCuoGeza7Dl9Ni_hmeLTPdkg" } },
          "flexColumns": [ { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [ { "text": "My Bloody Valentine" } ] } } } ],
          "thumbnail": { "musicThumbnailRenderer": { "thumbnail": { "thumbnails": [ { "url": "https://lh3.googleusercontent.com/mbv=w120", "width": 120 } ] } } }
        } },
        { "musicResponsiveListItemRenderer": {
          "navigationEndpoint": { "browseEndpoint": { "browseId": "UCotherKevinShields" } },
          "flexColumns": [ { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [ { "text": "Kevin Shields" } ] } } } ]
        } }
      ]
    } }
  ] } } } } ] } } }
}
```

- [ ] **Step 2: Write the failing test** `ResolveArtistTest.kt`

```kotlin
package com.stash.data.ytmusic

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ResolveArtistTest {
    private fun loadFixture(name: String): String =
        this::class.java.classLoader!!.getResourceAsStream("fixtures/$name")!!
            .bufferedReader().use { it.readText() }

    private fun fakeClient(responseJson: String?): YTMusicApiClient {
        val inner = mock<InnerTubeClient>()
        val parsed = responseJson?.let { Json.parseToJsonElement(it).jsonObject }
        runBlocking { whenever(inner.search(any(), any())).thenReturn(parsed) }
        return YTMusicApiClient(inner)
    }

    @Test fun `resolveArtist returns top artist id and name`() = runTest {
        val client = fakeClient(loadFixture("search_artists_filter.json"))
        val result = client.resolveArtist("my bloody valentine")
        assertEquals("UCuoGeza7Dl9Ni_hmeLTPdkg", result?.id)
        assertEquals("My Bloody Valentine", result?.name)
    }

    @Test fun `resolveArtist returns null when no artists shelf`() = runTest {
        val client = fakeClient("""{"contents":{}}""")
        assertNull(client.resolveArtist("nobody"))
    }

    @Test fun `resolveArtist returns null on null response`() = runTest {
        val client = fakeClient(null)
        assertNull(client.resolveArtist("nobody"))
    }

    @Test fun `resolveArtist returns null for blank name`() = runTest {
        val client = fakeClient(null)
        assertNull(client.resolveArtist("   "))
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :data:ytmusic:testDebugUnitTest --tests "com.stash.data.ytmusic.ResolveArtistTest"`
Expected: FAIL — `resolveArtist` unresolved reference.

- [ ] **Step 4: Write minimal implementation**

Add the constant to the `companion object` in `YTMusicApiClient.kt`:

```kotlin
/**
 * ytmusicapi-derived filter selector constraining search to the "Artists"
 * shelf. Forces the clean `musicShelfRenderer` shape (vs. the flat
 * `itemSectionRenderer` variant ambiguous queries otherwise return, where
 * no artist shelf parses). Verified live: top row = Official Artist Channel.
 */
private const val ARTISTS_FILTER = "EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D"
```

Add the method to the class body (near `searchAll`):

```kotlin
/**
 * Resolve an artist name to its YouTube Music browse identity. Runs an
 * artists-filtered search and returns the top [ArtistSummary] (browseId,
 * name, avatar), or null when the name is blank / there is no artist result
 * / on failure.
 *
 * Used by the Now Playing "tap track → artist profile" flow, where the
 * playing [com.stash.core.model.Track] carries only an artist NAME, not a
 * browseId. The name is passed raw (YT search handles multi-artist and
 * band-name credits); parsing reuses [parseArtistsShelf].
 */
suspend fun resolveArtist(name: String): ArtistSummary? {
    if (name.isBlank()) return null
    val response = innerTubeClient.search(name, params = ARTISTS_FILTER) ?: return null
    val shelves = response.navigatePath(
        "contents", "tabbedSearchResultsRenderer", "tabs",
    )?.firstArray()?.firstOrNull()?.asObject()
        ?.navigatePath("tabRenderer", "content", "sectionListRenderer", "contents")
        ?.asArray()
        ?: return null
    for (shelf in shelves) {
        val renderer = shelf.asObject()?.get("musicShelfRenderer")?.asObject() ?: continue
        val artists = parseArtistsShelf(renderer)
        if (artists.isNotEmpty()) return artists.first()
    }
    return null
}
```

Add the import if missing: `import com.stash.data.ytmusic.model.ArtistSummary`.

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :data:ytmusic:testDebugUnitTest --tests "com.stash.data.ytmusic.ResolveArtistTest"`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/YTMusicApiClient.kt \
        data/ytmusic/src/test/kotlin/com/stash/data/ytmusic/ResolveArtistTest.kt \
        data/ytmusic/src/test/resources/fixtures/search_artists_filter.json
git commit -m "feat(ytmusic): resolveArtist name→browseId via artists-filter search"
```

---

## Task 2: `focusAlbum` nav arg + NowPlaying route callback

Extend the nav route and wire the new navigation edge (no behavior yet — the ViewModel/screen changes come next).

**Files:**
- Modify: `app/src/main/kotlin/com/stash/app/navigation/TopLevelDestination.kt`
- Modify: `app/src/main/kotlin/com/stash/app/navigation/StashNavHost.kt:294-360`

- [ ] **Step 1: Add `focusAlbum` to `SearchArtistRoute`**

In `TopLevelDestination.kt`:

```kotlin
@Serializable
data class SearchArtistRoute(
    val artistId: String,
    val name: String,
    val avatarUrl: String? = null,
    val focusAlbum: String? = null,
)
```

(Optional with default → all existing call sites compile unchanged.)

- [ ] **Step 2: Leave the `SearchArtistRoute` composable's self-nav as-is**

In `StashNavHost.kt`, the `composable<SearchArtistRoute>` block builds `SearchArtistRoute(id, name, avatar)` for related-artist self-navigation — leave it (related-artist taps carry no focus album; the default null applies).

- [ ] **Step 3: Give `NowPlayingRoute` an `onNavigateToArtist`**

In the `composable<NowPlayingRoute>` block, change the `NowPlayingScreen(...)` call to:

```kotlin
NowPlayingScreen(
    onDismiss = { navController.popBackStack() },
    onNavigateToArtist = { id, name, avatar, focusAlbum ->
        navController.navigate(SearchArtistRoute(id, name, avatar, focusAlbum))
    },
)
```

(This will not compile until Task 4 adds the `onNavigateToArtist` param to `NowPlayingScreen`. That's expected — Tasks 3-4 complete the wiring. Do NOT build at the end of this task; commit the nav-layer change and proceed.)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/stash/app/navigation/TopLevelDestination.kt \
        app/src/main/kotlin/com/stash/app/navigation/StashNavHost.kt
git commit -m "feat(nav): focusAlbum arg on SearchArtistRoute + NowPlaying onNavigateToArtist"
```

---

## Task 3: `NowPlayingViewModel.onTrackInfoTapped`

Resolve on tap; emit a one-shot nav event; guard against double-tap; toast on failure.

**Files:**
- Modify: `feature/nowplaying/build.gradle.kts`
- Modify: `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingViewModel.kt`
- Test: `feature/nowplaying/src/test/kotlin/com/stash/feature/nowplaying/NowPlayingViewModelTest.kt`

**Context:** The VM already has `viewModelScope`, `_userMessages` (SharedFlow), `uiState.currentTrack`, and imports `CancellationException`. `YTMusicApiClient` is `@Singleton`/Hilt-injectable. The test file has THREE test classes; two build the VM through a private `newViewModel()` factory that passes all six current constructor args by name — a new required 7th arg breaks their compile unless updated (Step 2 below).

- [ ] **Step 1: Add the module dependency**

In `feature/nowplaying/build.gradle.kts`, add to the `dependencies { }` block (next to the other `project(...)` deps):

```kotlin
implementation(project(":data:ytmusic"))
```

- [ ] **Step 2: Update existing test factories for the new ctor arg**

In `NowPlayingViewModelTest.kt`, find every place the VM is constructed (the `newViewModel()` factories in the existing test classes) and add the new argument so the module keeps compiling:

```kotlin
ytMusicApiClient = mockk(relaxed = true),
```

(Ensure `import io.mockk.mockk` is present — it is used elsewhere in the file.)

- [ ] **Step 3: Write the failing tests**

Add a test class (or extend an existing one) mirroring the file's VM construction; add a `YTMusicApiClient` mock. Use Turbine for the event flow:

```kotlin
@Test
fun `onTrackInfoTapped emits nav target with focusAlbum on resolve success`() = runTest {
    val api = mockk<YTMusicApiClient>()
    coEvery { api.resolveArtist(any()) } returns
        ArtistSummary(id = "UC123", name = "My Bloody Valentine", avatarUrl = "http://a")
    val vm = buildViewModel(api = api, track = trackOf(artist = "My Bloody Valentine", album = "Loveless"))

    vm.artistNavEvents.test {
        vm.onTrackInfoTapped()
        val target = awaitItem()
        assertEquals("UC123", target.artistId)
        assertEquals("My Bloody Valentine", target.name)
        assertEquals("Loveless", target.focusAlbum)
    }
}

@Test
fun `onTrackInfoTapped emits toast and no nav on null resolve`() = runTest {
    val api = mockk<YTMusicApiClient>()
    coEvery { api.resolveArtist(any()) } returns null
    val vm = buildViewModel(api = api, track = trackOf(artist = "Nobody", album = ""))

    vm.userMessages.test {
        vm.onTrackInfoTapped()
        assertEquals("Couldn't find this artist", awaitItem())
    }
}

@Test
fun `onTrackInfoTapped is a no-op when nothing is playing`() = runTest {
    val api = mockk<YTMusicApiClient>()
    val vm = buildViewModel(api = api, track = null)
    vm.onTrackInfoTapped()
    coVerify(exactly = 0) { api.resolveArtist(any()) }
}
```

Notes for the implementer: add small `buildViewModel(api = ..., track = ...)` and `trackOf(...)` helpers if the file lacks them (follow the existing construction; seed `currentTrack` via the same fake `PlayerRepository`/state path the other tests use). Imports: `app.cash.turbine.test`, `io.mockk.*`, `com.stash.data.ytmusic.YTMusicApiClient`, `com.stash.data.ytmusic.model.ArtistSummary`.

- [ ] **Step 4: Run tests to verify they fail**

Run: `./gradlew :feature:nowplaying:testDebugUnitTest --tests "com.stash.feature.nowplaying.NowPlayingViewModelTest"`
Expected: FAIL — `resolveArtist`/`onTrackInfoTapped`/`artistNavEvents` unresolved.

- [ ] **Step 5: Write minimal implementation**

Add the injected dependency to the constructor:

```kotlin
private val ytMusicApiClient: com.stash.data.ytmusic.YTMusicApiClient,
```

Add the nav-target type (top-level in the VM file):

```kotlin
/** One-shot target for navigating from Now Playing to an artist profile. */
data class ArtistNavTarget(
    val artistId: String,
    val name: String,
    val avatarUrl: String?,
    val focusAlbum: String?,
)
```

Inside the class:

```kotlin
private val _artistNavEvents = MutableSharedFlow<ArtistNavTarget>(extraBufferCapacity = 1)
val artistNavEvents: SharedFlow<ArtistNavTarget> = _artistNavEvents.asSharedFlow()

private val _resolvingArtist = MutableStateFlow(false)
val resolvingArtist: StateFlow<Boolean> = _resolvingArtist.asStateFlow()

/**
 * Tap on the Now Playing track block. Resolves the playing artist's NAME to
 * a YT browseId and emits an [ArtistNavTarget] carrying the current album as
 * `focusAlbum`. Prefers the clean `albumArtist`, falling back to `artist`.
 * No-op when nothing is playing or a resolve is already in flight (double-tap
 * guard). Resolve miss/failure → snackbar, no nav.
 */
fun onTrackInfoTapped() {
    val track = _uiState.value.currentTrack ?: return
    if (_resolvingArtist.value) return
    val name = track.albumArtist.ifBlank { track.artist }
    if (name.isBlank()) {
        viewModelScope.launch { _userMessages.emit("Couldn't find this artist") }
        return
    }
    _resolvingArtist.value = true
    viewModelScope.launch {
        try {
            val artist = ytMusicApiClient.resolveArtist(name)
            if (artist != null) {
                _artistNavEvents.emit(
                    ArtistNavTarget(
                        artistId = artist.id,
                        name = artist.name,
                        avatarUrl = artist.avatarUrl,
                        focusAlbum = track.album.ifBlank { null },
                    ),
                )
            } else {
                _userMessages.emit("Couldn't find this artist")
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            _userMessages.emit("Couldn't find this artist")
        } finally {
            _resolvingArtist.value = false
        }
    }
}
```

Ensure imports exist: `MutableStateFlow`, `StateFlow`, `asStateFlow`, `MutableSharedFlow`, `SharedFlow`, `asSharedFlow`, `CancellationException` (most are already imported in the file).

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :feature:nowplaying:testDebugUnitTest --tests "com.stash.feature.nowplaying.NowPlayingViewModelTest"`
Expected: PASS (existing tests + 3 new).

- [ ] **Step 7: Commit**

```bash
git add feature/nowplaying/build.gradle.kts \
        feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingViewModel.kt \
        feature/nowplaying/src/test/kotlin/com/stash/feature/nowplaying/NowPlayingViewModelTest.kt
git commit -m "feat(nowplaying): onTrackInfoTapped resolves artist + emits nav event"
```

---

## Task 4: NowPlayingScreen — clickable track block + nav wiring

Make the title/artist block tappable (with chevron + resolving spinner), collect the nav event, expose `onNavigateToArtist`. Completes the compile started in Task 2.

**Files:**
- Modify: `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingScreen.kt:87-338`

**No unit test** (Compose UI + navigation glue) — verified on-device in Task 7.

- [ ] **Step 1: Add the `onNavigateToArtist` param**

```kotlin
@Composable
fun NowPlayingScreen(
    onDismiss: () -> Unit,
    onNavigateToArtist: (id: String, name: String, avatarUrl: String?, focusAlbum: String?) -> Unit,
    viewModel: NowPlayingViewModel = hiltViewModel(),
) {
```

- [ ] **Step 2: Collect the nav event + resolving state**

Near the other `LaunchedEffect`/collectors at the top of the composable body:

```kotlin
LaunchedEffect(viewModel) {
    viewModel.artistNavEvents.collect { t ->
        onNavigateToArtist(t.artistId, t.name, t.avatarUrl, t.focusAlbum)
    }
}
val resolvingArtist by viewModel.resolvingArtist.collectAsStateWithLifecycle()
```

- [ ] **Step 3: Make the track-info block clickable + chevron**

Wrap the "-- Track info --" `Row` and the artist/album `Text` (lines ~292-338) in a `Column` with a click gated on a non-null track. Keep the existing children verbatim inside the new `Column`:

```kotlin
Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = Modifier
        .fillMaxWidth()
        .then(
            if (track != null) Modifier.clickable(enabled = !resolvingArtist) {
                viewModel.onTrackInfoTapped()
            } else Modifier,
        ),
) {
    // existing title Row + FlacBadge, Spacer, artist/album Text go here unchanged.
    // Append a trailing affordance next to the title Row when track != null:
    //   if (resolvingArtist) CircularProgressIndicator(Modifier.size(16.dp), color = Color.White)
    //   else Icon(Icons.Default.ChevronRight, contentDescription = "Open artist",
    //             tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
}
```

Add imports: `androidx.compose.foundation.clickable`, `androidx.compose.material.icons.Icons`, `androidx.compose.material.icons.filled.ChevronRight`, `androidx.compose.material3.Icon`, `androidx.compose.material3.CircularProgressIndicator`, `androidx.compose.foundation.layout.Column`.

- [ ] **Step 4: Build the modules to verify they compile**

Run: `./gradlew :feature:nowplaying:compileDebugKotlin :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (Task 2's nav call now resolves).

- [ ] **Step 5: Commit**

```bash
git add feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingScreen.kt
git commit -m "feat(nowplaying): tappable track block navigates to artist profile"
```

---

## Task 5: Album-focus matching + `ArtistProfileViewModel` reads `focusAlbum`

Pure matcher (which shelf + index a `focusAlbum` resolves to) and plumb `focusAlbum` into the profile UI state.

**Files:**
- Modify: `feature/search/src/main/kotlin/com/stash/feature/search/ArtistProfileUiState.kt`
- Modify: `feature/search/src/main/kotlin/com/stash/feature/search/ArtistProfileViewModel.kt`
- Create: `feature/search/src/main/kotlin/com/stash/feature/search/AlbumFocus.kt`
- Test: `feature/search/src/test/kotlin/com/stash/feature/search/AlbumFocusTest.kt`

- [ ] **Step 1: Write the failing test** `AlbumFocusTest.kt`

```kotlin
package com.stash.feature.search

import com.stash.data.ytmusic.model.AlbumSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlbumFocusTest {
    private fun album(t: String) = AlbumSummary(id = t, title = t, artist = "", thumbnailUrl = null, year = null)
    private val albums = listOf(album("m b v"), album("EPs 1988-1991"))
    private val singles = listOf(album("you made me realise"), album("Feed Me With Your Kiss"))

    @Test fun `matches album case and space insensitively`() {
        val r = findAlbumFocus("M B V", albums, singles)
        assertEquals(AlbumShelf.ALBUMS, r?.shelf); assertEquals(0, r?.index)
    }

    @Test fun `matches single when not in albums`() {
        val r = findAlbumFocus("you made me realise", albums, singles)
        assertEquals(AlbumShelf.SINGLES, r?.shelf); assertEquals(0, r?.index)
    }

    @Test fun `null focus returns null`() = assertNull(findAlbumFocus(null, albums, singles))

    @Test fun `no match returns null`() = assertNull(findAlbumFocus("Loveless", albums, singles))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :feature:search:testDebugUnitTest --tests "com.stash.feature.search.AlbumFocusTest"`
Expected: FAIL — unresolved `findAlbumFocus`/`AlbumShelf`.

- [ ] **Step 3: Write minimal implementation** `AlbumFocus.kt`

```kotlin
package com.stash.feature.search

import com.stash.data.ytmusic.model.AlbumSummary

enum class AlbumShelf { ALBUMS, SINGLES }

/** Where a focused album lives on the profile: which shelf and which card. */
data class AlbumFocusTarget(val shelf: AlbumShelf, val index: Int)

private fun norm(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ")

/**
 * Locate [focusAlbum] in the profile's shelves. Albums win over Singles/EPs
 * when both contain a title match. Case-, edge-, and internal-whitespace-
 * insensitive ("m b v" == "M B V"). Null when the album isn't present (e.g.
 * missing from YouTube's catalog) — the caller then just lands on the top.
 */
fun findAlbumFocus(
    focusAlbum: String?,
    albums: List<AlbumSummary>,
    singles: List<AlbumSummary>,
): AlbumFocusTarget? {
    val target = focusAlbum?.let(::norm)?.takeIf { it.isNotEmpty() } ?: return null
    albums.indexOfFirst { norm(it.title) == target }
        .takeIf { it >= 0 }?.let { return AlbumFocusTarget(AlbumShelf.ALBUMS, it) }
    singles.indexOfFirst { norm(it.title) == target }
        .takeIf { it >= 0 }?.let { return AlbumFocusTarget(AlbumShelf.SINGLES, it) }
    return null
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :feature:search:testDebugUnitTest --tests "com.stash.feature.search.AlbumFocusTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Add `focusAlbum` to UI state + VM**

In `ArtistProfileUiState.kt`, add a field (default null) to the data class:

```kotlin
val focusAlbum: String? = null,
```

In `ArtistProfileViewModel.kt`, read the nav arg alongside the existing ones (mirroring `initialName`/`initialAvatar`):

```kotlin
private val initialFocusAlbum: String? = savedStateHandle["focusAlbum"]
```

Seed it into the initial `_uiState` value (`ArtistProfileUiState(hero = ..., status = ..., focusAlbum = initialFocusAlbum)`). Since `apply(...)` rebuilds state via `_uiState.value.copy(...)`, `focusAlbum` is preserved automatically — do NOT overwrite it in `apply`.

- [ ] **Step 6: Run the search module tests + compile**

Run: `./gradlew :feature:search:testDebugUnitTest --tests "com.stash.feature.search.*" :feature:search:compileDebugKotlin`
Expected: PASS + BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add feature/search/src/main/kotlin/com/stash/feature/search/AlbumFocus.kt \
        feature/search/src/test/kotlin/com/stash/feature/search/AlbumFocusTest.kt \
        feature/search/src/main/kotlin/com/stash/feature/search/ArtistProfileUiState.kt \
        feature/search/src/main/kotlin/com/stash/feature/search/ArtistProfileViewModel.kt
git commit -m "feat(search): album-focus matcher + focusAlbum in ArtistProfile state"
```

---

## Task 6: Two-axis scroll + highlight on the Artist Profile

Hoist the outer `LazyColumn` state, scroll to the matched section, scroll the row to the card, and flash a highlight ring.

**Files:**
- Modify: `feature/search/src/main/kotlin/com/stash/feature/search/ArtistProfileScreen.kt`
- Modify: `feature/search/src/main/kotlin/com/stash/feature/search/AlbumsRow.kt`
- Modify: `feature/search/src/main/kotlin/com/stash/feature/search/SinglesRow.kt`

**No unit test** (Compose scroll/animation) — verified on-device in Task 7. The matching logic it depends on is already tested (Task 5).

- [ ] **Step 1: Hoist the outer `LazyColumn` state + compute the section item index**

In `ArtistProfileScreen`, create `val listState = rememberLazyListState()` and pass `state = listState` to the `LazyColumn`. Because sections are emitted conditionally, compute the target outer index from presence flags (fixed section order: hero=0; then Popular header+row if `popular` non-empty; then Albums header+row; then Singles header+row):

```kotlin
val focus = remember(state.focusAlbum, state.albums, state.singles) {
    findAlbumFocus(state.focusAlbum, state.albums, state.singles)
}
// Outer LazyColumn item index of the section HEADER we want on screen.
val sectionOuterIndex = remember(focus, state.popular, state.albums) {
    if (focus == null) return@remember null
    var idx = 1 // hero
    if (state.popular.isNotEmpty()) idx += 2 // Popular header + row
    when (focus.shelf) {
        AlbumShelf.ALBUMS -> idx            // Albums header
        AlbumShelf.SINGLES -> idx + (if (state.albums.isNotEmpty()) 2 else 0) // + Albums block
    }
}
var focusHandled by rememberSaveable { mutableStateOf(false) }
LaunchedEffect(sectionOuterIndex, state.status) {
    val target = sectionOuterIndex ?: return@LaunchedEffect
    if (focusHandled) return@LaunchedEffect
    if (state.status is ArtistProfileStatus.Fresh || state.status is ArtistProfileStatus.Stale) {
        focusHandled = true
        listState.animateScrollToItem(target)
    }
}
```

Imports for `ArtistProfileScreen`: `androidx.compose.foundation.lazy.rememberLazyListState`, `androidx.compose.runtime.getValue`, `androidx.compose.runtime.setValue`, `androidx.compose.runtime.mutableStateOf`, `androidx.compose.runtime.remember`, `androidx.compose.runtime.saveable.rememberSaveable` (some already present). `findAlbumFocus`, `AlbumShelf`, `AlbumFocusTarget` are same-package (no import).

- [ ] **Step 2: Pass the focus into the matching row**

In `contentSections(...)`, add a `focus: AlbumFocusTarget?` parameter. For the `AlbumsRow` call, pass `focusIndex = focus?.takeIf { it.shelf == AlbumShelf.ALBUMS }?.index`; for the `SinglesRow` call, pass `focusIndex = focus?.takeIf { it.shelf == AlbumShelf.SINGLES }?.index`. Thread `focus` from `ArtistProfileScreen` into both `contentSections(...)` call sites (the Loading-with-popular branch and the Fresh/Stale branch).

- [ ] **Step 3: Row-level scroll + highlight in `AlbumsRow`; forward from `SinglesRow`**

`SinglesRow` delegates to `AlbumsRow` (it has no `LazyRow` of its own), so the scroll/highlight logic lives ONCE in `AlbumsRow`. Add a `focusIndex: Int? = null` param to `SinglesRow` and pass it through:

```kotlin
@Composable
fun SinglesRow(
    singles: List<AlbumSummary>,
    onClick: (AlbumSummary) -> Unit,
    modifier: Modifier = Modifier,
    focusIndex: Int? = null,
) {
    AlbumsRow(albums = singles, onClick = onClick, modifier = modifier, focusIndex = focusIndex)
}
```

Then implement the scroll + highlight in `AlbumsRow` (`AlbumSquareCard` already has a `modifier` param):

```kotlin
@Composable
fun AlbumsRow(
    albums: List<AlbumSummary>,
    onClick: (AlbumSummary) -> Unit,
    modifier: Modifier = Modifier,
    focusIndex: Int? = null,
) {
    val rowState = rememberLazyListState()
    var highlighted by remember { mutableStateOf(false) }
    LaunchedEffect(focusIndex, albums) {
        val i = focusIndex ?: return@LaunchedEffect
        if (i in albums.indices) {
            rowState.animateScrollToItem(i)
            highlighted = true
            kotlinx.coroutines.delay(1500)
            highlighted = false
        }
    }
    LazyRow(
        state = rowState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(albums, key = { _, a -> a.id }) { index, album ->
            val ring = if (highlighted && index == focusIndex) {
                Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
            } else Modifier
            AlbumSquareCard(
                title = album.title,
                artist = album.artist,
                thumbnailUrl = album.thumbnailUrl,
                year = album.year,
                onClick = { onClick(album) },
                modifier = ring,
            )
        }
    }
}
```

Imports to add to `AlbumsRow.kt`: `androidx.compose.foundation.lazy.rememberLazyListState`, `androidx.compose.foundation.lazy.itemsIndexed`, `androidx.compose.foundation.border`, `androidx.compose.foundation.shape.RoundedCornerShape`, `androidx.compose.material3.MaterialTheme`, `androidx.compose.runtime.getValue`, `androidx.compose.runtime.setValue`, `androidx.compose.runtime.mutableStateOf`, `androidx.compose.runtime.remember`, `androidx.compose.runtime.LaunchedEffect`. (Remove the now-unused `androidx.compose.foundation.lazy.items` import.)

- [ ] **Step 4: Compile the module**

Run: `./gradlew :feature:search:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add feature/search/src/main/kotlin/com/stash/feature/search/ArtistProfileScreen.kt \
        feature/search/src/main/kotlin/com/stash/feature/search/AlbumsRow.kt \
        feature/search/src/main/kotlin/com/stash/feature/search/SinglesRow.kt
git commit -m "feat(search): scroll to + highlight the focused album on artist profile"
```

---

## Task 7: Full build, install, device verification

Assemble the app, install on the connected device, and drive the real flow.

**Files:** none (integration).

- [ ] **Step 1: Full unit-test sweep for touched modules**

Run: `./gradlew :data:ytmusic:testDebugUnitTest :feature:nowplaying:testDebugUnitTest :feature:search:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all green.

- [ ] **Step 2: Assemble + install (per @feedback_install_after_fix)**

Run: `./gradlew :app:installDebug`
Expected: BUILD SUCCESSFUL, `Installed on 1 device`.

- [ ] **Step 3: Device verification (per @superpowers:verification-before-completion)**

Before tapping, confirm the app is foreground (`adb shell dumpsys activity activities | grep mResumedActivity` — it is the user's DAILY phone; stop if another app is foreground). Then:
  1. Play a track by a well-catalogued artist (e.g. search "Radiohead", play a song). Open the full Now Playing screen.
  2. Tap the title/artist text block → the Artist Profile opens for that artist; if the album is in the Albums/Singles shelf, it is scrolled into view and briefly ringed.
  3. Tap a track whose artist is ambiguous ("My Bloody Valentine") → profile still resolves (verifies the artists-filter path).
  4. Airplane-mode a tap → "Couldn't find this artist" toast, no navigation, no crash.
  5. Confirm `adb logcat` shows the resolve happening and no exceptions.

- [ ] **Step 4: Report evidence**

Paste the test summary + `installDebug` result + a one-line note of each device step's outcome. Do NOT claim done until steps 1-3 pass on-device.

---

## Done criteria

- All new unit tests green (`resolveArtist` ×4, `onTrackInfoTapped` ×3, `findAlbumFocus` ×4) and existing module tests still green.
- App installs; tapping the Now Playing track opens the correct artist profile; the playing album is scrolled-to + highlighted when present; failures toast without crashing.
- Work is on `feat/nowplaying-tap-to-artist`; merge handled by the finishing-a-development-branch skill after user sign-off.
