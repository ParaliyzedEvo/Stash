package com.stash.core.media.streaming

import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamSourceRegistryTest {

    private val kennyy: KennyyStreamResolver = mockk()
    private val qobuz: QobuzStreamResolver = mockk()
    private val antra: AntraStreamResolver = mockk()
    private val lucida: LucidaStreamResolver = mockk()
    private val saavn: SaavnStreamResolver = mockk()
    private val youtube: YouTubeStreamResolver = mockk()
    private val streamingPreference: StreamingPreference = mockk()

    private fun registry(): StreamSourceRegistry {
        coEvery { lucida.resolve(any()) } returns null
        coEvery { saavn.resolve(any()) } returns null
        return StreamSourceRegistry(kennyy, qobuz, antra, lucida, saavn, youtube, streamingPreference)
    }


    /**
     * The forceAntraOnly test toggle is the outage drill for the antra
     * fallback: kennyy, squid AND youtube are all removed from play, so a
     * track either streams via antra or not at all.
     */
    @Test
    fun resolve_forceAntraOnly_routes_through_antra_only() = runTest {
        coEvery { streamingPreference.isForceAntraOnly() } returns true
        // kennyy/qobuz/youtube must never be consulted — intentionally unstubbed.
        coEvery { antra.resolve(any()) } returns antraStreamUrl()
        val track = stubTrack()

        val result = registry().resolve(track, allowYouTube = true)

        assertThat(result?.origin).isEqualTo("antra")
        coVerify(exactly = 0) { youtube.resolve(any(), any()) }
    }

    /**
     * Under forceAntraOnly an antra miss must NOT fall through to youtube —
     * the drill exists to prove antra alone can serve, so a miss surfaces
     * as "no stream" rather than being papered over.
     */
    @Test
    fun resolve_forceAntraOnly_returns_null_when_antra_misses() = runTest {
        coEvery { streamingPreference.isForceAntraOnly() } returns true
        coEvery { antra.resolve(any()) } returns null
        val track = stubTrack()

        val result = registry().resolve(track, allowYouTube = true)

        assertThat(result).isNull()
        coVerify(exactly = 0) { youtube.resolve(any(), any()) }
    }

    /**
     * The background-fill path passes `allowYtDlp = false` so the YouTube
     * fallback resolves via the fast InnerTube engine only. Verify the flag
     * is forwarded to [YouTubeStreamResolver.resolve].
     */
    @Test
    fun resolve_passes_allowYtDlp_to_youtube_resolver() = runTest {
        coEvery { streamingPreference.isForceYouTubeFallback() } returns false
        coEvery { kennyy.resolve(any()) } returns null
        coEvery { qobuz.resolve(any()) } returns null
        coEvery { youtube.resolve(any(), any()) } returns null
        val track = stubTrack()

        registry().resolve(track, allowYouTube = true, allowYtDlp = false)

        coVerify { youtube.resolve(track, allowYtDlp = false) }
    }

    /**
     * Foreground (user-tap) callers leave `allowYtDlp` at its default of
     * `true`, so the slower yt-dlp path stays available.
     */
    @Test
    fun resolve_defaults_allowYtDlp_true() = runTest {
        coEvery { streamingPreference.isForceYouTubeFallback() } returns false
        coEvery { kennyy.resolve(any()) } returns null
        coEvery { qobuz.resolve(any()) } returns null
        coEvery { youtube.resolve(any(), any()) } returns null
        val track = stubTrack()

        registry().resolve(track, allowYouTube = true)

        coVerify { youtube.resolve(track, allowYtDlp = true) }
    }

    /**
     * Both Qobuz proxies miss → the registry falls through to youtube.
     */
    @Test
    fun resolve_falls_to_youtube_when_qobuz_misses() = runTest {
        coEvery { streamingPreference.isForceYouTubeFallback() } returns false
        coEvery { kennyy.resolve(any()) } returns null
        coEvery { qobuz.resolve(any()) } returns null
        coEvery { youtube.resolve(any(), any()) } returns null
        val track = stubTrack()

        registry().resolve(track, allowYouTube = true)

        coVerify { kennyy.resolve(track) }
        coVerify { qobuz.resolve(track) }
        coVerify { youtube.resolve(track, allowYtDlp = true) }
    }

    /**
     * The forceYt test toggle skips kennyy/qobuz entirely and routes through
     * the YouTube resolver only. Verify that branch still forwards
     * `allowYtDlp` to [YouTubeStreamResolver.resolve].
     */
    @Test
    fun resolve_forceYt_branch_passes_allowYtDlp_to_youtube() = runTest {
        coEvery { streamingPreference.isForceYouTubeFallback() } returns true
        // kennyy/qobuz are skipped in the forceYt branch — intentionally unstubbed.
        coEvery { youtube.resolve(any(), any()) } returns null
        val track = stubTrack()

        registry().resolve(track, allowYouTube = true, allowYtDlp = false)

        coVerify { youtube.resolve(track, allowYtDlp = false) }
    }

    private fun stubTrack(): TrackEntity = TrackEntity(
        id = 1L,
        title = "Title",
        artist = "Artist",
        album = "Album",
        durationMs = 200_000L,
        youtubeId = "abc123",
    )
}
