package com.stash.core.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory holder for Cast session connectivity. Updated by
 * [com.stash.core.media.service.StashPlaybackService] via the
 * SessionManagerListener; read by [PlayerRepositoryImpl] to feed
 * [com.stash.core.model.PlayerState.isCasting].
 *
 * Singleton-scoped so both the Service (writer) and the Repository
 * (reader) see the same instance without a circular dependency.
 */
@Singleton
class CastStateHolder @Inject constructor() {

    private val _connected = MutableStateFlow(false)

    /** `true` when a Cast session is currently active. */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    fun setConnected(value: Boolean) {
        _connected.value = value
    }
}
