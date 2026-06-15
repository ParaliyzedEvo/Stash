package com.stash.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// ── M3 shape scale ────────────────────────────────────────────────────────
// Follows the Material Design 3 shape scale specification:
// https://m3.material.io/styles/shape/shape-scale-tokens
//
// extraSmall  →  components like text fields, menus, tooltips
// small       →  chips, small buttons, snackbars
// medium      →  cards, dialogs, bottom sheets (default)
// large       →  navigation drawers, side sheets
// extraLarge  →  full-screen dialogs, large bottom sheets
val StashShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small      = RoundedCornerShape(8.dp),
    medium     = RoundedCornerShape(12.dp),
    large      = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
