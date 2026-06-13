package com.stash.core.media

import com.stash.core.model.Track

/**
 * Pure logic for deriving the queue the UI displays.
 *
 * The ExoPlayer timeline is NOT the user's queue: the background fill
 * resolves stream tracks through the fast lane only (no yt-dlp, no antra),
 * silently dropping whatever it can't resolve — during antra streaming
 * that's most of the mix, leaving a timeline of downloaded tracks while
 * playback follows the *logical* queue via just-in-time prefetch insertion.
 * Displaying the timeline made "Up Next" show downloaded tracks that were
 * never going to play next.
 *
 * [compute] therefore prefers the logical queue (the Track list handed to
 * `setQueue`) whenever the currently-playing item belongs to it, falling
 * back to the timeline for queues built outside `setQueue` (single-track
 * play, restored sessions, anything that skipped the bookkeeping).
 */
internal object QueueDisplay {

  /** What the UI should show: the queue, the current track's index in it,
   * and whether the logical queue (vs. raw timeline) was used. */
  data class DisplayQueue(
    val queue: List<Track>,
    val currentIndex: Int,
    val isLogical: Boolean,
  )

  /**
   * Pick the display queue. Logical wins when [currentTrackId] is a real id
   * that appears in [logicalQueue]; logical rows are overlaid with their
   * timeline counterparts where present, because timeline rows carry
   * stream-resolved metadata (FLAC badge bits, codec) that the DB rows of
   * streaming-only tracks don't have.
   */
  fun compute(
    timelineQueue: List<Track>,
    timelineIndex: Int,
    logicalQueue: List<Track>,
    currentTrackId: Long?,
  ): DisplayQueue {
    if (currentTrackId != null && currentTrackId > 0L) {
      val idx = logicalQueue.indexOfFirst { it.id == currentTrackId }
      if (idx >= 0) {
        val timelineById = timelineQueue.associateBy { it.id }
        val overlaid = logicalQueue.map { timelineById[it.id] ?: it }
        return DisplayQueue(overlaid, idx, isLogical = true)
      }
    }
    return DisplayQueue(timelineQueue, timelineIndex, isLogical = false)
  }

  /**
   * Timeline insertion index for a queue-sheet drag committed against the
   * logical queue. [newLogical] is the logical queue AFTER the move,
   * [toIndex] the moved track's new logical position, and
   * [timelineIdsWithoutMoved] the timeline's track ids in timeline order
   * with the moved item excluded (i.e. the list the insert happens into —
   * matching Media3's moveMediaItem remove-then-insert semantics).
   *
   * The timeline holds a subsequence of the logical queue in the same
   * relative order, so the right slot is simply "after every timeline item
   * that is logically before the new position".
   */
  fun moveTimelineTarget(
    newLogical: List<Track>,
    toIndex: Int,
    timelineIdsWithoutMoved: List<Long>,
  ): Int {
    val idsBefore = HashSet<Long>(toIndex.coerceAtLeast(0)).apply {
      for (i in 0 until toIndex.coerceAtMost(newLogical.size)) add(newLogical[i].id)
    }
    return timelineIdsWithoutMoved.count { it in idsBefore }
  }
}
