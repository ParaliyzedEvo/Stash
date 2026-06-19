package com.stash.core.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay

/**
 * Fade-in/fade-out scrollbar for [LazyListState] (Tracks tab).
 *
 * Shows a thin thumb on the trailing edge while scrolling, then fades it
 * out after [idleDelayMs] of no scroll activity. Purely a draw-layer
 * overlay — doesn't consume touch input, doesn't affect layout.
 */
fun Modifier.verticalScrollbar(
    state: LazyListState,
    color: Color = Color.White.copy(alpha = 0.4f),
    width: Float = 4f,
    idleDelayMs: Long = 800L,
): Modifier = scrollbarImpl(
    isScrollInProgress = { state.isScrollInProgress },
    scrollFraction = {
        val layout = state.layoutInfo
        val totalItems = layout.totalItemsCount
        if (totalItems == 0 || layout.visibleItemsInfo.isEmpty()) return@scrollbarImpl null
        val firstVisible = layout.visibleItemsInfo.first()
        val avgItemSize = layout.visibleItemsInfo.sumOf { it.size }.toFloat() / layout.visibleItemsInfo.size
        val estimatedTotalHeight = avgItemSize * totalItems
        if (estimatedTotalHeight <= layout.viewportEndOffset) return@scrollbarImpl null
        val scrolledPx = firstVisible.index * avgItemSize - firstVisible.offset
        val maxScrollPx = estimatedTotalHeight - layout.viewportEndOffset
        val thumbFraction = layout.viewportEndOffset / estimatedTotalHeight
        (scrolledPx / maxScrollPx).coerceIn(0f, 1f) to thumbFraction.coerceIn(0.05f, 1f)
    },
    color = color,
    width = width,
    idleDelayMs = idleDelayMs,
)

/**
 * Fade-in/fade-out scrollbar for [LazyGridState] (Playlists/Artists/Albums
 * grids). Same behavior as [verticalScrollbar] but reads row-based grid
 * layout info instead of list item info.
 */
fun Modifier.verticalScrollbar(
    state: LazyGridState,
    color: Color = Color.White.copy(alpha = 0.4f),
    width: Float = 4f,
    idleDelayMs: Long = 800L,
): Modifier = scrollbarImpl(
    isScrollInProgress = { state.isScrollInProgress },
    scrollFraction = {
        val layout = state.layoutInfo
        val totalItems = layout.totalItemsCount
        if (totalItems == 0 || layout.visibleItemsInfo.isEmpty()) return@scrollbarImpl null
        val columns = layout.visibleItemsInfo.map { it.row }.distinct().size.coerceAtLeast(1)
        val totalRows = (totalItems + columns - 1) / columns
        val firstVisible = layout.visibleItemsInfo.first()
        val avgItemHeight = layout.visibleItemsInfo
            .map { it.size.height }
            .average()
            .toFloat()
        val estimatedTotalHeight = avgItemHeight * totalRows
        if (estimatedTotalHeight <= layout.viewportEndOffset) return@scrollbarImpl null
        val firstRow = firstVisible.row
        val scrolledPx = firstRow * avgItemHeight - firstVisible.offset.y
        val maxScrollPx = estimatedTotalHeight - layout.viewportEndOffset
        val thumbFraction = layout.viewportEndOffset / estimatedTotalHeight
        (scrolledPx / maxScrollPx).coerceIn(0f, 1f) to thumbFraction.coerceIn(0.05f, 1f)
    },
    color = color,
    width = width,
    idleDelayMs = idleDelayMs,
)

/**
 * Shared draw + fade logic. [scrollFraction] returns
 * (scrollProgress 0..1, thumbSizeFraction 0..1) or null when there's
 * nothing to scroll (content fits the viewport).
 */
private fun Modifier.scrollbarImpl(
    isScrollInProgress: () -> Boolean,
    scrollFraction: () -> Pair<Float, Float>?,
    color: Color,
    width: Float,
    idleDelayMs: Long,
): Modifier = composed {
    var alpha by remember { mutableStateOf(0f) }
    val animatedAlpha by animateFloatAsState(
        targetValue = alpha,
        animationSpec = tween(durationMillis = 200),
        label = "scrollbarAlpha",
    )

    val scrolling = isScrollInProgress()
    LaunchedEffect(scrolling) {
        if (scrolling) {
            alpha = 1f
        } else {
            delay(idleDelayMs)
            alpha = 0f
        }
    }

    this.drawWithContent {
        drawContent()
        if (animatedAlpha <= 0.001f) return@drawWithContent
        val result = scrollFraction() ?: return@drawWithContent
        val (progress, thumbFraction) = result
        val trackHeight = size.height
        val thumbHeight = trackHeight * thumbFraction
        val maxThumbOffset = trackHeight - thumbHeight
        val thumbOffsetY = progress * maxThumbOffset

        drawRoundRect(
            color = color.copy(alpha = color.alpha * animatedAlpha),
            topLeft = Offset(size.width - width - 2f, thumbOffsetY),
            size = Size(width, thumbHeight),
            cornerRadius = CornerRadius(width / 2f, width / 2f),
        )
    }
}