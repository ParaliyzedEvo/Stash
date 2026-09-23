package com.stash.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.prefs.LyricsPreference
import com.stash.core.data.prefs.LyricsSourcePreference
import com.stash.data.lyrics.LyricsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Backs the "Lyrics" section on the Playback settings screen. Kept separate from [SettingsViewModel]. */
@HiltViewModel
class LyricsSettingsViewModel @Inject constructor(
    lyricsPreference: LyricsPreference,
    private val lyricsRepository: LyricsRepository,
) : ViewModel() {

    val sourcePreference: StateFlow<LyricsSourcePreference> = lyricsPreference.sourcePreference
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), LyricsSourcePreference.APPLE_MUSIC)

    /** Switching to LRC_ONLY wipes stored TTML and queues affected tracks for re-fetch — see [LyricsRepository.setSourcePreference]. */
    fun setSourcePreference(preference: LyricsSourcePreference) {
        viewModelScope.launch { lyricsRepository.setSourcePreference(preference) }
    }
}