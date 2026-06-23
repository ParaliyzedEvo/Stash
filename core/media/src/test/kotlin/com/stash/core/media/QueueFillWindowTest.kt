package com.stash.core.media

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.Track
import org.junit.Test

/**
 * Pins [computeFillWindow]: the bounded background-fill window for
 * [PlayerRepositoryImpl.setQueue].
 *
 * Before the bound, setQueue background-resolved the ENTIRE rest of the queue
 * (e.g. 1142 tracks) into the player timeline. For cheap sources (Qobuz signed
 * URLs) that was merely wasteful; for amz — where each resolve fetches +
 * ffmpeg-decrypts a whole 30–180 MB track — it tried to download the user's
 * entire library on one tap AND saturated the resolve fan-out, starving the
 * immediate next-track prefetch so auto-advance died.
 *
 * The fix bounds the fill to a small lookahead/lookbehind window; the rolling
 * single-next-up prefetch ([PlayerRepositoryImpl.prefetchNextTrack]) keeps
 * playback seamless as the user advances.
 */
class QueueFillWindowTest {

    private fun tracks(n: Int): List<Track> =
        (0 until n).map { Track(id = it.toLong(), title = "t$it", artist = "a") }

    @Test fun `huge queue started at head fills only the forward lookahead, no backward`() {
        val window = computeFillWindow(tracks(1142), safeStart = 0)

        assertThat(window.forward.map { it.id })
            .isEqualTo((1L..BACKGROUND_FILL_LOOKAHEAD.toLong()).toList())
        assertThat(window.backward).isEmpty()
        // The whole library is NOT pulled into the fill.
        assertThat(window.forward.size).isAtMost(BACKGROUND_FILL_LOOKAHEAD)
    }

    @Test fun `started mid-queue fills bounded window on both sides`() {
        val window = computeFillWindow(tracks(1142), safeStart = 500)

        assertThat(window.forward.size).isEqualTo(BACKGROUND_FILL_LOOKAHEAD)
        assertThat(window.forward.first().id).isEqualTo(501L)
        assertThat(window.backward.size).isEqualTo(BACKGROUND_FILL_LOOKBEHIND)
        assertThat(window.backward.last().id).isEqualTo(499L)
    }

    @Test fun `clamps at the tail without overrunning`() {
        val window = computeFillWindow(tracks(5), safeStart = 4)

        assertThat(window.forward).isEmpty() // nothing after the last track
        assertThat(window.backward.size).isEqualTo(BACKGROUND_FILL_LOOKBEHIND)
    }

    @Test fun `single-track queue fills nothing`() {
        val window = computeFillWindow(tracks(1), safeStart = 0)

        assertThat(window.forward).isEmpty()
        assertThat(window.backward).isEmpty()
    }

    // --- bufferTopUpSlice: the rolling buffer's append decision ---

    @Test fun `shallow buffer tops up to the lookahead, skipping the immediate next`() {
        // current=5, timeline frontier=7 (so ahead=2). Should append from 8 up
        // to current+1+LOOKAHEAD, i.e. fill the cushion back to LOOKAHEAD deep.
        val slice = bufferTopUpSlice(
            tracks = tracks(1142),
            currentLogical = 5,
            lastLogical = 7,
            aheadInTimeline = 2,
            existingIds = setOf(5L, 6L, 7L),
        )
        assertThat(slice.first().id).isEqualTo(8L) // never re-touches the immediate next (6)
        assertThat(slice.last().id).isEqualTo((5 + 1 + BACKGROUND_FILL_LOOKAHEAD - 1).toLong())
        assertThat(slice.map { it.id }).doesNotContain(6L)
    }

    @Test fun `full buffer appends nothing`() {
        val slice = bufferTopUpSlice(
            tracks = tracks(1142),
            currentLogical = 5,
            lastLogical = 5 + BACKGROUND_FILL_LOOKAHEAD,
            aheadInTimeline = BACKGROUND_FILL_LOOKAHEAD,
            existingIds = emptySet(),
        )
        assertThat(slice).isEmpty()
    }

    @Test fun `near the end of the queue appends only what remains`() {
        val slice = bufferTopUpSlice(
            tracks = tracks(12),
            currentLogical = 9,
            lastLogical = 11, // frontier already at the last track
            aheadInTimeline = 2,
            existingIds = setOf(9L, 10L, 11L),
        )
        assertThat(slice).isEmpty() // nothing left to append
    }

    @Test fun `skips ids already in the timeline`() {
        val slice = bufferTopUpSlice(
            tracks = tracks(1142),
            currentLogical = 0,
            lastLogical = 0,
            aheadInTimeline = 0,
            existingIds = setOf(0L, 3L), // 3 already present (e.g. a prefetch insert)
        )
        assertThat(slice.map { it.id }).doesNotContain(3L)
        assertThat(slice.first().id).isEqualTo(2L) // starts at current+2 (1 belongs to prefetch)
    }
}
