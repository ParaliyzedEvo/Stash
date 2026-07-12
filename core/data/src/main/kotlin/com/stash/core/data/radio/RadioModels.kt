package com.stash.core.data.radio

/**
 * One potential radio track before YouTube resolution.
 *
 * [videoId] is known for artist-radio picks (they come from a YT `popular`
 * list) and null for song-radio similar-track picks (resolved via
 * `searchCanonicalVideoId` at emit time). [weight] is the source artist's
 * relative frequency (seed share, or a neighbor's Last.fm match score); the
 * interleaver uses it to decide how often this candidate's artist appears.
 */
data class RadioCandidate(
    val artist: String,
    val title: String,
    val videoId: String?,
    val weight: Float,
    val thumbnailUrl: String? = null,
)

/** Stable per-station identity for the no-repeat set. */
internal fun RadioCandidate.identity(): String =
    videoId?.takeIf { it.isNotBlank() }
        ?: (artist.trim().lowercase() + "|" + title.trim().lowercase())

/**
 * Live, in-memory state of one station. Mutable cursor + no-repeat set; not
 * persisted (a process kill ends the station — queued tracks still play out).
 * Created and mutated only by [RadioStationGenerator]; consumed by the player.
 */
class RadioSession internal constructor(
    internal val seed: RadioSeed,
    internal val ordered: MutableList<RadioCandidate>,
    internal val played: MutableSet<String>,   // identity() + resolved-videoId keys already emitted
) {
    internal var cursor: Int = 0

    /** True once the pool is exhausted and no further widening is possible. */
    internal var exhausted: Boolean = false

    /**
     * Neighbors not yet used, consumed NEIGHBOR_POOL at a time by widening.
     * This is what keeps the station extending: artist radio fills it at start
     * (with the neighbors AFTER the first pool); song radio lazy-loads it on the
     * first widen. Draining it — not re-fetching the same top-N — is the fix for
     * "widen returns the same already-played neighbors."
     */
    internal val remainingNeighbors: ArrayDeque<com.stash.core.data.lastfm.LastFmSimilarArtist> = ArrayDeque()
    internal var neighborsLoaded: Boolean = false
}
