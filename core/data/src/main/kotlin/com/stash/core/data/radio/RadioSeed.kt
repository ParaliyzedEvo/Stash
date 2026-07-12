package com.stash.core.data.radio

/** What the user tapped to start a station. */
sealed interface RadioSeed {
    /** Artist radio — seeded from an artist. [ytBrowseId] is known when started
     *  from the artist profile (skips a resolveArtist hop); null otherwise. */
    data class Artist(val name: String, val ytBrowseId: String? = null) : RadioSeed

    /** Song radio — seeded from a specific track. */
    data class Song(val title: String, val artist: String, val ytVideoId: String? = null) : RadioSeed
}
