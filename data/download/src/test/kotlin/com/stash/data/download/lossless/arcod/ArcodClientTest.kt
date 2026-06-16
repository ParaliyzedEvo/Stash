package com.stash.data.download.lossless.arcod

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ArcodClient] against a [MockWebServer]. The injected
 * [ArcodAuthInterceptor] is built over a mocked store whose `session()` returns
 * null, so it attaches no header (harmless) and never hits a real DataStore.
 */
class ArcodClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: ArcodClient

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        val store = mockk<ArcodCredentialStore>(relaxed = true)
        coEvery { store.session() } returns null
        val refresher = mockk<ArcodTokenRefresher>(relaxed = true)
        val interceptor = ArcodAuthInterceptor(store, refresher)
        client = ArcodClient(
            sharedClient = OkHttpClient(),
            authInterceptor = interceptor,
        ).apply {
            baseUrl = server.url("/api").toString().trimEnd('/')
        }
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun `search returns parsed items`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(SEARCH_BODY))

        val items = client.search("Ja Rule Murderers")

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertTrue(request.path!!.startsWith("/api/get-music"))
        assertTrue(request.path!!.contains("q="))
        assertTrue(request.path!!.contains("offset=0"))

        assertEquals(1, items.size)
        assertEquals(8767428L, items[0].id)
        assertEquals("0093624804567", items[0].album?.id)
        assertEquals("USAT20300456", items[0].isrc)
    }

    @Test fun `search returns empty on non-2xx`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        assertTrue(client.search("x").isEmpty())
    }

    @Test fun `createJob posts to v2 downloads and parses pending job`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"id":"abc","status":"pending","progress":0}"""),
        )

        val job = client.createJob(SAMPLE_REQUEST)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path!!.endsWith("/api/v2/downloads"))
        assertNotNull(job)
        assertEquals("abc", job!!.id)
        assertEquals("pending", job.status)
    }

    @Test fun `pollStatus returns completed job after pending polls`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(PENDING_BODY))
        server.enqueue(MockResponse().setResponseCode(200).setBody(PENDING_BODY))
        server.enqueue(MockResponse().setResponseCode(200).setBody(COMPLETED_BODY))

        val job = client.pollStatus("abc", timeoutMs = 2000L, intervalMs = 10L)

        assertNotNull(job)
        assertEquals("completed", job!!.status)
        assertNotNull(job.downloadUrl)
    }

    @Test fun `pollStatus returns null on error status`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"id":"x","status":"error","error":"nope"}"""),
        )

        assertNull(client.pollStatus("x", timeoutMs = 2000L, intervalMs = 10L))
    }

    @Test fun `pollStatus returns null on timeout without hanging`() = runTest {
        repeat(5) { server.enqueue(MockResponse().setResponseCode(200).setBody(PENDING_BODY)) }

        assertNull(client.pollStatus("x", timeoutMs = 50L, intervalMs = 10L))
    }

    @Test fun `createJob throws on 429`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("slow down"))

        try {
            client.createJob(SAMPLE_REQUEST)
            fail("expected ArcodRateLimitedException")
        } catch (e: ArcodRateLimitedException) {
            // expected
        }
    }

    private companion object {
        val SAMPLE_REQUEST = ArcodJobRequest(
            albumId = "0093624804567",
            trackId = 8767428L,
            albumTitle = "Blood In My Eye",
            artistName = "Ja Rule",
            artistId = 12345L,
            coverUrl = "https://img.arcod.xyz/large.jpg",
            releaseDate = "2003-11-04",
            tracksCount = 13,
        )

        val SEARCH_BODY = """
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
                      "performer": { "name": "Ja Rule", "id": 12345 },
                      "album": { "id": "0093624804567", "title": "Blood In My Eye" }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        const val PENDING_BODY = """{"id":"abc","status":"pending","progress":40}"""

        val COMPLETED_BODY = """
            {"id":"abc","status":"completed","progress":100,"fileName":"x.flac","fileSize":16416910,"downloadUrl":"https://dl.arcod.xyz/downloads/abc/x.flac"}
        """.trimIndent()
    }
}
