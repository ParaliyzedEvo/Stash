package com.stash.data.download.lossless.amz

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.audio.FFmpegBridge
import io.mockk.coEvery
import io.mockk.mockk
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests for [AmzDecryptor] — the ffmpeg `-decryption_key` wrapper that turns an
 * encrypted amz CMAF file into clear FLAC. The [FFmpegBridge] is mocked; the
 * mock "is" ffmpeg, so a success path writes the output file the real binary
 * would have produced. Mirrors [com.stash.data.download.files.WebmAudioRemuxer]'s
 * non-fatal contract (failure cleans up + returns false, never throws except on
 * cancellation).
 */
class AmzDecryptorTest {

    @get:Rule val tmp = TemporaryFolder()

    private val ffmpeg: FFmpegBridge = mockk()
    private fun decryptor() = AmzDecryptor(ffmpeg)

    private val key = "8164fe2db5ebd498c8265b3e873462c1"

    @Test fun `decrypt args feed the key, stream-copy and overwrite the output`() {
        val input = File("/enc.cmaf")
        val output = File("/out.flac")
        assertThat(AmzDecryptor.buildDecryptArgs(input, key, output)).isEqualTo(
            listOf(
                "-y",
                "-decryption_key", key,
                "-i", input.absolutePath,
                "-c", "copy",
                output.absolutePath,
            ),
        )
    }

    @Test fun `returns true when ffmpeg produces a non-empty output`() = runTest {
        val input = tmp.newFile("enc.cmaf")
        val output = File(tmp.root, "out.flac")
        coEvery { ffmpeg.runWithStderrCapture(any()) } answers {
            output.writeBytes(ByteArray(1024) { 1 }) // simulate ffmpeg writing FLAC
            "size=...  time=00:03:14"
        }

        assertThat(decryptor().decryptToFlac(input, key, output)).isTrue()
        assertThat(output.exists()).isTrue()
    }

    @Test fun `returns false and cleans up when ffmpeg writes no output`() = runTest {
        val input = tmp.newFile("enc.cmaf")
        val output = File(tmp.root, "out.flac")
        coEvery { ffmpeg.runWithStderrCapture(any()) } returns "Error: decryption failed"

        assertThat(decryptor().decryptToFlac(input, key, output)).isFalse()
        assertThat(output.exists()).isFalse()
    }

    @Test fun `returns false and cleans up a zero-byte output`() = runTest {
        val input = tmp.newFile("enc.cmaf")
        val output = File(tmp.root, "out.flac")
        coEvery { ffmpeg.runWithStderrCapture(any()) } answers {
            output.createNewFile() // ffmpeg touched it but wrote nothing usable
            "boom"
        }

        assertThat(decryptor().decryptToFlac(input, key, output)).isFalse()
        assertThat(output.exists()).isFalse()
    }

    @Test fun `returns false on ffmpeg failure`() = runTest {
        val input = tmp.newFile("enc.cmaf")
        val output = File(tmp.root, "out.flac")
        coEvery { ffmpeg.runWithStderrCapture(any()) } throws IOException("ffmpeg binary missing")

        assertThat(decryptor().decryptToFlac(input, key, output)).isFalse()
    }

    @Test fun `cancellation propagates and is not swallowed`() {
        val input = File(tmp.root, "enc.cmaf").apply { writeBytes(ByteArray(8)) }
        val output = File(tmp.root, "out.flac")
        coEvery { ffmpeg.runWithStderrCapture(any()) } throws CancellationException("cancelled")

        assertThrows(CancellationException::class.java) {
            runBlocking { decryptor().decryptToFlac(input, key, output) }
        }
    }
}
