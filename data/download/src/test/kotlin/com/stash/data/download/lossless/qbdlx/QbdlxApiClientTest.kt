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
            signer = QbdlxSigner { 1000L },
            // Every token in these tests signs under the same test pair; getFileUrl
            // reads app_id/secret from here (the real store resolves per-token).
            signingResolver = { QbdlxSigning(appId = "798273057", appSecret = "secret") },
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

    /**
     * REGRESSION (live-probed 2026-08-15): the shared pool mixes tokens minted under
     * two Qobuz apps, and a token authenticates ONLY against its own app_id. Every
     * catalog call used to send the client-wide primary, so the 312369995 tokens —
     * two thirds of the pool — 401'd on `search` and were marked dead before signing
     * ever ran. 2 of 18 tokens worked that way; 12 of 18 work with their own app_id.
     */
    @Test fun `catalog calls send the token's own app_id, not the client default`() = runTest {
        val client = QbdlxApiClient(
            sharedClient = OkHttpClient(),
            signer = QbdlxSigner { 1000L },
            signingResolver = { QbdlxSigning(appId = "312369995", appSecret = "other-secret") },
        ).also {
            it.baseUrl = server.url("/").toString().trimEnd('/')
            it.appId = "798273057"
        }
        server.enqueue(MockResponse().setBody("""{"tracks":{"items":[]}}"""))

        client.search("anything", token = "second-app-token")

        val req = server.takeRequest()
        assertThat(req.getHeader("X-App-Id")).isEqualTo("312369995")
        assertThat(req.path).contains("app_id=312369995")
        assertThat(req.path).doesNotContain("798273057")
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

    /**
     * A banned Qobuz account answers 403 USER_BLOCKED. That is a dead TOKEN, not a
     * sick service: it must surface as QbdlxAuthException so the source marks it
     * dead and rotates. Treated as a generic failure it never rotates, and because
     * the active token is sticky one banned account silently downgrades every play
     * to lossy YouTube while live tokens sit unused.
     */
    @Test fun `403 USER_BLOCKED is a dead token, not a service failure`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(403).setBody(
                """{"status":"error","code":403,"message":"Account is blocked","error_code":"USER_BLOCKED"}"""
            )
        )
        try {
            client.search("x", token = "tok")
            assertThat(false).isTrue()
        } catch (e: QbdlxAuthException) {
            assertThat(e.status).isEqualTo(403)
        }
    }

    /** A 403 that is NOT a ban stays transient — don't burn a good token on it. */
    @Test fun `other 403s remain transient api errors`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(403).setBody("""{"status":"error","code":403,"message":"Rate limited"}""")
        )
        try {
            client.search("x", token = "tok")
            assertThat(false).isTrue()
        } catch (e: QbdlxApiException) {
            assertThat(e.status).isEqualTo(403)
        }
    }

    @Test fun `search 401 throws TokenDead-signalling exception`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"status":"error","code":401}"""))
        try { client.search("x", token = "tok"); assertThat(false).isTrue() }
        catch (e: QbdlxAuthException) { assertThat(e.status).isEqualTo(401) }
    }

    @Test fun `getFeaturedAlbums sends type + genre_id + app_id and parses`() = runTest {
        server.enqueue(MockResponse().setBody("""{"albums":{"items":[
            {"id":"a1","title":"T","image":{"large":"L"},"artist":{"name":"AR"},
             "release_date_original":"2026-01-02","tracks_count":9}]}}"""))
        val items = client.getFeaturedAlbums("best-sellers", genreId = 112, token = "tok")
        val req = server.takeRequest()
        assertThat(req.path).contains("album/getFeatured")
        assertThat(req.path).contains("type=best-sellers")
        assertThat(req.path).contains("genre_id=112")
        assertThat(req.path).contains("app_id=")
        assertThat(items.single().title).isEqualTo("T")
    }

    @Test fun `getFeaturedAlbums omits genre_id when null`() = runTest {
        server.enqueue(MockResponse().setBody("""{"albums":{"items":[]}}"""))
        client.getFeaturedAlbums("new-releases-full", genreId = null, token = "tok")
        assertThat(server.takeRequest().path).doesNotContain("genre_id")
    }

    @Test fun `getFeaturedPlaylists parses playlist items`() = runTest {
        server.enqueue(MockResponse().setBody("""{"playlists":{"items":[
            {"id":5,"name":"P","owner":{"name":"O"},"tracks_count":3,"images300":["i"]}]}}"""))
        val items = client.getFeaturedPlaylists(genreId = null, token = "tok")
        assertThat(server.takeRequest().path).contains("playlist/getFeatured")
        assertThat(items.single().name).isEqualTo("P")
    }

    @Test fun `getFeaturedPlaylists uses genre_ids plural + offset`() = runTest {
        server.enqueue(MockResponse().setBody("""{"playlists":{"items":[]}}"""))
        client.getFeaturedPlaylists(genreId = 133, token = "tok", limit = 30, offset = 60)
        val path = server.takeRequest().path!!
        assertThat(path).contains("genre_ids=133")   // plural — singular is ignored by Qobuz
        assertThat(path).doesNotContain("genre_id=133&")
        assertThat(path).contains("offset=60")
        assertThat(path).contains("limit=30")
    }

    @Test fun `getPlaylist sends extra=tracks and parses detail`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":5,"name":"P","owner":{"name":"O"},
            "images300":["i"],"tracks":{"items":[
              {"id":9,"title":"S","performer":{"name":"AR"},"duration":100,
               "album":{"title":"AL","image":{"large":"L"}}}]}}"""))
        val d = client.getPlaylist("5", token = "tok")
        val req = server.takeRequest()
        assertThat(req.path).contains("playlist/get")
        assertThat(req.path).contains("extra=tracks")
        assertThat(d.tracks.items.single().title).isEqualTo("S")
    }
}
