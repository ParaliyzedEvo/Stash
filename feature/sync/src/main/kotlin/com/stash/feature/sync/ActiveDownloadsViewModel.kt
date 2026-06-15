package com.stash.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.db.dao.ActiveDownloadRow
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.model.DownloadStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Which tab the user is viewing on the Downloads page.
 */
enum class DownloadsTab { ACTIVE, HISTORY }

/**
 * UI state for the Active Downloads screen.
 *
 * @property isLoading  True until the first emission from the DAO flow.
 * @property downloads  Flat list of active download rows, ordered by status
 *                      priority (IN_PROGRESS → PENDING → WAITING_FOR_LOSSLESS)
 *                      then by creation time.
 * @property completedDownloads  Recently completed downloads for the history tab.
 * @property selectedTab  Currently selected tab.
 */
data class ActiveDownloadsUiState(
    val isLoading: Boolean = true,
    val downloads: List<ActiveDownloadRow> = emptyList(),
    val completedDownloads: List<ActiveDownloadRow> = emptyList(),
    val selectedTab: DownloadsTab = DownloadsTab.ACTIVE,
) {
    val inProgressCount: Int
        get() = downloads.count { it.status == DownloadStatus.IN_PROGRESS }

    val pendingCount: Int
        get() = downloads.count { it.status == DownloadStatus.PENDING }

    val waitingCount: Int
        get() = downloads.count { it.status == DownloadStatus.WAITING_FOR_LOSSLESS }

    val totalCount: Int get() = downloads.size
}

/**
 * ViewModel for the Active Downloads screen. Subscribes to
 * [DownloadQueueDao.getActiveDownloads] and [DownloadQueueDao.getCompletedDownloads]
 * and maps the reactive Room flows into [ActiveDownloadsUiState].
 */
@HiltViewModel
class ActiveDownloadsViewModel @Inject constructor(
    private val downloadQueueDao: DownloadQueueDao,
) : ViewModel() {

    val uiState: Flow<ActiveDownloadsUiState> = combine(
        downloadQueueDao.getActiveDownloads(),
        downloadQueueDao.getCompletedDownloads(),
    ) { active, completed ->
        ActiveDownloadsUiState(
            isLoading = false,
            downloads = active,
            completedDownloads = completed,
        )
    }

    /** Cancel a single download by removing its queue entry. */
    fun cancelDownload(queueId: Long) {
        viewModelScope.launch {
            downloadQueueDao.deleteById(queueId)
        }
    }

    /** Delete all completed download entries. */
    fun clearCompleted() {
        viewModelScope.launch {
            downloadQueueDao.deleteCompleted()
        }
    }
}
