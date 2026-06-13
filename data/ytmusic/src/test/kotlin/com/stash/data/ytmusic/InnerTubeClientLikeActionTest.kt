package com.stash.data.ytmusic

import com.stash.core.auth.TokenManager
import com.stash.core.auth.youtube.YouTubeCookieHelper
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * v0.9.52: like/removelike share one payload builder. Pins the JSON
 * shape ({context, target:{videoId}}) and the 2xx+body success rule
 * for both directions.
 */
class InnerTubeClientLikeActionTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() { server.shutdown() }

    private fun client(): InnerTubeClient {
        val token = mock<TokenManager>()
        val cookies = mock<YouTubeCookieHelper>()
        runBlocking { whenever(token.getYouTubeCookie()).thenReturn(null) }
        return InnerTubeClient(OkHttpClient(), token, cookies)
    }

    @Test fun `like action returns true on 2xx with body and posts target videoId`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))

        val ok = client().sendLikeActionForTest(server.url("/like/removelike").toString(), "vid123")

        assertTrue(ok)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        val body = recorded.body.readUtf8()
        assertTrue("payload carries target.videoId", body.contains(""""videoId":"vid123""""))
        assertTrue("payload carries an InnerTube context", body.contains(""""context""""))
    }

    @Test fun `like action returns false on 4xx`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("denied"))
        assertFalse(client().sendLikeActionForTest(server.url("/like/removelike").toString(), "vid123"))
    }

    @Test fun `like action returns false on connection failure`() = runBlocking {
        val url = server.url("/like/removelike").toString()
        server.shutdown()
        assertFalse(client().sendLikeActionForTest(url, "vid123"))
    }
}
