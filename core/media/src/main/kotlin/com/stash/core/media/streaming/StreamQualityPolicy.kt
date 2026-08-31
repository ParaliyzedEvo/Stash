package com.stash.core.media.streaming

import com.stash.data.download.lossless.LosslessQualityTier
import com.stash.data.download.prefs.StreamingQualityPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single decision point for "what lossless tier should a streaming
 * resolve request right now?". Streaming resolvers call this instead of
 * reading the download tier; downloads never call it.
 *
 * Phase 1 returns just a tier. Phase 2 will widen the return to a
 * StreamDecision (Tier | ForceYouTube) for the cellular budget without
 * changing this class's callers' shape beyond the new branch.
 */
@Singleton
class StreamQualityPolicy @Inject constructor(
    private val connectivity: ConnectivityMonitor,
    private val prefs: StreamingQualityPreferences,
) {
    /**
     * Save Data returns the lossy floor, not the lowest lossless tier.
     *
     * This used to return [LosslessQualityTier.CD], which is still FLAC — roughly
     * 28 MB for a four-minute track — so the setting effectively did nothing: a
     * user on a metered plan enabling "Save Data" still pulled full lossless.
     *
     * [LosslessQualityTier.DATA_SAVER] asks Qobuz for `format_id = 5` (MP3 320,
     * ~10 MB), verified live against the API. Tracks with no lossless match fall to
     * the YouTube path, which is already lossy (~5 MB), so the floor holds across
     * both routes.
     */
    suspend fun streamingTier(): LosslessQualityTier {
        if (prefs.saveDataNow()) return LosslessQualityTier.DATA_SAVER
        return if (connectivity.isCellular()) prefs.cellularTierNow() else prefs.wifiTierNow()
    }
}
