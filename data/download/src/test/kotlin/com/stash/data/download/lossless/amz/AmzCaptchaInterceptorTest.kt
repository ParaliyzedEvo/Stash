package com.stash.data.download.lossless.amz

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class AmzCaptchaInterceptorTest {

    private val captchaClient: AmzCaptchaClient = mockk()

    /**
     * Minimal fake [Interceptor.Chain]: returns canned response codes (queued in
     * order) and records every [Request] passed to [proceed] so tests can assert
     * the `x-captcha-token` header and the number of round-trips.
     */
    private class FakeChain(
        private val request: Request,
        private val codes: ArrayDeque<Int>,
    ) : Interceptor.Chain {
        val proceeded = mutableListOf<Request>()

        override fun request(): Request = request

        override fun proceed(request: Request): Response {
            proceeded += request
            val code = codes.removeFirstOrNull() ?: 200
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("")
                .body("".toResponseBody(null))
                .build()
        }

        override fun connection() = null
        override fun call() = throw UnsupportedOperationException()
        override fun connectTimeoutMillis() = 0
        override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        override fun readTimeoutMillis() = 0
        override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        override fun writeTimeoutMillis() = 0
        override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
    }

    private fun req(url: String): Request = Request.Builder().url(url).build()

    @Test
    fun `off-host request passes through untouched and never mints`() {
        val interceptor = AmzCaptchaInterceptor(captchaClient)
        val chain = FakeChain(req("https://example.com/x"), ArrayDeque(listOf(200)))

        interceptor.intercept(chain)

        assertThat(chain.proceeded).hasSize(1)
        assertThat(chain.proceeded[0].header("x-captcha-token")).isNull()
        coVerify(exactly = 0) { captchaClient.mint() }
    }

    @Test
    fun `captcha path is bypassed - no token, no mint`() {
        val interceptor = AmzCaptchaInterceptor(captchaClient)
        val chain = FakeChain(
            req("https://amz.squid.wtf/api/captcha/challenge"),
            ArrayDeque(listOf(200)),
        )

        interceptor.intercept(chain)

        assertThat(chain.proceeded).hasSize(1)
        assertThat(chain.proceeded[0].header("x-captcha-token")).isNull()
        coVerify(exactly = 0) { captchaClient.mint() }
    }

    @Test
    fun `amz api request mints once and attaches token header`() {
        coEvery { captchaClient.mint() } returns "tok-1"
        val interceptor = AmzCaptchaInterceptor(captchaClient)
        val chain = FakeChain(req("https://amz.squid.wtf/api/search"), ArrayDeque(listOf(200)))

        interceptor.intercept(chain)

        assertThat(chain.proceeded).hasSize(1)
        assertThat(chain.proceeded[0].header("x-captcha-token")).isEqualTo("tok-1")
        coVerify(exactly = 1) { captchaClient.mint() }
    }

    @Test
    fun `cached token is reused without re-minting`() {
        coEvery { captchaClient.mint() } returns "tok-1"
        val interceptor = AmzCaptchaInterceptor(captchaClient)

        interceptor.intercept(FakeChain(req("https://amz.squid.wtf/api/search"), ArrayDeque(listOf(200))))
        val chain2 = FakeChain(req("https://amz.squid.wtf/api/search"), ArrayDeque(listOf(200)))
        interceptor.intercept(chain2)

        assertThat(chain2.proceeded[0].header("x-captcha-token")).isEqualTo("tok-1")
        coVerify(exactly = 1) { captchaClient.mint() }
    }

    @Test
    fun `stale 403 triggers one re-mint and one retry with fresh token`() {
        val tokens = ArrayDeque(listOf("tok-1", "tok-2"))
        coEvery { captchaClient.mint() } answers { tokens.removeFirst() }
        val interceptor = AmzCaptchaInterceptor(captchaClient)
        // amz returns 403 (verified live) for a stale/invalid token.
        val chain = FakeChain(req("https://amz.squid.wtf/api/search"), ArrayDeque(listOf(403, 200)))

        val resp = interceptor.intercept(chain)

        // exactly two round-trips: original (403) + one retry (200), no loop.
        assertThat(chain.proceeded).hasSize(2)
        assertThat(chain.proceeded[0].header("x-captcha-token")).isEqualTo("tok-1")
        assertThat(chain.proceeded[1].header("x-captcha-token")).isEqualTo("tok-2")
        assertThat(resp.code).isEqualTo(200)
        coVerify(exactly = 2) { captchaClient.mint() }
    }

    @Test
    fun `null mint lets request proceed without token`() {
        coEvery { captchaClient.mint() } returns null
        val interceptor = AmzCaptchaInterceptor(captchaClient)
        val chain = FakeChain(req("https://amz.squid.wtf/api/search"), ArrayDeque(listOf(200)))

        interceptor.intercept(chain)

        assertThat(chain.proceeded).hasSize(1)
        assertThat(chain.proceeded[0].header("x-captcha-token")).isNull()
    }

    @Test
    fun `concurrent intercepts share a single mint (single-flight)`() = runBlocking {
        val mintCount = AtomicInteger(0)
        coEvery { captchaClient.mint() } coAnswers {
            mintCount.incrementAndGet()
            delay(50) // hold the mutex so the second caller races it
            "tok-shared"
        }
        val interceptor = AmzCaptchaInterceptor(captchaClient)
        val c1 = FakeChain(req("https://amz.squid.wtf/api/search"), ArrayDeque(listOf(200)))
        val c2 = FakeChain(req("https://amz.squid.wtf/api/search"), ArrayDeque(listOf(200)))

        val j1 = launch(Dispatchers.IO) { interceptor.intercept(c1) }
        val j2 = launch(Dispatchers.IO) { interceptor.intercept(c2) }
        j1.join(); j2.join()

        assertThat(c1.proceeded[0].header("x-captcha-token")).isEqualTo("tok-shared")
        assertThat(c2.proceeded[0].header("x-captcha-token")).isEqualTo("tok-shared")
        coVerify(exactly = 1) { captchaClient.mint() }
    }
}
