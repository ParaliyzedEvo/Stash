# Spotify-URI Resolver for antra — Design

**Date:** 2026-06-09
**Status:** Approved (3-agent design consensus + 2-round spec review; pending user review)
**Author:** brainstorming session + opus design panel

## Problem

The `antra` lossless source downloads/streams 24-bit FLAC, but it **requires a
Spotify track URL** (`https://open.spotify.com/track/<id>`) to extract a track.
So only tracks that already carry a `spotify_uri` can use antra. The 7,099
YouTube-sourced library tracks (which have no `spotify_uri` and no ISRC) can
never use antra today — they always fall through to lossy YouTube playback /
download.

**Goal:** let a non-Spotify track use antra by finding its **correct** Spotify
link on demand, so "everything across the board plays or downloads as long as
antra can find the FLAC for it."

## Decisions (locked with the user)

- **Correctness = conservative / "bulletproof."** Never attach a *wrong* Spotify
  recording (live, remaster, cover, sped-up/nightcore, karaoke, alt mix). If not
  highly confident, attach **nothing** — the track stays on the YouTube fallback
  exactly as today. A missed match is acceptable; a wrong match is not.
- **Trigger = on-demand.** Resolution runs just-in-time the first time antra is
  actually needed for a track (a stream tap or a download), **not** a batch
  backfill. A ~300 ms Spotify search is negligible next to antra's 60–120 s job.
- **No ISRC available.** YouTube Music does not surface ISRC; all 7,099 YouTube
  tracks have only title / artist / album / duration_ms / youtube_id. Matching is
  therefore Spotify **text search + confidence scoring**, made bulletproof by a
  strict acceptance rule (the duration gate is the load-bearing anti-wrong-match
  signal).

## Architecture

Four units, each with one purpose, plus a 2-edit integration seam.

### 1. `SpotifyApiClient.searchTracks()` — new method (`:data:spotify`)
Thin wrapper over `GET /v1/search?type=track`. No matching logic.

```kotlin
data class SpotifyTrackCandidate(
    val id: String,            // bare id → "spotify:track:$id"
    val name: String,
    val artists: List<String>, // ordered; artists[].name
    val albumName: String,
    val durationMs: Long,
    val isrc: String?,         // external_ids.isrc (may be null on /search)
    val explicit: Boolean,
)

/** Public-data search via the existing client_credentials token.
 *  Returns [] on a parseable empty/non-match response.
 *  THROWS SpotifyRateLimitException(retryAfter) on 429 and SpotifyApiException
 *  on other transport errors so the caller can distinguish a transient failure
 *  from a genuine no-match (they cache differently). */
suspend fun searchTracks(query: String, limit: Int = 8, market: String = "US"): List<SpotifyTrackCandidate>
```

Reuses `getClientCredentialsToken()` and its existing 401-refresh-once path. The
`external_ids` truncation quirk documented on the playlist path does **not**
apply to `/search`. **`SpotifyRateLimitException(retryAfter)` is a NEW exception
type** (only `SpotifyApiException` exists today) — add it alongside the search
method.

### 2. `SpotifySearchScorer` — the bulletproof rule (`:data:download`, `…matching`)
Given our track + a candidate list, returns the single accepted candidate or
null. The reverse-direction mirror of `MatchScorer`; delegates all string math
to the **same `TrackMatcher`** (`canonicalTitle` / `canonicalArtist` /
`jaroWinklerSimilarity`) so canonicalization is identical to the forward path.

```kotlin
@Singleton
class SpotifySearchScorer @Inject constructor(private val matcher: TrackMatcher) {
    data class Decision(val accepted: SpotifyTrackCandidate?, val reason: String)
    fun pick(track: TrackQuery, candidates: List<SpotifyTrackCandidate>): Decision
}
```

### 3. `SpotifyUriResolver` — cache-first orchestrator (`:data:download`, `…lossless.spotifyresolve`)
The only thing the antra call sites talk to. Reads the cache; on a miss builds
queries, calls `searchTracks`, scores, persists the outcome, returns a
`spotify:track:<id>` URI or null.

```kotlin
@Singleton
class SpotifyUriResolver @Inject constructor(
    private val spotify: SpotifyApiClient,
    private val scorer: SpotifySearchScorer,
    private val dao: SpotifyResolutionDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Cache-first. Returns the full "https://open.spotify.com/track/<id>" URL on
     *  a confident match, else null (no confident match OR transient failure —
     *  both fall back to YouTube). Built from the bare id the resolver holds, so
     *  the caller needs no copy()/re-parse round-trip. Internally the side-table
     *  stores the "spotify:track:<id>" URI form. */
    suspend fun resolveUrl(trackId: Long, track: TrackQuery): String?
}
```

If `track.durationMs` is null/0 (`durKnown == false`), `resolveUrl` returns null
**immediately without an API call and without writing the cache** — the gate
can't run, so there is nothing to cache, and a future `duration_ms` backfill can
flip the track to resolvable with no stale `NO_MATCH` row to clear.

Owns **per-trackId in-flight coalescing** (mirrors `AntraStreamResolver`'s
`HashMap<Long, CompletableDeferred>` + `Mutex`) so a foreground tap and the
next-track prefetch don't double-search. The Spotify search runs **outside**
`AntraJobGate` and `rateLimiter.acquire` — it is a cheap read, not an antra job,
and must never hold antra's single job slot while waiting on Spotify.

### 4. `SpotifyResolutionDao` + `SpotifyResolutionEntity` (`:core:data`, Room v31→32)
Persistence (schema below).

### Integration seam (two edits, one extension)
```kotlin
// data/download/.../lossless/LosslessSource.kt
// resolveUrl returns the open.spotify.com/track/<id> URL directly (the resolver
// already has the bare id; no copy()/re-parse round-trip through spotifyTrackUrl).
suspend fun TrackQuery.resolvedSpotifyTrackUrl(resolver: SpotifyUriResolver): String? =
    spotifyTrackUrl() ?: trackId?.let { id -> resolver.resolveUrl(id, this) }
```
- Add `val trackId: Long? = null` to `TrackQuery`. A null `trackId` simply skips
  resolution (degrades to today's behavior) — a missed wire-up is **safe, never
  wrong**.
- `SpotifyUriResolver.resolveUrl(trackId, track)` returns the full
  `https://open.spotify.com/track/<id>` URL on a confident match (it builds it
  from the bare id it already holds), else null.
- **`AntraSource.resolve()` (download):** `query.resolvedSpotifyTrackUrl(resolver) ?: return null`.
  The download-side `TrackQuery` (built in `DownloadManager.kt:398`) **already
  carries `durationMs`, `album`, `isrc`** — only add `trackId = track.id` there.
- **`AntraStreamResolver.resolve(track)` (stream, `:core:media`, already depends on
  `:data:download`):** the current stream-side `TrackQuery` (`AntraStreamResolver.kt:70`)
  passes ONLY `artist`, `title`, `spotifyUri`. It **must be extended to thread
  `trackId = track.id` AND `durationMs = track.durationMs`** (and `album =
  track.album` for the album-qualified query). Without `durationMs`, the acceptance
  rule's `durKnown` gate rejects EVERY streamed track → resolution would no-op on
  the stream path. This is a required edit, not optional.
- Both call the extension **after** the cheap `isEnabled()`/`isConnected()`
  checks, so the search only runs when antra is genuinely about to do a 60–120 s
  job.

## Resolution algorithm

### Query construction (priority list; first query yielding an *accepted* candidate wins)
Mirror `TrackQuery.searchTerms()` (primary-artist fallback — see the
multi-artist memory) but use Spotify field filters for precision:

1. `track:"<cleanTitle>" artist:"<primaryArtist>"`
2. `track:"<cleanTitle>" artist:"<primaryArtist>" album:"<album>"` (only when
   album is present and not blank/equal-to-title)
3. Unfielded fallback: `"<primaryArtist> <cleanTitle>"` (rescues odd punctuation
   / sparse field index)

- `primaryArtist` = first part after splitting on `MatchScorer.ARTIST_PART_SEPARATOR`
  (`,;&/／・|` + feat./ft./and). Featured artists are dropped from the **query**
  but validated in the candidate's `artists[]` at accept time.
- `cleanTitle` = title with **non-version** decorations stripped for the query
  (`(Official Video)`, `(Lyric Video)`, `(Audio)`, `(MV)`, `【…】`, `(feat. …)`).
  Version-defining suffixes (`(Live)`, `(Acoustic)`, `(Remix)`, `(Remaster)`,
  `(Sped Up)`…) are **kept** — they are signals, not noise, at accept time.
- **Non-Latin scripts:** never transliterate. Spotify indexes native scripts and
  `track:`/`artist:` filters work on them; the duration gate carries the safety
  load where Jaro-Winkler is cross-script-unreliable.
- **Candidate count:** `limit = 8`, `market = "US"`. Score all 8; never trust
  rank 1 blindly.

### Signals (per candidate, against the original track)
| Signal | Definition |
|---|---|
| `titleSim` | `jaroWinkler(canonicalTitle(track.title), canonicalTitle(cand.name))` (via `TrackMatcher`; `canonicalTitle` strips parentheticals + feat + lowercases — used **only** for similarity) |
| `artistOk` | see "Artist match" below — part-wise, order-insensitive, reuses `MatchScorer`'s `artistParts` + token-run containment |
| `durKnown` | `track.durationMs != null && track.durationMs > 0 && cand.durationMs > 0` |
| `durDeltaSec` | `abs(track.durationMs − cand.durationMs) / 1000` |
| `versionConflict` | see "Version veto" below — computed over **raw lowercased** titles, NOT canonical |

**Artist match (`artistOk`).** Reuse `MatchScorer`'s artist machinery, NOT
`TrackMatcher.canonicalArtist` (which sorts/joins the whole credit into one
string and is wrong for per-element membership). Split the track's artist credit
into parts via `MatchScorer.ARTIST_PART_SEPARATOR` (`,;&/／・|` + feat./ft./and)
and strip each to a comparable form via its `artistParts` helper. `artistOk` is
true iff: the track's **primary** part matches some `cand.artists[]` element by
`jaroWinkler(strippedPart, strippedCandArtist) ≥ 0.85` **or** `containsRun`
token-run containment (the latter is the CJK / multi-word escape, mirroring how
`MatchScorer.artistAppearsInTitle` composes them); **and** every additional track
artist part is likewise a member of the candidate's artist set. Each
`cand.artists[]` element is compared per-element (not joined). Order-insensitive
and feat.-placement-insensitive.

> **Implementation note (visibility):** `MatchScorer.ARTIST_PART_SEPARATOR`,
> `artistParts`, and `containsRun` are currently `private` — a separate
> `SpotifySearchScorer` cannot call them. Promote these three to `internal`
> (same `:data:download` module) **or** extract them into a shared
> `ArtistMatching` object/helper that both `MatchScorer` and `SpotifySearchScorer`
> use. Pick the extraction if it stays small; otherwise `internal` is acceptable.
> Do NOT duplicate the logic.

**Version veto (`versionConflict`).** **Critical:** compute over **raw lowercased
titles**, exactly as `MatchScorer.computePenalty` does (`targetTitle.lowercase()`
/ `candidateTitle.lowercase()`) — **NOT** `canonicalTitle`. `canonicalTitle`
strips all parenthetical/bracketed content (`PARENTHETICAL_REGEX`) *and* feat
before the scan, which would delete every `(Live)`/`(Remaster)`/`(Acoustic)`
token and make this veto a no-op (this was the original spec's load-bearing bug —
see "Why raw titles" below). `versionConflict` is true iff, for any token in the
disqualifying set, its presence in `track.title.lowercase()` **differs** from its
presence in `cand.name.lowercase()` (symmetric: must be in **both** or
**neither**). Tokens are matched on **word boundaries** (`\b…\b` / token split),
so "live" does not match inside "Oliver", "edit" inside "editor", "demo" inside
"Demolition".

**Disqualifying token set** — defined explicitly and self-contained in
`SpotifySearchScorer` (do **not** delegate to `MatchScorer.computePenalty`, whose
set is tuned for a different soft-penalty purpose and omits several traps).
Reconciled set:
`{ live, concert, unplugged, session, acoustic, instrumental, karaoke,
remaster, remastered, sped up, spedup, slowed, nightcore, cover, remix, rework,
edit, radio edit, extended, demo, mono, re-recorded, rerecorded,
taylor's version, commentary, mtv }`.
Multi-word tokens ("sped up", "radio edit", "taylor's version") are matched as
contiguous word sequences. This set deliberately ADDS `extended`/`unplugged`/
`session`/`concert`/`mtv` (real wrong-version traps absent from the original
list) and keeps `remaster`/`demo`/`mono`/`taylor's version` (absent from
`computePenalty`).

**Why raw titles (load-bearing).** Because `canonicalTitle` strips parentheticals,
"Song (Live)" and "Song" both canonicalize to "song" → `titleSim = 1.0`. So
`titleSim` alone CANNOT distinguish a labelled variant from the original; the
version veto (over raw titles) is what rejects it. If the veto ran over canonical
titles it would never fire, and a labelled live/remaster/acoustic cut that landed
within ±4 s (remasters often preserve runtime) would be **accepted as the
original** — violating "never wrong." The raw-title version check is therefore
not optional polish; it closes that false-accept window.

### Acceptance rule (the bulletproof core)
A candidate is **accepted iff ALL** hold:
1. `durKnown` **AND** `durDeltaSec ≤ 4` — **hard gate, no exceptions.** If
   duration is unknown, **reject** (we refuse to drop our strongest signal).
2. `titleSim ≥ 0.92`.
3. `artistOk == true`.
4. `versionConflict == false`.

Among all candidates that pass, take the smallest `durDeltaSec` (tie-break:
highest `titleSim`). **Ambiguity abstain:** if two or more candidates pass *and*
are within `0.02` `titleSim` and `2 s` `durDeltaSec` of each other (e.g. two
regional masters), **reject everything** — we never guess which id is "the" one.

### Threshold justification
- **`durDeltaSec ≤ 4`, duration required-known** — deliberately tighter than
  `MatchScorer`'s soft 15 s gate. 15 s is calibrated for noisy *YouTube*
  candidate durations (silent intros/outros). Here both sides are clean catalog
  metadata for the *same recording*, which agree to ≤1–2 s; 4 s absorbs encoder
  rounding while rejecting nearly every wrong-version trap (live +10–90 s,
  extended mix +60 s, sped-up −20–40 s, radio edit −15–40 s, most remasters with
  re-cut fades). A wrong recording almost never lands within ±4 s of the right
  one for the same title+artist. We **reject unknown duration** (opposite of
  `MatchScorer`, which passes it) because a blind match violates "bulletproof";
  YouTube tracks reliably carry `duration_ms`, so coverage cost is negligible.
- **`titleSim ≥ 0.92`** — far stricter than the forward composite's 0.60, because
  here title is a **standalone gate**, not one weighted term, and there is no
  human in the loop. Both sides are clean (our metadata vs Spotify's catalog), so
  a true match is near-identical. No containment escape (that exists to rescue
  decorated YouTube titles; Spotify `name` is clean).
- **`artistOk` part-wise ≥ 0.85** — handles multi-artist ordering and "feat."
  placement without demanding full-credit string equality, while still rejecting
  an unrelated artist's same-titled song (the single most dangerous trap).
- **`versionConflict` as a hard veto (symmetric)** — bulletproof: if either side
  advertises a variant the other lacks, reject outright rather than risk it.

This is a **boolean gate, not a weighted score** — maximum precision, accepted
low recall.

## Persistence / caching

**Decision: a side table, NOT a column on `tracks`.** (Unanimous across the
panel.) Rationale:
- Negative and transient-failure states carry their own TTL/attempt metadata that
  has no home on the track row.
- `tracks.spotify_uri` semantically means "this track *came from* Spotify" and is
  trusted by sync diffing, matching, Like/scrobble logic. Writing a *derived,
  fuzzy* value there would silently feed those consumers a value they didn't earn.
- A side table is independently clearable (re-resolve after tuning thresholds by
  truncating one table) and keeps the migration off a hot table.

```kotlin
@Entity(tableName = "spotify_resolution")
data class SpotifyResolutionEntity(
    @PrimaryKey val trackId: Long,        // local tracks.id (the non-Spotify track)
    val status: String,                   // "MATCHED" | "NO_MATCH" | "TRANSIENT"
    val spotifyUri: String?,              // "spotify:track:<id>" when MATCHED, else null
    val matchedIsrc: String?,             // candidate ISRC when present (future kennyy/squid use)
    val titleSim: Float?,                 // audit: why we accepted
    val durDeltaSec: Int?,                // audit
    val resolvedAtMs: Long,
    val expiresAtMs: Long,                // TTL by status (below)
    val attempts: Int = 1,                // TRANSIENT backoff counter
)
```

DAO: `get(trackId): SpotifyResolutionEntity?`, `@Upsert upsert(e)`, and a
`deleteByTrackIds(...)` hook into the existing track-scoped orphan cleanup.

**TTL policy — three statuses, three behaviors (the whole point of the table):**
| status | meaning | TTL | on fresh lookup |
|---|---|---|---|
| `MATCHED` | confident URI found | **never expires** | return URI (no API call) |
| `NO_MATCH` | search ran, nothing passed the gate | **30 days** | return null (no API call) |
| `TRANSIENT` | 429 / token fail / offline / timeout | **15 min**, or `now + Retry-After` (whichever later), exponential backoff capped at `attempts ≤ 5` then promoted to a 30-day `NO_MATCH` | return null (no API call) |

- `MATCHED` permanent: a correct Spotify id does not rot; re-searching wastes
  quota and risks a *worse* answer after a catalog shuffle.
- `NO_MATCH` 30-day: stable but not eternal (Spotify's catalog grows; gates may
  relax in a future version) — monthly re-attempt.
- `TRANSIENT` short: **the critical correctness boundary** — a 429 must NEVER be
  cached as `NO_MATCH`, or one rate-limit burst permanently disables antra for
  thousands of tracks.

`resolveUrl` read path: if `durKnown == false` ⇒ return null (no search, no
cache, see above). Else `get(trackId)` → `MATCHED` ⇒ return the URL form of
`spotifyUri`; `NO_MATCH`/`TRANSIENT` and `now < expiresAtMs` ⇒ return null
(respect the negative cache, no API call); else (miss or expired) ⇒ resolve and
upsert.

**TRANSIENT → NO_MATCH promotion happens on write during the next resolve**, not
passively: when an expired `TRANSIENT` row is re-resolved and the search *again*
fails transiently, increment `attempts`; once `attempts > 5`, write the new row
as `NO_MATCH` (30-day TTL) instead of `TRANSIENT`. A genuine success or empty
result at any point supersedes the row (`MATCHED` or `NO_MATCH`) and the
`attempts` counter is irrelevant thereafter. The audit column `durDeltaSec` is
stored in **whole seconds** (the gate unit); if finer audit precision is wanted
later, widen to a `durDeltaMs` column — not needed for the gate.

Migration 31→32: `CREATE TABLE spotify_resolution (...)`. No data backfill.

## Edge cases (wrong-match traps → how the rule rejects each)
| Trap | Rejected by |
|---|---|
| Live version | `durDeltaSec > 4` **and** `versionConflict` (live) — double-guarded |
| Remaster | `versionConflict` (remaster); duration often differs too |
| Cover / re-recording / "Taylor's Version" | `artistOk` fails (different artist) and/or `versionConflict` |
| Sped-up / nightcore / slowed | `durDeltaSec` (tempo edits shift runtime 20–60 s) + `versionConflict` |
| Karaoke / instrumental | `versionConflict`; karaoke artist usually differs too |
| Multi-artist ordering ("A, B" vs "B, A") | `artistOk` is part-wise, order-insensitive |
| "feat." placement | feat. stripped from both titles; featured artist validated via `artists[]` membership |
| Classical Op./No./Mvt. | Op/No tokens survive canonicalization → `titleSim < 0.92`; `durDeltaSec` separates performances |
| Instrumental vs vocal | `versionConflict` if labelled; else duration usually differs |
| Single vs album version | `durDeltaSec ≤ 4` (edits differ in length); identical length ⇒ effectively same master, accept |
| Regional duplicates / two masters | `market=US` collapses most; the ambiguity-abstain rule rejects genuine ties |
| Our `duration_ms` unknown | `durKnown == false` ⇒ return null with NO search and NO cache write (a duration backfill can flip it later; not a `NO_MATCH`) |

The recurring theme: **duration is the trap-catcher of last resort.** Title/
artist gates catch labelled variants; the ≤4 s gate catches the unlabelled ones.

## Failure / rate-limit handling
- **429:** `searchTracks` throws `SpotifyRateLimitException(retryAfter)`;
  `resolveUrl` catches → writes `TRANSIENT` (`expiresAtMs = now + max(15min, retryAfter)`,
  `attempts++`) → returns null → antra fails over to YouTube. Never `NO_MATCH`.
- **Token 401:** reuse `SpotifyApiClient`'s existing null-cache-refresh-once-retry
  path; persistent failure → `TRANSIENT`.
- **Offline / IOException / timeout:** catch (re-throw `CancellationException`
  first, per the worker-cancellation memory) → `TRANSIENT`. antra is already
  unusable offline, so nothing is lost.
- **Empty/unparseable 200:** a genuine "Spotify has nothing" → `NO_MATCH` (30 d),
  not a failure.
- **Search-burst safety net:** the app-global client_credentials token is shared
  with sync; gate `searchTracks` behind a lightweight `"spotify_search"`
  token-bucket (reuse `AggregatorRateLimiter`, e.g. 5 req/s). On-demand single-
  track resolution already drips, so this is a backstop, not a throttle.
- **Token-refresh race:** `getClientCredentialsToken()`'s token cache is a plain
  field with no `Mutex` today. Add a `Mutex` around the refresh so a resolver
  401-refresh and a concurrent sync 401-refresh don't double-acquire. (This is a
  required small edit, not a "verify if needed".)
- **`market` coverage cost (honest note):** `market = "US"` collapses Spotify's
  per-market duplicate track objects, but a recording present ONLY on a non-US
  catalog (some regional K-pop/J-pop/Latin releases) returns zero candidates →
  `NO_MATCH` for 30 days. For a heavily non-Western library this is a real recall
  hole, accepted under "a missed match is fine." A future enhancement reads the
  market from the user's locale (app-only client_credentials tokens cannot use
  `market=from_token`, so locale-derived is the realistic path).
- Resolution **does not** spend antra quota (`singles_left`); only an accepted
  match proceeds to enqueue a job, exactly as today.

## Top risks + mitigations
1. **A wrong match poisons the permanent `MATCHED` cache.** Strict gates
   (required-known ≤4 s duration, 0.92 title, symmetric version veto, ambiguity-
   abstain) + audit columns (`titleSim`, `durDeltaSec`, `matchedIsrc`) for a
   future invalidation query + an independently-clearable table. The post-download
   **duration backstop already validates the FLAC** (`AntraSource` kdoc), so even
   a bad URI producing a wrong-length file is caught before save.
2. **Negative cache hides a track that later becomes resolvable.** 30-day
   `NO_MATCH` TTL (not permanent); transient failures use short backoff and never
   write `NO_MATCH`. A future manual "re-check Spotify match" action can delete a
   row.
3. **Shared-credential 429 storms** (a recurring class — see the Last.fm rate-
   limit history). On-demand only (no backfill storm) + aggressive negative/
   transient caching + per-track coalescing + the token bucket.
4. **`TrackQuery.trackId` wired wrong from the download path.** Defaults null ⇒
   resolution skipped ⇒ today's behavior. Safe, never wrong.
5. **First-tap latency** (a Spotify search before the antra job). A single fast
   GET inside the existing 180 s stream timeout; every repeat is an instant cache
   hit. Acceptable vs the alternative of no antra at all for 7,099 tracks.

## Testing strategy
- **`SpotifySearchScorer` (pure, table-driven JVM tests):** the heart of the
  feature. One test per trap row above — assert each wrong candidate is rejected
  and the correct one accepted. Property: an unknown-duration track is always
  rejected. Ambiguity-abstain test. These are fast and exhaustive.
- **`SpotifyUriResolver` (mockk):** cache-hit returns without searching;
  `MATCHED`/`NO_MATCH`/`TRANSIENT` TTL behaviors; 429 → `TRANSIENT` not
  `NO_MATCH`; in-flight coalescing (two concurrent resolves → one search).
- **`SpotifyApiClient.searchTracks` (parser test):** parse a real `/v1/search`
  JSON fixture into `SpotifyTrackCandidate`, including missing `external_ids`.
- **DAO (in-memory Room):** upsert/get round-trip; migration 31→32 creates the
  table.
- **Integration:** `AntraSource` / `AntraStreamResolver` — when `spotifyUri` is
  null and the resolver returns a URI, the antra job runs; when it returns null,
  the resolver isn't consulted twice and the registry fails over (no behavior
  change for already-URI'd tracks).

## Out of scope (explicit)
- Background backfill of all 7,099 tracks (on-demand only this round; backfill is
  a clean phase-2 that reuses the same resolver).
- A UI to review/override matches (the conservative gate makes auto-only safe;
  manual review was considered and deferred).
- Writing the resolved URI back onto `tracks.spotify_uri` (kept in the side table
  to protect sync/match/scrobble consumers).
- ISRC extraction from YouTube Music (not currently exposed; would be the path to
  *exact* matching if it ever becomes available).
