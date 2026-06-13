package com.stash.data.download.lossless.antra

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Parsing tests for the antra API models against canned JSON bodies shaped
 * to the 2026-06-08 HAR. Verifies the serializer tolerates unknown keys and
 * that the fields Stash relies on (auth/quota, resolved track titles, job
 * id, terminal status + filename) deserialize correctly.
 */
class AntraApiModelsTest {

    @Test fun `parses auth-me with quota`() {
        val body = """
            {"username":"rawn","is_supporter":true,"concurrent_jobs":2,
             "albums_left":10,"playlists_left":5,"singles_left":99,
             "unexpected_field":"ignored"}
        """.trimIndent()

        val me = AntraJson.decodeFromString(AntraMe.serializer(), body)

        assertThat(me.username).isEqualTo("rawn")
        assertThat(me.is_supporter).isTrue()
        assertThat(me.singles_left).isEqualTo(99)
        assertThat(me.concurrent_jobs).isEqualTo(2)
    }

    @Test fun `parses resolve with tracks`() {
        val body = """
            {"release_name":"Super Fly","release_type":"album",
             "artist":"Curtis Mayfield","artwork_url":"https://img/art.jpg",
             "tracks":[{"index":0,"title":"Pusherman","artist":"Curtis Mayfield",
                        "duration_ms":297000,"track_number":3,"explicit":false}]}
        """.trimIndent()

        val resolve = AntraJson.decodeFromString(AntraResolve.serializer(), body)

        assertThat(resolve.artist).isEqualTo("Curtis Mayfield")
        assertThat(resolve.artwork_url).isEqualTo("https://img/art.jpg")
        assertThat(resolve.tracks).hasSize(1)
        assertThat(resolve.tracks.first().title).isEqualTo("Pusherman")
        assertThat(resolve.tracks.first().duration_ms).isEqualTo(297000L)
    }

    @Test fun `parses job created`() {
        val body = """{"job_id":"job-123","ws_token":"tok-abc"}"""

        val created = AntraJson.decodeFromString(AntraJobCreated.serializer(), body)

        assertThat(created.job_id).isEqualTo("job-123")
        assertThat(created.ws_token).isEqualTo("tok-abc")
    }

    @Test fun `parses terminal job status with filename`() {
        val body = """
            {"job_id":"job-123","status":"complete","done":1,"failed":0,
             "total":1,"error":null,"filename":"Pusherman.flac"}
        """.trimIndent()

        val status = AntraJson.decodeFromString(AntraJobStatus.serializer(), body)

        assertThat(status.status).isEqualTo("complete")
        assertThat(status.filename).isEqualTo("Pusherman.flac")
        assertThat(status.done).isEqualTo(1)
        assertThat(status.error).isNull()
    }

    @Test fun `resolve tolerates absent optional fields`() {
        val resolve = AntraJson.decodeFromString(AntraResolve.serializer(), "{}")

        assertThat(resolve.tracks).isEmpty()
        assertThat(resolve.artwork_url).isNull()
        assertThat(resolve.release_name).isEmpty()
    }
}
