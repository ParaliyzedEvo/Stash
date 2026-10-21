package com.stash.data.lyrics.source

import com.stash.core.common.Clock
import java.io.InterruptedIOException
import kotlinx.coroutines.test.runTest
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The ISRC-keyed TTML stores (lrc.red / Binimum): known-ISRC lookups, what counts as a hit, and the
 * shared skip window. Robolectric for real org.json + android.util.Log.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class IsrcTtmlLyricsSourceTest {

    private val wordTtml = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:lrc="http://lrc.red/lyric-ttml-internal" """ +
        """lrc:timing="Word"><body><div><p begin="1.000" end="2.000"><span begin="1.000" end="1.500">Hi</span>""" +
        """<span begin="1.500" end="2.000">there</span></p></div></body></tt>"""
    private val lineTtml = wordTtml.replace("lrc:timing=\"Word\"", "lrc:timing=\"Line\"")
    private val search = """{"results":[{"isrc":"USAAA0000002","track_name":"Song","artist_name":"Artist","duration":200,"timing_type":"word"}]}"""

    private lateinit var server: MockWebServer
    /** Path -> response for the TTML store; unknown paths are 404. */
    private val files = HashMap<String, MockResponse>()
    private var searchCalls = 0
    private var ttmlCalls = 0
    private var timeOut = false
    private var now = 0L
    private val clock = object : Clock { override fun now() = now }
    private lateinit var breaker: IsrcLyricsHostBreaker
    private lateinit var client: OkHttpClient

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                return if (path.startsWith("/api/v1")) {
                    MockResponse().setBody(search)
                } else {
                    files[path] ?: MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            if (chain.request().url.encodedPath.startsWith("/api/v1")) searchCalls++ else ttmlCalls++
            if (timeOut) throw InterruptedIOException("timeout")
            chain.proceed(chain.request())
        }).build()
        breaker = IsrcLyricsHostBreaker(clock)
    }

    @After fun tearDown() { server.shutdown() }

    private fun source(id: String = IsrcTtmlLyricsSource.LRC_RED_ID, prefix: String = "s") = IsrcTtmlLyricsSource(
        // Built by hand: server.url() would percent-encode the braces of the {ISRC} placeholder.
        client, "test", id, id, server.url("/").toString() + "$prefix/{ISRC}.ttml",
        IsrcResolver(client, "test", breaker, server.url("/api/v1").toString(), lyricsDns = Dns.SYSTEM),
        breaker, lyricsDns = Dns.SYSTEM,
    )

    private fun query(isrc: String? = "USAAA0000001") =
        LyricsQuery(1L, "Song", "Artist", null, null, 200_000L, null, isrc = isrc, interactive = true, onDemand = true)

    private fun serve(isrc: String, body: String, code: Int = 200) {
        files["/s/$isrc.ttml"] = MockResponse().setResponseCode(code).setBody(body)
    }

    @Test fun `a known ISRC with word-timed TTML is a hit keyed by that ISRC`() = runTest {
        serve("USAAA0000001", wordTtml)
        val result = source().resolve(query())
        assertNotNull(result!!.ttml)
        assertEquals("USAAA0000001", result.sourceLyricsId)
        assertEquals(IsrcTtmlLyricsSource.LRC_RED_ID, result.sourceId)
        assertTrue(result.syncedLrc!!.contains("Hi"))
        assertEquals(0, searchCalls)
    }

    @Test fun `a clean 404 on a known ISRC is a miss and never falls back to a search`() = runTest {
        assertNull(source().resolve(query()))
        assertEquals(0, searchCalls)
        assertEquals(1, ttmlCalls)
    }

    @Test fun `without an ISRC the search picks one`() = runTest {
        serve("USAAA0000002", wordTtml)
        val result = source().resolve(query(isrc = null))
        assertEquals("USAAA0000002", result!!.sourceLyricsId)
        assertEquals(1, searchCalls)
    }

    @Test fun `the User-Agent is Stash's`() = runTest {
        serve("USAAA0000001", wordTtml)
        source().resolve(query())
        assertEquals("Stash/test (Android)", server.takeRequest().getHeader("User-Agent"))
    }

    @Test fun `a non-2xx other than 404 is a failure`() = runTest {
        serve("USAAA0000001", "oops", code = 503)
        val e = assertThrows(AppleTtmlLyricsSource.HttpStatusException::class.java) {
            kotlinx.coroutines.runBlocking { source().resolve(query()) }
        }
        assertEquals(503, e.code)
        assertFalse("a 5xx is an answer, not a dead host", breaker.isOpen())
    }

    @Test fun `a 200 that is not TTML is a miss`() = runTest {
        serve("USAAA0000001", """{"error":"nope"}""")
        assertNull(source().resolve(query()))
        assertFalse(breaker.isOpen())
    }

    @Test fun `an HTML challenge page is a miss and opens the skip window`() = runTest {
        serve("USAAA0000001", "<!DOCTYPE html><html><title>Just a moment...</title></html>")
        assertNull(source().resolve(query()))
        assertTrue(breaker.isOpen())
    }

    @Test fun `line-timed TTML is rejected so it never replaces line sync with fake word timing`() = runTest {
        serve("USAAA0000001", lineTtml)
        assertNull(source().resolve(query()))
    }

    @Test fun `TTML with no timing attribute is rejected`() = runTest {
        serve("USAAA0000001", wordTtml.replace(" lrc:timing=\"Word\"", ""))
        assertNull(source().resolve(query()))
    }

    @Test fun `Apple-namespaced word timing is accepted`() = runTest {
        serve("USAAA0000001", wordTtml.replace("lrc:timing", "itunes:timing"))
        assertNotNull(source().resolve(query()))
    }

    @Test fun `one timeout opens the shared window - both sources then skip without touching the network`() = runTest {
        timeOut = true
        assertThrows(InterruptedIOException::class.java) {
            kotlinx.coroutines.runBlocking { source().resolve(query()) }
        }
        assertEquals(1, ttmlCalls)
        timeOut = false
        serve("USAAA0000001", wordTtml)

        val binimum = source(IsrcTtmlLyricsSource.BINIMUM_ID)
        assertThrows(IsrcLyricsHostBreaker.SkippedException::class.java) {
            kotlinx.coroutines.runBlocking { binimum.resolve(query()) }
        }
        assertThrows(IsrcLyricsHostBreaker.SkippedException::class.java) {
            kotlinx.coroutines.runBlocking { source().resolve(query(isrc = null)) }
        }
        assertEquals("no request while the window is open", 1, ttmlCalls)
        assertEquals(0, searchCalls)

        now += IsrcLyricsHostBreaker.SKIP_WINDOW_MS
        assertNotNull(source().resolve(query()))
        assertEquals(2, ttmlCalls)
    }

    @Test fun `an HTTP 429 opens the window`() = runTest {
        serve("USAAA0000001", "slow down", code = 429)
        val e = assertThrows(AppleTtmlLyricsSource.HttpStatusException::class.java) {
            kotlinx.coroutines.runBlocking { source().resolve(query()) }
        }
        assertEquals(429, e.code)
        assertTrue(breaker.isOpen())
    }

    @Test fun `isWordTimed reads only the root timing attribute`() {
        assertTrue(IsrcTtmlLyricsSource.isWordTimed("""<tt xmlns="x" lrc:timing="Word">"""))
        assertTrue(IsrcTtmlLyricsSource.isWordTimed("""<tt itunes:timing='word' xmlns="x">"""))
        assertFalse(IsrcTtmlLyricsSource.isWordTimed("""<tt lrc:timing="Line">"""))
        assertFalse(IsrcTtmlLyricsSource.isWordTimed("""<tt itunes:timing="None">"""))
        assertFalse(IsrcTtmlLyricsSource.isWordTimed("""<tt><body lrc:timing="Word">"""))
    }
}
