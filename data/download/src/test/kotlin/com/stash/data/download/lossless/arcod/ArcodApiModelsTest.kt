package com.stash.data.download.lossless.arcod

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing/serialisation tests for the ARCOD DTOs. The captured JSON carries
 * many fields Stash doesn't model (copyright, performers, audio_info, …) and
 * mixes id types (numeric track id, string album id); these tests pin down
 * that [ArcodJson] swallows the unknowns and that the modeled fields land with
 * the right types.
 */
class ArcodApiModelsTest {

    /**
     * Realistic get-music payload: numeric track `id`, string album `id`,
     * snake_case fields we remap, and several unknown keys that must not break
     * decoding.
     */
    private val getMusicJson = """
        {
          "success": true,
          "data": {
            "tracks": {
              "items": [
                {
                  "id": 8767428,
                  "title": "Murderers (Album Version)",
                  "isrc": "USAT20300456",
                  "duration": 243,
                  "maximum_bit_depth": 24,
                  "maximum_sampling_rate": 44.1,
                  "parental_warning": true,
                  "copyright": "(C) 2003 Some Label",
                  "audio_info": { "replaygain_track_gain": -7.0 },
                  "performer": { "name": "Ja Rule", "id": 12345 },
                  "performers": "Ja Rule, MainArtist - Composer",
                  "album": {
                    "id": "0093624804567",
                    "title": "Blood In My Eye",
                    "release_date_original": "2003-11-04",
                    "tracks_count": 13,
                    "artist": { "name": "Ja Rule", "id": 12345 },
                    "image": {
                      "large": "https://img.arcod.xyz/large.jpg",
                      "small": "https://img.arcod.xyz/small.jpg"
                    }
                  }
                }
              ]
            }
          }
        }
    """.trimIndent()

    @Test fun `parses get-music with numeric track id and string album id`() {
        val resp = ArcodJson.decodeFromString<ArcodSearchResponse>(getMusicJson)

        assertTrue(resp.success)
        val item = resp.data?.tracks?.items?.firstOrNull()
        assertNotNull(item)
        item!!
        assertEquals(8767428L, item.id)
        assertEquals("Murderers (Album Version)", item.title)
        assertEquals("USAT20300456", item.isrc)
        assertEquals(243, item.duration)
        assertEquals(24, item.maxBitDepth)
        assertEquals("Ja Rule", item.performer?.name)
        assertEquals(12345L, item.performer?.id)

        val album = item.album
        assertNotNull(album)
        assertEquals("0093624804567", album!!.id)
        assertEquals("Blood In My Eye", album.title)
        assertEquals("2003-11-04", album.releaseDate)
        assertEquals(13, album.tracksCount)
        assertEquals("https://img.arcod.xyz/large.jpg", album.image?.large)
    }

    @Test fun `parses completed poll job with downloadUrl`() {
        val json = """
            {"id":"6d51ba91-36cc-4c4f-a370-5dfa18a74fb7","status":"completed","progress":100,"description":"Download ready!","error":null,"fileName":"10 - Murderers (Album Version).flac","fileSize":16416910,"downloadUrl":"https://dl.arcod.xyz/downloads/6d51ba91-36cc-4c4f-a370-5dfa18a74fb7/10_-_Murderers_(Album_Version).flac"}
        """.trimIndent()

        val job = ArcodJson.decodeFromString<ArcodJob>(json)

        assertEquals("6d51ba91-36cc-4c4f-a370-5dfa18a74fb7", job.id)
        assertEquals("completed", job.status)
        assertEquals(100, job.progress)
        assertNull(job.error)
        assertEquals(16416910L, job.fileSize)
        assertNotNull(job.downloadUrl)
    }

    @Test fun `parses url response`() {
        val json = """
            {"downloadUrl":"https://dl.arcod.xyz/downloads/x.flac","fileName":"x.flac","expiresIn":300}
        """.trimIndent()

        val url = ArcodJson.decodeFromString<ArcodUrlResponse>(json)

        assertEquals("https://dl.arcod.xyz/downloads/x.flac", url.downloadUrl)
        assertEquals("x.flac", url.fileName)
        assertEquals(300, url.expiresIn)
    }

    @Test fun `unknown keys do not break parsing`() {
        val json = """
            {"id":"abc","status":"pending","totallyUnknown":42,"nested":{"x":1}}
        """.trimIndent()

        val job = ArcodJson.decodeFromString<ArcodJob>(json)

        assertEquals("abc", job.id)
        assertEquals("pending", job.status)
        assertEquals(0, job.progress)
    }

    @Test fun `encodes job request with quality and format`() {
        val body = ArcodJson.encodeToString(
            ArcodJobRequest(
                albumId = "0093624804567",
                trackId = 8767428L,
                albumTitle = "Blood In My Eye",
                artistName = "Ja Rule",
                artistId = 12345L,
                coverUrl = "https://img.arcod.xyz/large.jpg",
                releaseDate = "2003-11-04",
                tracksCount = 13,
            ),
        )

        assertTrue(body.contains("\"quality\":27"))
        assertTrue(body.contains("\"format\":\"FLAC\""))
        assertTrue(body.contains("\"trackId\":8767428"))
        assertTrue(body.contains("\"albumId\":\"0093624804567\""))
        // Round-trips back to an equal object.
        val decoded = ArcodJson.decodeFromString<ArcodJobRequest>(body)
        assertEquals(8767428L, decoded.trackId)
        assertEquals("FLAC", decoded.format)
        assertFalse(decoded.embedLyrics)
    }
}
