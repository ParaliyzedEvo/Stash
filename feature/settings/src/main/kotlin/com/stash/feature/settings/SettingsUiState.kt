package com.stash.feature.settings

import com.stash.core.auth.model.AuthState
import com.stash.core.data.youtube.YouTubeScrobblerHealth
import com.stash.core.model.DownloadNetworkMode
import com.stash.core.model.QualityTier
import com.stash.core.model.ThemeMode
import com.stash.data.download.lossless.LosslessQualityTier

/**
 * Immutable UI state for the Settings screen.
 *
 * @property spotifyAuthState Current Spotify authentication lifecycle state.
 * @property youTubeAuthState Current YouTube Music authentication lifecycle state.
 * @property audioQuality Selected download / streaming quality tier.
 * @property totalStorageBytes Total bytes occupied by the music library on disk (filesystem walk via [com.stash.data.download.files.LibrarySizeHolder]).
 * @property totalTracks Number of tracks currently stored in the library.
 * @property showYouTubeCookieDialog Whether the YouTube cookie input dialog should be visible.
 * @property showSpotifyCookieDialog Whether the Spotify sp_dc cookie input dialog should be visible.
 * @property spotifyCookieError Error message to display in the Spotify cookie dialog, or null if none.
 * @property isSpotifyCookieValidating Whether the sp_dc cookie is currently being validated.
 * @property youTubeCookieError Error message to display in the YouTube cookie dialog, or null if none.
 * @property isYouTubeCookieValidating Whether the YouTube cookie is currently being validated.
 */
data class SettingsUiState(
    val spotifyAuthState: AuthState = AuthState.NotConnected,
    val youTubeAuthState: AuthState = AuthState.NotConnected,
    val audioQuality: QualityTier = QualityTier.MAX,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /**
     * Network + power conditions under which Stash runs background
     * downloads (Stash Discover, tag enrichment). Changing this in
     * Settings re-schedules both workers with new WorkManager constraints.
     */
    val downloadNetworkMode: DownloadNetworkMode = DownloadNetworkMode.WIFI_AND_CHARGING,
    val ytHistoryEnabled: Boolean = false,
    val ytHistoryHealth: YouTubeScrobblerHealth = YouTubeScrobblerHealth.DISABLED,
    val ytPendingCount: Int = 0,
    /**
     * Master switch for the lossless-source download path
     * (squid.wtf-proxied Qobuz). On by default as of v0.9.8 — every
     * track is routed through the registry first and falls back to
     * yt-dlp only when no source has a confident lossless match (or
     * the captcha is unverified). Files end up 5–10× larger than Opus.
     *
     * Existing v0.9.7 users who explicitly toggled this off keep their
     * saved `false`; v0.9.7 users who never opened the toggle pick up
     * the new default (functionally identical to v0.9.7 behaviour
     * because captcha-unverified silently falls back to yt-dlp/MP3).
     */
    val losslessEnabled: Boolean = true,
    /**
     * Selected lossless quality tier (CD / Hi-Res / Max). Defaults to HI_RES
     * which matches [LosslessSourcePreferences]'s stored default. The actual
     * first-emission from the DataStore flow replaces this within a few ms on
     * cold start; the default only guards the brief pre-emission window.
     */
    val losslessQualityTier: LosslessQualityTier = LosslessQualityTier.HI_RES,
    /**
     * Manually-pasted `captcha_verified_at` cookie value from
     * `qobuz.squid.wtf`. Bridges the captcha gate until WebView-based
     * automation lands — user solves ALTCHA in their browser, copies
     * the cookie value, pastes here. Empty string == not configured.
     */
    val squidWtfCaptchaCookie: String = "",
    val totalStorageBytes: Long = 0,
    val totalTracks: Int = 0,
    val showYouTubeCookieDialog: Boolean = false,
    val showSpotifyWebLogin: Boolean = false,
    val showSpotifyCookieDialog: Boolean = false,
    val spotifyCookieError: String? = null,
    val isSpotifyCookieValidating: Boolean = false,
    val youTubeCookieError: String? = null,
    val isYouTubeCookieValidating: Boolean = false,
    val youTubeError: String? = null,
    /**
     * User-selected SAF tree URI for external storage (SD card / USB-OTG /
     * any folder). Null = using the app's internal music directory. When
     * non-null, new downloads are written there via ContentResolver.
     */
    val externalTreeUri: android.net.Uri? = null,
    /**
     * Live state of the one-shot "Move existing library" migration. The
     * Settings UI watches this to render progress, a Done banner, or an
     * Error message when the user migrates their internal library to an
     * external SAF target.
     */
    val moveLibraryState: com.stash.data.download.files.MoveLibraryState =
        com.stash.data.download.files.MoveLibraryState.Idle,
    /** Last.fm connection state — drives the Settings → Last.fm section. */
    val lastFmState: LastFmAuthState = LastFmAuthState.NotConfigured,
    /** True while a manual scrobble-drain is in-flight. */
    val isScrobbleDraining: Boolean = false,
    /**
     * One-shot result of the most recent manual scrobble drain. Non-null
     * triggers a snackbar; the UI clears it via onClearScrobbleDrainResult.
     */
    val scrobbleDrainResult: com.stash.core.data.lastfm.LastFmScrobbler.DrainResult? = null,
)

/**
 * Connection state for the Last.fm scrobbler integration.
 *
 * - [NotConfigured]: the APK was built without a Last.fm API key / secret.
 *   UI shows a disabled card explaining the developer setup step.
 * - [Disconnected]: credentials present, user hasn't auth'd yet.
 * - [AwaitingAuth]: we requested an auth token and opened the user's
 *   browser; waiting for the user to tap "Finish connecting" after
 *   approving on Last.fm's site.
 * - [Connected]: session key stored; scrobbler is live.
 * - [Error]: something went wrong. Dismissable back to [Disconnected].
 */
sealed interface LastFmAuthState {
    data object NotConfigured : LastFmAuthState
    data object Disconnected : LastFmAuthState
    data class AwaitingAuth(val token: String) : LastFmAuthState
    data class Connected(val username: String, val pendingScrobbles: Int) : LastFmAuthState
    data class Error(val message: String) : LastFmAuthState
}
