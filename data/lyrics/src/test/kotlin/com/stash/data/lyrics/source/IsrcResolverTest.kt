package com.stash.data.lyrics.source

import com.stash.core.common.Clock
import kotlinx.coroutines.test.runTest
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** title/artist -> ISRC: scoring, normalisation, what is remembered, and a non-JSON 200. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class IsrcResolverTest {

    private lateinit var server: MockWebServer
    private var body = """{"results":[]}"""
    private var code = 200
    private var now = 0L
    private lateinit var breaker: IsrcLyricsHostBreaker
    private lateinit var resolver: IsrcResolver

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(code).setBody(body)
        }
        server.start()
        breaker = IsrcLyricsHostBreaker(object : Clock { override fun now() = now })
        resolver = IsrcResolver(OkHttpClient(), "test", breaker, server.url("/api/v1").toString(), lyricsDns = Dns.SYSTEM)
    }

    @After fun tearDown() { server.shutdown() }

    private fun query(title: String = "Blinding Lights", artist: String = "The Weeknd", durationMs: Long? = 200_000L, album: String? = null) =
        LyricsQuery(1L, title, artist, album, null, durationMs, null, interactive = true, onDemand = true)

    private fun hit(isrc: String, title: String, artist: String = "The Weeknd", duration: Int = 200, timing: String = "word", album: String = "After Hours") =
        """{"isrc":"$isrc","track_name":"$title","artist_name":"$artist","duration":$duration,"timing_type":"$timing","album_name":"$album"}"""

    private fun pick(q: LyricsQuery, vararg hits: String) = IsrcResolver.pickBest(q, JSONArray("[${hits.joinToString(",")}]"))

    @Test fun `exact title, artist and duration wins over a partial title`() {
        assertEquals(
            "USUG11904206",
            pick(query(), hit("US23A1521198", "Blinding Lights Mixed Edit"), hit("USUG11904206", "Blinding Lights")),
        )
    }

    @Test fun `line-timed and untimed hits are never candidates`() {
        assertNull(pick(query(), hit("USUG12004507", "Blinding Lights", timing = "line"), hit("US23A9314953", "Blinding Lights", timing = "none")))
        assertNull(pick(query(), """{"isrc":"USUG11904206","track_name":"Blinding Lights","artist_name":"The Weeknd","duration":200}"""))
    }

    @Test fun `a duration more than 5 s off is another version`() {
        assertNull(pick(query(durationMs = 210_000L), hit("USUG11904206", "Blinding Lights")))
        assertEquals("USUG11904206", pick(query(durationMs = 204_000L), hit("USUG11904206", "Blinding Lights")))
    }

    @Test fun `the artist must overlap`() {
        assertNull(pick(query(), hit("USUG11904206", "Blinding Lights", artist = "Someone Else")))
        assertEquals("USUG11904206", pick(query(), hit("USUG11904206", "Blinding Lights", artist = "The Weeknd, ROSALÍA")))
    }

    @Test fun `normalisation drops feat brackets, dash suffixes and punctuation`() {
        assertEquals("song", IsrcResolver.normalize("Song (feat. Someone)"))
        assertEquals("song", IsrcResolver.normalize("Song - 2011 Remaster"))
        assertEquals("don t stop", IsrcResolver.normalize("Don't  Stop!"))
        assertEquals(
            "USAAA0000001",
            pick(query(title = "Song (feat. X)", artist = "Artist"), hit("USAAA0000001", "Song - Remastered", artist = "Artist")),
        )
    }

    @Test fun `the same album breaks an otherwise equal tie`() {
        assertEquals(
            "USAAA0000002",
            pick(query(album = "After Hours"), hit("USAAA0000001", "Blinding Lights", album = "Hits 2020"), hit("USAAA0000002", "Blinding Lights")),
        )
    }

    @Test fun `malformed ISRCs are skipped`() {
        assertNull(pick(query(), hit("SHORT", "Blinding Lights"), hit("US-UG1-1904206!", "Blinding Lights")))
    }

    @Test fun `a match and a clean no-match are both remembered`() = runTest {
        body = "[${hit("USUG11904206", "Blinding Lights")}]".let { """{"results":$it}""" }
        assertEquals("USUG11904206", resolver.resolve(query()))
        assertEquals("USUG11904206", resolver.resolve(query()))
        body = """{"results":[]}"""
        assertNull(resolver.resolve(query(title = "Nothing")))
        assertNull(resolver.resolve(query(title = "Nothing")))
        assertEquals(2, server.requestCount)
        val request = server.takeRequest()
        assertEquals("Blinding Lights", request.requestUrl!!.queryParameter("track"))
        assertEquals("Stash/test (Android)", request.getHeader("User-Agent"))
    }

    @Test fun `a 200 that is not JSON is a no-match, not remembered, and opens the skip window`() = runTest {
        body = "<!DOCTYPE html><html>Just a moment...</html>"
        assertNull(resolver.resolve(query()))
        assertTrue(breaker.isOpen())
        // Not remembered as "no match": once the window is over, the song is asked again.
        assertThrows(IsrcLyricsHostBreaker.SkippedException::class.java) {
            kotlinx.coroutines.runBlocking { resolver.resolve(query()) }
        }
        now += IsrcLyricsHostBreaker.SKIP_WINDOW_MS
        body = """{"results":[${hit("USUG11904206", "Blinding Lights")}]}"""
        assertEquals("USUG11904206", resolver.resolve(query()))
        assertEquals(2, server.requestCount)
    }

    @Test fun `a 5xx throws and is not remembered`() = runTest {
        code = 503
        assertThrows(AppleTtmlLyricsSource.HttpStatusException::class.java) {
            kotlinx.coroutines.runBlocking { resolver.resolve(query()) }
        }
        code = 200
        body = """{"results":[${hit("USUG11904206", "Blinding Lights")}]}"""
        assertEquals("USUG11904206", resolver.resolve(query()))
    }

    @Test fun `an empty title or artist never asks`() = runTest {
        assertNull(resolver.resolve(query(artist = " ")))
        assertNull(resolver.resolve(query(title = "()")))
        assertEquals(0, server.requestCount)
    }
}
