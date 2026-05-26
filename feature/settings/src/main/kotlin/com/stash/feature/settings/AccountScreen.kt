package com.stash.feature.settings

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.stash.core.model.MusicSource
import com.stash.core.ui.components.GlassCard
import com.stash.core.ui.components.SectionHeader
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.settings.components.AccountConnectionCard
import com.stash.feature.settings.components.SpotifyAutoSaveSection
import com.stash.feature.settings.components.SpotifyCookieDialog
import com.stash.feature.settings.components.SpotifyLoginWebView
import com.stash.feature.settings.components.YouTubeCookieDialog
import com.stash.feature.settings.components.YouTubeHistorySyncSection
import com.stash.feature.settings.components.YouTubeLoginWebView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    onNavigateBack: () -> Unit,
    onWebLoginChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val extendedColors = StashTheme.extendedColors

    val isWebLoginActive = uiState.showSpotifyWebLogin || uiState.showYouTubeWebLogin
    LaunchedEffect(isWebLoginActive) {
        onWebLoginChanged(isWebLoginActive)
    }

    // Spotify WebView login (full-screen overlay)
    if (uiState.showSpotifyWebLogin) {
        SpotifyLoginWebView(
            onCookieExtracted = viewModel::onSpotifyWebLoginCookieExtracted,
            onDismiss = viewModel::onDismissSpotifyWebLogin,
            onManualFallback = viewModel::onConnectSpotifyManual,
        )
        return // Full-screen WebView replaces the content
    }

    // YouTube Music WebView login (full-screen overlay)
    if (uiState.showYouTubeWebLogin) {
        YouTubeLoginWebView(
            onCookieExtracted = viewModel::onYouTubeWebLoginCookieExtracted,
            onDismiss = viewModel::onDismissYouTubeWebLogin,
            onManualFallback = viewModel::onConnectYouTubeManual,
        )
        return // Full-screen WebView replaces the content
    }

    // Spotify sp_dc cookie input dialog (manual fallback)
    if (uiState.showSpotifyCookieDialog) {
        SpotifyCookieDialog(
            isValidating = uiState.isSpotifyCookieValidating,
            errorMessage = uiState.spotifyCookieError,
            onConnect = { cookie, username -> viewModel.onConnectSpotifyWithCookie(cookie, username) },
            onDismiss = viewModel::onDismissSpotifyCookieDialog,
        )
    }

    // YouTube Music cookie input dialog
    if (uiState.showYouTubeCookieDialog) {
        YouTubeCookieDialog(
            isValidating = uiState.isYouTubeCookieValidating,
            errorMessage = uiState.youTubeCookieError,
            onConnect = viewModel::onConnectYouTubeWithCookie,
            onDismiss = viewModel::onDismissYouTubeCookieDialog,
        )
    }

    // YouTube error dialog (missing credentials, network failure, etc.)
    if (uiState.youTubeError != null) {
        AlertDialog(
            onDismissRequest = viewModel::onDismissYouTubeError,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            title = {
                Text(
                    text = "YouTube Music",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Text(
                    text = uiState.youTubeError!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::onDismissYouTubeError) {
                    Text("OK")
                }
            },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Account Settings") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            SectionHeader(title = "Linked Accounts")

            AccountConnectionCard(
                serviceName = "Spotify",
                icon = Icons.Rounded.MusicNote,
                accentColor = extendedColors.spotifyGreen,
                authState = uiState.spotifyAuthState,
                onConnect = viewModel::onConnectSpotify,
                onDisconnect = viewModel::onDisconnectSpotify,
                extraContent = {
                    SpotifyAutoSaveSection(
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
                    YouTubeHistorySyncSection(
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

            val lastFmUriHandler = LocalUriHandler.current
            GlassCard {
                Column(modifier = Modifier.fillMaxWidth()) {
                    LastFmSection(
                        state = uiState.lastFmState,
                        onConnect = {
                            viewModel.onConnectLastFm { url ->
                                runCatching { lastFmUriHandler.openUri(url) }
                            }
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
                        LaunchedEffect(result) {
                            kotlinx.coroutines.delay(3000)
                            viewModel.onClearScrobbleDrainResult()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LastFmSection(
    state: LastFmAuthState,
    onConnect: () -> Unit,
    onFinish: () -> Unit,
    onDisconnect: () -> Unit,
    onDismissError: () -> Unit,
    onSyncScrobblesNow: () -> Unit,
    isScrobbleDraining: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        when (state) {
            LastFmAuthState.NotConfigured -> {
                Text(
                    text = "Not configured",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "This build of Stash doesn't include a Last.fm API key. " +
                        "A developer rebuilding with a key in local.properties unlocks this feature.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LastFmAuthState.Disconnected -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Scrobble your plays",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    OutlinedButton(
                        onClick = onConnect,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Text("Connect Last.fm")
                    }
                }
            }
            is LastFmAuthState.AwaitingAuth -> {
                Text(
                    text = "Waiting for approval",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Your browser should be open on Last.fm. Tap \"Yes, allow access\" on their page, then come back and tap Finish below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onFinish,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Finish connecting")
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = onDismissError,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Cancel")
                }
            }
            is LastFmAuthState.Connected -> {
                Text(
                    text = "Connected as ${state.username}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (state.pendingScrobbles > 0) {
                        "Scrobbling your plays. ${state.pendingScrobbles} queued to submit."
                    } else {
                        "Scrobbling your plays. Everything up to date."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onSyncScrobblesNow,
                    enabled = !isScrobbleDraining && state.pendingScrobbles > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = when {
                            isScrobbleDraining -> "Syncing…"
                            state.pendingScrobbles == 0 -> "Nothing to sync"
                            else -> "Sync scrobbles now"
                        },
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text("Disconnect")
                }
            }
            is LastFmAuthState.Error -> {
                Text(
                    text = "Couldn't connect",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(
                    onClick = onDismissError,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Dismiss")
                }
            }
        }
    }
}
