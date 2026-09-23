# Shared mixes — design

**Status:** approved in brainstorming 2026-09-23 · next: implementation plan
**Scope:** sharing a playlist or mix with other Stash users through a tappable link, with **Follow** (stays in sync with the owner) or **Save a copy** (frozen, editable), plus upgrading single-track share links to the same link style. *Listen Together* is a separate, later design that reuses this one's track descriptor and link host.

## 1. Goals and decisions

The motivating case: an ambient mix of 110 tracks that has accumulated over months exists only in its owner's Stash. The owner wants to send it to other Stash users, and it should keep growing on their side too.

| Decision | Choice | Why |
|---|---|---|
| What a share is | **Both:** the link is followable; the recipient picks **Follow** or **Save a copy** | Following an ever-growing mix is the thing no other service can offer for a Stash-only mix; "Save a copy" costs almost nothing on top |
| Link host | **`stash-share.rawnaldclark.workers.dev`** for now | No cost. Known trade-off: `workers.dev` is blocked in some countries. The host is a single setting; a custom domain can be added later without breaking old links |
| Storage | **Cloudflare KV**, one JSON document per mix | One write per publish, nearly free reads, same pattern as the tip-jar Worker. D1 row-per-track and link-only encoding were rejected (see §9) |
| Identity | **None beyond an optional display name.** Updates and deletes are authorised by a per-mix secret edit key | No accounts exist in Stash, and none are needed for this |

## 2. The shared-track descriptor

The unit both this feature and Listen Together depend on. Any Stash can turn it into playable audio through the existing chain (`ensureTrackPersisted` → `StreamSourceRegistry`: Qobuz by ISRC, then JioSaavn, then YouTube).

```json
{ "t": "Avril 14th", "a": "Aphex Twin", "al": "Drukqs", "d": 125000,
  "isrc": "GBBPW0100025", "sp": "5Y6nVaayzitvsD5F7nr3DV", "yt": "d1fQx6aXhkc" }
```

| Field | Source column (`TrackEntity`) | Required |
|---|---|---|
| `t` title | `title` | yes |
| `a` artist | `artist` | yes |
| `al` album | `album` (omit if blank) | no |
| `d` duration ms | `duration_ms` (omit if ≤ 0) | no |
| `isrc` | `isrc` | no |
| `sp` Spotify track id | the id part of `spotify_uri` | no |
| `yt` YouTube video id | `youtube_id` | no |

When a descriptor is received, it becomes a track row via `ensureTrackPersisted`, which de-duplicates by YouTube id, then Spotify URI, then canonical (title, artist). New rows are stream-only (`is_downloaded = 0`, `is_streamable = 1`). **Before persisting, the ISRC, album and duration from the descriptor must be written onto the row, so that lossless matching can use them.** The implementation plan must confirm that `ensureTrackPersisted` carries those fields, or extend it.

## 3. The shared-mix document

One document per mix, stored in KV:

```json
{
  "v": 1,
  "id": "Kx7Qa2pL",
  "version": 7,
  "updatedAt": 1790000000,
  "name": "Ambient",
  "sharedBy": "Rawn",
  "covers": ["https://…/a.jpg", "https://…/b.jpg"],
  "tracks": [ { "t": "…", "a": "…" } ]
}
```

- `v`: the document format version. It starts at 1. A reader that sees a higher value shows "Update Stash to open this mix".
- `id`: 8 characters of base62, random (about 2×10¹⁴ possibilities). The server generates it and retries on a collision.
- `version`: set by the server. It starts at 1 and goes up by one on every accepted update.
- `updatedAt`: set by the server, in unix seconds.
- `name`: 1–100 characters. `sharedBy`: 0–40 characters, optional.
- `covers`: up to 4 `https` album-art URLs taken from the first tracks that have art. Optional; used only for the preview page and cards.
- `tracks`: 1–2,000 descriptors in the owner's order. Whole document ≤ 1 MB.

## 4. Server: the `stash-share` Worker (`infra/share-worker`)

A new Worker with its own KV namespace (`SHARE_KV`) and a rate-limit binding.

**KV value** under key `mix:<id>`:

```json
{ "doc": { …document… }, "keyHash": "<sha256 hex of the edit key>", "deleted": false }
```

A deleted mix keeps only `{ "deleted": true }` for 180 days (KV expiration), so the app and the page can say "no longer shared" instead of "not found".

| Route | Behaviour |
|---|---|
| `POST /v1/mixes` | Body `{ doc, editKey }` (`editKey` = 32 random bytes, base64url, generated on the phone). Validates the limits in §3, stores `sha256(editKey)`, and sets `id`, `version = 1` and `updatedAt`. Returns `{ id, version, url }`. |
| `PUT /v1/mixes/{id}` | Header `X-Stash-Edit-Key`. Hashes it and compares with `keyHash` in constant time. On a match, replaces the doc and sets `version = old + 1`. Returns `{ version }`. Returns 403 on a wrong key, 404 if unknown, 410 if deleted. |
| `DELETE /v1/mixes/{id}` | Same key check. Writes the deleted marker. Returns 204. |
| `GET /v1/mixes/{id}` | Returns the doc. 404 if unknown, 410 if deleted. |
| `GET /v1/mixes/{id}/version` | Returns `{ version }`. 404 or 410 as above. The cheap follow check. |
| `GET /m/{id}` | The HTML preview page (§7). |
| `GET /t` | The HTML preview page for a single track (§7). |
| `GET /.well-known/assetlinks.json` | Android App Links file listing the Stash release and debug signing-certificate SHA-256 fingerprints. |

**Limits:**
- The rate-limit binding is keyed by client IP: creating is limited to 5 per minute; updating and deleting to 30 per minute.
- Oversized or malformed bodies get 400 or 413.
- No other authentication. Creating a mix stores metadata only (track names and IDs) and is never listed anywhere.

**Consistency:** KV reads can lag a write by up to about 60 s between regions. That's acceptable: followers see an update on their next check.

**Operator removal:** delete the KV key by hand with `wrangler kv key delete` if a mix must come down.

## 5. App: sharing a mix (owner)

**Entry points:**
- A **Share mix** action in the playlist/mix detail screen's menu.
- Home mix cards' menus.
- The Library playlist long-press menu.

Any playlist type can be shared.

**Share sheet, first time:**
- The name to share (prefilled from the playlist name).
- "Show my name as" (optional; remembered in preferences).
- **Keep it updated for followers**, on by default.
- **Create link**: `POST`, then the Android share sheet with `"<name>: <n> tracks on Stash · <url>"`.

**Share sheet, already shared:** shows the link with **Copy link**, **Share again**, **Stop sharing**, and when the mix was last updated.

**New Room table `shared_mixes`** (one schema migration):

| Column | Type | Notes |
|---|---|---|
| `playlist_id` | Long, PK, FK → playlists (cascade) | one share per playlist |
| `share_id` | String, unique | |
| `role` | `OWNER` / `FOLLOWER` | |
| `edit_key` | String? | owner only; kept in backups |
| `version` | Int | last published (owner) or last applied (follower) |
| `content_hash` | String | owner: hash of the last published track list and name |
| `auto_update` | Boolean | owner: "keep it updated" |
| `status` | `ACTIVE` / `REMOVED` | |
| `shared_by` | String? | follower: display name shown in the UI |
| `last_checked_at` | Long? | follower |

**`SharedMixPublisher`** is WorkManager unique work with a network constraint. It runs:
- after each sync finishes;
- about 30 s after the last local edit to an owned shared playlist (adding, removing or reordering tracks), so a burst of edits becomes one publish.

For each `OWNER` row with `auto_update` and status `ACTIVE`, it builds the document from the playlist's live members (`removed_at IS NULL`) in `position` order and hashes it. It sends a `PUT` only when the hash differs, then stores the new version and hash. On a network failure it retries with backoff and shows nothing to the user. On a 410 (deleted server-side) it marks the row `REMOVED`.

**Stop sharing:** `DELETE`, then remove the row.

## 6. App: opening a shared mix (recipient)

**Link handling:**
- An `https` intent filter for the share host with `autoVerify="true"`, paths `/m/` and `/t`, makes these verified App Links.
- `MainActivity` routes them in the same way it routes `stash://track` today.
- Old `stash://track?t=&a=` links keep working and get the new single-track behaviour.

**Mix screen**, built from `GET /v1/mixes/{id}`; nothing is written yet:
- A header with name, "shared by", track count and last update time.
- A cover mosaic from `covers`, and the track list.
- Actions: **Play**, **Follow**, **Save a copy**.

If this mix is already followed on this phone, the screen shows **Following** and **Unfollow** instead of Follow.

**Play, Follow and Save a copy** persist the descriptors (§2). Then:
- **Play:** queues the tracks with `setQueue`.
- **Follow:** creates a playlist and a `FOLLOWER` row.
  - Playlist fields: `type = CUSTOM`, `source = LOCAL`, `source_id = "share:<id>"`, `sync_enabled = false`.
  - The playlist is **read-only**: add, remove, reorder and rename are hidden for playlists that have a `FOLLOWER` row. The header reads "Following · from <sharedBy>".
  - It shows up in Library and in **Manage playlists** with its own switch, so it downloads only when switched on, under the existing Download rules.
  - **The plan must verify that no Spotify/YouTube sync path deactivates or deletes `LOCAL` playlists, and that orphan cleanup treats a followed playlist's tracks like any other playlist membership.**
- **Save a copy:** creates an ordinary editable `CUSTOM`/`LOCAL` playlist with no `shared_mixes` row.

**`SharedMixFollower`** checks for updates:
- after each sync;
- on app start, at most once every 6 hours per followed mix.

For each `FOLLOWER` row with status `ACTIVE`:
- It calls `GET …/version`. If the version is newer, it calls `GET …/{id}` and reconciles:
  - descriptors not in the playlist are persisted and added;
  - members no longer in the doc are soft-removed (`removed_at`), then go through the same cleanup as a synced playlist's removed tracks;
  - `position` is rewritten to the doc's order;
  - the name is updated.
- On a **410**: the row becomes `REMOVED` and the playlist turns into an ordinary editable playlist. The user sees a one-time message: "<sharedBy or 'The owner'> stopped sharing this mix; you keep your copy."
- On a **404**: treated the same as 410.

**Unfollow:** deletes the `FOLLOWER` row and the playlist. Tracks are kept only if another playlist or a like claims them, following the existing orphan rules.

**Single-track link** `/t?t&a&al&d&isrc&sp&yt`: shows a small card with **Play** and **Add to library**.

**Errors:**
- **No network:** "Couldn't load this mix. Check your connection" with a Retry button.
- **410:** "This mix is no longer shared."
- **404:** "This link doesn't point to a mix."
- **`v` > 1:** "Update Stash to open this mix."
- **Tracks the chain can't resolve** use the existing unavailable state, and playback skips them.

**Share sheet change:** the Track share sheet's "Stash link" row produces the new `https://…/t?…` link instead of `stash://track`.

## 7. Preview pages

Server-rendered HTML from the Worker, with **every string HTML-escaped**:
- **`/m/{id}`:** name, "shared by", track count, and the first 10 tracks.
  - **Open in Stash:** an Android `intent://` URL with the https link as fallback.
  - **Get Stash:** the GitHub latest-release URL.
  - Open Graph tags (`og:title` = name, `og:description` = "<n> tracks · shared on Stash", `og:image` = first cover), so chat apps show a card.
- **`/t`:** title, artist, and the same two buttons.
- **Deleted or unknown mix:** a short "no longer shared" or "not found" page with the Get Stash button.

## 8. Privacy and disclosure

- **What leaves the phone:** only the shared mix's name, its track descriptors, up to 4 cover URLs and, if chosen, a display name. No install ID, account data or listening history is sent.
- **Who can see it:** anyone with the link can read the mix. The ID can't be guessed, and nothing lists mixes.
- **README "What Stash talks to":** add the share host and what's sent to it.

## 9. Rejected alternatives

- **D1, one row per track:** allows cross-mix queries, but nothing needs them. About 110 writes per publish and more schema. Revisit only if social discovery is ever built.
- **Whole mix encoded in the link:** needs no server, but 110 tracks make a link of several KB (chat apps truncate it), and it can't be followed or previewed. Only the single-track `/t` link carries its data in the URL.
- **A custom domain now:** better reach and nicer links, but the owner chose zero cost for v1. The host is a single setting so the switch is cheap later; the same domain would also give the lossless relay a second address.

## 10. Testing

**Worker** (node test runner, same style as `infra/lossless-relay`):
- create, update and delete with the right and wrong edit key;
- only the key hash is stored;
- version increments;
- the size, track-count and name-length limits;
- rate-limit responses;
- the deleted marker (410) and unknown IDs (404);
- the preview page escapes `<script>` in the name, "shared by" and titles;
- `assetlinks.json` content;
- `/version` for a live, deleted and unknown mix.

**App unit tests:**
- mapping a `TrackEntity` to a descriptor and back, for every field;
- reconciling a followed playlist (add, remove, reorder, rename, 410 → ordinary playlist);
- the publisher only `PUT`s on a hash change and marks the row `REMOVED` on a 410;
- link parsing (`/m/<id>`, `/t?…`, old `stash://track`);
- the `shared_mixes` migration.

**Device test,** with two Stash users on one phone (the debug and release builds are separate packages with separate libraries):
1. The release app shares a mix.
2. The debug app opens the link: the App Link goes straight into the app.
3. The debug app follows the mix.
4. The release app adds a track and syncs; the debug app receives it on its next check.
5. The release app stops sharing; the debug app's playlist becomes an ordinary playlist.
6. Save a copy is also tested.

## 11. Rollout

1. Deploy `stash-share` (it's harmless on its own) and put both signing fingerprints in `assetlinks.json`.
2. Ship the app release that adds the intent filters, the share sheet, the follower and publisher jobs, and the migration.
3. Later and optional: add a custom domain as a second host, with the app accepting both.

## 12. Out of scope for v1

- Accounts, follower lists or counts, and a public directory.
- Uploaded custom cover images.
- Sharing a Stash Mix *recipe* instead of its tracks.
- Comments or likes on shared mixes.
- iOS or web playback.
- Listen Together (a separate design).
