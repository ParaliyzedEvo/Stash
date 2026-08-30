package com.stash.data.download.lossless.qbdlx.di

import com.stash.core.data.discography.DiscographySupplement
import com.stash.core.data.discography.QobuzAlbumFetcher
import com.stash.core.data.discovery.HomeDiscoveryRepository
import com.stash.data.download.lossless.qbdlx.HomeDiscoveryRepositoryImpl
import com.stash.data.download.BuildConfig
import com.stash.data.download.lossless.LosslessSource
import com.stash.data.download.lossless.qbdlx.HttpQbdlxRemotePool
import com.stash.data.download.lossless.qbdlx.QbdlxPoolCipher
import com.stash.data.download.lossless.qbdlx.QbdlxPoolProvider
import com.stash.data.download.lossless.qbdlx.QbdlxCredentialStore
import com.stash.data.download.lossless.qbdlx.QbdlxRemotePool
import com.stash.data.download.lossless.qbdlx.QbdlxQobuzSource
import com.stash.data.download.lossless.qbdlx.QbdlxSigner
import com.stash.data.download.lossless.qbdlx.QbdlxSigningResolver
import com.stash.data.download.lossless.qbdlx.QobuzAlbumFetcherImpl
import com.stash.data.download.lossless.qbdlx.QobuzDiscographyProvider
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
 * The ONLY thing this module @Provides is the [QbdlxSigner] (it needs the
 * bundled app secret). Deliberately NOT provided here:
 *  - a bare `String` appId — that would pollute the global Hilt `String`
 *    namespace; catalog calls run tokenless under the web player's own id
 *    ([com.stash.data.download.lossless.qbdlx.QbdlxApiClient.catalogAppId]) and
 *    [QbdlxCredentialStore] reads `BuildConfig.QBDLX_APP_ID` itself for signing.
 *  - `QbdlxApiClient` / `QbdlxCredentialStore` — both have `@Inject`
 *    constructors, so a second binding here = duplicate-binding error.
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
     * The runtime pool source. This is the "broker-ready" seam the original
     * design left open: swapping in a Worker-backed broker later means changing
     * this binding and nothing else.
     */
    @Binds
    @Singleton
    abstract fun bindQbdlxRemotePool(impl: HttpQbdlxRemotePool): QbdlxRemotePool

    /**
     * The credential store IS the signing authority — it knows each token's
     * app_id (pool tag / connected account) and the matching secret.
     */
    @Binds
    @Singleton
    abstract fun bindQbdlxSigningResolver(impl: QbdlxCredentialStore): QbdlxSigningResolver

    companion object {
        // Stateless now: the app_secret is chosen PER REQUEST by the resolver,
        // because the pool spans multiple app_ids (each with its own secret) and a
        // connected account carries its own — a single bundled secret would sign
        // mismatched tokens wrong and silently serve previews.
        @Provides
        @Singleton
        fun provideQbdlxSigner(): QbdlxSigner = QbdlxSigner()

        @Provides
        @Singleton
        fun provideQbdlxPoolProvider(): QbdlxPoolProvider = QbdlxPoolProvider {
            val pool = QbdlxPoolCipher.decrypt(BuildConfig.QBDLX_TOKEN_POOL)
            val fp = BuildConfig.QBDLX_POOL_FP
            if (fp.isNotBlank()) {
                val actual = if (pool.isBlank()) "" else
                    java.security.MessageDigest.getInstance("SHA-256")
                        .digest(pool.toByteArray())
                        .joinToString("") { "%02x".format(it.toInt() and 0xFF) }.take(8)
                if (actual != fp) {
                    android.util.Log.w("QbdlxPool", "pool fp mismatch — embed/runtime crypto drift?")
                }
            }
            pool
        }
    }
}
