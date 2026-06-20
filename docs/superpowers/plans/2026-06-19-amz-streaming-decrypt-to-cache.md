# amz streaming via decrypt-to-cache (slice 4)

Status: in progress 2026-06-19, branch `fix/remove-antra`.

## Why
amz serves CENC-encrypted CMAF that cannot be progressively streamed — the
per-track AES key requires a whole-file `ffmpeg -decryption_key` pass (slices
1–3, download path, validated on-device). The OLD amz stream path
(`StashMediaSourceFactory.amzFactory` → progressive OkHttpDataSource) played the
encrypted bytes directly = silence. Slice 4 makes amz streaming work by
decrypting the whole track to a local cache file and handing the player a
`file://` URL — it then plays like a downloaded track.

## Seamlessness (user ask: "best judgement for a seamless experience")
Reuses existing infra so only the FIRST tapped amz track stalls:
- **Next-track prefetch** — `PrefetchOrchestrator.onPlaybackProgress` already
  calls `streamResolver.resolve(nextTrack)` at 60% and caches the result in
  `StreamUrlCache`. If amz `resolve()` does the decrypt, the next track is
  decrypted ahead of auto-advance → instant. Free, no new code.
- **First-track spinner** — the foreground resolve (`PlayerRepositoryImpl`
  ~1256) awaits `resolve()` with NO timeout, behind the existing streaming
  loading state. A multi-second amz fetch+decrypt just keeps the spinner up.
- **Routing** — `isAmzOrigin` = `scheme in {http,https} && origin=="amz"`. A
  `file://` decrypted URL has scheme `file` → falls through to `localFactory`
  (DefaultMediaSourceFactory) automatically. No `StashPlaybackService` change.

## Components
1. `AmzStreamFileProvider` (NEW, data/download/.../amz/):
   `suspend fun resolveLocalFile(asin, encryptedStreamUrl, key): File?`
   - cache dir `<cacheDir>/amz_stream/`, file `<asin>.flac`.
   - cache HIT (exists & non-empty) → return it (replay/re-resolve instant).
   - MISS → build `SourceResult(sourceId="amz", downloadUrl=encryptedStreamUrl,
     decryptionKey=key, format=flac)` and reuse `LosslessUrlDownloader.download`
     (slice-3 fetch+decrypt+cleanup, already tested) into `<asin>.flac`.
   - after a successful write, LRU-evict the dir to a size cap (oldest-mtime
     first) so cached FLACs don't grow unbounded.
   - any failure → null (resolver falls through to next source).
2. `AmzStreamResolver` (MODIFY): after the `/api/track` lookup (now yields
   `AmzTrack` with `decryptionKey` + `streamUrl`), call the provider; return
   `StreamUrl(url="file://"+file.absolutePath, expiresAtMs=Long.MAX_VALUE,
   codec="flac", coverArtUrl=…, origin="amz")`. If key/streamUrl missing or the
   provider returns null → return null.

## Out of scope (follow-ups)
- Data-saver per-tier request (request hd/high instead of best on
  cellular/save-data) — folds into the parent streaming-data-efficiency feature.
- Removing the now-unused `amzFactory`/`isAmzOrigin` progressive branch from
  `StashMediaSourceFactory` (harmless dead code; leave until amz lands).

## Tests (TDD)
- `AmzStreamFileProviderTest`: cache hit returns existing file w/o re-download;
  miss fetches+decrypts (mock LosslessUrlDownloader or MockWebServer+AmzDecryptor)
  and writes `<asin>.flac`; eviction trims oldest when over cap; failure → null.
- `AmzStreamResolverTest`: happy path returns a `file://` StreamUrl with the
  decrypted path; provider-null → resolver null; no-key → resolver null.
