package com.stash.feature.library.allsongs

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import com.stash.core.common.constants.StashConstants
import com.stash.core.data.db.dao.ScannedSongDao
import com.stash.core.data.db.entity.ScanFolderEntity
import com.stash.core.data.db.entity.ScannedSongEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class ScanResult(val added: Int, val removed: Int)

/**
 * Walks a user-chosen SAF tree for audio files and syncs `scanned_songs` with it.
 * One ContentResolver query per directory (no per-file DocumentFile IPC), tag reads
 * bounded by Semaphore(4), only NEW files are tag-read on a rescan.
 */
@Singleton
class AllSongsScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: ScannedSongDao,
) {
    private data class AudioFile(val uri: String, val name: String, val size: Long)

    fun displayName(tree: Uri): String {
        val name = try {
            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            context.contentResolver
                .query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (e: Exception) {
            null
        }
        return name ?: tree.lastPathSegment ?: "Folder"
    }

    /** Throws if the folder can't be read, so a missing SD card / revoked grant never wipes its songs. */
    suspend fun scan(folder: ScanFolderEntity): ScanResult = withContext(Dispatchers.IO) {
        val tree = Uri.parse(folder.treeUri)
        val files = walk(tree)
        val known = dao.getUris(folder.id).toHashSet()
        val found = files.mapTo(HashSet()) { it.uri }

        val gone = known.filter { it !in found }
        gone.chunked(500).forEach { dao.deleteByUris(folder.id, it) }

        val fresh = files.filter { it.uri !in known }
        val sem = Semaphore(4)
        val songs = coroutineScope {
            fresh.map { f -> async { sem.withPermit { readTags(folder.id, f) } } }.awaitAll()
        }
        songs.chunked(200).forEach { dao.insertSongs(it) }
        ScanResult(added = songs.size, removed = gone.size)
    }

    private fun walk(tree: Uri): List<AudioFile> {
        val resolver = context.contentResolver
        val out = ArrayList<AudioFile>()
        val stack = ArrayList<String>()
        stack.add(DocumentsContract.getTreeDocumentId(tree))
        var isRoot = true
        while (stack.isNotEmpty()) {
            val parentId = stack.removeAt(stack.lastIndex)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            val cursor = resolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                ),
                null, null, null,
            )
            if (cursor == null) {
                if (isRoot) throw IOException("Can't read folder") else continue
            }
            isRoot = false
            cursor.use { c ->
                while (c.moveToNext()) {
                    val docId = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    val size = c.getLong(3)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (!name.startsWith(".")) stack.add(docId)
                    } else if (isAudio(name, mime) && !(size in 1 until StashConstants.MIN_PLAYABLE_LOCAL_BYTES)) {
                        out.add(AudioFile(DocumentsContract.buildDocumentUriUsingTree(tree, docId).toString(), name, size))
                    }
                }
            }
        }
        return out
    }

    private fun isAudio(name: String, mime: String): Boolean =
        mime.startsWith("audio/") || name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS

    private fun readTags(folderId: Long, f: AudioFile): ScannedSongEntity {
        val ext = f.name.substringAfterLast('.', "").lowercase()
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var duration = 0L
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, Uri.parse(f.uri))
            title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            // Unreadable tags: fall back to the filename below.
        } finally {
            runCatching { r.release() }
        }
        return ScannedSongEntity(
            folderId = folderId,
            uri = f.uri,
            title = title?.takeIf { it.isNotBlank() } ?: f.name.substringBeforeLast('.'),
            artist = artist?.takeIf { it.isNotBlank() } ?: "Unknown Artist",
            album = album?.takeIf { it.isNotBlank() } ?: "",
            durationMs = duration,
            fileFormat = ext.ifBlank { "audio" },
            fileSizeBytes = f.size,
            dateAdded = System.currentTimeMillis(),
        )
    }

    private companion object {
        val AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav")
    }
}
