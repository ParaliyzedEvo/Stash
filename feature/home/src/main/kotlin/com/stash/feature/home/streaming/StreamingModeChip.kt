package com.stash.feature.home.streaming

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stash.core.common.constants.StashConstants
import com.stash.core.ui.theme.StashTheme

/**
 * Compact status chip rendered in the Home top bar (between the Stash
 * wordmark and the GitHub-issue icon). Shows the current playback mode
 * — "Online" or "Offline" — with a matching glyph, and routes taps to
 * [onClick] so the host screen can pop the bottom sheet that holds the
 * picker proper.
 *
 * Visibility is gated on [StashConstants.STREAMING_ENGINE_ENABLED] for
 * consistency with the rest of the streaming surfaces; when the flag
 * is off the composable renders nothing.
 *
 * Visual: pill-shaped glass with a primary-tinted accent border. Stays
 * intentionally small so it doesn't compete with the wordmark or the
 * trailing utility icons in the top row.
 */
@Composable
fun StreamingModeChip(
    streamingEnabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!StashConstants.STREAMING_ENGINE_ENABLED) return

    val extendedColors = StashTheme.extendedColors
    val accent = MaterialTheme.colorScheme.primary

    IconButton(
        onClick = onClick,
        modifier = modifier
    ) {
        Icon(
            imageVector = if (streamingEnabled) Icons.Default.CloudQueue else Icons.Default.OfflinePin,
            contentDescription = if (streamingEnabled) "Online Mode" else "Offline Mode",
            tint = accent,
            modifier = Modifier.size(24.dp),
        )
    }
}
