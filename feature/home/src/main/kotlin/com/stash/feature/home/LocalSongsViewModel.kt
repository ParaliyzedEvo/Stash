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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LocalSongsUiState(
    val tracks: List<Track> = emptyList(),
    val isLoading: Boolean = true,
    val currentlyPlayingTrackId: Long? = null,
)

@HiltViewModel
class LocalSongsViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
) : ViewModel() {

    val uiState: StateFlow<LocalSongsUiState> = combine(
        musicRepository.getAllTracks().map { tracks -> tracks.filter { it.isDownloaded } },
        playerRepository.playerState,
    ) { tracks, playerState ->
        LocalSongsUiState(
            tracks = tracks,
            isLoading = false,
            currentlyPlayingTrackId = playerState.currentTrack?.id,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LocalSongsUiState(),
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
