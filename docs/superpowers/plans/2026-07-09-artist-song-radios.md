# Artist & Song Radios Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A self-extending, balanced generated play queue seeded from an artist or a track — tap "Radio" on an artist, or "Start radio" on any song, and the player fills forever with a mix of the seed and similar music.

**Architecture:** A pure orchestration engine in `core:data/radio` combines the Last.fm similarity graph (taste) with the YouTube Music client (real playable videoIds). `core:media` gains a `startRadio(seed)` on `PlayerRepository` plus a radio auto-grow watcher that mirrors the existing v0.9.14 library-shuffle grower — it refills the queue as it nears the tail. Three thin entry points (artist profile, song ⋮ menu, Now Playing) call `startRadio`. Radio tracks carry real videoIds, so they are ordinary streaming `Track`s needing no DB persistence.

**Tech Stack:** Kotlin, coroutines, Hilt DI, MockK (final-class mocking), Truth assertions, JUnit4, Media3 controller (already wrapped by `PlayerRepositoryImpl`), Jetpack Compose (entry-point UI).

**Spec:** `docs/superpowers/specs/2026-07-09-artist-song-radios-design.md`

---

## Grounding facts (verified against the codebase — do not re-derive)

- **Last.fm client** `core/data/.../lastfm/LastFmApiClient.kt` (final class, `@Inject`):
  - `suspend fun getSimilarArtists(artist, limit=30): Result<List<LastFmSimilarArtist>>`
  - `suspend fun getSimilarTracks(artist, title, limit=30): Result<List<LastFmSimilarTrack>>`
  - `suspend fun getArtistTopTracks(artist, limit=10): Result<List<LastFmTopTrack>>`
  - Models: `LastFmSimilarArtist(name: String, match: Float)`, `LastFmSimilarTrack(artist, title, match: Float)`, `LastFmTopTrack(artist, title, playcount: Int)`.
  - All three are `runCatching`-wrapped (never throw; return `Result.failure`). Rate-limit Worker-proxy fallback is internal.
- **YT client** `data/ytmusic/.../YTMusicApiClient.kt` (final class, `@Inject`):
  - `suspend fun resolveArtist(name: String): ArtistSummary?` — `ArtistSummary(id, name, avatarUrl)`.
  - `suspend fun getArtist(browseId: String): ArtistProfile` — `ArtistProfile(id, name, avatarUrl, subscribersText, popular: List<TrackSummary>, albums, singles, related: List<ArtistSummary>)`.
  - `suspend fun searchCanonicalVideoId(artist: String, title: String): String?` — song-radio resolve.
  - `TrackSummary(videoId, title, artist, album: String?, durationSeconds: Double, thumbnailUrl: String?)`.
- **Track model** `core/model/.../Track.kt`: streaming track built from a videoId uses `id = videoId.hashCode().toLong()`, `youtubeId = videoId`, `source = MusicSource.YOUTUBE`, `durationMs = (seconds*1000).toLong()`, `isStreamable = true`. This is the established convention (see `AlbumDiscoveryViewModel.synthesizeDomainTracks`, `NowPlayingViewModel`).
- **Queue grower to mirror** `core/media/.../PlayerRepositoryImpl.kt`:
  - `shuffleLibrary()` (line ~594): sets `libraryShuffleActive = true`, sets media items, plays. `setQueueInternal` (line ~373) sets `libraryShuffleActive = false` (disarms).
  - `init` watcher (line ~180): collects `playerState`, `remaining = state.queue.size - state.currentIndex - 1`, calls grow when `remaining in 0 until LIBRARY_SHUFFLE_GROW_THRESHOLD`.
  - `growLibraryShuffle()` (line ~631): `growMutex.withLock { ... controller.addMediaItems(...); currentQueueTracks = currentQueueTracks + toAppend }`.
  - Consts (line ~1558): `LIBRARY_SHUFFLE_GROW_THRESHOLD = 5`, `LIBRARY_SHUFFLE_GROW_BATCH = 50`. Helpers: `Track.toMediaItem()`, `EXTRA_TRACK_ID`.
  - Constructor is `@Inject`/`@Singleton` with `streamingPreference`, `connectivity` already present.
- **Shared track actions** `core/media/.../actions/TrackActionsDelegate.kt` (`@Inject`): already holds `playerRepository`, `streamingPreference`; has `playNext(item)`, `addToQueue(item)`, `item.toDomainTrack()`. Used by search/album screens (`PreviewDownloadRow`) and indirectly by the library `TrackOptionsSheet`.
- **Streaming gate:** `streamingPreference.current(): Boolean` (suspend). `ConnectivityMonitor` in `core:media`.
- **Module graph:** `core:data` depends on `data:ytmusic` and hosts Last.fm; `core:media` depends on `core:data`; features depend on both. `RadioStationGenerator` lives in `core:data`, needs no Hilt module (plain `@Inject constructor`).

## File structure

**`core:data` (the brain):**
- Create `core/data/src/main/kotlin/com/stash/core/data/radio/RadioSeed.kt` — sealed seed model.
- Create `core/data/src/main/kotlin/com/stash/core/data/radio/RadioModels.kt` — `RadioCandidate`, `RadioSession`.
- Create `core/data/src/main/kotlin/com/stash/core/data/radio/RadioInterleaver.kt` — pure weighted, no-adjacent-repeat ordering.
- Create `core/data/src/main/kotlin/com/stash/core/data/radio/RadioStationGenerator.kt` — orchestrator.
- Tests under `core/data/src/test/kotlin/com/stash/core/data/radio/`.

**`core:media` (queue integration):**
- Modify `core/media/src/main/kotlin/com/stash/core/media/PlayerRepository.kt` — add `startRadio`, `stopRadio`.
- Modify `core/media/src/main/kotlin/com/stash/core/media/PlayerRepositoryImpl.kt` — inject generator, radio state, watcher, `growRadio`.
- Test `core/media/src/test/kotlin/com/stash/core/media/PlayerRepositoryRadioTest.kt`.

**feature (entry points):**
- Modify `feature/search/.../ArtistProfileViewModel.kt` + `ArtistProfileScreen.kt`/`AlbumHero.kt` (artist header Radio button).
- Modify `core/media/.../actions/TrackActionsDelegate.kt` (`startRadio(item)`), `core/ui/.../TrackOptionsSheet.kt` + `PreviewDownloadRow` (menu item).
- Modify `feature/nowplaying/.../NowPlayingViewModel.kt` + Now Playing screen (chip + stop).
- Tests alongside each.

**Convention:** Use `./gradlew :module:testDebugUnitTest --tests "FQCN"`. Always use the daemon (no `--no-daemon`). If a Gradle socket `BindException` appears, just re-run.

---

## Phase A — The station brain (`core:data/radio`)

### Task A1: Seed + candidate + session models

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/radio/RadioSeed.kt`
- Create: `core/data/src/main/kotlin/com/stash/core/data/radio/RadioModels.kt`

- [ ] **Step 1: Write `RadioSeed.kt`**

```kotlin
package com.stash.core.data.radio

/** What the user tapped to start a station. */
sealed interface RadioSeed {
    /** Artist radio — seeded from an artist. [ytBrowseId] is known when started
     *  from the artist profile (skips a resolveArtist hop); null otherwise. */
    data class Artist(val name: String, val ytBrowseId: String? = null) : RadioSeed

    /** Song radio — seeded from a specific track. */
    data class Song(val title: String, val artist: String, val ytVideoId: String? = null) : RadioSeed
}
```

- [ ] **Step 2: Write `RadioModels.kt`**

```kotlin
package com.stash.core.data.radio

/**
 * One potential radio track before YouTube resolution.
 *
 * [videoId] is known for artist-radio picks (they come from a YT `popular`
 * list) and null for song-radio similar-track picks (resolved via
 * `searchCanonicalVideoId` at emit time). [weight] is the source artist's
 * relative frequency (seed share, or a neighbor's Last.fm match score); the
 * interleaver uses it to decide how often this candidate's artist appears.
 */
data class RadioCandidate(
    val artist: String,
    val title: String,
    val videoId: String?,
    val weight: Float,
)

/** Stable per-station identity for the no-repeat set. */
internal fun RadioCandidate.identity(): String =
    videoId?.takeIf { it.isNotBlank() }
        ?: (artist.trim().lowercase() + "|" + title.trim().lowercase())
```

`RadioSession` is added in Task A3 (it needs the interleaver from A2). Keep this file to the two items above for now.

- [ ] **Step 3: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/radio/RadioSeed.kt \
        core/data/src/main/kotlin/com/stash/core/data/radio/RadioModels.kt
git commit -m "feat(radio): seed + candidate models"
```

---

### Task A2: RadioInterleaver (pure, weighted, no-adjacent-repeat)

The interleaver takes the candidate pool and produces the full play order: each
candidate exactly once, artist frequency proportional to weight, and never the
same artist twice in a row (until only one artist's candidates remain). It uses
smooth weighted round-robin across per-artist queues.

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/radio/RadioInterleaver.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/radio/RadioInterleaverTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.stash.core.data.radio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RadioInterleaverTest {
    // seed gets weight ~= half the neighbor weight sum, so it lands ~1/3 of slots.
    private fun cand(artist: String, title: String, weight: Float) =
        RadioCandidate(artist, title, videoId = "$artist-$title", weight = weight)

    @Test fun `orders every candidate exactly once`() {
        val pool = listOf(
            cand("Seed", "s1", 3f), cand("Seed", "s2", 3f),
            cand("A", "a1", 1f), cand("B", "b1", 1f),
        )
        val out = RadioInterleaver.order(pool)
        assertThat(out).containsExactlyElementsIn(pool)
    }

    @Test fun `never repeats the same artist back to back when alternatives exist`() {
        val pool = buildList {
            repeat(4) { add(cand("Seed", "s$it", 3f)) }
            repeat(2) { add(cand("A", "a$it", 1f)) }
            repeat(2) { add(cand("B", "b$it", 1f)) }
        }
        val out = RadioInterleaver.order(pool)
        // No adjacent same-artist until the tail (when one artist may be all
        // that's left). Check the leading window where alternatives still exist.
        val artists = out.map { it.artist }
        var adjacent = 0
        for (i in 1 until artists.size) if (artists[i] == artists[i - 1]) adjacent++
        assertThat(adjacent).isAtMost(1) // tolerate a single tail collision
    }

    @Test fun `higher-weight seed is the most frequent artist, never starved`() {
        val pool = buildList {
            repeat(12) { add(cand("Seed", "s$it", 6f)) }          // seed weight tuned by generator
            repeat(4) { add(cand("N${it % 4}", "n$it", 1f)) }     // 4 neighbors, 1 each
        }
        val out = RadioInterleaver.order(pool.shuffled())
        val seedCount = out.count { it.artist == "Seed" }
        // Pins only that the seed is the MOST frequent artist (never starved) — NOT
        // a literal 1/3. The generator's finite seed catalog + no-repeat set make a
        // steady 1/3-forever impossible; the feel is "seed-heavy open, drifting out."
        val maxNeighbor = out.filter { it.artist != "Seed" }.groupingBy { it.artist }.eachCount().values.max()
        assertThat(seedCount).isAtLeast(maxNeighbor)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.radio.RadioInterleaverTest"`
Expected: FAIL — `RadioInterleaver` unresolved.

- [ ] **Step 3: Implement `RadioInterleaver.kt`**

```kotlin
package com.stash.core.data.radio

/**
 * Pure ordering of a radio candidate pool into play order.
 *
 * Groups candidates by artist (preserving each artist's own order), then draws
 * across the groups with smooth weighted round-robin: an artist's "credit"
 * accumulates by its weight each slot; the highest-credit artist that is not the
 * previous one is picked and pays 1 credit. This yields frequency ∝ weight and
 * avoids back-to-back repeats while alternatives remain. No I/O — unit-testable.
 */
object RadioInterleaver {
    fun order(pool: List<RadioCandidate>): List<RadioCandidate> {
        if (pool.size <= 1) return pool
        // Per-artist FIFO queues, and each artist's weight (candidates of one
        // artist share a weight; take the max as the artist's frequency weight).
        val queues = LinkedHashMap<String, ArrayDeque<RadioCandidate>>()
        val weights = LinkedHashMap<String, Float>()
        for (c in pool) {
            queues.getOrPut(c.artist) { ArrayDeque() }.addLast(c)
            weights[c.artist] = maxOf(weights[c.artist] ?: 0f, c.weight)
        }
        val credit = HashMap<String, Float>().apply { queues.keys.forEach { this[it] = 0f } }
        val out = ArrayList<RadioCandidate>(pool.size)
        var previous: String? = null
        repeat(pool.size) {
            for (a in queues.keys) if (queues.getValue(a).isNotEmpty()) {
                credit[a] = credit.getValue(a) + weights.getValue(a)
            }
            // Highest-credit non-empty artist that isn't `previous`; fall back to
            // `previous` only if it's the sole remaining non-empty artist.
            val nonEmpty = queues.keys.filter { queues.getValue(it).isNotEmpty() }
            val pick = (nonEmpty.filter { it != previous }.maxByOrNull { credit.getValue(it) }
                ?: nonEmpty.first())
            out.add(queues.getValue(pick).removeFirst())
            credit[pick] = credit.getValue(pick) - 1f
            previous = pick
        }
        return out
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.radio.RadioInterleaverTest"`
Expected: PASS (3/3).

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/radio/RadioInterleaver.kt \
        core/data/src/test/kotlin/com/stash/core/data/radio/RadioInterleaverTest.kt
git commit -m "feat(radio): pure weighted no-adjacent-repeat interleaver"
```

---

### Task A3: Generator — artist-seed `start()` + `RadioSession`

`start(seed)` for an `Artist` seed: fetch Last.fm neighbors, build the artist
rotation with weights (seed weight tuned so it lands ~1/3), resolve each artist's
YT `popular` tracks (parallelized for a fast first batch), build the candidate
pool, order it via the interleaver, resolve the first batch of `Track`s, and
return a `RadioSession` holding the ordering + cursor + no-repeat set.

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/radio/RadioModels.kt` (add `RadioSession`)
- Create: `core/data/src/main/kotlin/com/stash/core/data/radio/RadioStationGenerator.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/radio/RadioStationGeneratorArtistTest.kt`

- [ ] **Step 1: Add `RadioSession` to `RadioModels.kt`**

```kotlin
/**
 * Live, in-memory state of one station. Mutable cursor + no-repeat set; not
 * persisted (a process kill ends the station — queued tracks still play out).
 * Created and mutated only by [RadioStationGenerator]; consumed by the player.
 */
class RadioSession internal constructor(
    internal val seed: RadioSeed,
    internal val ordered: MutableList<RadioCandidate>,
    internal val played: MutableSet<String>,   // identity() + resolved-videoId keys already emitted
) {
    internal var cursor: Int = 0
    /** True once the pool is exhausted and no further widening is possible. */
    internal var exhausted: Boolean = false
    /**
     * Neighbors not yet used, consumed NEIGHBOR_POOL at a time by widening.
     * This is what keeps the station extending: artist radio fills it at start
     * (with the neighbors AFTER the first pool); song radio lazy-loads it on the
     * first widen. Draining it — not re-fetching the same top-N — is the fix for
     * "widen returns the same already-played neighbors."
     */
    internal val remainingNeighbors: ArrayDeque<com.stash.core.data.lastfm.LastFmSimilarArtist> = ArrayDeque()
    internal var neighborsLoaded: Boolean = false
}
```

- [ ] **Step 2: Write the failing test**

```kotlin
package com.stash.core.data.radio

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmSimilarArtist
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.ArtistProfile
import com.stash.data.ytmusic.model.ArtistSummary
import com.stash.data.ytmusic.model.TrackSummary
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RadioStationGeneratorArtistTest {
    private val lastFm: LastFmApiClient = mockk()
    private val yt: YTMusicApiClient = mockk()
    private fun gen() = RadioStationGenerator(lastFm, yt)

    private fun ts(artist: String, n: Int) =
        TrackSummary(videoId = "$artist$n", title = "$artist song $n", artist = artist,
            album = null, durationSeconds = 180.0, thumbnailUrl = null)
    private fun profile(name: String, browseId: String) = ArtistProfile(
        id = browseId, name = name, avatarUrl = null, subscribersText = null,
        popular = (1..6).map { ts(name, it) }, albums = emptyList(),
        singles = emptyList(), related = emptyList())

    @Test fun `artist seed builds a balanced first batch of real videoId tracks`() = runTest {
        coEvery { lastFm.getSimilarArtists("My Bloody Valentine", any()) } returns
            Result.success(listOf(
                LastFmSimilarArtist("Slowdive", 1.0f),
                LastFmSimilarArtist("Ride", 0.8f)))
        coEvery { yt.getArtist("MBV_ID") } returns profile("My Bloody Valentine", "MBV_ID")
        coEvery { yt.resolveArtist("Slowdive") } returns ArtistSummary("SD_ID", "Slowdive", null)
        coEvery { yt.getArtist("SD_ID") } returns profile("Slowdive", "SD_ID")
        coEvery { yt.resolveArtist("Ride") } returns ArtistSummary("RD_ID", "Ride", null)
        coEvery { yt.getArtist("RD_ID") } returns profile("Ride", "RD_ID")

        val (session, batch) = gen().start(
            RadioSeed.Artist("My Bloody Valentine", ytBrowseId = "MBV_ID"))

        assertThat(batch).isNotEmpty()
        assertThat(batch.all { it.youtubeId != null && it.youtubeId!!.isNotBlank() }).isTrue()
        assertThat(batch.map { it.youtubeId }.toSet()).hasSize(batch.size) // no dupes
        assertThat(batch.any { it.artist == "My Bloody Valentine" }).isTrue()
        assertThat(batch.any { it.artist != "My Bloody Valentine" }).isTrue() // drifted outward
        assertThat(session.played).isNotEmpty()
    }

    @Test fun `uses the seed browseId directly and does not resolveArtist the seed`() = runTest {
        coEvery { lastFm.getSimilarArtists(any(), any()) } returns Result.success(emptyList())
        coEvery { yt.getArtist("MBV_ID") } returns profile("My Bloody Valentine", "MBV_ID")

        val (_, batch) = gen().start(RadioSeed.Artist("My Bloody Valentine", "MBV_ID"))

        assertThat(batch).isNotEmpty() // seed-only fallback still plays
        // resolveArtist(seed) never stubbed → if generator called it, MockK throws.
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.radio.RadioStationGeneratorArtistTest"`
Expected: FAIL — `RadioStationGenerator` unresolved.

- [ ] **Step 4: Implement `RadioStationGenerator.kt` (artist path + shared helpers)**

```kotlin
package com.stash.core.data.radio

import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.model.MusicSource
import com.stash.core.model.Track
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.TrackSummary
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds and extends a balanced radio station: Last.fm chooses the taste graph,
 * YouTube supplies real playable videoIds. Stateless across stations — all live
 * state lives in the returned [RadioSession]. Plain `@Inject` (both clients are
 * injectable); no Hilt module needed.
 */
@Singleton
class RadioStationGenerator @Inject constructor(
    private val lastFm: LastFmApiClient,
    private val yt: YTMusicApiClient,
) {
    /** Start a station: returns the session + the first playable batch. */
    suspend fun start(seed: RadioSeed): Pair<RadioSession, List<Track>> = when (seed) {
        is RadioSeed.Artist -> startArtist(seed)
        is RadioSeed.Song -> startSong(seed)
    }

    // Replaced in Task A4. Present now so `start()` compiles in this task.
    private suspend fun startSong(seed: RadioSeed.Song): Pair<RadioSession, List<Track>> =
        TODO("implemented in Task A4")

    private suspend fun startArtist(seed: RadioSeed.Artist): Pair<RadioSession, List<Track>> {
        // Fetch the FULL ranked neighbor list once; use the first NEIGHBOR_POOL now
        // and page the rest during widening (see widen()). Fetching the same top-N
        // again later would only re-return already-played neighbors.
        val allNeighbors = lastFm.getSimilarArtists(seed.name, limit = NEIGHBOR_MAX)
            .getOrDefault(emptyList())
        val neighbors = allNeighbors.take(NEIGHBOR_POOL)
        // Resolve seed (via known browseId) + each neighbor's popular list, in parallel.
        val artistTracks: Map<String, List<TrackSummary>> = coroutineScope {
            val seedJob = async { seed.name to seedPopular(seed) }
            val neighborJobs = neighbors.map { n ->
                async { n.name to resolvePopular(n.name) }
            }
            (listOf(seedJob) + neighborJobs).awaitAll().toMap()
        }
        val pool = buildArtistPool(seed.name, neighbors, artistTracks)
        val session = RadioSession(seed, RadioInterleaver.order(pool).toMutableList(), mutableSetOf())
        session.remainingNeighbors.addAll(allNeighbors.drop(NEIGHBOR_POOL))
        session.neighborsLoaded = true
        return session to drainBatch(session, FIRST_BATCH)
    }

    private suspend fun seedPopular(seed: RadioSeed.Artist): List<TrackSummary> {
        val browseId = seed.ytBrowseId ?: yt.resolveArtist(seed.name)?.id ?: return emptyList()
        return runCatching { yt.getArtist(browseId).popular }.getOrDefault(emptyList())
    }

    private suspend fun resolvePopular(name: String): List<TrackSummary> {
        val id = yt.resolveArtist(name)?.id ?: return emptyList()
        return runCatching { yt.getArtist(id).popular }.getOrDefault(emptyList())
    }

    /**
     * Seed weight tuned so that WITHIN the current pool the seed is the single
     * most frequent artist — weight = SEED_SHARE/(1-SEED_SHARE) * W (W = total
     * neighbor weight) makes the seed's weight ~1/3 of the total, so the
     * interleaver front-loads the seed's tracks across the opening. The seed has
     * a finite catalog (~[TRACKS_PER_ARTIST] popular tracks), so as the station
     * widens to more neighbors the seed naturally recedes — "recognizable open,
     * always drifting outward." A literal steady 1/3-forever is impossible
     * without repeating the seed's few tracks, which the no-repeat set forbids.
     */
    private fun buildArtistPool(
        seedName: String,
        neighbors: List<com.stash.core.data.lastfm.LastFmSimilarArtist>,
        tracks: Map<String, List<TrackSummary>>,
    ): List<RadioCandidate> {
        val neighborWeightSum = neighbors.sumOf { it.match.coerceAtLeast(0.05f).toDouble() }.toFloat()
        val seedWeight = if (neighborWeightSum <= 0f) 1f
            else (SEED_SHARE / (1f - SEED_SHARE)) * neighborWeightSum
        val out = ArrayList<RadioCandidate>()
        tracks[seedName]?.take(TRACKS_PER_ARTIST)?.forEach {
            out += RadioCandidate(seedName, it.title, it.videoId, seedWeight)
        }
        for (n in neighbors) {
            val w = n.match.coerceAtLeast(0.05f)
            tracks[n.name]?.take(TRACKS_PER_ARTIST)?.forEach {
                out += RadioCandidate(n.name, it.title, it.videoId, w)
            }
        }
        return out
    }

    /** Walk the ordered pool from the cursor, emit up to [size] fresh tracks,
     *  resolving any candidate that still lacks a videoId. Skips dupes + misses.
     *  Does NOT set `exhausted` — widen() owns that (the pool may still widen). */
    internal suspend fun drainBatch(session: RadioSession, size: Int): List<Track> {
        val out = ArrayList<Track>(size)
        while (out.size < size && session.cursor < session.ordered.size) {
            val cand = session.ordered[session.cursor++]
            val id = cand.identity()
            if (id in session.played) continue
            val videoId = cand.videoId?.takeIf { it.isNotBlank() }
                ?: yt.searchCanonicalVideoId(cand.artist, cand.title) ?: continue
            // Two different candidates (e.g. a seed-artist top track and a similar
            // track) can resolve to the SAME videoId — guard on the resolved id too
            // so the same audio never plays twice in one station.
            val vidKey = "vid:$videoId"
            if (vidKey in session.played) continue
            session.played += id
            session.played += vidKey
            out += cand.toTrack(videoId)
        }
        return out
    }

    private fun RadioCandidate.toTrack(videoId: String) = Track(
        id = videoId.hashCode().toLong(),
        title = title,
        artist = artist,
        durationMs = 0L,
        youtubeId = videoId,
        source = MusicSource.YOUTUBE,
        isStreamable = true,
    )

    companion object {
        const val SEED_SHARE = 1f / 3f
        const val NEIGHBOR_LIMIT = 30    // Last.fm page size for song getSimilarTracks
        const val NEIGHBOR_MAX = 100     // full artist-neighbor list (paged by widen)
        const val NEIGHBOR_POOL = 12     // neighbors consumed per pool/widen slice
        const val TRACKS_PER_ARTIST = 6
        const val FIRST_BATCH = 12
        const val GROW_BATCH = 12
    }
}
```

> **On the "~1/3 seed" feel (read before implementing):** the seed weight makes
> the seed the most frequent artist in the opening pool, then the station drifts
> outward as it widens — this is the intended "recognizable but always drifting
> outward" behavior, not a bug. Don't try to force a literal steady 1/3 forever
> (the seed's finite catalog makes that impossible without repeats). The
> interleaver test asserts only that the seed is never *starved*.

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.radio.RadioStationGeneratorArtistTest"`
Expected: PASS (2/2). (`startSong` is a stub referencing Task A4 — add a `TODO()` body if the compiler needs it, replaced in A4.)

- [ ] **Step 6: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/radio/RadioStationGenerator.kt \
        core/data/src/main/kotlin/com/stash/core/data/radio/RadioModels.kt \
        core/data/src/test/kotlin/com/stash/core/data/radio/RadioStationGeneratorArtistTest.kt
git commit -m "feat(radio): artist-seed station start + session + batch drain"
```

---

### Task A4: Generator — song-seed `start()`

Song radio: Last.fm `getSimilarTracks` gives `(artist, title, match)` pairs; the
seed artist's own top tracks fill the ~1/3 seed share. Candidates carry no
videoId (resolved lazily in `drainBatch` via `searchCanonicalVideoId`).

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/radio/RadioStationGenerator.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/radio/RadioStationGeneratorSongTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.stash.core.data.radio

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmSimilarTrack
import com.stash.core.data.lastfm.LastFmTopTrack
import com.stash.data.ytmusic.YTMusicApiClient
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RadioStationGeneratorSongTest {
    private val lastFm: LastFmApiClient = mockk()
    private val yt: YTMusicApiClient = mockk()
    private fun gen() = RadioStationGenerator(lastFm, yt)

    @Test fun `song seed blends seed-artist tracks with similar tracks, resolves via search`() = runTest {
        coEvery { lastFm.getSimilarTracks("Lil Wayne", "A Milli", any()) } returns
            Result.success(listOf(
                LastFmSimilarTrack("Drake", "Forever", 0.9f),
                LastFmSimilarTrack("T.I.", "Whatever You Like", 0.7f)))
        coEvery { lastFm.getArtistTopTracks("Lil Wayne", any()) } returns
            Result.success(listOf(
                LastFmTopTrack("Lil Wayne", "6 Foot 7 Foot", 100),
                LastFmTopTrack("Lil Wayne", "Lollipop", 90)))
        coEvery { yt.searchCanonicalVideoId(any(), any()) } answers
            { "vid_${firstArg<String>()}_${secondArg<String>()}".replace(" ", "") }

        val (session, batch) = gen().start(RadioSeed.Song("A Milli", "Lil Wayne"))

        assertThat(batch).isNotEmpty()
        assertThat(batch.all { it.youtubeId != null }).isTrue()
        assertThat(batch.any { it.artist == "Lil Wayne" }).isTrue()      // seed share
        assertThat(batch.any { it.artist != "Lil Wayne" }).isTrue()      // similar tracks
        assertThat(session.played).isNotEmpty()
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.radio.RadioStationGeneratorSongTest"`
Expected: FAIL (`startSong` is `TODO()`).

- [ ] **Step 3: Implement `startSong`**

```kotlin
    private suspend fun startSong(seed: RadioSeed.Song): Pair<RadioSession, List<Track>> {
        val similar = lastFm.getSimilarTracks(seed.artist, seed.title, limit = NEIGHBOR_LIMIT)
            .getOrDefault(emptyList())
            .take(SONG_SIMILAR_POOL)
        val seedTop = lastFm.getArtistTopTracks(seed.artist, limit = TRACKS_PER_ARTIST)
            .getOrDefault(emptyList())

        val similarWeightSum = similar.sumOf { it.match.coerceAtLeast(0.05f).toDouble() }.toFloat()
        val seedWeight = if (similarWeightSum <= 0f) 1f
            else (SEED_SHARE / (1f - SEED_SHARE)) * similarWeightSum

        val pool = ArrayList<RadioCandidate>()
        // Seed share: the seeded track first, then the seed artist's top tracks.
        pool += RadioCandidate(seed.artist, seed.title, seed.ytVideoId, seedWeight)
        seedTop.filterNot { it.title.equals(seed.title, ignoreCase = true) }
            .forEach { pool += RadioCandidate(seed.artist, it.title, null, seedWeight) }
        // Neighbors: each similar track, weighted by match; artist keyed for interleave.
        for (s in similar) {
            pool += RadioCandidate(s.artist, s.title, null, s.match.coerceAtLeast(0.05f))
        }
        val session = RadioSession(seed, RadioInterleaver.order(pool).toMutableList(), mutableSetOf())
        return session to drainBatch(session, FIRST_BATCH)
    }
```

Add to `companion object`: `const val SONG_SIMILAR_POOL = 40`.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.radio.RadioStationGeneratorSongTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/radio/RadioStationGenerator.kt \
        core/data/src/test/kotlin/com/stash/core/data/radio/RadioStationGeneratorSongTest.kt
git commit -m "feat(radio): song-seed station start (similar tracks + seed-artist share)"
```

---

### Task A5: Generator — `nextBatch` (self-extension + widening) and fallbacks

`nextBatch` drains the next slice; when the ordered pool nears exhaustion it
**widens** (artist radio: pull deeper into a fresh set of neighbors; song radio:
fall back to artist-radio expansion off the seed artist). Also pin the
degenerate-seed and Last.fm-failure fallbacks.

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/radio/RadioStationGenerator.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/radio/RadioStationGeneratorGrowTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.stash.core.data.radio

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmSimilarArtist
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.ArtistProfile
import com.stash.data.ytmusic.model.ArtistSummary
import com.stash.data.ytmusic.model.TrackSummary
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RadioStationGeneratorGrowTest {
    private val lastFm: LastFmApiClient = mockk(relaxed = true)
    private val yt: YTMusicApiClient = mockk(relaxed = true)
    private fun gen() = RadioStationGenerator(lastFm, yt)

    private fun ts(a: String, n: Int) = TrackSummary("$a$n", "$a song $n", a, null, 180.0, null)
    private fun prof(a: String, id: String) = ArtistProfile(id, a, null, null,
        (1..6).map { ts(a, it) }, emptyList(), emptyList(), emptyList())

    @Test fun `nextBatch widens PAST the initial pool into fresh neighbors`() = runTest {
        // This test must genuinely trigger widen() — not just drain a big pre-built
        // pool. The generator constants make the widen threshold `ordered.size - 6`,
        // so the INITIAL pool must be small enough that one 12-track batch crosses
        // it. We give the seed its 6 popular tracks but each neighbor only ONE, so
        // the initial pool = 6 + 12×1 = 18; after the first batch cursor=12 and
        // 12 >= 18-6 → widen fires and must page neighbors 13-20 (absent from the
        // initial 12-neighbor pool). A track from N13..N20 in the 2nd batch can
        // ONLY come from widen — so this fails if widen reverts to the no-op.
        // Define generic getArtist(any()) FIRST, then specific "MBV" (MockK last-match).
        coEvery { lastFm.getSimilarArtists(any(), any()) } returns Result.success(
            (1..20).map { LastFmSimilarArtist("N$it", 1f / it) })
        coEvery { yt.resolveArtist(any()) } answers
            { ArtistSummary(firstArg<String>() + "_ID", firstArg(), null) }
        coEvery { yt.getArtist(any()) } answers {
            val id = firstArg<String>(); val name = id.removeSuffix("_ID")
            ArtistProfile(id, name, null, null, listOf(ts(name, 1)),   // ONE popular track
                emptyList(), emptyList(), emptyList())
        }
        coEvery { yt.getArtist("MBV") } returns prof("My Bloody Valentine", "MBV") // seed: 6 tracks

        val g = gen()
        val (session, first) = g.start(RadioSeed.Artist("My Bloody Valentine", "MBV"))
        val second = g.nextBatch(session)

        val widened = (13..20).map { "N$it" }
        assertThat(second.map { it.artist }.any { it in widened }).isTrue() // widen actually ran
        val allIds = (first + second).mapNotNull { it.youtubeId }
        assertThat(allIds.toSet()).hasSize(allIds.size) // zero repeats across batches
    }

    @Test fun `degenerate seed with no neighbors still returns seed-only tracks`() = runTest {
        coEvery { lastFm.getSimilarArtists(any(), any()) } returns Result.success(emptyList())
        coEvery { yt.getArtist("MBV") } returns prof("My Bloody Valentine", "MBV")

        val (_, batch) = gen().start(RadioSeed.Artist("My Bloody Valentine", "MBV"))
        assertThat(batch).isNotEmpty()
        assertThat(batch.all { it.artist == "My Bloody Valentine" }).isTrue()
    }

    @Test fun `lastfm failure degrades to seed-only, not empty`() = runTest {
        coEvery { lastFm.getSimilarArtists(any(), any()) } returns
            Result.failure(RuntimeException("429"))
        coEvery { yt.getArtist("MBV") } returns prof("My Bloody Valentine", "MBV")

        val (_, batch) = gen().start(RadioSeed.Artist("My Bloody Valentine", "MBV"))
        assertThat(batch).isNotEmpty()
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.radio.RadioStationGeneratorGrowTest"`
Expected: FAIL — `nextBatch` unresolved.

- [ ] **Step 3: Implement `nextBatch` + widening**

```kotlin
    /** The next playable batch. Widens the pool when it nears exhaustion. */
    suspend fun nextBatch(session: RadioSession): List<Track> {
        if (session.cursor >= session.ordered.size - WIDEN_THRESHOLD && !session.exhausted) {
            widen(session)
        }
        return drainBatch(session, GROW_BATCH)
    }

    /** Append more candidates by PAGING the unused neighbor list (never
     *  re-fetching the same top-N, which would only re-return already-played
     *  neighbors). Song radio lazy-loads the neighbor list here on first widen;
     *  artist radio filled it at start. Sets [exhausted] only when no neighbors
     *  remain to try. */
    private suspend fun widen(session: RadioSession) {
        if (!session.neighborsLoaded) {
            val seedArtistName = when (val s = session.seed) {
                is RadioSeed.Artist -> s.name
                is RadioSeed.Song -> s.artist
            }
            session.remainingNeighbors.addAll(
                lastFm.getSimilarArtists(seedArtistName, limit = NEIGHBOR_MAX).getOrDefault(emptyList()),
            )
            session.neighborsLoaded = true
        }
        if (session.remainingNeighbors.isEmpty()) { session.exhausted = true; return }
        // Page the next slice of never-used neighbors.
        val next = ArrayList<com.stash.core.data.lastfm.LastFmSimilarArtist>()
        while (next.size < NEIGHBOR_POOL && session.remainingNeighbors.isNotEmpty()) {
            next += session.remainingNeighbors.removeFirst()
        }
        val fresh = ArrayList<RadioCandidate>()
        for (n in next) {
            val id = yt.resolveArtist(n.name)?.id ?: continue
            val popular = runCatching { yt.getArtist(id).popular }.getOrDefault(emptyList())
            popular.take(TRACKS_PER_ARTIST).forEach {
                val cand = RadioCandidate(n.name, it.title, it.videoId, n.match.coerceAtLeast(0.05f))
                if (cand.identity() !in session.played) fresh += cand
            }
        }
        if (fresh.isEmpty()) {
            // This slice yielded nothing new; only truly done when no neighbors remain.
            if (session.remainingNeighbors.isEmpty()) session.exhausted = true
            return
        }
        session.ordered += RadioInterleaver.order(fresh)
    }
```

Add to `companion object`: `const val WIDEN_THRESHOLD = 6`.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.radio.RadioStationGeneratorGrowTest"`
Expected: PASS (3/3).

- [ ] **Step 5: Run the whole radio package**

Run: `./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.radio.*"`
Expected: PASS (all radio tests).

- [ ] **Step 6: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/radio/RadioStationGenerator.kt \
        core/data/src/test/kotlin/com/stash/core/data/radio/RadioStationGeneratorGrowTest.kt
git commit -m "feat(radio): nextBatch self-extension, widening, seed-only + failure fallbacks"
```

---

## Phase B — Queue integration (`core:media`)

### Task B1: `startRadio` / `stopRadio` on PlayerRepository + arm/disarm

Mirror `shuffleLibrary`: `startRadio(seed)` calls the generator, sets the queue,
plays, and **arms** the radio watcher. `setQueueInternal` **disarms** it (like it
does for library shuffle). One station at a time.

**Files:**
- Modify: `core/media/src/main/kotlin/com/stash/core/media/PlayerRepository.kt`
- Modify: `core/media/src/main/kotlin/com/stash/core/media/PlayerRepositoryImpl.kt`
- Test: `core/media/src/test/kotlin/com/stash/core/media/PlayerRepositoryRadioTest.kt`

- [ ] **Step 1: Add to the `PlayerRepository` interface**

```kotlin
    /**
     * Start a radio station seeded from an artist or track. Builds a balanced
     * queue and arms self-extension; replaces any current queue/station. No-op
     * (returns false) if streaming is off/offline or the seed yields nothing.
     */
    suspend fun startRadio(seed: com.stash.core.data.radio.RadioSeed): Boolean

    /** Stop the active station (queued tracks remain; no more auto-grow). */
    fun stopRadio()

    /** Live flow of the active station's seed label (null when no station). */
    val radioSeedLabel: kotlinx.coroutines.flow.StateFlow<String?>
```

- [ ] **Step 2: Write the failing test**

```kotlin
package com.stash.core.media

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.radio.RadioSeed
import io.mockk.coEvery
import io.mockk.mockk
// ... (mirror the imports/harness of PlayerRepositoryStreamingTest.kt)
import kotlinx.coroutines.test.runTest
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
class PlayerRepositoryRadioTest {
    // Harness: copy PlayerRepositoryFullTimelineTest.kt exactly — it builds a real
    // PlayerRepositoryImpl in @Before with all mocked collaborators AND assigns a
    // relaxed mock MediaController to the internal seam: `repo.controllerDeferred =
    // controller`. Add one collaborator: `radioGenerator: RadioStationGenerator =
    // mockk()`, and pass it as the new last constructor arg.

    @Test fun `startRadio returns false and does not arm when streaming is off`() = runTest {
        // coEvery { streamingPreference.current() } returns false
        // assertThat(repo.startRadio(RadioSeed.Artist("MBV", "id"))).isFalse()
        // assertThat(repo.radioSeedLabel.value).isNull()
        // coVerify(exactly = 0) { radioGenerator.start(any()) }
    }

    @Test fun `startRadio sets queue, plays, arms watcher, and exposes seed label`() = runTest {
        // coEvery { streamingPreference.current() } returns true
        // coEvery { radioGenerator.start(any()) } returns (session to listOf(track1, track2))
        // repo.startRadio(RadioSeed.Artist("My Bloody Valentine", "id"))
        // verify { controller.setMediaItems(any(), 0, 0L) }; verify { controller.play() }
        // assertThat(repo.radioSeedLabel.value).isEqualTo("My Bloody Valentine")
    }

    @Test fun `setQueue disarms the station`() = runTest {
        // after startRadio, repo.setQueue(listOf(someTrack))
        // → radioSeedLabel.value == null (station ended)
    }
}
```

> Implementer note: use `PlayerRepositoryFullTimelineTest.kt` (not the Streaming
> one) as the harness — it is the test that assigns a mock `MediaController` to
> `controllerDeferred`, which `startRadio`/the watcher need. Keep assertions to the
> behaviors above.

- [ ] **Step 3: Run to verify it fails**

Run: `./gradlew :core:media:testDebugUnitTest --tests "com.stash.core.media.PlayerRepositoryRadioTest"`
Expected: FAIL — `startRadio` unresolved.

- [ ] **Step 4: Implement in `PlayerRepositoryImpl`**

Add constructor param (after `playbackResumer`):
```kotlin
    private val radioGenerator: com.stash.core.data.radio.RadioStationGenerator,
```
Add fields near `libraryShuffleActive`:
```kotlin
    @Volatile private var radioActive: Boolean = false
    @Volatile private var radioSession: com.stash.core.data.radio.RadioSession? = null
    private val _radioSeedLabel = MutableStateFlow<String?>(null)
    override val radioSeedLabel: StateFlow<String?> = _radioSeedLabel.asStateFlow()
    private val radioGrowMutex = Mutex()
```
Implement the methods:
```kotlin
    override suspend fun startRadio(seed: com.stash.core.data.radio.RadioSeed): Boolean {
        if (!streamingPreference.current()) return false
        val controller = ensureController() ?: return false
        val (session, firstBatch) = radioGenerator.start(seed)
        if (firstBatch.isEmpty()) return false
        radioSession = session
        radioActive = true
        // Only ONE grower may run: startRadio bypasses setQueueInternal (which is
        // what normally disarms library shuffle), so disarm it here explicitly —
        // otherwise both watchers append as the queue drains and library tracks
        // pollute the station.
        libraryShuffleActive = false
        librarySnapshot = emptyList()
        currentQueueTracks = firstBatch
        controller.setMediaItems(firstBatch.map { it.toMediaItem() }, 0, 0L)
        controller.prepare()
        controller.play()
        _radioSeedLabel.value = when (seed) {
            is com.stash.core.data.radio.RadioSeed.Artist -> seed.name
            is com.stash.core.data.radio.RadioSeed.Song -> seed.title
        }
        return true
    }

    override fun stopRadio() {
        radioActive = false
        radioSession = null
        _radioSeedLabel.value = null
    }
```
In `setQueueInternal`, next to `libraryShuffleActive = false`, add:
```kotlin
        radioActive = false
        radioSession = null
        _radioSeedLabel.value = null
```
(Also disarm in `shuffleLibrary()` — set the same three — so switching modes ends the station.)

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew :core:media:testDebugUnitTest --tests "com.stash.core.media.PlayerRepositoryRadioTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/PlayerRepository.kt \
        core/media/src/main/kotlin/com/stash/core/media/PlayerRepositoryImpl.kt \
        core/media/src/test/kotlin/com/stash/core/media/PlayerRepositoryRadioTest.kt
git commit -m "feat(radio): startRadio/stopRadio on PlayerRepository (arm/disarm like shuffle)"
```

---

### Task B2: Radio auto-grow watcher

Add a watcher sibling to the library-shuffle one: when a station is armed and the
queue nears the tail, append `nextBatch`.

**Files:**
- Modify: `core/media/src/main/kotlin/com/stash/core/media/PlayerRepositoryImpl.kt`
- Test: extend `PlayerRepositoryRadioTest.kt`

- [ ] **Step 1: Write the failing test**

Add:
```kotlin
    @Test fun `queue nearing tail while radio active appends the next batch`() = runTest {
        // arm radio with a 12-track batch; drive playerState so
        // remaining < RADIO_GROW_THRESHOLD; stub generator.nextBatch to return
        // 12 more; assert controller.addMediaItems called and currentQueueTracks grew.
    }

    @Test fun `no grow when radio inactive`() = runTest {
        // radioActive false; same low-remaining state → generator.nextBatch never called.
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :core:media:testDebugUnitTest --tests "com.stash.core.media.PlayerRepositoryRadioTest"`
Expected: FAIL on the two new cases.

- [ ] **Step 3: Implement the watcher (in `init`, mirroring the library grower)**

```kotlin
        // Radio auto-grow watcher. Mirrors the library-shuffle grower: when a
        // station is armed and the queue nears the tail, append the next batch.
        scope.launch {
            playerState.collect { state ->
                if (!radioActive) return@collect
                val remaining = state.queue.size - state.currentIndex - 1
                if (remaining in 0 until RADIO_GROW_THRESHOLD) growRadio()
            }
        }
```
Add `growRadio`:
```kotlin
    private suspend fun growRadio() {
        radioGrowMutex.withLock {
            if (!radioActive) return
            val controller = controllerDeferred ?: return
            val session = radioSession ?: return
            val batch = radioGenerator.nextBatch(session)
            if (batch.isEmpty()) return
            controller.addMediaItems(batch.map { it.toMediaItem() })
            currentQueueTracks = currentQueueTracks + batch
        }
    }
```
Add const near the others: `private const val RADIO_GROW_THRESHOLD = 5`.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :core:media:testDebugUnitTest --tests "com.stash.core.media.PlayerRepositoryRadioTest"`
Expected: PASS (all cases).

- [ ] **Step 5: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/PlayerRepositoryImpl.kt \
        core/media/src/test/kotlin/com/stash/core/media/PlayerRepositoryRadioTest.kt
git commit -m "feat(radio): single-flight auto-grow watcher refills the station queue"
```

---

## Phase C — Entry points (feature modules)

### Task C1: Artist profile "Radio" button

**Files:**
- Modify: `feature/search/src/main/kotlin/com/stash/feature/search/ArtistProfileViewModel.kt`
- Modify: `feature/search/src/main/kotlin/com/stash/feature/search/ArtistHero.kt` — the Play button host (`onPlayArtist` param ~line 61, button ~line 116). Add an `onStartRadio: () -> Unit` param and a **Radio** button next to Play.
- Modify: `feature/search/src/main/kotlin/com/stash/feature/search/ArtistProfileScreen.kt:120` — pass `onStartRadio = vm::startRadio` alongside `onPlayArtist = vm::playArtist`.
- Test: `feature/search/src/test/kotlin/com/stash/feature/search/ArtistProfileViewModelRadioTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// Verify vm.startRadio() calls playerRepository.startRadio(RadioSeed.Artist(name, browseId)).
// Mock PlayerRepository; assert coVerify { startRadio(RadioSeed.Artist(<name>, <artistId>)) }.
```

- [ ] **Step 2: Run to verify it fails.** Expected: FAIL — `startRadio` unresolved on the VM.

- [ ] **Step 3: Implement**

In `ArtistProfileViewModel` (it already has `playerRepository`, `savedStateHandle` name + `artistId`):
```kotlin
    fun startRadio() {
        viewModelScope.launch {
            val name = _uiState.value.hero.name.ifBlank { initialName }
            val started = playerRepository.startRadio(
                com.stash.core.data.radio.RadioSeed.Artist(name, ytBrowseId = artistId),
            )
            if (!started) _uiState.update { it.copy(radioUnavailable = true) } // transient flag → toast
        }
    }
```
Add a `radioUnavailable` one-shot to the UI state (reset after shown), and a **Radio** button in the artist hero next to Play wired to `vm::startRadio`. Follow the existing button styling in that hero.

- [ ] **Step 4: Run to verify it passes.** Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add feature/search/src/main/kotlin/com/stash/feature/search/ArtistProfileViewModel.kt \
        feature/search/src/main/kotlin/com/stash/feature/search/ArtistProfileScreen.kt \
        feature/search/src/test/kotlin/com/stash/feature/search/ArtistProfileViewModelRadioTest.kt
# plus the hero composable file you edited
git commit -m "feat(radio): Radio button on artist profile"
```

---

### Task C2: Song ⋮ "Start radio"

Put the action on the shared `TrackActionsDelegate` (already has
`playerRepository` + `streamingPreference`), then surface it in the two overflow
surfaces: `PreviewDownloadRow` (search/album) and `TrackOptionsSheet` (library).

**Files:**
- Modify: `core/media/src/main/kotlin/com/stash/core/media/actions/TrackActionsDelegate.kt`
- Modify: `core/ui/src/main/kotlin/com/stash/core/ui/components/TrackOptionsSheet.kt` (add optional `onStartRadio: ((Track) -> Unit)? = null` + a menu row when non-null)
- Modify the `PreviewDownloadRow` overflow menu to add a "Start radio" item calling a passed `onStartRadio`.
- Test: `core/media/src/test/kotlin/com/stash/core/media/actions/TrackActionsDelegateRadioTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// delegate.startRadio(item) → coVerify { playerRepository.startRadio(
//   RadioSeed.Song(item.title, item.artist, item.videoId)) }
```

- [ ] **Step 2: Run to verify it fails.** Expected: FAIL.

- [ ] **Step 3: Implement**

In `TrackActionsDelegate`:
```kotlin
    fun startRadio(item: TrackItem) {
        scope().launch {
            playerRepository.startRadio(
                com.stash.core.data.radio.RadioSeed.Song(
                    title = item.title, artist = item.artist, ytVideoId = item.videoId,
                ),
            )
        }
    }
```
Thread `onStartRadio` through `TrackOptionsSheet` (optional param + a `MenuRow("Start radio", Icons.Rounded.Radio) { onStartRadio(track) }` when non-null) and the `PreviewDownloadRow` overflow. Wire callers that already expose the delegate (search/album screens) to `vm.delegate::startRadio`; library screens pass a lambda that builds a `TrackItem` from the row's `Track`.

- [ ] **Step 4: Run to verify it passes.** Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/actions/TrackActionsDelegate.kt \
        core/ui/src/main/kotlin/com/stash/core/ui/components/TrackOptionsSheet.kt \
        core/media/src/test/kotlin/com/stash/core/media/actions/TrackActionsDelegateRadioTest.kt
# plus PreviewDownloadRow + any caller wiring you touched
git commit -m "feat(radio): Start radio in the shared track overflow menu"
```

---

### Task C3: Now Playing — start radio + live station chip

**Files:**
- Modify: `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingViewModel.kt` (add `startRadioFromCurrent()` + `stopRadio()`; expose `playerRepository.radioSeedLabel`)
- Modify: `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingScreen.kt` — a "Start radio" action in the player overflow, and a small "Radio · \<label\>" chip (with a Stop affordance) shown when `radioSeedLabel != null`.
- Test: `feature/nowplaying/src/test/kotlin/com/stash/feature/nowplaying/NowPlayingViewModelRadioTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// startRadioFromCurrent() with a current track → coVerify {
//   playerRepository.startRadio(RadioSeed.Song(title, artist, youtubeId)) }.
// stopRadio() → verify { playerRepository.stopRadio() }.
```

- [ ] **Step 2: Run to verify it fails.** Expected: FAIL.

- [ ] **Step 3: Implement**

```kotlin
    val radioSeedLabel = playerRepository.radioSeedLabel // StateFlow<String?>

    fun startRadioFromCurrent() {
        val t = uiState.value.currentTrack ?: return   // use the VM's current-track field
        viewModelScope.launch {
            playerRepository.startRadio(
                com.stash.core.data.radio.RadioSeed.Song(t.title, t.artist, t.youtubeId),
            )
        }
    }

    fun stopRadio() = playerRepository.stopRadio()
```
Add the overflow action + the chip in the screen. Chip is the only radio-specific UI here.

- [ ] **Step 4: Run to verify it passes.** Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingViewModel.kt \
        feature/nowplaying/src/test/kotlin/com/stash/feature/nowplaying/NowPlayingViewModelRadioTest.kt
# plus the Now Playing screen file
git commit -m "feat(radio): start-from-current + live station chip on Now Playing"
```

---

## Phase D — Integration & verification

### Task D1: Compile the whole app + DI graph check

- [ ] **Step 1:** `./gradlew :app:assembleDebug`
  Expected: BUILD SUCCESSFUL. (Confirms Hilt resolves `RadioStationGenerator` into `PlayerRepositoryImpl` and all feature wiring compiles. If Hilt complains about a missing binding, the generator's `@Inject constructor` + both clients being injectable should satisfy it — no module needed.)

- [ ] **Step 2:** Full targeted test sweep of everything this feature touched:
```bash
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.radio.*" \
          :core:media:testDebugUnitTest --tests "com.stash.core.media.PlayerRepositoryRadioTest" --tests "com.stash.core.media.actions.TrackActionsDelegateRadioTest" \
          :feature:search:testDebugUnitTest --tests "com.stash.feature.search.ArtistProfileViewModelRadioTest" \
          :feature:nowplaying:testDebugUnitTest --tests "com.stash.feature.nowplaying.NowPlayingViewModelRadioTest"
```
  Expected: BUILD SUCCESSFUL.

- [ ] **Step 3:** Install + device smoke test: `./gradlew :app:installDebug` then, with **streaming enabled/online**:
  1. Artist profile → **Radio** → queue fills, Now Playing opens, plays; ~1/3 seed, rest neighbors; grows past the first batch as it plays down.
  2. Song ⋮ → **Start radio** → station seeded from that track.
  3. Now Playing overflow → **Start radio** → chip shows "Radio · \<label\>"; **Stop** ends grow.
  4. Toggle streaming **off** → radio affordances disabled with the hint.
  Watch logcat for `startRadio`/`nextBatch` activity and confirm real videoIds resolve (qbdlx FLAC upgrade at play time, same as other streaming tracks). Also watch, near station exhaustion, for a YT-lookup burst (the watcher can fire `growRadio` on each `playerState` emission while a barren widen yields nothing) — bounded and single-flight, so acceptable for v1, but if it's a visible storm add a "last-widen-yielded-nothing" backoff. `ponytail:` YAGNI unless observed.

- [ ] **Step 4: Commit** any wiring/nit fixes from the smoke test.

```bash
git add -A && git commit -m "chore(radio): integration wiring + smoke-test fixes"
```

---

## Notes for the implementer

- **TDD, mock the two clients.** `LastFmApiClient` and `YTMusicApiClient` are final Kotlin classes — MockK handles them (see the discography `QobuzDiscographyProviderTest` for the pattern). Their similarity methods return `Result<…>`; stub with `Result.success(...)` / `Result.failure(...)`.
- **Never throw from the generator.** Every Last.fm call is `runCatching`-wrapped already; `.getOrDefault(emptyList())` and keep going. A single YT miss = skip that candidate.
- **Real videoIds → no persistence.** Do **not** call `ensureTrackPersisted` for radio tracks (that was a Qobuz-native concern). `id = videoId.hashCode()` is the existing convention.
- **Watcher discipline.** The radio watcher must early-return when `!radioActive`, and `growRadio` is single-flight via `radioGrowMutex`. `setQueue`/`shuffleLibrary`/explicit stop all disarm.
- **Pre-existing test noise:** the `:data:ytmusic` and `:feature:search` modules have known-failing unrelated tests (see memory `infra_preexisting_matcher_test_failures`) — always use `--tests` filters; don't gate on the flaky `:core:media` network test.
- **Offline gate:** `startRadio` guards on `streamingPreference.current()` and, being streaming-only, returns `false` gracefully when offline (empty batch). The entry-point affordances (artist Radio button, ⋮ item, Now Playing action) should disable/hint when streaming is off **or** there is no network — reuse `streamingPreference.enabled` and the existing connectivity signal (spec §5, "no dead taps"). A disabled button never calls `startRadio`.
