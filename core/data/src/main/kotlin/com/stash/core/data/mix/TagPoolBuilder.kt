package com.stash.core.data.mix

import com.stash.core.data.lastfm.LastFmApiClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay

/**
 * Builds a ranked discovery-candidate pool from a set of Last.fm tags.
 * Ranking: tag-overlap (most of the chosen tags) -> user-artist affinity ->
 * Last.fm playcount. The deep-cut window drops the top [sampleDepth] of each
 * tag so "Deep Cuts" surfaces lesser-known tracks instead of the same hits.
 */
@Singleton
class TagPoolBuilder @Inject constructor(
    private val apiClient: LastFmApiClient,
) {
    private data class Agg(
        val artist: String, val title: String,
        var overlap: Int, var bestPlaycount: Int, var bestTag: String,
    )

    suspend fun build(
        tags: List<String>,
        sampleDepth: Int,
        userTopArtists: Set<String>,
    ): List<MixGenerator.DiscoveryCandidate> {
        if (tags.isEmpty()) return emptyList()
        val byKey = LinkedHashMap<String, Agg>()
        for (tag in tags.take(MAX_TAGS)) {
            val tracks = apiClient.getTagTopTracks(tag, FETCH_LIMIT).getOrNull().orEmpty()
                .drop(sampleDepth.coerceAtLeast(0))
            for (t in tracks) {
                val key = "${t.artist.trim().lowercase()}|${t.title.trim().lowercase()}"
                val agg = byKey[key]
                if (agg == null) {
                    byKey[key] = Agg(t.artist, t.title, 1, t.playcount, tag)
                } else {
                    agg.overlap += 1
                    if (t.playcount > agg.bestPlaycount) agg.bestPlaycount = t.playcount
                }
            }
            delay(REQUEST_INTERVAL_MS)
        }
        return byKey.values
            .sortedWith(
                compareByDescending<Agg> { it.overlap }
                    .thenByDescending { it.artist.trim().lowercase() in userTopArtists }
                    .thenByDescending { it.bestPlaycount },
            )
            .map { MixGenerator.DiscoveryCandidate(it.artist, it.title, "tag:${it.bestTag}") }
    }

    private companion object {
        const val FETCH_LIMIT = 50
        const val MAX_TAGS = 10
        const val REQUEST_INTERVAL_MS = 220L
    }
}
