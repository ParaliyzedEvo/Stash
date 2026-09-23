package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.stash.core.data.db.entity.SharedMixEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SharedMixDao {
    @Upsert suspend fun upsert(row: SharedMixEntity)

    @Query("SELECT * FROM shared_mixes WHERE playlist_id = :playlistId")
    suspend fun forPlaylist(playlistId: Long): SharedMixEntity?

    @Query("SELECT * FROM shared_mixes WHERE playlist_id = :playlistId")
    fun observeForPlaylist(playlistId: Long): Flow<SharedMixEntity?>

    @Query("SELECT * FROM shared_mixes WHERE share_id = :shareId")
    suspend fun byShareId(shareId: String): SharedMixEntity?

    @Query("SELECT * FROM shared_mixes WHERE role = 'OWNER' AND status = 'ACTIVE' AND auto_update = 1")
    suspend fun activeOwnedWithUpdates(): List<SharedMixEntity>

    @Query("SELECT * FROM shared_mixes WHERE role = 'FOLLOWER' AND status = 'ACTIVE'")
    suspend fun activeFollowed(): List<SharedMixEntity>

    @Query("DELETE FROM shared_mixes WHERE playlist_id = :playlistId")
    suspend fun delete(playlistId: Long)
}
