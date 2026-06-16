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
        client = AmzCaptchaClient(OkHttpClient()).apply { baseUrl = server.url("/api").toString() }
    }
    @After fun tearDown() = server.shutdown()

    @Test fun `mint solves challenge and returns token`() = runTest {
        // Real captured challenge shape — NOTE: no expiresAt field.
        server.enqueue(MockResponse().setBody(
            """{"parameters":{"algorithm":"PBKDF2/SHA-256","cost":1000,"keyLength":32,""" +
            """"keyPrefix":"0d0301ca60ab63b9e18c9dc2c288e183","nonce":"f9f25960b45813de33fd107f596c3961",""" +
            """"salt":"fa12ba0b892664a85bc038b9d370bdd6"},"signature":"e7a947cd680676ad5b85bb000cb70cc6a4e82f2b302edbc17aaf131444d6ad87"}"""
        ))
        server.enqueue(MockResponse().setBody("""{"token":"1b52301ee504cf7464586d91ddecb1935ecd2eda432b9a81"}"""))

        val token = client.mint()

        assertThat(token).isEqualTo("1b52301ee504cf7464586d91ddecb1935ecd2eda432b9a81")
        server.takeRequest() // challenge GET
        val verify = server.takeRequest()
        assertThat(verify.path).endsWith("/captcha/verify")
        // The payload is base64-encoded inside {"payload":"<b64>"} — DECODE it
        // before asserting, or a raw-body substring check passes trivially.
        val b64 = Json.parseToJsonElement(verify.body.readUtf8())
            .jsonObject["payload"]!!.jsonPrimitive.content
        val decoded = String(java.util.Base64.getDecoder().decode(b64))
        // Requirement 1: the echoed parameters block must NOT contain expiresAt.
        assertThat(decoded).doesNotContain("expiresAt")
        assertThat(decoded).contains(""""keyPrefix":"0d0301ca60ab63b9e18c9dc2c288e183"""")
    }

    @Test fun `mint returns null on challenge http error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        assertThat(client.mint()).isNull()
    }
}
