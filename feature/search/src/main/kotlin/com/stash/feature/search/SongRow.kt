package com.stash.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.stash.core.ui.theme.StashTheme

/**
 * Canonical tap-to-play song row for Search results, artist "Popular", and album
 * tracklists — the SAME composable everywhere (no fork). The whole row is the play
 * affordance: tapping it plays the track (full stream when Online, 30s preview when
 * Offline), and tapping it again while a preview plays stops it (inheriting the old
 * ▶/⏹ toggle). Download stays a one-tap control on the row.
 *
 * The download control cycles through three visual states:
 *  - Default: download arrow icon (tappable)
 *  - Downloading: circular progress indicator
 *  - Downloaded: green checkmark icon
 *
 * The outer Row is tagged `"SongRow"` so a Compose UI test can lock the non-fork.
 */
@Composable
fun SongRow(
    item: SearchResultItem,
    isDownloading: Boolean,
    isDownloaded: Boolean,
    isPreviewLoading: Boolean,
    isPreviewPlaying: Boolean,
    onPlay: () -> Unit,
    onStopPreview: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * v0.9.17: Track is queued under WAITING_FOR_LOSSLESS — lossless source
     * was unavailable AND the user has yt-dlp fallback off. Renders an
     * outlined-clock icon in the download slot to distinguish from a hard
     * Failed state. Defaults to `false` so the param is backward compatible
     * for callers (PopularTracksSection, AlbumDiscoveryScreen) that haven't
     * threaded the new state yet.
     */
    isWaitingForLossless: Boolean = false,
    /**
     * v0.9.x extract-coalescing: this row's track is currently being resolved
     * for streaming (lossless URL fetch / YT fallback). Reuses the existing
     * preview-button spinner branch so the user sees instant feedback the
     * moment they tap. Defaults to `false` for back-compat with callers that
     * don't thread the resolving state yet.
     */
    isResolving: Boolean = false,
    onPlayNext: () -> Unit = {},
    onAddToQueue: () -> Unit = {},
    onAddToPlaylist: () -> Unit = {},
    onStartRadio: () -> Unit = {},
) {
    val extendedColors = StashTheme.extendedColors
    // Dim the art behind the loading spinner / stop glyph so it reads clearly.
    val scrim = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("SongRow")
            .clip(RoundedCornerShape(12.dp))
            .background(extendedColors.glassBackground)
            // Whole row = play affordance. Tapping while a 30s preview plays stops it
            // (inherits the old ▶/⏹ button toggle) so a running preview is never
            // orphaned. The download button + ⋮ below consume their own taps.
            .clickable { if (isPreviewPlaying) onStopPreview() else onPlay() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Album art or fallback music note, with a spinner (resolving/loading) or a
        // stop hint (preview playing) overlaid so the row-tap state is visible now
        // that the standalone ▶ button is gone.
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(extendedColors.elevatedSurface),
            contentAlignment = Alignment.Center,
        ) {
            if (item.thumbnailUrl != null) {
                AsyncImage(
                    model = item.thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            when {
                isPreviewLoading || isResolving -> Box(
                    modifier = Modifier.fillMaxSize().background(scrim),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                isPreviewPlaying -> Box(
                    modifier = Modifier.fillMaxSize().background(scrim),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop preview",
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Title and artist column -- takes up remaining space
        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = item.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Duration label
        Text(
            text = formatDuration(item.durationSeconds),
            style = MaterialTheme.typography.bodySmall,
            color = extendedColors.textTertiary,
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Download action button
        Box(
            modifier = Modifier.size(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                isDownloaded -> {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Downloaded",
                        modifier = Modifier.size(24.dp),
                        tint = extendedColors.success,
                    )
                }
                isDownloading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                isWaitingForLossless -> {
                    // v0.9.17: deferred — lossless unavailable, fallback off.
                    // Outlined clock signals "queued for retry," distinct from
                    // the red Failed treatment. Tint with onSurfaceVariant so
                    // it reads as informational rather than alerting.
                    Icon(
                        imageVector = Icons.Outlined.Schedule,
                        contentDescription = "Waiting for lossless source",
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    IconButton(onClick = onDownload) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Download",
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        // Overflow: Play next / Add to queue / Add to playlist
        Box {
            var menuOpen by remember { mutableStateOf(false) }
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "More actions",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Play next") },
                    leadingIcon = { Icon(Icons.Default.PlaylistPlay, contentDescription = null) },
                    onClick = { menuOpen = false; onPlayNext() },
                )
                DropdownMenuItem(
                    text = { Text("Add to queue") },
                    leadingIcon = { Icon(Icons.Default.PlaylistAdd, contentDescription = null) },
                    onClick = { menuOpen = false; onAddToQueue() },
                )
                DropdownMenuItem(
                    text = { Text("Add to playlist") },
                    leadingIcon = { Icon(Icons.Default.PlaylistAddCheck, contentDescription = null) },
                    onClick = { menuOpen = false; onAddToPlaylist() },
                )
                DropdownMenuItem(
                    text = { Text("Start radio") },
                    leadingIcon = { Icon(Icons.Default.Radio, contentDescription = null) },
                    onClick = { menuOpen = false; onStartRadio() },
                )
            }
        }
    }
}

/**
 * Formats a duration in seconds to "m:ss" or "h:mm:ss" display string.
 *
 * Internal to the search package so both [SongRow] and any
 * callers that surface a row can share a single formatter.
 */
internal fun formatDuration(seconds: Double): String {
    val totalSeconds = seconds.toLong()
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, secs)
    } else {
        "%d:%02d".format(minutes, secs)
    }
}
