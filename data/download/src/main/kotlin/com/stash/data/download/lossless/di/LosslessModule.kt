package com.stash.data.download.lossless.di

import com.stash.core.data.diagnostics.DiagnosticsContributor
import com.stash.data.download.lossless.LosslessSource
import com.stash.data.download.lossless.diagnostics.LosslessDiagnosticsContributor
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds

/**
 * Hilt multibinding scaffolding for [LosslessSource] implementations.
 *
 * [Multibinds] declares an empty `Set<LosslessSource>` that's safe to
 * inject even when no sources are registered. Each concrete source —
 * Bandcamp, Internet Archive, the various aggregator clients — adds
 * itself to the set with `@Binds @IntoSet` in its own module.
 *
 * No source bindings live here on purpose: this module ships in every
 * build (free + premium, F-Droid + APK), but the per-source bindings
 * live in modules whose presence depends on build flavor / opt-in.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class LosslessModule {

    /** Empty default set so injecting `Set<LosslessSource>` always works. */
    @Multibinds
    abstract fun losslessSources(): Set<LosslessSource>

    /** The diagnostics bundle's "Lossless" section: mode, routing, relay answers, reachability. */
    @Binds
    @IntoSet
    abstract fun losslessDiagnostics(impl: LosslessDiagnosticsContributor): DiagnosticsContributor
}
