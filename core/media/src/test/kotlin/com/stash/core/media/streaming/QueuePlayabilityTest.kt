package com.stash.core.media.streaming

import com.stash.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class QueuePlayabilityTest {
    private fun dl(id: Long) = Track(id = id, title = "d$id", artist = "a", isDownloaded = true, isStreamable = true, filePath = "/m/$id.opus")
    private fun stream(id: Long) = Track(id = id, title = "s$id", artist = "a", isDownloaded = false, isStreamable = true, filePath = null)
    private val tracks = listOf(dl(1), stream(2))

    @Test fun `streaming on returns all tracks`() {
        assertEquals(listOf(1L, 2L), queuePlayableTracks(tracks, streamingEnabled = true).map { it.id })
    }

    @Test fun `streaming off returns downloaded only`() {
        assertEquals(listOf(1L), queuePlayableTracks(tracks, streamingEnabled = false).map { it.id })
    }
}
