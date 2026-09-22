package com.stash.data.lyrics.sidecar

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.LyricsEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.LibraryLayout
import com.stash.core.data.prefs.StoragePreference
import com.stash.data.download.files.LibraryLayoutResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v0.9.36 sidecar writer, extended for word-synced (TTML) lyrics.
 *
 * Writes a sidecar next to the audio file on every successful lyrics fetch, so external players
 * pick lyrics up by convention. Two bodies compete for that role:
 *
 *  - **`.lrc`** — line-synced text, understood by essentially every external player (PowerAmp,
 *    VLC, Musicolet, etc.).
 *  - **`.ttml`** — Apple's word-synced format, when the source provided it. Few external players
 *    read this today, but it's the source of truth for Stash's own syllable renderer.
 *
 * When a track has TTML, the `.ttml` sidecar is written and the `.lrc` is DELETED rather than kept
 * alongside it — the `.ttml` supersedes it. The delete only runs after the `.ttml` write itself
 * succeeds, so a failure never leaves a track with no sidecar at all. When a track has no TTML
 * (LRCLIB/KuGou/YT hits), the `.lrc` is written exactly as before.
 *
 * Two storage targets are supported:
 *
 *  - **Internal storage** — `track.filePath` is an absolute filesystem path; sidecars are written
 *    via plain `java.io.File` next to the audio.
 *  - **SAF tree** — `track.filePath` starts with `content://`; the sidecar location is derived from
 *    the user's persisted external tree URI ([StoragePreference]), walked down through the same
 *    [LibraryLayoutResolver] location the download pipeline used, so a sidecar always lands where
 *    the download created it. See [resolveSafLocation].
 *
 * Write failure is non-fatal for the Room state: [com.stash.data.lyrics.LyricsRepository] wraps the
 * throwing `write()` in `runCatching`; only that path is best-effort — the lyrics row +
 * `tracks.lyrics_fetched_at` stamp are the source of truth for the in-app reader; the sidecar is a
 * courtesy.
 *
 * `.lrc` body format:
 * ```
 * [ti:<title>]
 * [ar:<albumArtist or artist if blank>]
 * [al:<album>]                -- only when non-blank
 * [length:mm:ss]              -- only when durationMs > 0
 * [by:Stash]
 * <synced LRC body, or plain text if synced is missing>
 * ```
 *
 * [write] throws [IOException] when both `syncedLrc` and `plainText` are null/blank AND there's no
 * `ttml` either (the instrumental case) — `LyricsRepository` already guards this for the
 * instrumental flag, but `write()` re-checks defensively so callers can't accidentally create a
 * header-only sidecar with no body.
 */
@Singleton
class LyricsSidecarWriter @Inject constructor(
    private val trackDao: TrackDao,
    @ApplicationContext private val context: Context,
    private val storagePreference: StoragePreference,
) {

    /**
     * Writes the sidecar(s) for [trackId] using [lyrics]. See class KDoc for which extension(s)
     * end up on disk.
     *
     * Fails when:
     *   - Both `syncedLrc`/`plainText` and `ttml` are null/blank (the instrumental case).
     *   - The track row is gone (deleted mid-flight).
     *   - The track has no [TrackEntity.filePath] (legacy / sync-only row).
     *   - The SAF tree URI is unset on a `content://` filePath.
     *
     * Throws on disk/SAF I/O failure so [com.stash.data.lyrics.LyricsRepository] can `runCatching`
     * it as non-fatal.
     */
    suspend fun write(trackId: Long, lyrics: LyricsEntity) {
        val ttml = lyrics.ttml?.takeUnless(String::isBlank)
        if (ttml == null && lyrics.syncedLrc.isNullOrBlank() && lyrics.plainText.isNullOrBlank()) {
            fail("No lyrics body for track $trackId")
        }
        val track = trackDao.getById(trackId) ?: fail("Track $trackId no longer exists")
        val path = track.filePath ?: fail("Track $trackId has no downloaded file")

        if (ttml != null) {
            // TTML supersedes the plain .lrc: write it first, and only once THAT succeeds, drop
            // the old .lrc rather than leaving a stale duplicate next to the new file.
            writeSidecarFile(track, path, ttml, "ttml", TTML_MIME)
            runCatching { deleteSidecarFile(track, path, "lrc") }
                .onFailure { e -> Log.w(TAG, "Stale .lrc cleanup failed for track $trackId", e) }
            return
        }

        // No TTML for this track: keep the plain .lrc as the on-disk sidecar.
        if (lyrics.syncedLrc.isNullOrBlank() && lyrics.plainText.isNullOrBlank()) {
            fail("No lyrics body for track $trackId")
        }
        writeSidecarFile(track, path, buildLrcBody(track, lyrics), "lrc", LRC_MIME)
    }

    private suspend fun writeSidecarFile(track: TrackEntity, path: String, body: String, ext: String, mime: String) {
        if (path.startsWith("content://")) writeSafSidecar(track, body, ext, mime)
        else writeFilesystemSidecar(path, body, ext)
    }

    private suspend fun deleteSidecarFile(track: TrackEntity, path: String, ext: String) {
        if (path.startsWith("content://")) deleteSafSidecar(track, ext)
        else deleteFilesystemSidecar(path, ext)
    }

    private fun writeFilesystemSidecar(audioPath: String, body: String, ext: String) {
        val audio = File(audioPath)
        val parent = audio.parentFile ?: run {
            Log.w(TAG, "Cannot resolve parent directory for $audioPath; sidecar skipped")
            throw IOException("Cannot resolve parent directory for $audioPath")
        }
        val sidecar = File(parent, "${audio.nameWithoutExtension}.$ext")
        sidecar.writeText(body, Charsets.UTF_8)
    }

    private fun deleteFilesystemSidecar(audioPath: String, ext: String) {
        val audio = File(audioPath)
        val parent = audio.parentFile ?: return
        File(parent, "${audio.nameWithoutExtension}.$ext").takeIf { it.exists() }?.delete()
    }

    private suspend fun writeSafSidecar(track: TrackEntity, body: String, ext: String, mime: String) {
        val (tree, segments, baseName) = resolveSafLocation(track)
            ?: fail("Track ${track.id} has no SAF tree configured")
        var cursor = tree
        for (segment in segments) {
            cursor = findOrCreateDir(cursor, segment) ?: fail("Could not create directory '$segment'")
        }
        val filename = "$baseName.$ext"
        val existing = cursor.findFile(filename)
        val target = existing ?: cursor.createFile(mime, filename) ?: run {
            Log.w(TAG, "Could not create SAF sidecar '$filename' under ${cursor.uri}")
            throw IOException("Could not create SAF sidecar $filename")
        }
        context.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
            out.write(body.toByteArray(Charsets.UTF_8))
        } ?: fail("Could not open SAF output stream for sidecar ${target.uri}")
    }

    private suspend fun deleteSafSidecar(track: TrackEntity, ext: String) {
        val (tree, segments, baseName) = resolveSafLocation(track) ?: return
        var cursor = tree
        for (segment in segments) {
            cursor = cursor.findFile(segment)?.takeIf { it.isDirectory } ?: return
        }
        cursor.findFile("$baseName.$ext")?.delete()
    }

    /**
     * Resolves (tree root, directory segments relative to the tree, filename without extension)
     * for [track]'s sidecar location. Shared by [writeSafSidecar] and [deleteSafSidecar] so both
     * land on / clean up the exact same path.
     *
     * Prefers the audio's OWN directory (decoded straight from its content URI via
     * [safLocationBesideAudio]) over re-deriving from the current layout preference — a track
     * downloaded under one layout stays put until Reorganize runs, and re-deriving from the
     * CURRENT preference would target an empty folder while the audio sat elsewhere.
     */
    private suspend fun resolveSafLocation(track: TrackEntity): Triple<DocumentFile, List<String>, String>? {
        val treeUri: Uri = storagePreference.externalTreeUri.first() ?: run {
            Log.w(TAG, "Track ${track.id} has SAF filePath but no externalTreeUri persisted; sidecar skipped")
            return null
        }
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: run {
            Log.w(TAG, "DocumentFile.fromTreeUri returned null for $treeUri; sidecar skipped")
            return null
        }
        val beside = safLocationBesideAudio(treeUri, track.filePath)
        val (segments, baseName) = beside ?: run {
            // track.artist (not albumArtist) matches what commitDownload slugged into the
            // directory names (#198/#104).
            val layout = runCatching { storagePreference.libraryLayout.first() }
                .getOrDefault(LibraryLayout.DEFAULT)
            val playlistName =
                if (layout == LibraryLayout.PLAYLIST) {
                    runCatching { trackDao.getFirstPlaylistNameForTrack(track.id) }.getOrNull()
                } else {
                    null
                }
            val location = LibraryLayoutResolver.resolve(
                layout,
                artist = track.artist,
                album = track.album.takeIf { it.isNotBlank() },
                title = track.title,
                playlistName = playlistName,
            )
            location.segments to location.baseName
        }
        return Triple(tree, segments, baseName)
    }

    /**
     * Decode a SAF audio document uri into (directory segments relative to
     * the picked tree, filename without extension) so a sidecar can be
     * written/deleted in the audio's ACTUAL directory.
     *
     * Mirrors the `<volume>:<path>` decode used by the reorganize pass.
     * Returns null whenever the ids don't parse or the document doesn't sit
     * under the tree — the caller then falls back to the layout resolver.
     */
    private fun safLocationBesideAudio(treeUri: Uri, docUriString: String?): Pair<List<String>, String>? {
        if (docUriString.isNullOrBlank() || !docUriString.startsWith("content://")) return null
        return try {
            val baseRel = DocumentsContract.getTreeDocumentId(treeUri).substringAfter(':', "")
            val docRel = DocumentsContract.getDocumentId(Uri.parse(docUriString)).substringAfter(':', "")
            if (!docRel.startsWith(baseRel, ignoreCase = true)) return null
            val parts = docRel.substring(baseRel.length).split('/').filter { it.isNotBlank() }
            if (parts.isEmpty()) null
            else parts.dropLast(1) to parts.last().substringBeforeLast('.')
        } catch (t: Throwable) {
            Log.d(TAG, "Sidecar location undecodable for $docUriString: ${t.message}")
            null
        }
    }

    /**
     * SAF-side `findOrCreateDir`. Returns `null` (logged) instead of throwing — sidecar failure
     * is converted to IOException by its caller.
     */
    private fun findOrCreateDir(parent: DocumentFile, name: String): DocumentFile? {
        parent.findFile(name)?.takeIf { it.isDirectory }?.let { return it }
        val created = parent.createDirectory(name)
        if (created == null) {
            Log.w(TAG, "Could not create SAF dir '$name' under ${parent.uri}")
        }
        return created
    }

    private fun buildLrcBody(track: TrackEntity, lyrics: LyricsEntity): String = buildString {
        appendLine("[ti:${track.title}]")
        appendLine("[ar:${track.albumArtist.ifBlank { track.artist }}]")
        if (track.album.isNotBlank()) appendLine("[al:${track.album}]")
        if (track.durationMs > 0) {
            val sec = (track.durationMs / 1000).toInt()
            appendLine("[length:${sec / 60}:%02d]".format(sec % 60))
        }
        appendLine("[by:Stash]")
        append(lyrics.syncedLrc?.takeUnless(String::isBlank) ?: lyrics.plainText.orEmpty())
    }

    private fun fail(message: String): Nothing = throw IOException(message).also { Log.w(TAG, message) }

    private companion object {
        private const val TAG = "LyricsSidecarWriter"
        private const val LRC_MIME = "application/x-lrc"
        private const val TTML_MIME = "application/x-ttml"
    }
}