// SlidingWindowMax.kt
package com.stash.core.media.equalizer.dsp

/**
 * Maximum over the last [windowSize] pushed values, O(1) amortized per push.
 *
 * Monotonic deque ("max wedge"): values are kept strictly decreasing from
 * head to tail, each tagged with its push position. A new value evicts every
 * tail entry it dominates (those can never be the window max again), and the
 * head expires once it slides out of the window.
 *
 * Replaces the full ring rescan in [SoftClipLimiterProcessor], which was
 * O(windowSize) per sample — at 24/192 stereo that was ~295M scan iterations
 * per second, enough to saturate the ExoPlayer playback thread and cause
 * audible underruns (the hi-res crackle bug).
 *
 * Intended for non-negative values (|PCM| magnitudes); [max] returns 0 when
 * nothing has been pushed. Not thread-safe.
 */
class SlidingWindowMax(windowSize: Int) {

  private val window = windowSize.toLong()

  // A monotonic deque never holds more than windowSize entries; +1 lets
  // head == tail unambiguously mean "empty" in the circular arrays.
  private val capacity = windowSize + 1
  private val positions = LongArray(capacity)
  private val values = IntArray(capacity)
  private var head = 0
  private var tail = 0
  private var nextPosition = 0L

  fun push(value: Int) {
    val position = nextPosition++
    while (head != tail) {
      val last = (tail + capacity - 1) % capacity
      if (values[last] <= value) tail = last else break
    }
    positions[tail] = position
    values[tail] = value
    tail = (tail + 1) % capacity
    while (positions[head] <= position - window) head = (head + 1) % capacity
  }

  fun max(): Int = if (head == tail) 0 else values[head]

  fun reset() {
    head = 0
    tail = 0
    nextPosition = 0L
  }
}
