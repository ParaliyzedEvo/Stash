package com.stash.core.media.streaming

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Routing tests for [StashMediaSourceFactory] — the player-wide
 * [MediaSource.Factory] that fans items out to three sub-factories by predicate:
 *
 * 1. YouTube refresh chain ([streamingTrackId] returns an id),
 * 2. JioSaavn authed-HTTP OkHttpDataSource ([isJioSaavnOrigin] true), and
 * 3. local/default (both false).
 *
 * The factory is a plain class (not Hilt), so routing is exercised directly via
 * fake predicate lambdas. Uses [RobolectricTestRunner] because
 * `DefaultMediaSourceFactory` / `OkHttpDataSource.Factory` construction touches
 * Android framework stubs (`android.net.Uri`) that throw on bare JVM.
 */
@RunWith(RobolectricTestRunner::class)
class StashMediaSourceFactoryTest {

    private val streamingFactory: StreamingMediaSourceFactory = mockk(relaxed = true)

    private fun item(uri: String): MediaItem = MediaItem.fromUri(uri)

    private fun newFactory(
        streamingTrackId: (MediaItem) -> Long?,
        isJioSaavnOrigin: (MediaItem) -> Boolean = { false },
    ): StashMediaSourceFactory = StashMediaSourceFactory(
        context = ApplicationProvider.getApplicationContext(),
        streamingFactory = streamingFactory,
        streamingTrackId = streamingTrackId,
        isJioSaavnOrigin = isJioSaavnOrigin,
        httpClient = OkHttpClient(),
        resolver = mockk(relaxed = true),
        urlCache = mockk(relaxed = true),
        trackDao = mockk(relaxed = true),
    )

    @Test
    fun placeholderItem_routesToLazyResolvingChain() {
        val lazy = item("stash-resolve://track/42")
        val factory = newFactory(streamingTrackId = { null })

        val source: MediaSource = factory.createMediaSource(lazy)

        // Placeholder must not enter the eager YouTube chain, and must produce a
        // progressive source backed by the lazy factory.
        verify(exactly = 0) { streamingFactory.create(any()) }
        assertThat(source).isInstanceOf(ProgressiveMediaSource::class.java)
    }

    @Test
    fun jioSaavnItem_routesToDedicatedProgressiveSource_notStreamingChain() {
        val jio = item("https://aac.saavncdn.com/song_320.mp4")
        val factory = newFactory(
            streamingTrackId = { null },
            isJioSaavnOrigin = { it === jio },
        )

        val source = factory.createMediaSource(jio)

        verify(exactly = 0) { streamingFactory.create(any()) }
        assertThat(source).isInstanceOf(ProgressiveMediaSource::class.java)
        assertThat(factory.jioSaavnHttpClient.followRedirects).isFalse()
        assertThat(factory.jioSaavnHttpClient.followSslRedirects).isFalse()
    }

    @Test
    fun youtubeTrackIdItem_routesToStreamingChain() {
        val yt = item("https://youtube.example/v.m4a")
        val innerFactory: MediaSource.Factory = mockk(relaxed = true)
        every { streamingFactory.create(99L) } returns innerFactory

        val factory = newFactory(
            streamingTrackId = { if (it === yt) 99L else null },
        )

        factory.createMediaSource(yt)

        verify(exactly = 1) { streamingFactory.create(99L) }
        verify(exactly = 1) { innerFactory.createMediaSource(yt) }
    }

    @Test
    fun youtubeTrackIdTakesPrecedence_overJioSaavn() {
        // trackId branch is checked first; if it matches, JioSaavn must not be consulted.
        val both = item("https://example/x")
        val innerFactory: MediaSource.Factory = mockk(relaxed = true)
        every { streamingFactory.create(7L) } returns innerFactory
        var jioConsulted = false

        val factory = newFactory(
            streamingTrackId = { 7L },
            isJioSaavnOrigin = { jioConsulted = true; true },
        )

        factory.createMediaSource(both)

        verify(exactly = 1) { streamingFactory.create(7L) }
        assertThat(jioConsulted).isFalse()
    }

    @Test
    fun localItem_routesToNeitherStreamingNorJioSaavn() {
        val local = item("file:///music/song.flac")
        val factory = newFactory(
            streamingTrackId = { null },
        )

        val source: MediaSource = factory.createMediaSource(local)

        verify(exactly = 0) { streamingFactory.create(any()) }
        // Local files also resolve to a progressive source via the default factory;
        // the meaningful assert is that it did not enter the streaming chain above.
        assertThat(source).isNotNull()
    }
}
