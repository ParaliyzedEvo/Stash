package com.stash.core.media

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.Track
import org.junit.Test

/**
 * Tests for [QueueDisplay] — the pure logic that decides what queue the UI
 * displays.
 *
 * Background (the bug this pins): `PlayerState.queue` used to be built from
 * the ExoPlayer timeline, but the background fill silently drops stream
 * tracks it can't resolve in the fast lane (antra is disallowed there
 * entirely), so during antra streaming the timeline holds only downloaded
 * tracks — and "Up Next" showed downloaded tracks while playback actually
 * followed the logical queue via just-in-time prefetch insertion.
 */
class QueueDisplayTest {

  private fun track(id: Long, fileFormat: String = "opus") =
    Track(id = id, title = "T$id", artist = "A$id", fileFormat = fileFormat)

  private val t1 = track(1)
  private val t2 = track(2)
  private val t3 = track(3)
  private val t4 = track(4)
  private val t5 = track(5)

  // ---- compute ----

  @Test
  fun `current track in logical queue - returns logical queue with id-matched index`() {
    // Timeline only holds the resolved subset [t1, t3]; logical has all 4.
    val result = QueueDisplay.compute(
      timelineQueue = listOf(t1, t3),
      timelineIndex = 0,
      logicalQueue = listOf(t1, t2, t3, t4),
      currentTrackId = 1L,
    )
    assertThat(result.queue.map { it.id }).containsExactly(1L, 2L, 3L, 4L).inOrder()
    assertThat(result.currentIndex).isEqualTo(0)
    assertThat(result.isLogical).isTrue()
  }

  @Test
  fun `current index is id-matched, not timeline index`() {
    // Playing t3 = timeline index 1, but logical position 2.
    val result = QueueDisplay.compute(
      timelineQueue = listOf(t1, t3),
      timelineIndex = 1,
      logicalQueue = listOf(t1, t2, t3, t4),
      currentTrackId = 3L,
    )
    assertThat(result.currentIndex).isEqualTo(2)
  }

  @Test
  fun `logical rows are overlaid with resolved timeline metadata`() {
    // The timeline's t3 carries stream-resolved format info (flac); the
    // logical (DB) row still says opus. Display should prefer the
    // resolved row so FLAC badges survive the logical-queue switch.
    val resolvedT3 = track(3, fileFormat = "flac")
    val result = QueueDisplay.compute(
      timelineQueue = listOf(t1, resolvedT3),
      timelineIndex = 0,
      logicalQueue = listOf(t1, t2, t3, t4),
      currentTrackId = 1L,
    )
    assertThat(result.queue[2].fileFormat).isEqualTo("flac")
    assertThat(result.queue[1].fileFormat).isEqualTo("opus") // unresolved stays DB row
  }

  @Test
  fun `current track not in logical queue - falls back to timeline`() {
    val result = QueueDisplay.compute(
      timelineQueue = listOf(t1, t3),
      timelineIndex = 1,
      logicalQueue = listOf(t2, t4),
      currentTrackId = 3L,
    )
    assertThat(result.queue.map { it.id }).containsExactly(1L, 3L).inOrder()
    assertThat(result.currentIndex).isEqualTo(1)
    assertThat(result.isLogical).isFalse()
  }

  @Test
  fun `empty logical queue - falls back to timeline`() {
    val result = QueueDisplay.compute(
      timelineQueue = listOf(t1, t2),
      timelineIndex = 0,
      logicalQueue = emptyList(),
      currentTrackId = 1L,
    )
    assertThat(result.isLogical).isFalse()
  }

  @Test
  fun `null or non-positive current id - falls back to timeline`() {
    val logical = listOf(t1, t2)
    for (id in listOf<Long?>(null, 0L, -1L)) {
      val result = QueueDisplay.compute(
        timelineQueue = listOf(t1),
        timelineIndex = 0,
        logicalQueue = logical,
        currentTrackId = id,
      )
      assertThat(result.isLogical).isFalse()
    }
  }

  // ---- moveTimelineTarget ----
  // Scenario for all cases: logical [1,2,3,4,5]; timeline holds the
  // resolved subset [1,3,5] (2 and 4 were dropped by the fast-lane fill).

  @Test
  fun `move resolved track toward front - lands after resolved predecessors`() {
    // Move t5 to logical position 1 → newLogical [1,5,2,3,4].
    // Timeline without the moved item: [1,3]. Only t1 is logically before
    // position 1 → insert at timeline index 1 → timeline becomes [1,5,3].
    val target = QueueDisplay.moveTimelineTarget(
      newLogical = listOf(t1, t5, t2, t3, t4),
      toIndex = 1,
      timelineIdsWithoutMoved = listOf(1L, 3L),
    )
    assertThat(target).isEqualTo(1)
  }

  @Test
  fun `move resolved track toward back`() {
    // Move t1 to logical position 3 → newLogical [2,3,4,1,5].
    // Timeline without moved: [3,5]. Logically before position 3: {2,3,4}
    // → only t3 of those is in the timeline → insert at 1 → [3,1,5].
    val target = QueueDisplay.moveTimelineTarget(
      newLogical = listOf(t2, t3, t4, t1, t5),
      toIndex = 3,
      timelineIdsWithoutMoved = listOf(3L, 5L),
    )
    assertThat(target).isEqualTo(1)
  }

  @Test
  fun `move to logical front - timeline index zero`() {
    val target = QueueDisplay.moveTimelineTarget(
      newLogical = listOf(t5, t1, t2, t3, t4),
      toIndex = 0,
      timelineIdsWithoutMoved = listOf(1L, 3L),
    )
    assertThat(target).isEqualTo(0)
  }

  @Test
  fun `move to logical end - timeline end`() {
    // Move t3 to the end → newLogical [1,2,4,5,3].
    // Timeline without moved: [1,5]; both are logically before position 4
    // → insert at 2 (timeline end) → [1,5,3].
    val target = QueueDisplay.moveTimelineTarget(
      newLogical = listOf(t1, t2, t4, t5, t3),
      toIndex = 4,
      timelineIdsWithoutMoved = listOf(1L, 5L),
    )
    assertThat(target).isEqualTo(2)
  }
}
