package com.stash.core.data.lossless

import com.stash.core.model.Track
import com.stash.core.model.UpgradeResult

/**
 * v0.9.18: thin abstraction for the Now Playing "Find in FLAC" path.
 *
 * Lives in `:core:data` so `:feature:nowplaying` (which depends on
 * `:core:data` but not `:data:download`) can call the lossless
 * pipeline. The implementation lives in `:data:download` where it
 * has access to [com.stash.data.download.DownloadManager].
 *
 * Distinct from the sync-time download flow: this entry point
 * always passes `forced = true` so it ignores the user's global
 * lossless toggle and `youtubeFallbackEnabled` pref. The user
 * tapped Find in FLAC; they want a lossless attempt regardless of
 * their default settings.
 */
interface LosslessUpgrader {
    /** Whether automatic lossless downloads are enabled by the master preference. */
    suspend fun isLosslessEnabled(): Boolean

    suspend fun upgradeToLossless(track: Track): UpgradeResult
}
