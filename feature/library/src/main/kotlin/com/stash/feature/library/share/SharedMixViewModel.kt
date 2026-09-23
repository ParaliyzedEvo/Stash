package com.stash.feature.library.share

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.db.entity.SharedMixEntity
import com.stash.core.data.share.ShareResult
import com.stash.core.data.share.SharedMixDocument
import com.stash.core.data.share.SharedMixRepository
import com.stash.core.media.PlayerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface SharedMixUiState {
    data object Loading : SharedMixUiState
    data class Error(val message: String, val retryable: Boolean) : SharedMixUiState
    data class Loaded(
        val doc: SharedMixDocument,
        val followedPlaylistId: Long?,
        val isOwnMix: Boolean,
        val busy: Boolean = false,
        /** A short error from the last action (Play, Follow…), shown under the buttons. */
        val message: String? = null,
    ) : SharedMixUiState
}

/** The screen an incoming shared-mix link opens (spec §6). */
@HiltViewModel
class SharedMixViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: SharedMixRepository,
    private val playerRepository: PlayerRepository,
) : ViewModel() {
    private val shareId: String = checkNotNull(savedStateHandle.get<String>("shareId"))
    private val _state = MutableStateFlow<SharedMixUiState>(SharedMixUiState.Loading)
    val state: StateFlow<SharedMixUiState> = _state

    init { load() }

    fun load() {
        _state.value = SharedMixUiState.Loading
        viewModelScope.launch {
            _state.value = try {
                when (val r = repository.fetch(shareId)) {
                    is ShareResult.Ok -> if (r.value.v > 1) SharedMixUiState.Error("Update Stash to open this mix.", false) else {
                        val row = repository.byShareId(shareId)
                        SharedMixUiState.Loaded(
                            doc = r.value,
                            followedPlaylistId = row?.takeIf { it.role == SharedMixEntity.ROLE_FOLLOWER }?.playlistId,
                            isOwnMix = row?.role == SharedMixEntity.ROLE_OWNER,
                        )
                    }
                    ShareResult.Gone -> SharedMixUiState.Error("This mix is no longer shared.", false)
                    ShareResult.NotFound -> SharedMixUiState.Error("This link doesn't point to a mix.", false)
                    else -> SharedMixUiState.Error(RETRY_MESSAGE, true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "load $shareId failed", e)
                SharedMixUiState.Error(RETRY_MESSAGE, true)
            }
        }
    }

    /** Runs one action on the loaded mix; a failure (DB, IO) becomes a message, never a crash. */
    private fun withLoaded(action: String, block: suspend (SharedMixUiState.Loaded) -> Unit) {
        val s = _state.value as? SharedMixUiState.Loaded ?: return
        if (s.busy) return
        _state.value = s.copy(busy = true, message = null)
        viewModelScope.launch {
            try {
                block(s.copy(message = null))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "$action $shareId failed", e)
                (_state.value as? SharedMixUiState.Loaded)?.let { _state.value = it.copy(message = "Couldn't $action this mix. Try again.") }
            } finally {
                (_state.value as? SharedMixUiState.Loaded)?.let { _state.value = it.copy(busy = false) }
            }
        }
    }

    fun play() = withLoaded("play") { s ->
        val tracks = repository.tracksFor(s.doc)
        if (tracks.isNotEmpty()) playerRepository.setQueue(tracks, 0)
    }

    fun follow(onFollowed: (Long) -> Unit) = withLoaded("follow") { s ->
        val id = repository.follow(s.doc)
        _state.value = s.copy(followedPlaylistId = id)
        onFollowed(id)
    }

    fun saveCopy(onSaved: (Long) -> Unit) = withLoaded("save") { s -> onSaved(repository.saveCopy(s.doc)) }

    fun unfollow() = withLoaded("unfollow") { s ->
        s.followedPlaylistId?.let { repository.unfollow(it) }
        _state.value = s.copy(followedPlaylistId = null)
    }

    private companion object {
        const val TAG = "SharedMixVM"
        const val RETRY_MESSAGE = "Couldn't load this mix. Check your connection."
    }
}
