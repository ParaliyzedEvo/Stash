package com.stash.data.download.files

import com.stash.core.data.library.FileAdopter
import com.stash.core.data.library.FileExistenceSessionFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Binds [FileExistenceSessionFactory] (declared in :core:data) to the real
 * [FileOrganizer]-backed implementation. This module lives in
 * :data:download specifically because it's the only place both types
 * are visible — see [FileExistenceSessionFactory]'s doc comment.
 */
@Module
@InstallIn(SingletonComponent::class)
object FileExistenceCheckerModule {
    @Provides
    fun provideFileExistenceSessionFactory(fileOrganizer: FileOrganizer): FileExistenceSessionFactory =
        FileExistenceSessionFactory { fileOrganizer.existenceSession() }

    @Provides
    fun provideFileAdopter(adoptExistingFiles: AdoptExistingFilesUseCase): FileAdopter =
        FileAdopter { adoptExistingFiles.adopt().adopted }
}