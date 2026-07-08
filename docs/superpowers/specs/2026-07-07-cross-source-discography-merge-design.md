# Cross-Source Discography Merge — Design

**Date:** 2026-07-07
**Status:** Approved (brainstorm), pre-plan — revised after 3-reviewer consensus
**Workstream:** Discoverability feature #2 (after Now Playing → artist profile; before artist/song radios)

## Problem

The artist profile's discography is 100% YouTube-Music-powered. YT Music's
catalog has gaps: My Bloody Valentine's Official Artist Channel exposes only 2
album entities (`m b v` 2013 + the `you made me realise` EP). *Loveless* and
*Isn't Anything* do not exist as album entities anywhere on YT Music (confirmed
via album-filtered search). So the profile shows a near-empty discography for a
canonical artist — a YouTube catalog gap, **not** a Stash parser bug
(`getArtist` already follows "View all" grids).

The app's own lossless source, Qobuz (via `qbdlx` — the direct Qobuz API with a
rotating per-account token pool), carries MBV's full catalog. This feature
supplements the YT-sourced discography with Qobuz albums so the profile shows the
complete discography and plays those albums lossless.

## Approved decisions

1. **Native Qobuz playback is the end state** — a supplemented album ultimately
   plays directly from its Qobuz track ids as FLAC (guaranteed-correct,
   guaranteed-lossless), not by re-resolving a YouTube `videoId`.
2. **Union merge, Qobuz-preferred on collision** — show both catalogs merged;
   on a duplicate album keep the Qobuz entry and drop the YouTube one; keep
   YouTube-only albums Qobuz lacks. Never loses an album.

## Phasing (from review consensus)

Three independent reviews (architecture, correctness/runtime, completeness)
converged: native **by-id** playback carries every runtime blocker (resume,
heart-state, `TrackEntity`/resolver threading, synthetic-id collisions) and is
isolated to `core:media` — the highest-risk surface. It is separated from the
discovery win so each ships and verifies independently. **Decision #1 is
preserved as the Phase 2 end state — this is sequencing, not cutting.**

- **Phase 1 (this spec, full detail):** merge + show + album-open + lossless
  playback via the **existing qbdlx search-match** path. A Qobuz album track is
  persisted like any streaming track (canonical identity, via the existing
  `ensureTrackPersisted`) and resolves through `QbdlxStreamResolver`'s current
  search/match — which serves lossless FLAC because the track's exact
  title/artist/album/duration come straight from Qobuz, so match confidence is
  high. **Zero changes to `core:media`, the resolver chain, `TrackEntity`, or
  the stash-resolve URI.** Delivers the entire discoverability win.
- **Phase 2 (separate spec — sketched at the end here):** upgrade playback from
  fuzzy search-match to guaranteed-correct **by Qobuz track id**. Adds
  `MusicSource.QOBUZ`, a `qobuzTrackId` thread, `resolveById`, the resolver
  fast-path, and native-track persistence. Isolated to the media module; earns
  its own review + on-device verification.

---

# Phase 1 — Discography merge + album-open + lossless playback

## Architecture

### The two seams (both required to avoid a module cycle)

`ArtistCache` and `AlbumCache` live in `core:data`; the Qobuz client
(`QbdlxApiClient`) lives in `data:download`, which already depends on
`core:data`. A direct reference the other way would create a Gradle cycle.
Invert **both** the list path and the detail path with interfaces `core:data`
owns:

```kotlin
// core:data
interface DiscographySupplement {
    /** Qobuz albums for an artist, or empty when no confident match /
     *  qbdlx unavailable. Best-effort; never throws to the caller. */
    suspend fun forArtist(artistName: String): List<AlbumSummary>
}

interface QobuzAlbumFetcher {
    /** Album detail (tracks with title/artist/album/duration) for a Qobuz
     *  album id. Throws on failure — AlbumCache surfaces it like any load. */
    suspend fun getAlbum(qobuzAlbumId: String): AlbumDetail
}
```

Real implementations live in `data:download` (alongside `QbdlxQobuzSource`),
bound via Hilt `@Binds` **declared in that module**. `AlbumCache` and
`ArtistCache` must never name `QbdlxApiClient` directly. No default no-op
binding in `core:data` — a second `@Binds` for the same type is a duplicate-
binding compile error, and `data:download` is always in the app graph; the
"Qobuz disabled" case is handled *inside* the impl (returns empty).

`AlbumSummary` stays in `data:ytmusic` — that module is already a dependency of
both `core:data` and `data:download`, so no model move is needed.

### New / changed components

- **`QbdlxApiClient`** (`data:download`) — add two metadata calls. Auth reuse is
  real: `artist/get` and `album/get` need the app_id + token but **not** the
  MD5 `request_sig` that `getFileUrl` requires. Note the existing `search()`
  hardcodes `type=tracks`; artist resolution needs a new `type=artists` search
  (or `artist/get` paging) plus new DTOs — genuinely new code, fixture-testable
  as the existing qbdlx tests are.
  - `getArtistAlbums(artistName)` → resolve the Qobuz artist, page the album
    list.
  - `getAlbum(qobuzAlbumId)` → album detail with track list.

- **`QobuzDiscographyProvider`** (`data:download`) — implements
  `DiscographySupplement`. Gates on `isEnabledForStreaming()` (toggle on + live
  tokens); resolves the artist (see match gate below), pages the discography,
  buckets releases into albums vs singles/EPs by Qobuz release type, maps →
  `AlbumSummary(source = QOBUZ)`.

- **`DiscographyMerger`** — pure function (no I/O), the primary testable unit.

- **`AlbumSummary`** — gains `source: AlbumSource = AlbumSource.YOUTUBE`
  (**defaulted**, and `AlbumSource` is `@Serializable`) so existing cached
  `ArtistProfile` JSON rows — which lack the field — still decode. Its `id`
  stays the source-native id.

- **`AlbumCache`** — routes `get()` by source: `YOUTUBE` → existing YT album
  parse; `QOBUZ` → `QobuzAlbumFetcher.getAlbum()`. Requires the source to reach
  the call site (see nav plumbing below).

- **`AlbumDiscoveryViewModel`** — `synthesizeDomainTracks` branches on source.
  Qobuz tracks build domain `Track`s from Qobuz title/artist/album/duration
  with `youtubeId = null`; they persist via the existing `ensureTrackPersisted`
  (canonical identity) so they get a real `tracks.id`, resume, and heart like
  any streaming track, and resolve lossless via qbdlx search-match.

### Nav plumbing — how `source` reaches the tap surface

Today the album tap navigates via `SearchAlbumRoute(browseId, title, artist,
thumbnailUrl, year)` and `AlbumCache.get(browseId: String)` — no source. Add
`source: AlbumSource` as a nav arg on `SearchAlbumRoute`, thread it through the
`onNavigateToAlbum` call sites and `AlbumDiscoveryViewModel`, and change the
cache signature to `get(id, source)`. (Alternative considered and rejected as
too implicit: infer source from id shape — YT ids are `MPRE`/`OLAK5uy_`
prefixed, Qobuz ids numeric. Explicit nav arg is the documented contract.)

## Flows

### Merge flow (inside the `ArtistCache` fetch — BOTH sites)

`ArtistCache` calls `api.getArtist()` in **two** places: the cold miss *and* the
stale-while-revalidate refresh. Extract a `fetchAndMerge(artistId)` helper and
route **both** through it — otherwise the 6h TTL refresh overwrites the cached
row with a YT-only profile and the Qobuz albums vanish until the next cold miss.

1. `getArtist()` → YT `ArtistProfile`, unchanged.
2. `supplement.forArtist(name)`, wrapped in a tight **`withTimeout`** (the token
   pool can hang; a slow supplement must not stall first-shelf paint). Gate:
   qbdlx stream-enabled + live tokens. On timeout/failure/miss → empty (YT-only).
   - **Artist match (correctness crux):** resolve the Qobuz artist by name and
     gate on `QobuzCandidateMatcher.artistSimilarity(...) >= threshold` — **not**
     raw normalized-string equality. (Correction to the prior draft: the reused
     `normalize()` strips parentheticals/feat/punctuation and lowercases but does
     **not** strip a leading "the"; `artistSimilarity` already handles the-prefix
     and token-subset cases.) Then, to defend the feature's own target case:
     - **Blocklist compilation pseudo-artists** ("Various Artists" and Qobuz's
       localized forms) — they match trivially and would pull huge wrong catalogs.
     - **When YT has ≥1 album:** require ≥1 normalized-title overlap between the
       YT and Qobuz discographies as corroboration.
     - **When YT has 0 albums** (the MBV-class target, where there's nothing to
       cross-check): require a stronger signal or skip — check the top-N Qobuz
       candidates and **abort on ambiguity** (two candidates similar to the name
       ⇒ shared-name risk like "Nirvana"/"Prince"/"Low" ⇒ skip). Under-
       supplementing always beats grafting a stranger's discography.
3. `DiscographyMerger.merge(...)`:
   - Dedup **within the same bucket only** (album↔album, single↔single) — a
     Qobuz single must never evict a full YT album of the same name.
   - On a within-bucket title collision, Qobuz wins **only if** a secondary
     signal agrees (track count within ±1, or year within ±1); else keep both.
   - Order each lane newest-first by the **earliest** known year
     (`min(ytYear, qobuzYear)`) — Qobuz commonly dates by remaster year (Loveless
     → 2021), which would otherwise mis-sort the canonical album to the top.
     Parse years to `Int` (YT `year` is a 4-digit `String`; Qobuz dates are
     `YYYY-MM-DD`/int); nulls sort last.
4. Merged profile cached as one unit (6h SWR, 20-entry LRU, unchanged).

### Album-open + playback flow

1. Tap → nav with `source`. `AlbumCache.get(id, QOBUZ)` → `QobuzAlbumFetcher` →
   `AlbumDetail`.
2. `AlbumDiscoveryViewModel` builds domain `Track`s (Qobuz branch), persists via
   `ensureTrackPersisted`, `setQueue`.
3. Playback resolves through the existing chain: `QbdlxStreamResolver` builds a
   `TrackQuery` from title/artist/album/duration and search-matches on qbdlx →
   lossless FLAC. `streamOrigin = "qbdlx"`, real FLAC badge in Now Playing.
4. `youtubeId = null` ⇒ YouTube fallback is skipped (correct — it came from
   Qobuz). If qbdlx is down, playback fails visibly via the existing
   `StreamErrorCascadeGuard` (halt + notify), same as any lossless-down track.

## Error handling, resilience, invariant

- Supplement is best-effort everywhere (mirrors the "View all" grid-expansion
  pattern): any failure/timeout/miss → YT-only profile. Never errors or blocks
  the page.
- **Invariant (corrected):** "every shown album was playable **at cache time**."
  The gate runs at merge time and the profile is cached 6h, so if all tokens die
  mid-window a Qobuz album can be shown-but-unplayable for ≤6h. Verified failure
  modes are graceful, not crashes: an album-detail load with dead tokens shows
  the existing `Error` card + "tap Retry"; a mid-queue resolution failure routes
  through `StreamErrorCascadeGuard` (halt + notify). Nice-to-have: short-TTL or
  invalidate the Qobuz portion when `isEnabledForStreaming()` flips false.

## Cost

- Supplement runs inside the cache fetch → cached with the profile (6h SWR): ~2
  extra Qobuz calls per artist per 6h, bounded by `withTimeout`.
- Album detail rides `AlbumCache`. Merge is pure and instant.

## Known limitations

- A remaster/deluxe whose title differs ("Loveless" vs "Loveless (Remastered)")
  won't dedup and may appear twice — deliberate; safer than false-merging.
- Non-Latin artists (transliteration differences, e.g. "BTS" vs "방탄소년단")
  usually fail the match gate → no supplement. Safe (skips), just rarely fires.
- An artist with **no** YouTube presence at all is unreachable in v1: the
  profile is entered via a YT `artistId` nav arg, so there's no page to
  supplement. The MBV case (YT channel exists, albums sparse) is the target.

## Testing (Phase 1)

- **`DiscographyMerger`** (pure): within-bucket dedup, Qobuz-preference gated on
  secondary signal, single-doesn't-evict-album, YT-only retention, min-year
  ordering with null/remaster-year cases, empty-supplement passthrough.
- **`QobuzDiscographyProvider`** match gate: accept corroborated match; reject
  shared-name ambiguity (zero-YT-album + two similar candidates); reject
  Various-Artists; disabled / no-token → empty. Fake client.
- **`QbdlxApiClient`** new methods: parse offline JSON fixtures (artist albums +
  album detail).
- **`AlbumCache`** source routing: `QOBUZ` → fetcher, `YOUTUBE` → YT path.
- **`AlbumDiscoveryViewModel`**: Qobuz branch builds a persistable, playable
  queue; `focusAlbum` deep-link lands on a Qobuz grid entry (title-matched,
  source-agnostic — verified compatible with the shipped tap-to-artist feature).
- **Serialization back-compat:** an old cached `ArtistProfile` JSON without
  `source` still decodes (default = YOUTUBE).

## UI note

No visible "Qobuz" source badge — **deliberate**. The playable-at-cache-time
invariant means the user needn't distinguish sources; a badge would be confusing
and off-brand. Stated so nobody adds one later.

## Plan-time must-address (from confirmation review)

These are localized to `feature:search` / `data:download`, all fail safe, and
none reopen the `core:media` boundary — but the plan must resolve them:

1. **Pin the artist-match threshold and define "candidate" as distinct artists
   only.** `artistSimilarity` returns 1.0 for any token-superset with one
   distinctive token, so a superstring pseudo-artist ("…performed by My Bloody
   Valentine", tribute/compilation entities) or the artist's own variant rows
   would trip the zero-album ambiguity-abort and kill the supplement for the
   flagship case. Dedup the artist's own variants and exclude superstring
   pseudo-entities from the candidate set before counting ambiguity; state the
   concrete threshold.
2. **Guard `observeAlbum`'s videoId-keyed side-effects for `source == QOBUZ`.**
   The revision reuses `AlbumDiscoveryViewModel`, but `observeAlbum` runs
   `prefetcher.prefetch(videoIds)`, `delegate.refreshDownloadedIds(videoIds)`,
   and `backfillAlbumForTracks(videoIds, …)` on `TrackSummary.videoId` (a
   non-null YouTube field). Qobuz tracks have blank videoIds — the first two are
   wasteful no-ops, but `backfillAlbumForTracks` keyed on `youtube_id = ""`
   could mis-key against other blank-youtubeId rows. Branch these for QOBUZ.
3. **State eager persist-on-play timing + its interactions.** Phase 1's
   resume/heart depends on a *new* eager-persist step in the ViewModel (YT
   albums today use synthetic ids and do **not** persist or resume — so "resume
   and heart like any streaming track" is the intended new behavior, not
   parity). With `MusicSource.QOBUZ` deferred to Phase 2, these rows persist as
   `source = YOUTUBE, youtubeId = null` (harmless to qbdlx resolution, a latent
   mislabel). Unlinked stub rows are subject to the existing orphan-cleanup (the
   "search-tab downloads vanish on relaunch" reaper) — the plan must confirm
   resume survives cleanup or accept YT-album parity (no resume).
4. **(nice-to-have)** Two tracks in one Qobuz album that collapse to the same
   canonical identity share a PK via `ensureTrackPersisted`, so a start-index
   `indexOfFirst { it.id == startId }` could land on the wrong row. Edge case.

---

# Phase 2 — Native playback by Qobuz track id (separate spec)

Design sketch only; a full spec + plan + on-device verification precede
implementation. Captured here so Phase 2 planning starts from the reviewers'
findings.

**Goal:** upgrade Qobuz-album playback from search-match to guaranteed-correct
resolution by Qobuz track id.

**Change surface (all in/under `core:media` + model):**
- `MusicSource.QOBUZ`; `Track.qobuzTrackId: Long?`.
- Thread the id: `AlbumDetail` track → domain `Track` → a new `qid` query param
  on `stashResolveUri` → parsed in `entityFromUri` → a **transient** (`@Ignore`,
  not migrated — native tracks resolve live) `qobuzTrackId` on `TrackEntity`,
  which is what the resolver actually receives. Also copy it in both
  `Track.toEntity()` sites.
- `QbdlxQobuzSource.resolveById(qobuzTrackId, quality)` reusing
  `resolveFile`/token rotation (skip only `search()`); `QbdlxStreamResolver`
  fast-path when the id is present, else current search-match.

**Blockers Phase 2 must solve (from review — do not start without these):**
- **Resume after process-kill.** The persisted queue is a list of `tracks.id`;
  `PlaybackResumer.getByIds` silently drops ids with no row. Native tracks must
  persist a **real `tracks` row with a `qobuzTrackId` column** before entering
  the queue (extend `ensureTrackPersisted`), or an all-Qobuz queue resumes as
  nothing (the Bluetooth/AA path). This column is the same fix that makes
  heart-state work.
- **Synthetic id collisions.** Real PKs (small positive), streaming synthetics
  (`videoId.hashCode()`, 32-bit signed), and Qobuz ids share one `Long` space,
  and several paths gate on `id > 0L`. Specify a provably-disjoint, strictly-
  positive mapping (e.g. `qobuzTrackId or 0x2000_0000_0000_0000L`). But note:
  if native tracks are persisted (above), the queue id is the real PK and this
  matters only for the pre-persist transient window.
- **Heart / lyrics key.** NowPlaying's live-row lookup falls back only on
  `youtubeId` (null here) and the like/lyrics key is `youtubeId.hashCode()`
  (0 for all null-youtubeId tracks). The persisted-row + canonical-identity
  fallback fixes the heart; NowPlaying needs a non-null-safe track key.
- **Background-fill lane.** `qbdlx::resolve` runs only when `allowYtDlp = true`
  (foreground/next-up), never on the speculative queue-fill, and the YouTube
  fallback bails on `youtubeId = null` — so a fully-Qobuz album's non-adjacent
  tracks won't background-resolve, leaving a sparse timeline until each is
  next-up. Confirm the by-id fast path is allowed on the background lane, or
  accept the sparse timeline.

## Out of scope (both phases)

- Search-tab Qobuz results and the latent `searchAll` flat-shape bug (separate
  workstream item).
- Artist/song radios (feature #3).
- Persisted YT-artist → Qobuz-artist_id mapping and a manual "wrong artist?"
  correction UI (YAGNI for v1; the tightened auto-gate is the safeguard).
- Download-by-id (existing search-match download path still works).
