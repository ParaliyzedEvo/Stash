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
    /** #468: with shuffle on, row i of [queue] is timeline slot `timelineIndices[i]`; null otherwise. */
    val timelineIndices: List<Int>? = null,
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
    shuffledTimelineIndices: List<Int>? = null,
  ): DisplayQueue {
    // #468: with shuffle on, playback follows Media3's shuffle walk, so the sheet must
    // show THAT order - the logical (unshuffled) list made "Up next" lie. The walk
    // is kept so index-based actions can map a row back to its timeline slot.
    if (shuffledTimelineIndices != null && timelineQueue.isNotEmpty()) {
      val queue = shuffledTimelineIndices.mapNotNull { timelineQueue.getOrNull(it) }
      val current = shuffledTimelineIndices.indexOf(timelineIndex).coerceAtLeast(0)
      return DisplayQueue(queue, current, isLogical = false, timelineIndices = shuffledTimelineIndices)
    }
    if (currentTrackId != null && currentTrackId > 0L) {
      // The PLAYING copy, not the first one: with a song queued twice ([A, B, C, A], the
      // second A playing) the first match highlighted row 0 and listed B and C, already
      // played, as "Up next". The timeline holds a subsequence of the logical queue in the
      // same order, so the k-th copy in one is the k-th copy in the other.
      val copy = copyOrdinal(timelineQueue, timelineIndex)
      val idx = indexOfCopy(logicalQueue, currentTrackId, copy)
        .takeIf { it >= 0 } ?: logicalQueue.indexOfFirst { it.id == currentTrackId }
      if (idx >= 0) {
        val timelineById = timelineQueue.associateBy { it.id }
        val overlaid = logicalQueue.map { timelineById[it.id] ?: it }
        return DisplayQueue(overlaid, idx, isLogical = true)
      }
    }
    return DisplayQueue(timelineQueue, timelineIndex, isLogical = false)
  }

  /**
   * How many earlier rows of [queue] hold the same song as row [index] (0 for its first
   * copy). With the same song queued twice, a row is found again by (id, copy), never by id.
   */
  fun copyOrdinal(queue: List<Track>, index: Int): Int {
    val id = queue.getOrNull(index)?.id ?: return 0
    var copies = 0
    for (i in 0 until index) if (queue[i].id == id) copies++
    return copies
  }

  /** Index of the [copy]-th (0-based) row of [queue] holding song [id], or -1 when there are fewer copies. */
  fun indexOfCopy(queue: List<Track>, id: Long, copy: Int): Int {
    var seen = 0
    for (i in queue.indices) {
      if (queue[i].id != id) continue
      if (seen == copy) return i
      seen++
    }
    return -1
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
   * that is logically before the new position". Counted per copy: with a
   * song queued twice, only as many of its timeline copies as sit before the
   * new position in the logical queue count (an id set counted them all).
   */
  /**
   * Timeline window indices in shuffle play order: from [first], following [next]
   * until it answers an index outside 0 until count or one already visited. The
   * visited guard matters: a mocked or degenerate timeline that keeps answering
   * the same index must not spin forever.
   */
  fun walkShuffleOrder(count: Int, first: Int, next: (Int) -> Int): List<Int> {
    if (count <= 0 || first !in 0 until count) return emptyList()
    val out = ArrayList<Int>(count)
    val seen = HashSet<Int>(count)
    var i = first
    while (i in 0 until count && out.size < count && seen.add(i)) {
      out.add(i)
      i = next(i)
    }
    return out
  }

  /**
   * The shuffle walk (timeline indexes in play order) after [count] items were inserted at
   * timeline index [at]: the old [walk]'s indexes shifted past the insert, with the new ones, in
   * order, right after [currentIndex] (pre-insert) when [afterCurrent] ("Play next"), else at
   * the end ("Add to queue"). Null when [walk] is the identity order: right after any queue
   * change the MediaController's masking timeline answers an UNSHUFFLED order until the
   * session's real one arrives, and building on that would unshuffle the queue.
   */
fun walkWithInserted(walk: List<Int>, at: Int, count: Int, currentIndex: Int, afterCurrent: Boolean, ahead: Int = 0): List<Int>? {
    if (walk.withIndex().all { (i, v) -> i == v }) return null
    val shifted = walk.map { if (it >= at) it + count else it }
    val inserted = (at until at + count).toList()
    val cur = if (afterCurrent) walk.indexOf(currentIndex) else -1
    if (cur < 0) return shifted + inserted
    val pos = (cur + ahead).coerceAtMost(shifted.size - 1)
    return shifted.subList(0, pos + 1) + inserted + shifted.subList(pos + 1, shifted.size)
}

  /**
   * The shuffle walk after [count] items were inserted at timeline index [at], with the new items
   * at [position] of the play order (0 = the walk's first): the queue sheet's Undo puts a song back
   * on the row it was swiped from. [walk] is from before the insert; [position] is clamped to it.
   * Null for an identity [walk], for the reason [walkWithInserted] gives.
   */
  fun walkWithInsertedAt(walk: List<Int>, at: Int, count: Int, position: Int): List<Int>? {
    if (walk.withIndex().all { (i, v) -> i == v }) return null
    val shifted = walk.map { if (it >= at) it + count else it }
    val pos = position.coerceIn(0, shifted.size)
    return shifted.subList(0, pos) + (at until at + count).toList() + shifted.subList(pos, shifted.size)
  }

  fun moveTimelineTarget(
    newLogical: List<Track>,
    toIndex: Int,
    timelineIdsWithoutMoved: List<Long>,
  ): Int {
    val copiesBefore = HashMap<Long, Int>()
    for (i in 0 until toIndex.coerceAtMost(newLogical.size)) {
      copiesBefore[newLogical[i].id] = (copiesBefore[newLogical[i].id] ?: 0) + 1
    }
    return timelineIdsWithoutMoved.count { id ->
      val left = copiesBefore[id] ?: 0
      if (left > 0) copiesBefore[id] = left - 1
      left > 0
    }
  }
}
