package com.stash.data.download.lossless.kennyy

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Session-scoped LRU cache mapping normalised `"artist title"` keys to
 * Qobuz track IDs **and** their cover art URLs. The Qobuz track ID is
 * stable and never changes — only the signed CDN URL expires (≈ 1 h).
 * By caching the track ID, replayed or re-resolved tracks skip the
 * expensive `get-music` search call (≈ 1 000 ms) and go straight to
 * `download-music` (≈ 500 ms), cutting per-track latency roughly in half.
 *
 * The cover art URL is cached alongside the track ID because the
 * `download-music` endpoint doesn't return album art metadata — only
 * the `get-music` (search) response carries `album.image`. Without
 * caching it, fast-path resolves produce a `SourceResult` with
 * `coverArtUrl = null`, and the downstream art-upgrade path in
 * `PlayerRepositoryImpl.buildMediaItemForTrack` can't fill the DB
 * column for tracks whose art was still blank.
 *
 * **Thread safety:** all access goes through `synchronized(lock)` over
 * a single access-ordered [LinkedHashMap]. Media3 reads on the player
 * thread while ViewModel code writes on the IO dispatcher — the lock
 * serialises both.
 *
 * **Lifetime:** `@Singleton` → lives for the process. A fresh install
 * starts empty; the cache warms naturally as the user plays tracks.
 * A future enhancement could persist to Room so the cache survives
 * process death, but in practice most users play the same tracks
 * within a single session often enough that an in-memory cache is
 * highly effective.
 *
 * **Capacity:** capped at [MAX_ENTRIES] with LRU eviction. Each entry
 * is a String key (≈ 80 bytes) + [CachedEntry] (8 + ~120 bytes), so
 * 2048 entries is well under 1 MB — negligible on modern devices.
 */
@Singleton
class KennyyTrackIdCache @Inject constructor() {

    /** Cached metadata for a Qobuz search result. */
    data class CachedEntry(
        val qobuzTrackId: Long,
        val coverArtUrl: String? = null,
    )

    private val lock = Any()

    private val cache = object : LinkedHashMap<String, CachedEntry>(
        /* initialCapacity = */ 64,
        /* loadFactor = */ 0.75f,
        /* accessOrder = */ true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedEntry>): Boolean =
            size > MAX_ENTRIES
    }

    /**
     * Look up a cached Qobuz track ID for the given artist + title.
     * Returns null on cache miss.
     */
    fun get(artist: String, title: String): Long? = synchronized(lock) {
        cache[makeKey(artist, title)]?.qobuzTrackId
    }

    /**
     * Look up the full cached entry (track ID + art URL) for the given
     * artist + title. Returns null on cache miss.
     */
    fun getEntry(artist: String, title: String): CachedEntry? = synchronized(lock) {
        cache[makeKey(artist, title)]
    }

    /**
     * Store a Qobuz track ID and optional cover art URL for the given
     * artist + title.
     */
    fun put(artist: String, title: String, qobuzTrackId: Long, coverArtUrl: String? = null) {
        synchronized(lock) {
            cache[makeKey(artist, title)] = CachedEntry(qobuzTrackId, coverArtUrl)
        }
    }

    /**
     * Normalised cache key: lowercase, trimmed, single-spaced.
     * Matches the same normalisation [KennyySource] uses for search
     * scoring, so a track found via any search-term variant maps to
     * the same cache key on replay.
     */
    private fun makeKey(artist: String, title: String): String =
        "${normalise(artist)}\u0000${normalise(title)}"

    private fun normalise(s: String): String =
        s.trim().lowercase().replace(MULTI_SPACE, " ")

    private companion object {
        /** LRU ceiling — see class KDoc for memory estimate. */
        const val MAX_ENTRIES = 2048

        val MULTI_SPACE = Regex("\\s+")
    }
}
