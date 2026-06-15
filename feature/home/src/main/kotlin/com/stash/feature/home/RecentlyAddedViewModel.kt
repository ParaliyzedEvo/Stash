package com.stash.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RecentlyAddedUiState(
    val tracks: List<Track> = emptyList(),
    val isLoading: Boolean = true,
    val currentlyPlayingTrackId: Long? = null,
)

@HiltViewModel
class RecentlyAddedViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
    private val streamingPreference: com.stash.core.data.prefs.StreamingPreference,
) : ViewModel() {

    val uiState: StateFlow<RecentlyAddedUiState> = combine(
        musicRepository.getRecentlyAdded(limit = 200), // Get more tracks for full listing
        playerRepository.playerState,
    ) { tracks, playerState ->
        RecentlyAddedUiState(
            tracks = tracks,
            isLoading = false,
            currentlyPlayingTrackId = playerState.currentTrack?.id,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = RecentlyAddedUiState(),
    )

    fun playTrack(index: Int) {
        viewModelScope.launch {
            val streamingOn = streamingPreference.current()
            val playable = if (streamingOn) {
                uiState.value.tracks
            } else {
                uiState.value.tracks.filter { it.filePath != null }
            }
            if (playable.isEmpty()) return@launch
            val adjustedIndex = index.coerceIn(0, playable.lastIndex)
            playerRepository.setQueue(playable, adjustedIndex)
        }
    }

    fun playNext(track: Track) {
        viewModelScope.launch { playerRepository.addNext(track) }
    }

    fun addToQueue(track: Track) {
        viewModelScope.launch { playerRepository.addToQueue(track) }
    }
}
