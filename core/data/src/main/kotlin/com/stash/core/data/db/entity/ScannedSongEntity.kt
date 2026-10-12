package com.stash.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A song found by the All Songs scanner. Isolated from `tracks`; deleted with its folder. */
@Entity(
    tableName = "scanned_songs",
    foreignKeys = [
        ForeignKey(
            entity = ScanFolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folder_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["uri"], unique = true),
        Index(value = ["folder_id"]),
    ],
)
data class ScannedSongEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "folder_id") val folderId: Long,
    @ColumnInfo(name = "uri") val uri: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "artist") val artist: String,
    @ColumnInfo(name = "album") val album: String,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "file_format") val fileFormat: String,
    @ColumnInfo(name = "file_size_bytes") val fileSizeBytes: Long,
    @ColumnInfo(name = "date_added") val dateAdded: Long,
)
