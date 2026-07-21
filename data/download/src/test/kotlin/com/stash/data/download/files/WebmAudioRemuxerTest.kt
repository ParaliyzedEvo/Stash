package com.stash.data.download.files

import android.content.Context
import android.content.pm.ApplicationInfo
import com.stash.core.data.audio.FFmpegBridge
import com.stash.core.data.audio.FFmpegBridgeImpl
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Tests for [WebmAudioRemuxer]'s decision/arg-building logic and remux-failure
 * file integrity.
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

    @get:Rule
    val temp = TemporaryFolder()

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

    @Test fun nonZeroFfmpegWithLargePartialOutputKeepsWebmAndDeletesPartialOpus() = runTest {
        val nativeDir = temp.newFolder("native").also {
            File(it, "libffmpeg.so").apply {
                writeText("fake")
                setExecutable(true)
            }
        }
        val appInfo = ApplicationInfo().apply {
            nativeLibraryDir = nativeDir.absolutePath
        }
        val context = mockk<Context> {
            every { applicationInfo } returns appInfo
            every { cacheDir } returns temp.newFolder("cache")
            every { noBackupFilesDir } returns temp.newFolder("no-backup")
        }
        val input = temp.newFile("track.webm").apply {
            writeBytes(ByteArray(PARTIAL_SIZE_BYTES) { 1 })
        }
        val output = WebmAudioRemuxer.opusTarget(input)
        val process = mockk<Process> {
            every { inputStream } returns ByteArrayInputStream(ByteArray(0))
            every { errorStream } returns ByteArrayInputStream("fatal remux error".toByteArray())
            every { waitFor() } returns 1
        }

        mockkConstructor(ProcessBuilder::class)
        try {
            every { anyConstructed<ProcessBuilder>().environment() } returns mutableMapOf()
            every { anyConstructed<ProcessBuilder>().redirectErrorStream(false) } answers {
                self as ProcessBuilder
            }
            every { anyConstructed<ProcessBuilder>().start() } answers {
                output.writeBytes(ByteArray(PARTIAL_SIZE_BYTES) { 2 })
                process
            }

            val result = WebmAudioRemuxer(FFmpegBridgeImpl(context)).toOpusIfWebm(input)

            assertEquals(input, result)
            assertTrue("original WebM must survive ffmpeg failure", input.exists())
            assertFalse("partial OPUS must be removed", output.exists())
        } finally {
            unmockkConstructor(ProcessBuilder::class)
        }
    }

    @Test fun cancellationKeepsWebmDeletesPartialOpusAndPropagates() = runTest {
        val input = temp.newFile("cancelled.webm").apply {
            writeBytes(ByteArray(PARTIAL_SIZE_BYTES) { 1 })
        }
        val output = WebmAudioRemuxer.opusTarget(input)
        val bridge = object : FFmpegBridge {
            override suspend fun runWithStderrCapture(args: List<String>): String {
                output.writeBytes(ByteArray(PARTIAL_SIZE_BYTES) { 2 })
                throw CancellationException("cancelled")
            }
        }

        var cancellationPropagated = false
        try {
            WebmAudioRemuxer(bridge).toOpusIfWebm(input)
        } catch (_: CancellationException) {
            cancellationPropagated = true
        }

        assertTrue("cancellation must propagate", cancellationPropagated)
        assertTrue("original WebM must survive cancellation", input.exists())
        assertFalse("partial OPUS must be removed on cancellation", output.exists())
    }

    private companion object {
        const val PARTIAL_SIZE_BYTES = 32 * 1024
    }
}
