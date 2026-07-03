package com.stash.data.download.lossless.qbdlx.di

import com.stash.data.download.BuildConfig
import com.stash.data.download.lossless.LosslessSource
import com.stash.data.download.lossless.qbdlx.QbdlxPoolCipher
import com.stash.data.download.lossless.qbdlx.QbdlxPoolProvider
import com.stash.data.download.lossless.qbdlx.QbdlxQobuzSource
import com.stash.data.download.lossless.qbdlx.QbdlxSigner
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/**
 * Hilt wiring for the qbdlx direct-Qobuz lossless source.
 *
 * Binds [QbdlxQobuzSource] into the `Set<LosslessSource>` multibinding so
 * [com.stash.data.download.lossless.LosslessSourceRegistry] picks it up
 * alongside the other registered sources.
 *
 * The ONLY thing this module @Provides is the [QbdlxSigner] (it needs the
 * bundled app secret). Deliberately NOT provided here:
 *  - a bare `String` appId — that would pollute the global Hilt `String`
 *    namespace; [com.stash.data.download.lossless.qbdlx.QbdlxApiClient] reads
 *    `BuildConfig.QBDLX_APP_ID` itself.
 *  - `QbdlxApiClient` / `QbdlxCredentialStore` — both have `@Inject`
 *    constructors, so a second binding here = duplicate-binding error.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class QbdlxModule {

    @Binds
    @IntoSet
    abstract fun bindQbdlxAsLosslessSource(impl: QbdlxQobuzSource): LosslessSource

    companion object {
        @Provides
        @Singleton
        fun provideQbdlxSigner(): QbdlxSigner = QbdlxSigner(BuildConfig.QBDLX_APP_SECRET)

        @Provides
        @Singleton
        fun provideQbdlxPoolProvider(): QbdlxPoolProvider =
            QbdlxPoolProvider { QbdlxPoolCipher.decrypt(BuildConfig.QBDLX_TOKEN_POOL) }
    }
}
