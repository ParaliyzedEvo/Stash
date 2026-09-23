package com.stash.feature.settings.libraryhealth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.data.download.lyrics.LyricsFetchStatus
import com.stash.data.download.lyrics.LyricsUpgradeTrigger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Drives the "Lyrics" card on Library Health. Kept separate so [LibraryHealthViewModel] is untouched. */
@HiltViewModel
class LyricsFetchViewModel @Inject constructor(
    private val trigger: LyricsUpgradeTrigger,
) : ViewModel() {

    val status: StateFlow<LyricsFetchStatus> = trigger.status
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), LyricsFetchStatus.Idle)

    fun fetchLyrics() = trigger.enqueueManualFetch()
}   