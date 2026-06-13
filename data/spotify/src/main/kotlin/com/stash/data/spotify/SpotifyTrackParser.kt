package com.stash.data.spotify

import android.util.Log
import com.stash.data.spotify.model.SpotifyAlbum
import com.stash.data.spotify.model.SpotifyArtist
import com.stash.data.spotify.model.SpotifyImage
import com.stash.data.spotify.model.SpotifyTrackItem
import com.stash.data.spotify.model.SpotifyTrackObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private const val TAG = "StashSync"

private val parserJson = Json { ignoreUnknownKeys = true }

/**
 * Parses a single page of the Spotify Web API `/v1/playlists/{id}/tracks`
 * response into [SpotifyTrackItem]s plus the `next` page URL.
 *
 * Pulls `explicit` and `external_ids.isrc` from each track object — both
 * must be requested via the `fields=` query parameter in
 * [SpotifyApiClient.tryGetPlaylistTracksViaWebApi] or they won't be present
 * in the response. Missing `external_ids` yields a null [SpotifyTrackObject.isrc]
 * (legacy tracks added before ISRCs were routinely attached).
 *
 * @param responseBody The raw JSON response body.
 * @return Pair of (tracks on this page, next page URL or null).
 */
internal fun parseWebApiPlaylistPage(responseBody: String): Pair<List<SpotifyTrackItem>, String?> {
    return try {
        val root = parserJson.parseToJsonElement(responseBody).jsonObject
        val nextUrl = root["next"]?.jsonPrimitive?.contentOrNull
        val items = root["items"]?.jsonArray ?: return Pair(emptyList(), null)

        val tracks = items.mapNotNull { element ->
            try {
                val wrapper = element.jsonObject
                val trackObj = wrapper["track"]?.jsonObject ?: return@mapNotNull null

                val id = trackObj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val name = trackObj["name"]?.jsonPrimitive?.contentOrNull ?: "Unknown"
                val uri = trackObj["uri"]?.jsonPrimitive?.contentOrNull ?: "spotify:track:$id"
                val durationMs = trackObj["duration_ms"]?.jsonPrimitive?.longOrNull ?: 0L
                val explicit = trackObj["explicit"]?.jsonPrimitive?.booleanOrNull ?: false
                val isrc = trackObj["external_ids"]?.jsonObject
                    ?.get("isrc")?.jsonPrimitive?.contentOrNull

                val artists = trackObj["artists"]?.jsonArray?.mapNotNull { artistEl ->
                    val artistObj = artistEl.jsonObject
                    val artistId = artistObj["id"]?.jsonPrimitive?.contentOrNull ?: ""
                    val artistName = artistObj["name"]?.jsonPrimitive?.contentOrNull
                        ?: return@mapNotNull null
                    SpotifyArtist(id = artistId, name = artistName)
                } ?: emptyList()

                val albumObj = trackObj["album"]?.jsonObject
                val album = if (albumObj != null) {
                    val albumId = albumObj["id"]?.jsonPrimitive?.contentOrNull ?: ""
                    val albumName = albumObj["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    val albumImages = albumObj["images"]?.jsonArray?.mapNotNull { imgEl ->
                        val imgUrl = imgEl.jsonObject["url"]?.jsonPrimitive?.contentOrNull
                        if (imgUrl != null) SpotifyImage(url = imgUrl) else null
                    }
                    SpotifyAlbum(id = albumId, name = albumName, images = albumImages)
                } else {
                    null
                }

                SpotifyTrackItem(
                    track = SpotifyTrackObject(
                        id = id,
                        name = name,
                        artists = artists,
                        album = album,
                        duration_ms = durationMs,
                        uri = uri,
                        explicit = explicit,
                        isrc = isrc,
                    ),
                )
            } catch (e: Exception) {
                Log.w(TAG, "parseWebApiPlaylistPage: failed to parse track item", e)
                null
            }
        }

        Pair(tracks, nextUrl)
    } catch (e: Exception) {
        Log.e(TAG, "parseWebApiPlaylistPage: failed to parse response", e)
        Pair(emptyList(), null)
    }
}

/**
 * Parses the Spotify Web API `/v1/search?type=track` response into
 * [SpotifyTrackCandidate]s.
 *
 * Response shape: `{ "tracks": { "items": [ {track object} ] } }`. Unlike the
 * playlist endpoint there is no `item.track` wrapper — each entry in
 * `tracks.items` IS the track object. Pulls `id`, `name`, `album.name`
 * (→ albumName), `duration_ms` (→ durationMs), `explicit`, ordered
 * `artists[].name`, and `external_ids.isrc` (often absent on /search results,
 * which yields a null isrc).
 *
 * Parseable-but-empty (`tracks.items` empty or absent) returns an empty list.
 *
 * @param responseBody The raw JSON response body.
 * @return The parsed candidate tracks, in result order.
 */
internal fun parseSearchTracks(responseBody: String): List<SpotifyTrackCandidate> {
    return try {
        val items = parserJson.parseToJsonElement(responseBody).jsonObject["tracks"]
            ?.jsonObject?.get("items")
            ?.jsonArray
            ?: return emptyList()

        items.mapNotNull { element ->
            try {
                val trackObj = element.jsonObject

                val id = trackObj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val name = trackObj["name"]?.jsonPrimitive?.contentOrNull ?: "Unknown"
                val durationMs = trackObj["duration_ms"]?.jsonPrimitive?.longOrNull ?: 0L
                val explicit = trackObj["explicit"]?.jsonPrimitive?.booleanOrNull ?: false
                val isrc = trackObj["external_ids"]?.jsonObject
                    ?.get("isrc")?.jsonPrimitive?.contentOrNull

                val artists = trackObj["artists"]?.jsonArray?.mapNotNull { artistEl ->
                    artistEl.jsonObject["name"]?.jsonPrimitive?.contentOrNull
                } ?: emptyList()

                val albumName = trackObj["album"]?.jsonObject
                    ?.get("name")?.jsonPrimitive?.contentOrNull ?: ""

                SpotifyTrackCandidate(
                    id = id,
                    name = name,
                    artists = artists,
                    albumName = albumName,
                    durationMs = durationMs,
                    isrc = isrc,
                    explicit = explicit,
                )
            } catch (e: Exception) {
                Log.w(TAG, "parseSearchTracks: failed to parse track item", e)
                null
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "parseSearchTracks: failed to parse response", e)
        emptyList()
    }
}
