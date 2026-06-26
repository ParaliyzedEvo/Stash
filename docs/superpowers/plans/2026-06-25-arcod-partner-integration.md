# ARCOD Partner Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Credit ARCOD non-invasively in two places — a collapsible "Powered by ARCOD" strip on Home (expands to Ko-fi + Discord) that stays visually subordinate to the Supporter pill, and the ARCOD logo as the leading mark on the Audio & Quality connect row (+ a connected dot).

**Architecture:** Static, in-code, ARCOD-only. A small `ArcodPartner` data object + a pure links helper in `feature/home` (the only consumer of the URLs); a shared logo drawable in `core/ui`; a new `PartnerStrip` composable on Home; and a small backward-compatible extension to the shared `SettingsNavRow` (untinted leading slot + title-trailing slot, the latter mirroring `SettingsToggleRow`). No ViewModel/state-store/registry changes, no network, no Worker calls.

**Tech Stack:** Kotlin, Jetbrains Compose (Material3), Hilt-injected screens, JUnit4 + Truth (pure-logic test only — no Compose UI-test harness exists in these modules).

**Spec:** `docs/superpowers/specs/2026-06-25-arcod-partner-integration-design.md`

**Branch:** already on `feat/arcod-partner-integration`.

---

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `feature/home/.../home/ArcodPartner.kt` | Create | ARCOD constants (name/tagline/Ko-fi/Discord URLs) + `PartnerLink` model + pure `arcodPartnerLinks()` (omits blank URLs). |
| `feature/home/src/test/.../home/ArcodPartnerLinksTest.kt` | Create | Unit-test the links helper (the empty-URL → hide rule). |
| `core/ui/src/main/res/drawable/partner_arcod.xml` | Create | Shared ARCOD logo (placeholder vector until the real asset arrives). |
| `feature/home/.../home/PartnerStrip.kt` | Create | Collapsible "Powered by ARCOD" strip composable. |
| `feature/home/.../home/HomeScreen.kt` | Modify | Insert the strip as an `item {}` right after the Supporter pill item. |
| `feature/settings/.../components/SettingsNavRow.kt` | Modify | Add optional `leadingContent` (untinted slot) + `titleTrailing` slot; backward-compatible. |
| `feature/settings/.../SettingsAudioQualityScreen.kt` | Modify | Pass the ARCOD logo as `leadingContent` and a green connected dot as `titleTrailing` to the ARCOD connect row. |

**Placement note (refines the spec):** the spec suggested ARCOD metadata in `core/common`. Since *only Home* consumes the URLs/tagline (Settings reuses its existing hardcoded "Connect ARCOD" title + just the logo), the plan keeps the URL/tagline data in `feature/home` (YAGNI — no premature shared-module touch) and shares only the **logo** via `core/ui`. If a second partner/surface later needs the URLs, promote to `core/common` then.

## Notes for the implementer (read first)

- **Gradle (Windows):** use the daemon — do NOT pass `--no-daemon` (it BindExceptions on back-to-back runs here). Use `--tests` filters. If wedged, `./gradlew --stop` once then retry. (`./gradlew` in Git Bash, `.\gradlew` in PowerShell.)
- **No Compose UI-test harness** exists in `feature/home`/`feature/settings` — do NOT add one. Test the pure links helper with JUnit; verify composables via `:app:assembleDebug` + the on-device checklist at the end.
- **Deferred real inputs** (user-supplied, fill before release): the real ARCOD **logo** (replace the placeholder drawable), the real **Ko-fi** + **Discord** URLs, and the final **tagline**. The code is written so blank URLs simply hide their chip, so it compiles/runs with placeholders today.
- `core/ui` resources are referenced from features via `com.stash.core.ui.R`.
- Insertion anchor on Home: the Supporter pill `item {}` in `HomeScreen.kt` (around lines 255-269), immediately after the wordmark-row item.

---

## Task 1: ARCOD partner data + pure links helper (TDD)

**Files:**
- Create: `feature\home\src\main\kotlin\com\stash\feature\home\ArcodPartner.kt`
- Test: `feature\home\src\test\kotlin\com\stash\feature\home\ArcodPartnerLinksTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.stash.feature.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ArcodPartnerLinksTest {

    @Test fun `both urls present yields kofi then discord`() {
        val links = arcodPartnerLinks(kofiUrl = "https://ko-fi.com/arcod", discordUrl = "https://discord.gg/arcod")
        assertThat(links.map { it.kind }).containsExactly(PartnerLinkKind.KOFI, PartnerLinkKind.DISCORD).inOrder()
        assertThat(links.first().url).isEqualTo("https://ko-fi.com/arcod")
    }

    @Test fun `blank discord is omitted`() {
        val links = arcodPartnerLinks(kofiUrl = "https://ko-fi.com/arcod", discordUrl = "")
        assertThat(links.map { it.kind }).containsExactly(PartnerLinkKind.KOFI)
    }

    @Test fun `blank kofi is omitted`() {
        val links = arcodPartnerLinks(kofiUrl = "  ", discordUrl = "https://discord.gg/arcod")
        assertThat(links.map { it.kind }).containsExactly(PartnerLinkKind.DISCORD)
    }

    @Test fun `both blank yields empty`() {
        assertThat(arcodPartnerLinks(kofiUrl = "", discordUrl = "")).isEmpty()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :feature:home:testDebugUnitTest --tests "com.stash.feature.home.ArcodPartnerLinksTest"`
Expected: FAIL — `arcodPartnerLinks` / `PartnerLinkKind` / `PartnerLink` unresolved (compile error).

- [ ] **Step 3: Implement the data + helper**

```kotlin
package com.stash.feature.home

/**
 * ARCOD partner metadata + the Home strip's links. ARCOD-only, static; Home is the
 * sole consumer of these URLs (Settings only reuses the shared logo). Replace the
 * placeholder URLs/tagline with the operator-supplied values before release; blank
 * URLs are simply hidden (see [arcodPartnerLinks]).
 */
object ArcodPartner {
    const val NAME = "ARCOD"
    const val TAGLINE = "Lossless FLAC source · part of Stash's backbone"
    // TODO(input): real operator URLs. Blank = chip hidden until provided.
    const val KOFI_URL = ""
    const val DISCORD_URL = ""
}

enum class PartnerLinkKind { KOFI, DISCORD }

/** A resolved, openable partner link. */
data class PartnerLink(val kind: PartnerLinkKind, val url: String)

/**
 * The partner's openable links in display order (Ko-fi, then Discord), omitting any
 * whose URL is blank — so the strip degrades gracefully before the real URLs are set.
 */
fun arcodPartnerLinks(
    kofiUrl: String = ArcodPartner.KOFI_URL,
    discordUrl: String = ArcodPartner.DISCORD_URL,
): List<PartnerLink> = buildList {
    if (kofiUrl.isNotBlank()) add(PartnerLink(PartnerLinkKind.KOFI, kofiUrl.trim()))
    if (discordUrl.isNotBlank()) add(PartnerLink(PartnerLinkKind.DISCORD, discordUrl.trim()))
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :feature:home:testDebugUnitTest --tests "com.stash.feature.home.ArcodPartnerLinksTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add feature/home/src/main/kotlin/com/stash/feature/home/ArcodPartner.kt \
        feature/home/src/test/kotlin/com/stash/feature/home/ArcodPartnerLinksTest.kt
git commit -m "feat(home): ARCOD partner data + pure links helper

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: Shared ARCOD logo drawable (placeholder)

**Files:**
- Create: `core\ui\src\main\res\drawable\partner_arcod.xml`

- [ ] **Step 1: Create the placeholder vector**

`core/ui` has no `drawable/` dir yet — create it. This is a **placeholder** (a rounded square in the brand gradient) so everything compiles before the real asset; replace with the operator-supplied ARCOD logo before release.

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- PLACEHOLDER ARCOD logo. Replace with the operator-supplied asset before release. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:pathData="M5,2 L19,2 A3,3 0 0 1 22,5 L22,19 A3,3 0 0 1 19,22 L5,22 A3,3 0 0 1 2,19 L2,5 A3,3 0 0 1 5,2 Z"
        android:fillColor="#7C3AED" />
    <path
        android:pathData="M12,6 L17,18 L14.5,18 L13.6,15.6 L10.4,15.6 L9.5,18 L7,18 Z M11.1,13.6 L12.9,13.6 L12,11 Z"
        android:fillColor="#FFFFFF" />
</vector>
```

- [ ] **Step 2: Verify it compiles into resources**

Run: `./gradlew :core:ui:assembleDebug`
Expected: BUILD SUCCESSFUL (the new `drawable/` merges; `com.stash.core.ui.R.drawable.partner_arcod` will resolve in later tasks).

- [ ] **Step 3: Commit**

```bash
git add core/ui/src/main/res/drawable/partner_arcod.xml
git commit -m "feat(ui): shared ARCOD logo drawable (placeholder)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: Extend `SettingsNavRow` with leading + title-trailing slots

**Files:**
- Modify: `feature\settings\src\main\kotlin\com\stash\feature\settings\components\SettingsNavRow.kt`

Add two optional, backward-compatible slots: `leadingContent` (an **untinted** leading composable, for a color logo — takes precedence over the tinted `leadingIcon`) and `titleTrailing` (mirrors the existing `SettingsToggleRow.titleTrailing`, for the connected dot). Existing callers pass neither and are unchanged.

- [ ] **Step 1: Apply the edit**

Replace the function signature + leading/title rendering. New full file:

```kotlin
package com.stash.feature.settings.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.stash.core.ui.theme.StashTheme

/**
 * A navigation settings row: an optional leading icon/logo, a title (with an
 * optional trailing slot and subtitle), and a trailing chevron. Tapping anywhere
 * on the row invokes [onClick]. Pure presentation — the caller owns navigation.
 *
 * Leading precedence: [leadingContent] (rendered as-is, untinted — for a color
 * logo) wins over the monochrome tinted [leadingIcon].
 */
@Composable
fun SettingsNavRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    titleTrailing: (@Composable () -> Unit)? = null,
    subtitle: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = SettingsRowPadH, vertical = SettingsRowPadV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingContent != null) {
            leadingContent()
            Spacer(modifier = Modifier.width(12.dp))
        } else if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (titleTrailing != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    titleTrailing()
                }
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = StashTheme.extendedColors.textTertiary,
                )
            }
        }
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = StashTheme.extendedColors.textTertiary,
        )
    }
}
```

- [ ] **Step 2: Verify it compiles (existing call sites unaffected)**

Run: `./gradlew :feature:settings:compileDebugKotlin`
Expected: BUILD SUCCESSFUL — existing `SettingsNavRow(...)` callers (hub rows, etc.) still compile (new params default to null).

- [ ] **Step 3: Commit**

```bash
git add feature/settings/src/main/kotlin/com/stash/feature/settings/components/SettingsNavRow.kt
git commit -m "feat(settings): SettingsNavRow leading-logo + title-trailing slots

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: Brand the ARCOD connect row (Audio & Quality)

**Files:**
- Modify: `feature\settings\src\main\kotlin\com\stash\feature\settings\SettingsAudioQualityScreen.kt`

Give the existing ARCOD `SettingsNavRow` the logo as `leadingContent` and, when connected, a green dot as `titleTrailing`.

- [ ] **Step 1: Add imports**

At the top with the other imports:

```kotlin
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.stash.core.ui.theme.StashTheme
```
(`size` is needed for `.size(22.dp)`/`.size(7.dp)` below; the current file imports only `width`/`height`.)

- [ ] **Step 2: Replace the ARCOD `SettingsNavRow` call**

Find (around lines 135-143):

```kotlin
                        SettingsNavRow(
                            title = if (uiState.arcodConnected) {
                                "ARCOD — connected"
                            } else {
                                "Connect ARCOD"
                            },
                            subtitle = "Independent Qobuz lossless (3rd source)",
                            onClick = onNavigateToArcodConnect,
                        )
```

Replace with:

```kotlin
                        SettingsNavRow(
                            title = if (uiState.arcodConnected) {
                                "ARCOD — connected"
                            } else {
                                "Connect ARCOD"
                            },
                            subtitle = "Independent Qobuz lossless (3rd source)",
                            onClick = onNavigateToArcodConnect,
                            leadingContent = {
                                Image(
                                    painter = painterResource(
                                        id = com.stash.core.ui.R.drawable.partner_arcod,
                                    ),
                                    contentDescription = "ARCOD",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(RoundedCornerShape(6.dp)),
                                )
                            },
                            titleTrailing = if (uiState.arcodConnected) {
                                {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(StashTheme.extendedColors.success),
                                    )
                                }
                            } else {
                                null
                            },
                        )
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :feature:settings:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsAudioQualityScreen.kt
git commit -m "feat(settings): ARCOD logo + connected dot on the connect row

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 5: Home "Powered by ARCOD" strip

**Files:**
- Create: `feature\home\src\main\kotlin\com\stash\feature\home\PartnerStrip.kt`
- Modify: `feature\home\src\main\kotlin\com\stash\feature\home\HomeScreen.kt`

- [ ] **Step 1: Create the strip composable**

Subordinate styling (lower-opacity glass + thinner padding than the Supporter pill), collapsed by default, expands inline to the Ko-fi/Discord chips. Uses `LocalUriHandler` (same as the wordmark-row icons).

```kotlin
package com.stash.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stash.core.ui.theme.StashTheme

/**
 * Quiet, collapsible "Powered by ARCOD" credit for Home. Deliberately subordinate
 * to the Supporter pill above it (lower-opacity glass, thinner padding). Collapsed
 * by default; tapping the header expands inline to ARCOD's Ko-fi/Discord links.
 * Links with a blank URL ([arcodPartnerLinks]) are omitted, so the strip degrades
 * gracefully before the real URLs are configured.
 */
@Composable
fun PartnerStrip(modifier: Modifier = Modifier) {
    val extendedColors = StashTheme.extendedColors
    var expanded by remember { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        label = "partner-chevron",
    )
    val uriHandler = LocalUriHandler.current
    val links = remember { arcodPartnerLinks() }

    Surface(
        modifier = modifier,
        // Subordinate to the Supporter pill: ~3% vs the pill's glassBackground.
        color = Color(0x08FFFFFF),
        shape = RoundedCornerShape(13.dp),
        border = BorderStroke(1.dp, extendedColors.glassBorder),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            // Header row — taps toggle expansion.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(id = com.stash.core.ui.R.drawable.partner_arcod),
                    contentDescription = null,
                    modifier = Modifier
                        .size(18.dp)
                        .clip(RoundedCornerShape(5.dp)),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Powered by ",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = ArcodPartner.NAME,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = if (expanded) "Collapse ARCOD" else "Expand ARCOD",
                    modifier = Modifier
                        .size(18.dp)
                        .graphicsLayer(rotationZ = chevronRotation),
                    tint = extendedColors.textTertiary,
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = ArcodPartner.TAGLINE,
                        style = MaterialTheme.typography.bodySmall,
                        color = extendedColors.textTertiary,
                    )
                    if (links.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            links.forEach { link ->
                                PartnerChip(
                                    link = link,
                                    modifier = Modifier.weight(1f),
                                    onClick = { uriHandler.openUri(link.url) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PartnerChip(
    link: PartnerLink,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isKofi = link.kind == PartnerLinkKind.KOFI
    val accent = if (isKofi) Color(0xFFFF7E7B) else Color(0xFF8A93F5) // ko-fi coral / discord blurple
    Surface(
        modifier = modifier.clickable { onClick() },
        color = Color(0x0DFFFFFF),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.FavoriteBorder, // both chips: simple glyph; label carries meaning
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (isKofi) "Support on Ko-fi" else "Discord",
                style = MaterialTheme.typography.labelMedium,
                color = accent,
            )
        }
    }
}
```

- [ ] **Step 2: Insert the strip into `HomeScreen`**

In `HomeScreen.kt`, immediately AFTER the Supporter pill `item { … }` block (the one containing `SupporterPill(...)`, ends ~line 269), add:

```kotlin
        // ── Powered-by-ARCOD strip (subordinate to the supporter pill) ────
        item {
            PartnerStrip(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 12.dp),
            )
        }
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :feature:home:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add feature/home/src/main/kotlin/com/stash/feature/home/PartnerStrip.kt \
        feature/home/src/main/kotlin/com/stash/feature/home/HomeScreen.kt
git commit -m "feat(home): collapsible Powered-by-ARCOD strip

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 6: Full build + Hilt-graph verification

**Files:** none (verification only).

- [ ] **Step 1: Assemble the debug APK**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL — proves the cross-module references (`core.ui.R.drawable.partner_arcod` from both features, the `SettingsNavRow` slots, the new Home item) all link and the Hilt graph still compiles.

- [ ] **Step 2: Re-run the unit test**

Run: `./gradlew :feature:home:testDebugUnitTest --tests "com.stash.feature.home.ArcodPartnerLinksTest"`
Expected: PASS (4).

- [ ] **Step 3: Commit (only if a fix was needed)**

```bash
git add -A && git commit -m "fix(arcod-partner): build wiring

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## On-device verification (with the user — after the real inputs land)

Compose UI isn't unit-tested here, so confirm visually on device (install via `:app:installDebug`). Best done once the real logo + URLs are set, but layout/hierarchy can be checked with the placeholder first:

1. **Home:** the "Powered by ARCOD" strip appears directly below the Supporter pill and reads as *quieter/smaller* than it (hierarchy intact). Tapping expands to the chips; tapping again collapses.
2. **Chips:** Ko-fi chip opens ARCOD's Ko-fi; Discord chip opens ARCOD's Discord (once real URLs set). With blank URLs, the expanded strip shows just the tagline and no chips (graceful).
3. **Settings → Audio & Quality → Lossless:** the ARCOD connect row shows the ARCOD logo as its leading mark; when connected, the green dot appears after "ARCOD — connected".
4. **Theming:** check both dark and light themes — glass + text tokens adapt; the logo reads on both.

## Out of scope (v1)

- amz or any second partner; a generalized partner list.
- Ko-fi/Discord links anywhere in Settings.
- Remote/Worker-driven partner data or dynamic logos.
- Replacing the placeholder logo / filling real URLs — those are user-supplied inputs applied before release, not implementation tasks.
