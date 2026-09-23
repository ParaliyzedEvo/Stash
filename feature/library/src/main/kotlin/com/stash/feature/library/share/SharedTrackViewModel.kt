package com.stash.feature.library.share

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.social.LikeCoordinator
import com.stash.core.media.PlayerRepository
import com.stash.core.model.share.ShareLinks
import com.stash.core.model.share.SharedTrack
import com.stash.core.model.share.toTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The card an incoming shared-track link opens (spec §6). */
@HiltViewModel
class SharedTrackViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
    private val likeCoordinator: LikeCoordinator,
) : ViewModel() {
    /** Parsed from the route's link, so the card survives process death. */
    val track: SharedTrack? = (ShareLinks.parse(savedStateHandle.get<String>("link")) as? ShareLinks.Parsed.Track)?.track
    private val _liked = MutableStateFlow(false)
    val liked: StateFlow<Boolean> = _liked
    private val _message = MutableStateFlow<String?>(null)
    /** A short error from the last action, shown on the card. */
    val message: StateFlow<String?> = _message

    private suspend fun persisted(): Long? = track?.let { musicRepository.ensureTrackPersisted(it.toTrack()) }

    fun play() = safely("play") {
        val id = persisted() ?: return@safely
        val t = musicRepository.observeTrackById(id).first() ?: return@safely
        _liked.value = t.stashLikedAt != null
        playerRepository.setQueue(listOf(t), 0)
    }

    fun like() = safely("add") {
        val id = persisted() ?: return@safely
        likeCoordinator.setLiked(id, true)
        _liked.value = musicRepository.observeTrackById(id).first()?.stashLikedAt != null
    }

    private var busy = false

    /** A failure (DB, IO) becomes a message, never a crash. [busy] drops a double tap. */
    private fun safely(action: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        _message.value = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("SharedTrackVM", "$action failed", e)
                _message.value = "Couldn't $action this song. Try again."
            } finally {
                busy = false
            }
        }
    }
}
