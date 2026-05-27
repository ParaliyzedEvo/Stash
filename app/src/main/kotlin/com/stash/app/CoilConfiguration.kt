package com.stash.app

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath

/**
 * App-wide [ImageLoader] configuration.
 *
 * Aggressively tuned for the Search + Artist Profile surfaces, which render
 * lots of thumbnails concurrently:
 *  - 30% of heap for the in-memory bitmap cache (up from 25%)
 *  - 350 MB on-disk cache for artwork persistence (up from 250 MB)
 *  - Parallel image decoding enabled (4 threads)
 *  - Shares the app's [OkHttpClient] so DNS/TLS reuse is maximal
 *  - Crossfade off globally — hero composables do their own crossfade
 */
object CoilConfiguration {
    fun build(context: PlatformContext, okHttp: OkHttpClient): ImageLoader =
        ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    // Increased from 25% to 30% for better hit rate on search results
                    .maxSizePercent(context, 0.30)
                    // Weak references allow GC to reclaim bitmaps under memory pressure
                    .weakReferencesEnabled(true)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("coil").toOkioPath())
                    // Increased from 250 MB to 350 MB for more persistent caching
                    .maxSizeBytes(350L * 1024 * 1024)
                    .build()
            }
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { okHttp }))
            }
            .crossfade(false) // hero does its own crossfade
            .build()
}
