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
    suspend fun streamingTier(): LosslessQualityTier {
        if (prefs.saveDataNow()) return LosslessQualityTier.CD
        return if (connectivity.isCellular()) prefs.cellularTierNow() else prefs.wifiTierNow()
    }
}
