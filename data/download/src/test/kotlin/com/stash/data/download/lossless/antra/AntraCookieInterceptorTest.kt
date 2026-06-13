package com.stash.data.download.lossless.antra

import com.stash.data.download.lossless.LosslessSourcePreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [AntraCookieInterceptor].
 *
 * Drives a real OkHttp client (interceptor installed) against a
 * [MockWebServer] and inspects the recorded request. Verifies:
 *  - When both antra cookies are present, the request to `antra.hoshi.cfd`
 *    carries a `Cookie: session=…; cf_clearance=…` header plus the
 *    browser-style fingerprint headers (UA / Origin / Referer / sec-ch-ua*).
 *  - When a cookie is missing, the request passes through untouched.
 *  - Requests to other hosts are never decorated.
 *
 * The MockWebServer runs on `127.0.0.1`, not `antra.hoshi.cfd`, so the
 * host-match path is exercised by overriding the interceptor's host to the
 * server's authority for the "connected" cases.
 */
class AntraCookieInterceptorTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun prefs(session: String?, cf: String?): LosslessSourcePreferences {
        val p = mockk<LosslessSourcePreferences>(relaxed = true)
        coEvery { p.antraSessionCookieNow() } returns session
        coEvery { p.antraCfClearanceNow() } returns cf
        every { p.antraSessionCookie } returns flowOf(session)
        every { p.antraCfClearance } returns flowOf(cf)
        return p
    }

    private fun clientFor(interceptor: AntraCookieInterceptor) =
        OkHttpClient.Builder().addInterceptor(interceptor).build()

    @Test fun `attaches cookie and browser headers when connected`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val interceptor = AntraCookieInterceptor(prefs("sess-abc", "cf-xyz")).apply {
            hostOverride = server.hostName
        }
        clientFor(interceptor)
            .newCall(Request.Builder().url(server.url("/api/auth/me")).build())
            .execute().close()

        val recorded = server.takeRequest()
        val cookie = recorded.getHeader("Cookie")
        assertNotNull(cookie)
        assertTrue(cookie!!.contains("antra_session=sess-abc"))
        assertTrue(cookie.contains("cf_clearance=cf-xyz"))
        // Browser fingerprint headers accompany the cookies.
        assertNotNull(recorded.getHeader("User-Agent"))
        assertNotNull(recorded.getHeader("Origin"))
        assertNotNull(recorded.getHeader("Referer"))
        assertNotNull(recorded.getHeader("sec-ch-ua"))
    }

    @Test fun `attaches antra_session alone when cf_clearance absent`() {
        // antra authenticates on antra_session alone; cf_clearance is usually
        // not set (no active Cloudflare challenge), so the interceptor must
        // still attach the session cookie.
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val interceptor = AntraCookieInterceptor(prefs("sess-only", null)).apply {
            hostOverride = server.hostName
        }
        clientFor(interceptor)
            .newCall(Request.Builder().url(server.url("/api/auth/me")).build())
            .execute().close()

        val cookie = server.takeRequest().getHeader("Cookie")!!
        assertTrue(cookie.contains("antra_session=sess-only"))
        assertTrue(!cookie.contains("cf_clearance"))
    }

    @Test fun `passes request through unchanged when no session`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val interceptor = AntraCookieInterceptor(prefs(null, "cf-xyz")).apply {
            hostOverride = server.hostName
        }
        clientFor(interceptor)
            .newCall(Request.Builder().url(server.url("/api/auth/me")).build())
            .execute().close()

        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Cookie"))
    }

    @Test fun `leaves non-antra hosts untouched`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        // hostOverride NOT set → server's host != antra.hoshi.cfd → no decoration.
        val interceptor = AntraCookieInterceptor(prefs("sess-abc", "cf-xyz"))
        clientFor(interceptor)
            .newCall(Request.Builder().url(server.url("/api/auth/me")).build())
            .execute().close()

        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Cookie"))
    }

    @Test fun `does not clobber an existing Cookie header`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val interceptor = AntraCookieInterceptor(prefs("sess-abc", "cf-xyz")).apply {
            hostOverride = server.hostName
        }
        clientFor(interceptor)
            .newCall(
                Request.Builder()
                    .url(server.url("/api/auth/me"))
                    .header("Cookie", "foo=bar")
                    .build(),
            )
            .execute().close()

        val cookie = server.takeRequest().getHeader("Cookie")!!
        assertTrue(cookie.contains("foo=bar"))
        assertTrue(cookie.contains("antra_session=sess-abc"))
    }
}
