package com.stash.core.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.media.streaming.StreamSourceRegistry
import com.stash.core.media.streaming.StreamUrl
import com.stash.core.media.streaming.StreamUrlCache
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [PlayerRepositoryImpl.buildMediaItemForTrack] and
 * [PlayerRepositoryImpl.playFromStream] — the streaming-routing decision
 * tree that decides between local-file playback, cached/resolved
 * streaming, and various "refused" outcomes.
 *
 * Uses Robolectric because the routing builds Media3 [androidx.media3
 * .common.MediaItem]s, which touch `android.net.Uri` and
 * `android.os.Bundle` internally. We only assert the result variant
 * type (and, for [StreamRoutingResult.Item], the mediaId and uri) —
 * downstream MediaController playback is exercised on-device.
 *
 * Test cases follow the 7-case spec from Task 11 plus a no-active-
 * network edge case to cover the early-exit on connectivity loss.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerRepositoryStreamingTest {

    private val playbackStateStore: PlaybackStateStore = mockk(relaxed = true)
    private val musicRepository: MusicRepository = mockk {
        every { trackDeletions } returns MutableSharedFlow()
    }
    private val streamingPreference: StreamingPreference = mockk()
    private val streamResolver: StreamSourceRegistry = mockk()
    private val streamUrlCache: StreamUrlCache = mockk(relaxUnitFun = true)
    private val connectivity: ConnectivityMonitor = mockk()
    private val trackDao: TrackDao = mockk()

    private lateinit var repo: PlayerRepositoryImpl

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        repo = PlayerRepositoryImpl(
            context = context,
            playbackStateStore = playbackStateStore,
            musicRepository = musicRepository,
            streamingPreference = streamingPreference,
            streamResolver = streamResolver,
            streamUrlCache = streamUrlCache,
            connectivity = connectivity,
            trackDao = trackDao,
        )
        // Tests that don't care about disk existence get a "file is there"
        // default; the not-downloaded tests can override per-test.
        repo.filePathExistsOnDisk = { true }
    }

    @Test
    fun buildMediaItem_downloadedTrack_returnsLocalFileMediaItem() = runTest {
        val track = downloaded(id = 1L, path = "/storage/music/song.flac")

        val result = repo.buildMediaItemForTrack(track)

        assertThat(result).isInstanceOf(StreamRoutingResult.Item::class.java)
        val item = (result as StreamRoutingResult.Item).mediaItem
        assertThat(item.mediaId).isEqualTo("1")
        assertThat(item.localConfiguration?.uri?.toString())
            .isEqualTo("file:///storage/music/song.flac")
    }

    @Test
    fun buildMediaItem_downloadedTrackButFileMissing_fallsThroughToStreamable() = runTest {
        // Edge case: row says downloaded=true but the file is gone (user
        // cleared external storage, e.g.). isStreamable=false here so we
        // expect NotAvailable, proving the file-existence check actually
        // matters rather than blindly trusting the column.
        // streamingPreference.current() returns false (the post-Stash-v0.9.30
        // routing checks this before the isStreamable fall-through, but the
        // path then surfaces NotAvailable for a non-streamable row).
        coEvery { streamingPreference.current() } returns false
        repo.filePathExistsOnDisk = { false }
        val track = downloaded(id = 1L, path = "/storage/music/missing.flac")
            .copy(isStreamable = false)

        val result = repo.buildMediaItemForTrack(track)

        // With streaming pref off and the file missing, the routing returns
        // OfflineMode (streaming-off check fires before isStreamable check).
        assertThat(result).isEqualTo(StreamRoutingResult.OfflineMode)
    }

    @Test
    fun buildMediaItem_streamableTrackWithCacheHit_usesCachedUrl() = runTest {
        val track = streamable(id = 2L)
        every { streamingPreference.streamOnCellular } returns flowOf(true)
        coEvery { streamingPreference.current() } returns true
        every { connectivity.isConnected() } returns true
        every { connectivity.isCellular() } returns false
        every { streamUrlCache.get(2L) } returns StreamUrl(
            url = "https://cdn.example/cached?etsp=999",
            expiresAtMs = 999_999L,
        )

        val result = repo.buildMediaItemForTrack(track)

        assertThat(result).isInstanceOf(StreamRoutingResult.Item::class.java)
        val item = (result as StreamRoutingResult.Item).mediaItem
        assertThat(item.localConfiguration?.uri?.toString())
            .isEqualTo("https://cdn.example/cached?etsp=999")
    }

    @Test
    fun buildMediaItem_streamableTrackWithCacheMiss_resolvesAndCaches() = runTest {
        val track = streamable(id = 3L)
        every { streamingPreference.streamOnCellular } returns flowOf(true)
        coEvery { streamingPreference.current() } returns true
        every { connectivity.isConnected() } returns true
        every { connectivity.isCellular() } returns false
        every { streamUrlCache.get(3L) } returns null
        coEvery { streamResolver.resolve(track) } returns StreamUrl(
            url = "https://cdn.example/fresh?etsp=1234",
            expiresAtMs = 1_234_000L,
        )

        val result = repo.buildMediaItemForTrack(track)

        assertThat(result).isInstanceOf(StreamRoutingResult.Item::class.java)
        val item = (result as StreamRoutingResult.Item).mediaItem
        assertThat(item.localConfiguration?.uri?.toString())
            .isEqualTo("https://cdn.example/fresh?etsp=1234")
        // And the resolved URL must have been persisted into the cache
        // so a subsequent tap doesn't re-resolve.
        verify {
            streamUrlCache.put(3L, match { it.url.contains("fresh?etsp=1234") })
        }
    }

    @Test
    fun buildMediaItem_unavailableTrack_returnsOfflineMode() = runTest {
        // Not downloaded AND not streamable, with streaming off — routing
        // exits early on the streaming-pref check before any isStreamable
        // logic. AvailabilityCheckWorker that drove isStreamable was
        // removed in Stash v0.9.30; Kennyy is now the sole source of truth.
        coEvery { streamingPreference.current() } returns false
        val track = TrackEntity(
            id = 4L,
            title = "x",
            artist = "y",
            isDownloaded = false,
            isStreamable = false,
        )

        val result = repo.buildMediaItemForTrack(track)

        assertThat(result).isEqualTo(StreamRoutingResult.OfflineMode)
    }

    @Test
    fun buildMediaItem_streamableTrackWithStreamingOff_returnsOfflineMode() = runTest {
        val track = streamable(id = 5L)
        coEvery { streamingPreference.current() } returns false

        val result = repo.buildMediaItemForTrack(track)

        assertThat(result).isEqualTo(StreamRoutingResult.OfflineMode)
    }

    @Test
    fun buildMediaItem_streamableButCellularWithoutCellularPref_returnsCellularRefused() = runTest {
        val track = streamable(id = 6L)
        every { streamingPreference.streamOnCellular } returns flowOf(false)
        coEvery { streamingPreference.current() } returns true
        every { connectivity.isConnected() } returns true
        every { connectivity.isCellular() } returns true

        val result = repo.buildMediaItemForTrack(track)

        assertThat(result).isEqualTo(StreamRoutingResult.CellularRefused)
    }

    @Test
    fun buildMediaItem_streamableButNoConnectivity_returnsNoConnectivity() = runTest {
        val track = streamable(id = 7L)
        coEvery { streamingPreference.current() } returns true
        every { connectivity.isConnected() } returns false

        val result = repo.buildMediaItemForTrack(track)

        assertThat(result).isEqualTo(StreamRoutingResult.NoConnectivity)
    }

    @Test
    fun buildMediaItem_streamableButResolverFails_returnsNotAvailable() = runTest {
        // Cache miss AND resolver returns null (no match in Kennyy's
        // catalog). Caller should treat as not-available rather than
        // surfacing a snackbar.
        val track = streamable(id = 8L)
        every { streamingPreference.streamOnCellular } returns flowOf(true)
        coEvery { streamingPreference.current() } returns true
        every { connectivity.isConnected() } returns true
        every { connectivity.isCellular() } returns false
        every { streamUrlCache.get(8L) } returns null
        coEvery { streamResolver.resolve(track) } returns null

        val result = repo.buildMediaItemForTrack(track)

        assertThat(result).isEqualTo(StreamRoutingResult.NotAvailable)
    }

    @Test
    fun playFromStream_searchItem_resolvesIntoStreamingMediaItem() = runTest {
        // Verifies the search-tab routing branch: TrackItem inputs are
        // converted into a transient streamable TrackEntity that runs
        // through buildMediaItemForTrack's streaming arm. We invoke
        // buildMediaItemForTrack directly with the synthesized entity
        // rather than playFromStream() — playFromStream additionally
        // connects to StashPlaybackService, which has no equivalent
        // in this Robolectric environment.
        every { streamingPreference.streamOnCellular } returns flowOf(true)
        coEvery { streamingPreference.current() } returns true
        every { connectivity.isConnected() } returns true
        every { connectivity.isCellular() } returns false
        every { streamUrlCache.get(0L) } returns null
        coEvery { streamResolver.resolve(any()) } returns StreamUrl(
            url = "https://cdn.example/searchhit?etsp=42",
            expiresAtMs = 42_000L,
        )

        val transient = TrackEntity(
            id = 0L,
            title = "Hit Song",
            artist = "Hit Artist",
            isDownloaded = false,
            isStreamable = true,
        )

        val result = repo.buildMediaItemForTrack(transient)

        assertThat(result).isInstanceOf(StreamRoutingResult.Item::class.java)
        val mediaItem = (result as StreamRoutingResult.Item).mediaItem
        assertThat(mediaItem.localConfiguration?.uri?.toString())
            .isEqualTo("https://cdn.example/searchhit?etsp=42")
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun downloaded(id: Long, path: String): TrackEntity = TrackEntity(
        id = id,
        title = "Local Song",
        artist = "Local Artist",
        album = "Local Album",
        filePath = path,
        isDownloaded = true,
        isStreamable = false,
    )

    private fun streamable(id: Long): TrackEntity = TrackEntity(
        id = id,
        title = "Streamable Song",
        artist = "Streamable Artist",
        album = "Streamable Album",
        durationMs = 200_000L,
        isDownloaded = false,
        isStreamable = true,
    )
}
