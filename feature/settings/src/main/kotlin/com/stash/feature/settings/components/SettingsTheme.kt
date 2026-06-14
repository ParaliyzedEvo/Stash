package com.stash.feature.settings.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.stash.core.ui.theme.StashTheme

/** v0.9.x Settings redesign shared dims. */
internal val SettingsRowPadH = 15.dp
internal val SettingsRowPadV = 14.dp
internal val SettingsGroupGap = 18.dp

/** Theme-aware hairline for inter-row separators. Reuses the glass-border token so
 *  it tracks light/dark exactly like the card border itself (a hardcoded white
 *  hairline would be invisible on the light theme's lavender surface). */
internal val settingsDivider: Color
    @Composable get() = StashTheme.extendedColors.glassBorder
