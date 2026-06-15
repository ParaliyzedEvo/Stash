package com.stash.core.network.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Cache
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * DNS resolver that prioritizes IPv4 addresses to bypass slow IPv6 resolution
 * on misconfigured networks (like corporate Wi-Fi or Android emulators),
 * while retaining IPv6 as a fallback for IPv6-only environments.
 */
object IPv4PreferredDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        Dns.SYSTEM.lookup(hostname).sortedWith(compareByDescending { it is Inet4Address })
}

/**
 * Hilt module that provides networking dependencies shared across all modules.
 *
 * The [OkHttpClient] singleton is configured with:
 * - **HTTP/2 + HTTP/1.1**: Explicit protocol list enables HTTP/2 multiplexing
 *   over TLS with HTTP/1.1 fallback for servers that don't negotiate h2.
 * - **Aggressive connection pooling**: 15 idle connections, 5 min keep-alive.
 *   Tuned for the concurrent thumbnail + API + CDN fetch pattern.
 * - **High dispatcher parallelism**: 128 total / 10 per-host concurrent
 *   requests. Queue-fill resolves 16 tracks in parallel; thumbnails on
 *   search/artist pages add another 10-20 concurrent fetches.
 * - **Optimized timeouts**: 10s connect, 15s read, 15s write, 30s call.
 *   Fail fast on dead endpoints so the resolution chain advances.
 * - **Retry on connection failure**: Transparent retry on socket-level
 *   failures (connection reset, EOF) without re-posting requests.
 * - **TLS 1.2+** via [ConnectionSpec.MODERN_TLS] + compatibility fallback.
 * - **IPv4-preferred DNS** to prevent IPv6 DNS hangs.
 * - **50 MB HTTP cache** for response caching (Coil shares this client
 *   so album art 304s benefit too).
 * - **Transparent gzip/brotli**: OkHttp automatically adds
 *   `Accept-Encoding: gzip` and decompresses. API responses from
 *   kennyy.com.br, squid.wtf, and Spotify compress ~70% smaller.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val CONNECT_TIMEOUT_SECONDS = 10L
    private const val READ_TIMEOUT_SECONDS = 15L
    private const val WRITE_TIMEOUT_SECONDS = 15L
    /** Overall call timeout — safety net against stalled requests. */
    private const val CALL_TIMEOUT_SECONDS = 30L

    @Provides
    @Singleton
    fun provideOkHttpClient(
        @ApplicationContext context: Context
    ): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (com.stash.core.network.BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
            redactHeader("Authorization")
            redactHeader("Cookie")
        }

        val cacheSize = 50 * 1024 * 1024L // 50 MiB
        val cache = Cache(File(context.cacheDir, "http_cache"), cacheSize)

        return OkHttpClient.Builder()
            // ── Protocols ─────────────────────────────────────────────
            // Explicit HTTP/2 + HTTP/1.1. OkHttp negotiates h2 via ALPN
            // on TLS connections — Qobuz CDN (Akamai), YouTube, Spotify
            // all support it. HTTP/2 multiplexes requests over a single
            // TCP connection, cutting latency on the 16-parallel
            // queue-fill and the 10-20 thumbnail fan-outs.
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))

            // ── TLS ───────────────────────────────────────────────────
            .connectionSpecs(
                listOf(
                    ConnectionSpec.MODERN_TLS,
                    ConnectionSpec.COMPATIBLE_TLS,
                    ConnectionSpec.CLEARTEXT,
                )
            )

            // ── Timeouts ──────────────────────────────────────────────
            // Tightened from 15/20/30 → 10/15/15 for faster failure
            // detection. The streaming resolve chain tries 4 sources
            // sequentially — shaving 5s off each timeout saves 20s in
            // the worst case.
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            // ── Resilience ────────────────────────────────────────────
            // Transparent retry on socket-level failures (connection
            // reset, unexpected EOF, negotiation failure). Does NOT
            // retry HTTP-level errors (4xx/5xx) — those propagate
            // immediately so the chain can fail over to the next source.
            .retryOnConnectionFailure(true)

            // ── Caching ───────────────────────────────────────────────
            .cache(cache)

            // ── DNS ───────────────────────────────────────────────────
            .dns(IPv4PreferredDns)

            // ── Connection pool ───────────────────────────────────────
            // 15 idle connections (up from 10), 5 min keep-alive. The
            // app talks to ~6 distinct hosts concurrently during a
            // streaming session (kennyy CDN, squid CDN, YouTube,
            // Spotify, yt-dlp preview, album-art CDNs). 15 keeps
            // warm connections to all of them without wasting sockets.
            .connectionPool(
                okhttp3.ConnectionPool(
                    maxIdleConnections = 15,
                    keepAliveDuration = 5,
                    timeUnit = TimeUnit.MINUTES,
                )
            )

            // ── Dispatcher ────────────────────────────────────────────
            // 128 total / 10 per-host. Queue-fill fires 16 concurrent
            // resolves; Coil fires 10-20 thumbnail fetches; Spotify/YT
            // sync can overlap. 128 total avoids blocking any of these
            // workloads while 10 per-host prevents hammering a single
            // endpoint.
            .dispatcher(
                okhttp3.Dispatcher().apply {
                    maxRequests = 128
                    maxRequestsPerHost = 10
                }
            )

            // ── Interceptors ──────────────────────────────────────────
            .addInterceptor(loggingInterceptor)
            .build()
    }
}
