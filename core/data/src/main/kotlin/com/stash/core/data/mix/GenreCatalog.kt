package com.stash.core.data.mix

/**
 * Curated, bundled genre catalog for the Mix Builder — the menu of selectable
 * genres, grouped into families for browsing. Tag strings are Last.fm tags
 * (the actual tracks are always fetched live; see TagPoolBuilder). Bump
 * [VERSION] when curation changes; refresh from Last.fm chart.getTopTags during
 * curation, then hand-clean (drop non-genre junk) — do NOT fetch live.
 */
object GenreCatalog {
    const val VERSION = 1

    data class Family(val name: String, val genres: List<String>)

    val FAMILIES: List<Family> = listOf(
        Family("Electronic", listOf("house", "techno", "ambient", "lo-fi", "drum and bass", "synthwave", "idm", "trance")),
        Family("Hip-Hop & R&B", listOf("hip-hop", "rap", "trap", "r&b", "neo-soul", "boom bap")),
        Family("Rock", listOf("rock", "indie rock", "classic rock", "punk", "post-rock", "garage rock", "shoegaze")),
        Family("Metal", listOf("metal", "heavy metal", "death metal", "black metal", "doom metal")),
        Family("Pop", listOf("pop", "indie pop", "synth pop", "dream pop", "k-pop", "art pop")),
        Family("Jazz & Soul", listOf("jazz", "soul", "funk", "blues", "bossa nova", "fusion")),
        Family("Folk & Country", listOf("folk", "country", "americana", "singer-songwriter", "bluegrass")),
        Family("Classical", listOf("classical", "piano", "orchestral", "contemporary classical")),
        Family("Reggae & Dub", listOf("reggae", "dub", "dancehall", "ska")),
        Family("Latin", listOf("latin", "salsa", "reggaeton", "cumbia")),
        Family("World", listOf("afrobeat", "world", "celtic", "flamenco")),
        Family("Experimental", listOf("experimental", "noise", "drone", "avant-garde")),
    ).map { it.copy(genres = it.genres.map { g -> g.trim().lowercase() }.distinct()) }

    fun allGenres(): Set<String> = FAMILIES.flatMap { it.genres }.toSet()
}
