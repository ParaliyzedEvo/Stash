package com.stash.feature.library.share

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.db.entity.SharedMixEntity
import com.stash.core.data.share.SharePreference
import com.stash.core.data.share.ShareResult
import com.stash.core.data.share.SharedMixRepository
import com.stash.core.model.share.ShareLinks
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface ShareMixUiState {
    data object Loading : ShareMixUiState
    data class NotShared(val defaultName: String, val displayName: String?, val working: Boolean = false, val error: String? = null) : ShareMixUiState
    data class Shared(val url: String, val name: String, val canManage: Boolean, val autoUpdate: Boolean, val error: String? = null) : ShareMixUiState
}

/**
 * The share sheet (spec §5). A followed mix shares its *original* link: re-sharing a copy would
 * split it from the owner, so it isn't offered.
 */
@HiltViewModel
class ShareMixViewModel @Inject constructor(
    private val repository: SharedMixRepository,
    private val sharePreference: SharePreference,
) : ViewModel() {
    private val _state = MutableStateFlow<ShareMixUiState>(ShareMixUiState.Loading)
    val state: StateFlow<ShareMixUiState> = _state
    private var playlistId = 0L
    private var observer: Job? = null

    fun bind(playlistId: Long, playlistName: String) {
        if (this.playlistId == playlistId && observer != null) {
            // A reopened sheet starts clean, not with the last attempt's error.
            (_state.value as? ShareMixUiState.NotShared)?.let { _state.value = it.copy(error = null) }
            return
        }
        this.playlistId = playlistId
        observer?.cancel()
        observer = viewModelScope.launch {
            val display = safely("read the display name") { sharePreference.displayName() }
            try {
                repository.observe(playlistId).collect { row ->
                    _state.value = if (row == null || row.status != SharedMixEntity.STATUS_ACTIVE) {
                        ShareMixUiState.NotShared(playlistName, display)
                    } else {
                        ShareMixUiState.Shared(ShareLinks.mixUrl(row.shareId), row.name, row.role == SharedMixEntity.ROLE_OWNER, row.autoUpdate)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "observe $playlistId failed", e)
                _state.value = ShareMixUiState.NotShared(playlistName, display, error = "Couldn't load this mix's link.")
            }
        }
    }

    fun create(name: String, displayName: String?, autoUpdate: Boolean) {
        val s = _state.value as? ShareMixUiState.NotShared ?: return
        if (s.working) return
        _state.value = s.copy(working = true, error = null)
        viewModelScope.launch {
            val r = safely("create a link") {
                sharePreference.setDisplayName(displayName)
                repository.share(playlistId, name.ifBlank { s.defaultName }, displayName, autoUpdate)
            }
            // On Ok the observer flips the state to Shared.
            if (r !is ShareResult.Ok) {
                val msg = (r as? ShareResult.Failed)?.message ?: "Couldn't create the link."
                (_state.value as? ShareMixUiState.NotShared)?.let { _state.value = it.copy(working = false, error = msg) }
            }
        }
    }

    fun setAutoUpdate(on: Boolean) {
        viewModelScope.launch {
            if (safely("change auto-update") { repository.setAutoUpdate(playlistId, on) } == null) sharedError("Couldn't change that setting.")
        }
    }

    /** [onDone] runs only once the link is really down. */
    fun stopSharing(onDone: () -> Unit) {
        viewModelScope.launch {
            if (safely("stop sharing") { repository.stopSharing(playlistId) } == true) onDone()
            else sharedError("Couldn't stop sharing. Check your connection.")
        }
    }

    private fun sharedError(msg: String) {
        (_state.value as? ShareMixUiState.Shared)?.let { _state.value = it.copy(error = msg) }
    }

    /** Runs [block]; a failure (DB, IO) is logged and becomes null, never a crash. */
    private suspend fun <T> safely(action: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "$action for $playlistId failed", e)
        null
    }

    private companion object { const val TAG = "ShareMixVM" }
}
