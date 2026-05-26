package com.stash.app.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
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
import com.stash.feature.nowplaying.NowPlayingScreen
import com.stash.feature.search.AlbumDiscoveryScreen
import com.stash.feature.search.ArtistProfileScreen
import com.stash.feature.search.SearchScreen
import com.stash.feature.settings.AccountScreen
import com.stash.feature.settings.BlockedSongsScreen
import com.stash.feature.settings.SettingsScreen
import com.stash.feature.settings.equalizer.EqualizerScreen
import com.stash.feature.settings.libraryhealth.LibraryHealthScreen
import com.stash.feature.sync.FailedDownloadsScreen
import com.stash.feature.sync.FailedMatchesScreen
import com.stash.feature.sync.SyncScreen

/** Transition duration for the Now Playing slide animation in milliseconds. */
private const val SLIDE_DURATION_MS = 350

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
            if (isTopLevel(initialRoute) && isTopLevel(targetRoute)) {
                fadeIn(
                    animationSpec = spring(
                        stiffness = Spring.StiffnessMediumLow,
                        dampingRatio = Spring.DampingRatioNoBouncy
                    )
                ) + scaleIn(
                    initialScale = 0.98f,
                    animationSpec = spring(
                        stiffness = Spring.StiffnessMediumLow,
                        dampingRatio = Spring.DampingRatioNoBouncy
                    )
                )
            } else {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = spring(
                        stiffness = Spring.StiffnessMediumLow,
                        dampingRatio = Spring.DampingRatioNoBouncy
                    )
                ) + fadeIn(
                    animationSpec = spring(
                        stiffness = Spring.StiffnessMediumLow,
                        dampingRatio = Spring.DampingRatioNoBouncy
                    )
                )
            }
        },
        exitTransition = {
            val initialRoute = initialState.destination.route
            val targetRoute = targetState.destination.route
            if (isTopLevel(initialRoute) && isTopLevel(targetRoute)) {
                fadeOut(
                    animationSpec = spring(
                        stiffness = Spring.StiffnessMediumLow,
                        dampingRatio = Spring.DampingRatioNoBouncy
                    )
                ) + scaleOut(
                    targetScale = 1.02f,
                    animationSpec = spring(
                        stiffness = Spring.StiffnessMediumLow,
                        dampingRatio = Spring.DampingRatioNoBouncy
                    )
                )
            } else {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = spring(
                        stiffness = Spring.StiffnessMediumLow,
                        dampingRatio = Spring.DampingRatioNoBouncy
                    )
                ) + fadeOut(
                    animationSpec = spring(
                        stiffness = Spring.StiffnessMediumLow,
                        dampingRatio = Spring.DampingRatioNoBouncy
                    )
                )
            }
        },
        popEnterTransition = {
            val initialRoute = initialState.destination.route
            val targetRoute = targetState.destination.route
            if (isTopLevel(initialRoute) && isTopLevel(targetRoute)) {
                fadeIn(
                    animationSpec = spring(
                        stiffness = Spring.StiffnessMediumLow,
                        dampingRatio = Spring.DampingRatioNoBouncy
                    )
                ) + scaleIn(
                    initialScale = 0.98f,
                    animationSpec = spring(
                        stiffness = Spring.StiffnessMediumLow,
                        dampingRatio = Spring.DampingRatioNoBouncy
                    )
                )
            } else {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = spring(
                        stiffness = Spring.StiffnessMediumLow,
                        dampingRatio = Spring.DampingRatioNoBouncy
                    )
                ) + fadeIn(
                    animationSpec = spring(
                        stiffness = Spring.StiffnessMediumLow,
                        dampingRatio = Spring.DampingRatioNoBouncy
                    )
                )
            }
        },
        popExitTransition = {
            val initialRoute = initialState.destination.route
            val targetRoute = targetState.destination.route
            if (isTopLevel(initialRoute) && isTopLevel(targetRoute)) {
                fadeOut(
                    animationSpec = spring(
                        stiffness = Spring.StiffnessMediumLow,
                        dampingRatio = Spring.DampingRatioNoBouncy
                    )
                ) + scaleOut(
                    targetScale = 1.02f,
                    animationSpec = spring(
                        stiffness = Spring.StiffnessMediumLow,
                        dampingRatio = Spring.DampingRatioNoBouncy
                    )
                )
            } else {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = spring(
                        stiffness = Spring.StiffnessMediumLow,
                        dampingRatio = Spring.DampingRatioNoBouncy
                    )
                ) + fadeOut(
                    animationSpec = spring(
                        stiffness = Spring.StiffnessMediumLow,
                        dampingRatio = Spring.DampingRatioNoBouncy
                    )
                )
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
            )
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
            SettingsScreen(
                onNavigateToEqualizer = {
                    navController.navigate(EqualizerRoute)
                },
                onNavigateToLibraryHealth = {
                    navController.navigate(LibraryHealthRoute)
                },
                onNavigateToSquidWtfCaptcha = {
                    navController.navigate(SquidWtfCaptchaRoute)
                },
                onNavigateToAccount = {
                    navController.navigate(AccountRoute)
                },
                onNavigateToSync = {
                    navController.navigate(SyncRoute)
                },
                onWebLoginChanged = onWebLoginChanged,
            )
        }
        composable<AccountRoute> {
            AccountScreen(
                onNavigateBack = { navController.popBackStack() },
                onWebLoginChanged = onWebLoginChanged,
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

        composable<BlockedSongsRoute> {
            BlockedSongsScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable<PlaylistDetailRoute> {
            PlaylistDetailScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable<ArtistDetailRoute> {
            ArtistDetailScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable<AlbumDetailRoute> {
            AlbumDetailScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable<LikedSongsDetailRoute> {
            LikedSongsDetailScreen(
                onBack = { navController.popBackStack() },
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
                    animationSpec = spring(
                        stiffness = Spring.StiffnessMediumLow,
                        dampingRatio = Spring.DampingRatioNoBouncy
                    ),
                )
            },
            exitTransition = {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Down,
                    animationSpec = spring(
                        stiffness = Spring.StiffnessMediumLow,
                        dampingRatio = Spring.DampingRatioNoBouncy
                    ),
                )
            },
            popEnterTransition = {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Up,
                    animationSpec = spring(
                        stiffness = Spring.StiffnessMediumLow,
                        dampingRatio = Spring.DampingRatioNoBouncy
                    ),
                )
            },
            popExitTransition = {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Down,
                    animationSpec = spring(
                        stiffness = Spring.StiffnessMediumLow,
                        dampingRatio = Spring.DampingRatioNoBouncy
                    ),
                )
            },
        ) {
            NowPlayingScreen(
                onDismiss = { navController.popBackStack() },
            )
        }
    }
}
