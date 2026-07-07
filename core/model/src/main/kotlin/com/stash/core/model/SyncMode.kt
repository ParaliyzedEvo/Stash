package com.stash.core.model

/**
 * Controls how algorithmic playlists (Daily Mixes, Discover Weekly, etc.)
 * are updated during sync.
 *
 * - [REFRESH] — each sync replaces the mix's tracks with the current set;
 *   tracks that rotate out are removed from the mix **and their downloads
 *   are deleted** to keep the library lean. Cleanup only runs while ALL
 *   sources are Refresh — see the refresh/accumulate design.
 *
 * - [ACCUMULATE] — only ever ADD tracks, never remove. While any source is
 *   Accumulate the library is append-only (no track or file is auto-deleted).
 *   Mixes grow into a discovery archive. This is the default.
 */
enum class SyncMode {
    REFRESH,
    ACCUMULATE,
}
