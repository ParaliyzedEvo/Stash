package com.stash.feature.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.stash.core.ui.theme.StashTheme

/**
 * A compact source/service health line: a glowing status dot, the service [name],
 * and a trailing [status] string (e.g. "active") end-aligned. Pure presentation —
 * the caller owns the status text and (optionally) the [dotColor].
 *
 * Used by category screens to render lossless-source / service health lists.
 *
 * @param name      Service or source name (e.g. "Qobuz proxy").
 * @param status    Short status word/phrase shown end-aligned (e.g. "active").
 * @param modifier  Optional [Modifier] for the root row.
 * @param dotColor  Status dot colour. Defaults (when [Color.Unspecified]) to the
 *   theme's cyan-light accent, resolved inside composition.
 */
@Composable
fun SettingsStatusRow(
    name: String,
    status: String,
    modifier: Modifier = Modifier,
    dotColor: Color = Color.Unspecified,
) {
    val resolvedDot =
        if (dotColor == Color.Unspecified) StashTheme.extendedColors.cyanLight else dotColor

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SettingsRowPadH, vertical = SettingsRowPadV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Status dot with a soft glow that picks up the dot colour.
        Box(
            modifier = Modifier
                .size(7.dp)
                .shadow(
                    elevation = 4.dp,
                    shape = CircleShape,
                    ambientColor = resolvedDot,
                    spotColor = resolvedDot,
                )
                .clip(CircleShape)
                .background(resolvedDot),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = status,
            style = MaterialTheme.typography.bodySmall,
            color = StashTheme.extendedColors.textTertiary,
        )
    }
}
