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
)

data class LyricsResult(
    val sourceId: String,
    val plainText: String?,
    val syncedLrc: String?,
    val instrumental: Boolean,
    val language: String?,
    val sourceLyricsId: String?,
)
