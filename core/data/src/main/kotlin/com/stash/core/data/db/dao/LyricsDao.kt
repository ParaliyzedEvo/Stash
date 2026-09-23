package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stash.core.data.db.entity.LyricsEntity
import kotlinx.coroutines.flow.Flow

/**
 * v0.9.36: room access for the `lyrics` table. One lyrics row per
 * track id; absence of a row + `tracks.lyrics_fetched_at IS NULL`
 * means "never tried"; absence + `lyrics_fetched_at = 0L` means
 * "tried and got nothing". Row presence implies a successful fetch
 * with `lyrics_fetched_at` stamped non-zero.
 *
 * Pair every `upsert` with a [TrackDao.setLyricsFetchedAt] call to
 * stamp the success epoch-millis on the parent row; pair every
 * "tried and got nothing" outcome with `setLyricsFetchedAt(id, 0L)`
 * WITHOUT inserting a lyrics row. The backfill worker's
 * `lyrics_fetched_at IS NULL` predicate then filters both outcomes
 * out so it terminates.
 */
@Dao
interface LyricsDao {
    @Query("SELECT * FROM lyrics WHERE track_id = :trackId")
    suspend fun get(trackId: Long): LyricsEntity?

    @Query("SELECT * FROM lyrics WHERE track_id = :trackId")
    fun observe(trackId: Long): Flow<LyricsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LyricsEntity)

    @Query("DELETE FROM lyrics WHERE track_id = :trackId")
    suspend fun delete(trackId: Long)
}
