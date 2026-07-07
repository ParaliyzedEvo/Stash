package com.stash.data.ytmusic

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever

class ResolveArtistTest {
    private fun loadFixture(name: String): String =
        this::class.java.classLoader!!.getResourceAsStream("fixtures/$name")!!
            .bufferedReader().use { it.readText() }

    private fun fakeInner(responseJson: String?): InnerTubeClient {
        val inner = mock<InnerTubeClient>()
        val parsed = responseJson?.let { Json.parseToJsonElement(it).jsonObject }
        runBlocking { whenever(inner.search(any(), any())).thenReturn(parsed) }
        return inner
    }

    private fun fakeClient(responseJson: String?): YTMusicApiClient =
        YTMusicApiClient(fakeInner(responseJson))

    @Test fun `resolveArtist returns top artist id name and avatar`() = runTest {
        val client = fakeClient(loadFixture("search_artists_filter.json"))
        val result = client.resolveArtist("my bloody valentine")
        assertEquals("UCuoGeza7Dl9Ni_hmeLTPdkg", result?.id)
        assertEquals("My Bloody Valentine", result?.name)
        assertNotNull(result?.avatarUrl)
    }

    @Test fun `resolveArtist returns null when no artists shelf`() = runTest {
        val client = fakeClient("""{"contents":{}}""")
        assertNull(client.resolveArtist("nobody"))
    }

    @Test fun `resolveArtist returns null on null response`() = runTest {
        val client = fakeClient(null)
        assertNull(client.resolveArtist("nobody"))
    }

    @Test fun `resolveArtist returns null for blank name without hitting the network`() = runTest {
        // Stub with a VALID artists response so a null result can only come from
        // the blank-name guard — not from null-response handling. Also assert the
        // search is never issued, so deleting the guard fails this test.
        val inner = fakeInner(loadFixture("search_artists_filter.json"))
        val client = YTMusicApiClient(inner)
        assertNull(client.resolveArtist("   "))
        verifyBlocking(inner, never()) { search(any(), any()) }
    }
}
