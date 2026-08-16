package com.stash.core.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StreamErrorCascadeGuardTest {

    // A streamed track that hits a transient error (mobile-network stall, dropped
    // CDN connection, expired URL) used to skip to the next track on the FIRST
    // error — the "songs randomly cut off and skip" cluster (#420/401/334/266/209).
    // The guard now returns RetrySameItem on the first error for an item so the
    // player re-prepares it in place, and only escalates to skip/halt if it keeps
    // failing.

    @Test
    fun firstErrorForAnItem_retriesSameItem() {
        val guard = StreamErrorCascadeGuard(threshold = 3)
        assertThat(guard.onError("track-1")).isEqualTo(StreamErrorCascadeGuard.Verdict.RetrySameItem)
    }

    @Test
    fun secondErrorForSameItem_recoversBySkipping() {
        val guard = StreamErrorCascadeGuard(threshold = 3)
        guard.onError("track-1") // RetrySameItem
        assertThat(guard.onError("track-1")).isEqualTo(StreamErrorCascadeGuard.Verdict.Recover)
    }

    @Test
    fun eachNewItemGetsItsOwnRetryBeforeCounting() {
        val guard = StreamErrorCascadeGuard(threshold = 3)
        // A dead backend: every track gets one retry, then a skip. The skips are
        // what accumulate toward the halt.
        assertThat(guard.onError("a")).isEqualTo(StreamErrorCascadeGuard.Verdict.RetrySameItem)
        assertThat(guard.onError("a")).isEqualTo(StreamErrorCascadeGuard.Verdict.Recover) // skip 1
        assertThat(guard.onError("b")).isEqualTo(StreamErrorCascadeGuard.Verdict.RetrySameItem)
        assertThat(guard.onError("b")).isEqualTo(StreamErrorCascadeGuard.Verdict.Recover) // skip 2
        assertThat(guard.onError("c")).isEqualTo(StreamErrorCascadeGuard.Verdict.RetrySameItem)
        assertThat(guard.onError("c")).isInstanceOf(StreamErrorCascadeGuard.Verdict.Halt::class.java) // skip 3 -> halt
    }

    @Test
    fun retriedItemThatKeepsFailing_doesNotLoopOnRetry() {
        // The one-retry-per-item guarantee: a genuinely dead URL is retried ONCE,
        // then every subsequent error skips/halts — never an infinite retry.
        val guard = StreamErrorCascadeGuard(threshold = 3)
        guard.onError("x") // RetrySameItem
        assertThat(guard.onError("x")).isEqualTo(StreamErrorCascadeGuard.Verdict.Recover)
        assertThat(guard.onError("x")).isEqualTo(StreamErrorCascadeGuard.Verdict.Recover)
        assertThat(guard.onError("x")).isInstanceOf(StreamErrorCascadeGuard.Verdict.Halt::class.java)
    }

    @Test
    fun nullItemKey_skipsRetryAndCountsImmediately() {
        // No stable id to retry against -> preserve the old skip-first behavior.
        val guard = StreamErrorCascadeGuard(threshold = 3)
        assertThat(guard.onError(null)).isEqualTo(StreamErrorCascadeGuard.Verdict.Recover)
    }

    @Test
    fun thirdConsecutiveSkip_haltsCascade() {
        val guard = StreamErrorCascadeGuard(threshold = 3)
        // Three null-key errors = three skips, no retries.
        guard.onError(null)
        guard.onError(null)
        assertThat(guard.onError(null)).isInstanceOf(StreamErrorCascadeGuard.Verdict.Halt::class.java)
    }

    @Test
    fun successfulPlaybackResetsSkipCounterButNotTheRetryGuard() {
        val guard = StreamErrorCascadeGuard(threshold = 3)
        guard.onError(null); guard.onError(null) // 2 skips
        guard.onPlaybackStarted()
        assertThat(guard.onError(null)).isEqualTo(StreamErrorCascadeGuard.Verdict.Recover) // counter reset
        // A track already retried before playback still must not re-retry after a
        // brief READY -> error blip (that is the infinite-retry trap).
        val g2 = StreamErrorCascadeGuard(threshold = 3)
        g2.onError("y") // RetrySameItem
        g2.onPlaybackStarted()
        assertThat(g2.onError("y")).isEqualTo(StreamErrorCascadeGuard.Verdict.Recover)
    }

    @Test
    fun userTransportRearmsBothCounterAndRetryGuard() {
        val guard = StreamErrorCascadeGuard(threshold = 3)
        guard.onError("z") // RetrySameItem
        guard.onError("z") // Recover
        guard.onUserTransport()
        // Deliberate user action = a fresh slate: the same item may retry again.
        assertThat(guard.onError("z")).isEqualTo(StreamErrorCascadeGuard.Verdict.RetrySameItem)
    }

    @Test
    fun haltVerdict_carriesCount() {
        val guard = StreamErrorCascadeGuard(threshold = 3)
        guard.onError(null); guard.onError(null)
        val verdict = guard.onError(null) as StreamErrorCascadeGuard.Verdict.Halt
        assertThat(verdict.consecutiveErrors).isEqualTo(3)
    }

    @Test
    fun thresholdZero_rejected() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            StreamErrorCascadeGuard(threshold = 0)
        }
    }
}
