package com.stash.core.data.sync.classifier

/**
 * Phase of the download lifecycle a failure surfaced in. The classifier
 * uses phase to disambiguate identical HTTP statuses that mean different
 * things at different points (e.g. a 401 during MATCHING is a Spotify
 * cookie issue; during DOWNLOADING it's a YouTube cookie issue).
 */
enum class DownloadPhase { MATCHING, DOWNLOADING, PROCESSING, TAGGING, STORAGE }

/**
 * Input to [DownloadFailureClassifier.classify]. Pure data — no side
 * effects. Workers populate this from the raw failure they observed
 * (error text, optional HTTP status, optional cause chain).
 */
data class FailureContext(
    val phase: DownloadPhase,
    val errorText: String?,
    val httpStatus: Int? = null,
    val causeChain: List<String> = emptyList(),
)
