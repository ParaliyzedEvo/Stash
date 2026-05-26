package com.stash.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
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
) : ViewModel() {

    val uiState: StateFlow<RecentlyAddedUiState> = combine(
        musicRepository.getRecentlyAdded(200),
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
            val tracks = uiState.value.tracks.filter { it.filePath != null }
            if (tracks.isEmpty()) return@launch
            val safeIndex = index.coerceIn(0, tracks.lastIndex)
            playerRepository.setQueue(tracks, safeIndex)
        }
    }

    fun playNext(track: Track) {
        viewModelScope.launch { playerRepository.addNext(track) }
    }

    fun addToQueue(track: Track) {
        viewModelScope.launch { playerRepository.addToQueue(track) }
    }
}
