@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.stash.feature.nowplaying

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import com.stash.core.ui.components.SheetOptionRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.stash.core.model.RepeatMode
import com.stash.core.model.isFlac
import com.stash.core.ui.components.SaveToPlaylistSheet
import com.stash.feature.nowplaying.ui.AmbientBackground
import com.stash.feature.nowplaying.ui.GlowingProgressBar
import com.stash.feature.nowplaying.ui.LyricsBottomSheet
import com.stash.feature.nowplaying.ui.QueueBottomSheet

/**
 * Full-screen Now Playing screen with premium visual design.
 *
 * Displays album art with ambient background, playback controls, progress bar,
 * and track information. Colors are extracted from album art via Palette API.
 *
 * @param onDismiss Callback invoked when the user taps the dismiss (down arrow) button.
 * @param viewModel The [NowPlayingViewModel] provided by Hilt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    onDismiss: () -> Unit,
    onNavigateToArtist: (String) -> Unit,
    viewModel: NowPlayingViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val hasNext = (uiState.currentIndex < uiState.queueSize - 1) || uiState.repeatMode == RepeatMode.ALL
    val isAmoled = MaterialTheme.colorScheme.background == Color.Black
    // Safe collection with default value to prevent crashes
    val showBlurLayer by viewModel.showBlurLayerInAmoled.collectAsStateWithLifecycle(initialValue = true)
    
    val track = uiState.currentTrack
    var showQueue by remember { mutableStateOf(false) }
    var showSaveSheet by remember { mutableStateOf(false) }
    var showOptionsSheet by remember { mutableStateOf(false) }
    val optionsSheetState = rememberModalBottomSheetState()
    // "This song is wrong" dialog — shown when the flag icon is tapped.
    // Decouples the Flag button (which is just "there's a problem") from
    // the action (find a replacement / delete / delete + block).
    var showWrongMatchDialog by remember { mutableStateOf(false) }

    // Scroll state is intentionally not keyed by track — on tall screens
    // content doesn't overflow so scroll stays at 0; on narrow screens the
    // user's scroll position aligns with controls and we want it preserved
    // across track changes.
    val scrollState = rememberScrollState()

    // One-shot Toast confirmation for the "wrong match" flag action. Toast
    // instead of Snackbar so we don't have to restructure the screen into
    // a Scaffold — the full-screen ambient background would fight with
    // Material's Snackbar surface anyway.
    val toastContext = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.userMessages.collect { msg ->
            android.widget.Toast.makeText(toastContext, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // Queue bottom sheet
    if (showQueue) {
        QueueBottomSheet(
            queue = uiState.queue,
            currentIndex = uiState.currentIndex,
            accentColor = uiState.vibrantColor,
            onDismiss = { showQueue = false },
            onTrackClick = { index ->
                viewModel.onSkipToQueueIndex(index)
                showQueue = false
            },
            onRemoveTrack = viewModel::onRemoveFromQueue,
            onMoveTrack = viewModel::onMoveInQueue,
        )
    }

    // v0.9.36 Task 12 — lyrics bottom sheet. The IconButton that
    // toggles this lives in Task 13; until then, no UI affordance
    // triggers `onShowLyrics()`. The block below is the real wiring
    // that Task 13 will hook into.
    val showLyrics by viewModel.lyricsSheetOpen.collectAsStateWithLifecycle()
    if (showLyrics) {
        val lyricsState by viewModel.lyricsViewState.collectAsStateWithLifecycle()
        val lyricsPositionMs by viewModel.currentPositionMs.collectAsStateWithLifecycle()
        LyricsBottomSheet(
            state = lyricsState,
            currentPositionMs = lyricsPositionMs,
            onSeek = viewModel::onLyricsLineSeek,
            onRetry = viewModel::onLyricsRetry,
            onDismiss = viewModel::onDismissLyrics,
        )
    }

    // Save to playlist bottom sheet
    if (showSaveSheet && track != null) {
        SaveToPlaylistSheet(
            playlists = uiState.userPlaylists,
            onSaveToPlaylist = { playlistId ->
                viewModel.saveTrackToPlaylist(track.id, playlistId)
            },
            onCreatePlaylist = { name ->
                viewModel.createPlaylistAndAddTrack(name, track.id)
            },
            onDismiss = { showSaveSheet = false },
        )
    }

    // "This song is wrong" — 3-option dialog triggered by the flag icon.
    // Separated from the icon's direct action so the same entry point
    // covers three very different outcomes: mark for replacement, delete
    // the file, delete + permanently block.
    if (showWrongMatchDialog && track != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showWrongMatchDialog = false },
            title = {
                androidx.compose.material3.Text(
                    text = "What's wrong with this song?",
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                )
            },
            text = {
                androidx.compose.foundation.layout.Column(
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    androidx.compose.material3.Text(
                        text = "Pick what should happen to '${track.title}'.",
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    androidx.compose.foundation.layout.Spacer(
                        modifier = Modifier.height(4.dp),
                    )
                    if (!track.isFlac) {
                        androidx.compose.material3.OutlinedButton(
                            onClick = {
                                viewModel.findInFlacForCurrentTrack()
                                showWrongMatchDialog = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            androidx.compose.material3.Text("Find in FLAC")
                        }
                    }
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            viewModel.flagCurrentTrackAsWrongMatch()
                            showWrongMatchDialog = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        androidx.compose.material3.Text("Find a better match")
                    }
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            viewModel.deleteCurrentTrack(alsoBlock = false)
                            showWrongMatchDialog = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        androidx.compose.material3.Text("Delete from library")
                    }
                    androidx.compose.material3.Button(
                        onClick = {
                            viewModel.deleteCurrentTrack(alsoBlock = true)
                            showWrongMatchDialog = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = androidx.compose.material3.MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        androidx.compose.material3.Text("Delete and block forever")
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { showWrongMatchDialog = false },
                ) {
                    androidx.compose.material3.Text("Cancel")
                }
            },
        )
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    Box(modifier = Modifier.fillMaxSize()) {
        // Ambient animated background behind everything.
        AmbientBackground(
            dominantColor = uiState.dominantColor,
            vibrantColor = uiState.vibrantColor,
            mutedColor = uiState.mutedColor,
            isAmoled = isAmoled,
            showBlurLayer = showBlurLayer,
            modifier = Modifier.fillMaxSize(),
        )

        if (isLandscape) {
            // ── LANDSCAPE LAYOUT ──────────────────────────────────────
            // Left half: album art. Right half: controls + info.
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Left: Album art (takes ~45% width)
                Box(
                    modifier = Modifier
                        .weight(0.45f)
                        .fillMaxHeight()
                        .padding(end = 16.dp, top = 8.dp, bottom = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    AlbumArtSection(
                        albumArtUrl = track?.albumArtUrl,
                        albumArtPath = track?.albumArtPath,
                        accentColor = uiState.vibrantColor,
                        onBitmapLoaded = viewModel::onAlbumArtLoaded,
                    )
                }

                // Right: controls + track info
                Column(
                    modifier = Modifier
                        .weight(0.55f)
                        .fillMaxHeight()
                        .verticalScroll(scrollState)
                        .padding(start = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    // Top bar (dismiss + like + more)
                    TopBar(
                        onDismiss = onDismiss,
                        onMoreClick = { showOptionsSheet = true },
                        hasTrack = uiState.hasTrack,
                        onLikeTap = viewModel::onLikeTap,
                        isLiked = uiState.currentTrack?.stashLikedAt != null,
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Track info
                    TrackInfoSection(
                        track = track,
                        isStreaming = uiState.isStreaming,
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Playback controls
                    PlaybackControls(
                        isPlaying = uiState.isPlaying,
                        isBuffering = uiState.isBuffering,
                        shuffleEnabled = uiState.shuffleEnabled,
                        repeatMode = uiState.repeatMode,
                        accentColor = uiState.vibrantColor,
                        hasNext = hasNext,
                        onPlayPauseClick = viewModel::onPlayPauseClick,
                        onSkipNext = viewModel::onSkipNext,
                        onSkipPrevious = viewModel::onSkipPrevious,
                        onToggleShuffle = viewModel::onToggleShuffle,
                        onCycleRepeatMode = viewModel::onCycleRepeatMode,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Progress bar
                    GlowingProgressBar(
                        progress = uiState.progressFraction,
                        accentColor = uiState.vibrantColor,
                        elapsedMs = uiState.currentPositionMs,
                        totalMs = uiState.durationMs,
                        onSeek = viewModel::onSeekTo,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Bottom actions
                    if (track != null) {
                        BottomActionsRow(
                            onShowLyrics = viewModel::onShowLyrics,
                            onShowQueue = { showQueue = true },
                            queueSize = uiState.queueSize,
                            isCasting = uiState.isCasting,
                        )
                    }
                }
            }
        } else {
            // ── PORTRAIT LAYOUT (original) ────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // -- Top bar: dismiss, like, options sheet trigger --
                TopBar(
                    onDismiss = onDismiss,
                    onMoreClick = { showOptionsSheet = true },
                    hasTrack = uiState.hasTrack,
                    onLikeTap = viewModel::onLikeTap,
                    isLiked = uiState.currentTrack?.stashLikedAt != null,
                )

                Spacer(modifier = Modifier.height(24.dp))

                // -- Album art --
                AlbumArtSection(
                    albumArtUrl = track?.albumArtUrl,
                    albumArtPath = track?.albumArtPath,
                    accentColor = uiState.vibrantColor,
                    onBitmapLoaded = viewModel::onAlbumArtLoaded,
                )

                Spacer(modifier = Modifier.height(32.dp))

                // -- Track info --
                TrackInfoSection(
                    track = track,
                    isStreaming = uiState.isStreaming,
                )

                Spacer(modifier = Modifier.height(28.dp))

                // -- Playback controls --
                PlaybackControls(
                    isPlaying = uiState.isPlaying,
                    isBuffering = uiState.isBuffering,
                    shuffleEnabled = uiState.shuffleEnabled,
                    repeatMode = uiState.repeatMode,
                    accentColor = uiState.vibrantColor,
                    hasNext = hasNext,
                    onPlayPauseClick = viewModel::onPlayPauseClick,
                    onSkipNext = viewModel::onSkipNext,
                    onSkipPrevious = viewModel::onSkipPrevious,
                    onToggleShuffle = viewModel::onToggleShuffle,
                    onCycleRepeatMode = viewModel::onCycleRepeatMode,
                )

                Spacer(modifier = Modifier.height(20.dp))

                // -- Progress bar --
                GlowingProgressBar(
                    progress = uiState.progressFraction,
                    accentColor = uiState.vibrantColor,
                    elapsedMs = uiState.currentPositionMs,
                    totalMs = uiState.durationMs,
                    onSeek = viewModel::onSeekTo,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Bottom actions: Lyrics (left), Cast (center), and Queue (right)
                if (track != null) {
                    BottomActionsRow(
                        onShowLyrics = viewModel::onShowLyrics,
                        onShowQueue = { showQueue = true },
                        queueSize = uiState.queueSize,
                        isCasting = uiState.isCasting,
                    )
                }

                Spacer(modifier = Modifier.height(48.dp))
            }
        }
    }

    if (showOptionsSheet && track != null) {
        NowPlayingOptionsSheet(
            isDownloaded = track.isDownloaded,
            onSaveClick = { showSaveSheet = true },
            onDownloadTap = viewModel::toggleDownloadForCurrentTrack,
            onFlagWrongMatch = { showWrongMatchDialog = true },
            onDismiss = { showOptionsSheet = false },
            sheetState = optionsSheetState,
        )
    }
}

// ---------------------------------------------------------------------------
// Private composables
// ---------------------------------------------------------------------------

/**
 * Top bar with dismiss button, "NOW PLAYING" label, save-to-playlist button,
 * and queue button.
 *
 * @param onDismiss    Callback when the down-arrow is tapped.
 * @param onSaveClick  Callback when the save/bookmark icon is tapped.
 * @param onQueueClick Callback when the queue icon is tapped.
 * @param hasTrack     Whether a track is currently loaded (save button is hidden otherwise).
 * @param queueSize    Number of tracks in the queue, shown as a badge hint.
 */
/**
 * Top bar with dismiss button, heart like button, and options sheet trigger.
 *
 * @param onDismiss    Callback when the down-arrow is tapped.
 * @param onMoreClick  Callback when the options menu trigger is tapped.
 * @param hasTrack     Whether a track is currently loaded.
 * @param onLikeTap    Callback when the heart icon is tapped.
 * @param isLiked      Whether the current track is liked.
 */
@Composable
private fun TopBar(
    onDismiss: () -> Unit,
    onMoreClick: () -> Unit,
    hasTrack: Boolean,
    onLikeTap: () -> Unit,
    isLiked: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Dismiss",
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // v0.9.13: Like button — Stash-only toggle. Tap on empty saves to
        // Stash Liked Songs; tap on filled removes.
        if (hasTrack) {
            com.stash.core.ui.components.LikeButton(
                isLiked = isLiked,
                onTap = onLikeTap,
                unlikedTint = Color.White,
                size = 20.dp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        if (hasTrack) {
            IconButton(onClick = onMoreClick) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "More actions",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

/**
 * Album art with a colored glow shadow behind it.
 *
 * Uses Coil 3 [AsyncImage] to load the art. When the image is loaded
 * successfully, the bitmap is forwarded to [onBitmapLoaded] for palette
 * extraction.
 */
@Composable
private fun AlbumArtSection(
    albumArtUrl: String?,
    albumArtPath: String?,
    accentColor: Color,
    onBitmapLoaded: (android.graphics.Bitmap?) -> Unit,
) {
    val context = LocalContext.current
    val artModel = albumArtPath ?: albumArtUrl

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .padding(horizontal = 8.dp)
    ) {
        // Glow behind the artwork.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .shadow(
                    elevation = 40.dp,
                    shape = RoundedCornerShape(8.dp),
                    ambientColor = accentColor.copy(alpha = 0.25f),
                    spotColor = accentColor.copy(alpha = 0.25f),
                ),
        )

        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(artModel)
                .allowHardware(false) // Required for Palette bitmap extraction.
                .build(),
            contentDescription = "Album art",
            contentScale = ContentScale.Crop,
            onState = { state ->
                if (state is AsyncImagePainter.State.Success) {
                    try {
                        val bitmap = state.result.image.toBitmap()
                        onBitmapLoaded(bitmap)
                    } catch (_: Exception) {
                        // Bitmap extraction failed; palette will use defaults.
                        onBitmapLoaded(null)
                    }
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp)),
        )
    }
}

/**
 * Track title, artist · album, FLAC badge, and quality line.
 * Extracted so both portrait and landscape layouts reuse the same block.
 */
@Composable
private fun TrackInfoSection(
    track: com.stash.core.model.Track?,
    isStreaming: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = track?.title ?: "Not Playing",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (track != null) {
            Spacer(modifier = Modifier.width(8.dp))
            com.stash.core.ui.components.FlacBadge(
                fileFormat = track.fileFormat,
                bitsPerSample = track.bitsPerSample,
                sampleRateHz = track.sampleRateHz,
                size = 18.dp,
                tint = Color.White,
            )
        }
    }

    Spacer(modifier = Modifier.height(4.dp))

    Text(
        text = buildString {
            if (track != null) {
                append(track.artist)
                if (track.album.isNotBlank()) {
                    append(" \u2022 ")
                    append(track.album)
                }
            }
        },
        fontSize = 14.sp,
        color = Color.White.copy(alpha = 0.7f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )

    if (track != null) {
        val qualityText = trackQualityText(track)
        if (qualityText != null) {
            Spacer(modifier = Modifier.height(2.dp))
            QualityLine(
                qualityText = qualityText,
                isStreaming = isStreaming,
            )
        }
    }
}

/**
 * Bottom action row: Lyrics (left), Cast (center), Queue (right).
 *
 * The cast button uses a Compose [Icon] with [Icons.Filled.Cast] /
 * [Icons.Filled.CastConnected] and launches either a chooser dialog
 * (pick device) or a controller dialog (stop casting) depending on
 * whether a Cast session is already active.
 */
@Composable
private fun BottomActionsRow(
    onShowLyrics: () -> Unit,
    onShowQueue: () -> Unit,
    queueSize: Int,
    isCasting: Boolean = false,
) {
    val ctx = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onShowLyrics) {
            Icon(
                imageVector = Icons.Outlined.Lyrics,
                contentDescription = "Lyrics",
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(24.dp),
            )
        }

        // Cast button — context-aware:
        //   • NOT connected → MediaRouteChooserDialog (pick device)
        //   • Connected → MediaRouteControllerDialog (stop casting / volume)
        // Uses the async getSharedInstance(context, executor) overload for
        // robust device support (Cast dynamic module can load lazily).
        IconButton(
            onClick = {
                try {
                    com.google.android.gms.cast.framework.CastContext
                        .getSharedInstance(ctx, java.util.concurrent.Executors.newSingleThreadExecutor())
                        .addOnSuccessListener { castCtx ->
                            try {
                                val selector = castCtx.mergedSelector
                                    ?: androidx.mediarouter.media.MediaRouteSelector.Builder()
                                        .addControlCategory(
                                            com.google.android.gms.cast.CastMediaControlIntent.categoryForCast(
                                                com.google.android.gms.cast.CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
                                            )
                                        )
                                        .build()

                                val themedCtx = androidx.appcompat.view.ContextThemeWrapper(
                                    ctx,
                                    androidx.appcompat.R.style.Theme_AppCompat
                                )

                                if (isCasting) {
                                    // Already connected — show controller (volume / stop)
                                    val dialog = androidx.mediarouter.app.MediaRouteControllerDialog(themedCtx)
                                    dialog.show()
                                } else {
                                    // Not connected — show chooser (pick device)
                                    val dialog = androidx.mediarouter.app.MediaRouteChooserDialog(themedCtx)
                                    dialog.routeSelector = selector
                                    dialog.show()
                                }
                            } catch (e: Exception) {
                                android.util.Log.w("NowPlaying", "Cast dialog failed", e)
                                android.widget.Toast.makeText(ctx, "Cast unavailable", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                        .addOnFailureListener { e ->
                            android.util.Log.w("NowPlaying", "CastContext init failed", e)
                            android.widget.Toast.makeText(ctx, "Cast unavailable", android.widget.Toast.LENGTH_SHORT).show()
                        }
                } catch (e: Exception) {
                    android.util.Log.w("NowPlaying", "Cast button failed", e)
                    android.widget.Toast.makeText(ctx, "Cast unavailable", android.widget.Toast.LENGTH_SHORT).show()
                }
            },
        ) {
            Icon(
                imageVector = if (isCasting) Icons.Filled.CastConnected else Icons.Filled.Cast,
                contentDescription = if (isCasting) "Stop casting" else "Cast to device",
                tint = if (isCasting) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(24.dp),
            )
        }

        IconButton(onClick = onShowQueue) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                contentDescription = "Queue ($queueSize tracks)",
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * Playback controls row: shuffle, previous, play/pause, next, repeat.
 */
@Composable
private fun PlaybackControls(
    isPlaying: Boolean,
    isBuffering: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    accentColor: Color,
    hasNext: Boolean,
    onPlayPauseClick: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeatMode: () -> Unit,
) {
    // Dynamic bouncy scale spring-physics micro-animation on state change (Expressive UI)
    val playButtonScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isPlaying) 1.08f else 1.0f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
        ),
        label = "playButtonScaleAnimation"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Shuffle
        IconButton(onClick = onToggleShuffle) {
            Icon(
                imageVector = Icons.Default.Shuffle,
                contentDescription = "Shuffle",
                tint = if (shuffleEnabled) accentColor else Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(24.dp),
            )
        }

        // Previous
        IconButton(onClick = onSkipPrevious) {
            Icon(
                imageVector = Icons.Default.SkipPrevious,
                contentDescription = "Previous",
                tint = Color.White,
                modifier = Modifier.size(36.dp),
            )
        }

        // Play / Pause — large gradient circle. While the track is still
        // resolving/buffering (e.g. an ~11 s YouTube-fallback yt-dlp resolve),
        // show a spinner in place of the icon so it doesn't look frozen.
        IconButton(
            onClick = onPlayPauseClick,
            enabled = !isBuffering,
            modifier = Modifier
                .size(64.dp)
                .graphicsLayer {
                    scaleX = playButtonScale
                    scaleY = playButtonScale
                }
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(accentColor, accentColor.copy(alpha = 0.7f)),
                    ),
                    shape = CircleShape,
                ),
        ) {
            if (isBuffering) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(28.dp),
                )
            } else {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp),
                )
            }
        }

        // Next
        IconButton(onClick = onSkipNext) {
            Icon(
                imageVector = if (hasNext) Icons.Default.SkipNext else Icons.Default.Replay,
                contentDescription = if (hasNext) "Next" else "Restart",
                tint = Color.White,
                modifier = Modifier.size(36.dp),
            )
        }

        // Repeat
        IconButton(onClick = onCycleRepeatMode) {
            Icon(
                imageVector = when (repeatMode) {
                    RepeatMode.ONE -> Icons.Default.RepeatOne
                    else -> Icons.Default.Repeat
                },
                contentDescription = "Repeat",
                tint = when (repeatMode) {
                    RepeatMode.OFF -> Color.White.copy(alpha = 0.6f)
                    else -> accentColor
                },
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * Formats a one-line quality summary for the Now Playing screen.
 *
 * Examples:
 *   - All four fields known:  `FLAC · 24-bit/96.0 kHz · 4233 kbps`
 *   - Codec + bitrate only:    `OPUS · 160 kbps`
 *   - Codec only:              `FLAC` (data not yet backfilled)
 *
 * Returns null only when the codec is blank — in that case the caller
 * should render no line at all.
 */
private fun trackQualityText(track: com.stash.core.model.Track): String? {
    // v0.9.13 fix: tracks downloaded before format-tracking was wired (pre-v0.9.11)
    // default to file_format = "opus" regardless of the actual codec — so a FLAC
    // file would render "OPUS · 4233 kbps", which is the source of "every track says
    // Opus" complaints. The Library Health backfill writes correct values from disk
    // but only when the user opens that screen. Cheap interim correction: if the
    // track has a downloaded filePath, prefer the file extension as canonical.
    val extension = track.filePath
        ?.takeIf { it.isNotBlank() }
        ?.substringAfterLast('.', missingDelimiterValue = "")
        ?.lowercase()
    val codec = when (extension) {
        "flac", "alac", "wav", "ape", "tta", "wv", "aiff" -> extension!!.uppercase()
        "opus", "m4a", "mp3", "ogg", "aac" -> extension!!.uppercase()
        else -> track.fileFormat.takeIf { it.isNotBlank() }?.uppercase() ?: return null
    }
    val bitDepth = track.bitsPerSample
    val sampleRateKHz = track.sampleRateHz?.let { it / 1000.0 }
    val bitrate = track.qualityKbps.takeIf { it > 0 }
    return buildList {
        add(codec)
        if (bitDepth != null && sampleRateKHz != null) {
            add("${bitDepth}-bit/${"%.1f".format(sampleRateKHz)} kHz")
        }
        if (bitrate != null) add("$bitrate kbps")
        // Flag the YouTube fallback so the user can tell when a track is
        // playing from yt-dlp/InnerTube extraction rather than Qobuz. The
        // codec ("AAC") alone doesn't convey this — Qobuz also serves AAC
        // at MP3_320 tier. Only the streamOrigin field distinguishes the
        // two. We don't badge "via Kennyy" / "via squid" because those
        // are the expected primary sources; only the lossy fallback
        // deserves a callout.
        if (track.streamOrigin == "youtube") add("via YT")
    }.joinToString(" · ")
}

/**
 * Renders the codec/bitrate quality line beneath the artist · album row.
 * When [isStreaming] is `true` a small wifi glyph is prefixed so the
 * user can tell at a glance that playback is coming from the network
 * rather than a local file. The icon picks up
 * [MaterialTheme.colorScheme.primary] so it stands out against the
 * white-on-ambient quality text without clashing with the album-art
 * palette.
 *
 * Centered as a Row so the prefix-icon variant stays visually balanced
 * with the icon-less variant — the original `Text(textAlign = Center)`
 * call is preserved when there is nothing to prefix.
 */
@Composable
private fun QualityLine(
    qualityText: String,
    isStreaming: Boolean,
) {
    if (isStreaming) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Wifi,
                contentDescription = "Streaming",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(12.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = qualityText,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    } else {
        Text(
            text = qualityText,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.5f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(
    name = "QualityLine — streaming",
    showBackground = true,
    backgroundColor = 0xFF101012,
)
@Composable
private fun PreviewQualityLineStreaming() {
    com.stash.core.ui.theme.StashTheme {
        QualityLine(
            qualityText = "OPUS \u00B7 160 kbps",
            isStreaming = true,
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(
    name = "QualityLine — local",
    showBackground = true,
    backgroundColor = 0xFF101012,
)
@Composable
private fun PreviewQualityLineLocal() {
    com.stash.core.ui.theme.StashTheme {
        QualityLine(
            qualityText = "FLAC \u00B7 24-bit/96.0 kHz \u00B7 4233 kbps",
            isStreaming = false,
        )
    }
}

/**
 * Premium track options bottom sheet that replaces the legacy dropdown menu on Now Playing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NowPlayingOptionsSheet(
    isDownloaded: Boolean,
    onSaveClick: () -> Unit,
    onDownloadTap: () -> Unit,
    onFlagWrongMatch: () -> Unit,
    onDismiss: () -> Unit,
    sheetState: androidx.compose.material3.SheetState,
    modifier: Modifier = Modifier,
) {
    val extendedColors = com.stash.core.ui.theme.StashTheme.extendedColors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = extendedColors.elevatedSurface,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp),
        ) {
            Text(
                text = "Track Options",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(bottom = 20.dp)
                    .align(Alignment.CenterHorizontally)
            )

            // Save to Playlist
            SheetOptionRow(
                icon = Icons.Default.BookmarkBorder,
                label = "Save to Playlist",
                onClick = {
                    onSaveClick()
                    onDismiss()
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Download / Remove download
            SheetOptionRow(
                icon = if (isDownloaded) Icons.Default.DownloadDone else Icons.Default.Download,
                label = if (isDownloaded) "Remove download" else "Download",
                onClick = {
                    onDownloadTap()
                    onDismiss()
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Flag as Wrong Match
            SheetOptionRow(
                icon = Icons.Default.Flag,
                label = "Flag as Wrong Match",
                onClick = {
                    onFlagWrongMatch()
                    onDismiss()
                }
            )
        }
    }
}

