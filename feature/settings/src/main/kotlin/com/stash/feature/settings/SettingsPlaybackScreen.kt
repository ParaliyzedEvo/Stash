package com.stash.feature.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.common.constants.StashConstants
import com.stash.feature.settings.components.SettingsGroupCard
import com.stash.feature.settings.components.SettingsScaffold
import com.stash.feature.settings.components.SettingsSectionLabel
import com.stash.feature.settings.components.SettingsSegmented
import com.stash.feature.settings.components.SettingsToggleRow

/**
 * The Playback spoke of the hub-and-spoke Settings redesign.
 *
 * This re-homes the original `StashConstants.STREAMING_ENGINE_ENABLED` block from
 * the monolithic `SettingsScreen.kt`: the Online/Offline mode picker plus the
 * streaming toggles (cellular, YouTube fallback, antra-only). This is a
 * behavior-preserving relocation + restyle — every control calls the SAME
 * [SettingsViewModel] method the old screen used; no logic is changed.
 */
@Composable
fun SettingsPlaybackScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val streamingEnabled by viewModel.streamingEnabled.collectAsStateWithLifecycle()
    val streamOnCellular by viewModel.streamOnCellular.collectAsStateWithLifecycle()
    val forceYouTubeFallback by viewModel.forceYouTubeFallback.collectAsStateWithLifecycle()
    val forceAmzOnly by viewModel.forceAmzOnly.collectAsStateWithLifecycle()

    SettingsScaffold(title = "Playback", onBack = onBack, modifier = modifier) {
        if (StashConstants.STREAMING_ENGINE_ENABLED) {
            SettingsSectionLabel("Mode")
            SettingsSegmented(
                options = listOf("Online", "Offline"),
                selectedIndex = if (streamingEnabled) 0 else 1,
                onSelect = { viewModel.onStreamingToggle(it == 0) },
            )

            SettingsSectionLabel("Streaming")
            SettingsGroupCard(
                rows = listOf(
                    {
                        SettingsToggleRow(
                            title = "Stream on cellular",
                            subtitle = "Allow streaming over mobile data (5G / LTE). Off by default to avoid surprise data use.",
                            checked = streamOnCellular,
                            onCheckedChange = viewModel::onStreamOnCellularToggle,
                        )
                    },
                    {
                        SettingsToggleRow(
                            title = "Stream via YouTube",
                            subtitle = "Skip the lossless sources (Qobuz) and stream everything via YouTube. Turn this on if lossless playback is down or only playing short clips.",
                            checked = forceYouTubeFallback,
                            onCheckedChange = viewModel::setForceYouTubeFallback,
                        )
                    },
                    {
                        SettingsToggleRow(
                            title = "Stream via amz (test)",
                            subtitle = "Route streaming AND downloads through amz (Amazon Music) only — no Qobuz, no YouTube. For testing the amz source. Turn off after testing.",
                            checked = forceAmzOnly,
                            onCheckedChange = viewModel::setForceAmzOnly,
                        )
                    },
                ),
            )
        } else {
            Text(
                text = "Streaming is unavailable in this build.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
