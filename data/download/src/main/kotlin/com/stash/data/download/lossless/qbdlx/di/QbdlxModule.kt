package com.stash.data.download.lossless.qbdlx.di

import com.stash.core.data.discography.DiscographySupplement
import com.stash.core.data.discography.QobuzAlbumFetcher
import com.stash.core.data.discovery.HomeDiscoveryRepository
import com.stash.data.download.lossless.qbdlx.HomeDiscoveryRepositoryImpl
import com.stash.data.download.lossless.LosslessSource
import com.stash.data.download.lossless.qbdlx.QbdlxCredentialStore
import com.stash.data.download.lossless.qbdlx.QbdlxQobuzSource
import com.stash.data.download.lossless.qbdlx.QbdlxSigner
import com.stash.data.download.lossless.qbdlx.QbdlxSigningResolver
import com.stash.data.download.lossless.qbdlx.QobuzAlbumFetcherImpl
import com.stash.data.download.lossless.qbdlx.QobuzDiscographyProvider
import com.stash.data.download.lossless.qbdlx.QobuzWebCredentials
import com.stash.data.download.lossless.qbdlx.QobuzWebCredentialsClient
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
 * alongside the other registered sources. The binding is UNCONDITIONAL — the
 * source is always in the registry, and
 * [com.stash.data.download.lossless.LosslessAvailability] decides at resolve
 * time whether there is a credential or relay behind it. Nothing here is gated
 * on a build flag.
 *
 * This module @Provides exactly two things: the stateless [QbdlxSigner], and
 * [QobuzWebCredentials] — the seam over the live Qobuz web-creds scrape, which
 * needs a @Provides because [QobuzWebCredentialsClient] does not implement the
 * interface. No app_id or app_secret is bundled or provided anywhere:
 * catalog calls run tokenless under the web player's own id
 * ([com.stash.data.download.lossless.qbdlx.QbdlxApiClient.catalogAppId]), and a
 * connected account carries the pair it was minted under. Deliberately NOT
 * provided here: `QbdlxApiClient` / [QbdlxCredentialStore] — both have
 * `@Inject` constructors, so a second binding here = duplicate-binding error.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class QbdlxModule {

    @Binds
    @IntoSet
    abstract fun bindQbdlxAsLosslessSource(impl: QbdlxQobuzSource): LosslessSource

    @Binds
    abstract fun bindDiscographySupplement(impl: QobuzDiscographyProvider): DiscographySupplement

    @Binds
    abstract fun bindQobuzAlbumFetcher(impl: QobuzAlbumFetcherImpl): QobuzAlbumFetcher

    @Binds
    @Singleton
    abstract fun bindHomeDiscoveryRepository(impl: HomeDiscoveryRepositoryImpl): HomeDiscoveryRepository

    /**
     * The credential store IS the signing authority — the connected account's
     * app_id and matching secret are stored with its token.
     */
    @Binds
    @Singleton
    abstract fun bindQbdlxSigningResolver(impl: QbdlxCredentialStore): QbdlxSigningResolver

    companion object {
        // Stateless: the app_secret is chosen PER REQUEST by the resolver, because
        // it belongs to the connected account, not to the app — a bundled secret
        // would sign a mismatched token wrong and silently serve previews.
        @Provides
        @Singleton
        fun provideQbdlxSigner(): QbdlxSigner = QbdlxSigner()

        /**
         * The live Qobuz web-creds scrape, as the narrow seam
         * [QbdlxCredentialStore] depends on. @Provides, not @Binds:
         * [QobuzWebCredentialsClient] does not implement [QobuzWebCredentials].
         */
        @Provides
        @Singleton
        fun provideQobuzWebCredentials(c: QobuzWebCredentialsClient): QobuzWebCredentials =
            QobuzWebCredentials(c::fetch)
    }
}
