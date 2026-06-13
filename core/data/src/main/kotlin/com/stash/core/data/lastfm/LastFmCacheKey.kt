package com.stash.core.data.lastfm

/**
 * Builds the cache key for a Last.fm read request. Deliberately excludes
 * `api_key` (and `format`) so the cached entry is shared across key rotation
 * and multi-key pooling — the response for "top techno tracks" is identical no
 * matter which key fetched it. Order-independent (sorted) so equal param sets
 * always collide.
 */
internal fun lastFmCacheKey(params: Map<String, String>): String =
    params.asSequence()
        .filter { it.key != "api_key" && it.key != "format" }
        .sortedBy { it.key }
        .joinToString("&") { "${it.key}=${it.value}" }
