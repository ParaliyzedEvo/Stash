package com.stash.core.media.streaming

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.stash.core.data.db.dao.TrackDao
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Composed [MediaSource.Factory] for online-streaming playback. Builds the
 * full read pipeline ExoPlayer consumes when sourcing bytes for a streamed
 * track:
 *
 * ```
 * CacheDataSource           <- writes hits into the shared [SimpleCache]
 *  └─ RefreshingDataSource  <- catches 403/410, re-resolves via Kennyy
 *      └─ OkHttpDataSource  <- actual HTTP GET against the signed CDN URL
 * ```
 *
 * **OkHttpDataSource** (v0.10.0) replaces the earlier `DefaultHttpDataSource`
 * so streaming audio shares the app-wide [OkHttpClient]:
 *  - **HTTP/2 multiplexing**: CDN connections (Akamai, YouTube) negotiate
 *    h2 over the same TCP socket used by metadata resolves, so there's no
 *    extra TLS handshake for the audio fetch — first byte arrives faster.
 *  - **Connection pool reuse**: The shared pool (15 idle / 5 min) means a
 *    warm connection to the CDN is often already established by the time
 *    the player starts reading bytes, cutting ~100-300ms of TCP+TLS setup.
 *  - **IPv4-preferred DNS / retry / timeouts**: All tuning from
 *    [com.stash.core.network.di.NetworkModule] applies automatically.
 *
 * The cache layer is on top so a partial download survives a 403 refresh
 * mid-stream — bytes already cached before the URL went stale aren't re-
 * fetched, and the refresh only kicks in for the still-missing tail.
 *
 * **Per-track factory via singleton + method.** The static deps
 * ([streamCache], [resolver], [urlCache], [trackDao]) are Hilt-injected
 * application-scope singletons; the only per-playback variable is
 * [create]'s `trackId`. Each call to [create] returns a fresh
 * [MediaSource.Factory] that closes over that id so Media3 can spawn
 * multiple [androidx.media3.datasource.DataSource] instances for the
 * same track and they'll all share the refresh policy.
 *
 * **CacheDataSource flags:**
 * - [CacheDataSource.FLAG_BLOCK_ON_CACHE]: if two readers ask for the same
 *   byte range concurrently, the second blocks until the first writes —
 *   avoids duplicate HTTP fetches for the same range.
 * - [CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR]: if cache I/O throws,
 *   fall through to the network rather than surfacing the cache error as
 *   a playback failure.
 */
@OptIn(UnstableApi::class)
@Singleton
class StreamingMediaSourceFactory @Inject constructor(
    @StreamCache private val streamCache: SimpleCache,
    private val resolver: StreamSourceRegistry,
    private val urlCache: StreamUrlCache,
    private val trackDao: TrackDao,
    private val okHttpClient: OkHttpClient,
) {
    fun create(trackId: Long): MediaSource.Factory {
        val httpFactory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent("Stash/0.9.26")
        val refreshFactory = RefreshingDataSourceFactory(
            innerFactory = httpFactory,
            resolver = resolver,
            cache = urlCache,
            trackDao = trackDao,
            trackId = trackId,
        )
        val cachedFactory = CacheDataSource.Factory()
            .setCache(streamCache)
            .setUpstreamDataSourceFactory(refreshFactory)
            .setCacheWriteDataSinkFactory(
                CacheDataSink.Factory().setCache(streamCache),
            )
            .setFlags(
                CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR or
                    CacheDataSource.FLAG_BLOCK_ON_CACHE,
            )
        return DefaultMediaSourceFactory(cachedFactory)
    }
}
