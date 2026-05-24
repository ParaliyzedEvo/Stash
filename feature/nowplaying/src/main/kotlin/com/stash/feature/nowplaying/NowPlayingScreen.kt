package com.stash.feature.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
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
    viewModel: NowPlayingViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val track = uiState.currentTrack
    var showQueue by remember { mutableStateOf(false) }
    var showSaveSheet by remember { mutableStateOf(false) }
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

    Box(modifier = Modifier.fillMaxSize()) {
        // Ambient animated background behind everything.
        AmbientBackground(
            dominantColor = uiState.dominantColor,
            vibrantColor = uiState.vibrantColor,
            mutedColor = uiState.mutedColor,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // -- Top bar: dismiss, label, flag, like, download, save, lyrics, queue --
            TopBar(
                onDismiss = onDismiss,
                onFlagWrongMatch = { showWrongMatchDialog = true },
                onSaveClick = { showSaveSheet = true },
                onLyricsClick = viewModel::onShowLyrics,
                onQueueClick = { showQueue = true },
                hasTrack = uiState.hasTrack,
                queueSize = uiState.queueSize,
                onLikeTap = viewModel::onLikeTap,
                isLiked = uiState.currentTrack?.stashLikedAt != null,
                onDownloadTap = viewModel::toggleDownloadForCurrentTrack,
                isDownloaded = uiState.currentTrack?.isDownloaded == true,
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

            // Quality line — codec + bit-depth/sample-rate + bitrate, when known.
            // Sized smaller than the artist/album line; degrades gracefully when
            // some fields are missing (returns a partial line, not nothing).
            // When the active MediaItem is sourced from an http(s) URI (Kennyy
            // stream rather than a local file), a small wifi glyph prefixes
            // the line so the user knows playback is using their connection.
            if (track != null) {
                val qualityText = trackQualityText(track)
                if (qualityText != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    QualityLine(
                        qualityText = qualityText,
                        isStreaming = uiState.isStreaming,
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // -- Progress bar --
            GlowingProgressBar(
                progress = uiState.progressFraction,
                accentColor = uiState.vibrantColor,
                elapsedMs = uiState.currentPositionMs,
                totalMs = uiState.durationMs,
                onSeek = viewModel::onSeekTo,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(20.dp))

            // -- Playback controls --
            PlaybackControls(
                isPlaying = uiState.isPlaying,
                shuffleEnabled = uiState.shuffleEnabled,
                repeatMode = uiState.repeatMode,
                accentColor = uiState.vibrantColor,
                onPlayPauseClick = viewModel::onPlayPauseClick,
                onSkipNext = viewModel::onSkipNext,
                onSkipPrevious = viewModel::onSkipPrevious,
                onToggleShuffle = viewModel::onToggleShuffle,
                onCycleRepeatMode = viewModel::onCycleRepeatMode,
            )

            Spacer(modifier = Modifier.height(48.dp))
        }
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
@Composable
private fun TopBar(
    onDismiss: () -> Unit,
    onFlagWrongMatch: () -> Unit,
    onSaveClick: () -> Unit,
    onLyricsClick: () -> Unit,
    onQueueClick: () -> Unit,
    hasTrack: Boolean,
    queueSize: Int,
    onLikeTap: () -> Unit,
    isLiked: Boolean,
    onDownloadTap: () -> Unit,
    isDownloaded: Boolean,
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

        // Flag as wrong match — only shown when a track is loaded. Lives
        // here (not in the Playlist Detail row menu) because Now Playing
        // is where the user actually realises "this isn't the right song"
        // — their ears are the ground truth.
        if (hasTrack) {
            IconButton(onClick = onFlagWrongMatch) {
                Icon(
                    imageVector = Icons.Default.Flag,
                    contentDescription = "Flag as wrong match",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        // v0.9.13: Like button — Stash-only toggle. Tap on empty saves to
        // Stash Liked Songs; tap on filled removes. Long-press is a no-op
        // by design; the override sheet was deprecated in favor of the
        // simpler standard like-button UX.
        if (hasTrack) {
            com.stash.core.ui.components.LikeButton(
                isLiked = isLiked,
                onTap = onLikeTap,
                unlikedTint = Color.White,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        // Download / Remove-download toggle — single button that flips
        // based on the current track's on-disk state. Streaming-mode
        // users use this to grab the song they're listening to right now
        // without leaving Now Playing.
        if (hasTrack) {
            IconButton(onClick = onDownloadTap) {
                Icon(
                    imageVector = if (isDownloaded) Icons.Default.DownloadDone else Icons.Default.Download,
                    contentDescription = if (isDownloaded) "Remove download" else "Download",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        // Save to playlist — only shown when a track is loaded.
        if (hasTrack) {
            IconButton(onClick = onSaveClick) {
                Icon(
                    imageVector = Icons.Default.BookmarkBorder,
                    contentDescription = "Save to Playlist",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        // v0.9.36 Task 13 — Lyrics. Sits next to Queue because both
        // surfaces are "playback context" — what's coming next, what
        // the singer is saying right now. Hidden when no track is loaded
        // (same gating as Save/Download/Like) so an empty Now Playing
        // doesn't show a button that opens an empty sheet.
        if (hasTrack) {
            IconButton(onClick = onLyricsClick) {
                Icon(
                    imageVector = Icons.Outlined.Lyrics,
                    contentDescription = "Lyrics",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        IconButton(onClick = onQueueClick) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                contentDescription = "Queue ($queueSize tracks)",
                tint = Color.White,
                modifier = Modifier.size(24.dp),
            )
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

    Box(contentAlignment = Alignment.Center) {
        // Glow behind the artwork.
        Box(
            modifier = Modifier
                .size(260.dp)
                .shadow(
                    elevation = 40.dp,
                    shape = RoundedCornerShape(20.dp),
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
                .size(280.dp)
                .clip(RoundedCornerShape(20.dp)),
        )
    }
}

/**
 * Playback controls row: shuffle, previous, play/pause, next, repeat.
 */
@Composable
private fun PlaybackControls(
    isPlaying: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    accentColor: Color,
    onPlayPauseClick: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeatMode: () -> Unit,
) {
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

        // Play / Pause — large gradient circle
        IconButton(
            onClick = onPlayPauseClick,
            modifier = Modifier
                .size(64.dp)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(accentColor, accentColor.copy(alpha = 0.7f)),
                    ),
                    shape = CircleShape,
                ),
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = Color.White,
                modifier = Modifier.size(32.dp),
            )
        }

        // Next
        IconButton(onClick = onSkipNext) {
            Icon(
                imageVector = Icons.Default.SkipNext,
                contentDescription = "Next",
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
