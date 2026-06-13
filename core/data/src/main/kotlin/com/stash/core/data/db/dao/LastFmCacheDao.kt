package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stash.core.data.db.entity.LastFmCacheEntity

/**
 * Read/write access to the [LastFmCacheEntity] table. TTL/staleness is the
 * caller's concern — this DAO just stores and returns rows by key.
 */
@Dao
interface LastFmCacheDao {

    /** Insert or overwrite the cached body for a request key. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LastFmCacheEntity)

    /** Cached row for [key], or null if never fetched. */
    @Query("SELECT * FROM lastfm_response_cache WHERE cache_key = :key")
    suspend fun get(key: String): LastFmCacheEntity?

    /** Drop entries older than [olderThanMs] (epoch-millis). */
    @Query("DELETE FROM lastfm_response_cache WHERE fetched_at < :olderThanMs")
    suspend fun prune(olderThanMs: Long)

    /** Wipe the entire cache (e.g. on sign-out / manual reset). */
    @Query("DELETE FROM lastfm_response_cache")
    suspend fun clear()
}
