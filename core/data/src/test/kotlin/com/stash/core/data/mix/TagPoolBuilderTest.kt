package com.stash.core.data.mix

import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmTopTrack
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TagPoolBuilderTest {
    private val api = mockk<LastFmApiClient>()
    private val builder = TagPoolBuilder(api)

    @Test fun `ranks tracks appearing under more tags first`() = runTest {
        coEvery { api.getTagTopTracks("jazz", any()) } returns Result.success(
            listOf(LastFmTopTrack("A", "Both", 100), LastFmTopTrack("B", "JazzOnly", 90)),
        )
        coEvery { api.getTagTopTracks("chill", any()) } returns Result.success(
            listOf(LastFmTopTrack("A", "Both", 80), LastFmTopTrack("C", "ChillOnly", 70)),
        )
        val pool = builder.build(listOf("jazz", "chill"), sampleDepth = 0, userTopArtists = emptySet())
        assertEquals("Both", pool.first().title)
        assertEquals(3, pool.size)
    }
    @Test fun `deep-cut window drops the top N of each tag`() = runTest {
        coEvery { api.getTagTopTracks("jazz", any()) } returns Result.success(
            (1..5).map { LastFmTopTrack("Art$it", "T$it", 100 - it) },
        )
        val pool = builder.build(listOf("jazz"), sampleDepth = 2, userTopArtists = emptySet())
        assertTrue(pool.none { it.title == "T1" || it.title == "T2" })
        assertEquals(3, pool.size)
    }
    @Test fun `boosts tracks by artists the user already favours`() = runTest {
        coEvery { api.getTagTopTracks("jazz", any()) } returns Result.success(
            listOf(LastFmTopTrack("Stranger", "S", 100), LastFmTopTrack("Fave", "F", 50)),
        )
        val pool = builder.build(listOf("jazz"), sampleDepth = 0, userTopArtists = setOf("fave"))
        assertEquals("F", pool.first().title)
    }
    @Test fun `empty tags yields empty pool without calling the api`() = runTest {
        assertEquals(emptyList<Any>(), builder.build(emptyList(), 0, emptySet()))
    }
}
