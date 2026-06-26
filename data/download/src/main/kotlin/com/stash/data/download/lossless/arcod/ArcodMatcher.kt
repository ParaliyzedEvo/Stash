package com.stash.data.download.lossless.arcod

import com.stash.data.download.lossless.TrackQuery
import kotlin.math.abs

/**
 * A scored ARCOD search candidate. [confidence] is on `[0.0, 1.0]`.
 */
data class ArcodMatch(val item: ArcodTrackItem, val confidence: Float)

/**
 * Picks the best ARCOD (Qobuz-DL proxy) `get-music` search result for a
 * [TrackQuery]. Pure — no I/O, no injection — so it is trivially unit-tested.
 *
 * The artist+title scoring deliberately mirrors `QobuzSource.confidence()`
 * (same `normalize`/`jaccard`/`artistSimilarity` shape and 0.5 accept
 * threshold) because both consume the same upstream Qobuz catalog JSON and
 * face the same Spotify-expansion-vs-canonical artist-name mismatches. The
 * helpers are re-implemented here rather than imported so this object stays
 * self-contained and dependency-free.
 *
 * Differences from `QobuzSource.confidence()`, per the ArcodSource spec:
 *  - ISRC is a confirmation BOOST (`max(score, ISRC_CONFIDENCE)`), not a
 *    short-circuit — text scoring still runs.
 *
 * Duration is a SOFT, PERCENTAGE-based factor copied from QobuzSource — NOT a
 * hard absolute reject. (An earlier 5s absolute hard-guard rejected exact
 * artist+title matches on-device 2026-06-16: a synced track's YouTube/Spotify
 * duration routinely differs from the Qobuz master by >5s, so every candidate
 * was dropped → no_match → fell through to yt-dlp. Percentage drift tolerates
 * cross-source variance and only sinks a strong text match below the bar on a
 * dramatic mismatch, e.g. a live cut.)
 */
object ArcodMatcher {

    /** Candidates scoring below this are never returned. */
    private const val MIN_CONFIDENCE = 0.5f

    /** ISRC equality lifts confidence to at least this. */
    private const val ISRC_CONFIDENCE = 0.95f

    /**
     * Returns the highest-confidence candidate whose confidence is at least
     * [MIN_CONFIDENCE], or null when nothing crosses the bar.
     */
    fun best(query: TrackQuery, items: List<ArcodTrackItem>): ArcodMatch? =
        items
            .map { ArcodMatch(it, confidence(query, it)) }
            .filter { it.confidence >= MIN_CONFIDENCE }
            .maxByOrNull { it.confidence }

    /**
     * Confidence on `[0.0, 1.0]`: token-overlap on title times artist
     * similarity times a soft duration factor, lifted to [ISRC_CONFIDENCE]
     * when the ISRCs confirm the same recording.
     */
    private fun confidence(query: TrackQuery, item: ArcodTrackItem): Float {
        val titleSim = jaccard(normalize(query.title), normalize(item.title))
        val artistName = item.performer?.name ?: item.album?.artist?.name ?: ""
        val artistSim = artistSimilarity(normalize(query.artist), normalize(artistName))
        val textScore = titleSim * artistSim * durationFactor(query, item)

        val queryIsrc = query.isrc
        val candidateIsrc = item.isrc
        return if (queryIsrc != null && candidateIsrc != null &&
            queryIsrc.equals(candidateIsrc, ignoreCase = true)
        ) {
            maxOf(textScore, ISRC_CONFIDENCE)
        } else {
            textScore
        }
    }

    /**
     * Graduated duration-agreement factor (copied from `QobuzSource`). Drift is
     * RELATIVE to the query length, so a few-seconds cross-source difference on
     * a multi-minute track barely penalizes, while a live/extended cut (>20%
     * off) sinks an otherwise-perfect text match below [MIN_CONFIDENCE].
     * Unknown/zero durations on either side don't penalize.
     */
    private fun durationFactor(query: TrackQuery, item: ArcodTrackItem): Float {
        val queryMs = query.durationMs ?: return 1.0f
        val candidateSec = item.duration ?: return 1.0f
        if (queryMs <= 0 || candidateSec <= 0) return 1.0f
        val drift = abs(queryMs - candidateSec * 1000L).toDouble() / queryMs.toDouble()
        return when {
            drift < 0.05 -> 1.0f   // <5% — same recording almost certainly
            drift < 0.10 -> 0.85f  // 5-10% — typical compression-vs-original variance
            drift < 0.20 -> 0.6f   // 10-20% — possibly a different cut
            else -> 0.3f           // dramatic mismatch (live vs studio, etc.)
        }
    }

    // ── Pure-function helpers (mirrors QobuzSource) ──────────────────────────

    /**
     * Lowercase + strip parenthetical/bracket content, "feat./featuring"
     * suffixes, and punctuation; collapse whitespace. Keeps Unicode letters,
     * digits and symbols so stylized names ("¥$", "$NOT") survive.
     */
    private fun normalize(s: String): String =
        s.lowercase()
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("\\[[^]]*\\]"), " ")
            .replace(Regex("(?i)\\b(feat\\.?|ft\\.?|featuring)\\b.*"), " ")
            .replace(Regex("[''`]"), "")
            .replace(Regex("[^\\p{L}\\p{N}\\p{S}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Jaccard similarity on whitespace-tokenized strings. */
    private fun jaccard(a: String, b: String): Float {
        val setA = a.split(" ").filter { it.isNotEmpty() }.toSet()
        val setB = b.split(" ").filter { it.isNotEmpty() }.toSet()
        if (setA.isEmpty() || setB.isEmpty()) return 0f
        val intersection = setA.intersect(setB).size.toFloat()
        val union = setA.union(setB).size.toFloat()
        return intersection / union
    }

    /**
     * Artist-aware similarity: max of plain jaccard and subset-coverage.
     * Returns 1.0 when the smaller artist string is fully contained in the
     * larger AND shares a distinctive (length > 3, or symbol-bearing) token —
     * the common Spotify-expansion ("Daft Punk feat. Pharrell") vs
     * Qobuz-canonical ("Daft Punk") pattern. Falls back to plain jaccard.
     */
    private fun artistSimilarity(a: String, b: String): Float {
        val setA = a.split(" ").filter { it.isNotEmpty() }.toSet()
        val setB = b.split(" ").filter { it.isNotEmpty() }.toSet()
        if (setA.isEmpty() || setB.isEmpty()) return 0f

        val intersection = setA.intersect(setB)
        val union = setA.union(setB)
        val jaccardScore = intersection.size.toFloat() / union.size.toFloat()

        val smallerSize = minOf(setA.size, setB.size)
        val smallerFullyCovered = intersection.size == smallerSize
        val hasDistinctiveOverlap = intersection.any { token ->
            token.length > 3 || token.any { ch -> !ch.isLetterOrDigit() }
        }
        val coverageScore = if (smallerFullyCovered && hasDistinctiveOverlap) 1.0f else 0f

        return maxOf(jaccardScore, coverageScore)
    }
}
