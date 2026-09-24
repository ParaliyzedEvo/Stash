package com.stash.data.lyrics.source

interface LyricsSource {
    val id: String
    val displayName: String
    suspend fun resolve(query: LyricsQuery): LyricsResult?
}

data class LyricsQuery(
    val trackId: Long,
    val title: String,
    val artist: String,
    val album: String?,
    val albumArtist: String?,
    val durationMs: Long?,
    val youtubeVideoId: String?,
    /**
     * True when this fetch is blocking something the user is actively watching (the lyrics sheet's
     * on-open fetch, a Retry tap). Sources MAY trade patience for latency on this — a shorter
     * timeout, no retry — so an unreachable host fails fast instead of stalling the UI. Ignored by
     * sources with nothing to trade off.
     */
    val interactive: Boolean = false,
)

data class LyricsResult(
    val sourceId: String,
    val plainText: String?,
    val syncedLrc: String?,
    val instrumental: Boolean,
    val language: String?,
    val sourceLyricsId: String?,
    /** Raw word-synced TTML when the source has one; null for LRC/plain-only sources. */
    val ttml: String? = null,
)
