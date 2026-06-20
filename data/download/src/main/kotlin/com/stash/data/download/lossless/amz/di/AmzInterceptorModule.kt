package com.stash.data.download.lossless.amz.di

import com.stash.data.download.lossless.amz.AmzCaptchaInterceptor
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import okhttp3.Interceptor

@Module
@InstallIn(SingletonComponent::class)
abstract class AmzInterceptorModule {
    @Binds
    @IntoSet
    abstract fun bindAmzCaptchaInterceptor(impl: AmzCaptchaInterceptor): Interceptor
}
