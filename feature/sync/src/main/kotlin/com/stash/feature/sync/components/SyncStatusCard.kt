package com.stash.feature.sync.components

import android.text.format.DateUtils
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.stash.core.model.SyncDisplayStatus
import com.stash.core.ui.components.GlassCard
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.sync.SyncStatusInfo

/**
 * Sync status card displayed at the top of the Sync tab.
 *
 * Shows a pulse-dot status indicator, the latest sync's display
 * status, per-source track counts, FLAC subtotals, total storage,
 * and a "Last sync …" relative-time line.
 *
 * Originally lived in `:feature:home` (HomeScreen.SyncStatusCard).
 * Relocated to `:feature:sync` so library-status information lives
 * with the rest of the Sync surface.
 */
@Composable
fun SyncStatusCard(
    syncStatus: SyncStatusInfo,
    spotifyConnected: Boolean,
    youTubeConnected: Boolean,
    hasEverSynced: Boolean,
    modifier: Modifier = Modifier,
) {
    val anyServiceConnected = spotifyConnected || youTubeConnected

    GlassCard(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // -- Connection + sync status header + last sync time --
            // Uses SyncDisplayStatus so "Completed with some failures" and
            // "Interrupted mid-run" don't both read as a generic failure.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PulseDot(color = syncStatusDotColor(syncStatus, anyServiceConnected, hasEverSynced))
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = syncStatusLabel(syncStatus, anyServiceConnected, hasEverSynced),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
                if (hasEverSynced && syncStatus.lastSyncTime != null) {
                    Text(
                        text = formatRelativeTimeForCard(syncStatus.lastSyncTime),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // -- Prompt or stats depending on sync state --
            if (!anyServiceConnected) {
                Text(
                    text = "Connect Spotify or YouTube Music in Settings to start syncing your library.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (!hasEverSynced) {
                Text(
                    text = "Tap Sync Now to download your playlists and tracks.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround,
                    ) {
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            StatItem(
                                label = "Tracks",
                                value = "%,d".format(syncStatus.totalTracks),
                            )
                        }
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            StatItem(
                                label = "Spotify",
                                value = "%,d".format(syncStatus.spotifyTracks),
                            )
                        }
                    }
                    androidx.compose.foundation.layout.Spacer(
                        modifier = Modifier.height(4.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround,
                    ) {
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            StatItem(
                                label = "YouTube",
                                value = "%,d".format(syncStatus.youTubeTracks),
                            )
                        }
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            StatItem(
                                label = "Storage",
                                value = formatBytes(syncStatus.storageUsedBytes),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Label shown next to the pulse dot in [SyncStatusCard]. Interprets
 * [SyncStatusInfo.displayStatus] so partial / interrupted runs aren't
 * misreported as generic failures.
 */
@Composable
private fun syncStatusLabel(
    syncStatus: SyncStatusInfo,
    anyServiceConnected: Boolean,
    hasEverSynced: Boolean,
): String = when {
    !anyServiceConnected -> "No services connected"
    !hasEverSynced -> "Ready to sync"
    else -> when (val s = syncStatus.displayStatus) {
        SyncDisplayStatus.Idle -> "Ready to sync"
        SyncDisplayStatus.Running -> "Syncing..."
        SyncDisplayStatus.Success -> "Synced"
        is SyncDisplayStatus.PartialSuccess ->
            "Partially synced — ${s.downloaded} saved, ${s.failed} failed"
        is SyncDisplayStatus.Interrupted ->
            if (s.downloaded > 0) "Interrupted — ${s.downloaded} saved"
            else "Interrupted"
        is SyncDisplayStatus.Failed -> "Sync failed"
    }
}

/**
 * Color for the pulse dot in [SyncStatusCard]. Green = success-ish,
 * amber = in-progress / warning, red = genuine failure, gray = idle.
 */
@Composable
private fun syncStatusDotColor(
    syncStatus: SyncStatusInfo,
    anyServiceConnected: Boolean,
    hasEverSynced: Boolean,
): Color {
    val extendedColors = StashTheme.extendedColors
    return when {
        !anyServiceConnected -> MaterialTheme.colorScheme.onSurfaceVariant
        !hasEverSynced -> extendedColors.warning
        else -> when (syncStatus.displayStatus) {
            SyncDisplayStatus.Idle -> extendedColors.warning
            SyncDisplayStatus.Running -> extendedColors.warning
            SyncDisplayStatus.Success -> extendedColors.success
            is SyncDisplayStatus.PartialSuccess -> extendedColors.warning
            is SyncDisplayStatus.Interrupted -> extendedColors.warning
            is SyncDisplayStatus.Failed -> Color(0xFFEF4444)
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PulseDot(color: Color, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )
    Box(
        modifier = modifier
            .size(8.dp)
            .alpha(alpha)
            .clip(CircleShape)
            .background(color),
    )
}

/**
 * Formats a byte count into a human-readable string (e.g. "45.2 MB").
 */
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    val safeIndex = digitGroups.coerceIn(0, units.lastIndex)
    return "%.1f %s".format(bytes / Math.pow(1024.0, safeIndex.toDouble()), units[safeIndex])
}

/**
 * Formats an epoch-millis timestamp into a relative time string (e.g. "2 hours ago").
 *
 * File-private and distinctly named (vs. the public `formatRelativeTime`
 * exposed by [com.stash.feature.sync.components.RecentSyncsCard]) so the
 * two helpers can evolve independently without import-ambiguity surprises.
 */
private fun formatRelativeTimeForCard(epochMillis: Long): String =
    DateUtils.getRelativeTimeSpanString(
        epochMillis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()
