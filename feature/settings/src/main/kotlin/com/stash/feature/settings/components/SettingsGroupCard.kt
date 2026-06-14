package com.stash.feature.settings.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.stash.core.ui.theme.StashTheme

/**
 * A card whose direct children are settings rows. Draws a hairline divider
 * between consecutive rows.
 *
 * Bespoke [Surface] copying GlassCard's visual params but with ZERO outer
 * padding (unlike GlassCard's 16dp Box inset), so rows own their own padding
 * and dividers span edge-to-edge.
 */
@Composable
fun SettingsGroupCard(
    modifier: Modifier = Modifier,
    rows: List<@Composable () -> Unit>,
) {
    val extendedColors = StashTheme.extendedColors
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large),
        color = extendedColors.glassBackground,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, extendedColors.glassBorder),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            rows.forEachIndexed { i, row ->
                if (i > 0) {
                    HorizontalDivider(color = SettingsDivider, thickness = 1.dp)
                }
                row()
            }
        }
    }
}
