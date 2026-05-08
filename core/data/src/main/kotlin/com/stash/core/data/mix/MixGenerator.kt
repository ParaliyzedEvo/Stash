package com.stash.core.data.mix

import com.stash.core.data.db.dao.DiscoveryQueueDao
import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.dao.TrackTagDao
import com.stash.core.data.db.entity.DiscoveryQueueEntity
import com.stash.core.data.db.entity.StashMixRecipeEntity
import com.stash.core.data.db.entity.TrackEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlin.math.pow
import kotlin.random.Random

/**
 * Pure materializer for a [StashMixRecipeEntity] — given the recipe and
 * read access to the library, it produces an ordered list of `TrackEntity`
 * rows that the refresh worker writes into the recipe's playlist.
 *
 * ## Scoring model (v0.9.16)
 *
 * Every candidate track is assigned a score that linearly combines:
 *
 *  - **Affinity** (`buildAffinityMap`): in-Stash plays in the last 180
 *    days, log-normalized and exponentially decayed (30-day half-life),
 *    plus a Last.fm cross-source playcount supplement. Recipe's
 *    [StashMixRecipeEntity.affinityBias] shifts the weight:
 *      - positive bias (+) → heavy rotation favorites bubble up
 *      - negative bias (−) → Rediscovery surfaces tracks you *have* liked
 *        but haven't played recently
 *
 *  - **Tag cosine** (`buildTagCosineMap`): cosine similarity between the
 *    track's tag-weight vector and the user's L2-normalized tag-affinity
 *    vector (computed from decayed listening history). Replaces the
 *    older "sum of include-tag weights" heuristic — every recipe now
 *    gets a tag-affinity signal, not just those with explicit tags.
 *
 *  - **Completion** (`buildCompletionMap`): completed-listen ratio over
 *    the last 60 days. Tracks the user habitually finishes get a small
 *    boost; unknown tracks default to 0.5 (neutral).
 *
 *  - **Loved boost**: additive bonus for Last.fm-loved tracks.
 *
 *  - **Skip penalty** (`buildSkipPenaltyMap`): subtracted from the
 *    score when a track's 14-day skip-rate is high (linear ramp from
 *    0.4, full penalty at 0.6+).
 *
 * Freshness is a hard pre-filter (Step 5), not a scoring term — a small
 * jitter is added at sort time so the same score doesn't produce
 * identical ordering day-to-day.
 *
 * ## Discovery slots
 *
 * The recipe's `discovery_ratio` reserves that fraction of the target
 * length for tracks not yet in the library. This generator does NOT fetch
 * those — it only queues candidate entries into [DiscoveryQueueEntity]
 * for the [com.stash.core.data.sync.workers.StashDiscoveryWorker] to
 * resolve asynchronously. If no discovery tracks are ready yet, the
 * library-only portion stretches to fill the mix (users never see an
 * empty-looking mix because discovery didn't finish).
 */
@Singleton
class MixGenerator @Inject constructor(
    private val trackDao: TrackDao,
    private val trackTagDao: TrackTagDao,
    private val listeningEventDao: ListeningEventDao,
    private val discoveryQueueDao: DiscoveryQueueDao,
    private val blocklistGuard: com.stash.core.data.blocklist.BlocklistGuard,
    private val trackSkipEventDao: com.stash.core.data.db.dao.TrackSkipEventDao,
) {

    companion object {
        /** Recency window for affinity. Decay half-life is what controls "current"-ness. */
        private const val AFFINITY_WINDOW_MS = 180L * 24 * 60 * 60 * 1000

        /** Half-life of the affinity exponential decay, in milliseconds (30 days). */
        private const val AFFINITY_HALF_LIFE_MS = 30L * 24 * 60 * 60 * 1000

        /** Window for skip-rate computation. Shorter than affinity — skips age fast. */
        private const val SKIP_WINDOW_MS = 14L * 24 * 60 * 60 * 1000

        /** Window for completion-rate computation. */
        private const val COMPLETION_WINDOW_MS = 60L * 24 * 60 * 60 * 1000

        private const val BASE_AFFINITY_WEIGHT = 0.40f      // was 0.50; tag-cosine takes some
        private const val BASE_TAG_WEIGHT      = 0.35f      // was 0.30
        private const val BASE_COMPLETION_W    = 0.10f      // NEW
        private const val LOVED_BOOST          = 0.5f       // additive, not weight
        private const val SKIP_PENALTY_HEAVY   = 0.6f       // when skip-rate >= ramp
        private const val SKIP_PENALTY_RAMP    = 0.6f       // skip-rate above which heavy penalty kicks in

        /**
         * Scalar applied to the Last.fm-user-playcount term INSIDE
         * buildAffinityMap, before the outer BASE_AFFINITY_WEIGHT
         * multiplication. Intentionally lower than parity with local
         * plays — Last.fm playcount counts every scrobble across every
         * service the user ever connected, so a single track with 200
         * lifetime LFM plays shouldn't outweigh a 30-play in-Stash
         * track from this month. Net effect: LFM contributes ~12% of
         * the affinity weight (0.3 * 0.40), local contributes ~40%.
         * Rebalance only after on-device data shows the bias is wrong.
         */
        private const val LFM_PLAYCOUNT_W      = 0.3f
        private const val SORT_JITTER          = 0.10f      // was 0.05; ~12% of nominal score range
    }

    /**
     * Produces the finalized track list for [recipe]. The worker calling
     * this replaces the playlist_tracks rows for [recipe.playlistId] with
     * this ordering.
     */
    suspend fun generate(recipe: StashMixRecipeEntity): List<TrackEntity> {
        // Step 1: candidate pool — start from every downloaded,
        // non-blacklisted track in the library.
        val rawPool = trackDao.getAllDownloaded()

        // Step 2: era filter (cheap, done in-memory).
        var pool = filterByEra(rawPool, recipe)

        // Step 3: include-tag filter (one DAO hit if recipe has tags).
        val includeTags = recipe.includeTagsCsv.splitTrim()
        if (includeTags.isNotEmpty()) {
            val taggedIds = trackTagDao.getTrackIdsByTags(includeTags).toSet()
            pool = pool.filter { it.id in taggedIds }
        }

        // Step 4: exclude-tag hard filter.
        val excludeTags = recipe.excludeTagsCsv.splitTrim()
        if (excludeTags.isNotEmpty()) {
            val excludedIds = trackTagDao.getTrackIdsMatchingAny(excludeTags).toSet()
            pool = pool.filter { it.id !in excludedIds }
        }

        // Step 5: freshness filter — exclude tracks played inside the window.
        if (recipe.freshnessWindowDays > 0) {
            val since = System.currentTimeMillis() -
                recipe.freshnessWindowDays * 24L * 60 * 60 * 1000
            val recentlyPlayedIds = listeningEventDao
                .getTrackIdsPlayedSince(since)
                .toSet()
            pool = pool.filter { it.id !in recentlyPlayedIds }
        }

        if (pool.isEmpty()) return emptyList()

        // Step 6: score + sort.
        val userVector = buildUserTagAffinityVector()
        val affinityMap = buildAffinityMap(pool)
        val tagCosineMap = buildTagCosineMap(pool, userVector)
        val completionMap = buildCompletionMap(pool)
        val skipPenaltyMap = buildSkipPenaltyMap(pool)

        val wAff = BASE_AFFINITY_WEIGHT + recipe.affinityBias * 0.3f
        val wTag = BASE_TAG_WEIGHT
        val wCmp = BASE_COMPLETION_W

        val scored = pool.map { track ->
            val aff = affinityMap[track.id] ?: 0f
            val tag = tagCosineMap[track.id] ?: 0f
            val cmp = completionMap[track.id] ?: 0.5f         // unknown -> neutral
            val loved = if (track.lastfmUserLoved) LOVED_BOOST else 0f
            val skip = skipPenaltyMap[track.id] ?: 0f
            val score = aff * wAff +
                tag * wTag +
                cmp * wCmp +
                loved -
                skip +
                Random.nextFloat() * SORT_JITTER
            track to score
        }

        // Step 7: library slots = targetLength * (1 - discoveryRatio), but
        // if we ran out of library candidates we fill up to targetLength.
        // Avoid back-to-back same-artist within the first stretch.
        val librarySlots = (recipe.targetLength * (1f - recipe.discoveryRatio)).toInt()
            .coerceAtLeast(0)
        val ordered = scored.sortedByDescending { it.second }.map { it.first }

        val picked = pickWithArtistSpread(
            candidates = ordered,
            desired = librarySlots.coerceAtMost(pool.size),
        )

        // Step 8: shortfall backfill from the remaining library pool.
        // Gated on discoveryRatio < 1.0 — pure-discovery recipes like
        // "Stash Discover" (ratio = 1.0) must NEVER fall back to library
        // tracks, even at the cost of a sparser mix until Discovery
        // downloads more candidates. The user has explicitly asked for
        // zero familiarity; respecting that beats the old "better to
        // repeat a little than look broken" heuristic.
        if (recipe.discoveryRatio < 1.0f) {
            val shortfall = recipe.targetLength - picked.size
            if (shortfall > 0 && picked.size < pool.size) {
                val extra = ordered.filter { it !in picked }.take(shortfall)
                return picked + extra
            }
        }

        return picked
    }

    /**
     * Build per-track affinity scores in the range [0, 1]. Combines two
     * signals:
     *  - In-Stash plays in the last 180 days, log-normalized by the
     *    library's max count and exponentially decayed by recency
     *    (30-day half-life from [AFFINITY_HALF_LIFE_MS]).
     *  - Last.fm cross-source playcount (only present after Last.fm
     *    track-info enrichment) scaled by [LFM_PLAYCOUNT_W].
     *
     * Tracks the user has neither played in-Stash nor scrobbled to
     * Last.fm get a zero entry (omitted from the map).
     */
    private suspend fun buildAffinityMap(pool: List<TrackEntity>): Map<Long, Float> {
        val now = System.currentTimeMillis()
        val since = now - AFFINITY_WINDOW_MS
        val rows = listeningEventDao.getPlayCountsSinceWithLatest(since)
        val poolIds = pool.mapTo(HashSet(pool.size)) { it.id }

        if (rows.isEmpty() && pool.none { (it.lastfmUserPlaycount ?: 0) > 0 }) {
            return emptyMap()
        }

        val maxPlays = (rows.maxOfOrNull { it.plays } ?: 1).coerceAtLeast(1)
        val maxLfmPlays = pool.maxOfOrNull { it.lastfmUserPlaycount ?: 0 }?.coerceAtLeast(1) ?: 1

        val byId = rows.associateBy { it.trackId }
        val result = HashMap<Long, Float>(pool.size)
        for (track in pool) {
            if (track.id !in poolIds) continue
            val row = byId[track.id]
            // In-Stash plays with exponential decay
            val localTerm = if (row != null) {
                val logNorm = (ln(1f + row.plays.toFloat()) /
                    ln(1f + maxPlays.toFloat())).coerceIn(0f, 1f)
                val ageMs = (now - row.latestPlayedAt).coerceAtLeast(0)
                val decay = 0.5f.pow(ageMs.toFloat() / AFFINITY_HALF_LIFE_MS)
                logNorm * decay
            } else 0f
            // Last.fm cross-source playcount (only present after enrichment)
            val lfmTerm = track.lastfmUserPlaycount?.let { lpc ->
                (ln(1f + lpc.toFloat()) /
                    ln(1f + maxLfmPlays.toFloat())).coerceIn(0f, 1f) * LFM_PLAYCOUNT_W
            } ?: 0f
            val combined = (localTerm + lfmTerm).coerceIn(0f, 1f)
            if (combined > 0f) result[track.id] = combined
        }
        return result
    }

    /**
     * v0.9.16: Build the L2-normalized user tag-affinity vector by
     * weighting each track's tag vector by its (decayed) play weight
     * and summing. Used as one half of the cosine-similarity scoring
     * term against each candidate's own tag vector.
     *
     * Filters the `__untaggable__` sentinel rows that
     * [com.stash.core.data.sync.workers.TagEnrichmentWorker] writes
     * for tracks Last.fm couldn't tag.
     */
    private suspend fun buildUserTagAffinityVector(): Map<String, Float> {
        val now = System.currentTimeMillis()
        val since = now - AFFINITY_WINDOW_MS
        val rows = listeningEventDao.getPlayCountsSinceWithLatest(since)
        if (rows.isEmpty()) return emptyMap()

        val plays = rows.map { row ->
            val ageMs = (now - row.latestPlayedAt).coerceAtLeast(0)
            val decay = 0.5f.pow(ageMs.toFloat() / AFFINITY_HALF_LIFE_MS)
            val weight = ln(1f + row.plays.toFloat()) * decay
            val tags = trackTagDao.getByTrack(row.trackId)
                .filter { it.tag != "__untaggable__" }
                .associate { it.tag.lowercase() to it.weight }
            UserTagAffinity.PlayWithTags(weight = weight, tags = tags)
        }
        return UserTagAffinity.compute(plays)
    }

    /**
     * v0.9.16: Per-candidate cosine similarity against the user's tag
     * vector. Replaces the old "sum of include-tag weights" heuristic
     * — now every recipe benefits from tag affinity, not just those
     * with explicit include-tags.
     */
    private suspend fun buildTagCosineMap(
        pool: List<TrackEntity>,
        userVector: Map<String, Float>,
    ): Map<Long, Float> {
        if (userVector.isEmpty()) return emptyMap()
        val result = HashMap<Long, Float>(pool.size)
        for (track in pool) {
            val tags = trackTagDao.getByTrack(track.id)
                .filter { it.tag != "__untaggable__" }
                .associate { it.tag.lowercase() to it.weight }
            if (tags.isEmpty()) continue
            result[track.id] = UserTagAffinity.cosine(tags, userVector)
        }
        return result
    }

    /**
     * v0.9.16: Top-N user tags ordered by tag-affinity weight. Used by
     * [com.stash.core.data.sync.workers.StashMixRefreshWorker] to drive
     * the TAG_GRAPH seed strategy. Returns empty list when the user has
     * no listening history yet.
     */
    suspend fun computeUserTopTags(limit: Int = 10): List<String> {
        val vector = buildUserTagAffinityVector()
        return vector.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }
    }

    /**
     * v0.9.16: Per-track completion rate over the last 60 days. Tracks
     * with no listening history get omitted (caller defaults to 0.5
     * neutral so brand-new tracks aren't penalized).
     */
    private suspend fun buildCompletionMap(pool: List<TrackEntity>): Map<Long, Float> {
        val since = System.currentTimeMillis() - COMPLETION_WINDOW_MS
        val ids = pool.map { it.id }
        if (ids.isEmpty()) return emptyMap()
        val rows = listeningEventDao.getCompletionStatsSince(ids, since)
        return rows.associate {
            it.trackId to (it.completed.toFloat() / it.total.coerceAtLeast(1).toFloat())
        }
    }

    /**
     * v0.9.16: Per-track skip-rate penalty over the last 14 days.
     * Tracks with fewer than 3 total encounters are excluded (not
     * enough signal). Skip-rate >= [SKIP_PENALTY_RAMP] gets the
     * shadow-block-grade [SKIP_PENALTY_HEAVY] penalty; skip-rate
     * between 0.4 and the ramp gets a linear ramp so a track on
     * its way out of rotation degrades smoothly instead of cliff-
     * dropping.
     */
    private suspend fun buildSkipPenaltyMap(pool: List<TrackEntity>): Map<Long, Float> {
        val since = System.currentTimeMillis() - SKIP_WINDOW_MS
        val ids = pool.map { it.id }
        if (ids.isEmpty()) return emptyMap()
        val rows = trackSkipEventDao.getSkipStatsSince(ids, since)
        return rows.mapNotNull { row ->
            val total = row.skips + row.plays
            if (total < 3) return@mapNotNull null  // not enough data
            val rate = row.skips.toFloat() / total
            val penalty = when {
                rate >= SKIP_PENALTY_RAMP -> SKIP_PENALTY_HEAVY              // shadow-block-ish
                rate >= 0.4f -> (rate - 0.4f) / 0.2f * 0.4f                   // linear ramp
                else -> 0f
            }
            if (penalty > 0f) row.trackId to penalty else null
        }.toMap()
    }

    /**
     * Greedy artist-spread pick. Walks [candidates] in score order and
     * takes each track unless its artist was the previous pick — in which
     * case we look ahead to the next slot and insert the current track
     * later. Prevents a mix starting with 4 straight same-artist songs
     * even when they have the highest scores.
     */
    private fun pickWithArtistSpread(
        candidates: List<TrackEntity>,
        desired: Int,
    ): List<TrackEntity> {
        if (desired <= 0 || candidates.isEmpty()) return emptyList()
        val result = ArrayList<TrackEntity>(desired)
        val remaining = ArrayDeque(candidates)
        while (result.size < desired && remaining.isNotEmpty()) {
            val next = remaining.removeFirst()
            val prevArtist = result.lastOrNull()?.artist?.lowercase()
            if (prevArtist != null && next.artist.lowercase() == prevArtist) {
                // Find a different-artist track in the lookahead.
                val swapIdx = remaining.indexOfFirst { it.artist.lowercase() != prevArtist }
                if (swapIdx >= 0) {
                    val swap = remaining.removeAt(swapIdx)
                    result += swap
                    remaining.addFirst(next) // put original back at the head
                    continue
                }
            }
            result += next
        }
        return result
    }

    private fun filterByEra(
        pool: List<TrackEntity>,
        recipe: StashMixRecipeEntity,
    ): List<TrackEntity> {
        if (recipe.eraStartYear == null && recipe.eraEndYear == null) return pool
        // TrackEntity has no direct `year`. Best available proxy is the
        // `date_added` Instant for now; a real year column would require
        // another migration and tag-scanner work. For v0.4.0 we treat
        // era_{start,end} as date_added year, which is approximate but
        // matches "Throwback" (library tracks older than N years) well
        // enough. Tag-based recipes (90s Alternative) should rely on tags
        // rather than era for accurate results.
        val startYear = recipe.eraStartYear
        val endYear = recipe.eraEndYear
        return pool.filter { track ->
            val year = track.dateAdded.atZone(java.time.ZoneId.systemDefault()).year
            (startYear == null || year >= startYear) &&
                (endYear == null || year <= endYear)
        }
    }

    /**
     * After a refresh, queue discovery candidates. Pulls the user's top
     * artists from [ListeningEventDao.getTopArtistsSince], fetches similar
     * artists per seed, takes the top tracks from each, and files
     * `discovery_queue` rows that the [StashDiscoveryWorker] will resolve.
     *
     * The worker itself performs no Last.fm calls — it's pure Kotlin. That
     * keeps the expensive network work off the refresh critical path.
     * Called from [com.stash.core.data.sync.workers.StashMixRefreshWorker]
     * after each successful recipe refresh with the list of seed artists.
     */
    suspend fun queueDiscoveryCandidates(
        recipe: StashMixRecipeEntity,
        similarArtistSuggestions: List<DiscoveryCandidate>,
    ) {
        if (similarArtistSuggestions.isEmpty()) return
        val toInsert = similarArtistSuggestions.mapNotNull { cand ->
            // v0.9.15: Skip blocklisted identities so the same blocked
            // artist+title doesn't get re-queued every refresh and end up
            // re-discovered via StashDiscoveryWorker.
            if (blocklistGuard.isBlocked(
                    artist = cand.artist, title = cand.title,
                    spotifyUri = null, youtubeId = null,
                )) {
                return@mapNotNull null
            }
            val exists = discoveryQueueDao.existsForRecipe(recipe.id, cand.artist, cand.title)
            if (exists) null else DiscoveryQueueEntity(
                recipeId = recipe.id,
                artist = cand.artist,
                title = cand.title,
                seedArtist = cand.seedArtist,
            )
        }
        if (toInsert.isNotEmpty()) discoveryQueueDao.insertAllIfNew(toInsert)
    }

    /**
     * Candidate shape for [queueDiscoveryCandidates]. Produced by the
     * refresh worker from Last.fm similar-artist + top-track queries.
     */
    data class DiscoveryCandidate(
        val artist: String,
        val title: String,
        val seedArtist: String,
    )

    private fun String.splitTrim(): List<String> =
        this.split(',').map { it.trim().lowercase() }.filter { it.isNotBlank() }
}
