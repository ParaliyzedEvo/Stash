package com.stash.feature.home

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthState
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.core.data.db.dao.StashMixRecipeDao
import com.stash.core.data.lastfm.LastFmCredentials
import com.stash.core.data.lastfm.LastFmSessionPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.toDisplayStatus
import com.stash.core.data.sync.workers.StashMixRefreshWorker
import com.stash.core.media.PlayerRepository
import com.stash.core.model.MusicSource
import com.stash.core.model.Playlist
import com.stash.core.model.PlaylistType
import com.stash.core.model.SyncDisplayStatus
import com.stash.core.model.Track
import com.stash.data.download.files.LibrarySizeBreakdown
import com.stash.data.download.files.LibrarySizeHolder
import com.stash.data.download.lossless.AggregatorRateLimiter
import com.stash.data.download.lossless.LosslessRetryWorker
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.kennyy.KennyySource
import com.stash.data.download.lossless.qobuz.QobuzSource
import com.stash.feature.home.banner.WaitingForLosslessBannerState
import com.stash.feature.home.banner.bannerStateFor
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "HomeViewModel"

/**
 * ViewModel for the Home screen. Collects playlist, track, sync data,
 * and authentication state from [MusicRepository] and [TokenManager],
 * combining them into a single reactive [HomeUiState].
 *
 * All data sources are Flow-based so the UI updates automatically when:
 * - New tracks/playlists are inserted after a sync
 * - A sync completes and a new history record appears
 * - Spotify or YouTube auth state changes (connect/disconnect)
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
    private val tokenManager: TokenManager,
    private val lastFmSessionPreference: LastFmSessionPreference,
    private val lastFmCredentials: LastFmCredentials,
    private val listeningEventDao: ListeningEventDao,
    private val librarySizeHolder: LibrarySizeHolder,
    private val losslessPrefs: LosslessSourcePreferences,
    private val settingsDeepLinkController: com.stash.core.data.navigation.SettingsDeepLinkController,
    private val tipJarRepository: com.stash.core.data.tipjar.TipJarRepository,
    private val recipeDao: StashMixRecipeDao,
    private val downloadQueueDao: DownloadQueueDao,
    private val qobuzSource: QobuzSource,
    private val aggregatorRateLimiter: AggregatorRateLimiter,
    @ApplicationContext private val context: Context,
) : ViewModel() {

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
    }

    /** v0.9.13: callable from the screen on resume to keep the pill fresh. */
    fun refreshTipJarIfStale() {
        viewModelScope.launch {
            if (tipJarRepository.isStale()) tipJarRepository.refresh()
        }
    }

    /**
     * Derives [SyncStatusInfo] reactively from the latest sync history record.
     * Emits a default (empty) status when no sync has ever run.
     */
    private val syncStatusFlow = musicRepository.observeLatestSync().map { latestSync ->
        if (latestSync != null) {
            SyncStatusInfo(
                lastSyncTime = latestSync.startedAt.toEpochMilli(),
                nextSyncTime = latestSync.completedAt?.toEpochMilli()?.plus(6 * 3_600_000L),
                state = latestSync.status,
                displayStatus = latestSync.toDisplayStatus(),
            )
        } else {
            SyncStatusInfo(displayStatus = SyncDisplayStatus.Idle)
        }
    }

    /**
     * Combines the Room-backed data flows + the disk-walked library size
     * into a single intermediate holder. The DB column `file_size_bytes`
     * is bypassed for the Storage display because legacy libraries have it
     * stuck at 0 for thousands of rows. [librarySizeHolder] reflects disk
     * truth via the shared [LibrarySizeHolder] singleton (storage-mode-aware:
     * internal File walk OR SAF DocumentFile traversal). See that class for
     * lifecycle and walk-failure semantics.
     */
    private val musicDataFlow = combine(
        musicRepository.getAllPlaylists(),
        musicRepository.getRecentlyAdded(20),
        musicRepository.getTrackCount(),
        librarySizeHolder.size,
    ) { playlists, recentlyAdded, trackCount, librarySize ->
        MusicData(playlists, recentlyAdded, trackCount, librarySize)
    }

    private val sourceCountsFlow = combine(
        musicRepository.getSpotifyDownloadedCount(),
        musicRepository.getYouTubeDownloadedCount(),
    ) { spotify, youtube ->
        SourceCounts(spotify = spotify, youtube = youtube)
    }

    /**
     * Active sort for the Home Playlists grid. Starts at RECENT to match
     * the previous default (implicit `getAllPlaylists` ordering, now made
     * explicit as "most recently added first").
     */
    private val _playlistSortOrder = MutableStateFlow(PlaylistSortOrder.RECENT)

    /**
     * Last.fm banner prompt: only visible when the app has creds wired
     * (so it's meaningful to connect), the user hasn't completed auth,
     * AND there are local plays already queued — otherwise there's
     * nothing to nudge about. Once a session is saved, the Flow re-emits
     * null and the banner disappears on its own.
     */
    private val lastFmPromptFlow =
        if (!lastFmCredentials.isConfigured) {
            kotlinx.coroutines.flow.flowOf<LastFmPromptState?>(null)
        } else {
            combine(
                lastFmSessionPreference.session,
                listeningEventDao.pendingScrobbleCount(),
                lastFmSessionPreference.bannerDismissed,
            ) { session, pending, dismissed ->
                if (session == null && pending > 0 && !dismissed) {
                    LastFmPromptState(pendingCount = pending)
                } else {
                    null
                }
            }
        }

    /**
     * Lossless connect nudge: only visible when the user has not
     * enabled lossless AND has not dismissed the banner. Mirrors
     * [lastFmPromptFlow]'s shape and lifecycle — once dismissed,
     * the DataStore write makes the Flow re-emit null and the
     * banner disappears on its own.
     *
     * No `isConfigured` guard (unlike [lastFmPromptFlow]) because
     * lossless ships unconditionally — every install has the
     * feature. Last.fm's guard exists because that feature is
     * gated on app-level API credentials.
     */
    private val losslessPromptFlow = combine(
        losslessPrefs.enabled,
        losslessPrefs.bannerDismissed,
    ) { enabled, dismissed ->
        if (!enabled && !dismissed) LosslessPromptState else null
    }

    /**
     * Per-session dismissal flag for the "tracks waiting for lossless"
     * banner. Deliberately NOT DataStore-persisted: this banner surfaces
     * a transient remediation hint, not a long-term opt-out. Cleared on
     * process restart (the ViewModel dies with `viewModelScope`).
     */
    private val _waitingBannerDismissed = MutableStateFlow(false)

    /**
     * v0.9.17: kennyy circuit-breaker state, expressed as a Flow so the
     * banner picker can react to outage transitions.
     *
     * `AggregatorRateLimiter.stateOf` is suspending and not a Flow today,
     * so we re-read it whenever the circuit-reset SharedFlow signals a
     * transition. The `flow { }` builder seeds the initial value with a
     * one-shot suspending read so the banner picks the right state on
     * first emission. `distinctUntilChanged` collapses no-op re-emissions
     * (the SharedFlow can fire spuriously on stateOf reads — see the
     * `_circuitResetEvents.tryEmit(sourceId)` inside `stateOf` itself).
     */
    private val kennyyBrokenFlow: Flow<Boolean> = flow {
        emit(aggregatorRateLimiter.stateOf(KennyySource.SOURCE_ID).isCircuitBroken)
        aggregatorRateLimiter.circuitResetEvents
            .filter { it == KennyySource.SOURCE_ID }
            .collect {
                emit(aggregatorRateLimiter.stateOf(KennyySource.SOURCE_ID).isCircuitBroken)
            }
    }.distinctUntilChanged()

    /**
     * Combined banner state for the "tracks waiting for lossless" Home
     * banner. Drives [HomeUiState.waitingForLosslessBanner]. The
     * per-session dismissal flag is applied here so the rest of the UI
     * sees [WaitingForLosslessBannerState.Hidden] uniformly when dismissed.
     */
    private val bannerStateFlow: Flow<WaitingForLosslessBannerState> = combine(
        downloadQueueDao.waitingForLosslessCount(),
        losslessPrefs.captchaCookieValue,
        qobuzSource.lastKnownBadCookie,
        kennyyBrokenFlow,
        _waitingBannerDismissed,
    ) { count, cookie, lastBad, kennyyBroken, dismissed ->
        if (dismissed) {
            WaitingForLosslessBannerState.Hidden
        } else {
            bannerStateFor(
                count = count,
                currentCookie = cookie.orEmpty(),
                lastBadCookie = lastBad,
                kennyyBroken = kennyyBroken,
            )
        }
    }

    /**
     * Derives (spotifyConnected, youTubeConnected, lastFmPrompt,
     * losslessPrompt) from TokenManager + Last.fm session state +
     * lossless prefs. Bundled so the top-level combine stays at 5
     * inputs (the non-vararg ceiling).
     */
    private val authStateFlow = combine(
        tokenManager.spotifyAuthState,
        tokenManager.youTubeAuthState,
        lastFmPromptFlow,
        losslessPromptFlow,
    ) { spotify, youtube, lastFmPrompt, losslessPrompt ->
        AuthInfo(
            spotifyConnected = spotify is AuthState.Connected,
            youTubeConnected = youtube is AuthState.Connected,
            lastFmPrompt = lastFmPrompt,
            losslessPrompt = losslessPrompt,
        )
    }

    val uiState: StateFlow<HomeUiState> = combine(
        musicDataFlow,
        syncStatusFlow,
        authStateFlow,
        sourceCountsFlow,
        _playlistSortOrder,
        tipJarRepository.state,
        bannerStateFlow,
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val musicData = args[0] as MusicData
        @Suppress("UNCHECKED_CAST")
        val syncStatus = args[1] as SyncStatusInfo
        @Suppress("UNCHECKED_CAST")
        val authInfo = args[2] as AuthInfo
        @Suppress("UNCHECKED_CAST")
        val sourceCounts = args[3] as SourceCounts
        val playlistSortOrder = args[4] as PlaylistSortOrder
        val tipJar = args[5] as com.stash.core.data.tipjar.TipJarState
        val bannerState = args[6] as WaitingForLosslessBannerState
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

        HomeUiState(
            syncStatus = syncStatus.copy(
                totalTracks = musicData.trackCount,
                spotifyTracks = sourceCounts.spotify,
                youTubeTracks = sourceCounts.youtube,
                totalPlaylists = musicData.playlists.size,
                storageUsedBytes = musicData.librarySize.totalBytes,
                flacTracks = musicData.librarySize.losslessFileCount,
                flacStorageBytes = musicData.librarySize.losslessBytes,
            ),
            stashMixes = stashMixes,
            spotifyMixes = spotifyMixes,
            youtubeMixes = youtubeMixes,
            recentlyAdded = musicData.recentlyAdded,
            spotifyLikedPlaylists = spotifyLikedPlaylists,
            youtubeLikedPlaylists = youtubeLikedPlaylists,
            spotifyLikedCount = spotifyLikedCount,
            youtubeLikedCount = youtubeLikedCount,
            totalTracks = musicData.trackCount,
            totalStorageBytes = musicData.librarySize.totalBytes,
            playlists = otherPlaylists,
            playlistSortOrder = playlistSortOrder,
            isLoading = false,
            spotifyConnected = authInfo.spotifyConnected,
            youTubeConnected = authInfo.youTubeConnected,
            lastFmPrompt = authInfo.lastFmPrompt,
            losslessPrompt = authInfo.losslessPrompt,
            hasEverSynced = syncStatus.lastSyncTime != null,
            tipJar = tipJar,
            waitingForLosslessBanner = bannerState,
        )
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
     * Hide the Last.fm connect nudge on Home forever (until the user
     * connects then disconnects, which resets the flag). Writes through
     * to DataStore; the prompt Flow re-emits null on the next tick.
     */
    fun dismissLastFmBanner() {
        viewModelScope.launch {
            lastFmSessionPreference.setBannerDismissed(true)
        }
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
     * v0.9.17: dismiss the "tracks waiting for lossless" banner for
     * this session only. Cleared on process restart (the flag lives
     * on a `MutableStateFlow` inside `viewModelScope`, not DataStore).
     * Persistent dismissal would let users hide a real failure
     * indefinitely — that's the wrong default for a transient outage
     * surface.
     */
    fun dismissWaitingForLosslessBanner() {
        _waitingBannerDismissed.value = true
    }

    /**
     * v0.9.17: kick off a one-shot retry sweep for any rows currently
     * stuck in `WAITING_FOR_LOSSLESS`. Mirrors
     * [com.stash.data.download.lossless.LosslessRetryScheduler.enqueue]
     * exactly — same unique work name + KEEP policy, so a manual press
     * coalesces with any in-flight automatic sweep instead of doubling
     * the work.
     */
    fun onRetryDeferredRequested() {
        val request = OneTimeWorkRequestBuilder<LosslessRetryWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            LosslessRetryWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
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

    /** v0.9.13: Counterpart for the Last.fm connect nudge. */
    fun requestSettingsLastFmFocus() {
        settingsDeepLinkController.request(com.stash.core.data.navigation.SettingsFocus.LASTFM)
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
     * Loads the downloaded tracks for [playlist] and begins playback from the first track.
     * Only tracks with a non-null [Track.filePath] (i.e. downloaded) are queued.
     */
    fun playPlaylist(playlist: Playlist) {
        viewModelScope.launch {
            val tracks = musicRepository.getTracksByPlaylist(playlist.id).first()
            val downloaded = tracks.filter { it.filePath != null }
            if (downloaded.isNotEmpty()) {
                playerRepository.setQueue(downloaded, startIndex = 0)
            }
        }
    }

    /**
     * Loads the downloaded tracks for [playlist] and appends each to the playback queue.
     * Only tracks with a non-null [Track.filePath] (i.e. downloaded) are queued.
     */
    fun addPlaylistToQueue(playlist: Playlist) {
        viewModelScope.launch {
            val tracks = musicRepository.getTracksByPlaylist(playlist.id).first()
            val downloaded = tracks.filter { it.filePath != null }
            downloaded.forEach { playerRepository.addToQueue(it) }
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
     * v0.9.16: Manually re-run the Stash Mix refresh worker for a single
     * recipe (the one whose materialized playlist is [playlistId]). Used by
     * the long-press "Refresh this mix" action on Stash Mix cards. No-op if
     * the playlist doesn't belong to a Stash Mix recipe — the menu item is
     * already gated on `playlist.type == STASH_MIX`, so the lookup miss is
     * a defensive guard, not a user-facing error path.
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

            val allTracks = mixes
                .flatMap { mix ->
                    musicRepository.getTracksByPlaylist(mix.id).first()
                }
                .filter { it.filePath != null }
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
            val allTracks = playlistsToPlay
                .flatMap { playlist ->
                    musicRepository.getTracksByPlaylist(playlist.id).first()
                }
                .filter { it.filePath != null }
                .distinctBy { it.id }

            if (allTracks.isNotEmpty()) {
                playerRepository.setQueue(allTracks, startIndex = 0)
            }
        }
    }
}

/**
 * Internal holder for the four music-data Room flows so we can combine
 * them into a single upstream before the top-level combine.
 */
private data class MusicData(
    val playlists: List<Playlist>,
    val recentlyAdded: List<Track>,
    val trackCount: Int,
    val librarySize: LibrarySizeBreakdown,
)

/**
 * Bundled per-source counts that flow into [HomeUiState.syncStatus].
 * FLAC count + storage now come from disk via [MusicData.librarySize],
 * not from this struct — see KDoc on `musicDataFlow` for why.
 */
private data class SourceCounts(
    val spotify: Int,
    val youtube: Int,
)

/**
 * Internal holder for auth state so it can participate in the combine
 * as a single flow emission.
 */
private data class AuthInfo(
    val spotifyConnected: Boolean,
    val youTubeConnected: Boolean,
    val lastFmPrompt: LastFmPromptState?,
    val losslessPrompt: LosslessPromptState?,
)
