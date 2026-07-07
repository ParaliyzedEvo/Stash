package com.stash.data.download.lossless.amz

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [AmzApiClient] against a [MockWebServer] emulating the
 * amz.squid.wtf proxy. The shared-client captcha interceptor is not
 * exercised here (it's a separate concern bound on the production
 * OkHttpClient) — a bare client is injected.
 */
class AmzApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: AmzApiClient

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        client = AmzApiClient(OkHttpClient()).apply {
            baseUrl = server.url("/api").toString().removeSuffix("/")
        }
    }

    @After fun tearDown() = server.shutdown()

    @Test fun `search parses captured trackList and sends query plus content_type`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(SEARCH_JSON))

        val items = client.search("Kanye West Can't Tell Me Nothing")

        assertThat(items).hasSize(1)
        assertThat(items[0].asin).isEqualTo("B07NHH5X4P")
        assertThat(items[0].primaryArtistName).isEqualTo("Kanye West")

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).endsWith("/search")
        val body = request.body.readUtf8()
        assertThat(body).contains("Kanye West Can't Tell Me Nothing")
        assertThat(body).contains("\"content_type\":\"TRACK\"")
        // Must NOT send `country`: it routes to a stale per-country Amazon
        // session that returns empty results / 503 (regression guard).
        assertThat(body).doesNotContain("country")
    }

    @Test fun `track returns parsed metadata`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(TRACK_JSON))

        val track = client.track("B07K7VJXVG")

        assertThat(track).isNotNull()
        assertThat(track!!.meta.asin).isEqualTo("B07K7VJXVG")
        assertThat(track.meta.isrc).isEqualTo("USUM71807761")
        assertThat(track.meta.isExplicit).isTrue()

        val request = server.takeRequest()
        assertThat(request.path).endsWith("/track")
        val body = request.body.readUtf8()
        assertThat(body).contains("\"asin\":\"B07K7VJXVG\"")
        // No explicit tier → the proxy default ("best", native max quality).
        assertThat(body).contains("\"tier\":\"best\"")
    }

    @Test fun `track sends the requested tier in the body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(TRACK_JSON))

        client.track("B07K7VJXVG", tier = "hd")

        // Data-saver / per-network tier (e.g. cellular Hi-Res -> amz "hd")
        // must reach the wire, not the hardcoded "best".
        assertThat(server.takeRequest().body.readUtf8()).contains("\"tier\":\"hd\"")
    }

    @Test fun `streamUrl encodes the requested tier`() {
        client.baseUrl = "https://amz.squid.wtf/api"
        assertThat(client.streamUrl("B07K7VJXVG", tier = "ultrahd"))
            .isEqualTo("https://amz.squid.wtf/api/stream?asin=B07K7VJXVG&tier=ultrahd")
    }

    @Test fun `track parses drm key and resolves relative stream url to absolute`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(TRACK_JSON_DRM))

        val track = client.track("B07K7VJXVG")

        assertThat(track).isNotNull()
        assertThat(track!!.decryptionKey).isEqualTo("8164fe2db5ebd498c8265b3e873462c1")
        assertThat(track.codec).isEqualTo("flac")
        // stream.url is site-relative; the client resolves it against the API
        // origin (the MockWebServer base) into an absolute URL.
        assertThat(track.streamUrl).isEqualTo(
            server.url("/api/stream?asin=B07K7VJXVG&tier=ultrahd").toString(),
        )
    }

    @Test fun `track leaves drm and stream null when response omits them`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(TRACK_JSON))

        val track = client.track("B07K7VJXVG")

        assertThat(track).isNotNull()
        assertThat(track!!.decryptionKey).isNull()
        assertThat(track.streamUrl).isNull()
    }

    @Test fun `search throws AmzApiException on non-2xx (real HTTP error, not a catalog miss)`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        assertThrows(AmzApiException::class.java) {
            kotlinx.coroutines.runBlocking { client.search("anything") }
        }
    }

    @Test fun `search returns empty list on a 2xx with no results (healthy catalog miss)`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"trackList":[]}"""))
        assertThat(client.search("anything")).isEmpty()
    }

    @Test fun `search throws AmzRateLimitedException on 429`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("slow down"))
        assertThrows(AmzRateLimitedException::class.java) {
            kotlinx.coroutines.runBlocking { client.search("anything") }
        }
    }

    @Test fun `track throws AmzRateLimitedException on 429`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("slow down"))
        assertThrows(AmzRateLimitedException::class.java) {
            kotlinx.coroutines.runBlocking { client.track("B0") }
        }
    }

    @Test fun `streamUrl returns the exact expected string`() {
        client.baseUrl = "https://amz.squid.wtf/api"
        assertThat(client.streamUrl("B07K7VJXVG"))
            .isEqualTo("https://amz.squid.wtf/api/stream?asin=B07K7VJXVG&tier=best")
    }

    companion object {
        private const val SEARCH_JSON = """
        {"trackList":[{"asin":"B07NHH5X4P","title":"Can't Tell Me Nothing [Explicit]",
        "primaryArtistName":"Kanye West","artistName":"Kanye West","albumArtistName":"Kanye West",
        "album":{"title":"","image":"https://m.media-amazon.com/images/I/710XWtyJ+8L.jpg"}}]}
        """

        private const val TRACK_JSON = """
        {"metadata":{"asin":"B07K7VJXVG","title":"Ghost Town [feat. PARTYNEXTDOOR] [Explicit]",
        "artist":"Kanye West feat. PARTYNEXTDOOR","album":"ye [Explicit]","album_asin":"B07K7WZSQZ",
        "album_artist":"Kanye West feat. PARTYNEXTDOOR",
        "cover":"https://m.media-amazon.com/images/I/714yVKHQM-L.SX1200_QL90.jpg",
        "cover_cdn":"https://m.media-amazon.com/images/I/714yVKHQM-L.SX1200_QL90.jpg",
        "isrc":"USUM71807761","is_explicit":true,"lyrics":{"synced":"[00:00.00]intro"}}}
        """

        // Captured /api/track shape WITH the drm + stream objects (the fields
        // the original models dropped). drm.key is the per-track AES-128 hex
        // key; stream.url is a site-relative encrypted-CMAF path.
        private const val TRACK_JSON_DRM = """
        {"metadata":{"asin":"B07K7VJXVG","title":"Ghost Town","artist":"Kanye West",
        "album":"ye","isrc":"USUM71807761","is_explicit":true},
        "drm":{"key":"8164fe2db5ebd498c8265b3e873462c1"},
        "stream":{"url":"/api/stream?asin=B07K7VJXVG&tier=ultrahd","codec":"flac",
        "sampleRate":48000,"bitrate":1411000,"representationId":"audio_ultrahd"}}
        """
    }
}
