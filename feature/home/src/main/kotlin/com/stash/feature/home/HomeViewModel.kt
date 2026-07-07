package com.stash.feature.home

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.db.dao.DiscoveryQueueDao
import com.stash.core.data.db.dao.StashMixRecipeDao
import com.stash.core.data.mix.MixBuildState
import com.stash.core.data.mix.mixBuildState
import com.stash.core.data.prefs.DownloadNetworkPreference
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.workers.StashDiscoveryWorker
import com.stash.core.data.sync.workers.StashMixRefreshWorker
import com.stash.core.media.PlayerRepository
import com.stash.core.media.streaming.queuePlayableTracks
import com.stash.core.model.MusicSource
import com.stash.core.model.Playlist
import com.stash.core.model.PlaylistType
import com.stash.core.model.Track
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.backfill.MetadataBackfillState
import com.stash.feature.home.banner.MetadataBackfillBannerState
import com.stash.feature.home.banner.metadataBackfillBannerStateFor
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "HomeViewModel"

/**
 * ViewModel for the Home screen. Collects playlist, track, recently-added,
 * and prompt-banner data from [MusicRepository] and the Last.fm / lossless
 * preference surfaces, combining them into a single reactive [HomeUiState].
 *
 * All data sources are Flow-based so the UI updates automatically when:
 * - New tracks/playlists are inserted after a sync
 * - A sync completes and a new history record appears
 *
 * Note: the Sync status card (and its per-source connection booleans,
 * library-size walk, and latest-sync stream) was relocated to
 * `:feature:sync` in the SyncStatusCard relocation refactor — see
 * SyncViewModel for the moved flow assembly. Home no longer observes
 * TokenManager.spotifyAuthState / youTubeAuthState directly.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
    private val losslessPrefs: LosslessSourcePreferences,
    private val settingsDeepLinkController: com.stash.core.data.navigation.SettingsDeepLinkController,
    private val tipJarRepository: com.stash.core.data.tipjar.TipJarRepository,
    private val recipeDao: StashMixRecipeDao,
    private val discoveryQueueDao: DiscoveryQueueDao,
    private val downloadNetworkPreference: DownloadNetworkPreference,
    private val streamingPreference: StreamingPreference,
    private val metadataBackfillState: MetadataBackfillState,
    private val lyricsBackfillState: LyricsBackfillState,
    private val networkAwareStreamingManager: com.stash.core.media.streaming.NetworkAwareStreamingManager,
    private val syncScheduler: com.stash.core.data.sync.SyncScheduler,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /**
     * Master streaming-mode toggle observed by the Home `StreamingModeToggle`.
     * Mirrors [StreamingPreference.enabled] one-for-one — writes route
     * through `MusicRepository.applyStreamingMode` (currently just a pref
     * write — v0.9.30 Path A: Library is downloaded-only regardless).
     *
     * Gated for visibility by `StashConstants.STREAMING_ENGINE_ENABLED`
     * inside the composable — the StateFlow keeps emitting regardless,
     * so when the kill-switch is flipped on the Home toggle picks up the
     * current pref value immediately without a recompose cycle.
     */
    val streamingEnabled: StateFlow<Boolean> = streamingPreference.enabled
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = true,
        )

    /**
     * One-shot event: emit when the user is about to enable streaming for
     * the first time so the Home screen can show the privacy disclosure
     * dialog. The pref is already being flipped — the dialog is purely
     * informational ("here's what streaming means"), not a confirmation
     * gate.
     */
    private val _showStreamingDisclosure = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val showStreamingDisclosure: SharedFlow<Unit> = _showStreamingDisclosure.asSharedFlow()

    /**
     * v0.9.30 Path A: simplified one-arg streaming toggle.
     *
     * Off→On: if the user has never seen the disclosure, emit a one-shot
     * event so the screen renders the AlertDialog after the pref flips.
     * On→Off: no prompt — flip the pref instantly.
     *
     * Library is always downloaded-only regardless of this toggle; the
     * pref gates only search-tap streaming and the Now Playing wifi
     * indicator. See `MusicRepository.applyStreamingMode` for the
     * (deliberately minimal) side-effects.
     */
    fun onStreamingToggle(enabled: Boolean) {
        viewModelScope.launch {
            musicRepository.applyStreamingMode(enabled = enabled)
            if (enabled) {
                val prefs = context.getSharedPreferences(
                    STREAMING_DISCLOSURE_PREFS,
                    Context.MODE_PRIVATE,
                )
                if (!prefs.getBoolean(STREAMING_DISCLOSURE_SEEN_KEY, false)) {
                    _showStreamingDisclosure.tryEmit(Unit)
                    prefs.edit().putBoolean(STREAMING_DISCLOSURE_SEEN_KEY, true).apply()
                }
            }
        }
    }

    private val _userMessages = MutableSharedFlow<String>(
        // Bumped to 8 to mirror NowPlayingViewModel — actions that emit two
        // back-to-back messages (refresh start → done, lossless retry start →
        // result) need headroom against the Toast collector's drain rate.
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** One-shot snackbar messages (e.g. "Refreshing Daily Discover…"). */
    val userMessages: SharedFlow<String> = _userMessages.asSharedFlow()

    init {
        // v0.9.13: warm the tip-jar cache on cold-start, then trigger a
        // network refresh if the cache is stale (>15 min). Failures are
        // silently absorbed by the repo — the pill always shows
        // something thanks to the bundled fallback.
        viewModelScope.launch {
            tipJarRepository.warmUp()
            if (tipJarRepository.isStale()) {
                tipJarRepository.refresh()
            }
        }
        // Forward network-aware streaming messages (Online↔Offline auto-switch
        // notifications) to the Home screen's toast channel.
        viewModelScope.launch {
            networkAwareStreamingManager.messages.collect { msg ->
                _userMessages.tryEmit(msg)
            }
        }
    }

    /** v0.9.13: callable from the screen on resume to keep the pill fresh. */
    fun refreshTipJarIfStale() {
        viewModelScope.launch {
            if (tipJarRepository.isStale()) tipJarRepository.refresh()
        }
    }

    /**
     * Pull-to-refresh handler. Triggers a one-shot sync (same work
     * request pattern the Sync tab uses) and refreshes the tip-jar
     * data. The refresh indicator stays visible for at least 1s so the
     * user gets visual feedback even if the sync returns instantly.
     */
    fun onRefresh() {
        if (_isRefreshing.value) return // debounce
        viewModelScope.launch {
            _isRefreshing.value = true
            val startMs = System.currentTimeMillis()
            try {
                // Enqueue a one-shot sync just like the Sync tab does.
                syncScheduler.triggerManualSync()
                // Also refresh tip-jar data.
                if (tipJarRepository.isStale()) tipJarRepository.refresh()
            } catch (e: Exception) {
                Log.w(TAG, "pull-to-refresh sync failed", e)
            }
            // Keep the indicator visible for at least 1s.
            val elapsed = System.currentTimeMillis() - startMs
            if (elapsed < 1_000L) delay(1_000L - elapsed)
            _isRefreshing.value = false
        }
    }

    /**
     * Bundles the two Room-backed flows Home needs into a single
     * intermediate holder. Track count + disk-walked library size used
     * to live here too — both fed the SyncStatusCard at the top of
     * Home; that card now lives in `:feature:sync` and its plumbing
     * moved with it.
     */
    private val musicDataFlow = combine(
        musicRepository.getAllPlaylists(),
        musicRepository.getRecentlyAdded(20),
        musicRepository.getAllTracks(),
        // Folded in here (rather than as a 6th positional arg to the top-
        // level `uiState` combine, which is already at the 5-arg typed-
        // overload max) so the recipe-derived custom-mix sets ride the
        // existing holder flow alongside `playlists`.
        recipeDao.observeAll(),
        discoveryQueueDao.observeNonFailedCountsByRecipe(),
    ) { playlists, recentlyAdded, allTracks, recipes, discoveryCounts ->
        val customRecipes = recipes.filter { !it.isBuiltin && it.playlistId != null }
        val customMixPlaylistIds = customRecipes.mapNotNull { it.playlistId }.toSet()

        // Per-custom-mix build state, so the Home card can show "Building…"
        // while a freshly-created mix populates, and "No tracks" if it found
        // nothing — instead of looking broken at "0 tracks".
        val trackCounts = playlists.associate { it.id to it.trackCount }
        val discoveryByRecipe = discoveryCounts.associate { it.recipeId to it.count }
        val buildingMixIds = mutableSetOf<Long>()
        val emptyMixIds = mutableSetOf<Long>()
        for (recipe in customRecipes) {
            val playlistId = recipe.playlistId ?: continue
            val state = mixBuildState(
                recipe = recipe,
                trackCount = trackCounts[playlistId] ?: 0,
                nonFailedDiscoveryCount = discoveryByRecipe[recipe.id] ?: 0,
            )
            when (state) {
                MixBuildState.BUILDING -> buildingMixIds.add(playlistId)
                MixBuildState.EMPTY -> emptyMixIds.add(playlistId)
                MixBuildState.READY -> Unit
            }
        }
        MusicData(
            playlists = playlists,
            recentlyAdded = recentlyAdded,
            allTracks = allTracks,
            customMixPlaylistIds = customMixPlaylistIds,
            buildingMixIds = buildingMixIds,
            emptyMixIds = emptyMixIds
        )
    }

    /**
     * Active sort for the Home Playlists grid. Starts at RECENT to match
     * the previous default (implicit `getAllPlaylists` ordering, now made
     * explicit as "most recently added first").
     */
    private val _playlistSortOrder = MutableStateFlow(PlaylistSortOrder.RECENT)

    /**
     * Lossless connect nudge: only visible when the user has not
     * enabled lossless AND has not dismissed the banner. Once dismissed,
     * the DataStore write makes the Flow re-emit null and the banner
     * disappears on its own.
     */
    private val losslessPromptFlow = combine(
        losslessPrefs.enabled,
        losslessPrefs.bannerDismissed,
    ) { enabled, dismissed ->
        if (!enabled && !dismissed) LosslessPromptState else null
    }

    /**
     * v0.9.35: drives [HomeUiState.metadataBackfillBanner]. Pure-mapped
     * from [MetadataBackfillState.snapshot] so the banner sealed type
     * doesn't have to plumb through the raw DataStore record. Hidden in
     * the steady state (the dominant case post-backfill).
     */
    private val metadataBackfillBannerFlow: Flow<MetadataBackfillBannerState> =
        metadataBackfillState.snapshot.map { metadataBackfillBannerStateFor(it) }

    /**
     * Bundles the Home-banner flows ([bannerStateFlow] +
     * [metadataBackfillBannerFlow]) into a single emission so the top-level
     * [uiState] combine stays at its non-vararg-friendly arg count. Mirrors
     * the [authStateFlow] precedent.
     */
    private val bannersInfoFlow: StateFlow<BannersInfo> = combine(
        bannerStateFlow,
        metadataBackfillBannerFlow,
        lyricsBackfillBannerFlow,
    ) { lossless, backfill, lyrics -> BannersInfo(lossless, backfill, lyrics) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = BannersInfo(
                WaitingForLosslessBannerState.Hidden,
                MetadataBackfillBannerState.Hidden,
                LyricsBackfillBannerState.Hidden,
            ),
        )

    /**
     * Bundles the two prompt-banner flows so the top-level combine
     * treats them as a single positional arg. Previously also carried
     * Spotify / YouTube auth state for the SyncStatusCard's "Connect
     * Spotify or YouTube Music" prompt — that responsibility moved
     * to `:feature:sync` along with the card itself.
     */
    private val promptsFlow: StateFlow<PromptsInfo> = combine(
        lastFmPromptFlow,
        losslessPromptFlow,
    ) { lastFmPrompt, losslessPrompt ->
        PromptsInfo(
            lastFmPrompt = lastFmPrompt,
            losslessPrompt = losslessPrompt,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = PromptsInfo(lastFmPrompt = null, losslessPrompt = null),
    )

    /** Pull-to-refresh state — set by [onRefresh], cleared after the refresh completes. */
    private val _isRefreshing = MutableStateFlow(false)

    private val _baseUiState: Flow<HomeUiState> = combine(
        musicDataFlow,
        losslessPromptFlow,
        _playlistSortOrder,
        tipJarRepository.state,
        metadataBackfillBannerFlow,
    ) { musicData, losslessPrompt, playlistSortOrder, tipJar, metadataBackfillBanner ->
        // Stash Mixes — recipe-driven, generated locally. Separate from
        // sync-imported Daily Mixes so the UI can label them distinctly.
        val stashMixes = musicData.playlists.filter {
            it.type == PlaylistType.STASH_MIX || it.type == PlaylistType.DOWNLOADS_MIX
        }

        // Split daily mixes by source
        val dailyMixes = musicData.playlists.filter { it.type == PlaylistType.DAILY_MIX }
        val spotifyMixes = dailyMixes.filter { it.source == MusicSource.SPOTIFY }
        val youtubeMixes = dailyMixes.filter { it.source == MusicSource.YOUTUBE }

        // Split liked songs by source
        val likedPlaylists = musicData.playlists.filter { it.type == PlaylistType.LIKED_SONGS }
        val spotifyLikedPlaylists = likedPlaylists.filter { it.source == MusicSource.SPOTIFY }
        val youtubeLikedPlaylists = likedPlaylists.filter { it.source == MusicSource.YOUTUBE }
        val spotifyLikedCount = spotifyLikedPlaylists.sumOf { it.trackCount }
        val youtubeLikedCount = youtubeLikedPlaylists.sumOf { it.trackCount }

        val otherPlaylists = musicData.playlists
            .filter { it.type == PlaylistType.CUSTOM || it.type == PlaylistType.STASH_LIKED }
            .let { list ->
                when (playlistSortOrder) {
                    PlaylistSortOrder.RECENT -> list.sortedByDescending { it.dateAdded }
                    PlaylistSortOrder.ALPHABETICAL -> list.sortedBy { it.name.lowercase() }
                    PlaylistSortOrder.MOST_PLAYED -> list.sortedByDescending { it.trackCount }
                }
            }

        val localSongs = musicData.allTracks.filter { it.isDownloaded }
            .sortedByDescending { it.dateAdded }
            .take(20)

        val likedSongs = musicData.allTracks.filter {
            it.spotifySavedAt != null || it.ytMusicSavedAt != null || it.stashLikedAt != null
        }.sortedByDescending {
            maxOf(it.spotifySavedAt ?: 0L, it.ytMusicSavedAt ?: 0L, it.stashLikedAt ?: 0L)
        }.take(20)

        HomeUiState(
            stashMixes = stashMixes,
            spotifyMixes = spotifyMixes,
            youtubeMixes = youtubeMixes,
            recentlyAdded = musicData.recentlyAdded,
            localSongs = localSongs,
            likedSongs = likedSongs,
            spotifyLikedPlaylists = spotifyLikedPlaylists,
            youtubeLikedPlaylists = youtubeLikedPlaylists,
            spotifyLikedCount = spotifyLikedCount,
            youtubeLikedCount = youtubeLikedCount,
            playlists = otherPlaylists,
            customMixPlaylistIds = musicData.customMixPlaylistIds,
            buildingMixIds = musicData.buildingMixIds,
            emptyMixIds = musicData.emptyMixIds,
            playlistSortOrder = playlistSortOrder,
            isLoading = false,
            losslessPrompt = losslessPrompt,
            tipJar = tipJar,
            metadataBackfillBanner = metadataBackfillBanner,
            lyricsBackfillBanner = lyricsBackfillBanner,
        )
    }

    val uiState: StateFlow<HomeUiState> = combine(
        _baseUiState,
        _isRefreshing,
    ) { base, refreshing ->
        base.copy(isRefreshing = refreshing)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )

    /**
     * Updates the sort applied to the Home Playlists grid. The new order
     * propagates through the UI state combine and the grid re-sorts on the
     * next recomposition.
     */
    fun setPlaylistSortOrder(order: PlaylistSortOrder) {
        _playlistSortOrder.update { order }
    }

    /**
     * Hide the "Try lossless audio" Home banner forever. Writes
     * through to DataStore; the prompt Flow re-emits null on the
     * next tick and the banner disappears.
     */
    fun dismissLosslessBanner() {
        viewModelScope.launch {
            losslessPrefs.setBannerDismissed(true)
        }
    }

    /**
     * v0.9.35: called by the Home re-tagging banner's `LaunchedEffect`
     * after the 2-second "Done" pulse expires. Flips
     * [MetadataBackfillState] back to IDLE, which causes the snapshot
     * Flow to emit a [MetadataBackfillBannerState.Hidden] mapping and
     * the banner vanishes from the screen.
     */
    fun onMetadataBackfillFinishedAcknowledged() {
        viewModelScope.launch { metadataBackfillState.markFinishedAcknowledged() }
    }

    /**
     * v0.9.13: Queue a Settings deep-link to the Lossless / Audio Quality
     * card. The Settings screen reads + clears this on entry and scrolls
     * the targeted card into view. Called by [LosslessConnectBanner]'s
     * tap handler immediately before navigation, so the read happens
     * after the navigation has actually started.
     */
    fun requestSettingsLosslessFocus() {
        settingsDeepLinkController.request(com.stash.core.data.navigation.SettingsFocus.LOSSLESS)
    }

    /**
     * Begins playback of the given track list starting at [index].
     */
    fun playTrack(tracks: List<Track>, index: Int) {
        viewModelScope.launch {
            playerRepository.setQueue(tracks, index)
        }
    }

    /**
     * Loads the tracks for [playlist] and begins playback from the first
     * track. In streaming mode every member is playable (Kennyy resolves
     * on demand inside setQueue); in offline mode only on-disk tracks
     * are queued.
     */
    fun playPlaylist(playlist: Playlist) {
        viewModelScope.launch {
            val tracks = musicRepository.getTracksByPlaylist(playlist.id).first()
            val playable = queuePlayableTracks(tracks, streamingPreference.current())
            if (playable.isNotEmpty()) {
                playerRepository.setQueue(playable, startIndex = 0)
            }
        }
    }

    /**
     * Queue every undownloaded track in [playlist] for download. Surfaces
     * a snackbar with the count so the user knows it took effect even
     * though the download chain runs in WorkManager background context.
     */
    fun queueDownloadsForPlaylist(playlist: Playlist) {
        viewModelScope.launch {
            val count = musicRepository.queueDownloadsForPlaylist(playlist.id)
            val msg = when (count) {
                0 -> "Nothing to download — all tracks are already on disk."
                1 -> "Queued 1 track for download."
                else -> "Queued $count tracks for download."
            }
            _userMessages.tryEmit(msg)
        }
    }

    /**
     * Remove the on-disk file for every downloaded track in [playlist].
     * Rows stay (still streamable). Counterpart to [queueDownloadsForPlaylist].
     */
    fun removeDownloadsForPlaylist(playlist: Playlist) {
        viewModelScope.launch {
            val count = musicRepository.removeDownloadsForPlaylist(playlist.id)
            val msg = when (count) {
                0 -> "No downloads to remove."
                1 -> "Removed 1 download."
                else -> "Removed downloads for $count tracks."
            }
            _userMessages.tryEmit(msg)
        }
    }

    /**
     * Loads the tracks for [playlist] and appends each to the playback
     * queue. Streaming-mode-aware (same filter as [playPlaylist]).
     */
    fun addPlaylistToQueue(playlist: Playlist) {
        viewModelScope.launch {
            val tracks = musicRepository.getTracksByPlaylist(playlist.id).first()
            val playable = queuePlayableTracks(tracks, streamingPreference.current())
            playable.forEach { playerRepository.addToQueue(it) }
        }
    }

    /**
     * Deletes a playlist using the protected-playlist cascade. Tracks that
     * also belong to Liked Songs or an in-app custom playlist are kept —
     * only their membership in [playlist] is removed. If [alsoBlacklist]
     * is `true`, tracks that WERE deleted are also marked never-download-
     * again, so future syncs skip their identity forever.
     *
     * The [CascadeRemovalSummary] returned via [_lastCascadeSummary] drives
     * the post-delete Snackbar so users see exactly what happened.
     */
    fun deletePlaylistAndSongs(playlist: Playlist, alsoBlacklist: Boolean = false) {
        viewModelScope.launch {
            val summary = musicRepository.deletePlaylistWithCascade(
                playlistId = playlist.id,
                alsoBlacklist = alsoBlacklist,
            )
            _lastCascadeSummary.emit(summary)
        }
    }

    /**
     * Preview counts the UI uses in the delete-confirmation dialog:
     * how many tracks would actually be removed vs. kept due to
     * protected-playlist membership.
     */
    suspend fun previewPlaylistDelete(playlist: Playlist): DeletePreview {
        val tracks = musicRepository.getTracksByPlaylist(playlist.id).first()
        var protected = 0
        for (track in tracks) {
            // isTrackInProtectedPlaylist returns true if the track is in
            // Liked Songs / custom playlists OTHER than [playlist]. We
            // have to do the "other than" filtering here because the DAO
            // query doesn't exclude the source playlist.
            val inProtectedElsewhere = musicRepository.isTrackProtectedExcluding(
                trackId = track.id,
                excludePlaylistId = playlist.id,
            )
            if (inProtectedElsewhere) protected++
        }
        return DeletePreview(
            totalTracks = tracks.size,
            protectedCount = protected,
        )
    }

    private val _lastCascadeSummary =
        kotlinx.coroutines.flow.MutableSharedFlow<com.stash.core.data.repository.MusicRepository.CascadeRemovalSummary>(
            extraBufferCapacity = 1,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        )
    /** One-shot cascade summaries for the delete Snackbar. */
    val lastCascadeSummary: kotlinx.coroutines.flow.SharedFlow<com.stash.core.data.repository.MusicRepository.CascadeRemovalSummary> =
        _lastCascadeSummary.asSharedFlow()

    /** Preview counts shown in the playlist-delete confirmation dialog. */
    data class DeletePreview(
        val totalTracks: Int,
        val protectedCount: Int,
    ) {
        val willDelete: Int get() = totalTracks - protectedCount
    }

    /** Remove playlist from library without deleting its downloaded tracks. */
    fun removePlaylist(playlist: Playlist) {
        viewModelScope.launch {
            musicRepository.removePlaylist(playlist)
        }
    }

    /**
     * Creates a new empty custom playlist with the given [name]. Trims input
     * and no-ops if the trimmed name is blank. The new playlist will appear
     * in the Home Playlists section automatically (Room Flow).
     */
    fun createPlaylist(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            musicRepository.createPlaylist(trimmed)
        }
    }

    /**
     * Manually re-run the Stash Mix refresh worker for a single recipe (the
     * one whose materialized playlist is [playlistId]). Used by the long-
     * press "Refresh this mix" action on Stash Mix cards.
     *
     * Emits snackbar lifecycle messages via [userMessages]: "Refreshing X…"
     * on enqueue, then "Refreshed X" or "Refresh failed" on the worker's
     * terminal WorkInfo state. If the playlist is tagged `STASH_MIX` but no
     * recipe back-links it (data-integrity bug — menu shouldn't have
     * appeared), logs a warning and surfaces a "not linked to a recipe"
     * message instead of silently no-opping.
     */
    fun refreshMix(playlistId: Long) {
        viewModelScope.launch {
            val recipe = recipeDao.findByPlaylistId(playlistId)
            if (recipe == null) {
                // Data-integrity bug: playlist.type == STASH_MIX but no recipe
                // back-links it. Menu shouldn't have appeared. Log + soft-fail.
                Log.w(TAG, "refreshMix: no recipe back-links playlistId=$playlistId")
                _userMessages.tryEmit("Couldn't refresh \u2014 this mix isn't linked to a recipe")
                return@launch
            }

            _userMessages.tryEmit("Refreshing ${recipe.name}\u2026")

            // Build the request ourselves so we can capture its id for exact-
            // match WorkInfo filtering below. enqueueUniqueWork uses the same
            // unique name + REPLACE policy as StashMixRefreshWorker.enqueueOneTime,
            // mirroring lines 154-168 of that worker.
            val request = OneTimeWorkRequestBuilder<StashMixRefreshWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setInputData(workDataOf(StashMixRefreshWorker.KEY_RECIPE_ID to recipe.id))
                .build()
            val uniqueName = "${StashMixRefreshWorker.ONE_SHOT_WORK_NAME}_${recipe.id}"
            WorkManager.getInstance(context)
                .enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, request)

            // v0.9.20: fire the full discovery pipeline. queueDiscoveryForRecipe
            // inside the mix refresh worker enqueues new Last.fm candidates into
            // discovery_queue PENDING; this trigger processes them right now (subject
            // to user's DownloadNetworkMode pref) instead of waiting up to 24h for
            // the periodic schedule. The chain in StashDiscoveryWorker's tail will
            // fire DiscoveryDownloadWorker, which fires StashMixRefreshWorker again
            // at the end — the mix re-materializes with newly-downloaded survivors
            // without the user lifting another finger.
            val mode = downloadNetworkPreference.current()
            StashDiscoveryWorker.enqueueOneTime(context, mode)

            // Observe the unique-work Flow; filter to OUR enqueued request's id
            // so historical entries from earlier taps (or earlier sessions)
            // don't fire stale "Refreshed" Toasts.
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkFlow(uniqueName)
                .firstOrNull { infos ->
                    val ours = infos.firstOrNull { it.id == request.id } ?: return@firstOrNull false
                    when (ours.state) {
                        WorkInfo.State.SUCCEEDED -> {
                            _userMessages.tryEmit("Refreshed ${recipe.name}")
                            true
                        }
                        WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                            _userMessages.tryEmit("Refresh failed \u2014 try again later")
                            true
                        }
                        else -> false
                    }
                }
        }
    }

    /**
     * Delete a user-built Stash Mix: removes the materialized playlist (via
     * the protected-playlist cascade, NOT blacklisting), then deletes the
     * backing recipe row.
     *
     * Order matters: capture the recipe BEFORE the cascade runs, because
     * `deletePlaylistWithCascade` nulls the recipe's `playlist_id` FK
     * (SET_NULL), after which `findByPlaylistId` would no longer resolve it.
     */
    fun deleteCustomMix(playlist: Playlist) {
        viewModelScope.launch {
            val recipe = recipeDao.findByPlaylistId(playlist.id) // capture BEFORE cascade nulls the FK
            musicRepository.deletePlaylistWithCascade(playlist.id, alsoBlacklist = false)
            recipe?.let { recipeDao.deleteCustom(it.id) }
            _userMessages.tryEmit("Deleted “${playlist.name}”")
        }
    }

    /**
     * If [playlistId] backs a user (non-builtin) recipe whose last refresh
     * is older than [STALE_MIX_MS], kick a refresh. Fire-and-forget from the
     * mix-card tap so opening a stale custom mix transparently freshens it.
     * No-ops for builtin recipes (those refresh on the periodic schedule)
     * and for playlists with no backing recipe.
     */
    fun refreshMixIfStale(playlistId: Long) {
        viewModelScope.launch {
            val r = recipeDao.findByPlaylistId(playlistId) ?: return@launch
            val stale = (r.lastRefreshedAt ?: 0L) < System.currentTimeMillis() - STALE_MIX_MS
            if (!r.isBuiltin && stale) refreshMix(playlistId)
        }
    }

    /**
     * Resolve the recipe id backing [playlistId] asynchronously, invoking
     * [onResult] with the id (or null if no recipe back-links it). Used by
     * the context-sheet Edit action to build the MixBuilder nav arg, since
     * the playlist→recipe mapping isn't carried synchronously in uiState.
     */
    fun editRecipeId(playlistId: Long, onResult: (Long?) -> Unit) {
        viewModelScope.launch {
            onResult(recipeDao.findByPlaylistId(playlistId)?.id)
        }
    }

    /**
     * Plays every downloaded track across every daily mix from the given [source],
     * effectively merging all of that source's mixes into one continuous queue.
     * Passing null plays the combined pool from BOTH sources (Spotify first,
     * then YouTube) with per-track deduplication.
     *
     * Duplicates are removed via [distinctBy] so tracks appearing in multiple
     * mixes are only queued once. Tracks appear in the order their parent
     * playlists are returned by the repository.
     *
     * @param source The source whose mixes to play, or null to combine both.
     */
    fun playAllMixes(source: MusicSource?) {
        viewModelScope.launch {
            val state = uiState.value
            val mixes = when (source) {
                MusicSource.SPOTIFY -> state.spotifyMixes
                MusicSource.YOUTUBE -> state.youtubeMixes
                null -> state.spotifyMixes + state.youtubeMixes
                else -> return@launch
            }
            if (mixes.isEmpty()) return@launch

            val streamingOn = streamingPreference.current()
            val allTracks = mixes
                .flatMap { mix ->
                    musicRepository.getTracksByPlaylist(mix.id).first()
                }
                .let { tracks -> if (streamingOn) tracks else tracks.filter { it.filePath != null } }
                .distinctBy { it.id }

            if (allTracks.isNotEmpty()) {
                playerRepository.setQueue(allTracks, startIndex = 0)
            }
        }
    }

    /**
     * Loads liked songs from the specified [source] (or both if null) and
     * begins playback. Fetches actual playlist members from the join table
     * rather than all downloaded tracks.
     *
     * **Play order:** When [source] is null, Spotify liked songs are queued
     * first, then YouTube Music liked songs. Within each source, tracks are
     * ordered by the liked-playlist's insertion order. Duplicates (same track
     * ID appearing in both sources) are removed via `distinctBy`, keeping the
     * first occurrence (Spotify wins).
     *
     * @param source Specific source to play from, or null for combined
     *   Spotify + YouTube liked songs.
     */
    fun playLikedSongs(source: MusicSource? = null) {
        viewModelScope.launch {
            val state = uiState.value
            val playlistsToPlay = when (source) {
                MusicSource.SPOTIFY -> state.spotifyLikedPlaylists
                MusicSource.YOUTUBE -> state.youtubeLikedPlaylists
                else -> state.spotifyLikedPlaylists + state.youtubeLikedPlaylists
            }

            if (playlistsToPlay.isEmpty()) return@launch

            // Fetch each liked playlist's tracks in parallel and flatten
            val streamingOn = streamingPreference.current()
            val allTracks = playlistsToPlay
                .flatMap { playlist ->
                    musicRepository.getTracksByPlaylist(playlist.id).first()
                }
                .let { tracks -> if (streamingOn) tracks else tracks.filter { it.filePath != null } }
                .distinctBy { it.id }

            if (allTracks.isNotEmpty()) {
                playerRepository.setQueue(allTracks, startIndex = 0)
            }
        }
    }

    companion object {
        /** SharedPreferences file backing the one-time streaming disclosure flag. */
        private const val STREAMING_DISCLOSURE_PREFS = "streaming_disclosure"
        /** Boolean flag — true once the user has dismissed the disclosure dialog. */
        private const val STREAMING_DISCLOSURE_SEEN_KEY = "streaming_disclosure_seen"
        /** A custom mix older than this (24h) is refreshed on open. */
        private const val STALE_MIX_MS = 24L * 60 * 60 * 1000
    }
}

/**
 * Internal holder for the Room-backed flows Home reads so the top-level
 * combine treats them as a single positional arg. Track count + disk-
 * walked library size used to live here too; both belonged to the
 * relocated SyncStatusCard pipeline.
 */
private data class MusicData(
    val playlists: List<Playlist>,
    val recentlyAdded: List<Track>,
    val allTracks: List<Track>,
    /** Playlist ids backing user-defined (non-builtin) Stash Mix recipes. */
    val customMixPlaylistIds: Set<Long>,
    /** Custom-mix playlist ids still populating (show a "Building…" affordance). */
    val buildingMixIds: Set<Long>,
    /** Custom-mix playlist ids whose discovery finished with no tracks. */
    val emptyMixIds: Set<Long>,
)

