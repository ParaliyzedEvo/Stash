package com.stash.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.stash.core.ui.theme.StashTheme

/**
 * A subtle frosted-glass style card using a semi-transparent background.
 *
 * The glass effect comes from the translucent [glassBackground] color layered
 * over the dark app background. Border is omitted intentionally to avoid
 * a "wall of boxes" look — the tonal contrast between the card fill and
 * the page background provides enough visual separation.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val extendedColors = StashTheme.extendedColors
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large),
        color = extendedColors.glassBackground,
        shape = MaterialTheme.shapes.large,
    ) {
        Box(modifier = Modifier.padding(16.dp), content = content)
    }
}

