package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.download.preview.PreviewUrlExtractor
import com.stash.data.ytmusic.YTMusicApiClient
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class YouTubeStreamResolverTest {

    /**
     * Regression: structural-concurrency bug — `runCatching` inside
     * `resolve()` used to catch `CancellationException` (a Throwable)
     * and convert it to a null result, which then surfaces upstream as
     * `StreamRoutingResult.NotAvailable` → "Couldn't find this track"
     * snackbar — even when the resolve was simply preempted by a newer
     * tap. The fix rethrows CE inside the `runCatching.onFailure`.
     */
    @Test
    fun resolve_propagatesCancellationException_notSwallowAsNull() = runTest {
        val extractor: PreviewUrlExtractor = mockk()
        val ytMusic: YTMusicApiClient = mockk()
        // Mock throws CE synchronously — simulates an in-flight
        // extractStreamUrl call hitting a suspension point that
        // observes parent cancellation.
        coEvery { extractor.extractStreamUrl(any()) } throws
            CancellationException("outer cancel")
        val resolver = YouTubeStreamResolver(extractor, ytMusic)
        val track = trackWithYoutubeId("abc123")

        try {
            resolver.resolve(track)
            fail("Expected CancellationException to propagate")
        } catch (expected: CancellationException) {
            // pass — cancellation reached us, not converted to null
        }
    }

    /**
     * Same regression as above, but for the metadata-search path that
     * runs when the track has no stored `youtubeId`.
     */
    @Test
    fun searchYouTubeForVideoId_propagatesCancellationException() = runTest {
        val extractor: PreviewUrlExtractor = mockk()
        val ytMusic: YTMusicApiClient = mockk()
        coEvery { ytMusic.searchAll(any()) } throws
            CancellationException("outer cancel")
        val resolver = YouTubeStreamResolver(extractor, ytMusic)
        // Track without youtubeId — forces the search path.
        val track = trackWithoutYoutubeId(artist = "X", title = "Y")

        try {
            resolver.resolve(track)
            fail("Expected CancellationException to propagate")
        } catch (expected: CancellationException) {
            // pass
        }
    }

    /**
     * Regression lock for the existing timeout behaviour: a genuine
     * extraction stall past `YT_RESOLVE_TIMEOUT_MS` (35s) still
     * surfaces as null (and upstream as `NotAvailable`), separate
     * from a cancellation. Catches the case where the CE-rethrow
     * fix accidentally turns `withTimeoutOrNull` into `withTimeout`.
     * `runTest`'s virtual time skips the real 35-60s wait.
     */
    @Test
    fun resolve_returnsNull_onGenuineExtractionTimeout() = runTest {
        val extractor: PreviewUrlExtractor = mockk()
        val ytMusic: YTMusicApiClient = mockk()
        coEvery { extractor.extractStreamUrl(any()) } coAnswers {
            delay(60_000)
            "unreachable"
        }
        val resolver = YouTubeStreamResolver(extractor, ytMusic)
        val track = trackWithYoutubeId("abc123")

        val result = resolver.resolve(track)
        assertThat(result).isNull()
    }

    private fun trackWithYoutubeId(id: String): TrackEntity = TrackEntity(
        id = 1L,
        title = "Title",
        artist = "Artist",
        album = "Album",
        durationMs = 200_000L,
        youtubeId = id,
    )

    private fun trackWithoutYoutubeId(artist: String, title: String): TrackEntity = TrackEntity(
        id = 2L,
        title = title,
        artist = artist,
        album = "Album",
        durationMs = 200_000L,
        youtubeId = null,
    )
}
