package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stash.core.data.db.entity.DiscoveryQueueEntity
import kotlinx.coroutines.flow.Flow

/** Queue DAO for Stash Mix discovery candidates. */
@Dao
interface DiscoveryQueueDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNew(entry: DiscoveryQueueEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIfNew(entries: List<DiscoveryQueueEntity>)

    /** Pending entries for the discovery worker to drain. */
    @Query(
        """
        SELECT * FROM discovery_queue
        WHERE status = 'PENDING'
        ORDER BY queued_at ASC
        LIMIT :limit
        """
    )
    suspend fun getPending(limit: Int): List<DiscoveryQueueEntity>

    /**
     * Pending entries that aren't in the supplied at-cap recipe set.
     * Without this fairness filter, a single recipe's deferred-at-cap
     * backlog (rows that keep getting skipped because their recipe is
     * over its weekly download budget) sits at the head of the queue and
     * starves out fresher candidates from under-cap recipes. See
     * conversation 2026-05-12: First Listen had 100+ deferred PENDING
     * blocking Deep Cuts' freshly-queued candidates from ever being
     * processed.
     *
     * Pass an empty list to behave like [getPending] — Room rejects
     * empty `IN ()` SQL so the worker special-cases the empty case
     * before calling.
     */
    @Query(
        """
        SELECT * FROM discovery_queue
        WHERE status = 'PENDING'
          AND recipe_id NOT IN (:cappedRecipeIds)
        ORDER BY queued_at ASC
        LIMIT :limit
        """
    )
    suspend fun getPendingExcludingRecipes(
        cappedRecipeIds: List<Long>,
        limit: Int,
    ): List<DiscoveryQueueEntity>

    /**
     * Recipe ids currently over the weekly download cap. Mirrors
     * [countRecentCompletedForRecipe]'s join + filter exactly so the
     * pre-fetch and per-row checks agree.
     */
    @Query(
        """
        SELECT dq.recipe_id FROM discovery_queue dq
        INNER JOIN tracks t ON t.id = dq.track_id
        WHERE dq.status = 'DONE'
          AND dq.completed_at >= :sinceMillis
          AND t.is_downloaded = 1
        GROUP BY dq.recipe_id
        HAVING COUNT(*) >= :cap
        """
    )
    suspend fun findRecipesAtWeeklyCap(sinceMillis: Long, cap: Int): List<Long>

    /**
     * Count of discovery entries completed within [sinceMillis] for a
     * specific recipe — enforces the per-recipe weekly cap so discovery
     * doesn't spiral.
     *
     * v0.9.21: Aligned with [getDoneTrackIdsForRecipe] — only count DONE
     * rows whose track actually landed on disk (`is_downloaded = 1`). The
     * cap exists to bound real disk usage; counting unmaterialized stubs
     * trapped recipes in a "100 intended but 0 downloaded" loop where
     * fresh PENDING candidates could never drain. See conversation
     * 2026-05-12: Deep Cuts had ~100 DONE rows but 0 in the mix.
     */
    @Query(
        """
        SELECT COUNT(*) FROM discovery_queue dq
        INNER JOIN tracks t ON t.id = dq.track_id
        WHERE dq.recipe_id = :recipeId
          AND dq.status = 'DONE'
          AND dq.completed_at >= :sinceMillis
          AND t.is_downloaded = 1
        """
    )
    suspend fun countRecentCompletedForRecipe(recipeId: Long, sinceMillis: Long): Int

    @Query(
        """
        UPDATE discovery_queue
        SET status = :status, track_id = :trackId,
            completed_at = :completedAt, error_message = :errorMessage
        WHERE id = :id
        """
    )
    suspend fun updateStatus(
        id: Long,
        status: String,
        trackId: Long? = null,
        completedAt: Long? = null,
        errorMessage: String? = null,
    )

    /** Diagnostics projection for the Settings/debug view. */
    @Query(
        """
        SELECT status, COUNT(*) AS n FROM discovery_queue GROUP BY status
        """
    )
    fun observeStatusCounts(): Flow<List<StatusCount>>

    data class StatusCount(val status: String, val n: Int)

    /**
     * Track ids for every DONE discovery row whose track still exists
     * in the tracks table. Used by [StashMixRefreshWorker.materializeMix]
     * to re-link surviving discovery tracks after the refresh clears the
     * playlist — without this step, a Discovery download that completed
     * between refreshes gets wiped from the mix and then garbage-
     * collected by the orphan sweeper.
     *
     * v0.9.19 follow-up: capped at [limit] rows ordered by
     * `completed_at DESC`, so newest-DONE survivors win when
     * `materializeMix` trims to the recipe's discovery slot count
     * (`targetLength * discoveryRatio`). Without the cap, every DONE row
     * accumulated for the recipe got re-linked, growing the playlist
     * unboundedly across release transitions (Daily Discover went
     * 50 -> 100 between v0.9.18 and v0.9.19 because ~50 survivors had
     * accumulated). SQLite treats NULL as "smaller than any value" under
     * `DESC`, so any pathological NULL `completed_at` rows fall to the
     * end naturally, but they're already excluded by the status filter
     * since worker DONE transitions stamp the timestamp.
     *
     * v0.9.20 follow-up: INNER JOIN tracks filters out orphan rows whose
     * track_id no longer points at a live row in `tracks` (deleted by the
     * orphan sweeper, user delete, or stale state from migrations). Without
     * this JOIN, materializeMix's insertCrossRef into playlist_tracks throws
     * SQLITE_CONSTRAINT_FOREIGNKEY because the FK on tracks(id) is enforced.
     *
     * v0.9.20 follow-up: AND t.is_downloaded = 1 ensures the mix never
     * surfaces a stub TrackEntity (a discovery row that was marked DONE
     * by StashDiscoveryWorker but whose file hasn't landed on disk yet).
     * Without this filter, a transient window between StashDiscoveryWorker
     * completion and DiscoveryDownloadWorker completion would put unplayable
     * tracks in the playlist. DiscoveryDownloadWorker fixes the underlying
     * issue (downloads run promptly); this filter is defense-in-depth.
     */
    @Query(
        """
        SELECT dq.track_id FROM discovery_queue dq
        INNER JOIN tracks t ON t.id = dq.track_id
        WHERE dq.recipe_id = :recipeId
          AND dq.status = 'DONE'
          AND dq.track_id IS NOT NULL
          AND t.is_downloaded = 1
        ORDER BY dq.completed_at DESC
        LIMIT :limit
        """
    )
    suspend fun getDoneTrackIdsForRecipe(recipeId: Long, limit: Int): List<Long>

    /**
     * Track ids referenced by any non-terminal discovery row (PENDING /
     * DONE). Fed to the orphan sweeper so in-flight + just-completed
     * discovery tracks don't get deleted between refresh and re-link.
     */
    @Query(
        """
        SELECT DISTINCT track_id FROM discovery_queue
        WHERE track_id IS NOT NULL
          AND status IN ('PENDING', 'DONE')
        """
    )
    suspend fun getActiveTrackIds(): List<Long>

    /**
     * Does this recipe already have an entry for (artist, title) queued
     * within the TTL window? v0.9.16 introduces a 30-day TTL on the
     * dedup key — older candidates that failed download or were
     * skipped/blocked previously can re-enter the funnel after a month
     * so the discovery surface stays fresh instead of permanently
     * pinning a candidate the moment it's first queued.
     */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM discovery_queue
            WHERE recipe_id = :recipeId
              AND LOWER(artist) = LOWER(:artist)
              AND LOWER(title) = LOWER(:title)
              AND queued_at >= :sinceMs
        )
        """
    )
    suspend fun existsForRecipeSince(
        recipeId: Long,
        artist: String,
        title: String,
        sinceMs: Long,
    ): Boolean

    /**
     * Age-out pass: delete PENDING rows that have been queued longer than
     * [cutoffMillis] ago (typically now − 30 days). Stale pending
     * candidates clog the queue's drain order without contributing value
     * — the user's taste has usually moved on, and newer refreshes will
     * surface any still-relevant similar-artist suggestions anyway.
     * Completed (DONE) / failed rows are left alone so the per-recipe
     * weekly-cap query and the re-link step still see accurate history.
     *
     * Returns the number of rows deleted — non-zero values are logged by
     * [StashDiscoveryWorker] as a diagnostic signal.
     */
    @Query(
        """
        DELETE FROM discovery_queue
        WHERE status = 'PENDING'
          AND queued_at < :cutoffMillis
        """
    )
    suspend fun deleteStalePending(cutoffMillis: Long): Int

    /**
     * One-time PR 7 cleanup: delete discovery_queue DONE rows whose linked
     * track has `source != 'YOUTUBE'`. Such rows were created pre-PR-6 by
     * StashDiscoveryWorker.handle()'s canonical-dedup branch — Last.fm
     * candidates that matched an existing library track got linked to that
     * track instead of producing a real discovery stub. They surface in
     * mixes as "discovery survivors" despite being library content.
     *
     * Heuristic: real discovery stubs have `source = MusicSource.YOUTUBE`
     * (set by StashDiscoveryWorker.handle's stub-creation branch). Library
     * tracks have other sources (`SPOTIFY`, `LOCAL`, `BOTH`).
     *
     * PR 6's seed-stage pre-filter prevents new library-hit rows from being
     * created; this query cleans up the accumulated backlog.
     *
     * @return number of rows deleted.
     */
    @Query(
        """
        DELETE FROM discovery_queue
        WHERE status = 'DONE'
          AND track_id IN (SELECT id FROM tracks WHERE source != 'YOUTUBE')
        """
    )
    suspend fun deleteLibraryHitDoneRows(): Int
}
