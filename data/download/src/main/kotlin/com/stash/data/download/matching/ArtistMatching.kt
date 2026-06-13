package com.stash.data.download.matching

/**
 * Shared artist-matching primitives extracted from [MatchScorer] so a future
 * Spotify-search scorer can reuse the exact same multi-artist splitting and
 * contiguous-run logic. These are pure functions with no scorer state.
 */
internal object ArtistMatching {
    /**
     * Multi-artist credit separators used by [artistParts]. Covers ASCII
     * (`,;&/|`), CJK full-width slash/middot (`／・`), and the spelled-out
     * " feat. / ft. / and " joiners.
     */
    val ARTIST_PART_SEPARATOR: Regex =
        Regex("""[,;&/／・|]|\s+(?:feat\.?|ft\.?|and)\s+""", RegexOption.IGNORE_CASE)

    /** True when [needle] appears as a contiguous run of tokens in [haystack]. */
    fun containsRun(haystack: List<String>, needle: List<String>): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        for (start in 0..(haystack.size - needle.size)) {
            if (haystack.subList(start, start + needle.size) == needle) return true
        }
        return false
    }

    /**
     * Split an artist credit into normalised comparison parts: break on
     * multi-artist separators (`,;&/／・|` and " feat./ft./and "), then strip
     * each part down to its letter/digit characters (so "Kairiki bear" and
     * "Kairikibear" coincide). Parts shorter than 2 chars are dropped.
     */
    fun artistParts(artist: String): List<String> =
        artist.split(ARTIST_PART_SEPARATOR)
            .map { part -> part.lowercase().filter { it.isLetterOrDigit() } }
            .filter { it.length >= 2 }
}
