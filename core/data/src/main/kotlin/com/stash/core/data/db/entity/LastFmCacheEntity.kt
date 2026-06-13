package com.stash.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * On-device cache for Last.fm read responses that are identical across every
 * user (tag→tracks, artist→similar, etc.). Caching these collapses the bulk
 * of the app's Last.fm traffic — the same generic lookups were otherwise
 * re-fetched by every install on every refresh, which is what throttled the
 * shared API key.
 *
 * - [cacheKey] is [com.stash.core.data.lastfm.lastFmCacheKey] of the request
 *   params, deliberately excluding `api_key` so the entry survives key
 *   rotation/pooling.
 * - [json] is the raw successful response body (errors are never cached).
 * - [fetchedAt] is epoch-millis; staleness is checked against the read TTL.
 */
@Entity(tableName = "lastfm_response_cache")
data class LastFmCacheEntity(
    @PrimaryKey @ColumnInfo(name = "cache_key") val cacheKey: String,
    @ColumnInfo(name = "json") val json: String,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
)
