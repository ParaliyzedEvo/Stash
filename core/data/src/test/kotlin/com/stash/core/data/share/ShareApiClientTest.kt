package com.stash.core.data.share

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.share.SharedTrack
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ShareApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ShareApiClient
    private val doc = SharedMixDocument(name = "Ambient", tracks = listOf(SharedTrack("T", "A", isrc = "X")))

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        client = ShareApiClient(OkHttpClient()).apply { baseUrl = server.url("/").toString().removeSuffix("/") }
    }
    @After fun tearDown() { server.shutdown() }

    @Test fun `create posts doc and key, returns id and version`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"Kx7Qa2pL","version":1,"url":"u"}"""))
        assertThat(client.create(doc, "KEY")).isEqualTo(ShareResult.Ok(ShareApiClient.Created("Kx7Qa2pL", 1)))
        val req = server.takeRequest()
        assertThat(req.path).isEqualTo("/v1/mixes")
        val body = req.body.readUtf8()
        assertThat(body).contains("\"editKey\":\"KEY\"")
        assertThat(body).contains("\"isrc\":\"X\"")
        assertThat(body).doesNotContain("\"sp\"") // nulls omitted
    }

    @Test fun `update sends the key header, 403 404 410 map to typed results`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"version":3}"""))
        assertThat(client.update("Kx7Qa2pL", doc, "KEY", 2)).isEqualTo(ShareResult.Ok(3))
        val put = server.takeRequest()
        assertThat(put.getHeader("X-Stash-Edit-Key")).isEqualTo("KEY")
        assertThat(put.body.readUtf8()).contains("\"baseVersion\":2")
        server.enqueue(MockResponse().setResponseCode(403))
        assertThat(client.update("Kx7Qa2pL", doc, "BAD", 2)).isEqualTo(ShareResult.Forbidden)
        server.enqueue(MockResponse().setResponseCode(404))
        assertThat(client.version("Kx7Qa2pL")).isEqualTo(ShareResult.NotFound)
        server.enqueue(MockResponse().setResponseCode(410))
        assertThat(client.get("Kx7Qa2pL")).isEqualTo(ShareResult.Gone)
        server.enqueue(MockResponse().setResponseCode(400))
        assertThat(client.update("Kx7Qa2pL", doc, "KEY", 2)).isEqualTo(ShareResult.Rejected(400))
        server.enqueue(MockResponse().setResponseCode(429))
        assertThat(client.update("Kx7Qa2pL", doc, "KEY", 2)).isInstanceOf(ShareResult.Failed::class.java)
    }

    @Test fun `get parses the doc, transport failure is Failed`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"v":1,"id":"Kx7Qa2pL","version":2,"updatedAt":5,"name":"Ambient","tracks":[{"t":"T","a":"A"}],"extra":true}"""))
        val got = client.get("Kx7Qa2pL") as ShareResult.Ok
        assertThat(got.value.version).isEqualTo(2)
        assertThat(got.value.tracks.single()).isEqualTo(SharedTrack("T", "A"))
        server.shutdown()
        assertThat(client.version("Kx7Qa2pL")).isInstanceOf(ShareResult.Failed::class.java)
    }
}
