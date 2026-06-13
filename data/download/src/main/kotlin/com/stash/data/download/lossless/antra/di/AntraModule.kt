package com.stash.data.download.lossless.antra.di

import com.stash.data.download.lossless.LosslessSource
import com.stash.data.download.lossless.antra.AntraSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Hilt wiring for the antra.hoshi.cfd lossless source.
 *
 * Binds [AntraSource] into the `Set<LosslessSource>` multibinding so
 * [com.stash.data.download.lossless.LosslessSourceRegistry] picks it up
 * alongside the Qobuz proxies. The registry orders sources by
 * `LosslessSourcePreferences.priorityOrder` (which now lists `"antra"`
 * last) and appends any unranked source in registration order — so antra
 * naturally falls last, engaging only when squid/kennyy miss.
 *
 * No `@Provides` for [AntraSource], [AntraClient], [AntraCredentialStore],
 * or [AntraCookieInterceptor] is needed: each is a constructor-injected
 * `@Singleton` whose dependencies (the shared `OkHttpClient`,
 * `AggregatorRateLimiter`, `LosslessSourcePreferences`) are already on the
 * graph. The cookie interceptor is composed into the antra OkHttp client
 * inside `AntraClient`'s constructor (mirroring how `QobuzApiClient` builds
 * its captcha-interceptor client), so there's nothing extra to provide.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AntraModule {

    @Binds
    @IntoSet
    abstract fun bindAntraAsLosslessSource(impl: AntraSource): LosslessSource
}
