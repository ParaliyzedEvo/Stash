package com.stash.core.data.sync

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Emits a track id every time that track's `youtube_id` is REPLACED (not
 * first-set) — i.e. an existing platform identity is swapped for a
 * different one. Resync approval, wrong-match swap, and OMV→ATV
 * canonicalization all go through [emitIdentityChanged].
 *
 * Exists so `:core:media` (PlayerRepositoryImpl) can evict any
 * [com.stash.core.media.streaming.StreamUrlCache] entry keyed on the
 * OLD identity without `:core:data` call sites needing a direct
 * dependency on `:core:media` (which would be circular — StreamUrlCache
 * lives in `:core:media`, which already depends on `:core:data`).
 * Mirrors `MusicRepository.trackDeletions`, same reasoning.
 */
@Singleton
class TrackIdentityEvents @Inject constructor() {
    private val _changes = MutableSharedFlow<Long>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val changes: SharedFlow<Long> = _changes.asSharedFlow()

    fun emitIdentityChanged(trackId: Long) {
        _changes.tryEmit(trackId)
    }
}