package com.stash.core.media.streaming

import androidx.media3.exoplayer.source.MediaSource
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TrackDao
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [StreamingMediaSourceFactory] — the composed `DataSource.Factory`
 * (cache wraps refresh wraps http) that ExoPlayer consumes for streamed
 * playback.
 *
 * Verification surface is intentionally narrow: most behaviour
 * (cache hit/miss, 403 refresh, byte-offset preservation) is exercised in
 * the lower-level [RefreshingDataSourceFactoryTest] and the Task 23 manual
 * validation. Here we only assert that [StreamingMediaSourceFactory.create]
 * runs to completion without NPE-ing in Media3's builders and produces a
 * distinct [MediaSource.Factory] instance per call (i.e. the `trackId` arg
 * is captured per-invocation, not memoised).
 *
 * Uses [RobolectricTestRunner] because `CacheDataSource.Factory` /
 * `DefaultHttpDataSource.Factory` construction touches Android framework
 * stubs (e.g. `android.net.Uri` defaults) that throw without a Robolectric
 * environment.
 */
@RunWith(RobolectricTestRunner::class)
class StreamingMediaSourceFactoryTest {

    private val streamCache: androidx.media3.datasource.cache.SimpleCache = mockk(relaxed = true)
    private val resolver: StreamSourceRegistry = mockk(relaxed = true)
    private val urlCache: StreamUrlCache = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk(relaxed = true)
    private val okHttpClient: okhttp3.OkHttpClient = okhttp3.OkHttpClient()

    private fun newFactory(): StreamingMediaSourceFactory = StreamingMediaSourceFactory(
        streamCache = streamCache,
        resolver = resolver,
        urlCache = urlCache,
        trackDao = trackDao,
        okHttpClient = okHttpClient,
    )

    @Test
    fun create_returnsNonNullMediaSourceFactory() {
        val factory = newFactory()

        val mediaSourceFactory: MediaSource.Factory = factory.create(trackId = 42L)

        assertThat(mediaSourceFactory).isNotNull()
    }

    @Test
    fun create_returnsDistinctFactoryPerCall() {
        // Per-track factories must be independent so two simultaneous
        // playbacks of different tracks don't share refresh state.
        val factory = newFactory()

        val a = factory.create(trackId = 1L)
        val b = factory.create(trackId = 2L)

        assertThat(a).isNotSameInstanceAs(b)
    }
}
