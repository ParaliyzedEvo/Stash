# ARCOD partner integration — Home credit + Settings logo — Design

**Date:** 2026-06-25
**Status:** Design (approved in brainstorm 2026-06-25; pending spec review)
**Branch:** `feat/arcod-partner-integration` (off master @ v0.9.57 + the unpushed ARCOD-streaming commit)

## Summary

Surface ARCOD as a credited provider in two non-invasive places, in Stash's
existing glass aesthetic:

1. **Home** — a quiet, collapsible "Powered by **ARCOD**" strip directly below the
   Supporter pill, deliberately *subordinate* to it (Stash's own donation
   placement stays dominant). Tapping it expands inline to ARCOD's **Ko-fi** and
   **Discord** links.
2. **Settings → Audio & Quality → Lossless card** — the existing ARCOD connect row
   gets ARCOD's **logo** as its leading mark (seamless branding at the connect
   point), plus a small "connected" status dot. No links here.

Scope is **ARCOD only**. No multi-partner scaffolding, no remote data, no Worker
calls. Adding a second provider (amz) later is a small follow-up, not built now.

## Design-system grounding (verified)

- `GlassCard` (`core/ui`): translucent surface (`extendedColors.glassBackground`)
  + 1dp `glassBorder`, `MaterialTheme.shapes.large`, 16dp padding. The standard card.
- Palette (`core/ui/theme/Color.kt`): brand `StashPurple #8B5CF6` / `StashCyan
  #06B6D4`; text primary `#E8E8F0`, secondary `#A0A0B8`, tertiary `#606078`;
  Discord blurple `#5865F2` is already used on Home.
- Home (`feature/home/HomeScreen.kt`) is a `LazyColumn` of `item {}` blocks:
  wordmark row (Discord + GitHub icons, opened via `LocalUriHandler`), then the
  full-width `SupporterPill`, then content. `STASH_DISCORD_URL` is a top-level
  const in that file.
- Settings is hub-and-spoke. The ARCOD connect control is a `SettingsNavRow`
  inside the Lossless `GlassCard` of `SettingsAudioQualityScreen.kt`
  (title `Connect ARCOD` / `ARCOD — connected`, subtitle "Independent Qobuz
  lossless (3rd source)", `onClick = onNavigateToArcodConnect`).
- `SettingsNavRow` currently accepts only a monochrome **tinted** `leadingIcon:
  ImageVector?` (18dp, tinted `onSurfaceVariant`) — unsuitable for a full-color
  logo, so a small extension is needed (below).
- Shared constants live in `core/common/.../constants/StashConstants.kt`.
  `core/ui` has a `res/` dir but no `drawable/` yet.

## Data model (static, in-code)

A single ARCOD partner definition — no list/registry abstraction:

- **Metadata** in `core/common` (e.g. an `ArcodPartner` object in / beside
  `StashConstants`): `name = "ARCOD"`, `tagline` (draft: *"Lossless FLAC source ·
  part of Stash's backbone"* — final wording TBD by user), `kofiUrl`,
  `discordUrl`. URLs are placeholders until the user supplies the real ones;
  treat empty URL → hide/disable that one link.
- **Logo** as a shared drawable `partner_arcod` in **`core/ui/src/main/res/drawable/`**
  (both `feature/home` and `feature/settings` depend on `core/ui`), referenced via
  `com.stash.core.ui.R`. Vector preferred; PNG acceptable. Asset supplied by user.

## Component 1 — Home "Powered by ARCOD" strip

New self-contained composable in `feature/home` (e.g. `PartnerStrip.kt`), added as a
`LazyColumn` `item {}` in `HomeScreen` **immediately after the Supporter pill item**.

**Visual hierarchy (critical):** strip is quieter than the Supporter pill —
thinner padding, lower-opacity glass (`~rgba(255,255,255,0.03)` bg, `~0.08` border),
smaller type. It must never read as more prominent than the donation pill.

**States (local `remember { mutableStateOf(false) }` — `expanded`):**
- **Collapsed (default):** one row — small ARCOD logo (~20dp, rounded, untinted) +
  "Powered by **ARCOD**" (secondary text, "ARCOD" emphasized) + trailing chevron
  (`⌄`). Tapping the row toggles `expanded`. Animate with
  `animateFloatAsState` chevron rotation + `AnimatedVisibility` (matching the
  Audio screen's existing expander idiom).
- **Expanded:** logo + name + tagline, then two chips/buttons in a row:
  - **♥ Support on Ko-fi** → `uriHandler.openUri(kofiUrl)` (Ko-fi coral accent).
  - **✦ Discord** → `uriHandler.openUri(discordUrl)` (blurple `#5865F2`).
  Each chip is a `clickable` glass pill; opening uses the existing
  `LocalUriHandler` pattern already used by the wordmark-row icons.

No ViewModel/state-store changes — purely presentational + local expand state.

## Component 2 — Settings branded connect row

**Extend `SettingsNavRow`** (`feature/settings/components`) with an optional
**untinted leading slot** so a color logo can render. Minimal, backward-compatible:
add a parameter such as `leadingContent: (@Composable () -> Unit)? = null` (or
`leadingPainter: Painter? = null` rendered untinted at ~22dp, rounded). Existing
callers (the hub rows using `leadingIcon`) are unchanged; when `leadingContent` is
provided it takes precedence over `leadingIcon`.

**Apply in `SettingsAudioQualityScreen`:** the ARCOD connect `SettingsNavRow` passes
the ARCOD logo as its leading mark in **both** states (`Connect ARCOD` /
`ARCOD — connected`). When `uiState.arcodConnected`, append a small **green dot**
(theme-aware `StashTheme.extendedColors.success` — `#10B981` in dark) after the
title as a connected cue. Title/subtitle/onClick unchanged. No Ko-fi/Discord here.

## Files

| File | Change |
|---|---|
| `core/ui/src/main/res/drawable/partner_arcod.*` | Create — shared ARCOD logo asset (user-supplied). |
| `core/common/.../constants/StashConstants.kt` (or new `ArcodPartner.kt`) | Add — ARCOD name/tagline/Ko-fi/Discord constants. |
| `feature/home/.../PartnerStrip.kt` | Create — collapsible strip composable. |
| `feature/home/.../HomeScreen.kt` | Modify — add the strip as an `item {}` after the Supporter pill. |
| `feature/settings/components/SettingsNavRow.kt` | Modify — add the optional untinted leading slot. |
| `feature/settings/.../SettingsAudioQualityScreen.kt` | Modify — pass the ARCOD logo (+ connected dot) to the connect row. |

## Testing strategy

- `PartnerStrip`: Compose UI test (or Robolectric) — collapsed shows "Powered by
  ARCOD" + no link chips; after a click, the Ko-fi + Discord chips appear; a chip
  click invokes the URL callback with the right URL. (Inject URLs/handler so the
  test asserts behavior, not a real browser.)
- `SettingsNavRow`: render with `leadingContent` → the slot composable shows and the
  tinted-icon path is bypassed; existing `leadingIcon` path still renders. Chevron +
  onClick unchanged.
- Audio & Quality: the ARCOD row renders the logo in both states; the green dot
  shows only when `arcodConnected`.
- No ViewModel logic changes → no new unit-test surface there.

## Accepted limitations / out of scope (v1)

- ARCOD only — no amz or any second partner; no generalized partner list.
- No Ko-fi/Discord links in Settings (Home carries them).
- No remote/Worker-driven partner data.
- Logo is a static bundled asset; no dynamic/remote logo.

## Inputs required before implementation (non-blocking for the spec)

1. Real **ARCOD logo** asset (vector preferred).
2. ARCOD's real **Ko-fi URL** and **Discord invite URL**.
3. Final **tagline** wording (draft above).
