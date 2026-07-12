package com.stash.data.ytmusic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PLAY_COUNT_REGEX] guards the shared song-row parser from stamping a YouTube
 * play count (flexColumns[2] in the search "Songs" + artist "Popular" shelves)
 * as the album title — which polluted `track.album` and broke tap-to-album.
 */
class PlayCountGuardTest {

    @Test fun `play counts are recognized (rejected as album)`() {
        for (s in listOf("16M plays", "1.2B plays", "1,234,567 plays", "500K plays", "1 play", "16m PLAYS")) {
            assertTrue("'$s' should match play-count", PLAY_COUNT_REGEX.matches(s))
        }
    }

    @Test fun `real album titles are NOT play counts (kept as album)`() {
        for (s in listOf("Under Pressure", "The Incredible True Story", "Child's Play", "2014 Forest Hills Drive", "m b v", "21")) {
            assertFalse("'$s' should NOT match play-count", PLAY_COUNT_REGEX.matches(s))
        }
    }
}
