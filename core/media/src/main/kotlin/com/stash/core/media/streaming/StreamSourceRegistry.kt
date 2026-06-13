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
 *   4. [YouTubeStreamResolver] — yt-dlp / InnerTube extraction. Last
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
 * Test toggles (both off for normal use):
 *  - [StreamingPreference.isForceYouTubeFallback]: [resolve] skips Kennyy
 *    and Squid entirely and routes every track through the YouTube resolver
 *    only — reproduces the lossless-down fallback path on demand.
 *  - [StreamingPreference.isForceAntraOnly]: routes through antra ONLY
 *    (no kennyy/squid/YouTube) — the outage drill proving the antra
 *    fallback can serve by itself. Takes precedence over forceYt.
 */
@Singleton
class StreamSourceRegistry @Inject constructor(
    private val kennyy: KennyyStreamResolver,
    private val qobuz: QobuzStreamResolver,
    private val antra: AntraStreamResolver,
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
     * @param allowAntra pass `false` to keep antra out of the chain. An
     *   antra resolve is EXPENSIVE: it spends one single from a finite
     *   per-account quota and occupies antra's exclusive job slot for
     *   60-120s. setQueue's queue-wide background fill passes `false` —
     *   without that, one playlist tap during a kennyy outage drains the
     *   quota at ~1 single/90s (observed on-device 2026-06-09). The
     *   tapped track and the single NEXT-UP prefetch keep the default
     *   `true`: both are spent the moment they actually play, and the
     *   next-up prefetch is what keeps auto-advance seamless across
     *   antra's 60-120s job latency.
     */
    suspend fun resolve(
        track: TrackEntity,
        allowYouTube: Boolean = true,
        allowYtDlp: Boolean = true,
        allowAntra: Boolean = true,
    ): StreamUrl? {
        val resolvers = buildList<Pair<String, suspend (TrackEntity) -> StreamUrl?>> {
            if (streamingPreference.isForceAntraOnly()) {
                // Test toggle (outage drill): antra ONLY — kennyy, squid and
                // the YouTube fallback are all removed from play so a track
                // either streams via antra or fails visibly. Takes precedence
                // over forceYt (which is not even consulted). Still gated by
                // allowAntra so speculative callers resolve nothing under the
                // drill instead of draining the quota (matching how the
                // forceYt branch keeps the background fill empty).
                if (allowAntra) add("antra" to antra::resolve)
            } else if (streamingPreference.isForceYouTubeFallback()) {
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
                if (allowAntra) add("antra" to antra::resolve)
                if (allowYouTube) add("youtube" to { t: TrackEntity -> youtube.resolve(t, allowYtDlp) })
            }
        }
        for ((name, fn) in resolvers) {
            val result = runCatching { fn(track) }
                .onFailure { e ->
                    // Resolvers should never throw — they catch and return
                    // null. Defensive log so an unexpected throw from one
                    // source doesn't break the chain.
                    Log.w(TAG, "$name threw on resolve for ${track.id} '${track.title}'", e)
                }
                .getOrNull()
            if (result != null) {
                if (name != "kennyy") {
                    // Diagnostic: anything other than the primary source is
                    // a fallback path worth noticing. Helps explain "this
                    // track played but at lower quality" reports.
                    Log.i(TAG, "$name served ${track.id} '${track.title}' (kennyy missed)")
                }
                return result
            }
        }
        return null
    }

    private companion object {
        private const val TAG = "StreamSourceRegistry"
    }
}
