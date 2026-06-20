package com.stash.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.ui.components.GlassCard
import com.stash.data.download.lossless.LosslessQualityTier
import com.stash.feature.settings.components.AudioQualityPicker
import com.stash.feature.settings.components.BetaPill
import com.stash.feature.settings.components.LosslessRoutingStatus
import com.stash.feature.settings.components.SettingsNavRow
import com.stash.feature.settings.components.SettingsPickerRow
import com.stash.feature.settings.components.SettingsScaffold
import com.stash.feature.settings.components.SettingsSectionLabel
import com.stash.feature.settings.components.SettingsToggleRow

/**
 * The Audio & Quality spoke of the hub-and-spoke Settings redesign.
 *
 * This re-homes the original "Audio Quality" + "Lossless audio card" block
 * from the monolithic `SettingsScreen.kt`: the download-tier picker (shown
 * only when lossless is off), the lossless toggle, the routing status,
 * the lossless-quality picker, the YouTube-fallback
 * expander, and the Advanced (captcha cookie + reset) expander — plus the
 * Equalizer nav. This is a behavior-preserving relocation + restyle: every
 * control calls the SAME [SettingsViewModel] method the old screen used; no
 * logic is changed. The legacy `bringIntoViewRequester` (deep-link scroll
 * affordance) is dropped — it is not needed on a dedicated category screen.
 */
@Composable
fun SettingsAudioQualityScreen(
    onBack: () -> Unit,
    onNavigateToEqualizer: () -> Unit,
    onNavigateToSquidWtfCaptcha: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsScaffold(title = "Audio & Quality", onBack = onBack, modifier = modifier) {
        // (a) Download tier — only when lossless OFF. The standalone yt-dlp
        // tier picker governs downloads when lossless routing is disabled.
        if (!uiState.losslessEnabled) {
            SettingsSectionLabel("Audio Quality")
            GlassCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectableGroup(),
                ) {
                    Text(
                        text = "Download quality",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    AudioQualityPicker(
                        selected = uiState.audioQuality,
                        onSelected = viewModel::onQualityChanged,
                    )
                }
            }
        }

        // (b) Lossless card.
        SettingsSectionLabel("Lossless")
        GlassCard {
            Column(modifier = Modifier.fillMaxWidth()) {
                SettingsToggleRow(
                    title = "Lossless downloads",
                    subtitle = if (uiState.losslessEnabled) {
                        "FLAC routing active. Files ~10× larger than MP3."
                    } else {
                        "Studio-quality FLAC via Qobuz. Files ~10× larger than MP3."
                    },
                    checked = uiState.losslessEnabled,
                    onCheckedChange = viewModel::onLosslessEnabledChanged,
                )

                AnimatedVisibility(
                    visible = uiState.losslessEnabled,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Spacer(modifier = Modifier.height(14.dp))

                        // v0.9.13 ROUTING block — kenny carries lossless on its
                        // own; squid is an optional second source the user can
                        // unlock inline if they want the redundancy.
                        LosslessRoutingStatus(
                            squidStatus = uiState.squidCaptchaStatus,
                            onSolveCaptcha = onNavigateToSquidWtfCaptcha,
                        )

                        // -- Download quality picker --------------------------
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Download quality",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Column(modifier = Modifier.selectableGroup()) {
                            // Order top-down: MAX → HI_RES → CD (best-quality first).
                            listOf(
                                LosslessQualityTier.MAX,
                                LosslessQualityTier.HI_RES,
                                LosslessQualityTier.CD,
                            ).forEach { tier ->
                                SettingsPickerRow(
                                    selected = uiState.losslessQualityTier == tier,
                                    title = tier.displayLabel,
                                    subtitle = tier.sizeHint,
                                    onClick = { viewModel.onLosslessQualityTierChanged(tier) },
                                )
                            }
                        }

                        // -- Streaming quality block --------------------------
                        // Per-network tier for *streaming* playback (distinct
                        // from the download tier above). Save Data is the master
                        // override: when on, both pickers are dimmed + inert and
                        // policy forces CD on every network.
                        Spacer(modifier = Modifier.height(14.dp))
                        SettingsSectionLabel("Streaming")
                        GlassCard {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                val saveData = uiState.streamingSaveData
                                val pickerAlpha = if (saveData) 0.4f else 1f

                                Text(
                                    text = "On Wi-Fi",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Column(
                                    modifier = Modifier
                                        .alpha(pickerAlpha)
                                        .selectableGroup(),
                                ) {
                                    listOf(
                                        LosslessQualityTier.MAX,
                                        LosslessQualityTier.HI_RES,
                                        LosslessQualityTier.CD,
                                    ).forEach { tier ->
                                        SettingsPickerRow(
                                            selected = uiState.streamingWifiTier == tier,
                                            title = tier.displayLabel,
                                            subtitle = tier.sizeHint,
                                            onClick = {
                                                if (!saveData) viewModel.onStreamingWifiTierChanged(tier)
                                            },
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "On cellular",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Column(
                                    modifier = Modifier
                                        .alpha(pickerAlpha)
                                        .selectableGroup(),
                                ) {
                                    listOf(
                                        LosslessQualityTier.MAX,
                                        LosslessQualityTier.HI_RES,
                                        LosslessQualityTier.CD,
                                    ).forEach { tier ->
                                        SettingsPickerRow(
                                            selected = uiState.streamingCellularTier == tier,
                                            title = tier.displayLabel,
                                            subtitle = tier.sizeHint,
                                            onClick = {
                                                if (!saveData) viewModel.onStreamingCellularTierChanged(tier)
                                            },
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                SettingsToggleRow(
                                    title = "Save Data",
                                    subtitle = "Request the lowest streaming quality on every network " +
                                        "to minimize data. Not every source honors this yet.",
                                    checked = uiState.streamingSaveData,
                                    onCheckedChange = viewModel::onStreamingSaveDataChanged,
                                    titleTrailing = { BetaPill() },
                                )
                            }
                        }

                        // -- YouTube fallback expander row (v0.9.17) -----------
                        // Hosts the relocated yt-dlp tier picker plus the
                        // master fallback toggle. Re-keyed on the losslessEnabled
                        // flip so it collapses cleanly when toggled.
                        var fallbackExpanded by remember(uiState.losslessEnabled) { mutableStateOf(false) }
                        val fallbackChevronRotation by animateFloatAsState(
                            targetValue = if (fallbackExpanded) 90f else 0f,
                            label = "fallback-chevron",
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { fallbackExpanded = !fallbackExpanded }
                                .semantics {
                                    role = Role.Button
                                    stateDescription = if (fallbackExpanded) "expanded" else "collapsed"
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                modifier = Modifier.graphicsLayer(rotationZ = fallbackChevronRotation),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "YouTube fallback",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = if (uiState.youtubeFallbackEnabled) "on" else "off",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        AnimatedVisibility(
                            visible = fallbackExpanded,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut(),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Switch(
                                        checked = uiState.youtubeFallbackEnabled,
                                        onCheckedChange = viewModel::onYoutubeFallbackChanged,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Use YouTube when lossless fails",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                                if (uiState.youtubeFallbackEnabled) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    AudioQualityPicker(
                                        selected = uiState.audioQuality,
                                        onSelected = viewModel::onQualityChanged,
                                    )
                                }
                            }
                        }

                        // -- Advanced expander row (chevron + label) -----------
                        var advancedExpanded by remember(uiState.losslessEnabled) { mutableStateOf(false) }
                        val chevronRotation by animateFloatAsState(
                            targetValue = if (advancedExpanded) 90f else 0f,
                            label = "advancedChevron",
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { advancedExpanded = !advancedExpanded }
                                .semantics {
                                    role = Role.Button
                                    // Spec §Accessibility: announce collapsed/expanded
                                    // state to screen readers.
                                    stateDescription = if (advancedExpanded) "expanded" else "collapsed"
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null, // parent Row carries role + stateDescription + label
                                modifier = Modifier.graphicsLayer(rotationZ = chevronRotation),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Advanced",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        AnimatedVisibility(
                            visible = advancedExpanded,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut(),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Or paste the captcha_verified_at cookie value directly:",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                OutlinedTextField(
                                    value = uiState.squidWtfCaptchaCookie,
                                    onValueChange = viewModel::onSquidWtfCaptchaCookieChanged,
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("captcha_verified_at value") },
                                    singleLine = true,
                                    placeholder = { Text("e.g. 1777687404951") },
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                TextButton(
                                    onClick = viewModel::onResetLosslessRateLimiter,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        text = "Reset lossless attempts",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // (c) Effects.
        SettingsSectionLabel("Effects")
        SettingsNavRow(
            title = "Equalizer",
            onClick = onNavigateToEqualizer,
        )
    }
}
