# Full-Timeline Queue + Just-in-Time URL Resolution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the bounded rolling-window ExoPlayer timeline (11 items over an N-track logical queue) with a full timeline — every queue track becomes a MediaItem immediately — resolving stream URLs just-in-time in the DataSource layer, so native next/previous/repeat/shuffle are correct on every surface and the entire dual-bookkeeping layer is deleted.

**Architecture:** Stream tracks without a resolved URL enter the timeline with a `stash-resolve://track/<id>` placeholder URI. A new `LazyResolvingDataSource` intercepts that scheme in `DataSource.open()` on ExoPlayer's loader thread (the same proven pattern as the shipped `RefreshingDataSource` 403 recovery), resolves via `StreamUrlCache`-then-`StreamSourceRegistry` under a hard 45 s deadline, and delegates the read to an origin-appropriate inner source (plain HTTP for lossless, refresh-wrapped HTTP for YouTube, authed OkHttp for amz). `PlayerRepositoryImpl` loses the fill window, top-up buffer, pending-nav skip chain, resolve epochs, end-of-timeline recovery, and all three repeat-all wrap patches. The existing next-up prefetch survives as a URL-cache warmer + in-place item upgrade, so the common auto-advance path never blocks.

**Tech Stack:** Kotlin, Media3 1.9.2 (ExoPlayer + session), Hilt, MockK/Robolectric/Truth for tests.

---

## Why the 2026-05-22 `stash-lazy://` revert does NOT apply here

`4f80cc8d` (reverted by `8ec826db`) put placeholder resolution in the **session callback** (`resolveMediaItem` inside `onAddMediaItems`/`onSetMediaItems`). That runs at **add time**, not play time; any item that reached ExoPlayer unresolved kept its `stash-lazy://` URI, no DataSource handled the scheme, and playback errored (its own log line: "leaving placeholder URI; player will error"). The KDoc warning at `PlayerRepositoryImpl.kt` ("NOT the reverted stash-lazy:// placeholder/synthetic-id scheme") targets THAT design: session-layer placeholders + synthetic ids.

This plan resolves in the **DataSource layer** at `open()` — the layer where Media3 documents blocking as legal (`ResolvingDataSource.Resolver.resolveDataSpec`: "allowed to block until the DataSpec has been resolved") and where this codebase already blocks in production (`RefreshingDataSource.open` runs `runBlocking { resolver.resolve(track) }` on the loader thread for 403 recovery). ExoPlayer never sees an unresolvable URI — it sees a source that buffers, which drives the normal `STATE_BUFFERING` UI. Placeholders carry real DB track ids (`EXTRA_TRACK_ID`), not synthetic ids. When Task 4 lands, update that KDoc comment to point at this plan.

## File structure

| File | Action | Responsibility |
|---|---|---|
| `core/media/src/main/kotlin/com/stash/core/media/streaming/LazyResolvingDataSource.kt` | Create | Placeholder scheme constants; blocking resolve-with-deadline at `open()`; origin-based inner-source dispatch |
| `core/media/src/test/kotlin/com/stash/core/media/streaming/LazyResolvingDataSourceTest.kt` | Create | Unit tests for resolve/dispatch/timeout/passthrough |
| `core/media/src/main/kotlin/com/stash/core/media/streaming/StashMediaSourceFactory.kt` | Modify | Fourth routing branch: placeholder scheme → lazy factory |
| `core/media/src/test/kotlin/com/stash/core/media/streaming/StashMediaSourceFactoryTest.kt` | Modify | Routing test for the new branch |
| `core/media/src/main/kotlin/com/stash/core/media/PlayerRepositoryImpl.kt` | Modify (large) | Full-timeline `setQueueInternal`; native-only skips; placeholder item builder; delete window machinery |
| `core/media/src/main/kotlin/com/stash/core/media/service/StashPlaybackService.kt` | Modify | Wire lazy deps into `StashMediaSourceFactory`; delete `onPlayerCommandRequest` interception + `playerRepository` injection |
| `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingScreen.kt` | Modify | Play/pause button stays enabled while buffering |
| `core/media/src/test/kotlin/com/stash/core/media/PlayerRepositoryFullTimelineTest.kt` | Create | setQueue materializes all items; skips are native |
| `core/media/src/test/kotlin/com/stash/core/media/PlayerRepositoryRepeatAllWrapTest.kt` | Delete | Tests machinery this plan removes |
| `core/media/src/test/kotlin/com/stash/core/media/QueueFillWindowTest.kt` | Delete | Fill window is gone |
| `core/media/src/test/kotlin/com/stash/core/media/PlayerRepositoryEndOfTimelineRecoveryTest.kt` | Delete | STATE_ENDED recovery is gone (timeline is never truncated) |
| `core/media/src/test/kotlin/com/stash/core/media/PlayerRepositorySkipUpgradeTest.kt` | Modify | Skips are native-only now |

Base branch: `feat/full-timeline-lazy-resolve` created from `fix/repeat-all-window-wrap` (04e14206 — the checkpoint whose machinery this plan deletes; starting from it keeps history linear).

Key existing code to read before starting: `RefreshingDataSourceFactory.kt` (the blocking-resolve precedent, including the single-`runBlocking` re-entrancy note), `StreamingMediaSourceFactory.kt`, `StashMediaSourceFactory.kt`, `PlayerRepositoryImpl.setQueueInternal` / `prefetchNextTrack` / `updateState`, `StashPlaybackService` lines ~340-400 (factory wiring) and `StashSessionCallback`.

Invariants that MUST survive (each has a consumer today):
1. `PlayerState.queue == currentQueueTracks` (memory: v0.9.50 invariant; `QueueDisplay.compute` keeps working — with a full timeline the two lists converge naturally).
2. Every MediaItem carries `EXTRA_TRACK_ID` (+ `EXTRA_TRACK_IS_STREAMABLE`, duration, youtubeId) — offline silent-skip, scrobbler, like button, and resume all read these.
3. amz items must NEVER route through `RefreshingDataSource` (auth is a header, not a URL) and must NOT be disk-cached.
4. The cascade guard error flow (`onPlayerError` → `classifyPlaybackError` → recover/halt) stays the sole failure policy; the lazy source reports failure by throwing `IOException` from `open()`.
5. `prefetchNextTrack`'s in-place upgrade (`refreshControllerMediaItem`) keeps running: it swaps the next-up placeholder to a real resolved URL + quality extras, so the common auto-advance path uses today's proven eager chain (incl. disk cache) and Now Playing keeps its codec/bit-depth badge. The lazy DataSource is the fallback for cold jumps only.

---

### Task 1: Pause must stay possible while buffering (safety fix, independent)

**Files:**
- Modify: `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingScreen.kt:611-635`

- [ ] **Step 1: Change the button**

At line 611-613 the play/pause `IconButton` has `enabled = !isBuffering`, which turns it into a dead spinner during any resolve/buffer — this is the "can't pause" lockout. Remove the `enabled` gate entirely (keep the spinner icon):

```kotlin
IconButton(
    onClick = onPlayPauseClick,
    modifier = Modifier
```

(delete the `enabled = !isBuffering,` line; the `if (isBuffering) CircularProgressIndicator … else Icon` body is unchanged — spinner still shows, tap now pauses/plays.)

`onPlayPauseClick` → `PlayerRepositoryImpl.pause()/play()` works during buffering (Media3 `COMMAND_PLAY_PAUSE` is always available), so no other change is needed.

- [ ] **Step 2: Compile**

Run: `.\gradlew.bat :feature:nowplaying:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingScreen.kt
git commit -m "fix(nowplaying): keep play/pause tappable while buffering (spinner lockout)"
```

---

### Task 2: `LazyResolvingDataSource` (the core new unit)

**Files:**
- Create: `core/media/src/main/kotlin/com/stash/core/media/streaming/LazyResolvingDataSource.kt`
- Test: `core/media/src/test/kotlin/com/stash/core/media/streaming/LazyResolvingDataSourceTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.stash.core.media.streaming

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.delay
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class LazyResolvingDataSourceTest {

    private val resolver: StreamSourceRegistry = mockk()
    private val urlCache: StreamUrlCache = mockk(relaxUnitFun = true)
    private val trackDao: TrackDao = mockk()
    private val httpSource: DataSource = mockk(relaxed = true)
    private val amzSource: DataSource = mockk(relaxed = true)

    private fun source(deadlineMs: Long = 45_000L) = LazyResolvingDataSource(
        resolver = resolver,
        urlCache = urlCache,
        trackDao = trackDao,
        httpDelegate = { httpSource },
        amzDelegate = { amzSource },
        resolveDeadlineMs = deadlineMs,
    )

    private fun placeholderSpec(trackId: Long) =
        DataSpec.Builder().setUri(Uri.parse("$STASH_RESOLVE_SCHEME://track/$trackId")).build()

    private fun stream(url: String, origin: String) = StreamUrl(
        url = url, expiresAtMs = Long.MAX_VALUE, origin = origin,
    )

    @Test
    fun `cache hit opens delegate with the cached url and skips the resolver`() {
        every { urlCache.get(42L) } returns stream("https://cdn/x.flac", "squid")
        val spec = slot<DataSpec>()
        every { httpSource.open(capture(spec)) } returns 100L

        source().open(placeholderSpec(42L))

        assertThat(spec.captured.uri.toString()).isEqualTo("https://cdn/x.flac")
        coVerify(exactly = 0) { trackDao.getById(any()) } // getById is suspend — coVerify, not verify
    }

    @Test
    fun `cache miss resolves via registry and caches the result`() {
        every { urlCache.get(42L) } returns null
        // getById is suspend (TrackDao.kt:376) — coEvery, not every.
        coEvery { trackDao.getById(42L) } returns TrackEntity(id = 42L, title = "t", artist = "a")
        coEvery { resolver.resolve(any(), any(), any()) } returns stream("https://cdn/y.flac", "qbdlx")
        every { httpSource.open(any()) } returns 100L

        source().open(placeholderSpec(42L))

        verify { urlCache.put(42L, any()) }
        verify { httpSource.open(any()) }
    }

    @Test
    fun `amz origin routes to the amz delegate not the http delegate`() {
        every { urlCache.get(7L) } returns stream("https://amz/z.flac", "amz")
        every { amzSource.open(any()) } returns 100L

        source().open(placeholderSpec(7L))

        verify(exactly = 1) { amzSource.open(any()) }
        verify(exactly = 0) { httpSource.open(any()) }
    }

    @Test
    fun `failed resolve throws IOException so the cascade guard owns the failure`() {
        every { urlCache.get(42L) } returns null
        coEvery { trackDao.getById(42L) } returns null

        assertThrows(IOException::class.java) { source().open(placeholderSpec(42L)) }
    }

    @Test
    fun `hung resolve hits the deadline and throws instead of blocking forever`() {
        every { urlCache.get(42L) } returns null
        coEvery { trackDao.getById(42L) } returns TrackEntity(id = 42L, title = "t", artist = "a")
        coEvery { resolver.resolve(any(), any(), any()) } coAnswers { delay(60_000); null }

        assertThrows(IOException::class.java) { source(deadlineMs = 200L).open(placeholderSpec(42L)) }
    }

    @Test
    fun `non-placeholder specs pass through to the http delegate untouched`() {
        val spec = DataSpec.Builder().setUri(Uri.parse("https://direct/a.flac")).build()
        every { httpSource.open(spec) } returns 5L

        source().open(spec)

        verify { httpSource.open(spec) }
        verify(exactly = 0) { urlCache.get(any()) }
    }

    @Test
    fun `read and close forward to whichever delegate was opened`() {
        every { urlCache.get(42L) } returns stream("https://cdn/x.flac", "squid")
        every { httpSource.open(any()) } returns 10L
        every { httpSource.read(any(), any(), any()) } returns 4

        val s = source()
        s.open(placeholderSpec(42L))
        s.read(ByteArray(4), 0, 4)
        s.close()

        verify { httpSource.read(any(), 0, 4) }
        verify { httpSource.close() }
    }
}
```

Verified signatures (plan-review pass, 2026-07-04 — no need to re-check):
- `StreamUrl(url, expiresAtMs, codec=null, bitsPerSample=null, sampleRateHz=null, bitrateKbps=null, coverArtUrl=null, origin=null)` — only `url`/`expiresAtMs` required; the test helper compiles as written.
- `TrackEntity` — only `title` and `artist` lack defaults; `TrackEntity(id=42L, title="t", artist="a")` is valid.
- `StreamSourceRegistry.resolve` is a single `suspend fun resolve(track: TrackEntity, allowYouTube: Boolean = true, allowYtDlp: Boolean = true): StreamUrl?` (StreamSourceRegistry.kt:85).
- `TrackDao.getById` is `suspend` (TrackDao.kt:376) — always `coEvery`/`coVerify` in tests.

- [ ] **Step 2: Run tests, verify they fail to compile** (`LazyResolvingDataSource` doesn't exist)

Run: `.\gradlew.bat :core:media:testDebugUnitTest --tests "com.stash.core.media.streaming.LazyResolvingDataSourceTest"`
Expected: compilation FAILURE referencing `LazyResolvingDataSource` / `STASH_RESOLVE_SCHEME`

- [ ] **Step 3: Implement**

```kotlin
package com.stash.core.media.streaming

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.stash.core.data.db.dao.TrackDao
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException

/**
 * URI scheme for stream tracks queued BEFORE their URL is resolved:
 * `stash-resolve://track/<trackId>`. Every logical-queue track gets a
 * MediaItem immediately (full timeline — native next/prev/repeat/shuffle),
 * and the URL is resolved just-in-time here, in [DataSource.open] on
 * ExoPlayer's loader thread.
 *
 * NOT the reverted 2026-05-22 `stash-lazy://` design: that resolved in the
 * SESSION callback at add-time and let unresolved placeholders reach
 * ExoPlayer as unplayable URIs. This scheme is only ever opened by
 * [LazyResolvingDataSource], which blocks until it has a real URL or
 * throws — the documented `ResolvingDataSource` contract, and the same
 * loader-thread blocking [RefreshingDataSource] has shipped since v0.9.4x.
 */
const val STASH_RESOLVE_SCHEME = "stash-resolve"

/** Builds the placeholder URI for [trackId]. */
fun stashResolveUri(trackId: Long): Uri = Uri.parse("$STASH_RESOLVE_SCHEME://track/$trackId")

/**
 * DataSource that turns a `stash-resolve://track/<id>` placeholder into a
 * playable stream at open time: [StreamUrlCache] first (the next-up
 * prefetch usually has it warm), then a full [StreamSourceRegistry.resolve]
 * under [resolveDeadlineMs]. The read is delegated by origin:
 * amz → [amzDelegate] (authed OkHttp; header auth, never URL-refresh, never
 * disk cache), everything else → [httpDelegate].
 *
 * Failure = [IOException] from [open], which ExoPlayer surfaces as a source
 * error → `onPlayerError` → the cascade guard decides recover vs halt. The
 * deadline exists so a hung resolver can NEVER wedge playback (the
 * "spinner forever, can't pause" bug).
 *
 * Single [runBlocking] for DAO + resolve, mirroring [RefreshingDataSource]'s
 * re-entrancy note (two independent runBlocking calls on the loader thread
 * can deadlock Room's pool / the aggregator rate-limiter).
 */
@OptIn(UnstableApi::class)
class LazyResolvingDataSource(
    private val resolver: StreamSourceRegistry,
    private val urlCache: StreamUrlCache,
    private val trackDao: TrackDao,
    private val httpDelegate: () -> DataSource,
    private val amzDelegate: () -> DataSource,
    private val resolveDeadlineMs: Long = RESOLVE_DEADLINE_MS,
) : DataSource {

    private var active: DataSource? = null
    private val listeners = mutableListOf<TransferListener>()

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        active?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        if (uri.scheme != STASH_RESOLVE_SCHEME) {
            return delegateTo(httpDelegate(), dataSpec)
        }
        val trackId = uri.lastPathSegment?.toLongOrNull()
            ?: throw IOException("stash-resolve URI without a track id: $uri")

        // StreamUrlCache.get() checks expiry internally (evicts + returns null
        // when stale — StreamUrlCache.kt:71-79), so no freshness re-check here.
        val fresh = urlCache.get(trackId)
            ?: resolveBlocking(trackId)
            ?: throw IOException("stream resolve failed for track $trackId")

        val realSpec = dataSpec.buildUpon().setUri(Uri.parse(fresh.url)).build()
        val delegate = if (fresh.origin == "amz") amzDelegate() else httpDelegate()
        return delegateTo(delegate, realSpec)
    }

    private fun delegateTo(delegate: DataSource, spec: DataSpec): Long {
        listeners.forEach(delegate::addTransferListener)
        active = delegate
        return delegate.open(spec)
    }

    private fun resolveBlocking(trackId: Long): StreamUrl? = try {
        runBlocking {
            withTimeout(resolveDeadlineMs) {
                val entity = trackDao.getById(trackId) ?: return@withTimeout null
                resolver.resolve(entity, allowYouTube = true, allowYtDlp = true)
            }
        }?.also { urlCache.put(trackId, it) }
    } catch (e: TimeoutCancellationException) {
        throw IOException("stream resolve deadline (${resolveDeadlineMs}ms) for track $trackId", e)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active?.read(buffer, offset, length) ?: throw IOException("read before open")

    override fun getUri(): Uri? = active?.uri

    override fun close() {
        active?.close()
        active = null
    }

    companion object {
        /**
         * Hard ceiling on a just-in-time resolve. yt-dlp worst case is
         * ~15-35 s on the single serialized slot; 45 s covers it with margin
         * while guaranteeing playback can never hang indefinitely.
         */
        const val RESOLVE_DEADLINE_MS = 45_000L
    }
}
```

(Signatures already verified — see the "Verified signatures" note in Step 1.)

- [ ] **Step 4: Run tests, verify they pass**

Run: `.\gradlew.bat :core:media:testDebugUnitTest --tests "com.stash.core.media.streaming.LazyResolvingDataSourceTest"`
Expected: all PASS

- [ ] **Step 5: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/streaming/LazyResolvingDataSource.kt core/media/src/test/kotlin/com/stash/core/media/streaming/LazyResolvingDataSourceTest.kt
git commit -m "feat(media): LazyResolvingDataSource - just-in-time stream resolve at DataSource.open with hard deadline"
```

---

### Task 3: Route the placeholder scheme in `StashMediaSourceFactory`

**Files:**
- Modify: `core/media/src/main/kotlin/com/stash/core/media/streaming/StashMediaSourceFactory.kt`
- Modify: `core/media/src/main/kotlin/com/stash/core/media/service/StashPlaybackService.kt` (~line 340, factory construction)
- Test: `core/media/src/test/kotlin/com/stash/core/media/streaming/StashMediaSourceFactoryTest.kt`

- [ ] **Step 1: Failing test** (append to existing test class, mirroring its `item(uri)` helper and constructor pattern — it will need the three new constructor params; pass mockks)

```kotlin
@Test
fun placeholderItem_routesToLazyResolvingChain() {
    val lazy = item("stash-resolve://track/42")
    val factory = newFactory(streamingTrackId = { null }, isAmzOrigin = { false })

    val source: MediaSource = factory.createMediaSource(lazy)

    // Placeholder must not enter the eager YouTube chain, and must produce a
    // progressive source backed by the lazy factory.
    verify(exactly = 0) { streamingFactory.create(any()) }
    assertThat(source).isInstanceOf(ProgressiveMediaSource::class.java)
}
```

- [ ] **Step 2: Run, verify fails** (constructor mismatch / branch missing)

- [ ] **Step 3: Implement.** Add constructor params + branch:

```kotlin
class StashMediaSourceFactory(
    context: Context,
    private val streamingFactory: StreamingMediaSourceFactory,
    private val streamingTrackId: (MediaItem) -> Long?,
    private val isAmzOrigin: (MediaItem) -> Boolean,
    amzHttpClient: okhttp3.OkHttpClient,
    resolver: StreamSourceRegistry,
    urlCache: StreamUrlCache,
    trackDao: TrackDao,
) : MediaSource.Factory {
```

```kotlin
    // Full-timeline placeholders: stash-resolve://track/<id> items resolve
    // their URL inside LazyResolvingDataSource.open() on the loader thread.
    // Cold-jump path only — the next-up prefetch upgrades the common case to
    // a real URL in place, which then routes through the branches above.
    private val lazyFactory = DefaultMediaSourceFactory(
        DataSource.Factory {
            LazyResolvingDataSource(
                resolver = resolver,
                urlCache = urlCache,
                trackDao = trackDao,
                httpDelegate = {
                    DefaultHttpDataSource.Factory().setUserAgent("Stash/0.9.26")
                        .setConnectTimeoutMs(10_000).setReadTimeoutMs(30_000)
                        .createDataSource()
                },
                amzDelegate = {
                    androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(amzHttpClient)
                        .createDataSource()
                },
            )
        },
    )
```

In `createMediaSource`, FIRST branch (before `streamingTrackId`):

```kotlin
        if (mediaItem.localConfiguration?.uri?.scheme == STASH_RESOLVE_SCHEME) {
            return lazyFactory.createMediaSource(mediaItem)
        }
```

Also apply `setDrmSessionManagerProvider`/`setLoadErrorHandlingPolicy` to `lazyFactory` in the existing override methods (match how `localFactory`/`amzFactory` are handled).

In `StashPlaybackService` (~line 340): pass the three new args. Verified: `trackDao` is already injected (line 79); `StreamSourceRegistry` and `StreamUrlCache` are NOT yet injected (don't confuse with the existing `resumeStreamResolver: ResumeStreamResolver` / `streamingMediaSourceFactory` — different types). Add `@Inject lateinit var streamResolver: com.stash.core.media.streaming.StreamSourceRegistry` and `@Inject lateinit var streamUrlCache: com.stash.core.media.streaming.StreamUrlCache`, and pass them plus `trackDao` into the `StashMediaSourceFactory(...)` construction. `StashMediaSourceFactory.kt` will need new imports: `androidx.media3.datasource.DataSource`, `androidx.media3.datasource.DefaultHttpDataSource`, `com.stash.core.data.db.dao.TrackDao`, plus `STASH_RESOLVE_SCHEME`/`LazyResolvingDataSource` (same package).

- [ ] **Step 4: Run the factory tests** — `.\gradlew.bat :core:media:testDebugUnitTest --tests "com.stash.core.media.streaming.StashMediaSourceFactoryTest"` — all PASS (existing 4 + new 1)

- [ ] **Step 5: Commit** — `git commit -m "feat(media): route stash-resolve placeholders through LazyResolvingDataSource"`

---

### Task 4: Full-timeline `setQueueInternal` + native skips + machinery deletion

This is the big one. Work in `PlayerRepositoryImpl.kt` only. Keep: `prefetchNextTrack` (cache warm + `refreshControllerMediaItem` swap), `evictTrackFromQueue`, offline silent-skip, error cascade, library-shuffle auto-grow (it calls `setQueue`-style append — update it to append placeholder items), persistence, `QueueDisplay`.

- [ ] **Step 1: Write the failing test** (`PlayerRepositoryFullTimelineTest.kt`, same harness as `PlayerRepositorySkipUpgradeTest`):

```kotlin
@Test
fun `setQueue materializes the whole queue as media items immediately`() = runTest {
    every { streamingPreference.current() } returns true
    val tracks = (1L..57L).map { Track(id = it, title = "t$it", artist = "a") }
    val items = slot<List<MediaItem>>()
    every { controller.setMediaItems(capture(items), any<Int>(), any<Long>()) } returns Unit

    repo.setQueue(tracks, startIndex = 0)

    assertThat(items.captured).hasSize(57)
    assertThat(items.captured[5].localConfiguration?.uri?.scheme).isEqualTo("stash-resolve")
    assertThat(items.captured[5].mediaMetadata.extras?.getLong(EXTRA_TRACK_ID)).isEqualTo(6L)
}

@Test
fun `skipNext is always a native seek`() = runTest {
    every { controller.hasNextMediaItem() } returns true
    repo.skipNext()
    verify { controller.seekToNextMediaItem() }
}

@Test
fun `setQueue starts playback at the tapped index`() = runTest {
    every { streamingPreference.current() } returns true
    val tracks = (1L..20L).map { Track(id = it, title = "t$it", artist = "a") }

    repo.setQueue(tracks, startIndex = 7)

    verify { controller.setMediaItems(any(), 7, 0L) }
    verify { controller.prepare() }
    verify { controller.play() }
}
```

(`Track` needs `isStreamable = true`-equivalent state so the builder doesn't filter them; check `isUnavailableForDisplay` semantics — synced rows with `isStreamableCheckedAt = null` must be INCLUDED, mirroring the prefetch gate comment.)

- [ ] **Step 2: Run, verify fails** (setMediaItems gets 1 item today)

- [ ] **Step 3: Implement.** In `PlayerRepositoryImpl`:

**(a) Placeholder builder** — next to `Track.toMediaItem()`:

```kotlin
    /**
     * Queue-time MediaItem: downloaded-and-on-disk → the eager file:// item;
     * otherwise a stash-resolve:// placeholder that LazyResolvingDataSource
     * resolves at play time. Carries the SAME identity extras as toMediaItem
     * so every downstream consumer (silent-skip, scrobbler, likes, resume,
     * prefetch matching) keeps working unchanged.
     */
    private fun Track.toQueueMediaItem(): MediaItem {
        val localPath = filePath
        if (isDownloaded && !localPath.isNullOrBlank() && filePathExistsOnDisk(localPath)) {
            return toMediaItem()
        }
        return toMediaItem().buildUpon().setUri(stashResolveUri(id)).build()
    }
```

(`toMediaItem` already sets mediaId/title/artist/artwork + all EXTRA_* keys; only the URI differs. Import `stashResolveUri`.)

**(b) Rewrite `setQueueInternal`** — replace the whole body after the `currentQueueTracks = tracks` snapshot + controller/empty guards with:

```kotlin
        val streamingOn = streamingPreference.current()
        val safeStart = startIndex.coerceIn(0, tracks.size - 1)

        // Full timeline: EVERY playable track becomes a MediaItem now.
        // Downloaded → file://; stream → stash-resolve:// placeholder resolved
        // just-in-time by LazyResolvingDataSource. Native next/prev/repeat/
        // shuffle are correct because ExoPlayer sees the whole queue.
        // Item building does disk checks (filePathExistsOnDisk), so it runs
        // off the main thread; a 2.6k-track queue must not jank the UI.
        val playable = tracks.filter { track ->
            track.isDownloaded || (streamingOn && !track.isUnavailableForDisplay)
        }
        val items = kotlinx.coroutines.withContext(Dispatchers.IO) {
            playable.map { it.toQueueMediaItem() }
        }
        if (items.isEmpty()) {
            _userMessages.tryEmit("Nothing in this queue is playable right now.")
            return
        }
        // startIndex maps through the playable filter.
        val startId = tracks[safeStart].id
        val startInPlayable = playable.indexOfFirst { it.id == startId }.coerceAtLeast(0)

        currentQueueTracks = playable
        controller.setMediaItems(items, startInPlayable, startPositionMs)
        controller.prepare()
        controller.play()

        Log.i(TAG, "setQueue: full timeline, ${items.size} items, start=$startInPlayable")

        // Warm the next-up URL so auto-advance never waits on a cold resolve.
        scope.launch { prefetchNextTrack(controller.currentMediaItemIndex) }
```

DELETE from the old body: the `myEpoch`/`setQueueEpoch` guard block, the optimistic-display emit + `tapResolveEpoch` writes, the foreground `resolveTrackToMediaItem(tappedTrack…)` + null-failure block, the `computeFillWindow` call, and the whole `queueBuildJob = scope.launch { fillQueueAppend…fillQueuePrepend… }` block. KEEP the `queueBuildJob?.cancel()` line at the top for now (it nulls a dead field until step (d) removes the field). The `optimisticDisplay` parameter becomes unused — remove it and its call sites (`navigateToLogical` is deleted anyway; `resumeLastQueue` passes positionally, adjust).

Note: `setMediaItems` + `prepare` puts ExoPlayer into `STATE_BUFFERING` while the (possibly placeholder) start item resolves inside the DataSource — `computeIsBuffering`'s controller term drives the spinner with zero custom state, and after Task 1 the user can still pause. Tapping an unplayable track now surfaces through `onPlayerError`/cascade instead of the old "Couldn't play this track right now." snackbar — acceptable; the cascade halt message covers the outage case.

**(c) Native-only skips** — replace both bodies:

```kotlin
    override suspend fun skipNext() {
        cascadeGuard.onUserTransport()
        val controller = ensureController() ?: return
        if (controller.hasNextMediaItem()) controller.seekToNextMediaItem()
    }

    override suspend fun skipPrevious() {
        cascadeGuard.onUserTransport()
        val controller = ensureController() ?: return
        if (controller.hasPreviousMediaItem()) controller.seekToPreviousMediaItem()
    }
```

(Full timeline ⇒ `hasNextMediaItem` is false only at the true end with repeat off — the correct no-op. Repeat-all wrap is native and correct now.)

**(d) Delete** (grep each symbol for stragglers before removing):
- `pendingNavIndex`, `skipNavJob`, `navigateToLogical`, `SKIP_RESOLVE_DEBOUNCE_MS`
- `setQueueEpoch`, `tapResolveEpoch` (then `computeIsBuffering(controllerBuffering) = controllerBuffering` — keep the fun, body becomes just `controllerBuffering`)
- `currentLogicalIndex`, `maybeRecoverFromEnd` + its `STATE_ENDED` call in `onPlaybackStateChanged`
- `maybeRedirectRepeatAllWindowWrap`, the `onPositionDiscontinuity` override, `nativeSeekWouldMisWrap`
- **In the SAME task (compile-order dependency): the `StashPlaybackService` consumers of the above.** `StashPlaybackService.kt:38` imports `nativeSeekWouldMisWrap` and `StashSessionCallback.onPlayerCommandRequest` (~line 1350-1385) calls it — deleting the function without these edits breaks the module compile at Step 5. Delete the `onPlayerCommandRequest` override, the `playerRepository: dagger.Lazy<PlayerRepositoryImpl>` field + its KDoc, and the `PlayerRepositoryImpl` / `nativeSeekWouldMisWrap` imports. (Native seeks are correct against the full timeline on every surface — notification, Bluetooth, Android Auto.)
- `recoverOrStop`'s wrap guard (revert to plain `if (hasNextMediaItem()) { seek/prepare/play } else stop()`)
- `queueBuildJob`, `fillQueueAppend`, `fillQueuePrepend`, `resolveBatchParallel`, `computeFillWindow`, `FillWindow`, `BACKGROUND_FILL_LOOKAHEAD`, `BACKGROUND_FILL_LOOKBEHIND`, `bufferTopUpSlice`, `topUpBuffer` + its call in the init watcher, `bufferTopUpInFlight`, `BACKGROUND_FILL_BATCH`, `insertNextMediaItem` (+ its call in `prefetchNextTrack` — the timeline always has the next slot now; keep only `refreshControllerMediaItem`), and the now-unused helper `currentCoroutineActive()` (line ~1073)
- In `addNext` (line ~1180) and **BOTH `addToQueue` overloads** — `addToQueue(track: Track)` (~line 1203) and the batch `addToQueue(tracks: List<Track>)` (~line 1218, the one with `Semaphore` + `async`/`awaitAll`): replace the eager `resolveTrackToMediaItem` resolution with `toQueueMediaItem()` (instant adds — this re-lands the GOOD half of the reverted 4f80cc8d). `shuffleLibrary`/`growLibraryShuffle` already build via `toMediaItem()` on downloaded-only tracks — leave them as-is. Then `resolveTrackToMediaItem` + `STREAM_RESOLVE_PARALLELISM` become unused — delete both; `buildMediaItemForTrack` stays (live callers: `prefetchNextTrack`, `playTrack` ~line 1405, `playFromStreamInner` ~line 1482). Update the stale "NOT the reverted stash-lazy://" comment at the prefetch site to point at this plan.
- Update `currentQueueTracks` KDoc (it is now ALWAYS mirrored 1:1 by the timeline; `QueueDisplay` naturally converges).

**(e) `updateState` cost**: it rebuilds `timelineQueue` (a `toTrack()` per item) on EVERY player event (it's event-driven from `Player.Listener` callbacks — position ticks are a separate flow, but transitions/timeline/isPlaying events still fire constantly during playback); with a 2.6k-item timeline that's O(n) on main per event. Cache it:

```kotlin
    /** Timeline snapshot rebuilt only when the timeline actually changes —
     * updateState runs on every player event and must stay O(1) in queue size. */
    private var cachedTimelineQueue: List<Track> = emptyList()
    private var cachedTimelineVersion: Int = -1
```

In `updateState`, replace the `buildList` with:

```kotlin
        val version = controller.mediaItemCount * 31 + controller.currentTimeline.windowCount
```

— NO. Simpler and correct: bump a `@Volatile private var timelineDirty = true` flag from the existing `onTimelineChanged` listener override, and in `updateState`:

```kotlin
        if (timelineDirty || cachedTimelineQueue.size != controller.mediaItemCount) {
            cachedTimelineQueue = buildList {
                for (i in 0 until controller.mediaItemCount) add(controller.getMediaItemAt(i).toTrack())
            }
            timelineDirty = false
        }
        val timelineQueue = cachedTimelineQueue
```

(`onTimelineChanged` already exists at ~line 1724 and calls `updateState` — set `timelineDirty = true` as its first line. `replaceMediaItem` from the prefetch swap fires `onTimelineChanged`, so the quality-badge refresh still propagates.)

- [ ] **Step 4: Fix the collateral tests in the same module**
  - Delete `QueueFillWindowTest.kt`, `PlayerRepositoryEndOfTimelineRecoveryTest.kt`, `PlayerRepositoryRepeatAllWrapTest.kt`.
  - `PlayerRepositorySkipUpgradeTest.kt`: keep the two native-seek tests; delete the frontier/pendingNav test (the seam is gone). Fold what remains into `PlayerRepositoryFullTimelineTest` if that's cleaner and delete the file.
  - `PlayerRepositoryStreamingTest.kt` lines ~209-241: four `computeIsBuffering` tests touch the deleted `repo.setQueueEpoch`/`repo.tapResolveEpoch` fields. Delete the three epoch-dependent tests (`…whileTapResolveInFlight`, `…afterTapResolveCleared`, `…whenTapResolveSuperseded`) and drop the `repo.tapResolveEpoch = -1L` line from `computeIsBuffering_passesThroughControllerBuffering` (its two assertions still hold once `computeIsBuffering` = `controllerBuffering`).

- [ ] **Step 5: Run the module's repository tests**

Run: `.\gradlew.bat :core:media:testDebugUnitTest --tests "com.stash.core.media.PlayerRepositoryFullTimelineTest" --tests "com.stash.core.media.PlayerRepositorySkipUpgradeTest"`
Expected: PASS. Then the full module suite once: `.\gradlew.bat :core:media:testDebugUnitTest` (NOTE: one known flaky network test can hang in CI — see memory `infra_core_media_test_flaky_hang`; if it hangs locally, kill and rerun with `--tests` filters excluding it, don't gate on it).

- [ ] **Step 6: Commit** — `git commit -m "feat(media): full-timeline queue - every track is a MediaItem, URLs resolve just-in-time, window machinery deleted"` (includes the StashPlaybackService interception removal from (d) — they are one atomic change; splitting them cannot compile.)

---

### Task 5: Whole-app build + full verification

- [ ] **Step 1:** `.\gradlew.bat :core:media:testDebugUnitTest` — green (modulo the known flaky hang, see Task 4 Step 5).
- [ ] **Step 2:** `.\gradlew.bat :app:assembleDebug` — BUILD SUCCESSFUL (validates Hilt wiring for the new factory params).
- [ ] **Step 3:** `.\gradlew.bat :app:installDebug` — ONLY with the user's confirmation that the phone is free. **Check `adb shell "dumpsys window | grep mCurrentFocus"` immediately before EVERY input injection, and abort the moment focus is not `com.stash.app.debug`** (this device is the user's daily phone — see memory `project_queue_architecture_verdict`).
- [ ] **Step 4: On-device checklist** (Release Radar, 57 streaming tracks):
  1. Play All → track 1 starts; queue sheet shows all 57 immediately.
  2. Repeat button cycles OFF→ALL→ONE→OFF (icon updates each tap) — closes the unconfirmed regression.
  3. Repeat ALL + 15 rapid Next taps → lands 16 tracks in, audio starts within ~seconds of stopping; display never bounces back to an earlier track.
  4. 12 rapid notification/media-key Next presses → advances exactly 12.
  5. Skip to the last track, let it end under repeat ALL → wraps to track 1 natively (no spinner stall).
  6. Airplane-mode a streamed track mid-queue → cascade halt message, pause button STILL tappable (Task 1).
  7. Downloaded-tracks playlist plays with streaming OFF (placeholders never created for on-disk files).
  8. Crossfade ON: natural track end crossfades (prefetch swap gives the spare a real URL; a cold placeholder next-up just skips the fade — `isNextResolved` returns false for `stash-resolve` scheme, which is the safe degradation).
- [ ] **Step 5:** Update memories (`project_queue_architecture_verdict` → shipped state; `project_queue_repeatall_wrap_fix` → superseded note).

**Rollback:** every task is one commit on `feat/full-timeline-lazy-resolve`; the pre-migration wrap-fix state is tagged by branch `fix/repeat-all-window-wrap` (04e14206) and master is untouched.

**Explicit non-goals (YAGNI):** disk-caching lazy-resolved YouTube jumps (prefetch covers the common path; add if cold-jump data use shows up), gapless-aware pre-resolution beyond the existing single next-up prefetch, `QueueManager.kt` deletion (dead code, zero references — separate trivial cleanup), and any Now Playing UI redesign.
