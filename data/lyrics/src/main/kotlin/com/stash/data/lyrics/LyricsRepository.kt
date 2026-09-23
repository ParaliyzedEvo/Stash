package com.stash.data.lyrics

import android.util.Log
import com.stash.core.common.Clock
import com.stash.core.data.db.dao.LyricsDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.LyricsEntity
import com.stash.data.lyrics.sidecar.LyricsSidecarWriter
import com.stash.core.data.prefs.LyricsPreference
import com.stash.core.data.prefs.LyricsSourcePreference
import com.stash.data.lyrics.source.AppleTtmlLyricsSource
import com.stash.data.lyrics.source.LyricsQuery
import kotlinx.coroutines.flow.first
import com.stash.data.lyrics.source.LyricsResult
import com.stash.data.lyrics.source.LyricsSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sole entrypoint for the lyrics subsystem. Both UI (Now Playing sheet)
 * and workers (post-download + backfill) go through this class.
 *
 * `resolveAndStore` walks the [sources] chain in priority order
 * (LRCLIB -> KuGou -> InnerTube; ordering enforced in `LyricsModule.provideLyricsSources`),
 * persists the first non-null result to the `lyrics` table, stamps
 * `tracks.lyrics_fetched_at` with the success epoch-millis, and
 * triggers a sidecar `.lrc` write for the non-instrumental case.
 *
 * Sentinel rules (see `LyricsDao` docs):
 * - Successful fetch -> upsert lyrics row + stamp `lyrics_fetched_at = clock.now()`.
 * - Definitive miss across all sources -> no row + stamp `lyrics_fetched_at = 0L`.
 *   The 0L sentinel keeps the backfill worker's `WHERE lyrics_fetched_at IS NULL`
 *   predicate honest so it terminates.
 * - Source FAILURE (network/HTTP/parse — the source threw) with no hit from a
 *   later source -> THROWS and leaves the stamp untouched, so the track stays
 *   retryable (worker backoff, next sheet open). Failures must never write the
 *   0L sentinel: conflating them permanently miss-stamped ~72% of the library's
 *   "No lyrics found" tracks (2026-07-05 forensics).
 *
 * Sidecar-write failure is logged but does NOT unwind the Room state.
 * The Room row + stamp are the source of truth; the sidecar is a best-effort
 * courtesy for external players.
 */
/** Outcome of [LyricsRepository.upgradeToTtml]. Only UPGRADED changes stored lyrics. */
enum class TtmlUpgradeResult { UPGRADED, NO_TTML, FAILED, SKIPPED }

/** Outcome of [LyricsRepository.fetchLyricsNow] (manual "fetch lyrics" run). */
enum class ManualFetchResult { FETCHED, NOT_FOUND, FAILED, SKIPPED }

@Singleton
class LyricsRepository @Inject constructor(
    private val sources: List<@JvmSuppressWildcards LyricsSource>,
    private val lyricsDao: LyricsDao,
    private val trackDao: TrackDao,
    private val sidecarWriter: LyricsSidecarWriter,
    private val clock: Clock,
    private val lyricsPreference: LyricsPreference,
) {

    /** Observe the lyrics row for [trackId]. Emits null when no row exists yet. */
    fun observe(trackId: Long): Flow<LyricsEntity?> = lyricsDao.observe(trackId)

    /**
     * Observe the parent track's `lyrics_fetched_at` stamp. The sheet pairs this
     * with [observe] so it reacts the moment a fetch finishes — the stamp is what
     * distinguishes "never tried" (null → Loading) from "tried and missed"
     * (0L → None), and watching it live is what stops the sheet sticking on
     * Loading until a close+reopen re-queries the track.
     */
    fun observeFetchedAt(trackId: Long): Flow<Long?> = trackDao.observeLyricsFetchedAt(trackId)

    /** One-shot read of the lyrics row for [trackId], or null when absent. */
    suspend fun get(trackId: Long): LyricsEntity? = lyricsDao.get(trackId)

    /**
     * Walks [sources] in order, returns the first non-null
     * [com.stash.data.lyrics.source.LyricsResult], persists it to Room,
     * stamps `tracks.lyrics_fetched_at`, and (for non-instrumental hits)
     * triggers a sidecar `.lrc` write.
     *
     * On a definitive all-source miss, stamps `tracks.lyrics_fetched_at = 0L`
     * and returns null without writing a row. If any source FAILED and no
     * later source hit, rethrows that failure without stamping — the caller
     * decides retry policy (worker backoff / sheet Error state).
     */
    suspend fun resolveAndStore(query: LyricsQuery): LyricsEntity? {
        val result = walkSources(query)
        if (result == null) {
            trackDao.setLyricsFetchedAt(query.trackId, 0L)
            return null
        }
        val now = clock.now()
        val entity = LyricsEntity(
            trackId = query.trackId,
            plainText = result.plainText,
            syncedLrc = result.syncedLrc,
            instrumental = result.instrumental,
            language = result.language,
            source = result.sourceId,
            sourceLyricsId = result.sourceLyricsId,
            fetchedAt = now,
            ttml = result.ttml,
        )
        lyricsDao.upsert(entity)
        trackDao.setLyricsFetchedAt(query.trackId, now)
        if (!result.instrumental) {
            runCatching { sidecarWriter.write(query.trackId, entity) }
                .onFailure { e ->
                    // Non-fatal: Room row + stamp are the source of truth.
                    Log.w(TAG, "Sidecar write failed for trackId=${query.trackId}", e)
                }
        }
        return entity
    }

    /**
     * Transient source-chain walk for tracks that have no persistent
     * `tracks` row to key against — typically streaming-mode playback
     * where the audio is fetched by URL and never written to the
     * library. Returns the first non-null [LyricsResult] without
     * touching Room or the sidecar writer; caller renders the result
     * directly into its own state.
     *
     * Re-opening the sheet on the same streaming track re-runs the
     * source chain (no cache). LRCLIB is fast (~200ms typical) so the
     * UX cost is acceptable, and avoiding Room means we don't have to
     * invent a fake parent row for the FK CASCADE.
     *
     * Same failure contract as [resolveAndStore]: null = definitive miss,
     * throws when a source failed and nothing hit.
     */
    suspend fun resolveTransient(query: LyricsQuery): LyricsResult? = walkSources(query)

    /**
     * Clears `tracks.lyrics_fetched_at` back to NULL (never tried). The
     * Retry path calls this before re-resolving so the sheet's stamp
     * observer flips to Loading immediately — the visible feedback that
     * was missing when Retry left the 0L sentinel in place.
     */
    suspend fun clearFetchStamp(trackId: Long) = trackDao.setLyricsFetchedAt(trackId, null)

    /** Empty when the user has set LRC-only — nothing to upgrade if Apple is never consulted. */
    suspend fun trackIdsPendingTtml(): List<Long> {
        if (lyricsPreference.sourcePreference.first() == LyricsSourcePreference.LRC_ONLY) return emptyList()
        return lyricsDao.trackIdsPendingTtml()
    }

    /**
     * Applies a new [LyricsSourcePreference]. Switching TO [LyricsSourcePreference.LRC_ONLY] wipes
     * every stored row's TTML (column + `.ttml` sidecar) and clears `tracks.lyrics_fetched_at` for
     * those tracks, so they fall back into the existing "missing lyrics" pool — the normal retag /
     * manual-fetch pipeline then re-fills them from LRC sources only, since [walkSources] now skips
     * Apple. Switching back to APPLE_MUSIC does nothing extra: Apple simply re-enters the chain on
     * the next fetch for whatever's still missing.
     */
    suspend fun setSourcePreference(preference: LyricsSourcePreference) {
        if (preference == LyricsSourcePreference.LRC_ONLY) {
            val affected = lyricsDao.trackIdsWithTtml()
            affected.forEach { id -> sidecarWriter.deleteTtmlSidecar(id) }
            lyricsDao.clearAllTtml()
            affected.forEach { id -> trackDao.setLyricsFetchedAt(id, null) }
            Log.i(TAG, "Switched to LRC-only: wiped TTML for ${affected.size} track(s), queued for re-fetch")
        }
        lyricsPreference.setSourcePreference(preference)
    }

    /** Downloaded tracks that have no lyrics (never tried, or an earlier all-source miss). */
    suspend fun trackIdsMissingLyrics(): List<Long> = lyricsDao.trackIdsMissingLyrics()

    /**
     * Manual-run path: walks the full source chain for one track and stores the hit. Failures are
     * swallowed into [ManualFetchResult.FAILED] (state untouched, so it stays retryable); a definitive
     * miss re-stamps 0L exactly as [resolveAndStore] always does.
     */
    suspend fun fetchLyricsNow(trackId: Long): ManualFetchResult {
        val track = trackDao.getById(trackId) ?: return ManualFetchResult.SKIPPED
        val query = LyricsQuery(
            trackId = track.id,
            title = track.title,
            artist = track.artist,
            album = track.album.ifBlank { null },
            albumArtist = track.albumArtist.ifBlank { null },
            durationMs = track.durationMs.takeIf { it > 0 },
            youtubeVideoId = track.youtubeId,
        )
        return try {
            if (resolveAndStore(query) != null) ManualFetchResult.FETCHED else ManualFetchResult.NOT_FOUND
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Manual lyrics fetch failed for trackId=$trackId", e)
            ManualFetchResult.FAILED
        }
    }

    /**
     * Backfill path: asks ONLY the Apple TTML source (never the whole chain, so a good LRC can't be
     * swapped for a worse one) and replaces the stored lyrics + sidecar only on a hit.
     *
     * - hit                  -> row upserted (source/plain/synced/ttml), fetch stamp refreshed,
     *                           `.lrc` (+ `.ttml`) sidecar rewritten -> UPGRADED
     * - clean miss           -> `ttml_checked_at` stamped, row untouched -> NO_TTML
     * - source threw         -> NOTHING written, stays pending for the next run -> FAILED
     * - no row / already TTML / instrumental / source not in chain -> SKIPPED
     */
    suspend fun upgradeToTtml(trackId: Long): TtmlUpgradeResult {
        if (lyricsPreference.sourcePreference.first() == LyricsSourcePreference.LRC_ONLY) {
            return TtmlUpgradeResult.SKIPPED
        }
        val apple = sources.firstOrNull { it.id == AppleTtmlLyricsSource.SOURCE_ID }
            ?: return TtmlUpgradeResult.SKIPPED
        val existing = lyricsDao.get(trackId) ?: return TtmlUpgradeResult.SKIPPED
        if (existing.ttml != null || existing.instrumental) return TtmlUpgradeResult.SKIPPED
        val track = trackDao.getById(trackId) ?: return TtmlUpgradeResult.SKIPPED

        val query = LyricsQuery(
            trackId = track.id,
            title = track.title,
            artist = track.artist,
            album = track.album.ifBlank { null },
            albumArtist = track.albumArtist.ifBlank { null },
            durationMs = track.durationMs.takeIf { it > 0 },
            youtubeVideoId = track.youtubeId,
        )
        val result = try {
            apple.resolve(query)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "TTML upgrade failed for trackId=$trackId", e)
            return TtmlUpgradeResult.FAILED
        }

        val now = clock.now()
        if (result == null) {
            lyricsDao.markTtmlChecked(trackId, now)
            return TtmlUpgradeResult.NO_TTML
        }
        val entity = LyricsEntity(
            trackId = trackId,
            plainText = result.plainText,
            syncedLrc = result.syncedLrc,
            instrumental = false,
            language = result.language ?: existing.language,
            source = result.sourceId,
            sourceLyricsId = result.sourceLyricsId,
            fetchedAt = now,
            ttml = result.ttml,
            ttmlCheckedAt = now,
        )
        lyricsDao.upsert(entity)
        trackDao.setLyricsFetchedAt(trackId, now)
        if (track.filePath != null) {
            runCatching { sidecarWriter.write(trackId, entity) }
                .onFailure { e -> Log.w(TAG, "Sidecar rewrite failed for trackId=$trackId", e) }
        }
        return TtmlUpgradeResult.UPGRADED
    }

    /**
     * Priority-ordered source walk distinguishing miss from failure: a
     * throwing source is logged and the walk continues (LRCLIB down must
     * not block the InnerTube fallback), but if nothing hits and at least
     * one source failed, the first failure is rethrown — "no lyrics" may
     * only be concluded from sources that actually answered.
     */
    /** [sources] with Apple TTML filtered out when the user has chosen LRC-only. */
    private suspend fun activeSources(): List<LyricsSource> =
        if (lyricsPreference.sourcePreference.first() == LyricsSourcePreference.LRC_ONLY) {
            sources.filterNot { it.id == AppleTtmlLyricsSource.SOURCE_ID }
        } else {
            sources
        }

    private suspend fun walkSources(query: LyricsQuery): LyricsResult? {
        var firstFailure: Exception? = null
        for (source in activeSources()) {
            try {
                source.resolve(query)?.let { return it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Lyrics source ${source.id} failed for trackId=${query.trackId}", e)
                if (firstFailure == null) firstFailure = e
            }
        }
        firstFailure?.let { throw it }
        return null
    }

    private companion object {
        private const val TAG = "LyricsRepository"
    }
}
