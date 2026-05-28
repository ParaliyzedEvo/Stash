# Customizable Stash Mixes — genre/mood mix builder (and the Deep Cuts fix)

**Date:** 2026-05-28
**Status:** Design — approved in brainstorming, pending spec review
**Topic:** World-class, user-customizable Stash Mixes built on tag-seeded discovery

## Problem

Two problems, one root:

1. **Deep Cuts goes stale.** On refresh, "Deep Cuts" fills almost entirely with tracks the user **already downloaded**, while "Daily Discover" correctly surfaces fresh, non-downloaded (stream-only) tracks. Both recipes are configured nearly identically (85% discovery / 15% library); the divergence is the **seed strategy**. Daily Discover uses `ARTIST_SIMILAR` (similar *artists* → their top tracks → mostly artists the user doesn't own → fresh). Deep Cuts uses `TRACK_SIMILAR` (tracks similar to the user's top tracks → heavy overlap with owned music), and:
   - its seed input falls back to the user's own library (`StashMixRefreshWorker.kt:563-577` → `trackDao.getTopTracksByLfmPlaycount`), and
   - the discovery worker's canonical-dedup branch (`StashDiscoveryWorker.kt:306-313`) links an **existing downloaded** track instead of creating a fresh stream-only stub whenever a candidate matches an owned track.

   This exact symptom has been patched repeatedly (PR3+, v0.9.20 NONE→TRACK_SIMILAR, the 2026-05-12 "102 downloaded, 0 discovery survivors" fix, v0.9.37 stream-only seam, v0.9.38 round-robin/cap). Patching the seed once more is not the fix — the seeding approach itself is the problem.

2. **Mixes aren't customizable.** Users can't shape what a mix surfaces. The goal is world-class, personalized mixes the user builds by choosing genres, moods, era, and how adventurous the mix is.

These converge: rebuilding mix discovery around **explicit, tag-based semantics** delivers the customization *and* fixes Deep Cuts at the root (its candidates would come from genre tags, not from "tracks similar to my library").

## Goals

- A **Mix Builder**: users create their own mixes by choosing genres, moods, an optional era, and a discovery level.
- Mixes are **personalized within the chosen genres/moods** — the user's taste surfaces first.
- Mixes are **fresh streaming surfaces** — they pull live from Last.fm and are dominated by tracks the user does not already own (subject to the discovery-level slider).
- **Deep Cuts is fixed** by being re-pointed onto the new tag-seeded engine with a "deep-cut" lean.
- Reuse the existing recipe + discovery + materialization + streaming-playback pipeline (Approach 1) — minimal new infrastructure.

## Non-goals

- **No new "Stations" subsystem.** We extend `stash_mix_recipes` / `MixGenerator` / `StashDiscoveryWorker` / `StashMixRefreshWorker`, not a parallel engine.
- **No Spotify audio-features mood engine.** Moods are tag-based (Spotify deprecated audio-features for new apps in late 2024). 
- **No live genre-catalog fetching.** The catalog of selectable genres is a bundled, curated resource (the *tracks* are always live; only the menu of chips ships with the app).
- **No automatic downloads for custom-mix discoveries.** New tracks are stream-only stubs, consistent with the v0.9.37 stream-only design.
- **No change to Daily Discover** (works as-is) or First Listen (already tag-based).
- **No UI redesign of Home.** Custom mixes render with the existing `DailyMixCard` treatment.

## Design

### 1. Approach — extend the recipe engine

A custom mix is a row in the existing `stash_mix_recipes` table with `seedStrategy = TAG_GRAPH`, `isBuiltin = false`, a user-given `name`, and the user's picks stored on the recipe. It flows through the same `StashMixRefreshWorker` → `StashDiscoveryWorker` → `materializeMix` pipeline as the built-ins, and plays via the existing streaming engine. No parallel subsystem.

### 2. Data model

Reuse `StashMixRecipeEntity` (`core/data/.../db/entity/StashMixRecipeEntity.kt`). Fields used:

- `includeTagsCsv` — the chosen **genre** tags (existing field).
- **NEW `mood_keys_csv`** — the chosen **mood ids** (e.g. `chill,focus`). The engine expands these to Last.fm tags via an app-defined, versioned **mood→tag map** (a Kotlin constant, e.g. `Chill → [chill, mellow, downtempo, chillout, ambient]`). Storing the high-level mood ids (not just the resolved tags) keeps the mix editable and lets us improve the map later.
- `excludeTagsCsv` — exclude tags (existing).
- `eraStartYear` / `eraEndYear` — era (existing; see §5 for the soft-constraint behavior).
- `discoveryRatio` — the discovery-level slider, 0.0–1.0 (existing).
- `targetLength`, `name`, `description`, `isBuiltin`, `isActive`, `playlistId`, `seedStrategy` (existing).

**Schema change:** one new column `mood_keys_csv TEXT NOT NULL DEFAULT ''` + a Room migration.

**The tag catalog** (the selectable genres + mood definitions) is a **bundled, versioned resource** built from Last.fm's real taxonomy: pull Last.fm global top tags (`chart.getTopTags`), curate once (dedupe, drop non-genre junk, group into families such as *Electronic → house, techno, ambient*), and ship that as the catalog. This delivers the full range of Last.fm, curated for quality and grouped for browsing, with no noisy runtime tag fetches. The catalog version can be refreshed in any release; new *tracks* never depend on releases (they're fetched live every refresh).

### 3. The discovery & ranking engine

On refresh of any TAG_GRAPH mix (custom or rebuilt built-in):

1. **Resolve the tag set** — `includeTagsCsv` (genres) ∪ `expand(mood_keys_csv)` via the mood→tag map, minus `excludeTagsCsv`. If era is set, add the matching **decade tag** (Last.fm has `90s`, `80s`, etc.).
2. **Fetch live candidates** — call Last.fm `tag.getTopTracks` per tag and pool the results.
3. **Combine by tag overlap** — Last.fm queries one tag at a time, so to honor "Jazz **+** Chill" we **rank a track by how many of the chosen tags it appears under**. A track in both "jazz" and "chill" outranks one in only "jazz." This produces intersection behavior from single-tag queries.
4. **Filter** — drop blocklisted, skip-banned, and recently-served tracks (rotation, so refresh yields new tracks).
5. **Personalize** — rank the pool with the existing affinity scoring (`MixGenerator` weights: a **decayed listening-history affinity** with `affinityBias` lean, plus tag cosine, completion, loved boost, skip penalty). Note this is *listening-history* affinity, **not** artist-similarity — there is no artist-similarity scoring term to build against. The user's taste surfaces first *within* the chosen space.
6. **Split fresh vs owned** — `discoveryRatio` decides how many slots are brand-new stream-only tracks vs the user's matching library tracks; materialize into the STASH_MIX playlist via the existing path.

**Why this fixes Deep Cuts:** the candidate pool comes from **tags**, not "tracks similar to your library." The library-anchored seed fallback and the canonical "link existing download" bias no longer dominate, because we deliberately pull tag-top-tracks the user likely does not own.

### 4. The Mix Builder UI

A single builder screen (matched to Stash's design system — GlassCard, `#06060C` glass, Space Grotesk/Inter, purple/cyan accents, FilterChip styling):

- **Name** field.
- **Moods** — curated chips with **custom line-icon emblems** (no emoji): crescent (Chill), bolt (Energetic), concentric target (Focus), sparkle (Party), raincloud (Melancholy), line-heart (Romantic), etc. Selected = accent-soft fill + accent border.
- **Genres** — curated chips **grouped by family**, with "Show all families" expansion. No free-text search (per the chosen curated-grid model).
- **Era** — optional decade chips (Any / 70s / 80s / …).
- **Discovery level** — a cyan→purple slider (Familiar ↔ Brand new), mapped to `discoveryRatio`, with a plain-language caption ("~85% fresh streaming · ~15% from your library").
- **Live summary** card + a purple "Create mix" CTA.

**Home integration:** custom mixes appear in the existing "Your Mixes" carousel as `DailyMixCard`s (180×120, source dot, white Space Grotesk title, gradient overlay), with a dashed "＋ Create mix" tile. Per-mix actions (long-press / ⋯): Refresh now, Edit, Delete, Pin. Deleting a custom mix removes the recipe + its playlist but **keeps any downloaded tracks** in the library.

### 5. Built-ins & the Deep Cuts fix

The built-ins become tuned presets of the same engine:

- **Daily Discover** — unchanged (`ARTIST_SIMILAR`); it already surfaces fresh tracks well.
- **Deep Cuts** — re-pointed from `TRACK_SIMILAR` to the **tag-seeded engine**, seeded from the user's **top genres** (derived from their listening/library tags) with a **deep-cut lean**: sample from *deeper* in each tag's top-tracks ranking (skip the top ~N, draw from positions N…M) rather than the most popular hits. Preserves the "deeper cuts" identity and fixes the staleness by construction.
  - **Note for planning:** this deep-cut lean is a *new tag-ranking-window* sampling on the Last.fm discovery pool — distinct from the recipe's existing `affinityBias` field (which biases toward/away from high-play *library* tracks). Planning must define how the two interact for TAG_GRAPH mixes (likely: the ranking-window governs discovery depth; `affinityBias` keeps its existing meaning only for the library slice). This same ranking-window is the mechanism §7's "rotation exhaustion → pull deeper" reuses — unify them in planning.
- **First Listen** — unchanged (already `TAG_GRAPH`, discovery 1.0).

Delivered via a `STASH_MIX_RECIPE_TUNING_VERSION` bump (existing mechanism, `StashApplication.maybeRetuneStashMixes`) that re-points Deep Cuts on upgrade. No data loss; existing downloaded tracks remain in the library and simply stop dominating the mix.

### 6. Refresh model

- **Built-in mixes** — keep the existing **daily** auto-refresh cycle.
- **Custom mixes** — refresh **on-demand**. Opening a mix shows its current materialized tracks **instantly** (no wait). A queue-consuming refresh fires only (a) when the user taps **Refresh**, or (b) automatically *on open* **if the mix is stale** (e.g. not refreshed within ~24h). Merely opening an already-fresh mix does **not** re-seed the discovery queue. This scales to unlimited custom mixes without starving the shared discovery drain (the v0.9.38 round-robin splits each batch across *active* recipes; on-demand seeding means a mix only consumes the queue when the user engages with a stale one). Built-ins stay pre-warmed; custom mixes you don't open don't churn the queue. (Exact staleness threshold → planning.)

### 7. Edge cases & error handling

- **Offline / Last.fm unreachable** — the builder still works (bundled catalog). A refresh that can't reach Last.fm **keeps the existing materialized tracks** and shows a quiet "couldn't refresh — check connection." Never empties the mix. (Consistent with the mixes-offline-visibility fix.)
- **Sparse pool** (narrow genre+mood+era combo) — a **relaxation ladder**: drop era first, then loosen mood tags, then widen the set. If still below a minimum, surface "not many tracks for this combo — try fewer filters." Never silently return a tiny/empty mix; log what was relaxed (no silent truncation).
- **Rotation exhaustion / all-owned** — broaden the freshness window or pull deeper in the tag ranking so refresh always finds something new.
- **Catalog drift** — saved mixes store tag strings, so they keep working even if a chip later leaves the curated grid; unknown mood keys are skipped gracefully.
- **Delete** — removes recipe + playlist; downloaded tracks stay in the library.

### 8. Testing

- **Unit:** mood→tag expansion; tag-overlap ranking; the relaxation ladder; the discovery/library split; affinity ranking on a tag pool.
- **Room / DAO:** recipe CRUD; the `mood_keys_csv` migration; `getStreamableOrDoneTrackIdsForRecipe` for a tag-seeded recipe.
- **Worker:** per-recipe tag seeding; on-demand seeding for a custom recipe.
- **Deep Cuts regression:** assert the rebuilt Deep Cuts yields **fresh stream-only candidates**, not all-downloaded.
- **Catalog:** the bundled catalog parses and is well-formed (families, tags, mood map).
- **ViewModel:** builder validation (name required, ≥1 ingredient); Home integration.

## Open questions for implementation planning

- Exact `tag.getTopTracks` page depth and the deep-cut sampling window (N…M) for Deep Cuts.
- Minimum-pool threshold that triggers the relaxation ladder.
- The initial curated catalog contents (genre families + mood→tag map) — a content task, reviewed before bundling.
- Whether "Pin" affects refresh (pinned custom mixes could opt into the daily cycle) — deferred; on-demand is the v1 default.
