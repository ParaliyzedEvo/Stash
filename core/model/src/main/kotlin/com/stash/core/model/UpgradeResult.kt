package com.stash.core.model

/**
 * Outcome of a user-initiated lossless upgrade attempt
 * (Now Playing → "Find in FLAC"). Drives the snackbar copy in
 * [com.stash.feature.nowplaying.NowPlayingViewModel].
 *
 * Distinct from sync-pipeline outcomes (`TrackDownloadOutcome`)
 * because the user-facing language differs ("Upgraded" vs.
 * "Downloaded") and because no queue row is involved on this path.
 */
sealed interface UpgradeResult {
    /** Lossless source served a match; file was replaced and the row was updated. */
    data object Upgraded : UpgradeResult

    /** Sources are reachable but no candidate cleared the confidence threshold. */
    data object NoMatch : UpgradeResult

    /**
     * Caught exception during resolve/download — network, captcha-required,
     * registry threw, finalize failed. Generic enough to cover all of them
     * without leaking implementation detail to the snackbar.
     */
    data object Error : UpgradeResult
}
