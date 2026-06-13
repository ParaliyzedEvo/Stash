package com.stash.core.common.constants

object StashConstants {
    const val MAX_CONCURRENT_DOWNLOADS = 3
    const val SPOTIFY_API_DELAY_MS = 100L
    const val TOKEN_REFRESH_BUFFER_MINUTES = 5
    const val POSITION_UPDATE_INTERVAL_MS = 200L
    const val STAGGER_ANIMATION_DELAY_MS = 50L
    const val MAX_STAGGER_ITEMS = 10
    const val ALBUM_ART_TARGET_SIZE = 500
    const val ALBUM_ART_PALETTE_SIZE = 128
    const val LOW_DISK_THRESHOLD_BYTES = 100L * 1024 * 1024

    /**
     * Minimum size, in bytes, for a local file to count as a real, playable
     * download. A failed download can leave tiny garbage behind (observed:
     * ~274-byte yt-dlp error bodies written to a `.webm` and marked complete).
     * Anything below this floor is treated as NOT a valid download:
     *  - the download pipeline refuses to mark it `isDownloaded` (and deletes it),
     *  - the playback layer streams the track instead of playing junk,
     *  - the startup repair sweep un-marks + deletes existing junk rows.
     * 16 KiB is orders of magnitude below the smallest real music file, so it
     * never rejects a legitimate download.
     */
    const val MIN_PLAYABLE_LOCAL_BYTES = 16L * 1024L

    /**
     * Online-Streaming Engine master kill-switch — feature-module-visible
     * mirror of `com.stash.app.BuildConfig.STREAMING_ENGINE_ENABLED`.
     *
     * The canonical flag is the app module's BuildConfig field (set in
     * `app/build.gradle.kts`); this constant exists so feature modules
     * that don't depend on `:app` can still gate UI affordances on it.
     * Keep both in sync — Task 23 of the online-streaming-engine plan
     * flips both at once when the engine is validated end-to-end.
     *
     * While `false`, the Home `StreamingModeToggle` stays hidden and the
     * streaming-related Hilt wiring in `StashApplication` stays inert.
     */
    const val STREAMING_ENGINE_ENABLED = true
}
