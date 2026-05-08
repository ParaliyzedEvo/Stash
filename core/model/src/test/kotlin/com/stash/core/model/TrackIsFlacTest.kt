package com.stash.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackIsFlacTest {

    @Test
    fun `isFlac true for fileFormat = flac`() {
        val track = stubTrack(fileFormat = "flac")
        assertTrue(track.isFlac)
    }

    @Test
    fun `isFlac true for fileFormat = FLAC (case-insensitive)`() {
        val track = stubTrack(fileFormat = "FLAC")
        assertTrue(track.isFlac)
    }

    @Test
    fun `isFlac false for opus`() {
        val track = stubTrack(fileFormat = "opus")
        assertFalse(track.isFlac)
    }

    @Test
    fun `isFlac false for m4a`() {
        val track = stubTrack(fileFormat = "m4a")
        assertFalse(track.isFlac)
    }

    private fun stubTrack(fileFormat: String): Track = Track(
        title = "",
        artist = "",
        fileFormat = fileFormat,
    )
}
