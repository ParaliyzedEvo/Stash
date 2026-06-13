package com.stash.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.stash.core.data.db.dao.DiscoveryQueueDao
import com.stash.core.data.db.dao.StashMixRecipeDao
import com.stash.core.data.mix.MixBuildState
import com.stash.core.data.mix.mixBuildState
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.BulkPlayAction
import com.stash.core.media.PlayerRepository
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.media.streaming.queuePlayableTracks
import com.stash.core.model.Playlist
import com.stash.core.model.PlaylistType
import com.stash.core.model.Track
import com.stash.core.ui.util.withSearchFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the Playlist Detail screen.
 *
 * @property playlist              The playlist metadata, or null while loading.
 * @property tracks                The ordered list of tracks belonging to this playlist.
 * @property isLoading             True while the initial data load is in progress.
 * @property currentlyPlayingTrackId The ID of the track currently playing, used
 *                                   to highlight the active row in the list.
 */
data class PlaylistDetailUiState(
    val playlist: Playlist? = null,
    val tracks: List<Track> = emptyList(),
    val isLoading: Boolean = true,
    val currentlyPlayingTrackId: Long? = null,
    val searchQuery: String = "",
    val showSearch: Boolean = false,
)

/**
 * ViewModel for the Playlist Detail screen.
 *
 * Loads the playlist metadata via a one-shot suspend call and its tracks
 * reactively from [MusicRepository]. Combines both with the current player
 * state from [PlayerRepository] to highlight the active track row.
 *
 * The `playlistId` is extracted from the navigation [SavedStateHandle].
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
    private val playlistImageHelper: PlaylistImageHelper,
    private val streamingPreference: com.stash.core.data.prefs.StreamingPreference,
    private val connectivityMonitor: ConnectivityMonitor,
    private val recipeDao: StashMixRecipeDao,
    private val discoveryQueueDao: DiscoveryQueueDao,
) : ViewModel() {

    /** The playlist ID extracted from the navigation route arguments. */
    private val playlistId: Long = checkNotNull(savedStateHandle.get<Long>("playlistId")) {
        "playlistId is required but was not found in SavedStateHandle"
    }

    private val _searchQuery = MutableStateFlow("")
    private val _showSearch = MutableStateFlow(false)

    private val _tappedTrackId = MutableStateFlow<Long?>(null)
    val tappedTrackId: StateFlow<Long?> = _tappedTrackId.asStateFlow()

    private val _bulkPlayInFlight = MutableStateFlow<BulkPlayAction?>(null)
    val bulkPlayInFlight: StateFlow<BulkPlayAction?> = _bulkPlayInFlight.asStateFlow()

    fun onSearchQueryChanged(query: String) { _searchQuery.value = query }
    fun clearSearch() { _searchQuery.value = "" }
    fun toggleSearch() {
        _showSearch.value = !_showSearch.value
        if (!_showSearch.value) _searchQuery.value = ""
    }

    /** Holds the one-shot playlist metadata fetched in [init]. */
    private val _playlist = MutableStateFlow<Playlist?>(null)

    /**
     * Combined UI state that reacts to:
     * 1. Playlist metadata (one-shot load into [_playlist])
     * 2. Track list changes (reactive Flow from [MusicRepository])
     * 3. Player state changes (to highlight the currently-playing track)
     *
     * Uses [SharingStarted.WhileSubscribed] with a 5-second stop timeout to keep
     * the flow alive across configuration changes without leaking resources.
     */
    val uiState: StateFlow<PlaylistDetailUiState> = combine(
        _playlist,
        musicRepository.getTracksByPlaylist(playlistId).withSearchFilter(_searchQuery),
        playerRepository.playerState,
        _searchQuery,
        _showSearch,
    ) { playlist, tracks, playerState, query, showSearch ->
        PlaylistDetailUiState(
            playlist = playlist,
            tracks = tracks,
            isLoading = playlist == null,
            currentlyPlayingTrackId = playerState.currentTrack?.id,
            searchQuery = query,
            showSearch = showSearch,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PlaylistDetailUiState(),
    )

    /**
     * Build state for THIS playlist if it's a custom Stash Mix — drives the
     * "Building your mix…" / "Couldn't find tracks" states while a freshly
     * created mix populates. READY for non-mix playlists and built-ins.
     */
    val buildState: StateFlow<MixBuildState> = combine(
        recipeDao.observeAll(),
        discoveryQueueDao.observeNonFailedCountsByRecipe(),
        musicRepository.getTracksByPlaylist(playlistId),
    ) { recipes, counts, tracks ->
        val recipe = recipes.firstOrNull { it.playlistId == playlistId }
            ?: return@combine MixBuildState.READY
        val count = counts.firstOrNull { it.recipeId == recipe.id }?.count ?: 0
        mixBuildState(recipe, trackCount = tracks.size, nonFailedDiscoveryCount = count)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MixBuildState.READY,
    )

    init {
        loadPlaylistMetadata()
    }

    // ── Data loading ────────────────────────────────────────────────────

    /**
     * Loads the playlist metadata (name, art, source, etc.) once.
     * The tracks are loaded reactively via the Flow in [uiState],
     * so only the metadata needs a one-shot fetch.
     */
    private fun loadPlaylistMetadata() {
        viewModelScope.launch {
            _playlist.value = musicRepository.getPlaylistWithTracks(playlistId)
        }
    }

    // ── Playback actions ────────────────────────────────────────────────

    /**
     * Sets the playback queue to all downloaded tracks in this playlist
     * and begins playback from the track matching [trackId].
     *
     * Uses the track ID rather than a positional index because the UI
     * shows all tracks (including non-downloaded ones) but the queue
     * only contains downloaded tracks. A positional index from the UI
     * would point to the wrong track after non-downloaded entries are
     * filtered out.
     */
    fun playTrack(trackId: Long) {
        // ── Stream-only tap guard ──
        // Stream-only tracks (isStreamable + !isDownloaded) have no local audio
        // and require both Online mode AND a live connection. The player's
        // offline master-gate silently skips them in Offline mode, so bail out
        // early with a Snackbar so the user knows *why* nothing happened.
        // Downloaded tracks always play (local file works offline). Stream-only
        // tracks online (streaming on + connected) go through normal play.
        viewModelScope.launch {
            val tapped = uiState.value.tracks.firstOrNull { it.id == trackId }
            if (tapped != null && tapped.isStreamable && !tapped.isDownloaded) {
                if (!streamingPreference.current()) {
                    // Offline mode: the player's offline master-gate would
                    // silently skip a stream-only track. Tell the user how to
                    // play it instead of enqueuing something that can't play.
                    _userMessages.tryEmit("Switch to Online mode to play this track")
                    return@launch
                }
                if (!connectivityMonitor.isConnected()) {
                    _userMessages.tryEmit("Online only — connect to play this track")
                    return@launch
                }
            }

            _tappedTrackId.value = trackId
            try {
                val playable = playableTracks()
                if (playable.isEmpty()) return@launch
                val index = playable.indexOfFirst { it.id == trackId }
                if (index < 0) return@launch // tapped track not in playable list
                playerRepository.setQueue(playable, index)
            } finally {
                _tappedTrackId.value = null
            }
        }
    }

    /**
     * The subset of [uiState] tracks that can actually be enqueued right now.
     *
     * - **Streaming mode on:** every track. Stream-only tracks resolve via
     *   Kennyy inside [PlayerRepository.setQueue].
     * - **Offline mode (streaming off):** downloaded-only, so we never enqueue
     *   items the player's offline master-gate would silently skip.
     *
     * A Stash Mix still renders its full track list offline (TrackDao's
     * STASH_MIX visibility exemption), but tapping a stream-only mix track in
     * Offline mode is handled by the per-tap guard in [playTrack], which
     * surfaces "Switch to Online mode to play this track" rather than enqueuing
     * something the player can't play.
     */
    private suspend fun playableTracks(): List<Track> =
        queuePlayableTracks(uiState.value.tracks, streamingPreference.current())

    /**
     * Shuffles the playlist and begins playback. Streaming mode shuffles all
     * tracks (downloaded + synced-streamable); offline mode only shuffles
     * tracks present on disk.
     */
    fun shuffleAll() {
        viewModelScope.launch {
            val playable = playableTracks()
            if (playable.isEmpty()) return@launch
            val shuffled = playable.shuffled()
            _tappedTrackId.value = shuffled[0].id
            _bulkPlayInFlight.value = BulkPlayAction.SHUFFLE_ALL
            try {
                playerRepository.setQueue(shuffled, 0)
            } finally {
                _tappedTrackId.value = null
                _bulkPlayInFlight.compareAndSet(BulkPlayAction.SHUFFLE_ALL, null)
            }
        }
    }

    /**
     * Plays the playlist in order starting at the first track. Streaming-mode
     * aware — same filter as [shuffleAll].
     */
    fun playAll() {
        viewModelScope.launch {
            val playable = playableTracks()
            if (playable.isEmpty()) return@launch
            _tappedTrackId.value = playable[0].id
            _bulkPlayInFlight.value = BulkPlayAction.PLAY_ALL
            try {
                playerRepository.setQueue(playable, 0)
            } finally {
                _tappedTrackId.value = null
                _bulkPlayInFlight.compareAndSet(BulkPlayAction.PLAY_ALL, null)
            }
        }
    }

    /**
     * Inserts [track] immediately after the currently-playing track in
     * the playback queue.
     */
    fun playNext(track: Track) {
        viewModelScope.launch {
            playerRepository.addNext(track)
        }
    }

    /**
     * Appends [track] to the end of the current playback queue.
     */
    fun addToQueue(track: Track) {
        viewModelScope.launch {
            playerRepository.addToQueue(track)
        }
    }

    /**
     * Delete a track using the protected-playlist cascade against the
     * currently-open playlist. If the track is also in Liked Songs or an
     * in-app custom playlist (other than this one), only the membership
     * in THIS playlist is removed — the file stays so the other lists
     * still play. If [alsoBlacklist] is set, destroyed tracks are also
     * marked never-download-again.
     *
     * The returned cascade summary is emitted on [userMessages] as a
     * human-readable string so the detail screen can show a Snackbar.
     */
    /**
     * Queue [trackId] for download. Inserts a row into `download_queue`
     * and kicks the discovery worker — see [MusicRepository.queueDownload].
     */
    fun queueDownload(trackId: Long) {
        viewModelScope.launch {
            musicRepository.queueDownload(trackId)
            _userMessages.tryEmit("Queued for download.")
        }
    }

    /**
     * Delete the on-disk file but keep the streamable row. ExoPlayer's
     * open FD keeps any currently-playing audio alive until track end.
     */
    fun removeDownload(trackId: Long) {
        viewModelScope.launch {
            musicRepository.removeDownload(trackId)
            _userMessages.tryEmit("Download removed.")
        }
    }

    fun deleteTrackFromPlaylist(track: Track, alsoBlacklist: Boolean) {
        viewModelScope.launch {
            val isDownloadsMix = uiState.value.playlist?.type == PlaylistType.DOWNLOADS_MIX
            val summary = musicRepository.removeTrackFromPlaylistAndMaybeDelete(
                trackId = track.id,
                fromPlaylistId = playlistId,
                alsoBlacklist = alsoBlacklist,
            )
            val msg = if (isDownloadsMix) {
                "Deleted from your library."
            } else {
                when {
                    summary.keptProtected > 0 ->
                        "Removed from this playlist. Kept on disk (also in Liked Songs or a custom playlist)."
                    summary.keptElsewhere > 0 ->
                        "Removed from this playlist. Kept on disk (in other playlists)."
                    summary.blacklisted > 0 ->
                        "Deleted and blocked from future syncs."
                    summary.deleted > 0 ->
                        "Deleted from your device."
                    else -> "Removed."
                }
            }
            _userMessages.tryEmit(msg)
        }
    }

    // ── Batch (multi-select) actions ─────────────────────────────────────
    // Each wraps the existing single-track path for the multi-select toolbar.
    // Queue uses the batch addToQueue(List) overload (single call); Play Next
    // loops addNext; download/remove/save/delete loop the per-id repo calls.
    //
    // Looped batches isolate per-item failures (one bad item must not abort
    // the rest) and emit a SINGLE roll-up Snackbar mirroring the single-track
    // wording. CancellationException is always re-thrown so structured-
    // concurrency cancellation still propagates (project rule).

    /**
     * Insert each of [tracks] after the currently-playing track, in order.
     * Silent — the single-track [playNext] emits no message, so neither does
     * the batch (the toolbar dismissing is feedback enough).
     */
    fun playSelectedNext(tracks: List<Track>) {
        viewModelScope.launch {
            tracks.forEach {
                runCatching { playerRepository.addNext(it) }
                    .onFailure { e -> if (e is CancellationException) throw e }
            }
        }
    }

    /**
     * Append [tracks] to the queue via the batch overload (single call).
     * Silent — the single-track [addToQueue] emits no message.
     */
    fun addSelectedToQueue(tracks: List<Track>) {
        viewModelScope.launch {
            playerRepository.addToQueue(tracks)
        }
    }

    /** Queue each of [trackIds] for download. Emits one roll-up Snackbar. */
    fun downloadSelected(trackIds: List<Long>) {
        viewModelScope.launch {
            var succeeded = 0
            trackIds.forEach { id ->
                runCatching { musicRepository.queueDownload(id) }
                    .onSuccess { succeeded++ }
                    .onFailure { e -> if (e is CancellationException) throw e }
            }
            if (succeeded > 0) {
                _userMessages.tryEmit("Queued $succeeded ${songs(succeeded)} for download.")
            }
        }
    }

    /**
     * Remove the on-disk file for each of [trackIds], keeping streamable rows.
     * Emits one roll-up Snackbar.
     */
    fun removeDownloadsForSelected(trackIds: List<Long>) {
        viewModelScope.launch {
            var succeeded = 0
            trackIds.forEach { id ->
                runCatching { musicRepository.removeDownload(id) }
                    .onSuccess { succeeded++ }
                    .onFailure { e -> if (e is CancellationException) throw e }
            }
            if (succeeded > 0) {
                _userMessages.tryEmit("Removed downloads for $succeeded ${songs(succeeded)}.")
            }
        }
    }

    /** Add each of [trackIds] to the playlist identified by [playlistId]. */
    fun saveSelectedToPlaylist(trackIds: List<Long>, playlistId: Long) {
        viewModelScope.launch {
            trackIds.forEach { id ->
                runCatching { musicRepository.addTrackToPlaylist(id, playlistId) }
                    .onFailure { e -> if (e is CancellationException) throw e }
            }
        }
    }

    /**
     * Delete each of [tracks] from this playlist via the protected-playlist
     * cascade. If [alsoBlacklist] is set, destroyed tracks are marked
     * never-download-again.
     *
     * Aggregates each track's [MusicRepository.CascadeRemovalSummary] into one
     * roll-up Snackbar, mirroring the single-track [deleteTrackFromPlaylist]
     * wording (including the DOWNLOADS_MIX special-case). Per-item failures are
     * isolated so one bad track doesn't abort the rest.
     */
    fun deleteSelected(tracks: List<Track>, alsoBlacklist: Boolean) {
        viewModelScope.launch {
            val isDownloadsMix = uiState.value.playlist?.type == PlaylistType.DOWNLOADS_MIX
            var removed = 0
            var deleted = 0
            var keptProtected = 0
            var keptElsewhere = 0
            var blacklisted = 0
            tracks.forEach { track ->
                runCatching {
                    musicRepository.removeTrackFromPlaylistAndMaybeDelete(
                        trackId = track.id,
                        fromPlaylistId = playlistId,
                        alsoBlacklist = alsoBlacklist,
                    )
                }.onSuccess { summary ->
                    removed++
                    deleted += summary.deleted
                    keptProtected += summary.keptProtected
                    keptElsewhere += summary.keptElsewhere
                    blacklisted += summary.blacklisted
                }.onFailure { e -> if (e is CancellationException) throw e }
            }
            if (removed == 0) return@launch
            val msg = if (isDownloadsMix) {
                "Deleted $removed ${songs(removed)} from your library."
            } else {
                val base = "Removed $removed ${songs(removed)}"
                when {
                    keptProtected > 0 ->
                        "$base. Kept on disk (also in Liked Songs or a custom playlist)."
                    keptElsewhere > 0 ->
                        "$base. Kept on disk (in other playlists)."
                    blacklisted > 0 ->
                        "$base. Blocked from future syncs."
                    deleted > 0 ->
                        "Deleted $removed ${songs(removed)} from your device."
                    else -> base + "."
                }
            }
            _userMessages.tryEmit(msg)
        }
    }

    /** "song" / "songs" for [count]-aware roll-up messages. */
    private fun songs(count: Int): String = if (count == 1) "song" else "songs"

    private val _userMessages = kotlinx.coroutines.flow.MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    /** Snackbar-targeted messages (delete confirmation, errors). */
    val userMessages: kotlinx.coroutines.flow.SharedFlow<String> =
        _userMessages.asSharedFlow()

    /** User-created playlists for the Save to Playlist picker. */
    val userPlaylists = musicRepository.getUserCreatedPlaylists()

    /** Save a track to an existing playlist. */
    fun saveTrackToPlaylist(trackId: Long, playlistId: Long) {
        viewModelScope.launch {
            musicRepository.addTrackToPlaylist(trackId, playlistId)
        }
    }

    /** Create a new playlist and immediately add the track to it. */
    fun createPlaylistAndAddTrack(name: String, trackId: Long) {
        viewModelScope.launch {
            val playlistId = musicRepository.createPlaylist(name)
            musicRepository.addTrackToPlaylist(trackId, playlistId)
        }
    }

    /** Create a new playlist and add the whole batch of [trackIds] to it. */
    fun createPlaylistAndAddTracks(name: String, trackIds: List<Long>) {
        viewModelScope.launch {
            val playlistId = musicRepository.createPlaylist(name)
            trackIds.forEach { id ->
                runCatching { musicRepository.addTrackToPlaylist(id, playlistId) }
                    .onFailure { e -> if (e is CancellationException) throw e }
            }
        }
    }

    // ── Playlist cover image ───────────────────────────────────────────

    /**
     * Save a user-picked image as the playlist's cover art.
     *
     * After persisting to the DB, also re-emits [_playlist] with the new
     * [artUrl]. The playlist metadata is loaded once via a suspend call
     * (see [loadPlaylistMetadata]) and is not backed by a reactive Flow,
     * so the detail screen would otherwise keep showing the stale artUrl
     * until the user navigates away and back.
     */
    fun setPlaylistImage(playlistId: Long, imageUri: Uri) {
        viewModelScope.launch {
            val artUrl = playlistImageHelper.savePlaylistCoverImage(playlistId, imageUri)
            if (artUrl != null) {
                musicRepository.updatePlaylistArtUrl(playlistId, artUrl)
                _playlist.value = _playlist.value?.copy(artUrl = artUrl)
            }
        }
    }

    /** Remove the custom cover image, reverting to the default placeholder. */
    fun removePlaylistImage(playlistId: Long) {
        viewModelScope.launch {
            playlistImageHelper.deletePlaylistCoverFile(playlistId)
            musicRepository.updatePlaylistArtUrl(playlistId, null)
            _playlist.value = _playlist.value?.copy(artUrl = null)
        }
    }
}
