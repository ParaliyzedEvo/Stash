package com.stash.core.media.streaming

import android.util.Log
import com.stash.core.data.prefs.StreamingPreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Previously auto-switched between Online/Offline modes based on
 * connectivity. **Disabled** because the auto-switch was unreliable:
 * it would flip to offline and fail to recover, leaving the app stuck
 * in download-only mode on subsequent cold starts.
 *
 * The player already handles missing connectivity gracefully — when
 * there's no network, streaming resolvers return null and only
 * downloaded tracks play. The explicit toggle in Settings remains for
 * users who *want* to force offline mode manually.
 *
 * [startObserving] is kept as a no-op so existing call sites in
 * [com.stash.app.StashApplication] compile without changes.
 */
@Singleton
class NetworkAwareStreamingManager @Inject constructor(
    private val connectivityMonitor: ConnectivityMonitor,
    private val streamingPreference: StreamingPreference,
) {
    private val _messages = MutableSharedFlow<String>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** One-shot messages for the UI to show as toasts / snackbars. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /**
     * No-op. Previously observed connectivity changes and auto-toggled
     * streaming mode. Kept for API compatibility.
     */
    fun startObserving(scope: CoroutineScope) {
        // Intentionally empty — auto-switch disabled.
        Log.d(TAG, "startObserving: auto-switch disabled, streaming stays as-is")
    }

    companion object {
        private const val TAG = "NetworkAwareStreaming"
    }
}

