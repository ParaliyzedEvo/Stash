package com.stash.data.download.lossless.relay

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class LosslessRelayClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: LosslessRelayClient
    private var now = 1_000_000L
    private val base get() = server.url("/").toString().trimEnd('/')

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        client = LosslessRelayClient(OkHttpClient()).also { it.clock = { now } }
    }
    @After fun tearDown() { server.shutdown() }

    @Test fun `200 maps to Ok with Hz as sent and the protocol header`() = runTest {
        server.enqueue(MockResponse().setBody("""{"url":"https://cdn.example/f.flac?etsp=1","format_id":27,"bit_depth":24,"sample_rate":96000}"""))
        val r = client.mint(base, 42, 27)
        assertThat(r).isEqualTo(RelayMint.Ok("https://cdn.example/f.flac?etsp=1", 27, 24, 96_000))
        val req = server.takeRequest()
        assertThat(req.path).isEqualTo("/v1/qobuz/file?track_id=42&format_id=27")
        assertThat(req.getHeader("X-Stash-Version")).isEqualTo("1")
        assertThat(req.getHeader("X-Stash-Version")).isEqualTo(LosslessRelayClient.PROTOCOL_VERSION)
        assertThat(client.isCooled(base)).isFalse()
    }

    @Test fun `404 is NoMatch with no cooldown`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"status":"no_match"}"""))
        assertThat(client.mint(base, 42, 27)).isEqualTo(RelayMint.NoMatch)
        assertThat(client.isCooled(base)).isFalse()
    }

    @Test fun `503 busy cools the base for 60s and skips the request while cooled`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"status":"busy","retry_after":30}"""))
        assertThat(client.mint(base, 42, 27)).isEqualTo(RelayMint.Unavailable)
        assertThat(client.isCooled(base)).isTrue()
        assertThat(client.mint(base, 43, 27)).isEqualTo(RelayMint.Unavailable)
        assertThat(server.requestCount).isEqualTo(1)
        now += LosslessRelayClient.BUSY_COOLDOWN_MS + 1
        assertThat(client.isCooled(base)).isFalse()
    }

    @Test fun `502 and unreachable cool the base for 5 minutes`() = runTest {
        server.enqueue(MockResponse().setResponseCode(502).setBody("""{"status":"upstream"}"""))
        assertThat(client.mint(base, 42, 27)).isEqualTo(RelayMint.Unavailable)
        now += LosslessRelayClient.BUSY_COOLDOWN_MS + 1
        assertThat(client.isCooled(base)).isTrue()
        now += LosslessRelayClient.UNAVAILABLE_COOLDOWN_MS
        assertThat(client.isCooled(base)).isFalse()

        server.shutdown()
        assertThat(client.mint(base, 42, 27)).isEqualTo(RelayMint.Unavailable)
        assertThat(client.isCooled(base)).isTrue()
    }

    @Test fun `200 with a non-https url is Unavailable`() = runTest {
        server.enqueue(MockResponse().setBody("""{"url":"http://cdn.example/f.flac","format_id":27,"bit_depth":16,"sample_rate":44100}"""))
        assertThat(client.mint(base, 42, 27)).isEqualTo(RelayMint.Unavailable)
        assertThat(client.isCooled(base)).isTrue()
    }

    @Test fun `malformed base is Unavailable without a request`() = runTest {
        assertThat(client.mint("https://bad host", 1, 27)).isEqualTo(RelayMint.Unavailable)
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(client.isCooled("https://bad host")).isFalse()
    }

    @Test fun `200 with an unusable body cools the base`() = runTest {
        server.enqueue(MockResponse().setBody("<html>gateway error</html>"))
        assertThat(client.mint(base, 42, 27)).isEqualTo(RelayMint.Unavailable)
        assertThat(client.isCooled(base)).isTrue()
    }

    @Test fun `a body that dies mid-read cools the base`() = runTest {
        server.enqueue(MockResponse()
            .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            .setBody("""{"url":"https://cdn.example/f.flac?etsp=1","format_id":27,"bit_depth":16,"sample_rate":44100}"""))
        assertThat(client.mint(base, 42, 27)).isEqualTo(RelayMint.Unavailable)
        assertThat(client.isCooled(base)).isTrue()
    }

    @Test fun `omitted format_id echoes the requested one`() = runTest {
        server.enqueue(MockResponse().setBody("""{"url":"https://cdn.example/f.flac?etsp=1"}"""))
        val r = client.mint(base, 42, 27) as RelayMint.Ok
        assertThat(r.formatId).isEqualTo(27)
        assertThat(r.bitDepth).isEqualTo(0); assertThat(r.sampleRateHz).isEqualTo(0)
    }
}
