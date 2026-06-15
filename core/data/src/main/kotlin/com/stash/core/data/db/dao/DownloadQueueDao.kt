package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.ColumnInfo
import androidx.room.Transaction
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.model.DownloadFailureType
import com.stash.core.model.DownloadStatus
import kotlinx.coroutines.flow.Flow

/** Room projection for status count diagnostic query. */
data class StatusCount(
    val status: String,
    @ColumnInfo(name = "COUNT(*)") val count: Int,
)

/** Room projection for source count diagnostic query. */
data class SourceCount(
    val source: String,
    val cnt: Int,
)

/**
 * Data-access object for [DownloadQueueEntity].
 *
 * Manages the download work queue with insert, status updates,
 * retry tracking, and cleanup operations.
 */
@Dao
interface DownloadQueueDao {

    // ── Inserts ─────────────────────────────────────────────────────────

    /** Insert a single download queue entry. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: DownloadQueueEntity): Long

    /** Insert multiple download queue entries. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<DownloadQueueEntity>): List<Long>

    // ── Queries ─────────────────────────────────────────────────────────

    /** Reactive stream of pending downloads, ordered by creation time. */
    @Query("SELECT * FROM download_queue WHERE status = 'PENDING' ORDER BY created_at ASC")
    fun getPending(): Flow<List<DownloadQueueEntity>>

    /** Reactive stream of downloads filtered by [status]. */
    @Query("SELECT * FROM download_queue WHERE status = :status ORDER BY created_at ASC")
    fun getByStatus(status: DownloadStatus): Flow<List<DownloadQueueEntity>>

    /** Find a download queue entry by the associated track ID. */
    @Query("SELECT * FROM download_queue WHERE track_id = :trackId LIMIT 1")
    suspend fun getByTrackId(trackId: Long): DownloadQueueEntity?

    /** Reactive count of tracks deferred because no lossless source could serve them. */
    @Query("SELECT COUNT(*) FROM download_queue WHERE status = 'WAITING_FOR_LOSSLESS'")
    fun waitingForLosslessCount(): Flow<Int>

    /** All deferred entries, for [LosslessRetryWorker] to re-resolve. */
    @Query("SELECT * FROM download_queue WHERE status = 'WAITING_FOR_LOSSLESS' ORDER BY created_at ASC")
    suspend fun waitingForLosslessTracks(): List<DownloadQueueEntity>

    /** Retrieve all pending downloads for a specific sync run, ordered by creation time. */
    @Query("SELECT * FROM download_queue WHERE sync_id = :syncId AND status = 'PENDING' ORDER BY created_at ASC")
    suspend fun getPendingBySyncId(syncId: Long): List<DownloadQueueEntity>

    /** Retrieve ALL pending downloads whose track is in a currently
     *  sync-enabled playlist, filtered by connected sources. Without
     *  the EXISTS predicate, prior syncs of now-disabled playlists
     *  leak their PENDING rows into every subsequent sync — turning
     *  a 55-track sync into thousands of phantom downloads.
     *  Spotify tracks (no youtube_url) are prioritized. */
    @Query("""
        SELECT dq.* FROM download_queue dq
        INNER JOIN tracks t ON t.id = dq.track_id
        LEFT JOIN track_blocklist bl
            ON bl.canonical_key = (t.canonical_artist || '|' || t.canonical_title)
            OR (bl.spotify_uri IS NOT NULL AND bl.spotify_uri = t.spotify_uri)
            OR (bl.youtube_id  IS NOT NULL AND bl.youtube_id  = t.youtube_id)
        WHERE dq.status = 'PENDING'
          AND dq.sync_id IS NOT NULL
          AND t.source IN (:sources)
          AND bl.canonical_key IS NULL
          AND EXISTS (
              SELECT 1 FROM playlist_tracks pt
              INNER JOIN playlists p ON p.id = pt.playlist_id
              WHERE pt.track_id = t.id
                AND pt.removed_at IS NULL
                AND p.sync_enabled = 1
                -- Must also be ACTIVE (visible in the Sync UI). A playlist
                -- can be sync_enabled=1 yet is_active=0 — e.g. a YouTube
                -- daily mix force-enabled by the one-shot migration, then
                -- rotated out of the feed. Hidden from the UI (so the user
                -- can't toggle it off) but it kept downloading (observed:
                -- Replay Mix queued 90 tracks). Mirrors the is_active=1
                -- guard in PlaylistDao.getSyncEnabledPlaylists.
                AND p.is_active = 1
                -- Stash Mixes are stream-only (v0.9.37 seam): their stubs
                -- live in a sync_enabled playlist (so they stay visible
                -- offline) but must NEVER be download-eligible. Require a
                -- sync-enabled, NON-mix parent. A track also in Liked Songs
                -- /a real playlist still matches via that membership.
                AND p.type != 'STASH_MIX'
          )
        ORDER BY (CASE WHEN dq.youtube_url IS NULL THEN 0 ELSE 1 END) ASC, dq.created_at ASC
    """)
    suspend fun getAllPendingBySources(sources: List<String>): List<DownloadQueueEntity>

    /**
     * Retrieve failed downloads that should be retried (max 3 attempts),
     * filtered to only include tracks from the given [sources] AND from
     * a currently sync-enabled playlist. Without the playlist predicate,
     * failed entries from disabled playlists keep getting retried every
     * sync forever.
     * Spotify tracks (needing YouTube search) are prioritized first.
     */
    @Query("""
        SELECT dq.* FROM download_queue dq
        INNER JOIN tracks t ON t.id = dq.track_id
        LEFT JOIN track_blocklist bl
            ON bl.canonical_key = (t.canonical_artist || '|' || t.canonical_title)
            OR (bl.spotify_uri IS NOT NULL AND bl.spotify_uri = t.spotify_uri)
            OR (bl.youtube_id  IS NOT NULL AND bl.youtube_id  = t.youtube_id)
        WHERE dq.status = 'FAILED' AND dq.retry_count < 3
          AND dq.sync_id IS NOT NULL
          AND t.source IN (:sources)
          AND bl.canonical_key IS NULL
          AND EXISTS (
              SELECT 1 FROM playlist_tracks pt
              INNER JOIN playlists p ON p.id = pt.playlist_id
              WHERE pt.track_id = t.id
                AND pt.removed_at IS NULL
                AND p.sync_enabled = 1
                -- Must also be ACTIVE (visible in the Sync UI). A playlist
                -- can be sync_enabled=1 yet is_active=0 — e.g. a YouTube
                -- daily mix force-enabled by the one-shot migration, then
                -- rotated out of the feed. Hidden from the UI (so the user
                -- can't toggle it off) but it kept downloading (observed:
                -- Replay Mix queued 90 tracks). Mirrors the is_active=1
                -- guard in PlaylistDao.getSyncEnabledPlaylists.
                AND p.is_active = 1
                -- Stash Mixes are stream-only (v0.9.37 seam): their stubs
                -- live in a sync_enabled playlist (so they stay visible
                -- offline) but must NEVER be download-eligible. Require a
                -- sync-enabled, NON-mix parent. A track also in Liked Songs
                -- /a real playlist still matches via that membership.
                AND p.type != 'STASH_MIX'
          )
        ORDER BY (CASE WHEN dq.youtube_url IS NULL THEN 0 ELSE 1 END) ASC, dq.created_at ASC
    """)
    suspend fun getRetryableBySources(sources: List<String>): List<DownloadQueueEntity>

    /**
     * Discovery rows queued for download — `sync_id IS NULL` partitions
     * them away from sync-chain [com.stash.core.data.sync.workers.TrackDownloadWorker]
     * (which after the v0.9.20 predicate update only touches rows with
     * non-null sync_id).
     *
     * Includes FAILED rows with retry_count < 3 so a transient network
     * blip doesn't permanently sideline a discovery candidate — matches
     * the retry posture of [getRetryableBySources].
     *
     * Filtered to exclude WAITING_FOR_LOSSLESS (owned by LosslessRetryWorker)
     * and IN_PROGRESS / COMPLETED (already running or done).
     */
    @Query(
        """
        SELECT * FROM download_queue
        WHERE sync_id IS NULL
          AND (status = 'PENDING' OR (status = 'FAILED' AND retry_count < 3))
        ORDER BY created_at ASC
        """
    )
    suspend fun pendingDiscoveryDownloads(): List<DownloadQueueEntity>

    // ── Updates ─────────────────────────────────────────────────────────

    /**
     * Update the status of a download queue entry.
     *
     * @param id            Row ID of the queue entry.
     * @param status        New [DownloadStatus].
     * @param errorMessage  Error description when status is FAILED, null otherwise.
     * @param completedAt   Epoch-millis timestamp when the download finished, or null.
     */
    @Query("""
        UPDATE download_queue
        SET status = :status,
            error_message = :errorMessage,
            completed_at = :completedAt,
            failure_type = :failureType,
            rejected_video_id = :rejectedVideoId
        WHERE id = :id
    """)
    suspend fun updateStatus(
        id: Long,
        status: DownloadStatus,
        errorMessage: String? = null,
        completedAt: Long? = null,
        failureType: DownloadFailureType = DownloadFailureType.NONE,
        rejectedVideoId: String? = null,
    )

    // ── Failed Downloads viewer (Library Health phase 1) ────────────────

    /** Look up a single queue row by primary key. Used by the
     *  Failed Downloads viewer and by tests that need to read back
     *  exactly what [markFailed] / [atomicallyClaimForRetry] wrote. */
    @Query("SELECT * FROM download_queue WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): DownloadQueueEntity?

    /**
     * Reactive feed for the Failed Downloads viewer (Task 16). Joins the
     * queue row to its [com.stash.core.data.db.entity.TrackEntity] for
     * title / artist / album art, and pulls a single playlist name via a
     * scalar subquery so the viewer can show "from <playlist>" without a
     * separate query per row.
     *
     * Returns the first sync-enabled playlist a track belongs to, falling
     * back to the first non-sync-enabled playlist if none are enabled —
     * `ORDER BY p.sync_enabled DESC, p.id ASC` prefers enabled rows (1 > 0)
     * and uses the smallest id as a stable tie-breaker. This matches the
     * spec §3.6 rule "pick the first sync-enabled playlist arbitrarily"
     * while still surfacing *some* name for orphaned tracks whose only
     * memberships are sync-disabled.
     *
     * Excludes `NONE` (the implicit value for non-failed rows that may
     * still carry a `FAILED` status from legacy data) and `NO_MATCH`
     * (which has its own dedicated Failed Matches detail screen — see
     * [getUnmatchedTracks]).
     */
    @Query("""
        SELECT dq.id AS queueId, t.id AS trackId, t.title, t.artist,
               t.album_art_url AS albumArtUrl,
               (SELECT p.name FROM playlists p
                 INNER JOIN playlist_tracks pt ON pt.playlist_id = p.id
                 WHERE pt.track_id = t.id AND pt.removed_at IS NULL
                 ORDER BY p.sync_enabled DESC, p.id ASC LIMIT 1) AS playlistName,
               dq.failure_type AS failureType,
               dq.error_message AS errorMessage,
               dq.retry_count AS retryCount,
               dq.completed_at AS completedAt
          FROM download_queue dq
          INNER JOIN tracks t ON t.id = dq.track_id
         WHERE dq.status = 'FAILED'
           AND dq.failure_type != 'NONE'
           AND dq.failure_type != 'NO_MATCH'
         ORDER BY COALESCE(dq.completed_at, dq.created_at) DESC
    """)
    fun getFailedDownloads(): Flow<List<FailedDownloadRow>>

    /**
     * Atomic single-row retry claim: flip status FAILED → PENDING, clear
     * the classified failure_type / error_message in one UPDATE. Returns
     * the number of rows affected so the caller can detect lost races
     * (e.g. another worker already claimed the row).
     */
    @Query("""
        UPDATE download_queue
           SET status = 'PENDING', error_message = NULL, failure_type = 'NONE'
         WHERE id = :queueId AND status = 'FAILED'
    """)
    suspend fun atomicallyClaimForRetry(queueId: Long): Int

    /** Internal helper for [atomicallyClaimGroupForRetry]. */
    @Query("SELECT id FROM download_queue WHERE status = 'FAILED' AND failure_type = :type")
    suspend fun selectFailedIdsByType(type: DownloadFailureType): List<Long>

    /** Internal helper for [atomicallyClaimAllForRetry]. */
    @Query("SELECT id FROM download_queue WHERE status = 'FAILED' AND failure_type NOT IN ('NONE', 'NO_MATCH')")
    suspend fun selectAllNonMatchFailedIds(): List<Long>

    /** Internal helper: bulk flip a known set of FAILED rows back to PENDING. */
    @Query("UPDATE download_queue SET status='PENDING', error_message=NULL, failure_type='NONE' WHERE id IN (:ids)")
    suspend fun resetToPending(ids: List<Long>)

    /**
     * Atomic group retry: select all FAILED rows of a given failure type
     * then flip them back to PENDING inside a single transaction. Returns
     * the list of queue IDs that were claimed (so the caller can enqueue
     * the corresponding workers).
     */
    @Transaction
    suspend fun atomicallyClaimGroupForRetry(type: DownloadFailureType): List<Long> {
        val ids = selectFailedIdsByType(type)
        if (ids.isNotEmpty()) resetToPending(ids)
        return ids
    }

    /**
     * Atomic "retry all": flip every classified-failure FAILED row back to
     * PENDING (excluding `NO_MATCH` and `NONE`) inside a single transaction.
     * Returns the list of queue IDs claimed.
     */
    @Transaction
    suspend fun atomicallyClaimAllForRetry(): List<Long> {
        val ids = selectAllNonMatchFailedIds()
        if (ids.isNotEmpty()) resetToPending(ids)
        return ids
    }

    /**
     * Thin wrapper over the existing [updateStatus] — sets status=FAILED +
     * error_message + classified failure_type + completed_at in one place,
     * while leaving the existing [updateStatus] contract (which supports
     * `rejected_video_id` for the NO_MATCH lane) untouched. Pass
     * `rejectedVideoId=null` because the classified-failure caller has no
     * rejected match to record.
     */
    suspend fun markFailed(
        queueId: Long,
        errorMessage: String?,
        failureType: DownloadFailureType,
        completedAt: Long = System.currentTimeMillis(),
    ) {
        updateStatus(
            id = queueId,
            status = DownloadStatus.FAILED,
            errorMessage = errorMessage,
            completedAt = completedAt,
            failureType = failureType,
            rejectedVideoId = null,
        )
    }

    /** Increment the retry count for a download queue entry. */
    @Query("UPDATE download_queue SET retry_count = retry_count + 1 WHERE id = :id")
    suspend fun incrementRetryCount(id: Long)

    /**
     * Used when a download attempt is interrupted by a transient cause
     * (network drop, storage hiccup, mid-run auth expiry, etc.). Leaves the
     * row at PENDING for the next sync to retry, but bumps retry_count and
     * records the last error type/message for telemetry. Crucially: status
     * is PENDING, NOT FAILED — the track itself isn't broken, the sync just
     * couldn't finish it this time.
     *
     * Note: `failure_type` is populated for telemetry but the row stays at
     * status=PENDING, which keeps it out of the Failed Downloads viewer's
     * `getFailedDownloads()` query (that query gates on `status='FAILED'`).
     *
     * Callers should escalate to [markFailed] once retry_count reaches the
     * `TRANSIENT_RETRY_LIMIT` defined at the worker layer, so persistently-
     * stuck rows eventually surface to the user.
     */
    @Query("""
        UPDATE download_queue
           SET status = 'PENDING',
               error_message = :lastErrorMessage,
               failure_type = :lastFailureType,
               retry_count = retry_count + 1,
               completed_at = NULL
         WHERE id = :queueId
    """)
    suspend fun revertToPendingAfterInterruption(
        queueId: Long,
        lastErrorMessage: String?,
        lastFailureType: DownloadFailureType,
    )

    /**
     * Stamp a YouTube URL onto a pending queue row that doesn't have one
     * yet. Used by DiffWorker enrichment: when a YT Music sync deduplicates
     * a track snapshot against a Spotify-source row whose queue entry has
     * `youtube_url IS NULL`, we fill it in so DownloadManager skips the
     * Spotify → YT match path and uses the YT URL directly. Guarded by
     * `youtube_url IS NULL` so already-stamped rows aren't overwritten.
     */
    @Query(
        """
        UPDATE download_queue
        SET youtube_url = :url
        WHERE track_id = :trackId
          AND status = 'PENDING'
          AND youtube_url IS NULL
        """
    )
    suspend fun fillMissingYoutubeUrlForTrack(trackId: Long, url: String): Int

    /**
     * Cancel-by-delete: drop PENDING queue rows for tracks that no
     * longer belong to any sync-enabled playlist. Run as a defensive
     * cleanup so deselecting a playlist eventually stops its leftover
     * downloads. Tracks linked to at least one enabled playlist (any
     * source) are preserved. `is_active = 1` excludes archived
     * playlists from the membership check.
     *
     * Returns the number of queue rows deleted.
     */
    @Query(
        """
        DELETE FROM download_queue
        WHERE status = 'PENDING'
          AND track_id NOT IN (
            SELECT DISTINCT pt.track_id
            FROM playlist_tracks pt
            INNER JOIN playlists p ON p.id = pt.playlist_id
            WHERE p.sync_enabled = 1 AND p.is_active = 1
          )
        """
    )
    suspend fun cancelDownloadsWithNoEnabledPlaylist(): Int

    // ── Cleanup ─────────────────────────────────────────────────────────

    /** Delete all completed download entries to free up space. */
    @Query("DELETE FROM download_queue WHERE status = 'COMPLETED'")
    suspend fun deleteCompleted()

    /** Reset retry count for all failed entries so they can be retried after a bug fix. */
    @Query("UPDATE download_queue SET retry_count = 0, status = 'FAILED' WHERE status = 'FAILED' AND retry_count >= 3")
    suspend fun resetExhaustedRetries()

    /**
     * Reset stale IN_PROGRESS entries back to PENDING. Called at the start
     * of each TrackDownloadWorker run. Since the worker is a unique WorkManager
     * chain (only one instance runs at a time), any IN_PROGRESS entries at
     * the start of doWork() are guaranteed to be leftovers from a previous
     * interrupted run, not active downloads.
     *
     * @return Number of entries reset.
     */
    @Query("UPDATE download_queue SET status = 'PENDING' WHERE status = 'IN_PROGRESS'")
    suspend fun resetStaleInProgress(): Int

    /**
     * Bulk requeue: flip every WAITING_FOR_LOSSLESS row back to PENDING.
     * Called when the user disables lossless or enables YouTube fallback —
     * the deferred state only makes sense under "lossless on + fallback off",
     * so any other configuration should release the queue.
     *
     * @return number of rows flipped.
     */
    @Query("UPDATE download_queue SET status = 'PENDING' WHERE status = 'WAITING_FOR_LOSSLESS'")
    suspend fun requeueWaitingForLossless(): Int

    /**
     * Cancel all pending/in-progress downloads for tracks from a specific source.
     * Called when the user disconnects a service (Spotify/YouTube) to prevent
     * stale downloads from clogging the queue.
     *
     * @return Number of entries cancelled.
     */
    @Query("""
        DELETE FROM download_queue
        WHERE status IN ('PENDING', 'IN_PROGRESS')
        AND track_id IN (SELECT id FROM tracks WHERE source = :source)
    """)
    suspend fun cancelDownloadsForSource(source: String): Int

    /** Diagnostic: count queue entries by status. */
    @Query("SELECT status, COUNT(*) FROM download_queue GROUP BY status")
    suspend fun getStatusCounts(): List<StatusCount>

    /** Diagnostic: count undownloaded tracks (in a sync-enabled playlist)
     *  with no active queue entry, by source. Mirrors the predicate in
     *  [getUnqueuedTrackIds] so the diagnostic count matches what will
     *  actually be re-queued. */
    @Query("""
        SELECT t.source, COUNT(*) as cnt FROM tracks t
        WHERE t.is_downloaded = 0
          AND t.match_dismissed = 0
          AND EXISTS (
              SELECT 1 FROM playlist_tracks pt
              INNER JOIN playlists p ON p.id = pt.playlist_id
              WHERE pt.track_id = t.id
                AND pt.removed_at IS NULL
                AND p.sync_enabled = 1
                -- Must also be ACTIVE (visible in the Sync UI). A playlist
                -- can be sync_enabled=1 yet is_active=0 — e.g. a YouTube
                -- daily mix force-enabled by the one-shot migration, then
                -- rotated out of the feed. Hidden from the UI (so the user
                -- can't toggle it off) but it kept downloading (observed:
                -- Replay Mix queued 90 tracks). Mirrors the is_active=1
                -- guard in PlaylistDao.getSyncEnabledPlaylists.
                AND p.is_active = 1
                -- Stash Mixes are stream-only (v0.9.37 seam): their stubs
                -- live in a sync_enabled playlist (so they stay visible
                -- offline) but must NEVER be download-eligible. Require a
                -- sync-enabled, NON-mix parent. A track also in Liked Songs
                -- /a real playlist still matches via that membership.
                AND p.type != 'STASH_MIX'
          )
          AND t.id NOT IN (
              SELECT dq.track_id FROM download_queue dq
              WHERE dq.status IN ('PENDING', 'IN_PROGRESS', 'FAILED')
          )
        GROUP BY t.source
    """)
    suspend fun getOrphanedTrackCounts(): List<SourceCount>

    /**
     * Find tracks that need downloading but have no active queue entry.
     * These are tracks where is_downloaded=0 AND there's no PENDING/IN_PROGRESS
     * queue entry. Returns track IDs that need fresh queue entries.
     *
     * This covers tracks that:
     * - Had all their retries exhausted and queue entries cleaned up
     * - Were added by sync but never got queue entries (edge case)
     * - Had their queue entries deleted
     */
    @Query("""
        SELECT t.id FROM tracks t
        LEFT JOIN track_blocklist bl
            ON bl.canonical_key = (t.canonical_artist || '|' || t.canonical_title)
            OR (bl.spotify_uri IS NOT NULL AND bl.spotify_uri = t.spotify_uri)
            OR (bl.youtube_id  IS NOT NULL AND bl.youtube_id  = t.youtube_id)
        WHERE t.is_downloaded = 0
          AND t.match_dismissed = 0
          AND t.source IN (:sources)
          AND bl.canonical_key IS NULL
          AND EXISTS (
              SELECT 1 FROM playlist_tracks pt
              INNER JOIN playlists p ON p.id = pt.playlist_id
              WHERE pt.track_id = t.id
                AND pt.removed_at IS NULL
                AND p.sync_enabled = 1
                -- Must also be ACTIVE (visible in the Sync UI). A playlist
                -- can be sync_enabled=1 yet is_active=0 — e.g. a YouTube
                -- daily mix force-enabled by the one-shot migration, then
                -- rotated out of the feed. Hidden from the UI (so the user
                -- can't toggle it off) but it kept downloading (observed:
                -- Replay Mix queued 90 tracks). Mirrors the is_active=1
                -- guard in PlaylistDao.getSyncEnabledPlaylists.
                AND p.is_active = 1
                -- Stash Mixes are stream-only (v0.9.37 seam): their stubs
                -- live in a sync_enabled playlist (so they stay visible
                -- offline) but must NEVER be download-eligible. Require a
                -- sync-enabled, NON-mix parent. A track also in Liked Songs
                -- /a real playlist still matches via that membership.
                AND p.type != 'STASH_MIX'
          )
          AND t.id NOT IN (
              SELECT dq.track_id FROM download_queue dq
              WHERE dq.status IN ('PENDING', 'IN_PROGRESS', 'FAILED')
          )
    """)
    suspend fun getUnqueuedTrackIds(sources: List<String>): List<Long>

    /**
     * Self-healing sweep: delete PENDING/FAILED/WAITING_FOR_LOSSLESS queue
     * entries for tracks whose only parent playlists are sync-disabled.
     * Runs at the start of each [TrackDownloadWorker] run to evict the
     * stale rows that prior (pre-fix) syncs left behind. Without this,
     * the user's queue stays bloated with thousands of phantom downloads
     * even after the predicate fix lands.
     *
     * Spares any track that is still a member of at least one currently
     * sync-enabled playlist, and any IN_PROGRESS row (which the worker is
     * actively handling). Tracks with zero playlist memberships at all
     * (e.g. legacy search-tab orphans) are also evicted, matching the new
     * "must live in a sync-enabled playlist" contract enforced by the
     * other queries.
     *
     * v0.9.17: extended to also evict WAITING_FOR_LOSSLESS deferred rows
     * — they're queue entries with the same orphan semantics, just paused
     * waiting for a lossless source instead of actively pending.
     *
     * @return Number of rows deleted.
     */
    @Query("""
        DELETE FROM download_queue
        WHERE status IN ('PENDING', 'FAILED', 'WAITING_FOR_LOSSLESS')
          AND track_id NOT IN (
              SELECT pt.track_id FROM playlist_tracks pt
              INNER JOIN playlists p ON p.id = pt.playlist_id
              WHERE pt.removed_at IS NULL
                AND p.sync_enabled = 1
                -- Only an ACTIVE parent spares a row. A hidden (is_active=0)
                -- but sync_enabled=1 playlist — e.g. a rotated-out YouTube
                -- daily mix — does NOT spare its tracks, so this sweep drains
                -- the invisible-download backlog (Replay Mix's 90 queued
                -- tracks) the same way it drains mix-only orphans.
                AND p.is_active = 1
                -- A sync-enabled STASH_MIX membership does NOT spare a row:
                -- mix tracks are stream-only, so a mix-only track's queue
                -- entry is an orphan and gets swept. This drains the legacy
                -- pre-v0.9.48 backlog of force-queued mix downloads.
                AND p.type != 'STASH_MIX'
          )
    """)
    suspend fun deleteOrphanedQueueEntries(): Int

    // ── Unmatched track queries ─────────────────────────────────────────

    /** Unmatched tracks for the Failed Matches detail screen. */
    @Query("""
        SELECT dq.id, dq.track_id AS trackId, t.title, t.artist,
               t.album_art_url AS albumArtUrl, dq.created_at AS createdAt,
               dq.rejected_video_id AS rejectedVideoId,
               dq.search_query AS searchQuery
        FROM download_queue dq
        INNER JOIN tracks t ON dq.track_id = t.id
        WHERE dq.failure_type = 'NO_MATCH'
          AND dq.status = 'FAILED'
          AND t.match_dismissed = 0
        ORDER BY dq.created_at DESC
    """)
    fun getUnmatchedTracks(): Flow<List<UnmatchedTrackView>>

    /** Count of unmatched tracks for the Sync tab card. */
    @Query("""
        SELECT COUNT(*)
        FROM download_queue dq
        INNER JOIN tracks t ON dq.track_id = t.id
        WHERE dq.failure_type = 'NO_MATCH'
          AND dq.status = 'FAILED'
          AND t.match_dismissed = 0
    """)
    fun getUnmatchedCount(): Flow<Int>

    /** Delete all queue entries for a track (used on dismiss to prevent retry paths from picking it up). */
    @Query("DELETE FROM download_queue WHERE track_id = :trackId")
    suspend fun deleteByTrackId(trackId: Long)

    /** Find a queue entry by track ID (used for auto-reconciliation). */
    @Query("SELECT * FROM download_queue WHERE track_id = :trackId AND status = 'FAILED' LIMIT 1")
    suspend fun getFailedByTrackId(trackId: Long): DownloadQueueEntity?

    // ── Active Downloads page ───────────────────────────────────────────

    /**
     * Reactive feed for the Active Downloads page. Returns every queue row
     * whose status indicates it is queued, in-flight, or deferred — joined
     * with track metadata for display. Ordered by status priority
     * (IN_PROGRESS first so the user sees what's actively downloading),
     * then by creation time so the oldest queued track appears next.
     */
    @Query("""
        SELECT dq.id AS queueId, t.id AS trackId, t.title, t.artist,
               t.album_art_url AS albumArtUrl,
               dq.status, dq.created_at AS createdAt
          FROM download_queue dq
          INNER JOIN tracks t ON t.id = dq.track_id
         WHERE dq.status IN ('PENDING', 'IN_PROGRESS', 'WAITING_FOR_LOSSLESS')
         ORDER BY
           CASE dq.status
             WHEN 'IN_PROGRESS' THEN 0
             WHEN 'PENDING' THEN 1
             WHEN 'WAITING_FOR_LOSSLESS' THEN 2
           END,
           dq.created_at ASC
    """)
    fun getActiveDownloads(): Flow<List<ActiveDownloadRow>>

    /** Reactive count of active (queued + in-progress) downloads for badge display. */
    @Query("SELECT COUNT(*) FROM download_queue WHERE status IN ('PENDING', 'IN_PROGRESS')")
    fun getActiveDownloadCount(): Flow<Int>

    /**
     * Reactive feed of recently completed downloads for the history tab.
     * Capped at 200 most recent by completed_at timestamp.
     */
    @Query("""
        SELECT dq.id AS queueId, t.id AS trackId, t.title, t.artist,
               t.album_art_url AS albumArtUrl,
               dq.status, dq.created_at AS createdAt
          FROM download_queue dq
          INNER JOIN tracks t ON t.id = dq.track_id
         WHERE dq.status = 'COMPLETED'
         ORDER BY dq.completed_at DESC
         LIMIT 200
    """)
    fun getCompletedDownloads(): Flow<List<ActiveDownloadRow>>

    /** Cancel a single download by removing its queue entry. */
    @Query("DELETE FROM download_queue WHERE id = :queueId")
    suspend fun deleteById(queueId: Long)
}

/**
 * Room projection for unmatched track display.
 * Uses column aliases to map join results to flat fields.
 */
data class UnmatchedTrackView(
    val id: Long,
    val trackId: Long,
    val title: String,
    val artist: String,
    val albumArtUrl: String?,
    val createdAt: Long,
    val rejectedVideoId: String?,
    val searchQuery: String,
)

/**
 * Room projection for the Failed Downloads viewer (Task 16). One row per
 * FAILED queue entry whose `failure_type` is a classified, retryable
 * failure (i.e. not `NONE` and not `NO_MATCH` — the latter has its own
 * dedicated screen). `playlistName` is a single representative playlist
 * scalar-subqueried at the SQL layer to avoid an N+1.
 */
data class FailedDownloadRow(
    val queueId: Long,
    val trackId: Long,
    val title: String,
    val artist: String,
    val albumArtUrl: String?,
    val playlistName: String?,
    val failureType: DownloadFailureType,
    val errorMessage: String?,
    val retryCount: Int,
    val completedAt: Long?,
)

/**
 * Room projection for the Active Downloads page. One row per queue entry
 * that is currently queued, downloading, or waiting for a lossless source.
 */
data class ActiveDownloadRow(
    val queueId: Long,
    val trackId: Long,
    val title: String,
    val artist: String,
    val albumArtUrl: String?,
    val status: DownloadStatus,
    val createdAt: Long,
)
