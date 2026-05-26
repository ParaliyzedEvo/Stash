package com.stash.feature.sync

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.stash.core.model.DownloadFailureType

data class FailureReasonDisplay(
    val icon: ImageVector,
    val tint: Color,
    val groupTitle: String,
    val shortLabel: String,
)

/**
 * Display ordering: high-leverage groups (one fix repairs many rows) first.
 * Groups with zero count are filtered out of the UI.
 */
val FailureReasonDisplayOrder: List<DownloadFailureType> = listOf(
    DownloadFailureType.AUTH_EXPIRED,
    DownloadFailureType.STORAGE_ERROR,
    DownloadFailureType.NETWORK,
    DownloadFailureType.PROVIDER_UNAVAILABLE,
    DownloadFailureType.FFMPEG_ERROR,
    DownloadFailureType.UNKNOWN,
)

fun DownloadFailureType.display(): FailureReasonDisplay = when (this) {
    DownloadFailureType.AUTH_EXPIRED -> FailureReasonDisplay(
        icon = Icons.Default.Key,
        tint = Color(0xFFFFAA00),
        groupTitle = "Sign-in expired",
        shortLabel = "Sign-in expired",
    )
    DownloadFailureType.STORAGE_ERROR -> FailureReasonDisplay(
        icon = Icons.Default.Folder,
        tint = Color(0xFF888888),
        groupTitle = "Storage unreachable",
        shortLabel = "Storage error",
    )
    DownloadFailureType.NETWORK -> FailureReasonDisplay(
        icon = Icons.Default.WifiOff,
        tint = Color(0xFF508CFF),
        groupTitle = "Network errors",
        shortLabel = "Network error",
    )
    DownloadFailureType.PROVIDER_UNAVAILABLE -> FailureReasonDisplay(
        icon = Icons.Default.CloudOff,
        tint = Color(0xFFAA66CC),
        groupTitle = "Source unavailable",
        shortLabel = "Source unavailable",
    )
    DownloadFailureType.FFMPEG_ERROR -> FailureReasonDisplay(
        icon = Icons.Default.Settings,
        tint = Color(0xFFFF5A5A),
        groupTitle = "Encoding errors",
        shortLabel = "Encoding error",
    )
    DownloadFailureType.UNKNOWN -> FailureReasonDisplay(
        icon = Icons.Default.Help,
        tint = Color(0xFFAAAAAA),
        groupTitle = "Other errors",
        shortLabel = "Unknown error",
    )
    DownloadFailureType.NONE,
    DownloadFailureType.NO_MATCH -> error("Not surfaced in FailedDownloadsScreen")
}
