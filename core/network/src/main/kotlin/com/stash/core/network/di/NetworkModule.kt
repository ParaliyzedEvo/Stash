package com.stash.core.network.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Hilt module that provides networking dependencies shared across all modules.
 *
 * The [OkHttpClient] singleton is configured with:
 * - Aggressive connection pooling (10 connections, 5 min keep-alive)
 * - Increased dispatcher parallelism (8 concurrent requests)
 * - Optimized timeouts (15s connect, 20s read for faster failure detection)
 * - HTTP/2 multiplexing enabled by default
 * - DNS caching and connection reuse
 * - TLS 1.2+ enforcement via [ConnectionSpec.MODERN_TLS]
 *
 * These settings are tuned for the Search + Artist Profile surfaces, which
 * render many thumbnails concurrently and benefit from connection reuse.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /** Reduced from 30s to 15s for faster failure detection on slow networks */
    private const val CONNECT_TIMEOUT_SECONDS = 15L
    
    /** Reduced from 30s to 20s to fail fast on stalled downloads */
    private const val READ_TIMEOUT_SECONDS = 20L
    
    /** Standard write timeout */
    private const val WRITE_TIMEOUT_SECONDS = 30L

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (com.stash.core.network.BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
            redactHeader("Authorization")
            redactHeader("Cookie")
        }

        return OkHttpClient.Builder()
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
            // Optimized timeouts for faster failure detection
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // Aggressive connection pooling: 10 idle connections, 5 min keep-alive.
            // Default is 5 connections / 5 min; bumping to 10 helps when loading
            // search results with many thumbnails from the same CDN.
            .connectionPool(
                okhttp3.ConnectionPool(
                    maxIdleConnections = 10,
                    keepAliveDuration = 5,
                    timeUnit = TimeUnit.MINUTES,
                )
            )
            // Increase dispatcher parallelism from default 5 to 8 concurrent requests.
            // Search results + artist profiles render 10-20 thumbnails at once;
            // higher parallelism reduces waterfall delays.
            .dispatcher(
                okhttp3.Dispatcher().apply {
                    maxRequests = 64 // Total concurrent requests across all hosts
                    maxRequestsPerHost = 8 // Per-host limit (up from default 5)
                }
            )
            .addInterceptor(loggingInterceptor)
            .build()
    }
}
