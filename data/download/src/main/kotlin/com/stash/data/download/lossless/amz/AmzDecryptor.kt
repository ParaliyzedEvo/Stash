package com.stash.data.download.lossless.amz

import android.util.Log
import com.stash.core.data.audio.FFmpegBridge
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Decrypts an encrypted amz CMAF file into clear FLAC using ffmpeg's
 * `-decryption_key`.
 *
 * amz serves CENC AES-CTR-encrypted CMAF, but the per-track AES-128 key rides
 * in the `/api/track` response ([AmzTrack.decryptionKey]) — decryption is plain
 * ffmpeg, not Widevine/EME. A stream-copy (`-c copy`) decrypt is lossless: no
 * re-encode, the clear FLAC frames are written straight through.
 *
 * Mirrors [com.stash.data.download.files.WebmAudioRemuxer]'s non-fatal
 * contract: returns true only when ffmpeg produced a non-empty [output], and
 * cleans up + returns false on any failure rather than throwing — so a flaky
 * decrypt is "skip this source" rather than a crash. [CancellationException] is
 * re-thrown (never swallowed) so coroutine cancellation isn't mistaken for a
 * decrypt failure.
 *
 * The bundled youtubedl-android ffmpeg (7.0.1) is confirmed to support
 * `-decryption_key` — see FFmpegDecryptionKeyGateTest.
 */
@Singleton
class AmzDecryptor @Inject constructor(
    private val ffmpeg: FFmpegBridge,
) {
    /**
     * Decrypt [input] (encrypted CMAF) to FLAC at [output] with the AES-128 hex
     * [key]. Returns true on success (output exists and is non-empty); on any
     * failure deletes a partial [output] and returns false.
     */
    suspend fun decryptToFlac(input: File, key: String, output: File): Boolean =
        try {
            val stderr = ffmpeg.runWithStderrCapture(buildDecryptArgs(input, key, output))
            if (output.exists() && output.length() > 0) {
                Log.i(TAG, "decrypted ${input.name} -> ${output.name} (${output.length()} bytes)")
                true
            } else {
                Log.w(TAG, "decrypt produced no output for ${input.name}; stderr tail=${stderr.takeLast(200)}")
                runCatching { output.delete() }
                false
            }
        } catch (e: CancellationException) {
            runCatching { output.delete() }
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "decrypt failed for ${input.name}: ${e.message}")
            runCatching { output.delete() }
            false
        }

    companion object {
        private const val TAG = "AmzDecryptor"

        /**
         * ffmpeg args: overwrite, supply the AES-128 [key], read the encrypted
         * [input], stream-copy (lossless, no re-encode), write [output].
         */
        fun buildDecryptArgs(input: File, key: String, output: File): List<String> = listOf(
            "-y",
            "-decryption_key", key,
            "-i", input.absolutePath,
            "-c", "copy",
            output.absolutePath,
        )
    }
}
