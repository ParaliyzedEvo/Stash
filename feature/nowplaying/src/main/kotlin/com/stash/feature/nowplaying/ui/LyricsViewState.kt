package com.stash.feature.nowplaying.ui

import com.stash.core.data.db.entity.LyricsEntity
import com.stash.core.model.Track
import com.stash.data.lyrics.parser.LrcLine
import com.stash.data.lyrics.parser.LrcParser
import com.stash.data.lyrics.source.LyricsResult

/**
 * v0.9.36 Task 12 — Now Playing lyrics sheet view state.
 *
 * Sealed hierarchy the [LyricsBottomSheet] renders one-of:
 *  - [Loading]      Track has never been tried (`lyricsFetchedAt == null`).
 *                   The sheet enqueues a priority worker on open and waits.
 *  - [Synced]       LRC parsed into a non-empty [LrcLine] list. Used by
 *                   [LyricsSyncedRenderer] for the auto-scroll + tap-to-seek
 *                   experience. Carries [plainFallback] so the renderer can
 *                   degrade gracefully without re-fetching if it ever needs
 *                   to render plain text.
 *  - [Plain]        Un-timed lyrics only (or synced text that didn't parse
 *                   and there's a plain-text body to fall back to).
 *  - [Instrumental] Source confirmed the track has no lyrics. Distinct
 *                   from [None] because it's a positive answer, not a miss.
 *  - [None]         Either the fetch ran and every source definitively
 *                   answered "no lyrics" (`lyricsFetchedAt == 0L`), or the
 *                   row has no usable text. Sheet shows a Retry CTA wired
 *                   to [LyricsBottomSheet]'s `onRetry`.
 *  - [Error]        The fetch FAILED (network/HTTP/parse — sources threw)
 *                   rather than missed. Not produced by these mappers: the
 *                   ViewModel emits it directly — for library tracks via the
 *                   failed-fetch overlay (stamp stays NULL so the track
 *                   remains retryable), for streaming tracks when
 *                   `resolveTransient` throws. Distinct from [None] so a
 *                   flaky connection reads "Couldn't load" + Retry, not the
 *                   false-permanent "No lyrics found".
 */
sealed interface LyricsViewState {
    object Loading : LyricsViewState
    data class Synced(val lines: List<LrcLine>, val plainFallback: String) : LyricsViewState
    data class Plain(val text: String) : LyricsViewState
    object Instrumental : LyricsViewState
    object None : LyricsViewState
    data class Error(val retryable: Boolean) : LyricsViewState
}

/**
 * Pure derivation from the canonical pair (current [Track], observed
 * [LyricsEntity]?). Drives [LyricsBottomSheet]'s render via
 * [com.stash.feature.nowplaying.NowPlayingViewModel.lyricsViewState].
 *
 * Order is significant — the sentinel check on `lyricsFetchedAt` runs
 * first so a NULL stamp always renders Loading even if a stale row
 * lingers from a previous fetch (shouldn't happen, but defensive).
 */
internal fun lyricsViewStateFor(track: Track, row: LyricsEntity?): LyricsViewState = when {
    // Row has actual lyrics data — render it immediately regardless of whether
    // the track's lyricsFetchedAt stamp has propagated to the UI state yet.
    // The Room observer for the lyrics entity fires before the tracks-table
    // observer does, so the outer flatMapLatest capture may still hold a stale
    // `lyricsFetchedAt == null` Track — checking the stamp first caused lyrics
    // to stay stuck on "Loading" even though the row was already written.
    row?.instrumental == true -> LyricsViewState.Instrumental
    row?.syncedLrc != null -> {
        val synced = row.syncedLrc!!
        val lines = LrcParser.parse(synced)
        val plain = row.plainText
        when {
            lines.isNotEmpty() -> LyricsViewState.Synced(lines, plain.orEmpty())
            !plain.isNullOrBlank() -> LyricsViewState.Plain(plain)
            else -> LyricsViewState.None
        }
    }
    row != null && !row.plainText.isNullOrBlank() -> LyricsViewState.Plain(row.plainText!!)
    // No usable row — fall back to the sentinel to distinguish Loading from None.
    track.lyricsFetchedAt == null -> LyricsViewState.Loading
    track.lyricsFetchedAt == 0L -> LyricsViewState.None
    else -> LyricsViewState.None
}

/**
 * Result-only mapper for the streaming-track path. Streaming-mode tracks
 * have no persistent `tracks` row (id == 0L), so the ViewModel calls
 * [com.stash.data.lyrics.LyricsRepository.resolveTransient] directly and
 * feeds the raw [LyricsResult] through this mapper instead of going via
 * Room. Logic mirrors [lyricsViewStateFor] (instrumental / synced /
 * plain / none) minus the sentinel handling — a null result means
 * "miss," not "never tried."
 */
internal fun lyricsViewStateForResult(result: LyricsResult?): LyricsViewState = when {
    result == null -> LyricsViewState.None
    result.instrumental -> LyricsViewState.Instrumental
    result.syncedLrc != null -> {
        val synced = result.syncedLrc!!
        val lines = LrcParser.parse(synced)
        val plain = result.plainText
        when {
            lines.isNotEmpty() -> LyricsViewState.Synced(lines, plain.orEmpty())
            !plain.isNullOrBlank() -> LyricsViewState.Plain(plain)
            else -> LyricsViewState.None
        }
    }
    !result.plainText.isNullOrBlank() -> LyricsViewState.Plain(result.plainText!!)
    else -> LyricsViewState.None
}
