# Now Playing → Artist Profile (focused on the current album)

**Date:** 2026-07-06
**Status:** Approved design, pre-implementation
**Feature 1 of 3** in the "discoverability" workstream. Siblings (separate specs):
cross-source discography merge, and artist/song radios.

## 1. Goal

From the full-screen Now Playing screen, tapping the track's title/artist text
opens that artist's online **Artist Profile** (the existing discovery surface
with Popular / Albums / Singles / Related), scrolled to and briefly highlighting
the album the track belongs to.

This closes a discoverability gap: today the Now Playing screen is a dead end —
there is no way to jump from "what's playing" into browsing the artist's catalog.

## 2. The core problem

The in-memory `Track` (`core/model/Track.kt`) carries only **name strings**
(`artist`, `album`, `albumArtist`) plus `youtubeId` / `spotifyUri` / `isrc`. It
has **no YouTube artist browseId and no album browseId**. The destination
(`ArtistProfileScreen` via `SearchArtistRoute`) requires an artist browseId
(`UC…`). So a tap must **resolve** the artist name → browseId before it can
navigate.

## 3. Approach (chosen)

**Name-based resolve → reuse the existing Artist Profile.**

On tap, run an artist-filtered InnerTube search on the track's primary artist
name, take the top artist entity's browseId, and navigate to the existing
`SearchArtistRoute`. This reuses the entire search + artist-profile stack that
already ships. The album is surfaced via a new optional `focusAlbum` nav arg.

### Approaches rejected

- **videoId reverse-lookup** (read the artist browseId off the playing song's
  byline via InnerTube `next`). Most precise, but only works for tracks that
  have a `youtubeId` — many tracks stream from Qobuz with none — and still needs
  a name-resolve fallback. More code for a marginal accuracy gain. Revisit only
  if name-resolve picks wrong artists in real use.
- **Pre-resolve + cache the browseId at play time.** Instant tap, but speculative
  background work on every track. YAGNI for v1.

## 4. Components & data flow

```
NowPlayingScreen (track text block, now clickable)
   └─ onTrackInfoTap() ────────────► NowPlayingViewModel.onTrackInfoTapped()
                                          │  (resolving spinner on the block)
                                          ▼
                                     YTMusicApiClient.resolveArtist(name)
                                          │  artist-filtered search, top result
                                          ▼
                                     emit ArtistNavTarget(browseId, name, avatar,
                                                          focusAlbum = track.album)
   ◄──────────────────────────────── collected as one-shot event
   onNavigateToArtist(target) ─────► StashNavHost:
                                     navController.navigate(
                                       SearchArtistRoute(artistId, name, avatar,
                                                         focusAlbum))
                                          ▼
                                     ArtistProfileScreen
                                       └─ AlbumsRow scrolls to + highlights the
                                          card whose title == focusAlbum (norm.)
```

### 4.1 `YTMusicApiClient.resolveArtist(name): ArtistSummary?`

New method, mirrors the existing `searchCanonical` pattern. Issues a
`search(name, params = ARTISTS_FILTER)` and returns the **top** artist row as
an `ArtistSummary(id, name, avatarUrl)` (existing model), or `null` if there is
no artist result. Parsing reuses the existing `parseArtistsShelf(): List<ArtistSummary>`
(it already extracts the `UC…` browseId from `navigationEndpoint.browseEndpoint
.browseId`) — take `.firstOrNull()`.

The artists filter param (a new sibling constant to the existing
`songsFilterParams` in `InnerTubeClient`) forces the clean `musicShelfRenderer`
"Artists" shelf shape, sidestepping the flat `itemSectionRenderer` variant that
ambiguous queries otherwise return (where no artist shelf parses at all). The
exact value is **verified against the live InnerTube API** for the ambiguous
query "my bloody valentine":

```
ARTISTS_FILTER = "EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D"
```

It returned an `Artists` shelf whose top row was the correct Official Artist
Channel (`UCuoGeza7Dl9Ni_hmeLTPdkg`, pageType `MUSIC_PAGE_TYPE_ARTIST`). This is
the route to take — do **not** fall back to the unfiltered `searchAll` "Artists"
path, which returns the flat shape and yields nothing for exactly the ambiguous
names this feature most needs to handle.

- **Primary-artist selection:** resolve on `track.albumArtist.ifBlank { primary }`
  where `primary = track.artist.substringBefore(",").substringBefore(" feat").trim()`.
  So "A, B" and "A feat. C" both resolve to A.
- **Blank artist** → return null (caller shows a toast).

### 4.2 `NowPlayingViewModel.onTrackInfoTapped()`

- No-op when `currentTrack == null`.
- Sets a `resolvingArtist` state flag (drives an inline spinner / disables
  re-tap) for the duration of the resolve.
- Launches in `viewModelScope`: calls `resolveArtist`, and on success emits an
  `ArtistNavTarget` on a one-shot `SharedFlow` (`artistNavEvents`); on null or
  throw, emits a `userMessages` toast ("Couldn't find this artist").
- Double-tap-safe: a second tap while resolving is ignored (guard on the flag).

New module dependency: `feature/nowplaying/build.gradle.kts` gains
`implementation(project(":data:ytmusic"))` — the same edge `feature:search`
already has. `YTMusicApiClient` is `@Singleton`/Hilt-injectable.

### 4.3 Screen wiring

- `NowPlayingScreen` gains `onNavigateToArtist: (ArtistNavTarget) -> Unit`.
- It collects `viewModel.artistNavEvents` and invokes the callback.
- `StashNavHost`'s `composable<NowPlayingRoute>` passes `onNavigateToArtist`
  that calls `navController.navigate(SearchArtistRoute(...))`.
- The track text block (title `Text` + "artist • album" `Text`, lines ~292–338
  of `NowPlayingScreen.kt`) is wrapped in a `clickable` that calls
  `viewModel.onTrackInfoTapped()`. A subtle trailing chevron signals it's
  actionable. **Album art behavior is untouched.**

### 4.4 `focusAlbum` — "bring up that album"

- `SearchArtistRoute` (`TopLevelDestination.kt`) gains
  `val focusAlbum: String? = null` (optional → existing call sites compile
  unchanged).
- `ArtistProfileViewModel` reads it from `SavedStateHandle` and exposes it in
  UI state (or straight to the screen).
- **Match against both shelves.** A playing track's `album` may be a full
  album *or* a single/EP, which render in two different rows (`AlbumsRow` and
  `SinglesRow`). The match therefore searches **Albums first, then Singles/EPs**
  for a card whose normalized title equals the normalized `focusAlbum`.
  Normalization = lowercase + trim + collapse internal whitespace (handles
  "m b v" vs "M B V").
- **Two-axis scroll so the card is actually visible.** The matched card lives in
  a horizontal `LazyRow` that itself sits inside the screen's outer vertical
  `LazyColumn`, below the hero + Popular. Scrolling only the `LazyRow` would
  animate a card the user can't see (it's below the fold). So on a match:
  1. the screen's **outer `LazyColumn` state is hoisted** and
     `animateScrollToItem` brings the matched *section* (Albums or Singles) onto
     screen, then
  2. the matched **row's `LazyRow` state** `animateScrollToItem`s the card into
     the rail, and
  3. the card gets a brief highlight (accent ring, ~1.5s).
- Runs once, on the first profile emission that has a non-empty catalog
  (guard with a `focusHandled` flag so a Stale→Fresh refresh doesn't re-scroll).
- **Best-effort:** no match, blank `focusAlbum`, empty catalog, or the album
  missing from YT's catalog (e.g. Loveless) → land on the profile top, no
  scroll, no highlight. Never an error.

## 5. Error handling & edge cases

| Case | Behavior |
|------|----------|
| Track not playing (`currentTrack == null`) | Text block not clickable. |
| Blank artist name | Toast "Couldn't find this artist"; no nav. |
| `resolveArtist` returns null (no YT artist entity) | Toast; stay on screen. |
| `resolveArtist` throws (network) | Toast; stay on screen. Reuses existing snackbar/toast host. |
| Resolve slow | Inline spinner on the block; block disabled until it resolves. |
| Double-tap during resolve | Second tap ignored. |
| Album is actually a single/EP | Matched in the Singles/EPs shelf instead of Albums; same scroll + highlight. |
| Album not on the profile (e.g. Loveless, missing on YT) | Profile opens; no scroll/highlight. (The discography-gap spec addresses the missing album separately.) |
| Wrong artist for a shared name | Accepted risk for v1; upgrade path is the videoId reverse-lookup. |

## 6. Testing

- **`resolveArtist` (unit, `data:ytmusic`):** MockWebServer serving a canned
  artists-filter search response → asserts top artist `{id,name,avatar}` parsed;
  empty response → null. (Mirrors existing `searchCanonical` / client tests.)
- **Primary-artist selection (unit):** "A, B" → "A"; "A feat. C" → "A";
  `albumArtist` wins when present; blank → null. Pure function, table-driven.
- **`NowPlayingViewModel.onTrackInfoTapped` (unit, Turbine):** success emits an
  `ArtistNavTarget` with `focusAlbum = track.album`; null/throw emits a
  `userMessages` toast and no nav event; null track is a no-op; second tap while
  resolving is ignored.
- **Album normalization/match (unit):** normalized-equality matcher used by the
  scroll ("m b v" == "M B V"; trims; collapses internal whitespace;
  case-insensitive). Given a `focusAlbum` plus an albums list and a singles list,
  the resolver returns the right `(shelf, index)` — album hit, single/EP hit,
  and no-match-anywhere.

## 7. Out of scope (v1)

- videoId reverse-lookup resolve; pre-resolve/browseId caching.
- MiniPlayer tap (this is the full Now Playing screen only).
- Recovering albums missing from YouTube's catalog (that's the cross-source
  discography spec).
- Any change to `ArtistProfileViewModel`'s catalog-fill / prefetch logic beyond
  reading `focusAlbum`.
