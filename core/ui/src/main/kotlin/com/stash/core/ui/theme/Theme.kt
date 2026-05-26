package com.stash.core.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import com.stash.core.model.ThemeMode
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat

// ── Color schemes ────────────────────────────────────────────────────────

val StashDarkColorScheme = darkColorScheme(
    primary = StashPurple,
    onPrimary = Color.White,
    primaryContainer = StashPurpleDark,
    onPrimaryContainer = StashPurpleLight,
    secondary = StashCyan,
    onSecondary = Color.Black,
    secondaryContainer = StashCyanDark,
    onSecondaryContainer = StashCyanLight,
    tertiary = StashCyan,
    onTertiary = Color.Black,
    background = StashBackground,
    onBackground = StashTextPrimary,
    surface = StashSurface,
    onSurface = StashTextPrimary,
    surfaceVariant = StashElevatedSurface,
    onSurfaceVariant = StashTextSecondary,
    error = StashError,
    onError = Color.White,
    outline = StashGlassBorder,
    outlineVariant = StashGlassBorderBright,
)

val StashLightColorScheme = lightColorScheme(
    primary = StashPurpleDark,                   // deeper purple on light bg for contrast
    onPrimary = Color.White,
    primaryContainer = StashPurpleLight,
    onPrimaryContainer = StashPurpleDark,
    secondary = StashCyanDark,
    onSecondary = Color.White,
    secondaryContainer = StashCyanLight,
    onSecondaryContainer = StashCyanDark,
    tertiary = StashCyanDark,
    onTertiary = Color.White,
    background = StashBackgroundLight,
    onBackground = StashTextPrimaryLight,
    surface = StashSurfaceLight,
    onSurface = StashTextPrimaryLight,
    surfaceVariant = StashElevatedSurfaceLight,
    onSurfaceVariant = StashTextSecondaryLight,
    error = StashError,
    onError = Color.White,
    outline = StashGlassBorderLight,
    outlineVariant = StashGlassBorderBrightLight,
)

// ── LocalIsDarkTheme — queried by composables that need theme-aware assets
//    (e.g. the wordmark drawable selection in HomeScreen). ────────────────
//    Also compatible with official Material You UI theme guidelines.
val LocalIsDarkTheme = staticCompositionLocalOf { true }

/**
 * Root theme for the Stash app.
 *
 * Accepts an explicit [darkTheme] flag so the caller can wire in a user
 * preference (Light/Dark/System) instead of always following the OS.
 * When the caller wants to mirror the system, pass
 * `isSystemInDarkTheme()` — that's also the default for safety.
 *
 * Supports Android 12+ [dynamicColor] (Material You / Material UI Theme) 
 * dynamic system color schemes extracted from the wallpaper.
 *
 * Switching between schemes is done purely in Compose state — no activity
 * recreation, no resource configuration override — so the flip is instant
 * and any `animateColorAsState` wrappers can animate between the two sets.
 *
 * The system status-bar and navigation-bar icon colors are updated via a
 * [SideEffect] so they flip in sync with the in-app theme.
 */
@Composable
fun StashTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.AMOLED -> true
    }
    val context = LocalContext.current
    val colorScheme = when {
        themeMode == ThemeMode.AMOLED -> {
            val baseScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dynamicDarkColorScheme(context)
            } else {
                StashDarkColorScheme
            }
            baseScheme.copy(
                background = Color.Black,
                surface = Color.Black,
                surfaceVariant = Color(0xFF0C0C12),
            )
        }
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> StashDarkColorScheme
        else -> StashLightColorScheme
    }
    val extendedColors = StashExtendedColors(
        elevatedSurface = colorScheme.surfaceVariant,
        glassBackground = colorScheme.surfaceVariant.copy(alpha = 0.5f),
        glassBackgroundHover = colorScheme.surfaceVariant.copy(alpha = 0.7f),
        glassBorder = colorScheme.outline.copy(alpha = 0.12f),
        glassBorderBright = colorScheme.outline.copy(alpha = 0.24f),
        textTertiary = colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    )

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            val insetsController = WindowCompat.getInsetsController(window, view)
            // Light status-bar icons on dark theme, dark icons on light theme.
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(
        LocalStashColors provides extendedColors,
        LocalIsDarkTheme provides darkTheme,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = StashTypography,
            shapes = StashShapes,
            content = content,
        )
    }
}

object StashTheme {
    val extendedColors: StashExtendedColors
        @Composable
        get() = LocalStashColors.current
}
