package com.stash.feature.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [isRowPlaying] drives the now-playing indicator on [SongRow]. A row is active
 * only when its non-blank videoId matches the player's current youtubeId; blank
 * (Qobuz-native) rows must never match.
 */
class NowPlayingIdTest {
    @Test fun `row is active when its non-blank videoId equals the playing youtubeId`() {
        assertTrue(isRowPlaying(rowVideoId = "v1", currentPlayingYoutubeId = "v1"))
    }

    @Test fun `blank videoId never matches even against a blank or null playing id`() {
        assertFalse(isRowPlaying(rowVideoId = "", currentPlayingYoutubeId = ""))
        assertFalse(isRowPlaying(rowVideoId = "", currentPlayingYoutubeId = null))
    }

    @Test fun `different id is not active`() {
        assertFalse(isRowPlaying(rowVideoId = "v1", currentPlayingYoutubeId = "v2"))
    }

    @Test fun `null playing id is not active`() {
        assertFalse(isRowPlaying(rowVideoId = "v1", currentPlayingYoutubeId = null))
    }
}
