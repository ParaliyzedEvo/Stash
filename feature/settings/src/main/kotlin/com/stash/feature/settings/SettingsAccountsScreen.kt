package com.stash.feature.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.ui.components.GlassCard
import com.stash.feature.settings.components.AccountConnectionCard
import com.stash.feature.settings.components.SettingsScaffold
import com.stash.feature.settings.components.SettingsSectionLabel

/**
 * The Accounts & Sync spoke of the hub-and-spoke Settings redesign.
 *
 * Behavior-preserving relocation of the original "Accounts" section from the
 * monolithic [SettingsScreen]: the Spotify and YouTube Music connection cards
 * (each with their inline auto-save / history-sync extras), the Last.fm card,
 * and the like-mirroring opt-ins (plus the mirror-warning ack dialog). Every
 * control calls the SAME [SettingsViewModel] method the old screen used; no
 * logic is changed. The `bringIntoViewRequester` on the Last.fm card (a
 * deep-link affordance) is intentionally dropped here.
 */
@Composable
fun SettingsAccountsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val extendedColors = com.stash.core.ui.theme.StashTheme.extendedColors

    SettingsScaffold(title = "Accounts & Sync", onBack = onBack, modifier = modifier) {
        // Like-mirroring opt-in ack. Rendered at the top of the scaffold so it
        // overlays the content. Confirming writes the pref; dismissing leaves
        // mirroring off (no writes without this ack).
        uiState.pendingMirrorWarning?.let { dest ->
            com.stash.feature.settings.components.LikeMirrorWarningDialog(
                serviceName = if (dest == com.stash.core.data.social.Destination.SPOTIFY) "Spotify" else "YouTube Music",
                onConfirm = viewModel::onMirrorWarningConfirmed,
                onDismiss = viewModel::onMirrorWarningDismissed,
            )
        }

        SettingsSectionLabel("Connections")

        AccountConnectionCard(
            serviceName = "Spotify",
            icon = Icons.Rounded.MusicNote,
            accentColor = extendedColors.spotifyGreen,
            authState = uiState.spotifyAuthState,
            onConnect = viewModel::onConnectSpotify,
            onDisconnect = viewModel::onDisconnectSpotify,
            extraContent = {
                com.stash.feature.settings.components.SpotifyAutoSaveSection(
                    enabled = uiState.autoSaveEnabled,
                    threshold = uiState.autoSaveThreshold,
                    autoSavedCountLast7Days = uiState.autoSavedCountLast7Days,
                    spotifyConnected = uiState.spotifyAuthState is com.stash.core.auth.model.AuthState.Connected,
                    onToggle = viewModel::onAutoSaveEnabledChanged,
                    onThresholdChanged = viewModel::onAutoSaveThresholdChanged,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            },
        )

        AccountConnectionCard(
            serviceName = "YouTube Music",
            icon = Icons.Rounded.PlayCircle,
            accentColor = extendedColors.youtubeRed,
            authState = uiState.youTubeAuthState,
            onConnect = viewModel::onConnectYouTube,
            onDisconnect = viewModel::onDisconnectYouTube,
            extraContent = {
                com.stash.feature.settings.components.YouTubeHistorySyncSection(
                    enabled = uiState.ytHistoryEnabled,
                    health = uiState.ytHistoryHealth,
                    pendingCount = uiState.ytPendingCount,
                    ytConnected = uiState.youTubeAuthState is com.stash.core.auth.model.AuthState.Connected,
                    onToggle = viewModel::onYouTubeHistoryEnabledChanged,
                    onRetry = viewModel::onRetryYouTubeHistory,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            },
        )

        // Last.fm renders via its own composable (web-auth / cookie / OAuth UX
        // differs enough from AccountConnectionCard). The deep-link
        // bringIntoViewRequester from the monolith is dropped here.
        val lastFmUriHandler = androidx.compose.ui.platform.LocalUriHandler.current
        GlassCard {
            com.stash.feature.settings.components.LastFmSection(
                state = uiState.lastFmState,
                onConnect = {
                    viewModel.onConnectLastFm { url -> runCatching { lastFmUriHandler.openUri(url) } }
                },
                onFinish = viewModel::onFinishLastFmAuth,
                onDisconnect = viewModel::onDisconnectLastFm,
                onDismissError = viewModel::onDismissLastFmError,
                onSyncScrobblesNow = viewModel::onSyncScrobblesNow,
                isScrobbleDraining = uiState.isScrobbleDraining,
            )
            uiState.scrobbleDrainResult?.let { result ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = when {
                        !result.sessionPresent -> "Connect Last.fm first."
                        result.submitted == 0 -> "No new scrobbles to send."
                        else -> "Sent ${result.submitted} scrobble${if (result.submitted == 1) "" else "s"} to Last.fm."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                androidx.compose.runtime.LaunchedEffect(result) {
                    kotlinx.coroutines.delay(3000)
                    viewModel.onClearScrobbleDrainResult()
                }
            }
        }

        SettingsSectionLabel("Sync your likes", beta = true)

        GlassCard {
            com.stash.feature.settings.components.LikeMirrorSection(
                spotifyEnabled = uiState.mirrorLikesSpotify,
                ytMusicEnabled = uiState.mirrorLikesYtMusic,
                spotifyConnected = uiState.spotifyAuthState is com.stash.core.auth.model.AuthState.Connected,
                ytConnected = uiState.youTubeAuthState is com.stash.core.auth.model.AuthState.Connected,
                onSpotifyToggle = { viewModel.onMirrorToggleRequested(com.stash.core.data.social.Destination.SPOTIFY, it) },
                onYtMusicToggle = { viewModel.onMirrorToggleRequested(com.stash.core.data.social.Destination.YT_MUSIC, it) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}
