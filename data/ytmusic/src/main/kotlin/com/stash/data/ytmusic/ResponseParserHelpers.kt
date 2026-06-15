package com.stash.data.ytmusic

import com.stash.data.ytmusic.model.TrackSummary
import kotlinx.serialization.json.JsonObject

/**
 * Cross-file renderer-parsing helpers shared by [SearchResponseParser] and
 * [ArtistResponseParser].
 *
 * The search "Songs" shelf and the artist "Popular" shelf both ship their rows
 * as `musicResponsiveListItemRenderer` objects with identical flex/fixed column
 * semantics — extracting a single helper avoids copy-pasting ~40 lines of
 * column-walking code into two places.
 *
 * Kept `internal` so it's a module-private contract: parser files in
 * `data.ytmusic` may use it; no other module should need it.
 */

/**
 * Subtitle type labels that InnerTube places in search shelf subtitle runs.
 * These are NOT artist names and must be filtered out during artist extraction.
 * Covers both English and common localized labels.
 */
internal val SUBTITLE_TYPE_LABELS = setOf(
    "Song", "Video", "Album", "Single", "EP", "Playlist", "Podcast",
    "Artist", "Remix", "Live", "Performance",
    // Common localized type labels
    "Canción", "Chanson", "Lied", "Brano", "Música",
)

/**
 * Spec §8 Open Question 1: InnerTube returns artists with either `UC…`
 * (channel) or `MPLAUC…` (music channel) browseIds. Cache-key stability
 * requires a single form — we strip the `MPLA` prefix only when it is
 * immediately followed by `UC`, leaving other unknown `MPLA`-prefixed ids
 * (e.g. `MPLARZ…`) untouched to avoid truncating forms we don't recognize.
 *
 * Top-level `internal` so parser tests can exercise it directly.
 */
internal fun normalizeArtistBrowseId(browseId: String): String =
    if (browseId.startsWith("MPLAUC")) browseId.removePrefix("MPLA") else browseId

/**
 * Parses a `musicResponsiveListItemRenderer` into a [TrackSummary].
 *
 * Expected shape:
 * ```
 * {
 *   "playlistItemData": { "videoId": "..." },     // or overlay fallback
 *   "flexColumns": [                              // [title, artists, album?]
 *     { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [...] } } },
 *     ...
 *   ],
 *   "fixedColumns": [                             // [duration?]
 *     { "musicResponsiveListItemFixedColumnRenderer": { "text": { "runs": [...] } } }
 *   ],
 *   "thumbnail": { "musicThumbnailRenderer": { "thumbnail": { "thumbnails": [...] } } }
 * }
 * ```
 *
 * Returns null when a required field (videoId, title) is missing so callers
 * can `mapNotNull` over a shelf's items without filtering separately.
 *
 * @param renderer The parsed `musicResponsiveListItemRenderer` object.
 * @return A [TrackSummary], or null if the row is malformed.
 */
internal fun parseTrackSummaryFromListItem(
    renderer: JsonObject,
    fallbackArtist: String? = null,
): TrackSummary? {
    val videoId = renderer["playlistItemData"]?.asObject()
        ?.get("videoId")?.asString()
        ?: renderer.navigatePath(
            "overlay", "musicItemThumbnailOverlayRenderer", "content",
            "musicPlayButtonRenderer", "playNavigationEndpoint",
            "watchEndpoint", "videoId",
        )?.asString()
        ?: return null

    val flexColumns = renderer["flexColumns"]?.asArray() ?: return null
    val title = flexColumns.getOrNull(0)?.asObject()
        ?.navigatePath("musicResponsiveListItemFlexColumnRenderer", "text", "runs")
        ?.firstArray()?.firstOrNull()?.asObject()
        ?.get("text")?.asString()
        ?: return null

    // Album-page tracklists omit the artist column from flexColumns (the
    // artist is shown once in the album header). The result is an empty
    // artist string here, which breaks downstream lossless matching
    // (Qobuz/Kennyy score on artist+title and can't find candidates
    // without an artist). The caller — typically AlbumResponseParser —
    // passes the album header's artist as fallbackArtist so per-row
    // artists default to that when the shelf row carries none.
    val artistRuns = flexColumns.getOrNull(1)?.asObject()
        ?.navigatePath("musicResponsiveListItemFlexColumnRenderer", "text", "runs")
        ?.asArray()

    // Search shelf subtitle runs are a flat sequence like:
    //   "Song" • "Alan Walker" (nav→UC…) • "Faded" (nav→MPREb_…) • "3:32"
    // We classify each run by its browseEndpoint prefix to extract only
    // artist names. Runs with no endpoint are type labels, separators, or
    // duration tokens — all ignored for the artist field.
    val artistNames = mutableListOf<String>()
    var parsedAlbumFromSubtitle: String? = null
    val hasNavigatableRuns = artistRuns?.any { run ->
        run.asObject()?.navigatePath(
            "navigationEndpoint", "browseEndpoint", "browseId",
        )?.asString() != null
    } ?: false

    if (hasNavigatableRuns) {
        // Prefer navigation-endpoint-based classification.
        artistRuns?.forEach { run ->
            val obj = run.asObject() ?: return@forEach
            val text = obj["text"]?.asString() ?: return@forEach
            val browseId = obj.navigatePath(
                "navigationEndpoint", "browseEndpoint", "browseId",
            )?.asString()
            when {
                browseId != null && (browseId.startsWith("UC") || browseId.startsWith("MPLAUC")) ->
                    artistNames.add(text)
                browseId != null && browseId.startsWith("MPREb_") ->
                    if (parsedAlbumFromSubtitle == null) parsedAlbumFromSubtitle = text
                // Runs without endpoints (separators, type labels, durations) are skipped.
            }
        }
    } else {
        // Fallback: no runs have browse endpoints (some shelf layouts).
        // Filter out separators, type labels, and duration tokens.
        artistRuns
            ?.mapNotNull { it.asObject()?.get("text")?.asString() }
            ?.filterNot { text ->
                text == " & " || text == ", " || text == " x " || text == " • " ||
                    text == " · " || text.isBlank() ||
                    text.matches(DURATION_REGEX) ||
                    text in SUBTITLE_TYPE_LABELS
            }
            ?.let { artistNames.addAll(it) }
    }

    val parsedArtist = artistNames.joinToString(", ")
    val artist = parsedArtist.ifBlank { fallbackArtist.orEmpty() }

    val album = flexColumns.getOrNull(2)?.asObject()
        ?.navigatePath("musicResponsiveListItemFlexColumnRenderer", "text", "runs")
        ?.firstArray()?.firstOrNull()?.asObject()
        ?.get("text")?.asString()
        ?: parsedAlbumFromSubtitle

    val thumbnails = renderer.navigatePath(
        "thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails",
    )?.firstArray()
    val thumbnailUrl = com.stash.core.common.ArtUrlUpgrader.upgrade(
        thumbnails?.maxByOrNull {
            it.asObject()?.get("width")?.asString()?.toIntOrNull() ?: 0
        }?.asObject()?.get("url")?.asString(),
    )

    val durationText = renderer["fixedColumns"]?.asArray()
        ?.firstOrNull()?.asObject()
        ?.navigatePath("musicResponsiveListItemFixedColumnRenderer", "text", "runs")
        ?.firstArray()?.firstOrNull()?.asObject()
        ?.get("text")?.asString()

    return TrackSummary(
        videoId = videoId,
        title = title,
        artist = artist,
        album = album,
        durationSeconds = parseDurationToSeconds(durationText),
        thumbnailUrl = thumbnailUrl,
    )
}
