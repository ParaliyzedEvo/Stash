package com.stash.core.media.streaming

import com.stash.core.model.Track

/**
 * The subset of [tracks] that can actually be enqueued right now.
 *
 * Streaming mode → every track (stream-only ones resolve via the Kennyy/Squid
 * chain inside the player). Offline mode → downloaded-only, so we never enqueue
 * items the player's offline master-gate would silently skip.
 *
 * Pure (no I/O) so it is the single, exhaustively-testable source of truth for
 * the rule shared by PlaylistDetailViewModel and HomeViewModel.
 */
fun queuePlayableTracks(tracks: List<Track>, streamingEnabled: Boolean): List<Track> =
    if (streamingEnabled) tracks else tracks.filter { it.filePath != null }
