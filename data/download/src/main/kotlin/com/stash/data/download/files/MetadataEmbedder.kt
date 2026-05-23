package com.stash.data.download.files

import android.content.Context
import android.util.Log
import com.stash.core.common.AppVersionProvider
import com.stash.core.model.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Embeds metadata (TITLE, ARTIST, ALBUMARTIST, ALBUM, ISRC, ENCODER)
 * and optional cover art into audio files using ffmpeg. Container-
 * agnostic — `-c copy` muxes the new metadata + picture stream into
 * the original codec without re-encoding.
 *
 * The ffmpeg binary is bundled by youtubedl-android as a native .so.
 * If tagging fails for any reason, the original untagged file is
 * preserved — an untagged download is preferable to a missing one.
 *
 * Vorbis-comment casing: writes each key twice (canonical
 * `ALBUMARTIST` + legacy `album_artist`) so both strict Vorbis
 * readers (Symfonium, some car head units) and ID3-style readers
 * (Plex, Foobar) see the value. ffmpeg normalises both forms to a
 * single atom/frame for M4A and MP3 containers, so the duplicate
 * is a no-op there.
 */
@Singleton
class MetadataEmbedder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appVersion: AppVersionProvider,
) {
    suspend fun embedMetadata(
        audioFile: File,
        track: Track,
        albumArtFile: File? = null,
    ): File = withContext(Dispatchers.IO) {
        val outputFile = File(
            audioFile.parent,
            "${audioFile.nameWithoutExtension}_tagged.${audioFile.extension}",
        )

        val args = buildFfmpegArgs(audioFile, outputFile, track, albumArtFile, appVersion)

        try {
            val ffmpegPath = resolveFfmpegBinary() ?: return@withContext audioFile
            val pb = ProcessBuilder(listOf(ffmpegPath.absolutePath) + args)
                .redirectErrorStream(true)
            // Android's dynamic linker does NOT auto-search the executable's
            // directory for sibling .so files. The bundled ffmpeg needs
            // libc++_shared.so and the libav*.so siblings that youtubedl-android
            // extracts into noBackupFilesDir/youtubedl-android/packages/.
            // Without LD_LIBRARY_PATH the process fails to launch with
            // "CANNOT LINK EXECUTABLE ... library libc++_shared.so not found".
            // Mirrors FFmpegBridgeImpl.ldLibraryPath().
            pb.environment()["LD_LIBRARY_PATH"] = ldLibraryPath()
            val process = pb.start()
            val exit = process.waitFor()

            if (exit != 0) {
                Log.w(TAG, "ffmpeg exited with code $exit for ${audioFile.absolutePath}")
            }

            if (outputFile.exists() && outputFile.length() > 0) {
                audioFile.delete()
                outputFile.renameTo(audioFile)
            } else {
                Log.w(TAG, "ffmpeg produced no output for ${audioFile.absolutePath} (exit=$exit)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "ffmpeg embed failed for ${audioFile.absolutePath}: ${e.message}", e)
            outputFile.delete()
        }

        audioFile
    }

    private fun resolveFfmpegBinary(): File? {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val candidates = listOf("libffmpeg.so", "libffmpeg.zip.so")
        return candidates.map { File(nativeDir, it) }.firstOrNull { it.exists() }
    }

    /**
     * Reconstructs the `LD_LIBRARY_PATH` that youtubedl-android sets
     * internally so the bundled ffmpeg can dlopen its sibling .so files
     * (libc++_shared.so, libav*.so). Order mirrors FFmpegBridgeImpl —
     * see core/data/.../FFmpegBridge.kt#ldLibraryPath for the precedent.
     */
    private fun ldLibraryPath(): String {
        val base = File(context.noBackupFilesDir, "youtubedl-android/packages")
        return buildString {
            append(File(base, "python/usr/lib").absolutePath); append(':')
            append(File(base, "ffmpeg/usr/lib").absolutePath); append(':')
            append(File(base, "aria2c/usr/lib").absolutePath)
        }
    }

    companion object {
        private const val TAG = "MetadataEmbedder"

        private fun sanitize(value: String): String =
            value.replace(Regex("[\\x00-\\x1f]"), "")

        /**
         * Pure helper — builds the ffmpeg argv list for the embed
         * pass. Extracted so unit tests can exercise the tag-writing
         * logic without spawning a process. Internal `companion`
         * visibility intentional: only the embedder + its tests
         * need this.
         */
        internal fun buildFfmpegArgs(
            audioFile: File,
            outputFile: File,
            track: Track,
            albumArtFile: File?,
            appVersion: AppVersionProvider,
        ): List<String> = buildList {
            add("-i"); add(audioFile.absolutePath)

            if (albumArtFile != null && albumArtFile.exists()) {
                add("-i"); add(albumArtFile.absolutePath)
                add("-map"); add("0:a")
                add("-map"); add("1:0")
                add("-disposition:v:0"); add("attached_pic")
            }

            add("-metadata"); add("title=${sanitize(track.title)}")
            add("-metadata"); add("artist=${sanitize(track.artist)}")

            if (track.album.isNotEmpty()) {
                add("-metadata"); add("album=${sanitize(track.album)}")
            }

            val effectiveAlbumArtist = track.albumArtist.ifBlank { track.artist }
            if (effectiveAlbumArtist.isNotEmpty()) {
                add("-metadata"); add("ALBUMARTIST=${sanitize(effectiveAlbumArtist)}")
                add("-metadata"); add("album_artist=${sanitize(effectiveAlbumArtist)}")
            }

            track.isrc?.takeIf { it.isNotBlank() }?.let {
                add("-metadata"); add("ISRC=${sanitize(it)}")
            }

            add("-metadata"); add("ENCODER=Stash ${appVersion.versionName}")

            add("-c"); add("copy")
            add("-y")
            add(outputFile.absolutePath)
        }
    }
}
