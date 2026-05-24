package com.stash.feature.nowplaying.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.stash.data.lyrics.parser.LrcLine

/**
 * v0.9.36 Task 12 — renderers for the lyrics sheet.
 *
 * The sheet wrapper ([LyricsBottomSheet]) dispatches into one of these
 * composables based on [LyricsViewState]. Kept separate from the sheet
 * so the synced renderer's auto-scroll + tap-to-seek behaviour can be
 * tested visually (and, in future, swapped for a different presentation
 * without touching the sheet wiring).
 */

/**
 * Auto-scrolling LRC renderer. Centers the current line, dims surrounding
 * lines, taps to seek. Pauses auto-scroll for 5 seconds after the user
 * drags the list — without that grace period the auto-scroll fights any
 * attempt to look ahead/behind the playing line.
 *
 * The "current line" is the latest line whose timestamp is `<= positionMs`,
 * computed via [derivedStateOf] so we only recompose when the index
 * actually flips (typical position ticks at 250ms produce a flip every
 * line — usually a few seconds — not every tick).
 */
@Composable
fun LyricsSyncedRenderer(
    lines: List<LrcLine>,
    currentPositionMs: Long,
    onLineTap: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val currentIndex by remember(lines) {
        derivedStateOf {
            lines.indexOfLast { it.timestampMs <= currentPositionMs }.coerceAtLeast(0)
        }
    }

    // User-drag guard: any time the user manually scrolls, freeze the
    // auto-scroll for 5 seconds so they can browse without the current
    // line yanking them back. Tracked as a Long epoch-millis stamp so a
    // subsequent currentIndex change inside the grace window short-
    // circuits the animateScrollToItem call.
    var lastUserScrollAtMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            lastUserScrollAtMs = System.currentTimeMillis()
        }
    }

    LaunchedEffect(currentIndex, lines) {
        if (lines.isEmpty()) return@LaunchedEffect
        if (System.currentTimeMillis() - lastUserScrollAtMs > USER_SCROLL_GRACE_MS) {
            // Negative offset pulls the row up so the current line sits
            // roughly in the upper third of the visible area — matches
            // the Spotify-style "next-up" reading rhythm.
            runCatching {
                listState.animateScrollToItem(
                    index = currentIndex.coerceAtMost(lines.lastIndex),
                    scrollOffset = AUTO_SCROLL_OFFSET_PX,
                )
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 24.dp),
    ) {
        itemsIndexed(lines) { index, line ->
            val current = index == currentIndex
            Text(
                text = line.text,
                style = if (current) {
                    MaterialTheme.typography.headlineMedium
                } else {
                    MaterialTheme.typography.bodyLarge
                },
                color = if (current) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onLineTap(line.timestampMs) }
                    .padding(vertical = 8.dp, horizontal = 24.dp),
            )
        }
    }
}

/**
 * Plain-text fallback for tracks without an LRC body. Scrollable so the
 * full body is reachable even on short sheet heights.
 */
@Composable
fun LyricsPlainRenderer(text: String, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 24.dp, vertical = 24.dp),
    )
}

/** Loading state — spinner with a short caption. */
@Composable
internal fun CenteredSpinner(label: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Generic "nothing to render here" placard for [LyricsViewState.None],
 * [LyricsViewState.Instrumental], and [LyricsViewState.Error]. Optional
 * action button (typically "Retry") when [action] is non-null.
 */
@Composable
internal fun CenteredPlacard(
    label: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (action != null) {
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onAction) {
                    Text(action)
                }
            }
        }
    }
}

/**
 * Auto-scroll grace window after a user drag. Long enough that a quick
 * "let me look at the next verse" pan doesn't immediately get snapped
 * back, short enough that a forgotten-about scroll doesn't strand the
 * highlight off-screen.
 */
private const val USER_SCROLL_GRACE_MS = 5_000L

/**
 * Item-scroll offset for the auto-scroll target. Negative pulls the
 * highlighted row above center so the upcoming line is visible.
 */
private const val AUTO_SCROLL_OFFSET_PX = -200
