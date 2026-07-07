package com.stash.feature.nowplaying.ui

import com.stash.data.lyrics.parser.LrcLine
import org.junit.Assert.assertEquals
import org.junit.Test

class CurrentLineIndexTest {

    private val lines = listOf(
        LrcLine(timestampMs = 10_000L, text = "line one"),
        LrcLine(timestampMs = 20_000L, text = "line two"),
        LrcLine(timestampMs = 30_000L, text = "line three"),
    )

    @Test fun `before first timestamp coerces to 0`() {
        assertEquals(0, currentLineIndex(lines, positionMs = 0L))
        assertEquals(0, currentLineIndex(lines, positionMs = 9_999L))
    }

    @Test fun `exactly on a timestamp selects that line`() {
        assertEquals(0, currentLineIndex(lines, positionMs = 10_000L))
        assertEquals(1, currentLineIndex(lines, positionMs = 20_000L))
    }

    @Test fun `between lines selects the earlier line`() {
        assertEquals(1, currentLineIndex(lines, positionMs = 25_000L))
    }

    @Test fun `after last timestamp selects the last line`() {
        assertEquals(2, currentLineIndex(lines, positionMs = 99_000L))
    }

    @Test fun `empty list returns 0 - callers must getOrNull`() {
        assertEquals(0, currentLineIndex(emptyList(), positionMs = 5_000L))
    }
}
