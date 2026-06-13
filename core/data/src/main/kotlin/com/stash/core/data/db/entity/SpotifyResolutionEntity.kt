package com.stash.core.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "spotify_resolution")
data class SpotifyResolutionEntity(
    @PrimaryKey val trackId: Long,
    val status: String,          // "MATCHED" | "NO_MATCH" | "TRANSIENT"
    val spotifyUri: String?,     // "spotify:track:<id>" when MATCHED
    val matchedIsrc: String?,
    val titleSim: Float?,
    val durDeltaSec: Int?,
    val resolvedAtMs: Long,
    val expiresAtMs: Long,
    val attempts: Int = 1,
)
