package com.stash.core.data.tipjar.di

import com.stash.core.data.tipjar.SupportersJsonUrl
import com.stash.core.data.tipjar.TipJarRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * v0.9.13: Hilt provider for the supporters JSON URL.
 *
 * The URL is sourced from `BuildConfig.SUPPORTERS_JSON_URL` (defined
 * in `app/build.gradle.kts`). Keeping it as a build-time field
 * means a different Stash fork can repoint at their own JSON host
 * without touching Kotlin source. [TipJarRepository] is constructor-
 * injected with the [SupportersJsonUrl]-qualified String.
 *
 * The URL is read reflectively via `Class.forName("com.stash.app.BuildConfig")`
 * to keep this module decoupled from the `:app` module — `:core:data`
 * depends on neither `:app` nor any feature module, so a direct
 * import would be a layering violation.
 */
@Module
@InstallIn(SingletonComponent::class)
object TipJarModule {

    @Provides
    @Singleton
    @SupportersJsonUrl
    fun provideSupportersJsonUrl(): String {
        // Reflective access keeps :core:data layering clean. No hardcoded
        // fallback URL: BuildConfig is always present in a real build, and the
        // old fallback only published a stale repo name. Empty means "no
        // supporters host" — TipJarRepository.refresh() no-ops on it and the
        // pill keeps its bundled list.
        return runCatching {
            val cls = Class.forName("com.stash.app.BuildConfig")
            cls.getField("SUPPORTERS_JSON_URL").get(null) as String
        }.getOrDefault("")
    }
}
