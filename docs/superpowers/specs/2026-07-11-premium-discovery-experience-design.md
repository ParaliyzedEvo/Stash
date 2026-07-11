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

**Non-goals (Phase 1)**
- No artist-page restructure — keep hero → Popular → Albums row → Singles/EPs row →
  Fans also like (horizontal rows, compact hero). Chosen deliberately.
- No discography-quality fix (wrong albums, sort) — that's Phase 2.
- No change to the streaming resolver, download pipeline, or player.
- Keep the existing 30s-preview-on-tap behavior when Offline (the chip is the escape
  hatch, not a behavior change).

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

**Now-playing identity:** `SongRow` receives the currently-playing track id from the
host screen, which reads `PlayerRepository.playerState.currentTrack`. For streaming
rows this is the synthetic `videoId.hashCode()` id / the videoId — the row compares on
the same key the player exposes (blank-videoId Qobuz rows simply never match, which is
correct).

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

- **Extract** the stateless streaming-UI composables from `feature/home/streaming/`
  (`StreamingModeChip`, `StreamingModeSheet`, `StreamingModePrompt`) into **`core:ui`**
  (`core/ui/.../components/streaming/`). They take a `mode` + callbacks; they hold no
  state. `feature:home` and `feature:search` both depend on `core:ui`, so this removes
  a would-be `feature:search → feature:home` dependency (which must not exist).
- **Wire in Search:** `SearchViewModel` exposes the current mode from
  `StreamingPreference.enabled` and an action to open the mode sheet / toggle. The
  `SearchScreen` header renders the chip next to the search bar; tapping it opens the
  same `StreamingModeSheet` Home uses.
- **Behavior unchanged otherwise:** offline row tap still 30s-previews; switching to
  Online via the chip makes subsequent taps stream. No contextual prompt (chosen "A").
- **Visibility:** gated on `StashConstants.STREAMING_ENGINE_ENABLED`, same as Home, so
  the chip disappears entirely when the streaming engine is off.

---

## 6. Error handling & edge cases

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

## 7. Testing

- **`SongRow` (Compose/unit where possible, ViewModel for wiring):** row tap invokes
  play; download affordance shows idle/downloading/downloaded from the delegate flows;
  `⋮` actions route to the right delegate calls; now-playing indicator activates only
  for the matching id; download affordance hidden when `downloadSupported == false`.
- **Offline chip:** `SearchViewModel` exposes mode from `StreamingPreference`; the
  toggle/open-sheet action fires; chip hidden when the engine flag is off. Extraction
  is behavior-preserving — Home's existing streaming tests must still pass.
- **Artist/Album screens:** `SongRow` is wired into Popular and album tracklists;
  section structure/order is unchanged (guard against accidental restructure).
- **No regressions:** existing search/album/artist ViewModel tests stay green.

---

## 8. Out of scope / deferred

- Discography data quality — wrong-artist Qobuz gap-fills, album sort (Phase 2 spec).
- Album grid / filter-chip discography (rejected in favour of refined horizontal rows).
- Immersive backdrop hero (rejected in favour of the compact hero).
- Contextual "Go Online" prompt on tap (rejected in favour of the passive chip).
- Any change to the streaming resolver, download pipeline, or player internals.
