package com.stash.app.di

import com.stash.app.BuildConfig
import com.stash.core.common.AppVersionProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppVersionModule {

    @Provides
    @Singleton
    fun provideAppVersionProvider(): AppVersionProvider = object : AppVersionProvider {
        override val versionName: String = BuildConfig.VERSION_NAME
        override val versionCode: Int = BuildConfig.VERSION_CODE
    }
}
