package com.stash.core.media.service

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.media3.common.MediaMetadata
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.prefs.CrossfadePreference
import com.stash.core.data.social.LikeCoordinator
import com.stash.core.media.R
import com.stash.core.media.equalizer.EqController
import com.stash.core.media.equalizer.LoudnessController
import com.stash.core.media.equalizer.StashRenderersFactory
import com.stash.core.media.equalizer.computeGain
import com.stash.core.media.PlaybackResumer
import com.stash.core.media.ResumePlayGate
import com.stash.core.media.ResumeStreamResolver
import com.stash.core.media.streaming.PrefetchOrchestrator
import com.stash.core.media.streaming.StashMediaSourceFactory
import com.stash.core.media.streaming.StreamingMediaSourceFactory
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.guava.future
import javax.inject.Inject
import androidx.core.app.ServiceCompat
import androidx.core.net.toUri
import com.stash.core.data.db.entity.TrackEntity

/**
 * Background playback service that hosts an [ExoPlayer] and exposes a [MediaSession]
 * for media-controller clients (e.g. system notification, Bluetooth, Android Auto).
 *
 * Custom session commands:
 * - [COMMAND_TOGGLE_SHUFFLE] -- toggles shuffle mode on/off
 * - [COMMAND_CYCLE_REPEAT]   -- cycles repeat mode: OFF -> ALL -> ONE -> OFF
 * - [COMMAND_TOGGLE_LIKE]    -- toggles Stash Liked Songs membership for the
 *   currently playing track. Surfaced as a heart icon in the system
 *   notification (expanded view) so the user can like/unlike from the
 *   lockscreen without opening Now Playing.
 */
@AndroidEntryPoint
class StashPlaybackService : MediaLibraryService() {

    @Inject lateinit var eqController: EqController
    @Inject lateinit var loudnessController: LoudnessController
    @Inject lateinit var trackDao: TrackDao
    @Inject lateinit var playlistDao: PlaylistDao
    @Inject lateinit var likeCoordinator: LikeCoordinator
    @Inject lateinit var prefetchOrchestrator: PrefetchOrchestrator
    @Inject lateinit var streamingMediaSourceFactory: StreamingMediaSourceFactory
    @Inject lateinit var playbackResumer: PlaybackResumer
    @Inject lateinit var resumeStreamResolver: ResumeStreamResolver
    @Inject lateinit var crossfadePreference: CrossfadePreference

    /**
     * Shared, interceptor-bearing OkHttp client (carries `AmzCaptchaInterceptor`).
     * Used by [StashMediaSourceFactory] to stream amz-origin items through an
     * authed [androidx.media3.datasource.okhttp.OkHttpDataSource].
     */
    @Inject lateinit var okHttpClient: okhttp3.OkHttpClient

    companion object {
        /** Custom command action for toggling shuffle mode. */
        const val COMMAND_TOGGLE_SHUFFLE = "com.stash.TOGGLE_SHUFFLE"

        /** Custom command action for cycling repeat mode. */
        const val COMMAND_CYCLE_REPEAT = "com.stash.CYCLE_REPEAT"

        /** Custom command action for toggling Stash Liked on the current track. */
        const val COMMAND_TOGGLE_LIKE = "com.stash.TOGGLE_LIKE"

        /** Extra key for the track ID in MediaMetadata extras. */
        const val EXTRA_TRACK_ID = "stash_track_id"

        /**
         * Extra key for the track's YouTube video id. Carried alongside
         * [EXTRA_TRACK_ID] so the notification heart can resolve a
         * v0.9.30 streaming-engine synthetic id to a real DB row via
         * `TrackDao.findByYoutubeId` (issue #105 fix).
         */
        const val EXTRA_TRACK_YOUTUBE_ID = "stash_track_youtube_id"

        /**
         * Extra key for the track's duration in milliseconds. Without
         * this, `MediaItem.toTrack` rehydrates streaming tracks with
         * `durationMs = 0` (the domain default), and `ensureTrackPersisted`
         * then inserts a Liked-Songs row with duration 0:00 (issue #105
         * follow-up: Liked Songs detail showed 0:00 for stream-only tracks).
         */
        const val EXTRA_TRACK_DURATION_MS = "stash_track_duration_ms"

        /**
         * Extra key for the track's `isStreamable` flag. Carried alongside
         * [EXTRA_TRACK_ID] so the auto-advance listener can decide whether
         * to silent-skip a track that the queue would play but the user
         * can't reach (stream-only + offline). v0.9.37.
         */
        const val EXTRA_TRACK_IS_STREAMABLE = "stash_track_is_streamable"

        // ── Streaming-format extras ───────────────────────────────────
        // Written into MediaMetadata.extras when a streaming-only track
        // gets its URL resolved (PlayerRepositoryImpl.buildMediaItemForTrack).
        // Read back by MediaItem.toTrack so the Now Playing screen can
        // show the actual codec/bit-depth/sample-rate Qobuz is serving
        // instead of the stale Room defaults (`fileFormat = "opus"`).
        /** Lowercase codec tag from Qobuz, e.g. `"flac"`, `"mp3"`. */
        const val EXTRA_STREAM_CODEC = "stash_stream_codec"
        /** Bits per sample (16, 24); absent for lossy. */
        const val EXTRA_STREAM_BIT_DEPTH = "stash_stream_bit_depth"
        /** Sample rate in Hz (44100, 96000…). */
        const val EXTRA_STREAM_SAMPLE_RATE = "stash_stream_sample_rate"
        /** Stated bitrate in kbps. */
        const val EXTRA_STREAM_BITRATE = "stash_stream_bitrate"
        /**
         * Resolver origin that produced this stream's URL —
         * `"kennyy"` / `"squid"` (Qobuz lossless) or `"youtube"`
         * (yt-dlp / InnerTube extraction, lossy). Read by Now Playing
         * to surface a "via YT" badge so the user knows when playback
         * has dropped from FLAC to YouTube-extracted audio.
         */
        const val EXTRA_STREAM_ORIGIN = "stash_stream_origin"

        private const val ROOT_ID = "ROOT"
        private const val PLAYLISTS_ID = "PLAYLISTS"
        private const val RECENTLY_ADDED_ID = "RECENTLY_ADDED"
        private const val PLAYLIST_PREFIX = "PLAYLIST_"
        private const val SHUFFLE_PLAY_PREFIX = "SHUFFLE_PLAY_"

        /**
         * How often the prefetch poll checks playback position against
         * the 60 %-played threshold. 5 s keeps the worst-case prefetch
         * latency below half a poll-interval after crossing the
         * threshold without burning unnecessary CPU on a wakelock-held
         * service. Pulling this lower wastes battery; higher than ~10 s
         * risks crossing the threshold too late on short (<60 s) tracks.
         */
        private const val PREFETCH_POLL_INTERVAL_MS = 5_000L

        /**
         * Crossfade arm-poll cadence. Finer than the prefetch poll so the fade
         * fires within the chosen 1–12 s window before track end; 250 ms keeps
         * worst-case arm jitter to a quarter-second.
         */
        private const val CROSSFADE_POLL_INTERVAL_MS = 250L
    }

    private var mediaSession: MediaLibrarySession? = null

    // Service-scoped CoroutineScope for the like-state observer + toggle
    // suspending calls. Cancelled in onDestroy so the observer doesn't leak
    // when the service stops.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var likeObserverJob: Job? = null

    /**
     * Periodic position-poll that drives [PrefetchOrchestrator]. Started
     * when playback becomes active, cancelled when it stops or when a
     * track transition occurs (a new poll is started for the next track).
     * Runs on the service's main scope so player reads stay on the
     * required thread.
     */
    private var prefetchPollJob: Job? = null

    /**
     * Drives the auto-advance crossfade. Built in [onCreate] once player A
     * exists; null until then. Player B is built lazily on first fade.
     */
    private var crossfadeController: CrossfadeController? = null

    /**
     * Dedicated ~250 ms poll feeding [crossfadeController]. Separate from
     * [prefetchPollJob] (which ticks every 5 s — too coarse to catch a 1–12 s
     * fade-arm window). Cheap no-op when crossfade is off.
     */
    private var crossfadePollJob: Job? = null

    /**
     * Forces playback to start when a real *play* request restores a queue
     * onto the empty player. Media3 is supposed to auto-play after
     * resumption on a play request, but from a warm process that auto-play
     * sometimes doesn't take and the queue loads paused. Armed in
     * [onPlaybackResumption] and consumed in [onTimelineChanged] once the
     * restored timeline lands. Stays closed for boot-time notification
     * population so the device never starts playing on its own after a
     * reboot. See [ResumePlayGate].
     */
    private val resumePlayGate = ResumePlayGate()

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        // Generate an explicit audio session ID BEFORE building the player.
        // ExoPlayer.audioSessionId returns 0 (global mix) by default until playback starts,
        // which causes audio effect creation to fail with Error -3.
        // By generating our own ID and passing it to the builder, the effects can attach immediately.
        // Generate a dedicated audio session ID so audio effects can attach immediately.
        val audioManager = getSystemService(android.media.AudioManager::class.java)
        val audioSessionId = audioManager.generateAudioSessionId()
        android.util.Log.i("StashPlayback", "Generated audio session ID: $audioSessionId")

        // Optimised buffer for local music playback: larger buffers eliminate
        // micro-stutters from storage I/O; lower playback thresholds keep
        // start-up snappy.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 30_000,
                /* maxBufferMs = */ 60_000,
                /* bufferForPlaybackMs = */ 1_000,
                /* bufferForPlaybackAfterRebufferMs = */ 2_000,
            )
            .build()

        // Route ONLY YouTube-origin streaming items through the refresh chain
        // (RefreshingDataSource → yt-dlp on 403). Background queue-fill seeds
        // the timeline with cheap InnerTube/iOS placeholder URLs that 403 past
        // ~1 MB; without this they surface as onPlayerError and skip-storm the
        // queue. Lossless (Kennyy/Squid) and local/downloaded items stay on the
        // default factory, unchanged.
        val mediaSourceFactory = StashMediaSourceFactory(
            context = this,
            streamingFactory = streamingMediaSourceFactory,
            streamingTrackId = { item ->
                val scheme = item.localConfiguration?.uri?.scheme?.lowercase()
                val origin = item.mediaMetadata.extras?.getString(EXTRA_STREAM_ORIGIN)
                val trackId = item.mediaMetadata.extras?.getLong(EXTRA_TRACK_ID, -1L) ?: -1L
                if ((scheme == "http" || scheme == "https") && origin == "youtube" && trackId > 0L) {
                    trackId
                } else {
                    null
                }
            },
            // amz-origin http(s) items stream through an authed OkHttpDataSource
            // (shared client carries AmzCaptchaInterceptor) so the x-captcha-token
            // header rides every range request and is re-minted mid-stream.
            isAmzOrigin = { item ->
                val scheme = item.localConfiguration?.uri?.scheme?.lowercase()
                val origin = item.mediaMetadata.extras?.getString(EXTRA_STREAM_ORIGIN)
                (scheme == "http" || scheme == "https") && origin == "amz"
            },
            amzHttpClient = okHttpClient,
        )

        // Player A — the sole MediaSession/queue owner. Holds audio focus,
        // becoming-noisy and wake-lock. Player B (built lazily by the crossfade
        // controller) reuses this same builder with handleAudioFocus = false.
        val player = buildExoPlayer(
            handleAudioFocus = true,
            mediaSourceFactory = mediaSourceFactory,
            audioAttributes = audioAttributes,
            loadControl = loadControl,
        )

        // Set the pre-generated session ID on the player (A only)
        player.audioSessionId = audioSessionId

        crossfadeController = CrossfadeController(
            playerA = player,
            buildPlayerB = {
                buildExoPlayer(
                    handleAudioFocus = false,
                    mediaSourceFactory = mediaSourceFactory,
                    audioAttributes = audioAttributes,
                    loadControl = loadControl,
                )
            },
            crossfadePreference = crossfadePreference,
            scope = serviceScope,
        )

        // Set session activity so tapping the media notification opens the app.
        // The intent targets the app's launcher activity via the package's launch intent.
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val sessionActivity = if (launchIntent != null) {
            android.app.PendingIntent.getActivity(
                this, 0, launchIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
        } else null

        val sessionBuilder = MediaLibrarySession.Builder(this, player, StashSessionCallback())
        if (sessionActivity != null) {
            sessionBuilder.setSessionActivity(sessionActivity)
        }
        val session = sessionBuilder.build()

        mediaSession = session

        // Per-track wiring on every transition:
        //   1. Heart-button notification icon (filled vs. outlined) so the
        //      lockscreen reflects the new track's Stash-Liked state. The
        //      per-track observe loop inside [refreshLikeButton] keeps the
        //      icon in sync if the user toggles like from elsewhere
        //      (Now Playing, Library, etc.) while audio is playing.
        //   2. Loudness normalisation: pull the new track's measured LUFS /
        //      true-peak from the DB and push the computed per-track gain
        //      to [LoudnessController]. The DSP layer reads the controller
        //      state and ramps to the new target via LoudnessGainProcessor.
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // A user skip (SEEK) or queue change (PLAYLIST_CHANGED) cancels an
                // in-flight fade and hard-cuts; the controller's own fade-advance
                // seek is exempt (consumeSelfSeek). Natural auto-advance (AUTO) and
                // repeat (REPEAT) never cancel.
                crossfadeController?.let { controller ->
                    when (reason) {
                        Player.MEDIA_ITEM_TRANSITION_REASON_SEEK ->
                            if (!controller.consumeSelfSeek()) controller.cancelFade()
                        Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED ->
                            controller.cancelFade()
                    }
                }
                updateCustomLayout()
                onTrackTransitionForLoudness(mediaItem)
                // Every transition gives the orchestrator a fresh
                // "attempted" budget so the new "next" track can be
                // prefetched even if the previous next-id was the same
                // track id we already burned an attempt on (e.g.
                // REPEAT_ONE loops, manual skip-back).
                prefetchOrchestrator.resetSession()
                // Restart the polls against the new current item.
                if (player.isPlaying) {
                    startPrefetchPoll(player)
                    startCrossfadePoll(player)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    startPrefetchPoll(player)
                    startCrossfadePoll(player)
                    crossfadeController?.onResume()
                } else {
                    prefetchPollJob?.cancel()
                    prefetchPollJob = null
                    crossfadePollJob?.cancel()
                    crossfadePollJob = null
                    crossfadeController?.onPause()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // A errored (e.g. an unresolved next URL that slipped past the
                // arm guard) — abandon any fade so B doesn't linger and A
                // returns to full volume for the normal error-recovery path.
                crossfadeController?.cancelFade()
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                // When a play-triggered resumption lands its restored queue
                // on the (previously empty) player, force playback to start.
                // The gate keeps this scoped to resumption only — normal
                // in-app setQueue never arms it, and boot-time notification
                // population (isForPlayback = false) leaves it closed so the
                // device doesn't auto-play after a reboot. Idempotent with
                // Media3's own post-resumption play().
                when (resumePlayGate.onTimelineChanged(
                    timeline.windowCount,
                    player.playbackState == Player.STATE_IDLE,
                )) {
                    ResumePlayGate.Action.PREPARE_THEN_PLAY -> {
                        player.prepare()
                        player.play()
                    }
                    ResumePlayGate.Action.PLAY -> player.play()
                    ResumePlayGate.Action.NONE -> Unit
                }
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                updateCustomLayout()
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                updateCustomLayout()
            }
        })
        updateCustomLayout()
    }

    /**
     * Builds an [ExoPlayer] with Stash's renderer/EQ chain, media-source
     * factory and load control. Player A passes `handleAudioFocus = true` and
     * additionally owns becoming-noisy + wake-lock; the crossfade partner B
     * passes `false` and owns neither (only A drives the session).
     */
    @OptIn(UnstableApi::class)
    private fun buildExoPlayer(
        handleAudioFocus: Boolean,
        mediaSourceFactory: StashMediaSourceFactory,
        audioAttributes: AudioAttributes,
        loadControl: DefaultLoadControl,
    ): ExoPlayer {
        val builder = ExoPlayer.Builder(this)
            .setRenderersFactory(StashRenderersFactory(this, eqController, loudnessController))
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, handleAudioFocus)
        if (handleAudioFocus) {
            builder.setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_LOCAL)
        }
        return builder.build()
    }

    /**
     * ~250 ms poll feeding [CrossfadeController.onProgress] so the fade can arm
     * within the 1–12 s window before track end. Modeled on [startPrefetchPoll]
     * but finer-grained; runs only while playing and no-ops cheaply when
     * crossfade is disabled.
     */
    @OptIn(UnstableApi::class)
    private fun startCrossfadePoll(player: Player) {
        crossfadePollJob?.cancel()
        val controller = crossfadeController ?: return
        crossfadePollJob = serviceScope.launch {
            while (isActive && player.isPlaying) {
                val nextIndex = player.nextMediaItemIndex
                val hasResolvedNext = nextIndex != C.INDEX_UNSET &&
                    runCatching { player.getMediaItemAt(nextIndex) }
                        .getOrNull()
                        ?.let(::isNextResolved) == true
                controller.onProgress(
                    positionMs = player.currentPosition,
                    durationMs = player.duration,
                    hasResolvedNext = hasResolvedNext,
                    repeatMode = player.repeatMode,
                )
                delay(CROSSFADE_POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Whether [item] is playable right now (so a fade into it won't error).
     * Local/downloaded items (file/content URIs) always are. A streaming item
     * is only playable once a resolver has produced its URL, which stamps
     * [EXTRA_STREAM_ORIGIN] — placeholder queue-fill http(s) URLs lack it.
     */
    private fun isNextResolved(item: MediaItem): Boolean {
        val scheme = item.localConfiguration?.uri?.scheme?.lowercase() ?: return false
        return if (scheme == "http" || scheme == "https") {
            item.mediaMetadata.extras?.getString(EXTRA_STREAM_ORIGIN) != null
        } else {
            true
        }
    }

    /**
     * Posts a lightweight "Resuming…" foreground notification so a
     * media-button-triggered resumption (started via startForegroundService
     * on a dead process) satisfies the OS ~5s "must call startForeground"
     * deadline even when the current track needs a slow stream-URL resolve.
     *
     * Reuses Media3's default notification id so the real media notification
     * replaces this placeholder seamlessly once playback starts — no
     * lingering "Resuming…" entry.
     */
    @OptIn(UnstableApi::class)
    private fun showResumingForegroundNotification() {
        val channelId = "stash_playback_resume"
        val nm = getSystemService(android.app.NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            nm.getNotificationChannel(channelId) == null
        ) {
            nm.createNotificationChannel(
                android.app.NotificationChannel(
                    channelId,
                    getString(R.string.resuming_channel_name),
                    android.app.NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.resuming_notification_title))
            .setOngoing(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .build()
        val id = androidx.media3.session.DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(
                id,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(id, notification)
        }
    }

    /**
     * Cancels any existing prefetch poll and starts a new one that ticks
     * every [PREFETCH_POLL_INTERVAL_MS]. Each tick reads the current
     * playback position and the *next* queue item's mediaId off the
     * player on the main thread, then hands those to
     * [PrefetchOrchestrator.onPlaybackProgress] which decides whether to
     * launch a resolve.
     *
     * The poll runs as long as the player reports `isPlaying = true`.
     * `onIsPlayingChanged(false)` cancels it; `onMediaItemTransition`
     * cancels + restarts it so a new "next" target is picked up
     * immediately after auto-advance.
     */
    @OptIn(UnstableApi::class)
    private fun startPrefetchPoll(player: Player) {
        prefetchPollJob?.cancel()
        prefetchPollJob = serviceScope.launch {
            while (isActive && player.isPlaying) {
                val nextIndex = player.nextMediaItemIndex
                val nextId = if (nextIndex == C.INDEX_UNSET) {
                    null
                } else {
                    runCatching { player.getMediaItemAt(nextIndex) }
                        .getOrNull()
                        ?.mediaId
                        ?.toLongOrNull()
                }
                prefetchOrchestrator.onPlaybackProgress(
                    scope = serviceScope,
                    nextTrackId = nextId,
                    positionMs = player.currentPosition,
                    durationMs = player.duration,
                )
                delay(PREFETCH_POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Test-visible per-transition hook for loudness gain. Resolves the
     * media item to a track row (matching the heart-button's
     * `mediaId.toLongOrNull()` convention) and pushes the computed gain
     * to [LoudnessController]. No-ops when the id can't be parsed, the
     * row is missing, or the track has no measured loudness yet — in
     * those cases [computeGain] returns 0 dB which is the safe bypass.
     *
     * Visibility is `internal` so unit tests can invoke the hook directly
     * without booting a full [MediaLibraryService] / [ExoPlayer].
     */
    internal fun onTrackTransitionForLoudness(mediaItem: MediaItem?) {
        val trackId = mediaItem?.mediaId?.toLongOrNull() ?: return
        serviceScope.launch {
            val track = trackDao.getById(trackId) ?: return@launch
            val gainDb = computeGain(track.loudnessLufs, track.truePeakDbfs)
            loudnessController.setCurrentTrackGain(gainDb)
        }
    }

    private var lastTrackId: Long? = null
    private var lastIsLiked: Boolean = false

    /**
     * Updates the MediaSession custom layout with the heart, shuffle, and repeat icons.
     * Starts a database observer for the current track's like state. For player-state
     * changes (repeat/shuffle) on the same track, it refreshes the layout using the
     * last known like state to avoid redundant DB observer restarts.
     */
    @OptIn(UnstableApi::class)
    private fun updateCustomLayout() {
        val session = mediaSession ?: return
        val player = session.player
        val mediaItem = player.currentMediaItem
        val trackId = mediaItem?.mediaId?.toLongOrNull()
        val youtubeId = mediaItem?.mediaMetadata?.extras?.getString(EXTRA_TRACK_YOUTUBE_ID)

        if (trackId == null) {
            likeObserverJob?.cancel()
            lastTrackId = null
            lastIsLiked = false
            session.setCustomLayout(ImmutableList.of())
            return
        }

        if (trackId != lastTrackId) {
            likeObserverJob?.cancel()
            lastTrackId = trackId
            // Reset liked state and push an initial layout immediately for the new track.
            // This prevents the previous track's heart state from lingering until the
            // DB observer emits for the first time.
            lastIsLiked = false
            pushLayout(session, player, false)

            likeObserverJob = serviceScope.launch {
                // The id-keyed observer doesn't match streaming-engine
                // synthetic ids; fall back to youtube_id so the heart
                // tracks Room's truth even for stream-only tracks. The
                // service stays in sync with Now Playing's optimistic
                // flip because `MusicRepository.ensureTrackPersisted`
                // writes a row with the same `youtube_id` on like.
                val likeFlow = trackDao.observeLikeState(trackId)
                    .flatMapLatest { state ->
                        if (state != null || youtubeId.isNullOrBlank()) flowOf(state)
                        else trackDao.observeLikeStateByYoutubeId(youtubeId)
                    }
                likeFlow.collect { state ->
                    val isLiked = state?.stashLikedAt != null
                    if (isLiked != lastIsLiked) {
                        lastIsLiked = isLiked
                        pushLayout(session, player, lastIsLiked)
                    }
                }
            }
        } else {
            pushLayout(session, player, lastIsLiked)
        }
    }

    @OptIn(UnstableApi::class)
    private fun pushLayout(session: MediaSession, player: Player, isLiked: Boolean) {
        val layout = ImmutableList.of(
            buildLikeButton(isLiked),
            buildRepeatButton(player.repeatMode)
        )
        session.setCustomLayout(layout)
    }

    @OptIn(UnstableApi::class)
    private fun buildRepeatButton(repeatMode: Int): CommandButton {
        val iconRes = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> R.drawable.ic_repeat_off
            Player.REPEAT_MODE_ONE -> R.drawable.ic_repeat_one
            else -> R.drawable.ic_repeat
        }
        val displayNameRes = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> R.string.notification_action_repeat_off
            Player.REPEAT_MODE_ALL -> R.string.notification_action_repeat_all
            Player.REPEAT_MODE_ONE -> R.string.notification_action_repeat_one
            else -> R.string.notification_action_repeat_off
        }
        return CommandButton.Builder()
            .setDisplayName(getString(displayNameRes))
            .setIconResId(iconRes)
            .setSessionCommand(
                SessionCommand(COMMAND_CYCLE_REPEAT, android.os.Bundle.EMPTY),
            )
            .build()
    }

    @OptIn(UnstableApi::class)
    private fun buildLikeButton(isLiked: Boolean): CommandButton {
        val iconRes = if (isLiked) {
            R.drawable.ic_notification_heart_filled
        } else {
            R.drawable.ic_notification_heart_outlined
        }
        val displayNameRes = if (isLiked) {
            R.string.notification_action_unlike
        } else {
            R.string.notification_action_like
        }
        return CommandButton.Builder()
            .setDisplayName(getString(displayNameRes))
            .setIconResId(iconRes)
            .setSessionCommand(
                SessionCommand(COMMAND_TOGGLE_LIKE, android.os.Bundle.EMPTY),
            )
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaSession
    }

    @OptIn(UnstableApi::class)
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        likeObserverJob?.cancel()
        prefetchPollJob?.cancel()
        crossfadePollJob?.cancel()
        crossfadeController?.release()
        crossfadeController = null
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    /**
     * Resolves the Room PK for a Like-toggle from MediaSession state. The
     * `mediaId` may be a v0.9.30 streaming-engine synthetic id
     * (`videoId.hashCode().toLong()`) that doesn't exist in `tracks` —
     * passing it to a FK-bearing write crashes the service. Mirrors the
     * upsert pattern in `MusicRepositoryImpl.ensureTrackPersisted` /
     * `SearchDownloadCoordinator.upsertSearchTrack`.
     *
     * @return the real `tracks.id` to use; never returns a synthetic id.
     * @throws IllegalStateException when no identity info is recoverable
     *   from the MediaItem (i.e. neither a real candidate id nor a
     *   youtubeId / title / artist that maps to one).
     */
    private suspend fun resolveTrackIdForLike(
        candidateId: Long,
        youtubeId: String?,
        metadata: MediaMetadata,
    ): Long {
        // Real Room PK already? Cheap exit.
        if (candidateId > 0L && trackDao.getById(candidateId) != null) return candidateId

        // YouTube id is the most reliable secondary identity.
        if (!youtubeId.isNullOrBlank()) {
            trackDao.findByYoutubeId(youtubeId)?.let { return it.id }
        }

        val title = metadata.title?.toString().orEmpty()
        val artist = metadata.artist?.toString().orEmpty()
        val album = metadata.albumTitle?.toString().orEmpty()
        val albumArtist = metadata.albumArtist?.toString().orEmpty()
        val cTitle = canonicalizeIdentity(title)
        val cArtist = canonicalizeIdentity(artist)

        if (cTitle.isNotBlank() && cArtist.isNotBlank()) {
            trackDao.findByCanonicalIdentity(cTitle, cArtist)?.let { return it.id }
        }

        check(title.isNotBlank() && artist.isNotBlank()) {
            "Cannot resolve track id for like: no youtubeId and no title/artist metadata"
        }

        return trackDao.insert(
            TrackEntity(
                title = title,
                artist = artist,
                album = album,
                albumArtist = albumArtist,
                youtubeId = youtubeId,
                canonicalTitle = cTitle,
                canonicalArtist = cArtist,
                source = com.stash.core.model.MusicSource.YOUTUBE,
                isStreamable = true,
                isDownloaded = false,
            )
        )
    }

    private fun canonicalizeIdentity(s: String): String =
        s.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    // ---- MediaLibrarySession.Callback ----

    private inner class StashSessionCallback : MediaLibrarySession.Callback {

        fun TrackEntity.toMediaMetadata(): MediaMetadata {
            return MediaMetadata.Builder()
                .setTitle(this.title)
                .setArtist(this.artist)
                .setAlbumTitle(this.album)
                .setArtworkUri(this.albumArtUrl?.toUri() ?: this.albumArtPath?.toUri())
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setExtras(android.os.Bundle().apply {
                    putLong(EXTRA_TRACK_ID, id)
                })
                .build()
        }

        private suspend fun resolveMediaItem(item: MediaItem): MediaItem {
            // 1. If it's already a fully resolved item (has URI), use it
            if (item.localConfiguration?.uri != null) {
                return item
            }

            // 2. If it's a library item (has mediaId), resolve it from DB.
            // For downloaded tracks, set the file URI. Streaming-only items
            // are pre-resolved upstream in PlayerRepositoryImpl.setQueue
            // (semaphore-capped Kennyy lookups), so by the time they reach
            // here they already carry an http(s) URI and short-circuit on
            // the check above. If we ever see a streaming-only item with
            // no URI here, leaving URI absent will surface as an
            // onPlayerError → skip-next recovery.
            val trackId = item.mediaId.toLongOrNull()
            if (trackId != null) {
                val track = trackDao.getById(trackId)
                if (track != null) {
                    val builder = item.buildUpon()
                        .setMediaMetadata(track.toMediaMetadata())
                    val localPath = track.filePath
                    if (!localPath.isNullOrBlank()) {
                        val fileUri = if (localPath.startsWith("/")) {
                            "file://$localPath".toUri()
                        } else {
                            localPath.toUri()
                        }
                        builder.setUri(fileUri)
                    }
                    return builder.build()
                }
            }

            // 3. Fallback to request metadata URI (with security check)
            val uri = item.requestMetadata.mediaUri
            if (uri != null) {
                val scheme = uri.scheme
                if (scheme == "file" || scheme == "android.resource" || scheme == "content") {
                    return item.buildUpon().setUri(uri).build()
                }
            }

            return item
        }

        @OptIn(UnstableApi::class)
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            return serviceScope.future {
                if (mediaItems.size == 1 && mediaItems[0].mediaId.startsWith(SHUFFLE_PLAY_PREFIX)) {
                    val playlistId = mediaItems[0].mediaId.removePrefix(SHUFFLE_PLAY_PREFIX).toLongOrNull()
                    if (playlistId != null) {
                        val tracks = playlistDao.getTracksForPlaylist(playlistId)
                        // v0.9.37: include streamable tracks so stream-only Mix entries are
                        // playable. Downloaded-only filter would silently drop them.
                        val items = tracks.filter{track -> track.isDownloaded || track.isStreamable}.map { track ->
                            MediaItem.Builder()
                                .setMediaId(track.id.toString())
                                .setUri(track.filePath ?: "")
                                .setMediaMetadata(track.toMediaMetadata())
                                .build()
                        }.shuffled()

                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            mediaSession.player.shuffleModeEnabled = true
                        }

                        return@future MediaSession.MediaItemsWithStartPosition(
                            items,
                            0,
                            C.TIME_UNSET
                        )
                    }
                }
                // Browse-tap on a playlist/recently-added child (#154/#173):
                // the mediaId carries its parent, so rebuild the whole parent
                // as the queue, starting at the tapped track — mirroring the
                // SHUFFLE_PLAY_ expansion above, but in order and unshuffled.
                if (mediaItems.size == 1) {
                    val parsed = AutoBrowseQueue.parse(mediaItems[0].mediaId)
                    if (parsed != null) {
                        val plan = AutoBrowseQueue.queuePlan(
                            tracksForBrowseParent(parsed.parentId),
                            tappedTrackId = parsed.trackId,
                        )
                        if (plan.tracks.isNotEmpty()) {
                            val items = plan.tracks.map { track ->
                                MediaItem.Builder()
                                    .setMediaId(track.id.toString())
                                    .setUri(track.filePath ?: "")
                                    .setMediaMetadata(track.toMediaMetadata())
                                    .build()
                            }
                            // An explicit in-order tap means in-order playback;
                            // shuffle stays reachable via the Shuffle Play entry.
                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                mediaSession.player.shuffleModeEnabled = false
                            }
                            return@future MediaSession.MediaItemsWithStartPosition(
                                items,
                                plan.startIndex,
                                C.TIME_UNSET,
                            )
                        }
                    }
                }
                val resolvedItems = mediaItems.map { resolveMediaItem(it) }
                MediaSession.MediaItemsWithStartPosition(resolvedItems, startIndex, startPositionMs)
            }
        }

        /**
         * Loads the track list backing an Auto browse parent id — the same
         * rows (and order) `onGetChildren` listed for it.
         */
        private suspend fun tracksForBrowseParent(parentId: String): List<TrackEntity> =
            when {
                parentId.startsWith(PLAYLIST_PREFIX) ->
                    parentId.removePrefix(PLAYLIST_PREFIX).toLongOrNull()
                        ?.let { playlistDao.getTracksForPlaylist(it) }
                        ?: emptyList()
                parentId == RECENTLY_ADDED_ID -> trackDao.getRecentlyAdded(20).first()
                else -> emptyList()
            }

        @OptIn(UnstableApi::class)
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> {
            return serviceScope.future {
                if (mediaItems.size == 1 && mediaItems[0].mediaId.startsWith(SHUFFLE_PLAY_PREFIX)) {
                    val playlistId = mediaItems[0].mediaId.removePrefix(SHUFFLE_PLAY_PREFIX).toLongOrNull()
                    if (playlistId != null) {
                        return@future playlistDao.getTracksForPlaylist(playlistId).map { track ->
                            MediaItem.Builder()
                                .setMediaId(track.id.toString())
                                .setUri(track.filePath ?: "")
                                .setMediaMetadata(track.toMediaMetadata())
                                .build()
                        }.shuffled()
                    }
                }
                mediaItems.map { item ->
                    // A browse-child id in an ADD context (e.g. "add to queue")
                    // means just that one track — strip the parent envelope and
                    // resolve it as a normal library item. Queue expansion only
                    // happens on a SET (onSetMediaItems above).
                    val parsed = AutoBrowseQueue.parse(item.mediaId)
                    val normalized = if (parsed != null) {
                        item.buildUpon().setMediaId(parsed.trackId.toString()).build()
                    } else {
                        item
                    }
                    resolveMediaItem(normalized)
                }
            }
        }

        @OptIn(UnstableApi::class)
        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            android.util.Log.d("StashPlayback", "onGetItem: id=$mediaId client=${browser.packageName}")
            return when (mediaId) {
                ROOT_ID -> {
                    val rootItem = MediaItem.Builder()
                        .setMediaId(ROOT_ID)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle("Stash Root")
                                .setIsBrowsable(true)
                                .setIsPlayable(false)
                                .build(),
                        )
                        .build()
                    Futures.immediateFuture(LibraryResult.ofItem(rootItem, null))
                }
                PLAYLISTS_ID -> {
                    val playlistsItem = MediaItem.Builder()
                        .setMediaId(PLAYLISTS_ID)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle("Playlists")
                                .setIsBrowsable(true)
                                .setIsPlayable(false)
                                .build(),
                        )
                        .build()
                    Futures.immediateFuture(LibraryResult.ofItem(playlistsItem, null))
                }
                else -> {
                    // Try to resolve track or playlist
                    serviceScope.future {
                        // Browse-child ids (AUTOQ_…) resolve to their track,
                        // keeping the parent-carrying mediaId intact so a
                        // subsequent tap still expands the playlist (#154/#173).
                        val trackId = AutoBrowseQueue.parse(mediaId)?.trackId
                            ?: mediaId.toLongOrNull()
                        if (trackId != null) {
                            val track = trackDao.getById(trackId)
                            if (track != null) {
                                return@future LibraryResult.ofItem(
                                    MediaItem.Builder()
                                        .setMediaId(
                                            if (mediaId.startsWith(AutoBrowseQueue.PREFIX)) {
                                                mediaId
                                            } else {
                                                track.id.toString()
                                            },
                                        )
                                        .setUri(track.filePath ?: "")
                                        .setMediaMetadata(
                                            track.toMediaMetadata(),
                                        )
                                        .build(),
                                    null,
                                )
                            }
                        }
                        if (mediaId.startsWith(PLAYLIST_PREFIX)) {
                            val playlistId = mediaId.removePrefix(PLAYLIST_PREFIX).toLongOrNull()
                            if (playlistId != null) {
                                val playlist = playlistDao.getById(playlistId)
                                if (playlist != null) {
                                    return@future LibraryResult.ofItem(
                                        MediaItem.Builder()
                                            .setMediaId(mediaId)
                                            .setMediaMetadata(
                                                MediaMetadata.Builder()
                                                    .setTitle(playlist.name)
                                                    .setIsBrowsable(true)
                                                    .setIsPlayable(false)
                                                    .build(),
                                            )
                                            .build(),
                                        null,
                                    )
                                }
                            }
                        }
                        if (mediaId.startsWith(SHUFFLE_PLAY_PREFIX)) {
                            val playlistId = mediaId.removePrefix(SHUFFLE_PLAY_PREFIX).toLongOrNull()
                            if (playlistId != null) {
                                val playlist = playlistDao.getById(playlistId)
                                if (playlist != null) {
                                    return@future LibraryResult.ofItem(
                                        MediaItem.Builder()
                                            .setMediaId(mediaId)
                                            .setMediaMetadata(
                                                MediaMetadata.Builder()
                                                    .setTitle(getString(R.string.shuffle_play))
                                                    .setArtworkUri("android.resource://$packageName/drawable/ic_shuffle".toUri())
                                                    .setIsBrowsable(false)
                                                    .setIsPlayable(true)
                                                    .build(),
                                            )
                                            .build(),
                                        null,
                                    )
                                }
                            }
                        }
                        LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                    }
                }
            }
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootItem = MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("Stash Root")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build(),
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
        }

        @OptIn(UnstableApi::class)
        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return serviceScope.future {
                val items = when (parentId) {
                    ROOT_ID -> {
                        listOf(
                            MediaItem.Builder()
                                .setMediaId(PLAYLISTS_ID)
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle("Playlists")
                                        .setIsBrowsable(true)
                                        .setIsPlayable(false)
                                        .build(),
                                )
                                .build(),
                            MediaItem.Builder()
                                .setMediaId(RECENTLY_ADDED_ID)
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle("Recently Added")
                                        .setIsBrowsable(true)
                                        .setIsPlayable(false)
                                        .build(),
                                )
                                .build(),
                        )
                    }
                    PLAYLISTS_ID -> {
                        // Android Auto browse shows downloaded playlists only.
                        // Streaming-only tracks would fail on flaky cellular while
                        // driving — worse UX than not seeing them at all. Revisit
                        // when streaming-aware Auto support lands.
                        playlistDao.getAllVisible(includeStreamable = false).first().map { playlist ->
                            MediaItem.Builder()
                                .setMediaId("$PLAYLIST_PREFIX${playlist.id}")
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(playlist.name)
                                        .setSubtitle("${playlist.trackCount} tracks")
                                        .setArtworkUri(playlist.artUrl?.toUri())
                                        .setIsBrowsable(true)
                                        .setIsPlayable(false)
                                        .build(),
                                )
                                .build()
                        }
                    }
                    RECENTLY_ADDED_ID -> {
                        trackDao.getRecentlyAdded(20).first().map { track ->
                            MediaItem.Builder()
                                .setMediaId(AutoBrowseQueue.childMediaId(parentId, track.id))
                                .setUri(track.filePath ?: "")
                                .setMediaMetadata(
                                    track.toMediaMetadata(),
                                )
                                .build()
                        }
                    }
                    else -> {
                        if (parentId.startsWith(PLAYLIST_PREFIX)) {
                            val playlistId = parentId.removePrefix(PLAYLIST_PREFIX).toLongOrNull()
                            if (playlistId != null) {
                                val shuffleItem = MediaItem.Builder()
                                    .setMediaId("$SHUFFLE_PLAY_PREFIX$playlistId")
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle(getString(R.string.shuffle_play))
                                            .setArtworkUri("android.resource://$packageName/drawable/ic_shuffle".toUri())
                                            .setIsBrowsable(false)
                                            .setIsPlayable(true)
                                            .build(),
                                    )
                                    .build()

                                // v0.9.37: include streamable tracks so stream-only Mix entries are
                                // playable. Downloaded-only filter would silently drop them.
                                // mediaId carries the parent playlist (AUTOQ_…) so a tap can
                                // queue the WHOLE playlist, not a single item — #154/#173.
                                val tracks = playlistDao.getTracksForPlaylist(playlistId).filter{track -> track.isDownloaded || track.isStreamable}.map { track ->
                                    MediaItem.Builder()
                                        .setMediaId(AutoBrowseQueue.childMediaId(parentId, track.id))
                                        .setUri(track.filePath ?: "")
                                        .setMediaMetadata(
                                            track.toMediaMetadata(),
                                        )
                                        .build()
                                }
                                listOf(shuffleItem) + tracks
                            } else emptyList()
                        } else emptyList()
                    }
                }
                LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
            }
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            session.notifySearchResultChanged(browser, query, 0, params)
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        @OptIn(UnstableApi::class)
        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return serviceScope.future {
                // Sanitize for FTS (append * to each term for prefix matching)
                val sanitized = query.split(" ")
                    .filter { it.isNotBlank() }
                    .joinToString(" ") { "$it*" }

                val tracks = trackDao.searchDownloaded(sanitized).first()
                val items = tracks.map { track ->
                    MediaItem.Builder()
                        .setMediaId(track.id.toString())
                        .setUri(track.filePath ?: "")
                        .setMediaMetadata(
                            track.toMediaMetadata(),
                        )
                        .build()
                }
                LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
            }
        }

        @OptIn(UnstableApi::class)
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val customCommands = listOf(
                SessionCommand(COMMAND_TOGGLE_SHUFFLE, /* extras = */ android.os.Bundle.EMPTY),
                SessionCommand(COMMAND_CYCLE_REPEAT, /* extras = */ android.os.Bundle.EMPTY),
                SessionCommand(COMMAND_TOGGLE_LIKE, /* extras = */ android.os.Bundle.EMPTY),
            )
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
            customCommands.forEach { sessionCommands.add(it) }

            //Android auto commands
            sessionCommands.add(SessionCommand.COMMAND_CODE_LIBRARY_GET_LIBRARY_ROOT)
            sessionCommands.add(SessionCommand.COMMAND_CODE_LIBRARY_GET_CHILDREN)
            sessionCommands.add(SessionCommand.COMMAND_CODE_LIBRARY_GET_ITEM)
            sessionCommands.add(SessionCommand.COMMAND_CODE_LIBRARY_SUBSCRIBE)
            sessionCommands.add(SessionCommand.COMMAND_CODE_LIBRARY_SEARCH)

            // Default availablePlayerCommands omits COMMAND_CHANGE_MEDIA_ITEMS,
            // which is what addMediaItem / removeMediaItem / moveMediaItem
            // require. Without explicitly granting full player commands here,
            // controller.addMediaItem(...) silently no-ops — the item never
            // reaches the underlying ExoPlayer's timeline. This is what made
            // "Play Next" and "Add to Queue" appear broken when a queue
            // already existed.
            //
            // Granting all commands is safe: this MediaSession is internal-
            // only (no third-party controllers connect to it).
            val playerCommands = Player.Commands.Builder().addAllCommands().build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands.build())
                .setAvailablePlayerCommands(playerCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: android.os.Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                COMMAND_TOGGLE_SHUFFLE -> {
                    val player = session.player
                    player.shuffleModeEnabled = !player.shuffleModeEnabled
                }
                COMMAND_CYCLE_REPEAT -> {
                    val player = session.player
                    player.repeatMode = when (player.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                }
                COMMAND_TOGGLE_LIKE -> {
                    val mediaItem = session.player.currentMediaItem
                    val mediaMetadata = mediaItem?.mediaMetadata
                    val candidateId = mediaItem?.mediaId?.toLongOrNull()
                    val youtubeId = mediaMetadata?.extras?.getString(EXTRA_TRACK_YOUTUBE_ID)

                    if (candidateId != null && mediaMetadata != null) {
                        // Optimistic update: toggle the local state and push the layout
                        // immediately so the UI feels snappy and avoids race conditions
                        // where multiple clicks see the same stale DB state.
                        val newLikeState = !lastIsLiked
                        lastIsLiked = newLikeState
                        pushLayout(session, session.player, newLikeState)

                        serviceScope.launch {
                            runCatching {
                                // Resolve the real Room PK. The mediaId may be a
                                // v0.9.30 streaming-engine synthetic id
                                // (`videoId.hashCode().toLong()`); without this
                                // resolve the next call FK-violates on
                                // `tracks.id` and crashes the service process
                                // (issue #105).
                                val realId = resolveTrackIdForLike(
                                    candidateId = candidateId,
                                    youtubeId = youtubeId,
                                    metadata = mediaMetadata,
                                )

                                // v0.9.52: route through LikeCoordinator — local
                                // Stash like stays synchronous; optional Spotify/YT
                                // mirroring (off by default) layers on top.
                                likeCoordinator.setLiked(realId, liked = newLikeState)
                            }.onFailure { e ->
                                android.util.Log.w(
                                    "StashPlayback",
                                    "notification like toggle failed for candidateId=$candidateId yt=$youtubeId",
                                    e,
                                )
                                // Roll back the optimistic flip so the UI reflects truth.
                                lastIsLiked = !newLikeState
                                pushLayout(session, session.player, !newLikeState)
                            }
                        }
                    }
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        /**
         * Builds a resumable [MediaItem] from a DB row, mirroring the URI
         * resolution in [resolveMediaItem]: downloaded tracks get a
         * `file://` URI. When [streamUrl] is non-null (the current track was
         * resolved via [ResumeStreamResolver] for online-mode resume), it is
         * used as the playback URI. Otherwise a streaming-only row is left
         * without a URI, which the player surfaces as an onPlayerError →
         * skip-next recovery (see [resolveMediaItem]).
         */
        private fun buildResumeItem(track: TrackEntity, streamUrl: String? = null): MediaItem {
            val builder = MediaItem.Builder()
                .setMediaId(track.id.toString())
                .setMediaMetadata(track.toMediaMetadata())
            val localPath = track.filePath
            when {
                track.isDownloaded && !localPath.isNullOrBlank() -> {
                    val uri = if (localPath.startsWith("/")) {
                        "file://$localPath".toUri()
                    } else {
                        localPath.toUri()
                    }
                    builder.setUri(uri)
                }
                streamUrl != null -> builder.setUri(streamUrl.toUri())
                !localPath.isNullOrBlank() -> {
                    val uri = if (localPath.startsWith("/")) {
                        "file://$localPath".toUri()
                    } else {
                        localPath.toUri()
                    }
                    builder.setUri(uri)
                }
            }
            return builder.build()
        }

        @OptIn(UnstableApi::class)
        override fun onPlaybackResumption(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            return serviceScope.future {
                // This callback can run for a genuine play request started via
                // startForegroundService (media button on a dead process). The
                // current track may need a (possibly slow) stream-URL resolve
                // for online mode, which can blow the OS 5s "must call
                // startForeground" window. Post a lightweight "Resuming…"
                // foreground notification first to satisfy it; Media3 replaces
                // it with the real media notification once playback starts.
                // Only for a real play request — never for boot-time
                // notification population (isForPlayback = false).
                if (isForPlayback) showResumingForegroundNotification()

                // Preferred path: restore the full persisted queue at the
                // saved track + position so next/prev work and playback
                // continues where it stopped.
                val plan = playbackResumer.buildResumePlan()
                if (plan != null) {
                    // Resolve ONLY the current track's stream URL in the
                    // foreground so it plays in online mode; other streamed
                    // tracks resolve later via the prefetch/skip path.
                    // Downloaded tracks return null here and use their file.
                    val currentStreamUrl = resumeStreamResolver
                        .resolveStreamUrl(plan.tracks[plan.startIndex])
                    val items = plan.tracks.mapIndexed { index, track ->
                        buildResumeItem(
                            track,
                            streamUrl = if (index == plan.startIndex) currentStreamUrl else null,
                        )
                    }
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        session.player.shuffleModeEnabled = plan.isShuffled
                    }
                    // Force play once the queue lands iff this is a real play
                    // request (not boot-time notification population).
                    resumePlayGate.arm(isForPlayback)
                    return@future MediaSession.MediaItemsWithStartPosition(
                        ImmutableList.copyOf(items),
                        plan.startIndex,
                        plan.positionMs,
                    )
                }

                // Fallback (no persisted queue yet): single last-played
                // track, or the most recently added track if none.
                val track = trackDao.getLastPlayedTrack()
                    ?: trackDao.getRecentlyAdded(1).first().firstOrNull()
                val item = track?.let {
                    buildResumeItem(it, streamUrl = resumeStreamResolver.resolveStreamUrl(it))
                }

                if (item != null) {
                    resumePlayGate.arm(isForPlayback)
                    MediaSession.MediaItemsWithStartPosition(
                        ImmutableList.of(item),
                        /* startIndex= */ 0,
                        /* startPositionMs= */ C.TIME_UNSET,
                    )
                } else {
                    // Nothing to resume (empty library). Drop the "Resuming…"
                    // placeholder we may have posted so it doesn't linger as a
                    // stuck foreground notification, then signal no-resume.
                    if (isForPlayback) {
                        ServiceCompat.stopForeground(
                            this@StashPlaybackService,
                            ServiceCompat.STOP_FOREGROUND_REMOVE,
                        )
                    }
                    throw UnsupportedOperationException()
                }
            }
        }
    }
}
