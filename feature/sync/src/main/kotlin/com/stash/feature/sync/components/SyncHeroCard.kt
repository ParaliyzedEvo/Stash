package com.stash.feature.sync.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stash.core.ui.theme.StashTheme

/**
 * Gradient-tinted hero card carrying last-sync metadata + the Sync Now button.
 *
 * One mode control: [downloadOnline] governs whether Sync Now writes real
 * files to disk or only refreshes which tracks are stream-eligible — and,
 * today, whether playback may stream (the player reads the same
 * [StreamingPreference]). The second "Playback" control that #413 added here
 * wrote a preference the player never read; removed 2026-09-17.
 *
 * @param downloadOnline         Current Download Mode (was `streamingMode`).
 *                                True = sync only refreshes the streamable
 *                                index; false = sync writes real files.
 * @param onDownloadModeChange   Invoked with true for Online, false for
 *                                Offline when the user taps the Downloads toggle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncHeroCard(
    lastSyncRelativeTime: String,
    lastSyncTrackCount: Int?,
    healthLabel: String,
    healthColor: Color,
    isSyncing: Boolean,
    downloadOnline: Boolean,
    onDownloadModeChange: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
    progressContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val purple = MaterialTheme.colorScheme.primary
    val cyan = StashTheme.extendedColors.cyan
    val gradient = Brush.linearGradient(
        colors = listOf(
            purple.copy(alpha = 0.18f),
            cyan.copy(alpha = 0.08f),
        ),
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.Transparent,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, purple.copy(alpha = 0.35f)),
    ) {
        Column(
            modifier = Modifier
                .background(gradient, RoundedCornerShape(18.dp))
                .padding(18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "LAST SYNC",
                        style = MaterialTheme.typography.labelSmall,
                        color = StashTheme.extendedColors.purpleLight,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(2.dp))
                    val body = when {
                        lastSyncTrackCount == null -> "Never synced"
                        lastSyncTrackCount == 0 -> "$lastSyncRelativeTime · no new songs"
                        lastSyncTrackCount == 1 -> "$lastSyncRelativeTime · 1 new song"
                        else -> "$lastSyncRelativeTime · $lastSyncTrackCount new songs"
                    }
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (lastSyncTrackCount != null) {
                    Text(
                        text = healthLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = healthColor,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))

            // Download mode: does Sync Now write real files, or just refresh
            // the streamable index?
            Text(
                text = "DOWNLOADS",
                style = MaterialTheme.typography.labelSmall,
                color = StashTheme.extendedColors.purpleLight,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = downloadOnline,
                    onClick = { if (!downloadOnline) onDownloadModeChange(true) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    enabled = !isSyncing,
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.CloudQueue,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    label = { Text("Online") },
                )
                SegmentedButton(
                    selected = !downloadOnline,
                    onClick = { if (downloadOnline) onDownloadModeChange(false) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    enabled = !isSyncing,
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.OfflinePin,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    label = { Text("Offline") },
                )
            }

            Spacer(Modifier.height(12.dp))

            if (isSyncing) {
                progressContent()
            } else {
                Button(
                    onClick = onSyncNow,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = purple),
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (downloadOnline) "Update Streaming Index" else "Download Tracks to Device",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
