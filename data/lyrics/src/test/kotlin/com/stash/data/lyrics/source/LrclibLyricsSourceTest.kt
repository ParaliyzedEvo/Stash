package com.stash.data.lyrics.source

import com.stash.core.common.AppVersionProvider
import java.io.IOException
import kotlinx.coroutines.test.runTest
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

class LrclibLyricsSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: LrclibLyricsSource
    private val appVersion = object : AppVersionProvider {
        override val versionName = "0.9.36"
        override val versionCode = 9036
    }

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        source = LrclibLyricsSource(
            okHttpClient = OkHttpClient(),
            appVersion = appVersion,
            baseUrl = server.url("/").toString(),
        )
    }
    @After  fun tearDown() { server.shutdown() }

    @Test fun `exact-match get returns synced and plain`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"id": 42, "trackName": "Off The Grid", "artistName": "Kanye West",
             "albumName": "DONDA", "duration": 279, "instrumental": false,
             "plainLyrics": "I been off the grid",
             "syncedLyrics": "[00:01.00]I been off the grid"}
        """.trimIndent()))
        val result = source.resolve(query(durationMs = 279_000))
        assertNotNull(result)
        assertEquals("lrclib", result!!.sourceId)
        assertEquals("[00:01.00]I been off the grid", result.syncedLrc)
        assertEquals("I been off the grid", result.plainText)
        assertEquals(false, result.instrumental)
        assertEquals("42", result.sourceLyricsId)
        val request = server.takeRequest()
        val ua = request.getHeader("User-Agent")
        assertTrue("User-Agent header should mention Stash + version", ua!!.contains("Stash/0.9.36"))
        assertTrue(request.path!!.startsWith("/api/get"))
    }

    @Test fun `get omits album_name — album-strictness causes false misses`() = runTest {
        // Including album_name forces LRCLIB /api/get to 404 whenever our album
        // string differs even slightly from theirs. We deliberately drop it so a
        // good artist+title+duration still matches.
        server.enqueue(MockResponse().setBody("""
            {"id": 1, "trackName": "T", "artistName": "A", "albumName": "AL",
             "duration": 200, "instrumental": false, "plainLyrics": "x", "syncedLyrics": null}
        """.trimIndent()))
        source.resolve(query(durationMs = 200_000)) // query carries album="?"
        val getReq = server.takeRequest()
        assertTrue(getReq.path!!.startsWith("/api/get"))
        assertFalse("album_name must not be sent on /api/get", getReq.path!!.contains("album_name"))
    }

    @Test fun `single get attempt — no duration ladder`() = runTest {
        // One exact get, then straight to search on miss. No multi-rung ladder.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/api/get") -> MockResponse().setResponseCode(404)
                request.path!!.startsWith("/api/search") -> MockResponse().setBody("""
                    [{"id": 99, "trackName": "Random", "artistName": "Random",
                      "albumName": "?", "duration": 234, "instrumental": false,
                      "plainLyrics": "fallback", "syncedLyrics": null}]
                """.trimIndent())
                else -> MockResponse().setResponseCode(404)
            }
        }
        val result = source.resolve(query(durationMs = 234_000))
        assertNotNull(result)
        assertEquals("fallback", result!!.plainText)
        // Exactly two calls: one get (404) + one search. The old code made 11 gets.
        assertEquals(2, server.requestCount)
    }

    @Test fun `instrumental flag preserved`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"id": 7, "trackName": "Linus and Lucy", "artistName": "Vince Guaraldi Trio",
             "albumName": "A Charlie Brown Christmas", "duration": 180, "instrumental": true,
             "plainLyrics": null, "syncedLyrics": null}
        """.trimIndent()))
        val result = source.resolve(query(durationMs = 180_000))
        assertNotNull(result)
        assertTrue(result!!.instrumental)
        assertNull(result.plainText)
        assertNull(result.syncedLrc)
    }

    @Test fun `complete miss returns null — one get plus one search`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/api/search") -> MockResponse().setBody("[]")
                else -> MockResponse().setResponseCode(404)
            }
        }
        assertNull(source.resolve(query(durationMs = 234_000)))
        assertEquals(2, server.requestCount)
    }

    @Test fun `network exception THROWS — must not read as a definitive miss`() = runTest {
        // Pre-fix this returned null, which the repository stamped as a
        // permanent 0L miss. Forensics (2026-07-05) showed ~72% of the
        // library's "No lyrics found" tracks were poisoned exactly this way.
        server.shutdown()
        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking { source.resolve(query(durationMs = 234_000)) }
        }
    }

    @Test fun `get 5xx THROWS — server trouble is not a miss`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking { source.resolve(query(durationMs = 234_000)) }
        }
        assertEquals("no search fallback after a failing get", 1, server.requestCount)
    }

    @Test fun `search 5xx after get 404 THROWS`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/api/get") -> MockResponse().setResponseCode(404)
                else -> MockResponse().setResponseCode(503)
            }
        }
        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking { source.resolve(query(durationMs = 234_000)) }
        }
    }

    @Test fun `null duration skips get, goes straight to search`() = runTest {
        server.enqueue(MockResponse().setBody("[]"))
        assertNull(source.resolve(query(durationMs = null)))
        // Only one request (search), no get without a duration.
        assertEquals(1, server.requestCount)
    }

    @Test fun `regression — LRCLIB returns duration as JSON Number with decimal`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"id": 4578472, "trackName": "Carry On", "artistName": "Crosby, Stills, Nash & Young",
             "albumName": "Deja Vu", "duration": 265.0, "instrumental": false,
             "plainLyrics": "One morning I woke up and I knew",
             "syncedLyrics": "[00:13.71] One morning I woke up and I knew"}
        """.trimIndent()))
        val result = source.resolve(query(durationMs = 265_000))
        assertNotNull("decimal-duration response must deserialize", result)
        assertEquals("4578472", result!!.sourceLyricsId)
        assertEquals("[00:13.71] One morning I woke up and I knew", result.syncedLrc)
    }

    private fun query(durationMs: Long?) = LyricsQuery(
        trackId = 1L,
        title = "Random",
        artist = "Random",
        album = "?",
        albumArtist = null,
        durationMs = durationMs,
        youtubeVideoId = null,
    )
}
