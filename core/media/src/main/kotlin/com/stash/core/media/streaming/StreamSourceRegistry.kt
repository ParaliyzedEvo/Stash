package com.stash.core.media.streaming

import android.util.Log
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Walks Stash's streaming-source roster in priority order and returns
 * the first match. Each resolver internally handles its own enablement
 * (captcha cookies, circuit-breaker state for non-streaming paths, etc.)
 * — null from one resolver just means "try the next one".
 *
 * Current order:
 *   1. [KennyyStreamResolver]  — `kennyy.com.br`, Qobuz lossless. No
 *      captcha gate; almost always usable. Primary source.
 *   2. [QobuzStreamResolver]   — `qobuz.squid.wtf`. Same Qobuz catalog,
 *      requires a user-pasted `captcha_verified_at` cookie. Auto-skipped
 *      when no cookie is set or the current cookie has been marked stale.
 *   3. [AntraStreamResolver]   — `antra.hoshi.cfd`, independent per-user
 *      lossless (own multi-source backend). Self-gates: returns null when
 *      not connected / out of quota, so it only engages when both Qobuz
 *      proxies miss. Plays a locally-cached FLAC (no signed CDN URL).
 *   4. [LucidaStreamResolver]   — `lucida.to`, Qobuz-sourced FLAC via
 *      intermediary. Self-gates via `LosslessSourceHealthGate`; returns
 *      null when the content is degraded.
 *   5. [SaavnStreamResolver]    — JioSaavn API, AAC 320 kbps. Covers
 *      Indian music that isn't in the Qobuz catalog and may not be on
 *      YouTube Music. Fast (~1-2 s), better quality than YT. Before
 *      YouTube because it's faster and higher quality for the tracks it
 *      covers.
 *   6. [YouTubeStreamResolver] — yt-dlp / InnerTube extraction. Last
 *      resort, reached only when the track genuinely isn't in the Qobuz
 *      catalog (Bandcamp re-uploads, region-exclusive, underground
 *      releases). Lossy quality (AAC/Opus ~128-160 kbps), surfaced as a
 *      "via YT" badge in Now Playing so the user knows.
 *
 * Exposes the same `resolve(track) -> StreamUrl?` shape as the individual
 * resolvers so callers ([PlayerRepositoryImpl], [StreamingMediaSourceFactory],
 * [RefreshingDataSourceFactory], [PrefetchOrchestrator]) can swap in by
 * type without changing call-site logic.
 *
 * Result caching is the caller's responsibility — [StreamUrlCache] sits
 * on the player side and stores the first source's success keyed by track
 * id. Subsequent plays of the same track hit the cache and bypass the
 * registry entirely until the URL's `etsp` expires.
 *
 * Test toggle (off for normal use):
 *  - [StreamingPreference.isForceYouTubeFallback]: [resolve] skips Kennyy
 *    and Squid entirely and routes every track through the YouTube resolver
 *    only — reproduces the lossless-down fallback path on demand.
 */
@Singleton
class StreamSourceRegistry @Inject constructor(
    private val kennyy: KennyyStreamResolver,
    private val qobuz: QobuzStreamResolver,
    private val lucida: LucidaStreamResolver,
    private val saavn: SaavnStreamResolver,
    private val youtube: YouTubeStreamResolver,
    private val streamingPreference: StreamingPreference,
) {

    /**
     * Try each resolver in priority order; return the first non-null
     * [StreamUrl]. Returns null when no source produced a match — caller
     * should surface this as [StreamRoutingResult.NotAvailable].
     *
     * @param allowYouTube pass `false` to skip the YouTube fallback
     *   resolver, leaving only the two Qobuz operators. Used by
     *   [PlayerRepositoryImpl.setQueue]'s background-fill path so
     *   yt-dlp's limited 2-slot extraction semaphore stays available
     *   for the foreground user-tap critical path. Foreground (tapped
     *   track) calls leave this true.
     * @param allowYtDlp pass `false` to make the YouTube fallback resolve
     *   via the fast InnerTube engine only (no slow yt-dlp). Used by the
     *   background-fill path so a 15-35s yt-dlp invocation never sits on
     *   the queue's critical path. Foreground calls leave this true.
     */
    suspend fun resolve(
        track: TrackEntity,
        allowYouTube: Boolean = true,
        allowYtDlp: Boolean = true,
    ): StreamUrl? {
        val resolvers = buildList<Pair<String, suspend (TrackEntity) -> StreamUrl?>> {
            if (streamingPreference.isForceYouTubeFallback()) {
                // Test toggle: skip the lossless sources, forcing the
                // YouTube fallback path. Still gated by allowYouTube so the
                // background-fill keeps resolving nothing (matching a genuine
                // both-sources-down outage).
                if (allowYouTube) add("youtube" to { t: TrackEntity -> youtube.resolve(t, allowYtDlp) })
            } else {
                add("kennyy" to kennyy::resolve)
                add("squid" to qobuz::resolve)
                // antra self-gates (null when not connected / out of quota),
                // so it only serves when both Qobuz proxies miss. Kept out of
                // the forceYt branch above on purpose: that toggle exists to
                // force the YouTube path by skipping ALL lossless sources.
                add("lucida" to lucida::resolve)
                add("saavn" to saavn::resolve)
                if (allowYouTube) add("youtube" to { t: TrackEntity -> youtube.resolve(t, allowYtDlp) })
            }
        }
        for ((name, fn) in resolvers) {
            Log.d("LATDIAG", "registry: trying '$name' for id=${track.id} '${track.title}'")
            val t0 = System.currentTimeMillis()
            val result = runCatching { fn(track) }
                .onFailure { e ->
                    // Resolvers should never throw — they catch and return
                    // null. Defensive log so an unexpected throw from one
                    // source doesn't break the chain.
                    Log.w(TAG, "$name threw on resolve for ${track.id} '${track.title}'", e)
                    Log.w("LATDIAG", "registry: '$name' THREW dt=${System.currentTimeMillis() - t0}ms: ${e.message}")
                }
                .getOrNull()
            val dt = System.currentTimeMillis() - t0
            if (result != null) {
                Log.d("LATDIAG", "registry: '$name' HIT dt=${dt}ms id=${track.id} origin=${result.origin}")
                if (name != "kennyy") {
                    // Diagnostic: anything other than the primary source is
                    // a fallback path worth noticing. Helps explain "this
                    // track played but at lower quality" reports.
                    Log.i(TAG, "$name served ${track.id} '${track.title}' (kennyy missed)")
                }
                return result
            }
            Log.d("LATDIAG", "registry: '$name' MISS dt=${dt}ms id=${track.id}")
        }
        Log.w("LATDIAG", "registry: ALL resolvers failed for id=${track.id} '${track.title}'")
        return null
    }

    private companion object {
        private const val TAG = "StreamSourceRegistry"
    }
}
