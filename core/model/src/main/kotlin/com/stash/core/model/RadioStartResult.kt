package com.stash.core.model

/**
 * Outcome of [com.stash.core.media.PlayerRepository.startRadio]. Replaces a
 * Boolean that collapsed distinct failures into one misleading toast (issue:
 * silent radio button). There is no "streaming off" outcome: the Download
 * switch decides what sync writes to disk, never whether a radio may play.
 */
sealed interface RadioStartResult {
    /** Station built and spliced/queued; the seed label is live. */
    data object Started : RadioStartResult

    /** MediaController unavailable (player still starting / connection lost). */
    data object PlayerNotReady : RadioStartResult

    /** Generator produced an empty first batch — no similar tracks found. */
    data object NoStation : RadioStartResult
}
