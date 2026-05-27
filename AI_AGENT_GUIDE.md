# Stash — AI Agent Onboarding & System Architecture Guide

Welcome! If you are an AI agent pair-programming on **Stash**, this document serves as your comprehensive entry-point. It outlines the product, codebase structure, features, architecture, ongoing implementations, and chronological changelogs.

---

## 1. Product Overview

**Stash** is a state-of-the-art, premium music application built for Android using **Jetpack Compose** and modern Android development best practices. It bridges the gap between streaming convenience and high-fidelity audio libraries by:
1. **Streaming**: Impersonating YouTube Music InnerTube clients to stream tracks instantly.
2. **Metadata Canonicalization**: Matching search results against high-fidelity catalogs.
3. **Lossless Upgrades**: Downloading files in CD-quality FLAC directly via Qobuz API integrations, with a robust fallback to yt-dlp/YouTube audio when lossless is unavailable.
4. **Metadata & Artwork Embedding**: Packaging downloaded media with complete tags and covers directly inside the audio container (M4A, MP3, Opus, FLAC).
5. **Sync Engine**: Periodically synchronizing user libraries across Spotify, YouTube, and local storage.

---

## 2. Project Architecture & Module Breakdown

Stash is structured as a clean, highly modularized, multi-module Gradle project:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                                  :app                                   │
└────────────────────────────────────┬────────────────────────────────────┘
                                     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                            :feature modules                             │
│     (:feature:search, :feature:nowplaying, :feature:sync, etc.)        │
└────────────────────────────────────┬────────────────────────────────────┘
                                     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                             :data modules                               │
│                (:data:download, :data:lyrics, :data:ytmusic)            │
└────────────────────────────────────┬────────────────────────────────────┘
                                     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                             :core modules                               │
│                (:core:media, :core:ui, :core:model, :core:common)       │
└─────────────────────────────────────────────────────────────────────────┘
```

### Module Descriptions

1. **`:app`**
   - Application entry point. Houses the main single-activity entry, central Hilt dependency injection graphs, and global navigation routing between features.
2. **`:core:common`**
   - Common helper utilities, performance logging markers (`PerfLog`), and cross-module art URL resolution helpers (`ArtUrlUpgrader`).
3. **`:core:model`**
   - Central, framework-free Kotlin models representing core entities (e.g. `Track`, `Artist`, `Album`, `MusicSource`).
4. **`:core:ui`**
   - The design system of Stash. Houses custom themes, color harmonizers, premium glassmorphism surfaces, basic shimmers, and shared structural widgets (e.g., `SectionHeader`).
5. **`:core:media`**
   - Playback engine layer. Orchestrates **ExoPlayer**, manages the media queue, triggers stream pre-warming, track-prefetching, and resolves play-on-tap routing.
6. **`:data:ytmusic`**
   - Low-level InnerTube API client (`InnerTubeClient`) and parser utilities. Handles cookies, SAPISID-hash signatures, and parses deep JSON payloads into clean, domain-ready DTOs.
7. **`:data:download`**
   - Download, post-processing, and lossless-upgrade logic. Features:
     - `DownloadManager`: Orchestrates the download pipeline.
     - `QobuzApiClient`: Interfaces Qobuz for CD-quality downloads.
     - `MetadataEmbedder`: Invokes native ffmpeg binaries to write tags.
     - `FlacPictureBlock`: Creates metadata image blocks from raw image bytes.
8. **`:data:lyrics`**
   - Integrates watch next next-token structures to search and fetch synchronized lyrics.
9. **`:feature:search`**
   - Search inputs, result lists, personalized skeletons, `ArtistProfileScreen`, and `AlbumDiscoveryScreen`.
10. **`:feature:nowplaying`**
    - The Now Playing experience. Features timeline tracking, direct lyrics/queue buttons, and contextual bottom sheet actions.
11. **`:feature:sync`**
    - Synchronization statuses, statistics tables, and background sync worker states.

---

## 3. Core Technical Designs & Heuristics

### A. Locale-Independent Search Parsing
YouTube Music search responses vary based on device locale and system headers. Rather than strictly matching exact titles (like `"Songs"`, `"Artists"`, or `"Albums"`), Stash uses a hybrid, locale-independent structural fallback to inspect the contents:
- **Albums**: Items render as cards using `musicTwoRowItemRenderer`.
- **Songs**: Items use `musicResponsiveListItemRenderer` and successfully parse a playable track summary (have a `videoId` or `watchEndpoint`).
- **Artists**: Items use `musicResponsiveListItemRenderer` with a browseId starting with `UC` or `MPLAUC`.

### B. FLAC Cover Art & Picture Embedding
For maximum compatibility with audio players (VLC, Plex, Symfonium), Stash embeds cover art natively:
- **Standard Containers (M4A, MP3)**: Attach art as a mapped video stream (`-disposition:v:0 attached_pic`) via ffmpeg.
- **Xiph/Ogg Containers (Opus, Ogg, FLAC)**: Bypasses stream mapping to prevent ffmpeg exit errors. Encodes a raw `METADATA_BLOCK_PICTURE` block inside the Vorbis comments, which the container muxer native-writes.
- **Dual Format Parsing**: `FlacPictureBlock` inspects raw image bytes to identify if it is a JPEG or PNG:
  - **PNG**: Recognizes the `\x89PNG\r\n\x1a\n` signature, parses dimensions directly from the 4-byte big-endian integers inside the `IHDR` chunk (offset 16 and 20), and sets MIME to `"image/png"`.
  - **JPEG**: Falls back to walking marker segments to discover SOF dimensions and sets MIME to `"image/jpeg"`.

---

## 4. Chronological Changelogs & Ongoing Tasks

### Previous Changelogs
- **v0.8.0**: Initial modularization and room schema setup.
- **v0.9.0**: Media playback integration using ExoPlayer and background service binders.
- **v0.9.12**: Overhauled search with debounce query flat-mapping.
- **v0.9.17**: Implemented "strict-FLAC" lossless upgrades with a reactive retry scheduler.
- **v0.9.35**: Added detailed Vorbis-comment casings (`ALBUMARTIST` + `album_artist`) to ffmpeg args.

### Current Implementation & Bug Fixes (Active Phase)

1. **Search Results Enhancement (Issue 1)**
   - Modified `YTMusicApiClient.searchAll` to support the **Locale-Independent structural fallback**. This ensures Songs, Artists, and Albums sections always display, resolving empty search results in localized/non-English interfaces.
2. **Search Thread Offloading (Performance)**
   - Offloaded the query flow flat-mapping pipeline in `SearchViewModel` to `Dispatchers.IO` using `.flowOn(Dispatchers.IO)`. This prevents heavy JSON parsing from running on the Main thread, eliminating search stutter/lag when a song is playing concurrently.
3. **Spacing & Typography polish (Issue 5 & 5b)**
   - Increased `SectionHeader` top-padding to `20.dp` for premium breathing room on Artist profiles.
   - Redesigned `SyncStatusCard` to put the last sync relative time string inside the top-right header, added margin gaps between statistics rows, and enhanced contrast.
4. **Context-Aware Material Shimmer (Issue 6)**
   - Built beautiful, highly expressive diagonal LinearGradient shimmer loaders in `SearchScreen` and `ShimmerPlaceholder` that sweep glistening highlights dynamically using a FastOutSlowInEasing curve to mimic the loaded structure.
5. **Navigation State Preservation (UX Enhancement)**
   - Configured `StashScaffold` bottom tab bar navigation with `saveState = true` and `restoreState = true` to preserve ViewModels, active queries, scroll positions, and loaded content when switching tabs.
6. **Download Removal Bottom Sheet (Issue 7)**
   - Made already-downloaded checkmarks in search/profile rows interactive.
   - Created `RemoveDownloadSheet.kt` — a sleek bottom sheet to confirm the removal/deletion of a local track file.
6. **Now Playing Redesign (Issue 8 & 9)**
   - Migrated the **Lyrics** and **Queue** actions from the top-right overflow dropdown into fast-access, standalone icon buttons directly flanking the playback bar.
   - Replaced the classic top overflow menu with a modern, glassmorphism `ModalBottomSheet` for secondary options (Save to Playlist, Download, Wrong Match).
7. **Lossless FLAC Cover Art (Bug Fix)**
   - Upgraded `FlacPictureBlock` to automatically support PNG images alongside JPEGs by directly parsing the PNG signature and `IHDR` chunk.
   - Configured `MetadataEmbedder` to treat FLAC files similarly to Opus/Ogg, writing covers via `METADATA_BLOCK_PICTURE` tags rather than using unstable ffmpeg stream copies.
8. **Real-Time Download Progress Updates**
   - Streamed fine-grained percentages from `DownloadManager` to `SyncNotificationManager` and foreground `SyncWorker` / `DiscoveryDownloadWorker` via `progressFlow`.
   - Updated the foreground notification text dynamically to display the specific track title and smooth `0%` to `100%` progress in real-time as it downloads.
9. **Dedicated Success Notifications**
   - Posted dedicated, defaults-importance, auto-canceling system notifications on successful download completions (`Download completed: [Title] by [Artist]`) for both sync-mode and search-mode downloads.
10. **Discord Rich Presence Support**
    - Mimics Spotify's implicit Android broadcast intents (`com.spotify.music.metadatachanged`, `com.spotify.music.playbackstatechanged`) in `StashPlaybackService` on track transitions and playback state updates.
    - Allows the mobile Discord client to instantly detect and display Stash playback activity when **"Device Broadcast Status"** is active in Discord settings.
11. **YouTube Music Library Album Sync (Issue #113)**
    - Fetches the user's library albums from `FEmusic_liked_albums` using the custom `getUserAlbums()` API.
    - Parses album IDs (`MPREb_`) and fetches tracks using the existing `getAlbum` API.
    - Saves track and album snapshots to DB so they populate the Stash library exactly like custom playlists.
12. **Native Chromecast/Google Cast Audio Streaming (Issue #83)**
    - Configured standard `StashCastOptionsProvider` and registered it in the `core:media` manifest.
    - Smoothly transfers local playback state (queue, index, position) to remote `CastPlayer` on session started/resumed, and switches back to ExoPlayer on session ending/ended.
    - Embedded `MediaRouteButton` directly in the Now Playing bottom actions row.

---

## 5. Development & Verification Guide

### Build Commands
To verify correctness, clean compile, and package the debug APK:
```bash
./gradlew clean assembleDebug
```

