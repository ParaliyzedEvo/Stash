package com.stash.core.model

/**
 * Why a download failed. NONE for not-yet-failed rows; NO_MATCH owned by the
 * matching layer and surfaced in FailedMatchesScreen. The remaining buckets
 * are produced by [DownloadFailureClassifier] in the data/download module and
 * surfaced in FailedDownloadsScreen.
 */
enum class DownloadFailureType {
    NONE,
    NO_MATCH,
    AUTH_EXPIRED,
    NETWORK,
    PROVIDER_UNAVAILABLE,
    FFMPEG_ERROR,
    STORAGE_ERROR,
    UNKNOWN,
}
