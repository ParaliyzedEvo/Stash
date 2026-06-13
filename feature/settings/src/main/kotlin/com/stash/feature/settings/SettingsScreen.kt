package com.stash.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import android.net.Uri
import android.widget.Toast
import com.stash.core.data.sync.workers.UpdateCheckWorker
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.model.DownloadNetworkMode
import com.stash.core.model.QualityTier
import com.stash.core.model.ThemeMode
import com.stash.data.download.lossless.LosslessQualityTier
import com.stash.core.ui.components.GlassCard
import com.stash.core.ui.theme.StashTheme
import androidx.compose.material3.AlertDialog
import com.stash.feature.settings.components.AccountConnectionCard
import com.stash.feature.settings.components.AudioQualityPicker
import com.stash.feature.settings.components.SpotifyCookieDialog
import com.stash.feature.settings.components.YouTubeCookieDialog
import com.stash.feature.settings.components.YouTubeHistorySyncSection
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.IconButton

/**
 * Top-level Settings screen composable.
 *
 * Provides account connection management for Spotify and YouTube Music,
 * audio quality selection, storage statistics, and app information.
 * Spotify authentication uses the sp_dc cookie approach via [SpotifyCookieDialog].
 */
@Composable
fun SettingsScreen(
    onNavigateToEqualizer: () -> Unit = {},
    onNavigateToLibraryHealth: () -> Unit = {},
    onNavigateToSquidWtfCaptcha: () -> Unit = {},
    onNavigateToSync: () -> Unit = {},
    onNavigateToAccount: () -> Unit = {},
    onWebLoginChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val isWebLoginActive = uiState.showSpotifyWebLogin || uiState.showYouTubeWebLogin
    LaunchedEffect(isWebLoginActive) {
        onWebLoginChanged(isWebLoginActive)
    }

    // Spotify WebView login (full-screen overlay)
    if (uiState.showSpotifyWebLogin) {
        com.stash.feature.settings.components.SpotifyLoginWebView(
            onCookieExtracted = viewModel::onSpotifyWebLoginCookieExtracted,
            onDismiss = viewModel::onDismissSpotifyWebLogin,
            onManualFallback = viewModel::onConnectSpotifyManual,
        )
        return // Full-screen WebView replaces the Settings content
    }

    // YouTube Music WebView login (full-screen overlay) — Phase 1 spike
    if (uiState.showYouTubeWebLogin) {
        com.stash.feature.settings.components.YouTubeLoginWebView(
            onCookieExtracted = viewModel::onYouTubeWebLoginCookieExtracted,
            onDismiss = viewModel::onDismissYouTubeWebLogin,
            onManualFallback = viewModel::onConnectYouTubeManual,
        )
        return // Full-screen WebView replaces the Settings content
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

    // v0.9.13: Single-shot consumption of any pending Settings deep-link
    // focus from Home banners. Read once per Settings entry; cleared
    // immediately so revisiting the screen doesn't re-scroll.
    val deepLinkFocus = remember { viewModel.consumeDeepLinkFocus() }

    val storageContext = LocalContext.current
    val contentResolver = storageContext.contentResolver
    // Tracks what action the user intended when they tapped the folder
    // picker. "SetOnly" = redirect new downloads; "SetAndMove" = also start the
    // library migration to the picked folder as soon as it's granted.
    var pendingPickerIntent by remember { mutableStateOf(PickerIntent.SetOnly) }
    val treePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            // Take a persistable permission BEFORE handing the URI to the
            // VM — without this, the permission is revoked when the app
            // is backgrounded and the persisted URI becomes useless.
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            viewModel.setExternalStorageUri(uri)
            when (pendingPickerIntent) {
                PickerIntent.SetAndMove -> viewModel.startMoveLibrary(uri)
                PickerIntent.SetAndRestart -> {
                    // Success: permission re-granted for the restored folder.
                    // We can now safely restart the app.
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
                PickerIntent.SetOnly -> {
                    // Auto-scan the newly selected folder so local songs
                    // appear in the library immediately without a manual import.
                    viewModel.scanFolderForAudio(uri)
                }
            }
        }
        pendingPickerIntent = PickerIntent.SetOnly
    }

    SettingsContent(
        uiState = uiState,
        focusOnEntry = deepLinkFocus,
        onConnectSpotify = viewModel::onConnectSpotify,
        onDisconnectSpotify = viewModel::onDisconnectSpotify,
        onConnectYouTube = viewModel::onConnectYouTube,
        onDisconnectYouTube = viewModel::onDisconnectYouTube,
        onQualityChanged = viewModel::onQualityChanged,
        onDownloadNetworkModeChanged = viewModel::onDownloadNetworkModeChanged,
        onThemeChanged = viewModel::onThemeChanged,
        onSetExternalStorage = viewModel::setExternalStorageUri,
        onStartMoveLibrary = viewModel::startMoveLibrary,
        onCancelMoveLibrary = viewModel::cancelMoveLibrary,
        onDismissMoveLibrary = viewModel::dismissMoveLibrary,
        countMovableTracks = viewModel::countMovableTracks,
        onConnectLastFm = viewModel::onConnectLastFm,
        onFinishLastFmAuth = viewModel::onFinishLastFmAuth,
        onDisconnectLastFm = viewModel::onDisconnectLastFm,
        onDismissLastFmError = viewModel::onDismissLastFmError,
        onSyncScrobblesNow = viewModel::onSyncScrobblesNow,
        onClearScrobbleDrainResult = viewModel::onClearScrobbleDrainResult,
        onYouTubeHistoryEnabledChanged = viewModel::onYouTubeHistoryEnabledChanged,
        onStashMixesEnabledChanged = viewModel::onStashMixesEnabledChanged,
        onRetryYouTubeHistory = viewModel::onRetryYouTubeHistory,
        onLosslessEnabledChanged = viewModel::onLosslessEnabledChanged,
        onLosslessQualityTierChanged = viewModel::onLosslessQualityTierChanged,
        onYoutubeFallbackChanged = viewModel::onYoutubeFallbackChanged,
        onSquidWtfCaptchaCookieChanged = viewModel::onSquidWtfCaptchaCookieChanged,
        onResetLosslessRateLimiter = viewModel::onResetLosslessRateLimiter,
        onAutoSaveEnabledChanged = viewModel::onAutoSaveEnabledChanged,
        onAutoSaveThresholdChanged = viewModel::onAutoSaveThresholdChanged,
        onHeartDefaultStashChanged = viewModel::onHeartDefaultStashChanged,
        onHeartDefaultSpotifyChanged = viewModel::onHeartDefaultSpotifyChanged,
        onHeartDefaultYtMusicChanged = viewModel::onHeartDefaultYtMusicChanged,
        onExportDatabase = viewModel::onExportDatabase,
        onImportDatabase = viewModel::onImportDatabase,
        onConfirmImportDatabase = {
            viewModel.onConfirmImportDatabase { restoredUri ->
                pendingPickerIntent = PickerIntent.SetAndRestart
                treePicker.launch(restoredUri)
            }
        },
        onCancelImportDatabase = viewModel::onCancelImportDatabase,
        onDismissDatabaseBackupStatus = viewModel::onDismissDatabaseBackupStatus,
        onNavigateToEqualizer = onNavigateToEqualizer,
        onNavigateToLibraryHealth = onNavigateToLibraryHealth,
        onNavigateToSquidWtfCaptcha = onNavigateToSquidWtfCaptcha,
        onNavigateToSync = onNavigateToSync,
        onNavigateToAccount = onNavigateToAccount,
        onShareLatestCrashReport = viewModel::latestCrashShareTarget,
        onDiagnosticsRefresh = viewModel::refreshDiagnostics,
        onTestYtDlp = viewModel::testYtDlp,
        onUpdateYtDlp = viewModel::updateYtDlp,
        streamingEnabled = viewModel.streamingEnabled.collectAsStateWithLifecycle().value,
        onStreamingToggle = viewModel::onStreamingToggle,
        streamOnCellular = viewModel.streamOnCellular.collectAsStateWithLifecycle().value,
        onStreamOnCellularToggle = viewModel::onStreamOnCellularToggle,
        showBlurLayerInAmoled = viewModel.showBlurLayerInAmoled.collectAsStateWithLifecycle().value,
        onShowBlurLayerInAmoledChanged = viewModel::onShowBlurLayerInAmoledChanged,
        forceYouTubeFallback = viewModel.forceYouTubeFallback.collectAsStateWithLifecycle().value,
        onToggleForceYouTubeFallback = viewModel::setForceYouTubeFallback,
        forceAntraOnly = viewModel.forceAntraOnly.collectAsStateWithLifecycle().value,
        onToggleForceAntraOnly = viewModel::setForceAntraOnly,
        treePicker = treePicker,
        onSetPickerIntent = { pendingPickerIntent = it },
        modifier = modifier,
    )
}

/**
 * Stateless inner content for [SettingsScreen], accepting all state and
 * callbacks as parameters for testability and preview support.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private enum class SettingsSubScreen {
    MAIN,
    PLAYBACK,
    AUDIO_QUALITY,
    STORAGE_BACKUP,
    APPEARANCE,
    DIAGNOSTICS_ABOUT,
}

@Composable
private fun SettingsCategoryRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Stateless inner content for [SettingsScreen], accepting all state and
 * callbacks as parameters for testability and preview support.
 */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun SettingsContent(
    uiState: SettingsUiState,
    focusOnEntry: com.stash.core.data.navigation.SettingsFocus?,
    onConnectSpotify: () -> Unit,
    onDisconnectSpotify: () -> Unit,
    onConnectYouTube: () -> Unit,
    onDisconnectYouTube: () -> Unit,
    onQualityChanged: (QualityTier) -> Unit,
    onDownloadNetworkModeChanged: (DownloadNetworkMode) -> Unit,
    onThemeChanged: (ThemeMode) -> Unit,
    onSetExternalStorage: (android.net.Uri?) -> Unit,
    onStartMoveLibrary: (android.net.Uri) -> Unit,
    onCancelMoveLibrary: () -> Unit,
    onDismissMoveLibrary: () -> Unit,
    countMovableTracks: suspend () -> Int,
    onConnectLastFm: ((String) -> Unit) -> Unit,
    onFinishLastFmAuth: () -> Unit,
    onDisconnectLastFm: () -> Unit,
    onDismissLastFmError: () -> Unit,
    onSyncScrobblesNow: () -> Unit,
    onClearScrobbleDrainResult: () -> Unit,
    onYouTubeHistoryEnabledChanged: (Boolean) -> Unit,
    onStashMixesEnabledChanged: (Boolean) -> Unit,
    onRetryYouTubeHistory: () -> Unit,
    onLosslessEnabledChanged: (Boolean) -> Unit,
    onLosslessQualityTierChanged: (LosslessQualityTier) -> Unit,
    onYoutubeFallbackChanged: (Boolean) -> Unit,
    onSquidWtfCaptchaCookieChanged: (String) -> Unit,
    onResetLosslessRateLimiter: () -> Unit,
    onAutoSaveEnabledChanged: (Boolean) -> Unit,
    onAutoSaveThresholdChanged: (Int) -> Unit,
    onHeartDefaultStashChanged: (Boolean) -> Unit,
    onHeartDefaultSpotifyChanged: (Boolean) -> Unit,
    onHeartDefaultYtMusicChanged: (Boolean) -> Unit,
    onExportDatabase: (Uri) -> Unit,
    onImportDatabase: (Uri) -> Unit,
    onConfirmImportDatabase: () -> Unit,
    onCancelImportDatabase: () -> Unit,
    onDismissDatabaseBackupStatus: () -> Unit,
    onNavigateToEqualizer: () -> Unit,
    onNavigateToLibraryHealth: () -> Unit,
    onNavigateToSquidWtfCaptcha: () -> Unit,
    onNavigateToSync: () -> Unit,
    onNavigateToAccount: () -> Unit,
    /**
     * Returns the latest crash file + its FileProvider URI, or null if
     * none exists. Called only when the user taps the share button —
     * never on composition.
     */
    onShareLatestCrashReport: () -> SettingsViewModel.CrashShareTarget?,
    /**
     * Re-list `cacheDir/crashes/` to refresh [SettingsUiState.hasCrashReport].
     * Triggered on entry so the button reflects current disk state even
     * when the user navigates away and comes back.
     */
    onDiagnosticsRefresh: () -> Unit,
    onTestYtDlp: () -> Unit,
    onUpdateYtDlp: () -> Unit,
    treePicker: androidx.activity.result.ActivityResultLauncher<android.net.Uri?>?,
    onSetPickerIntent: (PickerIntent) -> Unit,
    /** Live streaming-mode (Online vs Offline) — see [SettingsViewModel.streamingEnabled]. */
    streamingEnabled: Boolean,
    /** Routed to [SettingsViewModel.onStreamingToggle] in the host. */
    onStreamingToggle: (Boolean) -> Unit,
    /** Live "stream on cellular" pref — see [SettingsViewModel.streamOnCellular]. */
    streamOnCellular: Boolean,
    /** Routed to [SettingsViewModel.onStreamOnCellularToggle] in the host. */
    onStreamOnCellularToggle: (Boolean) -> Unit,
    /** Live blur layer visibility for AMOLED mode. */
    showBlurLayerInAmoled: Boolean,
    /** Routed to [SettingsViewModel.onShowBlurLayerInAmoledChanged] in the host. */
    onShowBlurLayerInAmoledChanged: (Boolean) -> Unit,
    /** Live "force YouTube fallback" test pref — see [SettingsViewModel.forceYouTubeFallback]. */
    forceYouTubeFallback: Boolean,
    /** Routed to [SettingsViewModel.setForceYouTubeFallback] in the host. */
    onToggleForceYouTubeFallback: (Boolean) -> Unit,
    /** Live "force antra only" test pref — see [SettingsViewModel.forceAntraOnly]. */
    forceAntraOnly: Boolean,
    /** Routed to [SettingsViewModel.setForceAntraOnly] in the host. */
    onToggleForceAntraOnly: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val extendedColors = StashTheme.extendedColors
    var activeSubScreen by androidx.compose.runtime.saveable.rememberSaveable {
        mutableStateOf(SettingsSubScreen.MAIN)
    }

    if (activeSubScreen != SettingsSubScreen.MAIN) {
        BackHandler {
            activeSubScreen = SettingsSubScreen.MAIN
        }
    }

    // deep-link scroll anchors (kept for safety)
    val losslessRequester = remember { BringIntoViewRequester() }
    val lastFmRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(focusOnEntry) {
        when (focusOnEntry) {
            com.stash.core.data.navigation.SettingsFocus.LOSSLESS -> {
                activeSubScreen = SettingsSubScreen.AUDIO_QUALITY
                losslessRequester.bringIntoView()
            }
            com.stash.core.data.navigation.SettingsFocus.LASTFM -> {
                onNavigateToAccount() // LastFm is in Account tab now!
            }
            null -> Unit
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
        if (uiState.showImportConfirmation) {
            AlertDialog(
                onDismissRequest = onCancelImportDatabase,
                containerColor = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.large,
                title = {
                    Text(
                        text = "Overwrite Library & Settings?",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                text = {
                    Text(
                        text = "This will completely replace your existing library metadata, playlists, settings, and account connections. This action cannot be undone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = onConfirmImportDatabase,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text("Overwrite")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onCancelImportDatabase) {
                        Text("Cancel")
                    }
                },
            )
        }

        uiState.pendingMirrorWarning?.let { dest ->
            LikeMirrorWarningDialog(
                serviceName = if (dest == Destination.SPOTIFY) "Spotify" else "YouTube Music",
                onConfirm = onMirrorWarningConfirmed,
                onDismiss = onMirrorWarningDismissed,
            )
        }
        if (activeSubScreen == SettingsSubScreen.MAIN) {
            // -- Header --
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )

            GlassCard {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SettingsCategoryRow(
                        icon = Icons.Default.AccountCircle,
                        title = "Account",
                        subtitle = "Spotify, YouTube Music, Last.fm connections",
                        onClick = onNavigateToAccount,
                    )
                    HorizontalDivider(color = extendedColors.glassBorder)
                    SettingsCategoryRow(
                        icon = Icons.Default.PlayArrow,
                        title = "Playback",
                        subtitle = "Online streaming, cellular data preferences",
                        onClick = { activeSubScreen = SettingsSubScreen.PLAYBACK },
                    )
                    HorizontalDivider(color = extendedColors.glassBorder)
                    SettingsCategoryRow(
                        icon = Icons.Default.Audiotrack,
                        title = "Audio Quality",
                        subtitle = "Download codec, Lossless routing, YouTube fallback",
                        onClick = { activeSubScreen = SettingsSubScreen.AUDIO_QUALITY },
                    )
                    HorizontalDivider(color = extendedColors.glassBorder)
                    SettingsCategoryRow(
                        icon = Icons.Default.Storage,
                        title = "Storage & Backup",
                        subtitle = "Download location, library moves, database backup",
                        onClick = { activeSubScreen = SettingsSubScreen.STORAGE_BACKUP },
                    )
                    HorizontalDivider(color = extendedColors.glassBorder)
                    SettingsCategoryRow(
                        icon = Icons.Default.Palette,
                        title = "Appearance",
                        subtitle = "App theme and AMOLED black mode",
                        onClick = { activeSubScreen = SettingsSubScreen.APPEARANCE },
                    )
                    HorizontalDivider(color = extendedColors.glassBorder)
                    SettingsCategoryRow(
                        icon = Icons.Default.Equalizer,
                        title = "Equalizer",
                        subtitle = "Audio frequency controls and effects",
                        onClick = onNavigateToEqualizer,
                    )
                    HorizontalDivider(color = extendedColors.glassBorder)
                    SettingsCategoryRow(
                        icon = Icons.Default.GraphicEq,
                        title = "Library Health",
                        subtitle = "Bitrate breakdown and file format checks",
                        onClick = onNavigateToLibraryHealth,
                    )
                    HorizontalDivider(color = extendedColors.glassBorder)
                    SettingsCategoryRow(
                        icon = Icons.Default.Sync,
                        title = "Sync Settings",
                        subtitle = "Auto-sync, download blacklist, failed matches",
                        onClick = onNavigateToSync,
                    )
                    HorizontalDivider(color = extendedColors.glassBorder)
                    SettingsCategoryRow(
                        icon = Icons.Default.Info,
                        title = "Diagnostics & About",
                        subtitle = "Crash reports, version details, and updates",
                        onClick = { activeSubScreen = SettingsSubScreen.DIAGNOSTICS_ABOUT },
                    )
                }
            }

            // -- Support section + Report a Bug --
            val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
            SectionHeader(title = "Support & Feedback")
            GlassCard {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "If Stash replaced a subscription for you, consider supporting the project.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = { uriHandler.openUri("https://ko-fi.com/rawnald") },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary,
                            ),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.FavoriteBorder,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Donate", style = MaterialTheme.typography.labelMedium)
                        }

                        OutlinedButton(
                            onClick = { uriHandler.openUri("https://github.com/rawnaldclark/Stash") },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary,
                            ),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Star,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Star", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    OutlinedButton(
                        onClick = { uriHandler.openUri("https://github.com/rawnaldclark/Stash/issues") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.BugReport,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Report a Bug", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            Spacer(modifier = Modifier.height(80.dp))
        } else {
            // -- Sub-screen Header --
            val title = when (activeSubScreen) {
                SettingsSubScreen.PLAYBACK -> "Playback"
                SettingsSubScreen.AUDIO_QUALITY -> "Audio Quality"
                SettingsSubScreen.STORAGE_BACKUP -> "Storage & Backup"
                SettingsSubScreen.APPEARANCE -> "Appearance"
                SettingsSubScreen.DIAGNOSTICS_ABOUT -> "Diagnostics & About"
                else -> ""
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { activeSubScreen = SettingsSubScreen.MAIN }) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }

            // -- Sub-screen Contents --
            when (activeSubScreen) {
                SettingsSubScreen.PLAYBACK -> {
                    if (com.stash.core.common.constants.StashConstants.STREAMING_ENGINE_ENABLED) {
                        GlassCard {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onStreamingToggle(!streamingEnabled) }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Online Streaming",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            text = "Stream music from online sources when connected.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Switch(
                                        checked = streamingEnabled,
                                        onCheckedChange = onStreamingToggle,
                                    )
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Stream on cellular",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            text = "Allow streaming over mobile data (5G / LTE). Off by default to avoid surprise data use.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Switch(
                                        checked = streamOnCellular,
                                        onCheckedChange = onStreamOnCellularToggle,
                                    )
                                }


                            }
                        }
                    }
                }

                SettingsSubScreen.AUDIO_QUALITY -> {
                    if (!uiState.losslessEnabled) {
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
                                    onSelected = onQualityChanged,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    GlassCard(modifier = Modifier.bringIntoViewRequester(losslessRequester)) {
                        var advancedExpanded by remember(uiState.losslessEnabled) { mutableStateOf(false) }
                        val chevronRotation by animateFloatAsState(
                            targetValue = if (advancedExpanded) 90f else 0f,
                            label = "advancedChevron",
                        )
                        var fallbackExpanded by remember(uiState.losslessEnabled) { mutableStateOf(false) }
                        val fallbackChevronRotation by animateFloatAsState(
                            targetValue = if (fallbackExpanded) 90f else 0f,
                            label = "fallback-chevron",
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectableGroup(),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Lossless downloads",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = if (uiState.losslessEnabled) {
                                            "FLAC routing active. Files ~10\u00D7 larger than MP3."
                                        } else {
                                            "Studio-quality FLAC via Qobuz. Files ~10\u00D7 larger than MP3."
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = uiState.losslessEnabled,
                                    onCheckedChange = onLosslessEnabledChanged,
                                    modifier = Modifier.semantics { role = Role.Switch },
                                )
                            }

                            AnimatedVisibility(
                                visible = uiState.losslessEnabled,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut(),
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Spacer(modifier = Modifier.height(14.dp))

                                    LosslessRoutingStatus(
                                        squidStatus = uiState.squidCaptchaStatus,
                                        onSolveCaptcha = onNavigateToSquidWtfCaptcha,
                                        antraUsername = uiState.antraUsername,
                                        onConnectAntra = onNavigateToAntraConnect,
                                    )

                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "Lossless quality",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Column(modifier = Modifier.selectableGroup()) {
                                        listOf(
                                            LosslessQualityTier.MAX,
                                            LosslessQualityTier.HI_RES,
                                            LosslessQualityTier.CD,
                                        ).forEach { tier ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .selectable(
                                                        selected = uiState.losslessQualityTier == tier,
                                                        onClick = { onLosslessQualityTierChanged(tier) },
                                                        role = Role.RadioButton,
                                                    )
                                                    .padding(vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                RadioButton(
                                                    selected = uiState.losslessQualityTier == tier,
                                                    onClick = null,
                                                    colors = RadioButtonDefaults.colors(
                                                        selectedColor = MaterialTheme.colorScheme.primary,
                                                        unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    ),
                                                )
                                                Column(modifier = Modifier.padding(start = 8.dp)) {
                                                    Text(
                                                        text = tier.displayLabel,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                    )
                                                    Text(
                                                        text = tier.sizeHint,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                        }
                                    }

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
                                                    onCheckedChange = onYoutubeFallbackChanged,
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
                                                    onSelected = onQualityChanged,
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { advancedExpanded = !advancedExpanded }
                                            .semantics {
                                                role = Role.Button
                                                stateDescription = if (advancedExpanded) "expanded" else "collapsed"
                                            }
                                            .padding(vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                            contentDescription = null,
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
                                                onValueChange = onSquidWtfCaptchaCookieChanged,
                                                modifier = Modifier.fillMaxWidth(),
                                                label = { Text("captcha_verified_at value") },
                                                singleLine = true,
                                                placeholder = { Text("e.g. 1777687404951") },
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            TextButton(
                                                onClick = onResetLosslessRateLimiter,
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
                }

                SettingsSubScreen.STORAGE_BACKUP -> {
                    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip"),
                    ) { uri ->
                        if (uri != null) onExportDatabase(uri)
                    }
                    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
                    ) { uri ->
                        if (uri != null) onImportDatabase(uri)
                    }

                    val storageContext = LocalContext.current
                    val externalTree = uiState.externalTreeUri
                    val externalFolderName = remember(externalTree) {
                        externalTree?.lastPathSegment
                            ?.substringAfterLast(':', "")
                            ?.substringAfterLast('/', "")
                            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                            ?.takeIf { it.isNotBlank() }
                            ?: externalTree?.let { "External folder" }
                            ?: ""
                    }
                    val internalPath = remember(storageContext) {
                        java.io.File(storageContext.filesDir, "music").absolutePath
                    }

                    GlassCard {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Backup",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Export settings and database or import from a previous backup.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedButton(
                                    onClick = { exportLauncher.launch("stash-backup.zip") },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.primary,
                                    ),
                                ) {
                                    Text("Export Backup")
                                }
                                OutlinedButton(
                                    onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.primary,
                                    ),
                                ) {
                                    Text("Import Backup")
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    GlassCard {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            StorageRow(label = "Total tracks", value = "${uiState.totalTracks}")
                            Spacer(modifier = Modifier.height(8.dp))
                            StorageRow(label = "Storage used", value = formatBytes(uiState.totalStorageBytes))
                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider(color = extendedColors.glassBorder)
                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                text = "Download location",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = when {
                                    externalTree == null -> "Internal (app-private)"
                                    externalFolderName.isBlank() -> "External folder (SD card / USB)"
                                    else -> externalFolderName
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (externalTree != null) {
                                    "Tracks are stored in this folder and survive uninstall. Visible to other apps and over USB."
                                } else {
                                    internalPath
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        onSetPickerIntent(PickerIntent.SetOnly)
                                        treePicker?.launch(null)
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.primary,
                                    ),
                                ) {
                                    Text(
                                        text = if (externalTree != null) "Change folder" else "Pick SD / folder",
                                    )
                                }
                                if (externalTree != null) {
                                    OutlinedButton(
                                        onClick = { onSetExternalStorage(null) },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = MaterialTheme.colorScheme.primary,
                                        ),
                                    ) {
                                        Text("Use internal")
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "New downloads go to the selected location.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            var movableCount by remember { mutableStateOf<Int?>(null) }
                            LaunchedEffect(uiState.moveLibraryState, externalTree) {
                                if (uiState.moveLibraryState !is com.stash.data.download.files.MoveLibraryState.Running) {
                                    movableCount = runCatching { countMovableTracks() }.getOrNull()
                                }
                            }

                            val showMoveSection = when (uiState.moveLibraryState) {
                                com.stash.data.download.files.MoveLibraryState.Idle ->
                                    (movableCount ?: 0) > 0
                                else -> true
                            }

                            if (showMoveSection) {
                                Spacer(modifier = Modifier.height(12.dp))
                                HorizontalDivider(color = extendedColors.glassBorder)
                                Spacer(modifier = Modifier.height(12.dp))
                                MoveLibrarySection(
                                    state = uiState.moveLibraryState,
                                    hasExternalFolder = externalTree != null,
                                    movableCount = movableCount ?: 0,
                                    onStart = {
                                        if (externalTree != null) {
                                            onStartMoveLibrary(externalTree)
                                        } else {
                                            onSetPickerIntent(PickerIntent.SetAndMove)
                                            treePicker?.launch(null)
                                        }
                                    },
                                    onCancel = onCancelMoveLibrary,
                                    onDismiss = onDismissMoveLibrary,
                                )
                            }
                        }
                    }

                    // Streaming-source override. Relocated here from Diagnostics
                    // so users can self-rescue when the lossless sources (Qobuz
                    // via Squid/Kennyy) are down — routes ALL streaming through
                    // YouTube. Backed by StreamingPreference.forceYouTubeFallback.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Stream via YouTube",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Skip the lossless sources (Qobuz) and stream everything via YouTube. Turn this on if lossless playback is down or only playing short clips.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = forceYouTubeFallback,
                            onCheckedChange = onToggleForceYouTubeFallback,
                        )
                    }

                    // Test-only outage drill for the antra fallback: routes
                    // streaming AND downloads through antra alone (no Qobuz
                    // proxies, no YouTube), so the antra path can be verified
                    // end-to-end. Backed by StreamingPreference.forceAntraOnly.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Force antra only (test)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Route streaming and downloads through antra alone — no Qobuz proxies, no YouTube. Each antra fetch spends a single from your quota. Turn off after testing.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = forceAntraOnly,
                            onCheckedChange = onToggleForceAntraOnly,
                        )
                    }
                }

                SettingsSubScreen.APPEARANCE -> {
                    GlassCard {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectableGroup(),
                        ) {
                            Text(
                                text = "Theme",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            val themeOptions = listOf(
                                ThemeMode.LIGHT to "Light",
                                ThemeMode.DARK to "Dark",
                                ThemeMode.SYSTEM to "Follow system",
                                ThemeMode.AMOLED to "Pitch Black (AMOLED)",
                            )
                            themeOptions.forEach { (mode, label) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .selectable(
                                            selected = uiState.themeMode == mode,
                                            onClick = { onThemeChanged(mode) },
                                            role = Role.RadioButton,
                                        )
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        selected = uiState.themeMode == mode,
                                        onClick = null,
                                        colors = RadioButtonDefaults.colors(
                                            selectedColor = MaterialTheme.colorScheme.primary,
                                            unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        ),
                                    )
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(start = 8.dp),
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Blur layer toggle for AMOLED mode
                    if (uiState.themeMode == ThemeMode.AMOLED) {
                        GlassCard {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Show blur layer in music player",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Animated gradient background in Now Playing screen",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                androidx.compose.material3.Switch(
                                    checked = showBlurLayerInAmoled,
                                    onCheckedChange = onShowBlurLayerInAmoledChanged,
                                    colors = androidx.compose.material3.SwitchDefaults.colors(
                                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                                        checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                    ),
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }

                SettingsSubScreen.DIAGNOSTICS_ABOUT -> {
                    val diagnosticsContext = LocalContext.current
                    LaunchedEffect(Unit) { onDiagnosticsRefresh() }

                    GlassCard {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Share latest crash report",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (uiState.hasCrashReport) {
                                    "Attach the most recent crash log to email or chat. Stays on device until you share."
                                } else {
                                    "No recent crashes."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(
                                enabled = uiState.hasCrashReport,
                                onClick = {
                                    val target = onShareLatestCrashReport()
                                    if (target == null) {
                                        Toast.makeText(
                                            diagnosticsContext,
                                            "No crash report to share.",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                        return@OutlinedButton
                                    }
                                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(android.content.Intent.EXTRA_STREAM, target.contentUri)
                                        putExtra(
                                            android.content.Intent.EXTRA_SUBJECT,
                                            "Stash crash report",
                                        )
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    val chooser = android.content.Intent.createChooser(send, "Share crash report")
                                        .apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                                    runCatching { diagnosticsContext.startActivity(chooser) }
                                        .onFailure {
                                            Toast.makeText(
                                                diagnosticsContext,
                                                "No app available to share the report.",
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.primary,
                                ),
                            ) {
                                Text("Share latest crash report")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    GlassCard {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "yt-dlp Engine",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Update the yt-dlp binary to the latest nightly for YouTube signature fixes, or test the extractor.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                OutlinedButton(
                                    onClick = onUpdateYtDlp,
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.primary,
                                    ),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Update")
                                }
                                OutlinedButton(
                                    onClick = onTestYtDlp,
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.primary,
                                    ),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Test yt-dlp")
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    val aboutContext = LocalContext.current
                    val installedVersion = remember(aboutContext) {
                        runCatching {
                            aboutContext.packageManager
                                .getPackageInfo(aboutContext.packageName, 0)
                                .versionName
                        }.getOrNull() ?: "0.3.5-beta.1"
                    }

                    GlassCard {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            StorageRow(label = "Version", value = installedVersion)
                            Spacer(modifier = Modifier.height(8.dp))
                            StorageRow(label = "License", value = "GPL-3.0")
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = {
                                    UpdateCheckWorker.enqueueOneTimeCheck(aboutContext)
                                    Toast.makeText(
                                        aboutContext,
                                        "Checking for updates\u2026",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.primary,
                                ),
                            ) {
                                Text("Check for updates")
                            }
                        }
                    }
                }
                SettingsSubScreen.MAIN -> {}
            }

            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}
}

/**
 * Renders the Last.fm connection card. Four states from the VM drive
 * the layout: NotConfigured (disabled explanation), Disconnected
 * (Connect button), AwaitingAuth (Finish connecting after browser),
 * Connected (username + pending scrobbles + Disconnect), Error
 * (message + Dismiss).
 */
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
                    androidx.compose.material3.OutlinedButton(
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
                androidx.compose.material3.Button(
                    onClick = onFinish,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Finish connecting")
                }
                Spacer(modifier = Modifier.height(8.dp))
                androidx.compose.material3.TextButton(
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
                // "Sync scrobbles now" — manual drain. Useful right after
                // the Last.fm connect handshake when cold-start import +
                // accumulated local plays can leave hundreds queued up.
                androidx.compose.material3.Button(
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
                androidx.compose.material3.OutlinedButton(
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
                androidx.compose.material3.TextButton(
                    onClick = onDismissError,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Dismiss")
                }
            }
        }
    }
}

/**
 * Tracks what the user meant when they tapped the SAF folder-picker.
 * [SetOnly] = redirect new downloads; [SetAndMove] = also start the
 * library migration to the picked folder as soon as it's granted.
 */
private enum class PickerIntent { SetOnly, SetAndMove, SetAndRestart }

/**
 * Renders the "Move existing library" action inside the Storage card.
 *
 * Shows four visual states driven by the underlying
 * [com.stash.data.download.files.MoveLibraryState]:
 * - **Idle** — prompt + "Move library to this folder" button.
 * - **Running(c, t)** — live progress ("Moving c of t...") + linear bar + Cancel.
 * - **Done(moved, failed)** — result summary + Dismiss.
 * - **Error(msg)** — error text + Dismiss.
 */
@Composable
private fun MoveLibrarySection(
    state: com.stash.data.download.files.MoveLibraryState,
    hasExternalFolder: Boolean,
    movableCount: Int,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        com.stash.data.download.files.MoveLibraryState.Idle -> {
            Text(
                text = "Existing library",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (hasExternalFolder) {
                    "Move $movableCount track${if (movableCount == 1) "" else "s"} still on your device into the folder above so they're accessible over USB too."
                } else {
                    "Move $movableCount track${if (movableCount == 1) "" else "s"} on your device to an external folder (SD / USB) so you can access them over USB too. You'll pick the destination next."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            androidx.compose.material3.OutlinedButton(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text(
                    text = if (hasExternalFolder) {
                        "Move $movableCount track${if (movableCount == 1) "" else "s"} to this folder"
                    } else {
                        "Pick destination and move library"
                    },
                )
            }
        }
        is com.stash.data.download.files.MoveLibraryState.Running -> {
            Text(
                text = "Moving ${state.current} of ${state.total}...",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(6.dp))
            androidx.compose.material3.LinearProgressIndicator(
                progress = {
                    if (state.total == 0) 0f
                    else state.current.toFloat() / state.total.toFloat()
                },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            androidx.compose.material3.TextButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Cancel")
            }
        }
        is com.stash.data.download.files.MoveLibraryState.Done -> {
            Text(
                text = buildString {
                    append("Moved ${state.moved} track")
                    if (state.moved != 1) append("s")
                    if (state.failed > 0) {
                        append(" • ${state.failed} failed")
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (state.failed > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Failed tracks stay in internal storage. Try again later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            androidx.compose.material3.TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Dismiss")
            }
        }
        is com.stash.data.download.files.MoveLibraryState.Error -> {
            Text(
                text = "Couldn't move library",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            androidx.compose.material3.TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Dismiss")
            }
        }
    }
}

/**
 * A styled section header label.
 */
@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * A horizontal row displaying a label on the left and a value on the right.
 */
@Composable
private fun StorageRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * v0.9.13 — ROUTING status block for the lossless source chain.
 *
 * Replaces the legacy "Connect to squid.wtf" CTA which falsely implied
 * lossless required a captcha to function. Reality: kenny carries
 * lossless without auth or captcha; squid is an optional second source
 * that the user can unlock via captcha. When squid is down, kenny
 * silently fills in.
 *
 * Visual is dublab-influenced: mono caps header, indented `↳` rows,
 * small status dots (filled = configured, outlined = optional). Solve
 * link inline on the squid row when no cookie is set.
 *
 * Honesty caveat: we don't have ping/health telemetry yet, so we never
 * claim "live" — we use "active" (= configured and reachable in the
 * resolver chain). v0.9.14 can add real-time health based on the
 * AggregatorRateLimiter / source-success cache.
 */
@Composable
private fun LosslessRoutingStatus(
    squidStatus: com.stash.feature.settings.components.SquidCaptchaStatus,
    onSolveCaptcha: () -> Unit,
    antraUsername: String?,
    onConnectAntra: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mono = androidx.compose.ui.text.font.FontFamily.Monospace
    val (squidConfigured, squidLabel, showSolveLink) = when (squidStatus) {
        com.stash.feature.settings.components.SquidCaptchaStatus.NotConfigured ->
            Triple(false, "optional", true)
        com.stash.feature.settings.components.SquidCaptchaStatus.Active ->
            Triple(true, "active", false)
        com.stash.feature.settings.components.SquidCaptchaStatus.Expired ->
            // Cookie present but server-rejected — keep the dot filled
            // (user did set it up) but surface "expired" + the solver
            // entry-point so they can re-verify in one tap.
            Triple(true, "expired", true)
    }
    val antraConnected = antraUsername != null
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "ROUTING",
            fontFamily = mono,
            style = MaterialTheme.typography.labelSmall.copy(
                letterSpacing = 1.2.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        RoutingRow(
            host = "kennyy.com.br",
            configured = true,
            statusLabel = "active",
        )
        RoutingRow(
            host = "squid.wtf",
            configured = squidConfigured,
            statusLabel = squidLabel,
            actionLabel = if (showSolveLink) "solve captcha \u2192" else null,
            onAction = if (showSolveLink) onSolveCaptcha else null,
        )
        RoutingRow(
            host = "antra.hoshi.cfd",
            configured = antraConnected,
            statusLabel = if (antraConnected) antraUsername!! else "optional",
            actionLabel = if (!antraConnected) "connect \u2192" else "reconnect \u2192",
            onAction = onConnectAntra,
        )
        RoutingRow(
            host = "YouTube",
            configured = true,
            statusLabel = "fallback",
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Tracks resolve top-to-bottom. Adding squid/antra gives backup hosts when kennyy is down.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Single row inside [LosslessRoutingStatus]: indent arrow, host name,
 * status dot + label. Optional action link (e.g. "solve captcha →") on
 * the right when the source needs user setup.
 */
@Composable
private fun RoutingRow(
    host: String,
    configured: Boolean,
    statusLabel: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val mono = androidx.compose.ui.text.font.FontFamily.Monospace
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "\u21B3",
            fontFamily = mono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 6.dp),
        )
        Text(
            text = host,
            fontFamily = mono,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        // Status dot — filled-primary when configured, outlined-muted when not.
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    if (configured) MaterialTheme.colorScheme.primary
                    else androidx.compose.ui.graphics.Color.Transparent,
                )
                .border(
                    width = if (configured) 0.dp else 1.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = CircleShape,
                ),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = statusLabel,
            fontFamily = mono,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = actionLabel,
                fontFamily = mono,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onAction),
            )
        }
    }
}

/**
 * Formats a byte count into a human-readable string (B, KB, MB, GB).
 */
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    val safeIndex = digitGroups.coerceIn(0, units.lastIndex)
    return "%.1f %s".format(bytes / Math.pow(1024.0, safeIndex.toDouble()), units[safeIndex])
}
