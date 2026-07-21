package com.stash.data.download.files

import android.util.Log
import com.stash.core.data.audio.FFmpegBridge
import kotlinx.coroutines.CancellationException
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remuxes YouTube `.webm` (Opus) downloads into a `.opus` (Ogg) container.
 *
 * YouTube serves its Opus audio itags (251/250/249) inside a `.webm`
 * container. The stream is audio-only, but Android's MediaStore classifies a
 * `.webm` file as `video/webm` regardless — so when a user moves their library
 * into shared storage the tracks get indexed as video and surface in Google
 * Photos as black-screen "videos". Remuxing the Opus stream into a `.opus`
 * (Ogg) container is a lossless stream copy (no re-encode) that makes
 * MediaStore see audio and keeps the files out of the gallery.
 *
 * `.m4a`/`.flac`/`.opus`/`.mp3` are already audio containers and pass through
 * untouched. Remux failure is non-fatal: the original `.webm` is returned and
 * still plays — an un-ideal container beats a missing track.
 */
@Singleton
class WebmAudioRemuxer @Inject constructor(
    private val ffmpeg: FFmpegBridge,
) {
    /**
     * If [file] is a `.webm`, remux it losslessly to `.opus` and return the
     * new file (deleting the original). Otherwise return [file] unchanged.
     */
    suspend fun toOpusIfWebm(file: File): File {
        if (!needsRemux(file)) return file
        val output = opusTarget(file)
        return try {
            ffmpeg.runWithStderrCapture(buildRemuxArgs(file, output))
            if (output.exists() && output.length() > 0) {
                file.delete()
                Log.i(TAG, "remuxed webm->opus: ${file.name} -> ${output.name}")
                output
            } else {
                Log.w(TAG, "webm->opus remux produced no output for ${file.name}; keeping original")
                deletePartialOutput(output)
                file
            }
        } catch (e: CancellationException) {
            deletePartialOutput(output)
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "webm->opus remux failed for ${file.name}: ${e.message}; keeping original")
            deletePartialOutput(output)
            file
        }
    }

    private fun deletePartialOutput(output: File) {
        if (output.exists() && !output.delete()) {
            Log.w(TAG, "could not delete partial remux output: ${output.absolutePath}")
        }
    }

    companion object {
        private const val TAG = "WebmAudioRemuxer"

        /** True only for `.webm` containers (the ones MediaStore tags as video). */
        fun needsRemux(file: File): Boolean = file.extension.equals("webm", ignoreCase = true)

        /** The sibling `.opus` path for a `.webm` input (same directory + basename). */
        fun opusTarget(file: File): File = File(file.parent, "${file.nameWithoutExtension}.opus")

        /**
         * ffmpeg args: take the first audio stream, stream-copy it (lossless,
         * no re-encode), drop any video, and write the `.opus` (Ogg) output.
         */
        fun buildRemuxArgs(input: File, output: File): List<String> = listOf(
            "-y",
            "-i", input.absolutePath,
            "-map", "0:a:0",
            "-c:a", "copy",
            "-vn",
            output.absolutePath,
        )
    }
}
