package com.stash.app.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.stash.app.RequestNotificationPermissionOnce
import com.stash.core.ui.theme.StashTheme
import com.stash.data.download.lossless.squid.CaptchaExpiredNotifier
import com.stash.feature.nowplaying.MiniPlayer
import com.stash.feature.nowplaying.NowPlayingViewModel

/**
 * Root scaffold for the Stash app.
 *
 * Hosts the [StashNavHost], bottom navigation bar, and the [MiniPlayer]
 * which sits between the content area and the navigation bar.
 */
@Composable
fun StashScaffold(
    pendingDeepLink: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val playbackViewModel: NowPlayingViewModel = hiltViewModel()
    val playbackSnackbarHostState = remember { SnackbarHostState() }
    val showPlaybackMessages = currentRoute != NowPlayingRoute::class.qualifiedName

    // Whether a detail screen is currently in multi-select mode. Detail screens
    // signal this via `onSelectionModeChanged`; while it is true we hide the
    // whole bottom chrome (mini-player AND nav bar) so the screen's own bottom
    // selection action bar owns the bottom edge instead of stacking on / being
    // crowded by it (premium multi-select pattern, avoids mis-taps).
    var selectionActive by remember { mutableStateOf(false) }

    // Safeguard: a selection-capable screen normally clears its selection on
    // every exit path (✕ / Back / last-deselect), which fires
    // `onSelectionModeChanged(false)` before it leaves composition. Resetting on
    // route change as well guarantees the mini-player can never stay hidden if a
    // screen leaves the stack without that signal landing.
    LaunchedEffect(currentRoute) { selectionActive = false }

    // Android 13+ runtime permission for notifications. One-shot per install.
    RequestNotificationPermissionOnce()

    LaunchedEffect(playbackViewModel, showPlaybackMessages) {
        if (!showPlaybackMessages) return@LaunchedEffect
        playbackViewModel.userMessages.collect { message ->
            playbackSnackbarHostState.showSnackbar(message)
        }
    }

    // Process notification deep-link extras handed in from MainActivity.
    // Only one target right now (the captcha verifier); easy to extend
    // when more deep-link surfaces show up.
    LaunchedEffect(pendingDeepLink) {
        when (pendingDeepLink) {
            CaptchaExpiredNotifier.DEEP_LINK_TARGET -> {
                // Push Settings onto the back stack BEFORE the captcha screen.
                // SquidWtfCaptchaRoute reaches into the SettingsViewModel via
                // `navController.getBackStackEntry(SettingsRoute)` to share the
                // ViewModel's cookie-write callback — that throws
                // IllegalArgumentException ("No destination with route
                // SettingsRoute in BackStack") if Settings isn't already on the
                // stack. Cold-start from a notification has no stack history,
                // so we synthesize the parent here. When the user closes the
                // captcha screen they land on Settings — natural UX.
                navController.navigate(SettingsRoute) {
                    launchSingleTop = true
                }
                navController.navigate(SquidWtfCaptchaRoute) {
                    launchSingleTop = true
                }
                onDeepLinkConsumed()
            }
            null -> Unit
            else -> onDeepLinkConsumed()  // unknown target — clear so we don't loop
        }
    }

    // When NowPlaying is the active route, zero out the Scaffold's content
    // window insets so the player's AmbientBackground draws edge-to-edge
    // behind the status bar. Without this, the slide-down close animation
    // reveals a transparent status bar gap (the Scaffold's containerColor
    // bleeds through) because innerPadding pushes the NavHost below the
    // system bar and the player's background can't fill the gap.
    val isNowPlayingActive = currentRoute == NowPlayingRoute::class.qualifiedName

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(playbackSnackbarHostState) },
        contentWindowInsets = if (isNowPlayingActive) WindowInsets(0.dp) else WindowInsets.statusBars,
        bottomBar = {
            val hideBottomBar = currentRoute == NowPlayingRoute::class.qualifiedName ||
                                currentRoute == SquidWtfCaptchaRoute::class.qualifiedName ||
                                isWebLoginOpen
            // Hide mini player only on Settings/Account sub-pages and utility-only
            // inner pages. Content pages (playlists, artists, albums, liked songs)
            // keep the mini player visible since music is actively playing there.
            val innerPageRoutes = setOf(
                SettingsRoute::class.qualifiedName,
                AccountRoute::class.qualifiedName,
                EqualizerRoute::class.qualifiedName,
                LibraryHealthRoute::class.qualifiedName,
                BlockedSongsRoute::class.qualifiedName,
                FailedMatchesRoute::class.qualifiedName,
                FailedDownloadsRoute::class.qualifiedName,
            )
            val hideMiniPlayer = hideBottomBar ||
                                 innerPageRoutes.any { currentRoute?.startsWith(it ?: "") == true }
            // While a screen is selecting, render no bottom chrome at all — the
            // screen's own selection action bar (which handles its own nav insets)
            // takes the bottom edge. This drops innerPadding.bottom to 0 so the
            // content extends full-height behind that action bar.
            if (!selectionActive && !hideBottomBar) {
                Column(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                ) {
                    if (!hideMiniPlayer) {
                        MiniPlayer(
                            onExpand = {
                                navController.navigate(NowPlayingRoute) {
                                    launchSingleTop = true
                                }
                            },
                        viewModel = playbackViewModel,
                        )
                    }

                    StashBottomBar(
                        currentRoute = currentRoute,
                        onNavigate = { dest ->
                            val destRoute = dest.route::class.qualifiedName
                            val isOnThisTabRoot = currentRoute == destRoute
                            // If we're already on this tab's root, do nothing.
                            // If we're on a deeper page but the tab root is in the
                            // back stack, pop back to the root (clears inner pages).
                            if (isOnThisTabRoot) {
                                // Already on root — no-op (avoids reload).
                                return@StashBottomBar
                            }
                            // Try to pop back to the tab root. If it succeeds,
                            // the user was on an inner page of this tab → return.
                            val popped = navController.popBackStack(
                                route = dest.route,
                                inclusive = false,
                            )
                            if (!popped) {
                                // Not in this tab at all — standard cross-tab nav.
                                navController.navigate(dest.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        StashNavHost(
            navController = navController,
            onWebLoginChanged = { isWebLoginOpen = it },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
            onSelectionModeChanged = { selectionActive = it },
        )
    }
}

@Composable
private fun StashBottomBar(
    currentRoute: String?,
    onNavigate: (TopLevelDestination) -> Unit,
) {
    val extendedColors = StashTheme.extendedColors

    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,

    ) {
        TopLevelDestination.entries.forEach { dest ->
            val isSelected = currentRoute == dest.route::class.qualifiedName

            NavigationBarItem(
                selected = isSelected,
                onClick = { onNavigate(dest) },
                icon = {
                    Icon(
                        imageVector = if (isSelected) dest.selectedIcon else dest.unselectedIcon,
                        contentDescription = dest.label,
                    )
                },
                label = {
                    Text(text = dest.label, style = MaterialTheme.typography.labelSmall)
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = extendedColors.textTertiary,
                    unselectedTextColor = extendedColors.textTertiary,
                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                ),
            )
        }
    }
}
