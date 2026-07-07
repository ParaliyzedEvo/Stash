package com.stash.data.download.lossless.qbdlx

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class QbdlxApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: QbdlxApiClient

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        client = QbdlxApiClient(
            sharedClient = OkHttpClient(),
            signer = QbdlxSigner("secret") { 1000L },
        ).also {
            it.baseUrl = server.url("/").toString().trimEnd('/')
            it.appId = "798273057"   // appId is an internal var (reads BuildConfig in prod), set here for the test
        }
    }
    @After fun tearDown() { server.shutdown() }

    @Test fun `search parses track items`() = runTest {
        server.enqueue(MockResponse().setBody("""{"tracks":{"items":[{"id":42,"title":"Murderers","isrc":"USWB10003085","duration":160,"performer":{"name":"John Frusciante"},"maximum_bit_depth":16,"maximum_sampling_rate":44.1}]}}"""))
        val items = client.search("John Frusciante Murderers", token = "tok")
        assertThat(items).hasSize(1)
        assertThat(items[0].id).isEqualTo(42)
        assertThat(server.takeRequest().getHeader("X-User-Auth-Token")).isEqualTo("tok")
    }

    @Test fun `getFileUrl Ok when url present and not restricted`() = runTest {
        server.enqueue(MockResponse().setBody("""{"url":"https://cdn/file?fmt=6","format_id":6,"bit_depth":16,"sampling_rate":44.1,"sample":false,"restrictions":[]}"""))
        val r = client.getFileUrl(trackId = 42, formatId = 27, token = "tok")
        assertThat(r).isInstanceOf(QbdlxResolveResult.Ok::class.java)
        val ok = r as QbdlxResolveResult.Ok
        assertThat(ok.url).contains("cdn/file")
        assertThat(ok.bitDepth).isEqualTo(16)
        val req = server.takeRequest()
        assertThat(req.getHeader("X-App-Id")).isEqualTo("798273057")
        assertThat(req.path).contains("request_sig=")
    }

    @Test fun `getFileUrl TokenDead on UserUnauthenticated preview`() = runTest {
        server.enqueue(MockResponse().setBody("""{"url":"https://cdn/file?fmt=5&range=20-30","format_id":5,"sample":true,"restrictions":[{"code":"UserUnauthenticated"}]}"""))
        val r = client.getFileUrl(trackId = 42, formatId = 27, token = "tok")
        assertThat(r).isInstanceOf(QbdlxResolveResult.TokenDead::class.java)
    }

    @Test fun `getFileUrl RegionLocked when restricted with no usable url`() = runTest {
        server.enqueue(MockResponse().setBody("""{"format_id":6,"restrictions":[{"code":"TrackRestrictedByRights"}]}"""))
        val r = client.getFileUrl(trackId = 42, formatId = 27, token = "tok")
        assertThat(r).isInstanceOf(QbdlxResolveResult.RegionLocked::class.java)
    }

    @Test fun `getFileUrl accepts format-downgrade to CD FLAC`() = runTest {
        server.enqueue(MockResponse().setBody("""{"url":"https://cdn/file?fmt=6","format_id":6,"restrictions":[{"code":"FormatRestrictedByFormatAvailability"}]}"""))
        val r = client.getFileUrl(trackId = 42, formatId = 27, token = "tok")
        assertThat(r).isInstanceOf(QbdlxResolveResult.Ok::class.java)  // fmt6 is still lossless
    }

    @Test fun `search 401 throws TokenDead-signalling exception`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"status":"error","code":401}"""))
        try { client.search("x", token = "tok"); assertThat(false).isTrue() }
        catch (e: QbdlxAuthException) { assertThat(e.status).isEqualTo(401) }
    }
}
