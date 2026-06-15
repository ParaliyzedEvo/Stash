package com.stash.data.download.lossless.lucida.di

import com.stash.data.download.lossless.LosslessSource
import com.stash.data.download.lossless.lucida.LucidaSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class LucidaModule {

    @Binds
    @IntoSet
    abstract fun bindLucidaAsLosslessSource(impl: LucidaSource): LosslessSource
}
