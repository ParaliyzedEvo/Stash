package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stash.core.data.db.entity.ScanFolderEntity
import com.stash.core.data.db.entity.ScannedSongEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScannedSongDao {
    @Query("SELECT * FROM scan_folders ORDER BY added_at")
    fun observeFolders(): Flow<List<ScanFolderEntity>>

    @Query("SELECT * FROM scan_folders ORDER BY added_at")
    suspend fun getFolders(): List<ScanFolderEntity>

    /** Returns -1 when the tree is already registered. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFolder(folder: ScanFolderEntity): Long

    @Query("DELETE FROM scan_folders WHERE id = :id")
    suspend fun deleteFolder(id: Long)

    @Query("SELECT * FROM scanned_songs ORDER BY title COLLATE NOCASE")
    fun observeSongs(): Flow<List<ScannedSongEntity>>

    @Query("SELECT uri FROM scanned_songs WHERE folder_id = :folderId")
    suspend fun getUris(folderId: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSongs(songs: List<ScannedSongEntity>)

    @Query("DELETE FROM scanned_songs WHERE folder_id = :folderId AND uri IN (:uris)")
    suspend fun deleteByUris(folderId: Long, uris: List<String>)
}
