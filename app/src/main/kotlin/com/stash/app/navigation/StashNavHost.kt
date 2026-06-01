package com.stash.app.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
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
 */
@Composable
fun StashNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    // Forwarded to detail screens that support multi-select so the host can hide
    // the mini-player while a screen is in selection mode. General by design:
    // the same lambda will be wired to Liked/Album/Artist/Library detail screens
    // in later tasks — only the Playlist destination consumes it today.
    onSelectionModeChanged: (Boolean) -> Unit = {},
) {
    NavHost(
        navController = navController,
        startDestination = HomeRoute,
        modifier = modifier,
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
            )
        }
    }
}
