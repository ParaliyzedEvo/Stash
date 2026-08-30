package com.stash.data.download.lossless.relay

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LosslessConfigFetcherTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var server: MockWebServer
    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val pub = Base64.getEncoder().encodeToString(keys.public.encoded)
    private val body = """{"v":1,"relays":[{"base":"https://b.example","priority":2},{"base":"https://a.example/","priority":1}],"updated_at":1}"""

    private fun sign(bytes: ByteArray): String = Base64.getEncoder().encodeToString(
        Signature.getInstance("SHA256withECDSA").apply { initSign(keys.private); update(bytes) }.sign(),
    )
    private fun fetcher() = LosslessConfigFetcher(ctx, OkHttpClient()).also {
        it.configUrl = server.url("/stash/lossless.json").toString()
        it.publicKeyB64 = pub
    }

    @Before fun setUp() { server = MockWebServer(); server.start(); runBlocking { fetcher().clearForTest() } }
    @After fun tearDown() { runCatching { server.shutdown() } }

    @Test fun `valid signature applies and caches the list sorted by priority with bases normalised`() = runTest {
        server.enqueue(MockResponse().setBody(body))
        server.enqueue(MockResponse().setBody(sign(body.toByteArray())))
        val f = fetcher()
        assertThat(f.refresh()).isTrue()
        assertThat(f.relays.value.map { it.base }).containsExactly("https://a.example", "https://b.example").inOrder()
        assertThat(server.takeRequest().path).isEqualTo("/stash/lossless.json")
        assertThat(server.takeRequest().path).isEqualTo("/stash/lossless.json.sig")

        val cold = fetcher()
        cold.loadCached()
        assertThat(cold.relays.value.map { it.base }).containsExactly("https://a.example", "https://b.example").inOrder()
    }

    @Test fun `tampered body is ignored and the cached copy kept`() = runTest {
        server.enqueue(MockResponse().setBody(body)); server.enqueue(MockResponse().setBody(sign(body.toByteArray())))
        val f = fetcher(); f.refresh()
        val evil = body.replace("a.example", "evil.example")
        server.enqueue(MockResponse().setBody(evil)); server.enqueue(MockResponse().setBody(sign(body.toByteArray())))
        assertThat(f.refresh()).isFalse()
        assertThat(f.relays.value.map { it.base }).containsExactly("https://a.example", "https://b.example").inOrder()
    }

    @Test fun `network failure keeps the cached copy`() = runTest {
        server.enqueue(MockResponse().setBody(body)); server.enqueue(MockResponse().setBody(sign(body.toByteArray())))
        val f = fetcher()
        assertThat(f.refresh()).isTrue()
        server.shutdown()
        assertThat(f.refresh()).isFalse()
        assertThat(f.relays.value.map { it.base }).containsExactly("https://a.example", "https://b.example").inOrder()
    }

    @Test fun `disabled when url or key is blank`() = runTest {
        val f = fetcher().also { it.publicKeyB64 = "" }
        assertThat(f.enabled).isFalse()
        assertThat(f.refresh()).isFalse()
        assertThat(server.requestCount).isEqualTo(0)
        f.loadCached() // no cache means no relays
        assertThat(f.relays.value).isEmpty()
    }

    @Test fun `an oversized config body is rejected without applying`() = runTest {
        server.enqueue(MockResponse().setBody("x".repeat(70_000)))
        val f = fetcher()
        assertThat(f.refresh()).isFalse()
        assertThat(f.relays.value).isEmpty()
        assertThat(server.requestCount).isEqualTo(1) // the .sig is never fetched
    }

    @Test fun `an older validly-signed config is rejected`() = runTest {
        val newer = """{"v":1,"relays":[{"base":"https://c.example","priority":1}],"updated_at":2}"""
        server.enqueue(MockResponse().setBody(newer)); server.enqueue(MockResponse().setBody(sign(newer.toByteArray())))
        val f = fetcher()
        assertThat(f.refresh()).isTrue()

        val older = """{"v":1,"relays":[{"base":"https://d.example","priority":1}],"updated_at":1}"""
        server.enqueue(MockResponse().setBody(older)); server.enqueue(MockResponse().setBody(sign(older.toByteArray())))
        assertThat(f.refresh()).isFalse()
        assertThat(f.relays.value.map { it.base }).containsExactly("https://c.example")

        val cold = fetcher()
        cold.loadCached()
        assertThat(cold.relays.value.map { it.base }).containsExactly("https://c.example")
    }

    @Test fun `missing sig fails closed`() = runTest {
        server.enqueue(MockResponse().setBody(body)); server.enqueue(MockResponse().setResponseCode(404))
        val f = fetcher()
        assertThat(f.refresh()).isFalse()
        assertThat(f.relays.value).isEmpty()
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun `empty sig fails closed`() = runTest {
        server.enqueue(MockResponse().setBody(body)); server.enqueue(MockResponse().setBody(""))
        val f = fetcher()
        assertThat(f.refresh()).isFalse()
        assertThat(f.relays.value).isEmpty()
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun `non-https or malformed bases are dropped`() = runTest {
        val b = """{"v":1,"relays":[{"base":"http://plain.example","priority":1},{"base":"https://ok.example?x=1","priority":1}],"updated_at":1}"""
        server.enqueue(MockResponse().setBody(b)); server.enqueue(MockResponse().setBody(sign(b.toByteArray())))
        val f = fetcher(); f.refresh()
        assertThat(f.relays.value.map { it.base }).containsExactly("https://ok.example")
    }

    @Test fun `a failed refresh schedules the short retry`() {
        val f = fetcher()
        assertThat(f.nextDelayMs(false)).isEqualTo(15 * 60 * 1000L)
        assertThat(f.nextDelayMs(true)).isEqualTo(6 * 60 * 60 * 1000L)
    }
}
