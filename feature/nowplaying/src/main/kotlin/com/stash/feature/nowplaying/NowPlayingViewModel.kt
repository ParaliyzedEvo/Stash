package com.stash.feature.nowplaying

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.palette.graphics.Palette
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.ui.components.PlaylistInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * ViewModel for the full-screen Now Playing screen.
 *
 * Observes [PlayerRepository.playerState] and [PlayerRepository.currentPosition],
 * maps them into a single [NowPlayingUiState], and exposes one-shot action functions
 * that delegate to the repository.
 */
@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    private val playerRepository: PlayerRepository,
    private val musicRepository: MusicRepository,
    private val likeDispatcher: com.stash.core.data.social.LikeDestinationDispatcher,
    private val likePreferences: com.stash.core.data.prefs.LikePreferences,
    private val tokenManager: com.stash.core.auth.TokenManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NowPlayingUiState())
    val uiState: StateFlow<NowPlayingUiState> = _uiState.asStateFlow()

    private val _userMessages = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** One-shot snackbar messages (e.g. "Track flagged as wrong match"). */
    val userMessages: SharedFlow<String> = _userMessages.asSharedFlow()

    // v0.9.13: Per-track Like override sheet state. `null` = sheet hidden.
    private val _likeSheetState = MutableStateFlow<com.stash.core.ui.components.LikeDestinationSheetState?>(null)
    val likeSheetState: StateFlow<com.stash.core.ui.components.LikeDestinationSheetState?> = _likeSheetState.asStateFlow()

    init {
        observePlayerState()
        observeUserPlaylists()
    }

    // ------------------------------------------------------------------
    // Observation
    // ------------------------------------------------------------------

    /**
     * Combines the player state snapshot with the high-frequency position
     * ticker into a single [NowPlayingUiState] emission.
     */
    private fun observePlayerState() {
        combine(
            playerRepository.playerState,
            playerRepository.currentPosition,
        ) { state, positionMs ->
            _uiState.update { current ->
                // v0.9.13: smart-merge of player snapshot with locally-mutated
                // Like timestamps. The player re-emits the same Track object on
                // every position tick (~10/s); without this preservation step,
                // applyOptimisticLikeState's writes get stomped within ~100ms
                // and the heart icon flips back to outline. Forward-only
                // semantics make non-null preservation always correct: a
                // timestamp once set never legitimately needs to revert.
                val incoming = state.currentTrack
                val mergedTrack = if (incoming != null && current.currentTrack?.id == incoming.id) {
                    incoming.copy(
                        stashLikedAt = current.currentTrack?.stashLikedAt ?: incoming.stashLikedAt,
                        spotifySavedAt = current.currentTrack?.spotifySavedAt ?: incoming.spotifySavedAt,
                        ytMusicSavedAt = current.currentTrack?.ytMusicSavedAt ?: incoming.ytMusicSavedAt,
                    )
                } else {
                    incoming
                }
                current.copy(
                    currentTrack = mergedTrack,
                    isPlaying = state.isPlaying,
                    currentPositionMs = positionMs,
                    durationMs = state.durationMs,
                    shuffleEnabled = state.isShuffleEnabled,
                    repeatMode = state.repeatMode,
                    queueSize = state.queue.size,
                    currentIndex = state.currentIndex,
                    queue = state.queue,
                )
            }
        }.launchIn(viewModelScope)
    }

    /**
     * Observes user-created playlists and maps them to lightweight
     * [PlaylistInfo] models for the "Save to Playlist" bottom sheet.
     */
    private fun observeUserPlaylists() {
        musicRepository.getUserCreatedPlaylists()
            .onEach { playlists ->
                _uiState.update { current ->
                    current.copy(
                        userPlaylists = playlists.map { playlist ->
                            PlaylistInfo(
                                id = playlist.id,
                                name = playlist.name,
                                trackCount = playlist.trackCount,
                            )
                        },
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    // ------------------------------------------------------------------
    // User Actions
    // ------------------------------------------------------------------

    /** Toggle between play and pause. */
    fun onPlayPauseClick() {
        viewModelScope.launch {
            if (_uiState.value.isPlaying) {
                playerRepository.pause()
            } else {
                playerRepository.play()
            }
        }
    }

    /** Advance to the next track in the queue. */
    fun onSkipNext() {
        viewModelScope.launch { playerRepository.skipNext() }
    }

    /** Return to the previous track (or restart current). */
    fun onSkipPrevious() {
        viewModelScope.launch { playerRepository.skipPrevious() }
    }

    /**
     * Seek to [positionMs] within the current track.
     *
     * @param positionMs target position in milliseconds, clamped to `[0, durationMs]`.
     */
    fun onSeekTo(positionMs: Long) {
        val clamped = positionMs.coerceIn(0L, _uiState.value.durationMs)
        viewModelScope.launch { playerRepository.seekTo(clamped) }
    }

    /** Toggle shuffle mode on / off. */
    fun onToggleShuffle() {
        viewModelScope.launch { playerRepository.toggleShuffle() }
    }

    /** Cycle repeat mode: OFF -> ALL -> ONE -> OFF. */
    fun onCycleRepeatMode() {
        viewModelScope.launch { playerRepository.cycleRepeatMode() }
    }

    /**
     * Remove the track at [index] from the playback queue.
     * The currently-playing track cannot be removed through this action.
     */
    fun onRemoveFromQueue(index: Int) {
        if (index == _uiState.value.currentIndex) return
        viewModelScope.launch { playerRepository.removeFromQueue(index) }
    }

    /**
     * Move a track within the queue from position [from] to position [to].
     */
    fun onMoveInQueue(from: Int, to: Int) {
        viewModelScope.launch { playerRepository.moveInQueue(from, to) }
    }

    /**
     * Jump playback to the track at [index] in the queue.
     */
    fun onSkipToQueueIndex(index: Int) {
        viewModelScope.launch { playerRepository.skipToQueueIndex(index) }
    }

    // ------------------------------------------------------------------
    // Playlist Actions
    // ------------------------------------------------------------------

    /**
     * Add the currently-playing track to an existing playlist.
     *
     * @param trackId    ID of the track to save.
     * @param playlistId ID of the target playlist.
     */
    fun saveTrackToPlaylist(trackId: Long, playlistId: Long) {
        viewModelScope.launch { musicRepository.addTrackToPlaylist(trackId, playlistId) }
    }

    /**
     * Create a new playlist and immediately add the given track to it.
     *
     * @param name    Name for the new playlist.
     * @param trackId ID of the track to save into the newly created playlist.
     */
    fun createPlaylistAndAddTrack(name: String, trackId: Long) {
        viewModelScope.launch {
            val playlistId = musicRepository.createPlaylist(name)
            musicRepository.addTrackToPlaylist(trackId, playlistId)
        }
    }

    // ------------------------------------------------------------------
    // Wrong-match flagging
    // ------------------------------------------------------------------

    /**
     * Flag the currently-playing track as the wrong song. Surfaces it in
     * the Failed Matches screen so the user can pick a replacement. No-op
     * when nothing is playing. Emits a snackbar message so the user knows
     * where to go next.
     */
    fun flagCurrentTrackAsWrongMatch() {
        val track = _uiState.value.currentTrack ?: return
        viewModelScope.launch {
            musicRepository.setMatchFlagged(track.id, true)
            _userMessages.tryEmit("Flagged. Find it in Sync \u2192 Failed Matches to pick a replacement.")
        }
    }

    /**
     * Destroy the currently-playing track. Deletes the audio file + row;
     * if [alsoBlock] is set, keeps the row as a blacklist tombstone so
     * future syncs skip the identity forever.
     *
     * Skips to the next track BEFORE the delete so ExoPlayer doesn't
     * error out mid-playback on a file that just disappeared under it.
     * On the last track in the queue, skipNext will stop playback
     * naturally — cleaner than racing the delete.
     */
    fun deleteCurrentTrack(alsoBlock: Boolean) {
        val track = _uiState.value.currentTrack ?: return
        viewModelScope.launch {
            playerRepository.skipNext()
            if (alsoBlock) {
                musicRepository.blacklistTrack(track.id)
                _userMessages.tryEmit("Deleted and blocked from future syncs.")
            } else {
                musicRepository.deleteTrack(track)
                _userMessages.tryEmit("Deleted from your device.")
            }
        }
    }

    // ------------------------------------------------------------------
    // v0.9.13 — Heart / Like actions
    // ------------------------------------------------------------------

    /**
     * Tap-to-like with the user's default destinations from Settings.
     *
     * Stash always works locally; Spotify and YT Music only fire when the
     * user is connected AND has the corresponding default toggled on. The
     * dispatcher itself handles per-destination dedup — already-saved
     * targets are no-ops.
     *
     * Failures emit a per-destination Toast via [_userMessages]. Successes
     * are silent: the heart icon flips state via the Track flow update,
     * which is feedback enough.
     */
    fun onLikeTap() {
        val track = _uiState.value.currentTrack ?: return
        viewModelScope.launch {
            val defaults = buildSet {
                if (likePreferences.heartDefaultStashNow()) add(com.stash.core.data.social.Destination.STASH)
                if (likePreferences.heartDefaultSpotifyNow() &&
                    tokenManager.spotifyAuthState.value is com.stash.core.auth.model.AuthState.Connected) {
                    add(com.stash.core.data.social.Destination.SPOTIFY)
                }
                if (likePreferences.heartDefaultYtMusicNow() &&
                    tokenManager.youTubeAuthState.value is com.stash.core.auth.model.AuthState.Connected) {
                    add(com.stash.core.data.social.Destination.YT_MUSIC)
                }
            }
            if (defaults.isEmpty()) return@launch
            val results = likeDispatcher.like(track, defaults)
            applyOptimisticLikeState(track.id, results)
            results.forEach { (dest, result) ->
                val cause = result.exceptionOrNull() ?: return@forEach
                if (cause is com.stash.core.data.social.NoSpotifyUriException ||
                    cause is com.stash.core.data.social.NoYouTubeIdException) {
                    return@forEach  // Silently skip: track simply isn't on this platform.
                }
                _userMessages.tryEmit("Couldn't save to ${friendlyName(dest)}")
            }
        }
    }

    /**
     * Long-press on the heart — opens the per-track override sheet
     * pre-checked with the user's defaults, plus "Already saved" hints
     * for any destination whose `*_saved_at` is already populated.
     */
    fun onLikeLongPress() {
        viewModelScope.launch {
            val track = _uiState.value.currentTrack ?: return@launch
            _likeSheetState.value = com.stash.core.ui.components.LikeDestinationSheetState(
                spotifyVisible = tokenManager.spotifyAuthState.value is com.stash.core.auth.model.AuthState.Connected,
                ytVisible = tokenManager.youTubeAuthState.value is com.stash.core.auth.model.AuthState.Connected,
                stashChecked = likePreferences.heartDefaultStashNow(),
                spotifyChecked = likePreferences.heartDefaultSpotifyNow(),
                ytChecked = likePreferences.heartDefaultYtMusicNow(),
                stashAlreadySaved = track.stashLikedAt != null,
                spotifyAlreadySaved = track.spotifySavedAt != null,
                ytAlreadySaved = track.ytMusicSavedAt != null,
            )
        }
    }

    /** Dismiss the override sheet without saving. */
    fun onLikeSheetDismiss() {
        _likeSheetState.value = null
    }

    /**
     * Save action from the override sheet. Selection is ephemeral — it
     * does NOT update Settings defaults. Errors are intentionally
     * swallowed here; the dispatcher logs them and the sheet closes
     * either way to match Material guidance for modal sheets.
     */
    fun onLikeSheetSave(selected: Set<com.stash.core.ui.components.DestinationCheckbox>) {
        val track = _uiState.value.currentTrack ?: return
        viewModelScope.launch {
            val mapped = selected.map { ui ->
                when (ui) {
                    com.stash.core.ui.components.DestinationCheckbox.STASH -> com.stash.core.data.social.Destination.STASH
                    com.stash.core.ui.components.DestinationCheckbox.SPOTIFY -> com.stash.core.data.social.Destination.SPOTIFY
                    com.stash.core.ui.components.DestinationCheckbox.YT_MUSIC -> com.stash.core.data.social.Destination.YT_MUSIC
                }
            }.toSet()
            val results = likeDispatcher.like(track, mapped)
            applyOptimisticLikeState(track.id, results)
            _likeSheetState.value = null
        }
    }

    /**
     * v0.9.13: Mirror successful saves into [_uiState.currentTrack] so the
     * heart icon flips immediately. The Track flowing in from
     * [playerRepository.playerState] is a snapshot taken at track-load
     * time and is NOT live-bound to the `tracks` table, so without this
     * write-back the icon stays outline-white even though the DB row
     * has the timestamp set. Only updates the destinations that
     * actually succeeded; failures and skipped destinations leave the
     * timestamp at whatever it was before.
     */
    private fun applyOptimisticLikeState(
        trackId: Long,
        results: Map<com.stash.core.data.social.Destination, Result<Unit>>,
    ) {
        val now = System.currentTimeMillis()
        _uiState.update { current ->
            val track = current.currentTrack ?: return@update current
            if (track.id != trackId) return@update current  // Track changed mid-flight; drop.
            val updated = track.copy(
                stashLikedAt = if (results[com.stash.core.data.social.Destination.STASH]?.isSuccess == true) {
                    track.stashLikedAt ?: now
                } else track.stashLikedAt,
                spotifySavedAt = if (results[com.stash.core.data.social.Destination.SPOTIFY]?.isSuccess == true) {
                    track.spotifySavedAt ?: now
                } else track.spotifySavedAt,
                ytMusicSavedAt = if (results[com.stash.core.data.social.Destination.YT_MUSIC]?.isSuccess == true) {
                    track.ytMusicSavedAt ?: now
                } else track.ytMusicSavedAt,
            )
            current.copy(currentTrack = updated)
        }
    }

    private fun friendlyName(dest: com.stash.core.data.social.Destination): String = when (dest) {
        com.stash.core.data.social.Destination.STASH -> "Stash"
        com.stash.core.data.social.Destination.SPOTIFY -> "Spotify"
        com.stash.core.data.social.Destination.YT_MUSIC -> "YouTube Music"
    }

    // ------------------------------------------------------------------
    // Palette Color Extraction
    // ------------------------------------------------------------------

    /**
     * Called when the album art [Bitmap] has been loaded (e.g. via Coil).
     *
     * Extracts dominant, vibrant, and muted colors on [Dispatchers.Default]
     * so the main thread is never blocked by the Palette computation.
     *
     * Passing `null` resets colors to their defaults.
     */
    fun onAlbumArtLoaded(bitmap: Bitmap?) {
        if (bitmap == null) {
            _uiState.update {
                it.copy(
                    dominantColor = Color(0xFF6750A4),
                    vibrantColor = Color(0xFF8E24AA),
                    mutedColor = Color(0xFF37474F),
                )
            }
            return
        }

        viewModelScope.launch {
            val (dominant, vibrant, muted) = withContext(Dispatchers.Default) {
                val palette = Palette.from(bitmap).generate()
                Triple(
                    Color(palette.getDominantColor(0xFF6750A4.toInt())),
                    Color(palette.getVibrantColor(0xFF8E24AA.toInt())),
                    Color(palette.getMutedColor(0xFF37474F.toInt())),
                )
            }
            _uiState.update {
                it.copy(
                    dominantColor = dominant,
                    vibrantColor = vibrant,
                    mutedColor = muted,
                )
            }
        }
    }
}
