package com.stash.data.download.lossless.antra

import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [AntraClient] hitting a [MockWebServer] that emulates
 * antra.hoshi.cfd. Verifies request shape (paths, POST bodies), JSON
 * parsing, the bounded job-status poll loop, and Cloudflare 403 detection.
 *
 * The poll interval is shrunk to ~0 ms so the lifecycle test doesn't sit
 * through the production 2 s cadence.
 */
class AntraClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: AntraClient

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        val raw = OkHttpClient()
        client = AntraClient(
            sharedClient = raw,
            cookieInterceptor = mockk(relaxed = true),
        ).apply {
            httpClient = raw
            baseUrl = server.url("").toString().removeSuffix("/")
            pollIntervalMs = 1
        }
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private val spotifyUrl = "https://open.spotify.com/track/abc123"

    @Test fun `resolve posts url and format and parses tracks`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"release_name":"Super Fly","artist":"Curtis Mayfield",
                    "tracks":[{"index":0,"title":"Pusherman"}]}""",
            ),
        )

        val resolve = client.resolve(spotifyUrl)

        assertEquals("Pusherman", resolve?.tracks?.firstOrNull()?.title)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertTrue(recorded.path!!.endsWith("/api/resolve"))
        assertEquals(
            """{"url":"$spotifyUrl","format":"lossless-24"}""",
            recorded.body.readUtf8(),
        )
    }

    @Test fun `me parses quota`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"username":"rawn","singles_left":99}"""),
        )

        val me = client.me()

        assertEquals("rawn", me?.username)
        assertEquals(99, me?.singles_left)
        assertTrue(server.takeRequest().path!!.endsWith("/api/auth/me"))
    }

    @Test fun `downloadUrl builds the download path`() {
        assertTrue(client.downloadUrl("job-9").endsWith("/api/jobs/job-9/download"))
    }

    @Test fun `downloadTo streams bytes to the destination file`() = runTest {
        val flac = "FLACBYTES".toByteArray()
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(okio.Buffer().write(flac)),
        )
        val dest = java.io.File.createTempFile("antra", ".flac").apply { deleteOnExit() }

        val ok = client.downloadTo("job-7", dest)

        assertTrue(ok)
        assertEquals("FLACBYTES", dest.readText())
        assertTrue(server.takeRequest().path!!.endsWith("/api/jobs/job-7/download"))
    }

    @Test fun `downloadTo returns false on non-2xx`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("nope"))
        val dest = java.io.File.createTempFile("antra", ".flac").apply { deleteOnExit() }

        assertEquals(false, client.downloadTo("job-7", dest))
    }

    @Test fun `createJob posts range and pollStatus loops to complete`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"job_id":"job-7","ws_token":"t"}"""),
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"job_id":"job-7","status":"queued"}"""),
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"job_id":"job-7","status":"complete","filename":"Pusherman.flac"}"""),
        )

        val created = client.createJob(spotifyUrl, startIndex = 0, endIndex = 1)
        assertEquals("job-7", created?.job_id)

        val createReq = server.takeRequest()
        assertEquals("POST", createReq.method)
        assertTrue(createReq.path!!.endsWith("/api/jobs"))
        assertEquals(
            """{"url":"$spotifyUrl","format":"lossless-24","start_index":0,"end_index":1}""",
            createReq.body.readUtf8(),
        )

        val status = client.pollStatus("job-7")
        assertEquals("complete", status.status)
        assertEquals("Pusherman.flac", status.filename)
        // queued + complete = two status fetches.
        assertTrue(server.takeRequest().path!!.endsWith("/api/jobs/job-7/status"))
        assertTrue(server.takeRequest().path!!.endsWith("/api/jobs/job-7/status"))
    }

    @Test fun `non-2xx resolve returns null`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        assertNull(client.resolve(spotifyUrl))
    }

    @Test fun `cloudflare 403 throws AntraCloudflareException`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setHeader("cf-mitigated", "challenge")
                .setBody("blocked"),
        )

        assertThrows(AntraCloudflareException::class.java) {
            runBlockingResolve()
        }
    }

    @Test fun `createJob 429 throws AntraRateLimitedException`() = runTest {
        // antra enforces one concurrent job; a 2nd POST /api/jobs while one
        // is running returns 429. Must surface as a typed exception so the
        // caller can back off (not trip the failure circuit breaker).
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"job in progress"}"""))

        assertThrows(AntraRateLimitedException::class.java) {
            kotlinx.coroutines.runBlocking { client.createJob(spotifyUrl, startIndex = 0, endIndex = 1) }
        }
    }

    // assertThrows needs a non-suspending throwing call; bridge through here.
    private fun runBlockingResolve() = kotlinx.coroutines.runBlocking {
        client.resolve(spotifyUrl)
    }
}
