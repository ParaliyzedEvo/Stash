package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stash.core.data.db.entity.SharedMixEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SharedMixDao {
    /** Creates a row; throws if the playlist or the share id already has one (an @Upsert would silently no-op on a share_id clash). */
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(row: SharedMixEntity)

    // Targeted updates, never a whole-row rewrite: a row read before a network call must not
    // resurrect a stopped share or revert a toggle. An UPDATE that matches nothing is a no-op.

    @Query("UPDATE shared_mixes SET version = :version, content_hash = :hash WHERE playlist_id = :playlistId")
    suspend fun markPublished(playlistId: Long, version: Int, hash: String)

    @Query("UPDATE shared_mixes SET status = '${SharedMixEntity.STATUS_REMOVED}', notice_pending = :noticePending WHERE playlist_id = :playlistId")
    suspend fun markRemoved(playlistId: Long, noticePending: Boolean)

    @Query("UPDATE shared_mixes SET auto_update = :on WHERE playlist_id = :playlistId")
    suspend fun setAutoUpdate(playlistId: Long, on: Boolean)

    @Query("UPDATE shared_mixes SET missing_count = :missingCount, last_checked_at = :lastCheckedAt WHERE playlist_id = :playlistId")
    suspend fun markChecked(playlistId: Long, missingCount: Int, lastCheckedAt: Long)

    @Query(
        "UPDATE shared_mixes SET version = :version, name = :name, shared_by = :sharedBy, missing_count = 0, " +
            "last_checked_at = :lastCheckedAt WHERE playlist_id = :playlistId",
    )
    suspend fun markApplied(playlistId: Long, version: Int, name: String, sharedBy: String?, lastCheckedAt: Long)

    @Query("UPDATE shared_mixes SET notice_pending = 0 WHERE playlist_id = :playlistId")
    suspend fun clearNotice(playlistId: Long)

    @Query("SELECT * FROM shared_mixes WHERE playlist_id = :playlistId")
    suspend fun forPlaylist(playlistId: Long): SharedMixEntity?

    @Query("SELECT * FROM shared_mixes WHERE playlist_id = :playlistId")
    fun observeForPlaylist(playlistId: Long): Flow<SharedMixEntity?>

    @Query("SELECT * FROM shared_mixes WHERE share_id = :shareId")
    suspend fun byShareId(shareId: String): SharedMixEntity?

    @Query("SELECT * FROM shared_mixes WHERE role = '${SharedMixEntity.ROLE_OWNER}' AND status = '${SharedMixEntity.STATUS_ACTIVE}' AND auto_update = 1")
    suspend fun activeOwnedWithUpdates(): List<SharedMixEntity>

    @Query("SELECT * FROM shared_mixes WHERE role = '${SharedMixEntity.ROLE_FOLLOWER}' AND status = '${SharedMixEntity.STATUS_ACTIVE}'")
    suspend fun activeFollowed(): List<SharedMixEntity>

    @Query("SELECT * FROM shared_mixes") suspend fun getAll(): List<SharedMixEntity>

    @Query("DELETE FROM shared_mixes WHERE playlist_id = :playlistId")
    suspend fun delete(playlistId: Long)
}
