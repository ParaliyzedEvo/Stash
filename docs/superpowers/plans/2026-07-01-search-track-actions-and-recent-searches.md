# Search Track Actions + Recent Searches Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a per-track ⋮ menu (Play next / Add to queue / Add to playlist) to the shared search-result row across all three search surfaces, and persist recent search queries in the Search tab.

**Architecture:** Both features ride existing rails. Action logic lives in the already-shared `TrackActionsDelegate` (injected by all three search VMs). The ⋮ menu is added once to the shared `PreviewDownloadRow`. Recent searches are a small new DataStore-backed `RecentSearchesStore` owned by `SearchViewModel`, recorded only on committed searches (IME action / result tap) and shown in the empty-state.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Hilt, Preferences DataStore, Room (TrackDao), coroutines/Flow. Tests: JUnit4 + Truth + MockK; Robolectric for the DataStore store.

**Spec:** `docs/superpowers/specs/2026-07-01-search-track-actions-and-recent-searches-design.md`

---

## Background: verified facts (read before starting)

Confirmed against the current tree — do not re-derive:

- **Shared row:** `feature/search/src/main/kotlin/com/stash/feature/search/PreviewDownloadRow.kt` is rendered by all three surfaces: `SearchScreen.kt` (Songs section, line ~330 and TopResult track, line ~296), `PopularTracksSection.kt` (line ~62), `AlbumDiscoveryScreen.kt` (line ~174). Its trailing controls are a preview `IconButton` then a download `Box`. New callbacks must default to no-op so existing call sites compile before wiring.
- **Delegate:** `core/media/src/main/kotlin/com/stash/core/media/actions/TrackActionsDelegate.kt` is `@ViewModelScoped`, injected by `SearchViewModel`, `ArtistProfileViewModel`, `AlbumDiscoveryViewModel`. It already injects `playerRepository: com.stash.core.media.PlayerRepository`, `trackDao: com.stash.core.data.db.dao.TrackDao`, and has `_userMessages: MutableSharedFlow<String>` (buffered) exposed as `userMessages`. It uses `scope().launch { … }` for actions and re-throws `CancellationException` in `catch` blocks. There is NO `TrackItem → Track` mapper here today.
- **PlayerRepository:** `addNext(track: Track)` and `addToQueue(track: Track)` exist (`PlayerRepository.kt:147/153`), both `suspend`, both return `Unit`, both silently no-op if the track can't resolve to a media item.
- **DB insert precedent:** `SearchDownloadCoordinator.upsertSearchTrack` (private, `SearchDownloadCoordinator.kt:394`) shows the exact insert-if-absent sequence: `blocklistGuard.isBlocked(artist, title, spotifyUri=null, youtubeId)` → early return if blocked; else `trackDao.findByYoutubeId(videoId) ?: trackDao.findByCanonicalIdentity(canonicalize(title), canonicalize(artist))`; else `trackDao.insert(TrackEntity(...))` returning the new id. `canonicalize` is a private `SearchDownloadCoordinator.kt:518` helper. `BlocklistGuard` lives at `com.stash.core.data.blocklist.BlocklistGuard`. This path is NOT reusable (private + coupled to a completed-download `FinalizeResult`), so we add a small new helper.
- **Playlist picker:** `core/ui/src/main/kotlin/com/stash/core/ui/components/SaveToPlaylistSheet.kt` — `SaveToPlaylistSheet(playlists: List<PlaylistInfo>, onSaveToPlaylist: (Long) -> Unit, onCreatePlaylist: (String) -> Unit, onDismiss: () -> Unit)`. `PlaylistInfo(id, name, trackCount)`. `MusicRepository.getUserCreatedPlaylists(): Flow<List<Playlist>>` and `createPlaylist(name): Long` and `addTrackToPlaylist(trackId, playlistId)` exist. Existing create-then-add precedent: `ArtistDetailViewModel.createPlaylistAndAddTrack` (line 190).
- **Track model:** `Track` (domain) is constructed as in `ArtistProfileViewModel.toDomainTrack` (line 240): `id = videoId.hashCode().toLong()`, `youtubeId = videoId`, `source = MusicSource.YOUTUBE`, `isStreamable = true`, `durationMs = (durationSeconds*1000).toLong()`, `albumArtUrl = thumbnailUrl`. `TrackItem` fields: `videoId, title, artist, durationSeconds, thumbnailUrl, album?, albumArtist?`.
- **Search query pipeline:** `SearchViewModel.onQueryChanged(query)` updates `_uiState` + `queryFlow`; `onResultTap(item)` handles a result tap; `SearchBar` (`SearchScreen.kt:167`) has `KeyboardActions(onSearch = { keyboardController?.hide() })`; the empty state is `SearchStatus.Idle -> EmptySearchPrompt()` (`SearchScreen.kt:136`).
- **DataStore precedent for tests:** `data/download/src/test/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxCredentialStoreTest.kt` (Robolectric + `ApplicationProvider` + real temp DataStore, wiped in `@Before`).
- **Build/test env:** Use the Gradle daemon (never `--no-daemon` on this box — BindException). Always filter with `--tests`. `:core:media:testDebugUnitTest` has one flaky network test — always filter to the specific class.

---

## File Structure

**Feature 1 — per-track ⋮ actions:**
- Modify: `core/media/.../actions/TrackActionsDelegate.kt` — add `playNext`, `addToQueue`, `addToPlaylist`, `userPlaylists`, a private `TrackItem.toDomainTrack()`, and a private `ensureTrackPersisted()` insert-if-absent helper. Needs new injected deps: `musicRepository` (playlists + link) and `blocklistGuard`.
- Modify: `feature/search/.../PreviewDownloadRow.kt` — add ⋮ `IconButton` + `DropdownMenu` + 3 callbacks.
- Modify: `feature/search/.../SearchScreen.kt`, `PopularTracksSection.kt`, `AlbumDiscoveryScreen.kt` — thread the 3 callbacks; host the `SaveToPlaylistSheet`.
- Modify: `SearchViewModel.kt`, `ArtistProfileViewModel.kt`, `AlbumDiscoveryViewModel.kt` — expose `delegate.userPlaylists` and a "pending add-to-playlist item" for the sheet.

**Feature 2 — recent searches:**
- Create: `feature/search/.../RecentSearchesStore.kt` — DataStore-backed MRU query list.
- Create: `feature/search/src/test/.../RecentSearchesStoreTest.kt`.
- Modify: `SearchViewModel.kt` — inject store, expose `recentSearches`, record on commit, remove/clear.
- Modify: `SearchScreen.kt` — render recent list in the Idle state; commit-on-IME-search.

The two features are independent; Feature 2 can ship without Feature 1. Do Feature 1 first (it's the larger request), then Feature 2.

---

# FEATURE 1 — Per-track ⋮ actions

## Task 1: Delegate — Play next & Add to queue

**Files:**
- Modify: `core/media/src/main/kotlin/com/stash/core/media/actions/TrackActionsDelegate.kt`
- Test: `core/media/src/test/kotlin/com/stash/core/media/actions/TrackActionsDelegateQueueActionsTest.kt` (create)

- [ ] **Step 1: Write the failing test**

Create `TrackActionsDelegateQueueActionsTest.kt`. Mock the 8 constructor deps (MockK). Bind a `TestScope`. Assert `playNext`/`addToQueue` call the right repo method with a `Track` carrying the mapped fields, and that a `userMessages` string is emitted.

```kotlin
package com.stash.core.media.actions

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.stash.core.model.MusicSource
import com.stash.core.model.Track
import com.stash.core.model.TrackItem
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrackActionsDelegateQueueActionsTest {

    private val playerRepository: com.stash.core.media.PlayerRepository = mockk(relaxed = true)
    private val musicRepository: com.stash.core.data.repository.MusicRepository = mockk(relaxed = true)
    private val blocklistGuard: com.stash.core.data.blocklist.BlocklistGuard = mockk(relaxed = true)
    private val trackDao: com.stash.core.data.db.dao.TrackDao = mockk(relaxed = true)

    private fun delegate() = TrackActionsDelegate(
        previewPlayer = mockk(relaxed = true),
        searchPreviewMediaSource = mockk(relaxed = true),
        previewUrlExtractor = mockk(relaxed = true),
        previewUrlCache = mockk(relaxed = true),
        trackDao = trackDao,
        searchDownloadCoordinator = mockk(relaxed = true),
        playerRepository = playerRepository,
        streamingPreference = mockk(relaxed = true),
        musicRepository = musicRepository,
        blocklistGuard = blocklistGuard,
    )

    private val item = TrackItem(
        videoId = "abc123", title = "Song", artist = "Artist",
        durationSeconds = 200.0, thumbnailUrl = "http://art",
    )

    @Test
    fun `addToQueue maps TrackItem and calls repo with streamable Track`() = runTest {
        val d = delegate().apply { bindToScope(this@runTest.backgroundScope) }
        val slot = slot<Track>()
        coEvery { playerRepository.addToQueue(capture(slot)) } returns Unit

        d.userMessages.test {
            d.addToQueue(item)
            assertThat(awaitItem()).isEqualTo("Added to queue")
        }
        coVerify(exactly = 1) { playerRepository.addToQueue(any<Track>()) }
        assertThat(slot.captured.youtubeId).isEqualTo("abc123")
        assertThat(slot.captured.isStreamable).isTrue()
        assertThat(slot.captured.source).isEqualTo(MusicSource.YOUTUBE)
        assertThat(slot.captured.id).isEqualTo("abc123".hashCode().toLong())
    }

    @Test
    fun `playNext calls addNext and emits message`() = runTest {
        val d = delegate().apply { bindToScope(this@runTest.backgroundScope) }
        d.userMessages.test {
            d.playNext(item)
            assertThat(awaitItem()).isEqualTo("Playing next")
        }
        coVerify(exactly = 1) { playerRepository.addNext(any<Track>()) }
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails to compile**

Run: `./gradlew :core:media:testDebugUnitTest --tests "com.stash.core.media.actions.TrackActionsDelegateQueueActionsTest"`
Expected: FAIL — unresolved `musicRepository`/`blocklistGuard` constructor params, `addToQueue(item)`/`playNext(item)` unresolved.

- [ ] **Step 3: Add the two new constructor deps + the mapper + the two actions**

In `TrackActionsDelegate.kt`, add to the constructor (after `streamingPreference`):

```kotlin
    private val musicRepository: com.stash.core.data.repository.MusicRepository,
    private val blocklistGuard: com.stash.core.data.blocklist.BlocklistGuard,
```

Add a private mapper (near the bottom, before `companion object`):

```kotlin
    /**
     * Maps a search-surface [TrackItem] to a domain [Track] for the queue.
     * No existing mapper fits — preview/download pass TrackItem through
     * untouched. Mirrors ArtistProfileViewModel.toDomainTrack: the synthetic
     * id + youtubeId let PlayerRepositoryImpl.resolveTrackToMediaItem's
     * toEntity() fallback stream-resolve a track that isn't in the DB.
     */
    private fun TrackItem.toDomainTrack(): com.stash.core.model.Track =
        com.stash.core.model.Track(
            id = videoId.hashCode().toLong(),
            title = title,
            artist = artist,
            album = album.orEmpty(),
            durationMs = (durationSeconds * 1000).toLong(),
            albumArtUrl = thumbnailUrl,
            youtubeId = videoId,
            source = com.stash.core.model.MusicSource.YOUTUBE,
            isStreamable = true,
        )
```

Add the two actions:

```kotlin
    /** Insert [item] right after the current track. Feedback is the toast —
     *  addNext silently no-ops only when streaming is off AND the track isn't
     *  downloaded, a state in which the whole search-stream surface is inert. */
    fun playNext(item: TrackItem) {
        scope().launch {
            try {
                playerRepository.addNext(item.toDomainTrack())
                _userMessages.tryEmit("Playing next")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "playNext failed for ${item.videoId}", e)
                _userMessages.tryEmit("Couldn't add to queue")
            }
        }
    }

    /** Append [item] to the end of the queue. */
    fun addToQueue(item: TrackItem) {
        scope().launch {
            try {
                playerRepository.addToQueue(item.toDomainTrack())
                _userMessages.tryEmit("Added to queue")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "addToQueue failed for ${item.videoId}", e)
                _userMessages.tryEmit("Couldn't add to queue")
            }
        }
    }
```

Verify `Track` constructor param names/types against `core/model/.../Track.kt` before finalizing (adjust `album`/`isStreamable` if the model differs).

- [ ] **Step 4: Add the two deps to the Hilt-provided graph**

`TrackActionsDelegate` is `@Inject constructor` + `@ViewModelScoped`; `MusicRepository` and `BlocklistGuard` are already Hilt-bound singletons used elsewhere, so no module change is needed — Hilt resolves them. Confirm `:core:media` already depends on the modules exposing them (it injects `PlayerRepository` and `TrackDao` from the same layers). If a Gradle dep is missing, add `implementation(project(":core:data"))`-style entries already present.

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :core:media:testDebugUnitTest --tests "com.stash.core.media.actions.TrackActionsDelegateQueueActionsTest"`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/actions/TrackActionsDelegate.kt core/media/src/test/kotlin/com/stash/core/media/actions/TrackActionsDelegateQueueActionsTest.kt
git commit -m "feat(search): TrackActionsDelegate playNext + addToQueue"
```

---

## Task 2: Delegate — Add to playlist (insert-if-absent + link) + userPlaylists

**Files:**
- Modify: `core/media/.../actions/TrackActionsDelegate.kt`
- Test: `core/media/src/test/kotlin/com/stash/core/media/actions/TrackActionsDelegatePlaylistTest.kt` (create)

- [ ] **Step 1: Write the failing test**

```kotlin
package com.stash.core.media.actions

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.TrackItem
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrackActionsDelegatePlaylistTest {

    private val musicRepository: com.stash.core.data.repository.MusicRepository = mockk(relaxed = true)
    private val blocklistGuard: com.stash.core.data.blocklist.BlocklistGuard = mockk(relaxed = true)
    private val trackDao: com.stash.core.data.db.dao.TrackDao = mockk(relaxed = true)
    private val playerRepository: com.stash.core.media.PlayerRepository = mockk(relaxed = true)

    private fun delegate() = TrackActionsDelegate(
        previewPlayer = mockk(relaxed = true),
        searchPreviewMediaSource = mockk(relaxed = true),
        previewUrlExtractor = mockk(relaxed = true),
        previewUrlCache = mockk(relaxed = true),
        trackDao = trackDao,
        searchDownloadCoordinator = mockk(relaxed = true),
        playerRepository = playerRepository,
        streamingPreference = mockk(relaxed = true),
        musicRepository = musicRepository,
        blocklistGuard = blocklistGuard,
    )

    private val item = TrackItem(
        videoId = "vid", title = "T", artist = "A",
        durationSeconds = 100.0, thumbnailUrl = null,
    )

    @Test
    fun `addToPlaylist links existing track without inserting`() = runTest {
        val existing = mockk<com.stash.core.data.db.entity.TrackEntity>(relaxed = true) {
            coEvery { id } returns 42L
        }
        coEvery { blocklistGuard.isBlocked(any(), any(), any(), any()) } returns false
        coEvery { trackDao.findByYoutubeId("vid") } returns existing

        val d = delegate().apply { bindToScope(this@runTest.backgroundScope) }
        d.addToPlaylist(item, playlistId = 7L)
        this.testScheduler.advanceUntilIdle()

        coVerify(exactly = 0) { trackDao.insert(any()) }
        coVerify(exactly = 1) { musicRepository.addTrackToPlaylist(42L, 7L) }
    }

    @Test
    fun `addToPlaylist inserts stub when absent then links`() = runTest {
        coEvery { blocklistGuard.isBlocked(any(), any(), any(), any()) } returns false
        coEvery { trackDao.findByYoutubeId("vid") } returns null
        coEvery { trackDao.findByCanonicalIdentity(any(), any()) } returns null
        coEvery { trackDao.insert(any()) } returns 99L

        val d = delegate().apply { bindToScope(this@runTest.backgroundScope) }
        d.addToPlaylist(item, playlistId = 7L)
        this.testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { trackDao.insert(any()) }
        coVerify(exactly = 1) { musicRepository.addTrackToPlaylist(99L, 7L) }
    }

    @Test
    fun `addToPlaylist refuses a blocklisted track`() = runTest {
        coEvery { blocklistGuard.isBlocked(any(), any(), any(), any()) } returns true

        val d = delegate().apply { bindToScope(this@runTest.backgroundScope) }
        d.addToPlaylist(item, playlistId = 7L)
        this.testScheduler.advanceUntilIdle()

        coVerify(exactly = 0) { trackDao.insert(any()) }
        coVerify(exactly = 0) { musicRepository.addTrackToPlaylist(any(), any()) }
    }
}
```

- [ ] **Step 2: Run to confirm it fails**

Run: `./gradlew :core:media:testDebugUnitTest --tests "com.stash.core.media.actions.TrackActionsDelegatePlaylistTest"`
Expected: FAIL — `addToPlaylist` unresolved.

- [ ] **Step 3: Implement `ensureTrackPersisted` + `addToPlaylist` + `userPlaylists`**

Confirm the exact `canonicalize` used by the coordinator (`SearchDownloadCoordinator.kt:518`) and replicate it (it's a small normalize). Add a private helper mirroring `upsertSearchTrack`'s lookup, minus the download columns:

```kotlin
    /**
     * Returns the DB id for [item], inserting a stub row if absent. Runs the
     * BlocklistGuard first (v0.9.15: insert paths that skip the guard resurrect
     * blocked tracks). Returns null when blocked. No download columns are set —
     * this is a streamable library entry, not a downloaded file.
     */
    private suspend fun ensureTrackPersisted(item: TrackItem): Long? {
        if (blocklistGuard.isBlocked(
                artist = item.artist, title = item.title,
                spotifyUri = null, youtubeId = item.videoId,
            )
        ) {
            Log.d(TAG, "addToPlaylist refused blocked: ${item.artist} - ${item.title}")
            return null
        }
        val existing = trackDao.findByYoutubeId(item.videoId)
            ?: trackDao.findByCanonicalIdentity(
                title = canonicalize(item.title),
                artist = canonicalize(item.artist),
            )
        return existing?.id ?: trackDao.insert(
            com.stash.core.data.db.entity.TrackEntity(
                title = item.title,
                artist = item.artist,
                album = item.album.orEmpty(),
                albumArtist = item.albumArtist.orEmpty(),
                youtubeId = item.videoId,
                canonicalTitle = canonicalize(item.title),
                canonicalArtist = canonicalize(item.artist),
                durationMs = (item.durationSeconds * 1000).toLong(),
                source = com.stash.core.model.MusicSource.YOUTUBE,
                albumArtUrl = item.thumbnailUrl,
            )
        )
    }

    // EXACT copy of SearchDownloadCoordinator.canonicalize (line 518) — the
    // bodies MUST match or the delegate's findByCanonicalIdentity computes a
    // different key than the download path and duplicate rows appear.
    private fun canonicalize(s: String): String =
        s.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Add [item] to an existing playlist. Inserts a streamable stub if the
     *  track isn't in the library yet (NOT downloaded). */
    fun addToPlaylist(item: TrackItem, playlistId: Long) {
        scope().launch {
            try {
                val trackId = ensureTrackPersisted(item) ?: run {
                    _userMessages.tryEmit("Can't add a blocked track")
                    return@launch
                }
                musicRepository.addTrackToPlaylist(trackId, playlistId)
                _userMessages.tryEmit("Added to playlist")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "addToPlaylist failed for ${item.videoId}", e)
                _userMessages.tryEmit("Couldn't add to playlist")
            }
        }
    }

    /** Create a new playlist named [name] and add [item] to it. */
    fun createPlaylistAndAdd(item: TrackItem, name: String) {
        scope().launch {
            try {
                val trackId = ensureTrackPersisted(item) ?: run {
                    _userMessages.tryEmit("Can't add a blocked track")
                    return@launch
                }
                val playlistId = musicRepository.createPlaylist(name)
                musicRepository.addTrackToPlaylist(trackId, playlistId)
                _userMessages.tryEmit("Added to playlist")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "createPlaylistAndAdd failed for ${item.videoId}", e)
                _userMessages.tryEmit("Couldn't add to playlist")
            }
        }
    }

    /** User-created playlists for the picker. Mirrors ArtistDetailViewModel. */
    val userPlaylists: kotlinx.coroutines.flow.Flow<List<com.stash.core.model.Playlist>> =
        musicRepository.getUserCreatedPlaylists()
```

Verify `TrackEntity`'s field names (`albumArtist`, `canonicalTitle`, `canonicalArtist`, `albumArtUrl`, `durationMs`) against `core/data/.../entity/TrackEntity.kt` — copy from `upsertSearchTrack` which is authoritative.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :core:media:testDebugUnitTest --tests "com.stash.core.media.actions.TrackActionsDelegatePlaylistTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/actions/TrackActionsDelegate.kt core/media/src/test/kotlin/com/stash/core/media/actions/TrackActionsDelegatePlaylistTest.kt
git commit -m "feat(search): TrackActionsDelegate addToPlaylist with blocklist-guarded insert"
```

---

## Task 3: Shared row — ⋮ overflow menu

**Files:**
- Modify: `feature/search/src/main/kotlin/com/stash/feature/search/PreviewDownloadRow.kt`

- [ ] **Step 1: Add three no-op-default callbacks to the signature**

After `isResolving: Boolean = false,` add:

```kotlin
    onPlayNext: () -> Unit = {},
    onAddToQueue: () -> Unit = {},
    onAddToPlaylist: () -> Unit = {},
```

- [ ] **Step 2: Add the ⋮ icon + DropdownMenu after the download Box**

Add these imports:

```kotlin
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
```

After the closing of the download `Box` (before the outer `Row`'s close), add:

```kotlin
        // Overflow: Play next / Add to queue / Add to playlist
        Box {
            var menuOpen by remember { mutableStateOf(false) }
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "More actions",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Play next") },
                    leadingIcon = { Icon(Icons.Default.PlaylistPlay, contentDescription = null) },
                    onClick = { menuOpen = false; onPlayNext() },
                )
                DropdownMenuItem(
                    text = { Text("Add to queue") },
                    leadingIcon = { Icon(Icons.Default.PlaylistAdd, contentDescription = null) },
                    onClick = { menuOpen = false; onAddToQueue() },
                )
                DropdownMenuItem(
                    text = { Text("Add to playlist") },
                    leadingIcon = { Icon(Icons.Default.PlaylistAddCheck, contentDescription = null) },
                    onClick = { menuOpen = false; onAddToPlaylist() },
                )
            }
        }
```

- [ ] **Step 3: Compile the module**

Run: `./gradlew :feature:search:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (existing call sites still compile via defaults).

- [ ] **Step 4: Commit**

```bash
git add feature/search/src/main/kotlin/com/stash/feature/search/PreviewDownloadRow.kt
git commit -m "feat(search): add per-track overflow menu to PreviewDownloadRow"
```

---

## Task 4: Wire the menu on all three screens + playlist sheet

**Files:**
- Modify: `feature/search/.../SearchScreen.kt`, `PopularTracksSection.kt`, `AlbumDiscoveryScreen.kt`
- Modify: `SearchViewModel.kt`, `ArtistProfileViewModel.kt`, `AlbumDiscoveryViewModel.kt`

- [ ] **Step 1: Expose a "pending add-to-playlist item" + playlists on each VM**

In each of the three VMs (they all hold `val delegate: TrackActionsDelegate`), add:

```kotlin
    // Add-to-playlist picker: the item awaiting a playlist choice (null = sheet closed).
    private val _playlistSheetItem = MutableStateFlow<TrackItem?>(null)
    val playlistSheetItem: StateFlow<TrackItem?> = _playlistSheetItem.asStateFlow()

    val userPlaylists: StateFlow<List<com.stash.core.model.Playlist>> =
        delegate.userPlaylists.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun onPlayNext(item: TrackItem) = delegate.playNext(item)
    fun onAddToQueue(item: TrackItem) = delegate.addToQueue(item)
    fun onRequestAddToPlaylist(item: TrackItem) { _playlistSheetItem.value = item }
    fun onDismissPlaylistSheet() { _playlistSheetItem.value = null }
    fun onSaveToPlaylist(playlistId: Long) {
        _playlistSheetItem.value?.let { delegate.addToPlaylist(it, playlistId) }
        _playlistSheetItem.value = null
    }
    fun onCreatePlaylistAndAdd(name: String) {
        _playlistSheetItem.value?.let { delegate.createPlaylistAndAdd(it, name) }
        _playlistSheetItem.value = null
    }
```

Add imports as needed (`stateIn`, `SharingStarted`, `MutableStateFlow`, etc.).

- [ ] **Step 2: Thread the callbacks into each `PreviewDownloadRow` call site**

In `SearchScreen.kt` (both the TopResult track row ~296 and the Songs row ~330), `PopularTracksSection.kt` (~62), `AlbumDiscoveryScreen.kt` (~174), add to each `PreviewDownloadRow(...)`:

```kotlin
                            onPlayNext = { onPlayNext(<trackItem>) },
                            onAddToQueue = { onAddToQueue(<trackItem>) },
                            onAddToPlaylist = { onRequestAddToPlaylist(<trackItem>) },
```

where `<trackItem>` is the same `TrackItem` the row already builds (`t.toTrackItem()` / `top.track.toTrackItem()` / the popular/album item). Thread `onPlayNext/onAddToQueue/onRequestAddToPlaylist` lambdas down from each screen's top-level composable to the row (through `SectionedResultsList` params in SearchScreen).

- [ ] **Step 3: Host the `SaveToPlaylistSheet` on each screen**

In each screen composable, collect `playlistSheetItem` + `userPlaylists`; when non-null, render:

```kotlin
    val playlistSheetItem by viewModel.playlistSheetItem.collectAsStateWithLifecycle()
    val userPlaylists by viewModel.userPlaylists.collectAsStateWithLifecycle()
    if (playlistSheetItem != null) {
        com.stash.core.ui.components.SaveToPlaylistSheet(
            playlists = userPlaylists.map {
                com.stash.core.ui.components.PlaylistInfo(it.id, it.name, it.trackCount)
            },
            onSaveToPlaylist = viewModel::onSaveToPlaylist,
            onCreatePlaylist = viewModel::onCreatePlaylistAndAdd,
            onDismiss = viewModel::onDismissPlaylistSheet,
        )
    }
```

Verify `Playlist`'s `trackCount` field name against `core/model/.../Playlist.kt`; match what Library screens pass to `PlaylistInfo`.

- [ ] **Step 4: Compile all three feature modules**

Run: `./gradlew :feature:search:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (Artist Profile + Album Discovery live in `:feature:search`.)

- [ ] **Step 5: Build the app to validate the Hilt graph**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (validates the two new delegate deps resolve through the full Dagger component — module compile alone won't catch a graph break).

- [ ] **Step 6: Commit**

```bash
git add feature/search/src/main/kotlin/com/stash/feature/search/
git commit -m "feat(search): wire per-track menu + playlist sheet on all three surfaces"
```

---

# FEATURE 2 — Recent searches

## Task 5: RecentSearchesStore

**Files:**
- Create: `feature/search/src/main/kotlin/com/stash/feature/search/RecentSearchesStore.kt`
- Test: `feature/search/src/test/kotlin/com/stash/feature/search/RecentSearchesStoreTest.kt`

- [ ] **Step 1: Write the failing test** (Robolectric + real temp DataStore, mirroring `QbdlxCredentialStoreTest`)

```kotlin
package com.stash.feature.search

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecentSearchesStoreTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private fun store() = RecentSearchesStore(ctx)

    @Before fun clear() = runBlocking { store().clear() }

    @Test fun `record prepends most-recent-first`() = runTest {
        val s = store(); s.record("beatles"); s.record("stones")
        assertThat(s.recent.first()).containsExactly("stones", "beatles").inOrder()
    }

    @Test fun `record dedupes case-insensitively and moves to front`() = runTest {
        val s = store(); s.record("Beatles"); s.record("stones"); s.record("BEATLES")
        assertThat(s.recent.first()).containsExactly("BEATLES", "stones").inOrder()
    }

    @Test fun `caps at 10 dropping oldest`() = runTest {
        val s = store(); (1..12).forEach { s.record("q$it") }
        val r = s.recent.first()
        assertThat(r).hasSize(10)
        assertThat(r.first()).isEqualTo("q12")
        assertThat(r).doesNotContain("q1")
        assertThat(r).doesNotContain("q2")
    }

    @Test fun `blank is ignored`() = runTest {
        val s = store(); s.record("   "); s.record("")
        assertThat(s.recent.first()).isEmpty()
    }

    @Test fun `remove drops one entry`() = runTest {
        val s = store(); s.record("a"); s.record("b"); s.remove("a")
        assertThat(s.recent.first()).containsExactly("b")
    }

    @Test fun `clear empties`() = runTest {
        val s = store(); s.record("a"); s.clear()
        assertThat(s.recent.first()).isEmpty()
    }
}
```

- [ ] **Step 2: Run to confirm it fails**

Run: `./gradlew :feature:search:testDebugUnitTest --tests "com.stash.feature.search.RecentSearchesStoreTest"`
Expected: FAIL — `RecentSearchesStore` unresolved.

- [ ] **Step 3: Implement the store** (single delimited string value, newline-joined; strip newlines from queries defensively)

```kotlin
package com.stash.feature.search

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.recentSearchesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "recent_searches",
)

/**
 * Most-recent-first list of committed search queries, capped at [MAX].
 * Backed by a single newline-delimited string (the LosslessSourcePreferences
 * comma-list pattern). Dedupe is case-insensitive; re-recording an existing
 * query moves it to the front.
 */
@Singleton
class RecentSearchesStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val key = stringPreferencesKey("queries")

    val recent: Flow<List<String>> = context.recentSearchesDataStore.data.map { prefs ->
        prefs[key]?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
    }

    suspend fun record(query: String) {
        val q = query.trim().replace("\n", " ")
        if (q.isEmpty()) return
        context.recentSearchesDataStore.edit { prefs ->
            val current = prefs[key]?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
            val deduped = current.filterNot { it.equals(q, ignoreCase = true) }
            prefs[key] = (listOf(q) + deduped).take(MAX).joinToString("\n")
        }
    }

    suspend fun remove(query: String) {
        context.recentSearchesDataStore.edit { prefs ->
            val current = prefs[key]?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
            prefs[key] = current.filterNot { it.equals(query, ignoreCase = true) }.joinToString("\n")
        }
    }

    suspend fun clear() {
        context.recentSearchesDataStore.edit { it.remove(key) }
    }

    companion object { const val MAX = 10 }
}
```

Confirm `:feature:search` has `datastore.preferences` + `robolectric` + `androidx.test.core` test deps; if not, add them (mirror `:data:download`'s `build.gradle.kts`).

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :feature:search:testDebugUnitTest --tests "com.stash.feature.search.RecentSearchesStoreTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add feature/search/src/main/kotlin/com/stash/feature/search/RecentSearchesStore.kt feature/search/src/test/kotlin/com/stash/feature/search/RecentSearchesStoreTest.kt feature/search/build.gradle.kts
git commit -m "feat(search): RecentSearchesStore (DataStore MRU query list)"
```

---

## Task 6: SearchViewModel — record on commit, expose recents

**Files:**
- Modify: `feature/search/.../SearchViewModel.kt`
- Test: `feature/search/src/test/kotlin/com/stash/feature/search/SearchViewModelRecentsTest.kt` (create)

- [ ] **Step 1: Write the failing test** (MockK the store; assert record on commit, not on `onQueryChanged`)

```kotlin
package com.stash.feature.search

import com.stash.core.model.TrackItem
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.coEvery
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SearchViewModelRecentsTest {
    private val store: RecentSearchesStore = mockk(relaxed = true) {
        coEvery { recent } returns flowOf(emptyList())
    }
    // ... build a SearchViewModel with mocked api/prefetcher/delegate/etc. + store

    @Test fun `onSearchCommitted records the query`() = runTest {
        // vm.onQueryChanged("beatles"); coVerify(exactly = 0) { store.record(any()) }
        // vm.onSearchCommitted(); coVerify { store.record("beatles") }
    }
}
```

(Flesh out the VM construction to match its constructor; the assertion that matters: `onQueryChanged` does NOT record, `onSearchCommitted` / `onResultTap` DO.)

- [ ] **Step 2: Run to confirm it fails**

Run: `./gradlew :feature:search:testDebugUnitTest --tests "com.stash.feature.search.SearchViewModelRecentsTest"`
Expected: FAIL — `store` param / `onSearchCommitted` unresolved.

- [ ] **Step 3: Inject the store, expose recents, record on commit**

Add `private val recentSearchesStore: RecentSearchesStore` to the constructor. Add:

```kotlin
    val recentSearches: StateFlow<List<String>> =
        recentSearchesStore.recent.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Record the current query as a recent search. Call on committed searches
     *  only (IME Search action, or a result tap) — NOT on every keystroke. */
    fun onSearchCommitted() {
        val q = _uiState.value.query.trim()
        if (q.isNotEmpty()) viewModelScope.launch { recentSearchesStore.record(q) }
    }

    fun onRecentSearchTapped(query: String) {
        onQueryChanged(query)
        onSearchCommitted()
    }

    fun removeRecentSearch(query: String) =
        viewModelScope.launch { recentSearchesStore.remove(query) }.let { }

    fun clearRecentSearches() =
        viewModelScope.launch { recentSearchesStore.clear() }.let { }
```

In `onResultTap`, add `onSearchCommitted()` at the top (a tapped result means the query was useful).

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :feature:search:testDebugUnitTest --tests "com.stash.feature.search.SearchViewModelRecentsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add feature/search/src/main/kotlin/com/stash/feature/search/SearchViewModel.kt feature/search/src/test/kotlin/com/stash/feature/search/SearchViewModelRecentsTest.kt
git commit -m "feat(search): record + expose recent searches in SearchViewModel"
```

---

## Task 7: Recent-searches UI in the Idle state

**Files:**
- Modify: `feature/search/.../SearchScreen.kt`

- [ ] **Step 1: Add a `RecentSearches` composable**

```kotlin
@Composable
private fun RecentSearches(
    queries: List<String>,
    onTap: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClearAll: () -> Unit,
) {
    if (queries.isEmpty()) { EmptySearchPrompt(); return }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Recent searches", style = MaterialTheme.typography.titleSmall,
                     modifier = Modifier.weight(1f))
                TextButton(onClick = onClearAll) { Text("Clear all") }
            }
        }
        items(queries, key = { it }) { q ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onTap(q) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Schedule, contentDescription = null,
                     tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Text(q, modifier = Modifier.weight(1f),
                     style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                     overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { onRemove(q) }) {
                    Icon(Icons.Default.Clear, contentDescription = "Remove",
                         tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
```

Add imports: `androidx.compose.material.icons.outlined.Schedule`, `androidx.compose.material3.TextButton`, `androidx.compose.foundation.clickable`.

- [ ] **Step 2: Render it in the Idle branch**

Collect recents in `SearchScreen`:

```kotlin
    val recentSearches by viewModel.recentSearches.collectAsStateWithLifecycle()
```

Replace `SearchStatus.Idle -> EmptySearchPrompt()` with:

```kotlin
                SearchStatus.Idle -> RecentSearches(
                    queries = recentSearches,
                    onTap = viewModel::onRecentSearchTapped,
                    onRemove = { viewModel.removeRecentSearch(it) },
                    onClearAll = viewModel::clearRecentSearches,
                )
```

- [ ] **Step 3: Commit on the IME Search action**

Thread an `onSearch` lambda into `SearchBar` and call `viewModel.onSearchCommitted()`:

Change `SearchBar(...)` call to pass `onSearch = viewModel::onSearchCommitted`, add the param to `SearchBar`, and in its `KeyboardActions`:

```kotlin
        keyboardActions = KeyboardActions(
            onSearch = { keyboardController?.hide(); onSearch() },
        ),
```

- [ ] **Step 4: Compile**

Run: `./gradlew :feature:search:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add feature/search/src/main/kotlin/com/stash/feature/search/SearchScreen.kt
git commit -m "feat(search): recent-searches list in empty state + commit on IME search"
```

---

## Task 8: Full build + on-device verification

- [ ] **Step 1: Full debug assemble**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run the feature's unit tests**

Run: `./gradlew :feature:search:testDebugUnitTest :core:media:testDebugUnitTest --tests "com.stash.core.media.actions.TrackActionsDelegateQueueActionsTest" --tests "com.stash.core.media.actions.TrackActionsDelegatePlaylistTest"`
Expected: all PASS.

- [ ] **Step 3: Install + device verify** (ask the user for the current wireless-debug IP:port; the port rotates)

- Install: `./gradlew :app:installDebug`
- Search an artist → open profile → ⋮ on a Popular track → **Add to queue** → confirm queue grows (Now Playing queue) + snackbar.
- ⋮ → **Play next** → confirm the track is inserted right after current.
- ⋮ → **Add to playlist** → pick a playlist → confirm it appears in that playlist (Library) as a streamable (not downloaded) track, survives an app relaunch.
- Repeat one action on a Search-tab Songs row and one Album Discovery row (same shared row).
- Search 3 queries, tap results, leave Search, return → **Recent searches** shows them MRU; tap re-runs; ✕ removes one; Clear all empties.

- [ ] **Step 4: Commit any device-fix follow-ups, then report results to the user.**

---

## Notes for the executor

- **DRY:** the mapper and insert helper live once in the delegate; all three screens share them via the delegate they already inject.
- **YAGNI:** no multi-select, no recently-viewed-entities, no auto-download, no sync — see the spec Non-goals.
- **Verify-before-assert:** copy `canonicalize`, `TrackEntity` field names, and the `Track`/`Playlist`/`PlaylistInfo` shapes from the authoritative source files rather than trusting this plan's paraphrase.
- **Hilt gotcha:** module compile ≠ graph valid. Always run `:app:assembleDebug` after the delegate's constructor changes (Task 1/4).
- **Gradle on this box:** use the daemon, always `--tests`-filter, and filter `:core:media` to the specific test class (one flaky network test otherwise).
