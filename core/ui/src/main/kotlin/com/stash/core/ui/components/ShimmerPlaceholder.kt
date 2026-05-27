// core/ui/src/main/kotlin/com/stash/core/ui/components/ShimmerPlaceholder.kt
package com.stash.core.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.RectangleShape

@Composable
fun ShimmerPlaceholder(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    baseColor: Color = Color.White.copy(alpha = 0.05f),
    highlightColor: Color = Color.White.copy(alpha = 0.16f),
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "translation",
    )

    val shimmerBrush = Brush.linearGradient(
        colors = listOf(
            baseColor,
            highlightColor,
            baseColor,
        ),
        start = androidx.compose.ui.geometry.Offset(translateAnim - 250f, 0f),
        end = androidx.compose.ui.geometry.Offset(translateAnim, 250f)
    )

    Box(
        modifier = modifier
            .clip(shape)
            .background(shimmerBrush),
    )
}
