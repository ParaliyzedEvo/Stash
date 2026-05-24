package com.stash.feature.nowplaying.ui

import com.stash.core.data.db.entity.LyricsEntity
import com.stash.core.model.Track
import com.stash.data.lyrics.parser.LrcLine
import com.stash.data.lyrics.parser.LrcParser

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
 *  - [None]         Either the worker tried and missed (`lyricsFetchedAt == 0L`),
 *                   or the row has no usable text. Sheet shows a Retry CTA
 *                   wired to [LyricsBottomSheet]'s `onRetry`.
 *  - [Error]        Reserved for future surfaces (HTTP failure UX, etc.);
 *                   not currently emitted by the mapper — the post-download
 *                   and on-open paths both fall through to NULL/0L sentinels
 *                   handled by Loading/None, so a true Error state would
 *                   require the worker to expose a separate signal. Kept
 *                   here so the sheet renderer's `when` exhausts the surface
 *                   the spec defines.
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
    track.lyricsFetchedAt == null -> LyricsViewState.Loading
    track.lyricsFetchedAt == 0L -> LyricsViewState.None
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
    else -> LyricsViewState.None
}
