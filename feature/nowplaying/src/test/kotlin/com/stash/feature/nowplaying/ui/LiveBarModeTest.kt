package com.stash.feature.nowplaying.ui

import com.stash.data.lyrics.parser.LrcLine
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveBarModeTest {

    private val lines = listOf(LrcLine(timestampMs = 1_000L, text = "hi"))

    @Test fun `synced maps to Live carrying the lines`() {
        val mode = liveBarModeFor(LyricsViewState.Synced(lines, plainFallback = "hi"))
        assertEquals(LiveBarMode.Live(lines), mode)
    }

    @Test fun `plain maps to Static`() {
        assertEquals(LiveBarMode.Static, liveBarModeFor(LyricsViewState.Plain("text")))
    }

    @Test fun `everything else maps to Hidden`() {
        assertEquals(LiveBarMode.Hidden, liveBarModeFor(LyricsViewState.Loading))
        assertEquals(LiveBarMode.Hidden, liveBarModeFor(LyricsViewState.None))
        assertEquals(LiveBarMode.Hidden, liveBarModeFor(LyricsViewState.Instrumental))
        assertEquals(LiveBarMode.Hidden, liveBarModeFor(LyricsViewState.Error(retryable = true)))
    }
}
