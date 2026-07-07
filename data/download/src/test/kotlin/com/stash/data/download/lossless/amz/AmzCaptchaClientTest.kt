package com.stash.data.download.lossless.amz

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class AmzCaptchaClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: AmzCaptchaClient

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        val okHttp = OkHttpClient()
        client = AmzCaptchaClient(dagger.Lazy { okHttp }).apply { baseUrl = server.url("/api").toString() }
    }
    @After fun tearDown() = server.shutdown()

    /** Site-root response: sets the amz_web_sess cookie and embeds the webNonce. */
    private fun rootResponse() = MockResponse()
        .addHeader("Set-Cookie", "amz_web_sess=testsess; Path=/; HttpOnly; Max-Age=14400")
        .setBody("""<html><head><script>window.__AMZ_WEB={"n":"nonce-123"};</script></head></html>""")

    private fun challengeResponse() = MockResponse().setBody(
        """{"parameters":{"algorithm":"PBKDF2/SHA-256","cost":1000,"keyLength":32,""" +
            """"keyPrefix":"0d0301ca60ab63b9e18c9dc2c288e183","nonce":"f9f25960b45813de33fd107f596c3961",""" +
            """"salt":"fa12ba0b892664a85bc038b9d370bdd6"},"signature":"e7a947cd680676ad5b85bb000cb70cc6a4e82f2b302edbc17aaf131444d6ad87"}""",
    )

    @Test fun `mint loads session, sends webNonce + cookie, returns token`() = runTest {
        server.enqueue(rootResponse())
        server.enqueue(challengeResponse())
        server.enqueue(MockResponse().setBody("""{"token":"1b52301ee504cf7464586d91ddecb1935ecd2eda432b9a81"}"""))

        val token = client.mint()

        assertThat(token).isEqualTo("1b52301ee504cf7464586d91ddecb1935ecd2eda432b9a81")
        assertThat(client.sessionCookie).isEqualTo("amz_web_sess=testsess")

        val root = server.takeRequest()
        assertThat(root.path).isEqualTo("/") // origin root, not /api/...

        val challenge = server.takeRequest()
        assertThat(challenge.path).endsWith("/captcha/challenge")
        assertThat(challenge.getHeader("Cookie")).isEqualTo("amz_web_sess=testsess")

        val verify = server.takeRequest()
        assertThat(verify.path).endsWith("/captcha/verify")
        assertThat(verify.getHeader("Cookie")).isEqualTo("amz_web_sess=testsess")
        val verifyJson = Json.parseToJsonElement(verify.body.readUtf8()).jsonObject
        // NEW server requirement: verify body carries the page-session webNonce.
        assertThat(verifyJson["webNonce"]!!.jsonPrimitive.content).isEqualTo("nonce-123")
        // The payload is base64-encoded inside {"payload":"<b64>"} — DECODE it.
        val b64 = verifyJson["payload"]!!.jsonPrimitive.content
        val decoded = String(java.util.Base64.getDecoder().decode(b64))
        // Requirement 1: the echoed parameters block must NOT contain expiresAt.
        assertThat(decoded).doesNotContain("expiresAt")
        assertThat(decoded).contains(""""keyPrefix":"0d0301ca60ab63b9e18c9dc2c288e183"""")
    }

    @Test fun `mint returns null when session load fails`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503)) // root GET fails
        assertThat(client.mint()).isNull()
    }

    @Test fun `mint returns null when webNonce missing from page`() = runTest {
        server.enqueue(
            MockResponse()
                .addHeader("Set-Cookie", "amz_web_sess=testsess; Path=/")
                .setBody("<html>no nonce here</html>"),
        )
        assertThat(client.mint()).isNull()
    }

    @Test fun `mint returns null on challenge http error`() = runTest {
        server.enqueue(rootResponse())
        server.enqueue(MockResponse().setResponseCode(503))
        assertThat(client.mint()).isNull()
    }
}
