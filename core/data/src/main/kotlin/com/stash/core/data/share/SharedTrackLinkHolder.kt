package com.stash.core.data.share

import com.stash.core.model.share.SharedTrack
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands a shared-track link from MainActivity to the shared-track card, exactly once.
 * Survives the cold-start gap between intent parsing and ViewModel creation without
 * threading nav arguments through the typed route graph.
 */
@Singleton
class SharedTrackLinkHolder @Inject constructor() {
    private val pending = AtomicReference<SharedTrack?>(null)
    fun set(track: SharedTrack) { pending.set(track) }
    fun consume(): SharedTrack? = pending.getAndSet(null)
}
