package com.stash.data.download.lossless

/**
 * User-selectable lossless quality tier. Maps directly to Qobuz's integer
 * `format_id` codes used by both squid.wtf and kennyy.com.br proxies.
 *
 * The catalog API may legitimately return a lower tier than requested when
 * the upstream master doesn't exist at the requested resolution (e.g. asking
 * for `MAX` (24/192) on a track Qobuz only has at CD quality returns 16/44.1).
 * That's why the v0.9.11 design persists the *delivered* bit-depth/sample-rate
 * read from the file (via `AudioDurationExtractor`), not the requested tier.
 *
 * `MP3_320 (5)` is intentionally excluded — this enum governs the lossless
 * picker only; the lossy/yt-dlp path is governed by `core.model.QualityTier`.
 */
enum class LosslessQualityTier(
    /** Qobuz `format_id` code passed to `apiClient.getFileUrl(...)`. */
    val qobuzCode: Int,
    /** Human-readable label for the Settings radio row. */
    val displayLabel: String,
    /** Approximate file size for a 4-minute track at this tier. */
    val sizeHint: String,
) {
    /**
     * Lossy floor for Save Data. Qobuz `format_id = 5` is MP3 320 — verified live
     * on 2026-07-30: requesting 5 returns `format_id=5, mime=audio/mpeg`.
     *
     * This exists because "Save Data" previously mapped to [CD], which is still
     * FLAC — about 28 MB for a four-minute track — so in practice the setting
     * changed nothing that mattered. A user on a metered plan turning it on saved
     * essentially zero bytes.
     *
     * Deliberately NOT offered in the Settings quality radios — those choose
     * between lossless tiers, and this is the automatic floor Save Data applies on
     * top of whatever the user picked. It is a lossy member of an enum named for
     * lossless tiers, which is a wart; the alternative was a parallel type threaded
     * through five resolvers for one extra case.
     */
    DATA_SAVER(qobuzCode = 5, displayLabel = "Data Saver (MP3 320)", sizeHint = "~10 MB / 4 min"),
    CD(qobuzCode = 6, displayLabel = "CD (16-bit/44.1 kHz)", sizeHint = "~28 MB / 4 min"),
    HI_RES(qobuzCode = 7, displayLabel = "Hi-Res (24-bit/96 kHz)", sizeHint = "~70 MB / 4 min"),
    MAX(qobuzCode = 27, displayLabel = "Max (24-bit/192 kHz)", sizeHint = "~140 MB / 4 min");
}
