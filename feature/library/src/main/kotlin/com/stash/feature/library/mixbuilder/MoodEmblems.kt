package com.stash.feature.library.mixbuilder

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Celebration
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Grain
import androidx.compose.ui.graphics.vector.ImageVector

/** Mood id -> emblem icon, in Stash's Material-outlined icon language. */
val MoodEmblems: Map<String, ImageVector> = mapOf(
    "chill" to Icons.Outlined.Bedtime,
    "energetic" to Icons.Outlined.Bolt,
    "focus" to Icons.Outlined.CenterFocusStrong,
    "party" to Icons.Outlined.Celebration,
    "melancholy" to Icons.Outlined.Grain,
    "romantic" to Icons.Outlined.Favorite,
)

/** Title-case label for a mood id. */
fun moodLabel(key: String): String = key.replaceFirstChar { it.uppercase() }
