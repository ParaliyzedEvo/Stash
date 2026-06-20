package com.stash.data.download.lossless.amz.di

import com.stash.data.download.lossless.LosslessSource
import com.stash.data.download.lossless.amz.AmzSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Hilt wiring for the Amazon Music lossless source.
 *
 * Binds [AmzSource] into the `Set<LosslessSource>` multibinding so
 * [com.stash.data.download.lossless.LosslessSourceRegistry] picks it
 * up alongside the Qobuz proxies. Ranked LAST among lossless sources
 * (see [com.stash.data.download.lossless.LosslessSourcePreferences.Companion.DEFAULT_PRIORITY]) —
 * an independent fallback when both Qobuz proxies miss, tried just
 * before the lossy YouTube fallback.
 *
 * The captcha-token interceptor for amz lives in its own module
 * ([AmzInterceptorModule]); this module only registers the source.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AmzModule {

    @Binds
    @IntoSet
    abstract fun bindAmzAsLosslessSource(impl: AmzSource): LosslessSource
}
