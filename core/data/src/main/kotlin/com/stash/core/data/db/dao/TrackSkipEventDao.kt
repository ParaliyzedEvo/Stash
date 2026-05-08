package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.stash.core.data.db.entity.TrackSkipEventEntity

/** Per-track skip-rate projection for the recommender's negative weighting. */
data class TrackSkipStats(
    val trackId: Long,
    val skips: Int,
    val plays: Int,
)

@Dao
interface TrackSkipEventDao {

    @Insert
    suspend fun insert(event: TrackSkipEventEntity): Long

    @Query("SELECT COUNT(*) FROM track_skip_events WHERE track_id = :trackId AND skipped_at >= :sinceMs")
    suspend fun countSkipsSince(trackId: Long, sinceMs: Long): Int

    /**
     * Returns skip + play counts per track for the rolling window.
     * Used by [com.stash.core.data.mix.MixGenerator] to compute a
     * skip-rate penalty: tracks with high skip ratio over recent
     * encounters get demoted in scoring.
     */
    @Query(
        """
        SELECT t.id AS trackId,
               (SELECT COUNT(*) FROM track_skip_events s
                  WHERE s.track_id = t.id AND s.skipped_at >= :sinceMs) AS skips,
               (SELECT COUNT(*) FROM listening_events le
                  WHERE le.track_id = t.id AND le.started_at >= :sinceMs) AS plays
        FROM tracks t
        WHERE t.id IN (:trackIds)
        """
    )
    suspend fun getSkipStatsSince(trackIds: List<Long>, sinceMs: Long): List<TrackSkipStats>
}
