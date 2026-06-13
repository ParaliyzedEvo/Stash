package com.stash.feature.home

import com.stash.core.model.Playlist
import com.stash.core.model.Track
import com.stash.feature.home.banner.LyricsBackfillBannerState
import com.stash.feature.home.banner.MetadataBackfillBannerState
import com.stash.feature.home.banner.WaitingForLosslessBannerState

/**
 * UI state for the Home screen, combining all observable data streams
 * into a single immutable snapshot.
 *
 * Liked songs and daily mixes are split by source (Spotify / YouTube) so
 * the UI can render them in source-grouped sections with smart collapse
 * when only one source is connected.
 *
 * Note: sync-status, per-source connection booleans, and `hasEverSynced`
 * used to live here too — they powered the SyncStatusCard at the top of
 * the Home screen. The card was relocated to the Sync tab; its data
 * plumbing moved with it to `:feature:sync`'s SyncViewModel/SyncUiState.
 */
data class HomeUiState(
    /**
     * Recipe-generated Stash Mixes. Rotate daily via StashMixRefreshWorker.
     * Rendered in a dedicated Home section above Daily Mixes so users
     * recognize them as "yours" vs. imported.
     */
    val stashMixes: List<Playlist> = emptyList(),

    /** Spotify daily mixes (e.g. Daily Mix 1, Discover Weekly). */
    val spotifyMixes: List<Playlist> = emptyList(),

    /** YouTube Music mixes (e.g. My Mix 1, Discover Mix, Replay Mix). */
    val youtubeMixes: List<Playlist> = emptyList(),

    /** Recently downloaded tracks across all sources. */
    val recentlyAdded: List<Track> = emptyList(),

    /** Local (downloaded) tracks. */
    val localSongs: List<Track> = emptyList(),

    /** Liked tracks across all sources. */
    val likedSongs: List<Track> = emptyList(),

    /** Spotify liked-songs playlists (usually one — "Liked Songs"). */
    val spotifyLikedPlaylists: List<Playlist> = emptyList(),

    /** YouTube liked-songs playlists (usually one — "Liked Music"). */
    val youtubeLikedPlaylists: List<Playlist> = emptyList(),

    /** Combined Spotify liked-songs track count (sum of playlist metadata). */
    val spotifyLikedCount: Int = 0,

    /** Combined YouTube liked-songs track count (sum of playlist metadata). */
    val youtubeLikedCount: Int = 0,

    /** Custom (non-mix, non-liked) playlists shown in the grid. */
    val playlists: List<Playlist> = emptyList(),

    /**
     * Playlist ids materialized by user-defined (non-builtin) Stash Mix
     * recipes. Drives the Edit/Delete context-menu rows so they only
     * appear for mixes the user built (not the builtin recipe playlists,
     * which can't be edited or deleted from the Home sheet).
     */
    val customMixPlaylistIds: Set<Long> = emptySet(),

    /** Custom-mix playlist ids still populating — card shows a "Building…" state. */
    val buildingMixIds: Set<Long> = emptySet(),

    /** Custom-mix playlist ids whose discovery finished with no tracks. */
    val emptyMixIds: Set<Long> = emptySet(),

    /** Active sort for the Home Playlists grid. Mirrors Library's chips. */
    val playlistSortOrder: PlaylistSortOrder = PlaylistSortOrder.RECENT,

    val isLoading: Boolean = false,
    /** True while a user-initiated pull-to-refresh is in flight. */
    val isRefreshing: Boolean = false,
    /**
     * Non-null when Last.fm creds are wired but the user hasn't
     * connected yet AND there are local plays queued waiting to be
     * scrobbled. Drives the Home banner nudging them into Settings.
     */
    val lastFmPrompt: LastFmPromptState? = null,
    /**
     * Non-null when the user has not enabled lossless AND has not
     * dismissed the Home banner. Drives the "Try lossless audio"
     * banner that shows below the sync card.
     *
     * Same shape as [lastFmPrompt] but a singleton (no varying
     * fields like pendingCount); the banner copy is static.
     */
    val losslessPrompt: LosslessPromptState? = null,

    /**
     * v0.9.13: live tip-jar state. Drives the Home pill (compact
     * `$X/$Y` indicator) and the Tip Jar bottom sheet. Sourced from
     * [com.stash.core.data.tipjar.TipJarRepository] which fetches
     * the public supporters JSON. Defaults to [com.stash.core.data.tipjar.TipJarState.EMPTY]
     * before the repo's first emission so the Home pill never crashes
     * on null.
     */
    val tipJar: com.stash.core.data.tipjar.TipJarState =
        com.stash.core.data.tipjar.TipJarState.EMPTY,

    /**
     * v0.9.17: state of the "tracks waiting for lossless" banner. Computed
     * by [com.stash.feature.home.banner.bannerStateFor] from four observable
     * inputs (deferred-row count, current captcha cookie, last-known-bad
     * cookie, kennyy circuit-breaker state). [WaitingForLosslessBannerState.Hidden]
     * is the steady state — no banner renders. Per-session dismissal is a
     * separate ViewModel-side flag that gates rendering at the screen level.
     */
    val waitingForLosslessBanner: WaitingForLosslessBannerState =
        WaitingForLosslessBannerState.Hidden,

    /**
     * v0.9.35: state of the "re-tagging library" banner. Hidden in
     * the steady state — only renders while [com.stash.data.download.backfill.MetadataBackfillWorker]
     * is actively processing rows, and for a 2-second "Done" pulse
     * after completion.
     */
    val metadataBackfillBanner: MetadataBackfillBannerState =
        MetadataBackfillBannerState.Hidden,

    /**
     * v0.9.36: state of the "Fetching lyrics" banner. Hidden in the
     * steady state — only renders while
     * [com.stash.data.lyrics.worker.LyricsBackfillWorker] is actively
     * processing rows, and for a 2-second "Done" pulse after
     * completion. Independent of [metadataBackfillBanner]; both can
     * be visible simultaneously on a v0.9.34→v0.9.36 upgrade.
     */
    val lyricsBackfillBanner: LyricsBackfillBannerState =
        LyricsBackfillBannerState.Hidden,
) {
    /** Total liked songs across both sources. */
    val totalLikedCount: Int get() = spotifyLikedCount + youtubeLikedCount

    /** True when either source has a liked-songs playlist (regardless of track count). */
    val hasAnyLikedSongs: Boolean
        get() = spotifyLikedPlaylists.isNotEmpty() || youtubeLikedPlaylists.isNotEmpty()

    /** True when both sources have liked-songs playlists (used to decide whether to show source chips). */
    val hasBothLikedSources: Boolean
        get() = spotifyLikedPlaylists.isNotEmpty() && youtubeLikedPlaylists.isNotEmpty()

    /** True when both sources have daily mixes (used to decide whether to group mix rows). */
    val hasBothMixSources: Boolean
        get() = spotifyMixes.isNotEmpty() && youtubeMixes.isNotEmpty()

    /**
     * Identifies the single contributing source when only one has liked songs.
     * Returns null when both sources contribute or neither does.
     */
    val singleLikedSource: com.stash.core.model.MusicSource?
        get() = when {
            spotifyLikedPlaylists.isNotEmpty() && youtubeLikedPlaylists.isEmpty() ->
                com.stash.core.model.MusicSource.SPOTIFY
            youtubeLikedPlaylists.isNotEmpty() && spotifyLikedPlaylists.isEmpty() ->
                com.stash.core.model.MusicSource.YOUTUBE
            else -> null
        }
}

/** Payload for the "connect Last.fm to send plays" banner. */
data class LastFmPromptState(val pendingCount: Int)

/**
 * Sentinel for the "Try lossless audio" Home banner. Singleton
 * (data object) because the banner copy is static — its mere
 * presence in the UI state signals "show the banner."
 */
data object LosslessPromptState

/**
 * Sort options for the Home Playlists grid. Deliberately duplicated from
 * the Library module's `SortOrder` to avoid a cross-module dependency for
 * three enum values. If a third surface ever needs the same options, lift
 * to a shared module rather than crossing the feature:library boundary.
 */
enum class PlaylistSortOrder { RECENT, ALPHABETICAL, MOST_PLAYED }
