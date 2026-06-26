package com.stash.data.download.lossless.arcod

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [ArcodAuthInterceptor]. Drives a hand-rolled fake
 * [Interceptor.Chain] that records every proceeded [Request] and returns a
 * response with a queued status code, so we can assert exactly what headers
 * went out and how many times the chain was proceeded (no MockWebServer needed).
 */
class ArcodAuthInterceptorTest {

    private val store: ArcodCredentialStore = mockk(relaxed = true)
    private val refresher: ArcodTokenRefresher = mockk(relaxed = true)

    private fun interceptor() = ArcodAuthInterceptor(store, refresher)

    /** Far in the future relative to System.currentTimeMillis() + skew. */
    private val farFuture get() = System.currentTimeMillis() + 3_600_000L

    /**
     * Fake chain: pops a status code off [codes] for each proceed (defaulting
     * to 200 once exhausted) and records the request it was handed.
     */
    private class FakeChain(codes: List<Int>) : Interceptor.Chain {
        val requests = mutableListOf<Request>()
        private val queue = ArrayDeque(codes)
        private var current: Request =
            Request.Builder().url("https://api.arcod.xyz/v1/search").build()

        fun withRequest(req: Request): FakeChain {
            current = req
            return this
        }

        override fun request(): Request = current

        override fun proceed(request: Request): Response {
            requests += request
            val code = if (queue.isNotEmpty()) queue.removeFirst() else 200
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("")
                .body("".toResponseBody(null))
                .build()
        }

        override fun connection(): Connection? = null
        override fun call(): Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis(): Int = 0
        override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        override fun readTimeoutMillis(): Int = 0
        override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        override fun writeTimeoutMillis(): Int = 0
        override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
    }

    @Test
    fun `off-host request proceeds with no Authorization header and never touches creds`() {
        val chain = FakeChain(listOf(200))
            .withRequest(Request.Builder().url("https://example.com/x").build())

        val resp = interceptor().intercept(chain)
        resp.close()

        assertEquals(1, chain.requests.size)
        assertNull(chain.requests[0].header("Authorization"))
        coVerify(exactly = 0) { store.session() }
        coVerify(exactly = 0) { refresher.refresh() }
    }

    @Test
    fun `valid unexpired token is attached and refresh is not called`() {
        coEvery { store.session() } returns ArcodSession("good-token", "refresh", farFuture)
        val chain = FakeChain(listOf(200))

        val resp = interceptor().intercept(chain)
        resp.close()

        assertEquals(1, chain.requests.size)
        assertEquals("Bearer good-token", chain.requests[0].header("Authorization"))
        coVerify(exactly = 0) { refresher.refresh() }
    }

    @Test
    fun `expired token triggers refresh and the fresh token is attached`() {
        coEvery { store.session() } returns
            ArcodSession("stale-token", "refresh", System.currentTimeMillis() - 1_000L)
        coEvery { refresher.refresh() } returns "fresh-token"
        val chain = FakeChain(listOf(200))

        val resp = interceptor().intercept(chain)
        resp.close()

        assertEquals(1, chain.requests.size)
        assertEquals("Bearer fresh-token", chain.requests[0].header("Authorization"))
        coVerify(exactly = 1) { refresher.refresh() }
    }

    @Test
    fun `401 on valid token refreshes once and retries once with fresh token`() {
        coEvery { store.session() } returns ArcodSession("good-token", "refresh", farFuture)
        coEvery { refresher.refresh() } returns "fresh-token"
        val chain = FakeChain(listOf(401, 200))

        val resp = interceptor().intercept(chain)
        resp.close()

        assertEquals(2, chain.requests.size)
        assertEquals("Bearer good-token", chain.requests[0].header("Authorization"))
        assertEquals("Bearer fresh-token", chain.requests[1].header("Authorization"))
        coVerify(exactly = 1) { refresher.refresh() }
        assertEquals(200, resp.code)
    }

    @Test
    fun `null session proceeds with no header and does not crash`() {
        coEvery { store.session() } returns null
        val chain = FakeChain(listOf(200))

        val resp = interceptor().intercept(chain)
        resp.close()

        assertEquals(1, chain.requests.size)
        assertNull(chain.requests[0].header("Authorization"))
        coVerify(exactly = 0) { refresher.refresh() }
    }
}
