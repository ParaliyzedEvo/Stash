package com.stash.data.download.lossless.arcod

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

/**
 * Unit tests for [ArcodTokenRefresher] against a [MockWebServer] standing in
 * for the Supabase `/auth/v1/token` endpoint. Uses a real Robolectric-backed
 * [ArcodCredentialStore] (like the Task 1 store test) so the round-trip into
 * DataStore is exercised end-to-end.
 */
@RunWith(RobolectricTestRunner::class)
class ArcodTokenRefresherTest {

    private lateinit var server: MockWebServer
    private lateinit var context: Context
    private lateinit var store: ArcodCredentialStore
    private lateinit var refresher: ArcodTokenRefresher

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        context = ApplicationProvider.getApplicationContext()
        store = ArcodCredentialStore(context)
        // The preferencesDataStore delegate is a single per-process instance,
        // so clear through the store itself to guarantee a clean slate.
        runBlocking {
            store.markStale()
            store.save(accessToken = "old", refreshToken = "refreshtok", expiresAtMs = 0L)
        }
        refresher = ArcodTokenRefresher(OkHttpClient(), store).apply {
            supabaseUrl = server.url("").toString().trimEnd('/')
        }
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun `refresh on 200 persists new session and returns access token`() = runTest {
        val before = System.currentTimeMillis()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"access_token":"newAT","refresh_token":"newRT","expires_in":3600}""",
            ),
        )

        val result = refresher.refresh()

        assertThat(result).isEqualTo("newAT")

        val session = store.session()
        assertThat(session).isNotNull()
        assertThat(session!!.accessToken).isEqualTo("newAT")
        assertThat(session.refreshToken).isEqualTo("newRT")
        // expiresAtMs ≈ now + 3600s; allow a few seconds of slack for test runtime.
        assertThat(session.expiresAtMs).isAtLeast(before + 3600_000L)
        assertThat(session.expiresAtMs).isAtMost(System.currentTimeMillis() + 3600_000L + 5_000L)

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).contains("/auth/v1/token")
        assertThat(request.path).contains("grant_type=refresh_token")
        assertThat(request.getHeader("apikey")).isEqualTo(ArcodTokenRefresher.ANON_KEY)
        assertThat(request.getHeader("Authorization"))
            .isEqualTo("Bearer ${ArcodTokenRefresher.ANON_KEY}")
        // Body carries the OLD refresh token.
        assertThat(request.body.readUtf8()).contains("refreshtok")
    }

    @Test fun `refresh on non-2xx returns null and marks store stale`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"bad refresh"}"""))

        val result = refresher.refresh()

        assertThat(result).isNull()
        assertThat(store.isConnected()).isFalse()
    }

    @Test fun `refresh with no refresh token returns null and makes no request`() = runTest {
        // Wipe the pre-seeded session so there's nothing to refresh.
        store.markStale()

        val result = refresher.refresh()

        assertThat(result).isNull()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun `concurrent refresh callers do not race or crash`() = runTest {
        repeat(4) {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"access_token":"newAT","refresh_token":"newRT","expires_in":3600}""",
                ),
            )
        }

        val results = (1..4).map {
            async(Dispatchers.IO) { refresher.refresh() }
        }.awaitAll()

        assertThat(results).containsExactly("newAT", "newAT", "newAT", "newAT")
        assertThat(store.session()!!.accessToken).isEqualTo("newAT")
    }
}
