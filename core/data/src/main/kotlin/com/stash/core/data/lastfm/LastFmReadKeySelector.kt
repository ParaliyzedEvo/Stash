package com.stash.core.data.lastfm

/**
 * Picks which API key to use for an unsigned READ request, spreading load
 * round-robin across the pool while skipping any key currently throttled
 * (its breaker is open). Returns null when the pool is empty or every key is
 * throttled — the caller then fails fast without touching the network.
 *
 * Pure function so the rotation logic is unit-testable; the caller supplies a
 * monotonically-increasing [startIndex] (a request counter) and the
 * per-key open check.
 */
internal fun selectReadKey(
    keys: List<String>,
    startIndex: Int,
    isOpen: (String) -> Boolean,
): String? {
    if (keys.isEmpty()) return null
    for (offset in keys.indices) {
        // Double-mod guards against a negative startIndex (counter overflow).
        val idx = (((startIndex + offset) % keys.size) + keys.size) % keys.size
        val key = keys[idx]
        if (!isOpen(key)) return key
    }
    return null
}
