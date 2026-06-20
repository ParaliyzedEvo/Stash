package com.stash.data.download.lossless.amz

import com.stash.data.download.lossless.TrackQuery

/**
 * Picks the best Amazon Music search result for a [TrackQuery] by scoring
 * each candidate on artist + title text similarity.
 *
 * A pure, stateless `object` — no I/O, no injection. Given the
 * [AmzSearchItem] list that `AmzApiClient.search(...)` returns, [best]
 * returns the highest-scoring candidate plus a confidence in `[0, 1]`,
 * or `null` if nothing clears the acceptance threshold.
 *
 * **No ISRC, no duration (spec Requirement 2).** Amazon *search* results
 * carry neither field — so this matcher deliberately ignores [TrackQuery.isrc]
 * and [TrackQuery.durationMs]. ISRC confirmation happens later, in AmzSource,
 * against the per-track `/api/track` metadata. Compare with [QobuzSource],
 * whose catalog *does* expose ISRC + duration and whose `confidence()` folds
 * both in; here only the text signals are available.
 *
 * The normalization / similarity helpers are a focused port of
 * [com.stash.data.download.lossless.qobuz.QobuzSource]'s package-internal
 * companion helpers (`normalize`, `jaccard`, `artistSimilarity`). They are
 * copied (with this citation) rather than reused directly because:
 *  - those helpers are nested in QobuzSource's `companion object` and the
 *    spec forbids reaching into them, and
 *  - the canonical normalizer, [com.stash.data.download.matching.MatchScorer],
 *    is an injected (`@Inject`/`TrackMatcher`-backed) class, which would force
 *    DI on what the spec requires to be a pure function.
 * Keeping the behaviour identical to QobuzSource means both squid.wtf-style
 * lossless sources gate matches the same way.
 */
object AmzMatcher {

    /** A candidate Amazon result and its confidence in `[0, 1]`. */
    data class AmzMatch(val item: AmzSearchItem, val confidence: Float)

    /**
     * Threshold below which a candidate is rejected outright. Mirrors
     * QobuzSource.MIN_CONFIDENCE (0.5) and MatchScorer.REJECT_THRESHOLD
     * (0.50) — the established "credible match" floor across the matching
     * code.
     */
    private const val MIN_CONFIDENCE = 0.5f

    /**
     * Returns the best-scoring candidate whose confidence clears
     * [MIN_CONFIDENCE], or `null` when the list is empty or nothing is a
     * credible match.
     */
    fun best(query: TrackQuery, candidates: List<AmzSearchItem>): AmzMatch? {
        if (candidates.isEmpty()) return null

        return candidates
            .map { AmzMatch(it, confidence(query, it)) }
            .filter { it.confidence >= MIN_CONFIDENCE }
            .maxByOrNull { it.confidence }
    }

    /**
     * Confidence on `[0, 1]` from title × artist text similarity.
     *
     * Multiplying (rather than averaging) means a near-zero on *either*
     * axis sinks the whole score — a right title by the wrong artist (or
     * vice-versa) cannot sneak past the threshold, matching QobuzSource's
     * `titleSim * artistSim * durationFactor` shape (sans the duration
     * factor, which Amazon search can't supply).
     */
    private fun confidence(query: TrackQuery, candidate: AmzSearchItem): Float {
        val candidateArtist = candidate.primaryArtistName
            ?: candidate.artistName
            ?: candidate.albumArtistName
            ?: ""

        val titleSim = jaccard(normalize(query.title), normalize(candidate.title))
        val artistSim = artistSimilarity(normalize(query.artist), normalize(candidateArtist))

        return (titleSim * artistSim).coerceIn(0f, 1f)
    }

    // ── Pure text helpers (ported from QobuzSource — see class KDoc) ──────

    /**
     * Lowercase + strip parenthetical / bracketed content, "feat./featuring"
     * suffixes, and punctuation; collapse whitespace. Keeps Unicode
     * letters/digits/symbols so non-Latin titles and stylized artist names
     * ("¥$", "$NOT", "+44") still tokenize sensibly.
     *
     * The bracket/paren stripping is what gives Amazon's decorated titles
     * ("Ghost Town [feat. PARTYNEXTDOOR] [Explicit]") their match tolerance.
     */
    internal fun normalize(s: String): String =
        s.lowercase()
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("\\[[^]]*\\]"), " ")
            .replace(Regex("(?i)\\b(feat\\.?|ft\\.?|featuring)\\b.*"), " ")
            // Elide within-word apostrophes before the punctuation pass so
            // "don't" → "dont" rather than "don t".
            .replace(Regex("[''`]"), "")
            .replace(Regex("[^\\p{L}\\p{N}\\p{S}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Jaccard similarity on whitespace-tokenized strings. */
    internal fun jaccard(a: String, b: String): Float {
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
     * larger AND at least one shared token is "distinctive" (length > 3, or
     * carries a non-alphanumeric symbol). This is the common Amazon-vs-Spotify
     * pattern where one side expands the credit:
     *
     *  - query "Kanye West" vs candidate "Kanye West feat. PARTYNEXTDOOR"
     *    (the "feat." tail is stripped by [normalize], but coverage also
     *    rescues the case where it survives).
     *  - query "¥$, Kanye West, Ty Dolla $ign" vs candidate "¥$".
     *
     * The distinctiveness gate stops generic short tokens ("the", "u2")
     * spuriously matching unrelated acts that share one. Falls back to plain
     * jaccard so partial overlaps still score reasonably.
     */
    internal fun artistSimilarity(a: String, b: String): Float {
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
