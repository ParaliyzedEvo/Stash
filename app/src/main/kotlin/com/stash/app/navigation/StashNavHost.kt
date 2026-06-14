package com.stash.app.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.stash.feature.home.HomeScreen
import com.stash.feature.library.AlbumDetailScreen
import com.stash.feature.library.ArtistDetailScreen
import com.stash.feature.library.LibraryScreen
import com.stash.feature.library.LikedSongsDetailScreen
import com.stash.feature.library.PlaylistDetailScreen
import com.stash.feature.library.mixbuilder.MixBuilderScreen
import com.stash.feature.nowplaying.NowPlayingScreen
import com.stash.feature.search.AlbumDiscoveryScreen
import com.stash.feature.search.ArtistProfileScreen
import com.stash.feature.search.SearchScreen
import com.stash.feature.settings.AccountScreen
import com.stash.feature.settings.BlockedSongsScreen
import com.stash.feature.settings.SettingsHubScreen
import com.stash.feature.settings.equalizer.EqualizerScreen
import com.stash.feature.settings.libraryhealth.LibraryHealthScreen
import com.stash.feature.sync.FailedDownloadsScreen
import com.stash.feature.sync.ActiveDownloadsScreen
import com.stash.feature.sync.FailedMatchesScreen
import com.stash.feature.sync.SyncScreen

/** Transition duration for the Now Playing slide animation in milliseconds. */
private const val SLIDE_DURATION_MS = 250

/** Standard page-transition duration (ms). 200 ms is snappy and responsive
 *  while still feeling smooth — matches Material 3 brief motion.  */
private const val PAGE_DURATION_MS = 200

/** Tab cross-fade is shortest: the user expects near-instant switching. */
private const val TAB_DURATION_MS = 120

/** Material 3 standard easing (ease-out cubic). */
private val EaseOutCubic = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)

/**
 * Main navigation host for the Stash app.
 *
 * Contains all top-level tab destinations plus the full-screen Now Playing
 * route which enters with a slide-up and exits with a slide-down transition.
 * Uses smooth, premium dynamic horizontal slide and fade global page transitions.
 */
@Composable
fun StashNavHost(
    navController: NavHostController,
    onWebLoginChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    // Forwarded to detail screens that support multi-select so the host can hide
    // the mini-player while a screen is in selection mode. General by design:
    // the same lambda will be wired to Liked/Album/Artist/Library detail screens
    // in later tasks — only the Playlist destination consumes it today.
    onSelectionModeChanged: (Boolean) -> Unit = {},
) {
    val topLevelRoutes = remember {
        setOf(
            HomeRoute::class.qualifiedName,
            LibraryRoute::class.qualifiedName,
            SearchRoute::class.qualifiedName,
            AccountRoute::class.qualifiedName,
            SettingsRoute::class.qualifiedName
        )
    }

    fun isTopLevel(route: String?): Boolean {
        if (route == null) return false
        return topLevelRoutes.any { route.startsWith(it ?: "") }
    }

    NavHost(
        navController = navController,
        startDestination = HomeRoute,
        modifier = modifier,
        enterTransition = {
            val initialRoute = initialState.destination.route
            val targetRoute = targetState.destination.route
            if (targetRoute == NowPlayingRoute::class.qualifiedName) {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Up,
                    animationSpec = tween(SLIDE_DURATION_MS)
                )
            } else if (isTopLevel(initialRoute) && isTopLevel(targetRoute)) {
                fadeIn(animationSpec = tween(TAB_DURATION_MS, easing = EaseOutCubic)) +
                    scaleIn(initialScale = 0.98f, animationSpec = tween(TAB_DURATION_MS, easing = EaseOutCubic))
            } else {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(PAGE_DURATION_MS, easing = EaseOutCubic),
                ) + fadeIn(animationSpec = tween(PAGE_DURATION_MS, easing = EaseOutCubic))
            }
        },
        exitTransition = {
            val initialRoute = initialState.destination.route
            val targetRoute = targetState.destination.route
            if (targetRoute == NowPlayingRoute::class.qualifiedName) {
                fadeOut(animationSpec = tween(SLIDE_DURATION_MS))
            } else if (isTopLevel(initialRoute) && isTopLevel(targetRoute)) {
                fadeOut(animationSpec = tween(TAB_DURATION_MS, easing = EaseOutCubic)) +
                    scaleOut(targetScale = 1.02f, animationSpec = tween(TAB_DURATION_MS, easing = EaseOutCubic))
            } else {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(PAGE_DURATION_MS, easing = EaseOutCubic),
                ) + fadeOut(animationSpec = tween(PAGE_DURATION_MS, easing = EaseOutCubic))
            }
        },
        popEnterTransition = {
            val initialRoute = initialState.destination.route
            val targetRoute = targetState.destination.route
            if (initialRoute == NowPlayingRoute::class.qualifiedName) {
                fadeIn(animationSpec = tween(SLIDE_DURATION_MS))
            } else if (isTopLevel(initialRoute) && isTopLevel(targetRoute)) {
                fadeIn(animationSpec = tween(TAB_DURATION_MS, easing = EaseOutCubic)) +
                    scaleIn(initialScale = 0.98f, animationSpec = tween(TAB_DURATION_MS, easing = EaseOutCubic))
            } else {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(PAGE_DURATION_MS, easing = EaseOutCubic),
                ) + fadeIn(animationSpec = tween(PAGE_DURATION_MS, easing = EaseOutCubic))
            }
        },
        popExitTransition = {
            val initialRoute = initialState.destination.route
            val targetRoute = targetState.destination.route
            if (initialRoute == NowPlayingRoute::class.qualifiedName) {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Down,
                    animationSpec = tween(SLIDE_DURATION_MS)
                )
            } else if (isTopLevel(initialRoute) && isTopLevel(targetRoute)) {
                fadeOut(animationSpec = tween(TAB_DURATION_MS, easing = EaseOutCubic)) +
                    scaleOut(targetScale = 1.02f, animationSpec = tween(TAB_DURATION_MS, easing = EaseOutCubic))
            } else {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(PAGE_DURATION_MS, easing = EaseOutCubic),
                ) + fadeOut(animationSpec = tween(PAGE_DURATION_MS, easing = EaseOutCubic))
            }
        }
    ) {
        composable<HomeRoute> {
            HomeScreen(
                onNavigateToPlaylist = { playlistId ->
                    navController.navigate(PlaylistDetailRoute(playlistId))
                },
                onNavigateToLikedSongs = { source ->
                    navController.navigate(LikedSongsDetailRoute(source))
                },
                onNavigateToSettings = {
                    navController.navigate(SettingsRoute) {
                        // Clear top so repeated taps don't stack Settings entries.
                        launchSingleTop = true
                    }
                },
                onNavigateToRecentlyAdded = {
                    navController.navigate(RecentlyAddedRoute)
                },
                onNavigateToLocalSongs = {
                    navController.navigate(LocalSongsRoute)
                },
                onNavigateToMixBuilder = { recipeId ->
                    navController.navigate(MixBuilderRoute(recipeId))
                },
            )
        }
        composable<MixBuilderRoute> {
            MixBuilderScreen(onBack = { navController.popBackStack() })
        }
        composable<LibraryRoute> {
            LibraryScreen(
                onNavigateToPlaylist = { playlistId ->
                    navController.navigate(PlaylistDetailRoute(playlistId))
                },
                onNavigateToArtist = { artistName ->
                    navController.navigate(ArtistDetailRoute(artistName))
                },
                onNavigateToAlbum = { albumName, artistName ->
                    navController.navigate(AlbumDetailRoute(albumName, artistName))
                },
                onSelectionModeChanged = onSelectionModeChanged,
            )
        }
        composable<SearchRoute> {
            SearchScreen(
                onNavigateToArtist = { id, name, avatar ->
                    navController.navigate(SearchArtistRoute(id, name, avatar))
                },
                onNavigateToAlbum = { album ->
                    navController.navigate(
                        SearchAlbumRoute(
                            browseId = album.id,
                            title = album.title,
                            artist = album.artist,
                            thumbnailUrl = album.thumbnailUrl,
                            year = album.year,
                        ),
                    )
                },
            )
        }
        composable<SyncRoute> {
            SyncScreen(
                onNavigateToFailedMatches = {
                    navController.navigate(FailedMatchesRoute)
                },
                // Phase 8: Library actions (Blocked Songs + Fix wrong-version)
                // moved out of Settings into the Sync tab's Library section.
                onNavigateToBlockedSongs = { navController.navigate(BlockedSongsRoute) },
                onNavigateToFailedDownloads = {
                    navController.navigate(FailedDownloadsRoute)
                },
                onNavigateToSettings = {
                    navController.navigate(SettingsRoute) {
                        launchSingleTop = true
                    }
                },
            )
        }
        composable<SettingsRoute> {
            val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
            SettingsHubScreen(
                onOpenPlayback = { navController.navigate(SettingsPlaybackRoute) },
                onOpenAudioQuality = { navController.navigate(SettingsAudioQualityRoute) },
                onOpenAccounts = { navController.navigate(SettingsAccountsRoute) },
                onOpenLibraryStorage = { navController.navigate(SettingsLibraryStorageRoute) },
                onOpenAppearance = { navController.navigate(SettingsAppearanceRoute) },
                onOpenAbout = { navController.navigate(SettingsAboutRoute) },
                onDonate = { runCatching { uriHandler.openUri("https://ko-fi.com/rawnald") } },
                onStar = { runCatching { uriHandler.openUri("https://github.com/rawnaldclark/Stash") } },
            )
        }

        composable<SettingsPlaybackRoute> { backStackEntry ->
            val settingsEntry = remember(backStackEntry) { navController.getBackStackEntry(SettingsRoute) }
            val viewModel: com.stash.feature.settings.SettingsViewModel =
                androidx.hilt.navigation.compose.hiltViewModel(settingsEntry)
            com.stash.feature.settings.SettingsPlaybackScreen(
                onBack = { navController.popBackStack() },
                viewModel = viewModel,
            )
        }
        composable<SettingsAudioQualityRoute> { backStackEntry ->
            val settingsEntry = remember(backStackEntry) { navController.getBackStackEntry(SettingsRoute) }
            val viewModel: com.stash.feature.settings.SettingsViewModel =
                androidx.hilt.navigation.compose.hiltViewModel(settingsEntry)
            com.stash.feature.settings.SettingsAudioQualityScreen(
                onBack = { navController.popBackStack() },
                onNavigateToEqualizer = { navController.navigate(EqualizerRoute) },
                onNavigateToAntraConnect = { navController.navigate(AntraConnectRoute) },
                onNavigateToSquidWtfCaptcha = { navController.navigate(SquidWtfCaptchaRoute) },
                viewModel = viewModel,
            )
        }
        composable<SettingsAccountsRoute> { backStackEntry ->
            val settingsEntry = remember(backStackEntry) { navController.getBackStackEntry(SettingsRoute) }
            val viewModel: com.stash.feature.settings.SettingsViewModel =
                androidx.hilt.navigation.compose.hiltViewModel(settingsEntry)
            com.stash.feature.settings.SettingsAccountsScreen(
                onBack = { navController.popBackStack() },
                viewModel = viewModel,
            )
        }
        composable<SettingsLibraryStorageRoute> { backStackEntry ->
            val settingsEntry = remember(backStackEntry) { navController.getBackStackEntry(SettingsRoute) }
            val viewModel: com.stash.feature.settings.SettingsViewModel =
                androidx.hilt.navigation.compose.hiltViewModel(settingsEntry)
            com.stash.feature.settings.SettingsLibraryStorageScreen(
                onBack = { navController.popBackStack() },
                onNavigateToLibraryHealth = { navController.navigate(LibraryHealthRoute) },
                viewModel = viewModel,
            )
        }
        composable<SettingsAppearanceRoute> { backStackEntry ->
            val settingsEntry = remember(backStackEntry) { navController.getBackStackEntry(SettingsRoute) }
            val viewModel: com.stash.feature.settings.SettingsViewModel =
                androidx.hilt.navigation.compose.hiltViewModel(settingsEntry)
            com.stash.feature.settings.SettingsAppearanceScreen(
                onBack = { navController.popBackStack() },
                viewModel = viewModel,
            )
        }
        composable<SettingsAboutRoute> { backStackEntry ->
            val settingsEntry = remember(backStackEntry) { navController.getBackStackEntry(SettingsRoute) }
            val viewModel: com.stash.feature.settings.SettingsViewModel =
                androidx.hilt.navigation.compose.hiltViewModel(settingsEntry)
            com.stash.feature.settings.SettingsAboutScreen(
                onBack = { navController.popBackStack() },
                onNavigateToDiagnosticsPreview = { navController.navigate(DiagnosticsPreviewRoute) },
                viewModel = viewModel,
            )
        }

        composable<SquidWtfCaptchaRoute> { backStackEntry ->
            // Reach the Settings ViewModel from the parent route so the
            // captured cookie value writes to the same DataStore the
            // interceptor reads from. We grab the *Settings* nav entry
            // (not this one) so the ViewModel survives this route's
            // dispose and the value lands in prefs immediately.
            val settingsEntry = remember(backStackEntry) {
                navController.getBackStackEntry(SettingsRoute)
            }
            val viewModel: com.stash.feature.settings.SettingsViewModel =
                androidx.hilt.navigation.compose.hiltViewModel(settingsEntry)
            com.stash.feature.settings.components.SquidWtfCaptchaScreen(
                onCookieCaptured = viewModel::onSquidWtfCaptchaCookieChanged,
                onClose = { navController.popBackStack() },
            )
        }

        composable<AntraConnectRoute> { backStackEntry ->
            // Same pattern as SquidWtfCaptchaRoute: reach the Settings
            // ViewModel from the parent route so the harvested antra creds
            // write to the same store the interceptor reads, surviving this
            // route's dispose.
            val settingsEntry = remember(backStackEntry) {
                navController.getBackStackEntry(SettingsRoute)
            }
            val viewModel: com.stash.feature.settings.SettingsViewModel =
                androidx.hilt.navigation.compose.hiltViewModel(settingsEntry)
            com.stash.feature.settings.components.AntraConnectScreen(
                onConnected = viewModel::onAntraConnected,
                onClose = { navController.popBackStack() },
            )
        }

        composable<EqualizerRoute> {
            EqualizerScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable<LibraryHealthRoute> {
            LibraryHealthScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable<DiagnosticsPreviewRoute> {
            com.stash.feature.settings.diagnostics.DiagnosticsPreviewScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable<BlockedSongsRoute> {
            BlockedSongsScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable<PlaylistDetailRoute> {
            PlaylistDetailScreen(
                onBack = { navController.popBackStack() },
                onSelectionModeChanged = onSelectionModeChanged,
            )
        }

        composable<ArtistDetailRoute> {
            ArtistDetailScreen(
                onBack = { navController.popBackStack() },
                onSelectionModeChanged = onSelectionModeChanged,
            )
        }

        composable<AlbumDetailRoute> {
            AlbumDetailScreen(
                onBack = { navController.popBackStack() },
                onSelectionModeChanged = onSelectionModeChanged,
            )
        }

        composable<LikedSongsDetailRoute> {
            LikedSongsDetailScreen(
                onBack = { navController.popBackStack() },
                onSelectionModeChanged = onSelectionModeChanged,
            )
        }

        composable<FailedMatchesRoute> {
            FailedMatchesScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable<FailedDownloadsRoute> {
            FailedDownloadsScreen(onBack = { navController.popBackStack() })
        }

        composable<ActiveDownloadsRoute> {
            ActiveDownloadsScreen(onBack = { navController.popBackStack() })
        }

        composable<RecentlyAddedRoute> {
            com.stash.feature.home.RecentlyAddedScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable<LocalSongsRoute> {
            com.stash.feature.home.LocalSongsScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable<SearchArtistRoute> {
            ArtistProfileScreen(
                onBack = { navController.popBackStack() },
                onNavigateToAlbum = { album ->
                    navController.navigate(
                        SearchAlbumRoute(
                            browseId = album.id,
                            title = album.title,
                            artist = album.artist,
                            thumbnailUrl = album.thumbnailUrl,
                            year = album.year,
                        ),
                    )
                },
                onNavigateToArtist = { id, name, avatar ->
                    navController.navigate(SearchArtistRoute(id, name, avatar))
                },
            )
        }

        composable<SearchAlbumRoute> {
            AlbumDiscoveryScreen(
                onBack = { navController.popBackStack() },
                onNavigateToAlbum = { album ->
                    navController.navigate(
                        SearchAlbumRoute(
                            browseId = album.id,
                            title = album.title,
                            artist = album.artist,
                            thumbnailUrl = album.thumbnailUrl,
                            year = album.year,
                        ),
                    )
                },
            )
        }

        composable<NowPlayingRoute>(
            enterTransition = {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Up,
                    animationSpec = tween(SLIDE_DURATION_MS),
                )
            },
            exitTransition = {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Down,
                    animationSpec = tween(SLIDE_DURATION_MS),
                )
            },
            popEnterTransition = {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Up,
                    animationSpec = tween(SLIDE_DURATION_MS),
                )
            },
            popExitTransition = {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Down,
                    animationSpec = tween(SLIDE_DURATION_MS),
                )
            },
        ) {
            NowPlayingScreen(
                onDismiss = { navController.popBackStack() },
                onNavigateToArtist = { artistName ->
                    navController.navigate(ArtistDetailRoute(artistName))
                }
            )
        }
    }
}
