// SlidingWindowMaxTest.kt
package com.stash.core.media.equalizer.dsp

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

class SlidingWindowMaxTest {

  /** Naive reference: max over the last `window` pushed values (zero-padded history). */
  private class NaiveWindowMax(private val window: Int) {
    private val ring = IntArray(window)
    private var write = 0
    fun push(v: Int) { ring[write] = v; write = (write + 1) % window }
    fun max(): Int = ring.max()
  }

  @Test fun matchesNaiveReference_randomSequence() {
    val window = 768 // 192kHz stereo, 2ms lookahead
    val fast = SlidingWindowMax(window)
    val naive = NaiveWindowMax(window)
    val rng = Random(42)
    repeat(50_000) {
      val v = rng.nextInt(0, 32_768)
      fast.push(v)
      naive.push(v)
      assertThat(fast.max()).isEqualTo(naive.max())
    }
  }

  @Test fun matchesNaiveReference_windowOfOne() {
    val fast = SlidingWindowMax(1)
    val naive = NaiveWindowMax(1)
    val rng = Random(7)
    repeat(1_000) {
      val v = rng.nextInt(0, 32_768)
      fast.push(v)
      naive.push(v)
      assertThat(fast.max()).isEqualTo(naive.max())
    }
  }

  @Test fun matchesNaiveReference_monotonicallyDecreasing() {
    // Worst case for cached-max strategies: every expiring sample was the max.
    val window = 88
    val fast = SlidingWindowMax(window)
    val naive = NaiveWindowMax(window)
    for (v in 32_767 downTo 0) {
      fast.push(v)
      naive.push(v)
      assertThat(fast.max()).isEqualTo(naive.max())
    }
  }

  @Test fun matchesNaiveReference_constantValues() {
    val window = 88
    val fast = SlidingWindowMax(window)
    val naive = NaiveWindowMax(window)
    repeat(500) {
      fast.push(1_000)
      naive.push(1_000)
      assertThat(fast.max()).isEqualTo(naive.max())
    }
  }

  @Test fun emptyWindow_maxIsZero() {
    assertThat(SlidingWindowMax(88).max()).isEqualTo(0)
  }

  @Test fun reset_clearsState() {
    val fast = SlidingWindowMax(4)
    fast.push(30_000)
    fast.reset()
    assertThat(fast.max()).isEqualTo(0)
    // Behaves like a fresh instance afterwards.
    val naive = NaiveWindowMax(4)
    val rng = Random(3)
    repeat(100) {
      val v = rng.nextInt(0, 32_768)
      fast.push(v)
      naive.push(v)
      assertThat(fast.max()).isEqualTo(naive.max())
    }
  }
}
