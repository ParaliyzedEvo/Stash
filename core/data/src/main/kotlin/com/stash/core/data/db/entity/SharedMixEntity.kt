package com.stash.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One shared mix link attached to a local playlist (spec §5). [role] is OWNER for a mix this
 * phone shares, FOLLOWER for one it follows. Roles and statuses are plain strings so no type
 * converter is needed.
 */
@Entity(
    tableName = "shared_mixes",
    foreignKeys = [ForeignKey(
        entity = PlaylistEntity::class,
        parentColumns = ["id"],
        childColumns = ["playlist_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["share_id"], unique = true)],
)
data class SharedMixEntity(
    @PrimaryKey @ColumnInfo(name = "playlist_id") val playlistId: Long,
    @ColumnInfo(name = "share_id") val shareId: String,
    @ColumnInfo(name = "role") val role: String,
    /** OWNER only: the secret that authorises updates. Kept in backups (spec §5). */
    @ColumnInfo(name = "edit_key") val editKey: String? = null,
    /** OWNER: the name the mix is shared under. FOLLOWER: the last name received. */
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "version") val version: Int = 0,
    @ColumnInfo(name = "content_hash") val contentHash: String = "",
    @ColumnInfo(name = "auto_update") val autoUpdate: Boolean = true,
    @ColumnInfo(name = "status") val status: String = STATUS_ACTIVE,
    @ColumnInfo(name = "shared_by") val sharedBy: String? = null,
    @ColumnInfo(name = "missing_count") val missingCount: Int = 0,
    /** FOLLOWER: the owner stopped sharing and the one-time notice hasn't been shown yet. */
    @ColumnInfo(name = "notice_pending") val noticePending: Boolean = false,
    @ColumnInfo(name = "last_checked_at") val lastCheckedAt: Long? = null,
) {
    companion object {
        const val ROLE_OWNER = "OWNER"
        const val ROLE_FOLLOWER = "FOLLOWER"
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_REMOVED = "REMOVED"
    }
}
