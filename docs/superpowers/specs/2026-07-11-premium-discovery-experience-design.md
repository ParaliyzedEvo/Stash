# Premium Discovery Experience — Phase 1 (Design)

**Part of a two-phase effort to make the discovery surface — Search tab → artist
profile → album page — feel premium and structured ("the heart of the app").**
Phase 1 (this spec) is the **experience/UI** layer. Phase 2 (separate spec, later)
is **discography data quality** (wrong-artist Qobuz gap-fills + album sort).

**Theme: refinement, not restructure.** Every layout decision below was chosen by
the user against more aggressive alternatives (immersive hero, grid discography,
contextual prompts). The premium feel comes from cleaner interaction and consistent
states — not a new information architecture.

---

## 1. Goals & non-goals

**Goals**
- Replace the cluttered inline `▶ / ⭳ / ⋮` song row with a single **tap-to-play
  card**, keeping one-tap download (Stash is download-first).
- Make the whole song row a play affordance (today the row body does nothing).
- Give the Search tab an in-place **Online/Offline switch** so an offline user can
  stream without backing out to Home.
- Apply the new row consistently across Search results, artist "Popular", and album
  tracklists; polish spacing/typography/state feedback.
- **Make the preview start fast** — resolve its FLAC via the rate-limiter-bypass
  path (not the background rate-limited one) on a user tap (see §6).

**Non-goals (Phase 1)**
- No artist-page restructure — keep hero → Popular → Albums row → Singles/EPs row →
  Fans also like (horizontal rows, compact hero). Chosen deliberately.
- No discography-quality fix (wrong albums, sort) — that's Phase 2.
- No change to the streaming resolver, download pipeline, or player.
- Keep the existing 30s-preview-on-tap *behavior* when Offline (the chip is the escape
  hatch). We change how fast the preview *resolves* (§6), not what a tap does.

---

## 2. The shared song row (`SongRow`)

The atom of the whole surface. Today it is `PreviewDownloadRow`
(`feature/search/.../PreviewDownloadRow.kt`), used by three screens. It gains a new
role and a clearer name: **`SongRow`** (no more standalone preview button, so
"PreviewDownloadRow" is misleading).

**Behavior (the user's chosen "C"):**
- **Whole row is clickable → plays the track.** This is exactly the action the old
  `▶` button triggered: `TrackActionsDelegate.previewTrack` → full stream when Online
  (`playFromStream`), 30s preview when Offline. The standalone `▶` is removed.
- **Download stays one-tap on the row**, with three visual states:
  - idle → `⭳` (download)
  - downloading → spinner
  - downloaded → green `✓`
  Hidden entirely where download is unsupported (Qobuz-native album rows, Phase-1
  limitation — see §4).
- **`⋮` overflow** keeps the secondary actions unchanged: Play next, Add to queue,
  Start radio, Add to playlist.
- **Now-playing indicator (premium touch):** when this row's track is the one
  currently playing, show a subtle accent tint + a small equalizer/▮▮ glyph in place
  of/next to the art. Keyed on the same identity the player uses.

**Row anatomy (left → right):** album-art thumbnail · title + "artist · duration"
(two lines, ellipsized) · download affordance (state-driven) · `⋮`.

**Tap-target discipline:** the row's outer `clickable` plays; the download button and
`⋮` are inner clickables that consume their own taps (same pattern the code already
uses for the inline buttons), so tapping a control never also fires play.

**Now-playing identity (pinned):** the host screen reads
`PlayerRepository.playerState.currentTrack` and passes `currentTrack.youtubeId` (a
`String?`) to `SongRow`. The row is "active" iff
`item.videoId.isNotBlank() && item.videoId == currentPlayingYoutubeId`. This is the one
comparison — no hashCode arithmetic. Blank-videoId Qobuz-native rows never match (their
`videoId` is `""`), which is correct.

**Files:** rename `PreviewDownloadRow.kt` → `SongRow.kt` (composable + call sites in
`SearchScreen.kt`, `PopularTracksSection.kt`, `AlbumDiscoveryScreen.kt`). The
`onPreview` param becomes the row's `onPlay`; the `▶`-button UI is deleted.

---

## 3. Artist profile refinement

**Structure unchanged** (`ArtistProfileScreen.kt`, `ArtistHero.kt`): compact circular
hero (avatar, name, monthly listeners, `▶ Play` + `📻 Radio`), then **Popular**, then
**Albums** (horizontal row), **Singles & EPs** (horizontal row), **Fans also like**.

**Changes:**
- `PopularTracksSection` renders `SongRow` (inherits tap-to-play + the row states).
- Polish pass: consistent card sizing, spacing, and typography across the hero and
  section headers so the page reads as one designed surface rather than stacked parts.
- The hero `▶ Play` / `Radio` buttons stay (intentional large CTAs — not row clutter).

`AlbumsRow` / `SinglesRow` stay horizontal (the user accepted that albums past the
first few scroll off-screen; discoverability of a specific album is a Phase-2 sort
concern, not a Phase-1 layout one).

---

## 4. Album page

`AlbumDiscoveryScreen.kt`: apply `SongRow` to the tracklist. Today YT albums render
`PreviewDownloadRow` and Qobuz-native albums render a separate `NativeAlbumTrackRow`.
Under the unified `SongRow`:
- YT album tracks → full `SongRow` (tap-plays, download one-tap, `⋮`).
- Qobuz-native album tracks → `SongRow` with the **download affordance hidden**
  (Phase-1: Qobuz tracks have no videoId → no download-by-id). Tap still plays natively.
This removes the divergent `NativeAlbumTrackRow` in favour of one row that adapts to
`downloadSupported`. The album hero's `Play` CTA stays.

---

## 5. Offline mode chip on Search

**Problem:** opening Search while Offline, a tap only 30s-previews (or toasts "turn on
Online"), with no way to switch mode without leaving for Home.

**Fix (user's chosen "A"):** a persistent Online/Offline chip in the Search header,
reusing Home's control.

- **Extract** the two composables Search actually needs — `StreamingModeChip` and
  `StreamingModeSheet` — from `feature/home/streaming/` into **`core:ui`**
  (`core/ui/.../components/streaming/`). They take a `mode` + callbacks; they hold no
  state. `feature:home` and `feature:search` both depend on `core:ui`, so this removes
  a would-be `feature:search → feature:home` dependency (which must not exist).
  `StreamingModePrompt` is NOT used by Search — leave it in `feature:home` unless it
  shares state/helpers with the moved two (in which case move it as a co-located unit);
  do not extract an unconsumed composable.
- **Extraction pre-check (plan step):** before moving, confirm the composables' inputs
  are already `core:ui`-safe — the `mode` param resolves to a `core:model`/`core:common`
  type (not a `feature:home`-local enum), and any string resources / theme extensions /
  icons they reference are `core:ui`-resident or primitives. If a helper is
  `feature:home`-local, either inline it or move it too. This de-risks the classic
  module-extraction trap (a moved composable dragging hidden dependencies).
- **Wire in Search:** `SearchViewModel` exposes the current mode from
  `StreamingPreference.enabled` and an action to open the mode sheet / toggle. The
  `SearchScreen` header renders the chip next to the search bar; tapping it opens the
  same `StreamingModeSheet` Home uses.
- **Behavior unchanged otherwise:** offline row tap still 30s-previews; switching to
  Online via the chip makes subsequent taps stream. No contextual prompt (chosen "A").
- **Visibility:** gated on `StashConstants.STREAMING_ENGINE_ENABLED`, same as Home, so
  the chip disappears entirely when the streaming engine is off.

---

## 6. Fast preview (lossless resolve priority)

**On-device diagnosis (2026-07-11, offline preview of a Logic track):**
```
preview lossless Ii4E45K3UiU via qbdlx_qobuz confidence=1.00
preview-play … totalDt=18496ms
```
The offline preview **already uses the FLAC** (qbdlx, perfect 1.00 match) — *not*
YouTube. But a cold tap took **18.5 s** to start.

**Root cause:** `QbdlxQobuzSource` has two resolve paths — `resolve()` goes through
`AggregatorRateLimiter` (**1 token / 3 s**, burst 4); `resolveImmediate()` bypasses the
token bucket. Full streaming (`playFromStream`) is fast because it uses the immediate
path. The **preview** goes through `LosslessUrlPrefetcher` → `LosslessSourceRegistry.resolve`
→ the **rate-limited** path, so a cold tap waits behind speculative row-prefetches for
tokens to refill (~15–18 s). When the row's background prefetch already finished, the
tap is instant — hence "fast most of the time, sometimes very slow."

**Fix:** a user-initiated preview tap is a foreground action and must bypass the rate
limiter (mirroring streaming), while the background scroll-prefetch stays rate-limited
(it's speculative). The qbdlx source's internal `resolve` already accepts a
`bypassRateLimit` flag (the machinery exists); it just isn't exposed through the
interface.
- Add `bypassRateLimit: Boolean = false` to `LosslessSource.resolve` and
  `LosslessSourceRegistry.resolve`; thread it to the qbdlx source's existing internal
  param. Sources that don't rate-limit ignore it.
- `LosslessUrlPrefetcher`: `warmUp` (background, scroll-triggered) keeps
  `bypassRateLimit = false`; the foreground `lookup` (tap-triggered) passes
  `bypassRateLimit = true` when the warm cache isn't ready, so a cold tap does a single
  search+match+getFileUrl (~1–2 s) instead of waiting on tokens.
- Warm taps stay instant (they await an already-completed deferred). The YouTube
  fallback on a genuine lossless miss/failure is unchanged.
- **In-flight dedup:** when a cold `lookup` fires while a rate-limited `warmUp` for the
  *same* track is still in flight (the exact diagnosed scenario), `lookup` should
  **replace** that in-flight deferred with the bypassing resolve (cancel/supersede the
  slow one) rather than launch a second, redundant Qobuz call.

**Scope guard:** this touches the lossless resolve interface/registry (the
`data:download` *module*, not the download *pipeline*) — a thin, additive,
default-`false` flag, not a resolver rewrite. No change to matching, sources, the
streaming resolver, the player, or the download/save flow.
- **Implementor fanout (plan must enumerate):** adding the flag to the `LosslessSource`
  interface method forces **every** implementor's `override` to add the param (Kotlin
  requires matching signatures). Each non-qbdlx source is a trivial accept-and-ignore
  edit — the plan must list them all so none is missed and the module still compiles.

---

## 7. Error handling & edge cases

- **Blank-videoId rows (Qobuz-native):** already keyed safely by index elsewhere; the
  now-playing indicator never false-matches them (blank id ≠ player's id).
- **Download state race:** the three-state download affordance reads the delegate's
  existing `downloadingIds` / `downloadedIds` flows — no new state source.
- **Chip extraction must not regress Home:** the moved composables keep identical
  signatures; Home's call sites switch to the `core:ui` import only.
- **Streaming engine off:** chip hidden; `SongRow` tap falls back to the existing
  offline/preview path.
- **Nothing playing:** now-playing indicator simply renders no row as active.

---

## 8. Testing

- **`SongRow` (Compose/unit where possible, ViewModel for wiring):** row tap invokes
  play; download affordance shows idle/downloading/downloaded from the delegate flows;
  `⋮` actions route to the right delegate calls; now-playing indicator activates only
  for the matching id; download affordance hidden when `downloadSupported == false`.
- **Offline chip:** `SearchViewModel` exposes mode from `StreamingPreference`; the
  toggle/open-sheet action fires; chip hidden when the engine flag is off. Extraction
  is behavior-preserving — Home's existing streaming tests must still pass.
- **Fast preview:** the foreground `lookup` resolves with `bypassRateLimit = true`
  (the rate limiter's `acquire` is not awaited on a tap), while `warmUp` stays
  rate-limited; a lossless miss still falls back to the YouTube extractor. Verify the
  `bypassRateLimit` flag threads through registry → qbdlx source to its existing
  internal param.
- **Artist/Album screens:** `SongRow` is wired into Popular and album tracklists;
  section structure/order is unchanged (guard against accidental restructure).
- **No regressions:** existing search/album/artist ViewModel tests stay green.

---

## 9. Out of scope / deferred

- Discography data quality — wrong-artist Qobuz gap-fills, album sort (Phase 2 spec).
- Album grid / filter-chip discography (rejected in favour of refined horizontal rows).
- Immersive backdrop hero (rejected in favour of the compact hero).
- Contextual "Go Online" prompt on tap (rejected in favour of the passive chip).
- Any change to the streaming resolver, download pipeline, or player internals.
