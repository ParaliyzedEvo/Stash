# Artist & Song Radios — Design

**Feature #3 of the discoverability workstream** (siblings: Now Playing → artist
profile ✅ v0.9.74; cross-source discography merge ✅ merged 2026-07-09).

**Goal:** A self-extending, balanced generated queue seeded from an artist or a
track. Tap "Radio" on an artist, or "Start radio" on any song, and the player
fills with a never-ending station that mixes the seed with similar music.

---

## 1. Approach (why "C: hybrid")

Two services, each doing what it's best at:

- **Last.fm picks the taste** — `getSimilarArtists` / `getSimilarTracks` give a
  ranked similarity graph with match scores (0–1). This decides *who/what* plays
  and lets us control the balance directly (not YouTube's opaque algorithm).
- **YouTube supplies the audio** — `resolveArtist` + `getArtist().popular` (artist
  radio) and search (song radio) turn Last.fm's picks into **real videoIds** with
  real metadata. Clean playback, no title/artist resolve-miss holes in the queue.

Rejected alternatives:
- **A (Last.fm graph + lazy resolve):** candidates arrive as `(artist,title)` with
  no videoId, resolved at play time. Smallest surface, but some candidates fail to
  resolve → skipped, and the queue quality is at the mercy of the search-match.
- **B (YouTube native `RD…` watch-radio):** clean ids and built-in continuations,
  but hands the *feel* entirely to YouTube's algorithm — the opposite of the
  balance control the user wants — and sidelines the Last.fm graph.

C costs some up-front YT lookups; we hide that with a **foreground-first start**
(resolve a few tracks fast, begin playback, fill the rest in the background).

Both services are already wired: `core:data` depends on `data:ytmusic` and hosts
the Last.fm client; `StashMixRefreshWorker` and the discography caches already
orchestrate both there. `core:media` depends on `core:data`. No new module edges,
no new external dependency. Last.fm key verified live 2026-07-09
(`artist.getSimilar` MBV → Slowdive/Drop Nineteens; `track.getSimilar` Lil Wayne
"A Milli" → 3 Peat, with match scores).

---

## 2. Architecture & component boundaries

### `core:data/radio/` — the station brain (new)

The only place Last.fm and YouTube are combined. Given a seed and the set of
identities already played, it produces the next *balanced* batch of fully-playable
`Track`s (real videoIds, real metadata).

- **`RadioSeed`** — sealed model of what the user tapped:
  - `ArtistSeed(name: String, ytBrowseId: String?)`
  - `TrackSeed(title: String, artist: String, ytVideoId: String?)`
- **`RadioStationGenerator`** — the engine. Depends on `LastFmApiClient` (similarity
  graph + match scores) and `YTMusicApiClient` (picks → playable tracks). Exposes:
  - `suspend fun start(seed): RadioSession` — builds initial rotation state and the
    first batch.
  - `suspend fun RadioSession.nextBatch(): List<Track>` — the next ~12 tracks,
    deduped against everything already played.
  - Holds per-station rotation state: artist pool with weights, per-artist depth
    cursor, no-repeat identity set.
- **`RadioSession`** — the in-memory station state (seed, rotation, cursors, dedup).
- **`RadioInterleaver`** (pure) — weighted, no-adjacent-repeat interleave of the
  artist pool into a track order. Split out like the mix engine's `TagPoolBuilder`
  so the ratio/weighting logic is unit-tested without any I/O. **Both seed types
  flow through it:** artist radio feeds it the artist rotation directly; song radio
  keys each candidate by its *artist* (seed artist vs. each similar track's artist)
  and feeds those as the pool, so "~1/3 seed, no adjacent same-artist, match-score
  weighting" means the same thing for both. There is one ordering path, not two.

### `core:media` — queue integration (extend existing)

- **`PlayerRepository.startRadio(seed: RadioSeed)`** — dedicated entry, mirrors
  `shuffleLibrary`: builds the queue **and** arms the grow watcher in one step.
  (The public `setQueue` deliberately *disarms* watchers, so radio cannot route
  through it.)
- **Radio auto-grow watcher** — a sibling of the existing v0.9.14 library-shuffle
  grower in `PlayerRepositoryImpl.init`. Armed by `startRadio`; watches
  tracks-remaining-to-tail; under threshold (~5) calls `session.nextBatch()` off
  the main thread and appends via the logical-queue path; single-flight guard
  prevents overlapping grows. Disarmed by any normal `setQueue` /
  `shuffleLibrary` / `playTrack`, and by explicit stop.

### feature modules — entry points (small)

Three surfaces call `startRadio(seed)`; one radio-specific UI element (the live
indicator + stop). Everything else is the normal queue/player.

**Data flow:** tap → build `RadioSeed` → `generator.start(seed)` (foreground-first:
resolve ~4 fast to begin playback, background-fill the rest of the first batch) →
`setQueue` internally + arm watcher → drains → watcher calls `nextBatch()` →
append. The generator is a pure-ish orchestrator over the two clients, fully
testable by mocking them.

---

## 3. Generation strategy (the "balanced" feel)

"Balanced blend" = recognizable but always drifting outward. Concretely, the
seed is weighted to be the **single most frequent artist in the opening pool
(~1 in 3 slots there); neighbors take the rest ∝ Last.fm match score.** Because
the seed has a finite catalog, as the station widens to more neighbors the seed
naturally recedes — a familiar open that drifts outward, rather than a literal
steady 1/3 forever (which would require repeating the seed's few tracks, and the
no-repeat set forbids repeats). These ratios are named constants (tunable knobs).

### Artist radio (seed = artist)
1. `getSimilarArtists(seedName)` → ranked neighbors with match scores.
2. Build the **artist rotation**: seed + top ~12 neighbors, each weighted (seed
   weight set so it lands ~1/3 of slots; neighbors split the rest ∝ match score).
3. Turn each artist into real playable top tracks via YT: `resolveArtist(name)` →
   `getArtist(browseId).popular`. **Shortcut:** neighbors already present in the
   seed's YT "Fans also like" carousel arrive with a browseId in hand → skip the
   resolve hop for those.
4. **Interleave** by weight (never the same artist twice in a row); pull each
   artist's next unused top track; dedup; emit a ~12-track batch.
5. **Grow:** walk deeper into each artist's top-track list; when an artist is
   exhausted, pull more neighbors from the Last.fm list. ~12 neighbors × ~6 tracks
   ≈ 70+ tracks before widening — ample for a long session.

### Song radio (seed = track)
1. `getSimilarTracks(seedArtist, seedTitle)` → ranked `(artist,title)` pairs with
   match scores.
2. Balanced = ~1/3 from the **seed artist's** own top tracks, the rest from the
   similar-tracks list (∝ match score).
3. Resolve each pick to a real track via one YT search (`"artist title"` → top
   match videoId + metadata).
4. **Grow:** walk further down the similar-tracks list; when exhausted, fall back
   to artist-radio expansion off the seed artist's neighbors.

### Shared
- **No-repeat set** keyed by normalized identity: videoId if present, else
  `normalize("artist|title")`. Nothing repeats within a station.
- **Match-score weighting** keeps the closest neighbors most frequent.

---

## 4. Playback integration & track identity

- **Starting:** `startRadio(seed)` mirrors `shuffleLibrary` (queue + arm in one).
  Ordering matters: it builds the queue **first** (the same internal path
  `setQueue` uses), **then** arms the radio watcher — never the reverse — so radio
  does not disarm itself on start.
  `generator.start` is **foreground-first**: resolve ~4 tracks fast, start
  playback immediately, background-fill the remainder of the first batch. Now
  Playing opens as usual.
- **Self-extension:** a `radioActive` flag + the live `RadioSession`, watched like
  the library-shuffle grower. Under threshold, `nextBatch()` off-main, append,
  fold new tracks into the no-repeat set. `growing` guard prevents overlap.
- **Track identity — payoff of choosing C:** YT hands us **real videoIds**, so
  radio tracks are ordinary streaming `Track`s (`youtubeId` set,
  `source = YOUTUBE`, synthetic `id = videoId.hashCode()`). Unlike the Qobuz
  discography tracks, they need **no `ensureTrackPersisted`** and **no `id=0`
  special-casing**. The stream still upgrades to qbdlx FLAC at play time via the
  usual title/artist search-match. No new persistence. (`id = videoId.hashCode()`
  is the existing synthetic-key convention for streaming `Track`s built from a
  videoId — radio follows it, not a new scheme.)
- **Disarm & lifecycle:** any normal `setQueue` / `shuffleLibrary` / `playTrack`
  ends the station (user chose something else); plus an explicit "stop radio."
  The station is **in-memory / ephemeral**: on a process kill, the already-queued
  tracks resume and play out, but the generator does not resurrect — the station
  simply ends after the last generated batch. (YAGNI on cross-process generator
  revival for v1.)

---

## 5. UI, entry points & offline

- **Artist profile:** a **Radio** button on the artist header next to Play →
  `startRadio(ArtistSeed(name, browseId))` (both already in hand there).
- **Song ⋮ menu:** a **Start radio** item in the shared track overflow menu (the
  same one from the search-track-actions work) → `TrackSeed(title, artist,
  videoId)`. One addition surfaces it across search, albums, playlists, and queue.
- **Now Playing:** a **Start radio** action in the player overflow → `TrackSeed`
  from the current track.
- **Radio indicator:** while a station is live, a small "**Radio · \<seed\>**" chip
  on Now Playing that also hosts **Stop radio**. That is the whole radio-specific
  UI.
- **Offline:** radio is streaming-only (fetches neighbors + resolves new tracks).
  When streaming is off or there is no network, the entry points are **disabled
  with a hint** ("Radio needs streaming"), reusing the existing
  `StreamingPreference.enabled` + connectivity gate — no dead taps.

---

## 6. Error handling & resilience

- **Last.fm failure / rate-limit** → existing Worker-proxy fallback (v0.9.41), then
  degrade to **seed-artist-only** (its `getArtist.popular` + album tracks) so a
  station still plays.
- **YT resolve miss** for one candidate → skip it, keep filling the batch; never
  blocks the batch or the queue.
- **Degenerate seed** (obscure artist, no Last.fm neighbors) → seed-artist-only
  fallback; if even that is empty, a toast "Couldn't start radio" and **no queue
  change**.
- **Grow overlap** → single-flight `growing` guard.
- **Repeats** → the no-repeat identity set.

---

## 7. Testing (TDD, mock the two clients)

- **`RadioInterleaver` (pure):** the higher-weight seed is the most frequent
  artist in the pool (never starved — not a literal 1/3); match-score weighting
  orders neighbors; no adjacent same-artist repeat.
- **`RadioStationGenerator`:** artist-seed path; song-seed path; no-repeat dedup
  across batches; "Fans also like" browseId shortcut skips the resolve hop;
  degenerate/empty-neighbor fallback; Last.fm failure → seed-only.
- **`PlayerRepository` radio watcher:** arms on `startRadio`; grows when remaining
  < threshold; disarms on `setQueue`; single-flight grow.
- **Entry-point gating:** offline / streaming-off disables the affordances.

---

## 8. Out of scope (v1 / YAGNI)

- Cross-process generator revival after app death (queued tracks resume; station
  ends).
- A Home "Start a radio" card / recent-stations surface (dropped by the user).
- Persisting stations, sharing, or seeding from a genre/mood (mix engine already
  covers mood/genre).
- Tuning UI for the balance ratio — it ships as constants.
