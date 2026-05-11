package com.stash.core.data.sync.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.stash.core.data.db.dao.DiscoveryQueueDao
import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.StashMixRecipeDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.StashMixRecipeEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmCredentials
import com.stash.core.data.lastfm.LastFmPeriod
import com.stash.core.data.lastfm.LastFmPersonas
import com.stash.core.data.lastfm.LastFmSessionPreference
import com.stash.core.data.lastfm.LastFmTopArtist
import com.stash.core.data.lastfm.LastFmTopTrack
import com.stash.core.data.mix.MixGenerator
import com.stash.core.data.mix.MixSeedGenerator
import com.stash.core.data.mix.MixSeedStrategy
import com.stash.core.data.mix.StashMixDefaults
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Periodic worker that regenerates every active Stash Mix. For each
 * recipe:
 *
 *  1. Run [MixGenerator.generate] — pure-Kotlin ranking over the library.
 *  2. Find-or-create the backing [PlaylistEntity]. First refresh creates
 *     a new row with `type = STASH_MIX` and stores its id back on the
 *     recipe. Subsequent refreshes replace the track list in place so
 *     the Home-screen card URL and the user's history pointers stay
 *     stable.
 *  3. Rewrite [PlaylistTrackCrossRef] rows (REFRESH semantics).
 *  4. Recompute the cover art tiles from the top tracks.
 *  5. Stamp `last_refreshed_at`.
 *  6. For recipes with non-zero discovery ratio: query Last.fm
 *     `artist.getSimilar` seeded from the user's current top artists in
 *     that recipe's tag space, and enqueue candidate tracks into
 *     [com.stash.core.data.db.dao.DiscoveryQueueDao]. The actual search
 *     + download happens in a separate worker.
 *
 * Runs once per day (via WorkManager periodic scheduling) with no network
 * constraint — mix generation is purely local. The discovery Last.fm
 * queries inside run in a best-effort runCatching so a network outage
 * never blocks refreshing.
 */
@HiltWorker
class StashMixRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val recipeDao: StashMixRecipeDao,
    private val playlistDao: PlaylistDao,
    private val discoveryQueueDao: DiscoveryQueueDao,
    private val listeningEventDao: ListeningEventDao,
    private val trackDao: TrackDao,
    private val mixGenerator: MixGenerator,
    private val seedGenerator: MixSeedGenerator,
    private val lastFmApiClient: LastFmApiClient,
    private val lastFmCredentials: LastFmCredentials,
    private val sessionPreference: LastFmSessionPreference,
    private val blocklistGuard: com.stash.core.data.blocklist.BlocklistGuard,
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "StashMixRefresh"
        private const val WORK_NAME = "stash_mix_refresh"
        const val ONE_SHOT_WORK_NAME = "stash_mix_refresh_oneshot"
        private const val TOP_ARTISTS_LIMIT = 8
        private const val SIMILAR_REQUEST_INTERVAL_MS = 220L
        private const val AFFINITY_LOOKBACK_DAYS = 180L

        /**
         * Input-data key for [enqueueOneTime] (single-recipe overload).
         * When present and > 0, [doWork] only refreshes that one recipe
         * instead of iterating every active builtin. Backs the manual
         * "Refresh this mix" action surfaced from the Home long-press
         * menu.
         */
        const val KEY_RECIPE_ID = "stash_mix_refresh_recipe_id"

        /**
         * Schedule the periodic refresh. Default 24-hour cadence with no
         * constraints — the library-only path works offline and fast
         * enough to not care. Discovery is opportunistic and tolerates
         * being skipped when the device is offline.
         */
        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()
            val work = PeriodicWorkRequestBuilder<StashMixRefreshWorker>(
                repeatInterval = 1,
                repeatIntervalTimeUnit = TimeUnit.DAYS,
            )
                .setConstraints(constraints)
                .build()
            // 2026-05-11: UPDATE (not KEEP) so existing installs reschedule against
            // the current worker spec on the next cold start. KEEP previously meant
            // constraint changes / class changes were ignored across upgrades —
            // a credible cause of "periodic refresh hasn't fired in 3 days" reports.
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                work,
            )
        }

        /**
         * Fire a one-shot refresh immediately — used on first app launch
         * after seeding defaults so users see populated mixes without
         * waiting 24 hours for the periodic schedule, and by the
         * manual-refresh button on the Home Stash Mixes card.
         */
        fun enqueueOneTime(context: Context) {
            val work = OneTimeWorkRequestBuilder<StashMixRefreshWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_SHOT_WORK_NAME,
                androidx.work.ExistingWorkPolicy.REPLACE,
                work,
            )
        }

        /**
         * Fire a one-shot refresh for a single recipe. Used by the Home
         * long-press "Refresh this mix" action. Distinct unique-work name
         * per recipe id so refreshing mix A doesn't clobber a still-pending
         * refresh of mix B; REPLACE means a rapid double-tap on the same
         * mix coalesces into one job.
         */
        fun enqueueOneTime(context: Context, recipeId: Long) {
            val data = androidx.work.workDataOf(KEY_RECIPE_ID to recipeId)
            val work = OneTimeWorkRequestBuilder<StashMixRefreshWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setInputData(data)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "${ONE_SHOT_WORK_NAME}_$recipeId",
                androidx.work.ExistingWorkPolicy.REPLACE,
                work,
            )
        }
    }

    override suspend fun doWork(): Result {
        // Safety net: make sure default recipes exist. Normally seeded at
        // app startup; running here too means a fresh-install user gets
        // their first mixes even if the startup hook is racy with the
        // first WorkManager tick.
        StashMixDefaults.seedIfNeeded(recipeDao)

        val targetId = inputData.getLong(KEY_RECIPE_ID, -1L)
        val active = if (targetId > 0L) {
            val one = recipeDao.getById(targetId)?.takeIf { it.isActive }
            if (one == null) {
                Log.d(TAG, "single-recipe refresh: recipe $targetId not found or inactive")
                return Result.success()
            }
            listOf(one)
        } else {
            recipeDao.getActive()
        }
        if (active.isEmpty()) {
            Log.d(TAG, "no active recipes")
            return Result.success()
        }
        Log.i(
            TAG,
            "refreshing ${active.size} Stash Mix(es)" +
                if (targetId > 0L) " (single: ${active.first().name})" else "",
        )

        val now = System.currentTimeMillis()
        val lastFmConfigured = lastFmCredentials.isConfigured

        val username = sessionPreference.session.first()?.username
        val personas = if (lastFmConfigured && !username.isNullOrBlank()) {
            // v0.9.16: Bound the persona fetch — 5 periods × 2 endpoints =
            // 10 sequential HTTP calls. With a slow connection or upstream
            // hiccup, OkHttp's default timeouts could stack into multiple
            // minutes and blow past WorkManager's 10-min budget. 30s ceiling
            // means we degrade gracefully to library-only seeding when
            // Last.fm is sluggish; the next refresh tries again.
            runCatching {
                withTimeout(30_000L) { fetchPersonas(username) }
            }.getOrElse { e ->
                Log.w(TAG, "persona fetch failed/timed-out, falling back to local seeds", e)
                LastFmPersonas.EMPTY
            }
        } else LastFmPersonas.EMPTY

        for (recipe in active) {
            val tracks = mixGenerator.generate(recipe)
            // Empty-tracks skip is only safe for library-only recipes
            // (discoveryRatio == 0). Pure-discovery recipes like "Stash
            // Discover" (ratio = 1.0) produce an empty generator result
            // by design — the playlist gets its content from the
            // discovery re-link pass inside materializeMix + the async
            // StashDiscoveryWorker. Skipping them here would keep stale
            // pre-retune content in the playlist forever.
            if (tracks.isEmpty() && recipe.discoveryRatio == 0f) {
                Log.d(TAG, "'${recipe.name}' produced 0 tracks and has no discovery — skipping materialize")
                continue
            }

            val playlistId = materializeMix(recipe, tracks, now)
            recipeDao.setPlaylistId(recipe.id, playlistId)
            recipeDao.setLastRefreshedAt(recipe.id, now)

            // Discovery — opportunistic. Don't block refresh success on it.
            if (recipe.discoveryRatio > 0f && lastFmConfigured) {
                runCatching { queueDiscoveryForRecipe(recipe, personas) }
                    .onFailure { Log.w(TAG, "discovery queueing failed for '${recipe.name}'", it) }
            }
        }
        return Result.success()
    }

    /**
     * Find-or-create a playlist row for this recipe, then replace its
     * tracklist with [tracks] in correct order. Returns the playlist_id.
     */
    private suspend fun materializeMix(
        recipe: StashMixRecipeEntity,
        tracks: List<TrackEntity>,
        now: Long,
    ): Long {
        // Existing playlist: verify it's still there (could have been
        // deleted by the user). If gone, fall through to re-create.
        val existing = recipe.playlistId?.let { playlistDao.getById(it) }

        val playlistId = if (existing != null) {
            playlistDao.clearPlaylistTracks(existing.id)
            playlistDao.updateName(existing.id, recipe.name)
            existing.id
        } else {
            val firstArt = tracks.firstNotNullOfOrNull { it.albumArtUrl }
            val newPlaylist = PlaylistEntity(
                name = recipe.name,
                source = MusicSource.BOTH,
                sourceId = "stash_mix_${recipe.id}",
                type = PlaylistType.STASH_MIX,
                trackCount = tracks.size,
                artUrl = firstArt,
                syncEnabled = true,
                isActive = true,
            )
            playlistDao.insert(newPlaylist)
        }

        // Rebuild track membership in generator order.
        val nowInstant = Instant.ofEpochMilli(now)
        tracks.forEachIndexed { position, track ->
            playlistDao.insertCrossRef(
                PlaylistTrackCrossRef(
                    playlistId = playlistId,
                    trackId = track.id,
                    position = position,
                    addedAt = nowInstant,
                )
            )
        }

        // Re-link any Discovery-sourced tracks that this recipe has
        // already accepted on previous refreshes. Without this, every
        // weekly refresh wiped the Discovery slots when the playlist was
        // cleared above, the next orphan-sweep deleted the audio files,
        // and the mix silently degenerated to 100% library tracks — the
        // user's "Stash Discover" ended up being a random unplayed slice
        // of their own imports. See the audit in conversation 2026-04-21.
        //
        // Library tracks already own positions 0..tracks.size-1, so we
        // append discovery survivors after them. Dedup against the set
        // we just inserted in case generator + discovery both surface
        // the same track (possible if a Discovery download completed and
        // then showed up in the user's library in some other way).
        val librarySet = tracks.mapTo(HashSet(tracks.size)) { it.id }
        // v0.9.15: Filter out blocked identities. Without this, a track
        // that was discovered + downloaded + later blocked would re-link
        // into the mix on every refresh because the discovery queue's
        // DONE row is keyed by track id, not identity. `filter` doesn't
        // accept suspend lambdas, so the blocklist check is a manual
        // loop that calls the suspend guard sequentially.
        // v0.9.19 follow-up: cap discovery survivors at the recipe's stated
        // slot count (targetLength * discoveryRatio). DAO query orders by
        // completed_at DESC so newest-DONE survivors win when we have to cut.
        // Library shortfall-fill in MixGenerator is intentionally untouched —
        // total playlist size for Daily Discover settles at up-to-50 (library)
        // + up-to-20 (discovery) = <=70, replacing the previous unbounded growth.
        val discoveryCap = (recipe.targetLength * recipe.discoveryRatio)
            .roundToInt()
            .coerceAtLeast(0)
        val candidateIds = discoveryQueueDao
            .getDoneTrackIdsForRecipe(recipe.id, limit = discoveryCap)
            .filter { it !in librarySet }
        val discoveryTrackIds = buildList {
            for (trackId in candidateIds) {
                if (!blocklistGuard.isBlockedByTrackId(trackId)) add(trackId)
            }
        }
        discoveryTrackIds.forEachIndexed { offset, trackId ->
            playlistDao.insertCrossRef(
                PlaylistTrackCrossRef(
                    playlistId = playlistId,
                    trackId = trackId,
                    position = tracks.size + offset,
                    addedAt = nowInstant,
                )
            )
        }
        val totalCount = tracks.size + discoveryTrackIds.size

        // v0.4.1: single-image cover instead of the 2-tile mosaic used in
        // older builds. Still rotates every refresh — the top track's
        // album art becomes the mix cover.
        val coverUrl = tracks.mapNotNull { it.albumArtUrl }.firstOrNull()
        if (coverUrl != null) {
            playlistDao.updateArtUrl(playlistId, coverUrl)
        }
        playlistDao.updateTrackCount(playlistId, totalCount)
        return playlistId
    }

    /**
     * v0.9.16: Per-recipe candidate generation, dispatched by the
     * recipe's [StashMixRecipeEntity.seedStrategy]. Each strategy
     * inputs different signals from the user's personas + library;
     * [MixSeedGenerator] handles the Last.fm calls + rate limiting and
     * returns the candidate list, which we hand off to
     * [MixGenerator.queueDiscoveryCandidates] for blocklist + dedup
     * filtering before insertion.
     */
    private suspend fun queueDiscoveryForRecipe(
        recipe: StashMixRecipeEntity,
        personas: LastFmPersonas,
    ) {
        val strategy = MixSeedStrategy.fromStored(recipe.seedStrategy)
        if (strategy == MixSeedStrategy.NONE) return

        val since = System.currentTimeMillis() -
            AFFINITY_LOOKBACK_DAYS * 24 * 60 * 60 * 1000

        // Seed-artist fallback chain. Persona slice (1-month) > local
        // listening events > library-top-artists. The library fallback
        // matters for fresh installs that have synced but not yet played
        // — without it, Stash Discover would be empty until the user
        // racked up a few scrobbles.
        val seedArtists = personas.topArtistsByPeriod[LastFmPeriod.ONE_MONTH]
            ?.takeIf { it.isNotEmpty() }
            ?.take(TOP_ARTISTS_LIMIT)?.map { it.name }
            ?: listeningEventDao.getTopArtistsSince(since, TOP_ARTISTS_LIMIT)
                .map { it.artist }
                .ifEmpty { trackDao.getTopArtistsByTrackCount(TOP_ARTISTS_LIMIT) }

        val seedTracks = personas.topTracksByPeriod[LastFmPeriod.ONE_MONTH]
            ?.take(20)?.map { it.artist to it.title }
            ?: emptyList()

        val topTags = mixGenerator.computeUserTopTags(limit = 10)

        val candidates = seedGenerator.generate(
            strategy = strategy,
            seedArtists = seedArtists,
            topTags = topTags,
            seedTracks = seedTracks,
            personas = personas,
        )
        if (candidates.isEmpty()) return
        Log.i(TAG, "'${recipe.name}': ${candidates.size} candidates via $strategy")
        mixGenerator.queueDiscoveryCandidates(recipe, candidates)
    }

    /**
     * v0.9.16: Snapshot the user's period-sliced top tracks/artists once
     * per refresh run so each recipe can pull whichever slice it needs
     * (Daily Discover → 1month, Throwback → overall − 3month, etc.)
     * without redundant network calls. Sequential with a small inter-call
     * delay to stay polite under Last.fm's rate ceiling. Wrapped by
     * [withTimeout] at the call site — failures here surface as the
     * cancelled-coroutine path which the caller turns into [LastFmPersonas.EMPTY].
     */
    private suspend fun fetchPersonas(username: String): LastFmPersonas {
        val periods = listOf(
            LastFmPeriod.SEVEN_DAY,
            LastFmPeriod.ONE_MONTH,
            LastFmPeriod.THREE_MONTH,
            LastFmPeriod.SIX_MONTH,
            LastFmPeriod.OVERALL,
        )
        val tracks = mutableMapOf<LastFmPeriod, List<LastFmTopTrack>>()
        val artists = mutableMapOf<LastFmPeriod, List<LastFmTopArtist>>()
        for (period in periods) {
            tracks[period] = lastFmApiClient.getUserTopTracks(username, period, limit = 100)
                .getOrNull().orEmpty()
            artists[period] = lastFmApiClient.getUserTopArtists(username, period, limit = 50)
                .getOrNull().orEmpty()
            delay(SIMILAR_REQUEST_INTERVAL_MS)
        }
        return LastFmPersonas(tracks, artists)
    }
}
