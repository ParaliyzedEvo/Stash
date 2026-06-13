package com.stash.data.download.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests for [WebmAudioRemuxer]'s pure decision + arg-building logic.
 *
 * Why this exists: YouTube's Opus audio itags (251/250/249) download into a
 * `.webm` *container*. Even though the stream is audio-only, Android's
 * MediaStore classifies a `.webm` as `video/webm`, so when a user moves their
 * library to shared storage the tracks surface in Google Photos as
 * black-screen "videos". Remuxing the Opus stream into a `.opus` (Ogg)
 * container — losslessly, no re-encode — makes MediaStore see audio and keeps
 * them out of the gallery. `.m4a`/`.flac`/`.opus` are already audio containers
 * and must be left untouched.
 */
class WebmAudioRemuxerTest {

    @Test fun `webm files need remux (case-insensitive)`() {
        assertTrue(WebmAudioRemuxer.needsRemux(File("/tmp/dl_1.webm")))
        assertTrue(WebmAudioRemuxer.needsRemux(File("/tmp/dl_1.WEBM")))
    }

    @Test fun `audio-container files are left alone`() {
        assertFalse(WebmAudioRemuxer.needsRemux(File("/tmp/dl_1.m4a")))
        assertFalse(WebmAudioRemuxer.needsRemux(File("/tmp/dl_1.opus")))
        assertFalse(WebmAudioRemuxer.needsRemux(File("/tmp/dl_1.flac")))
        assertFalse(WebmAudioRemuxer.needsRemux(File("/tmp/dl_1.mp3")))
    }

    @Test fun `opus target swaps the webm extension for opus in the same directory`() {
        val src = File("/tmp/music/dl_1.webm")
        assertEquals(
            File(src.parent, "dl_1.opus").path,
            WebmAudioRemuxer.opusTarget(src).path,
        )
    }

    @Test fun `remux args losslessly stream-copy the audio and drop any video`() {
        val input = File("/in.webm")
        val output = File("/out.opus")
        assertEquals(
            listOf(
                "-y",
                "-i", input.absolutePath,
                "-map", "0:a:0",   // first audio stream only
                "-c:a", "copy",    // lossless: no re-encode
                "-vn",             // never carry a video stream into the audio container
                output.absolutePath,
            ),
            WebmAudioRemuxer.buildRemuxArgs(input, output),
        )
    }
}
