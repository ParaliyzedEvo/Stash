package com.stash.core.data.di

import com.stash.core.data.diagnostics.DiagnosticsContributor
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/**
 * Hilt multibinding scaffolding for [DiagnosticsContributor]s, mirroring
 * `LosslessModule`: the empty default set makes `Set<DiagnosticsContributor>`
 * injectable even in a build where no other module contributes a section.
 * Contributors bind themselves with `@Binds @IntoSet` in their own module.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DiagnosticsModule {
    @Multibinds
    abstract fun diagnosticsContributors(): Set<DiagnosticsContributor>
}
