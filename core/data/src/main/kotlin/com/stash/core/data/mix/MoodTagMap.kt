package com.stash.core.data.mix

/**
 * Curated mood → Last.fm-tag map. Each mood id resolves to a small set of
 * canonical tags the TAG_GRAPH engine seeds from. Bump [VERSION] on change.
 */
object MoodTagMap {
    const val VERSION = 1

    val MAP: Map<String, List<String>> = mapOf(
        "chill"      to listOf("chill", "chillout", "mellow", "downtempo", "ambient"),
        "energetic"  to listOf("energetic", "upbeat", "dance", "high energy"),
        "focus"      to listOf("instrumental", "ambient", "post-rock", "study", "concentration"),
        "party"      to listOf("party", "dance", "club", "feel good"),
        "melancholy" to listOf("melancholy", "sad", "moody", "atmospheric"),
        "romantic"   to listOf("romantic", "love", "soul", "smooth"),
    )

    val ALL_MOODS: List<String> = MAP.keys.toList()

    fun expand(moodKeysCsv: String): List<String> =
        moodKeysCsv.split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .flatMap { MAP[it].orEmpty() }
            .distinct()
}
