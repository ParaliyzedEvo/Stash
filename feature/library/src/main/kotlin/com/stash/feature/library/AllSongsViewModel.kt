package com.stash.feature.library.allsongs

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.db.dao.ScannedSongDao
import com.stash.core.data.db.entity.ScanFolderEntity
import com.stash.core.data.db.entity.ScannedSongEntity
import com.stash.core.media.PlayerRepository
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaybackSource
import com.stash.core.model.SCANNED_ID_BASE
import com.stash.core.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

@HiltViewModel
class AllSongsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: ScannedSongDao,
    private val scanner: AllSongsScanner,
    private val player: PlayerRepository,
) : ViewModel() {

    val folders: StateFlow<List<ScanFolderEntity>> = dao.observeFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val songs: StateFlow<List<Track>> = dao.observeSongs()
        .map { rows -> rows.map { it.toTrack() } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val playingId: StateFlow<Long?> = player.playerState
        .map { it.currentTrack?.id }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val scanMutex = Mutex()

    init {
        rescan()
    }

    fun rescan() {
        viewModelScope.launch { scanAll() }
    }

    fun addFolder(uri: Uri) {
        viewModelScope.launch {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                _status.value = "Couldn't get access to that folder"
                return@launch
            }
            val id = dao.insertFolder(
                ScanFolderEntity(
                    treeUri = uri.toString(),
                    displayName = scanner.displayName(uri),
                    addedAt = System.currentTimeMillis(),
                ),
            )
            if (id == -1L) {
                _status.value = "That folder is already added"
                return@launch
            }
            scanAll()
        }
    }

    /**
     * Songs go with the folder (FK cascade). The URI grant is NOT released: the same tree may be
     * Stash's download folder, and releasing it would break that.
     */
    fun removeFolder(folder: ScanFolderEntity) {
        viewModelScope.launch { dao.deleteFolder(folder.id) }
    }

    fun play(index: Int) {
        val queue = songs.value
        if (index !in queue.indices) return
        viewModelScope.launch { player.setQueue(queue, index, PlaybackSource.AllSongs) }
    }

    fun shuffle() {
        val queue = songs.value.shuffled()
        if (queue.isEmpty()) return
        viewModelScope.launch { player.setQueue(queue, 0, PlaybackSource.AllSongs) }
    }

    private suspend fun scanAll() = scanMutex.withLock {
        _scanning.value = true
        var added = 0
        var removed = 0
        var failed = 0
        try {
            for (folder in dao.getFolders()) {
                try {
                    val r = scanner.scan(folder)
                    added += r.added
                    removed += r.removed
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed++
                }
            }
        } finally {
            _scanning.value = false
        }
        _status.value = when {
            failed > 0 -> "$failed folder(s) couldn't be read (moved, or access lost?)"
            added + removed > 0 -> "Added $added, removed $removed"
            else -> null
        }
    }
}

/** Id below SCANNED_ID_BASE: can't collide with a `tracks` row or a radio/search hash id. */
internal fun ScannedSongEntity.toTrack(): Track = Track(
    id = SCANNED_ID_BASE - id,
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    filePath = uri,
    fileFormat = fileFormat,
    fileSizeBytes = fileSizeBytes,
    dateAdded = dateAdded,
    source = MusicSource.LOCAL,
    isDownloaded = true,
)
