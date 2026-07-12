package com.stash.core.data.radio

import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmSimilarArtist
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.ArtistProfile
import com.stash.data.ytmusic.model.ArtistSummary
import com.stash.data.ytmusic.model.TrackSummary
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioStationGeneratorGrowTest {
    private val lastFm: LastFmApiClient = mockk(relaxed = true)
    private val yt: YTMusicApiClient = mockk(relaxed = true)
    private fun gen() = RadioStationGenerator(lastFm, yt)

    private fun ts(a: String, n: Int) = TrackSummary("$a$n", "$a song $n", a, null, 180.0, null)
    private fun prof(a: String, id: String) = ArtistProfile(id, a, null, null,
        (1..6).map { ts(a, it) }, emptyList(), emptyList(), emptyList())

    @Test fun `nextBatch widens PAST the initial pool into fresh neighbors`() = runTest {
        // This test must genuinely trigger widen() — not just drain a big pre-built
        // pool. The generator constants make the widen threshold `ordered.size - 6`,
        // so the INITIAL pool must be small enough that one 12-track batch crosses
        // it. We give the seed its 6 popular tracks but each neighbor only ONE, so
        // the initial pool = 6 + 12×1 = 18; after the first batch cursor=12 and
        // 12 >= 18-6 → widen fires and must page neighbors 13-20 (absent from the
        // initial 12-neighbor pool). A track from N13..N20 in the 2nd batch can
        // ONLY come from widen — so this fails if widen reverts to the no-op.
        // Define generic getArtist(any()) FIRST, then specific "MBV" (MockK last-match).
        coEvery { lastFm.getSimilarArtists(any(), any()) } returns Result.success(
            (1..20).map { LastFmSimilarArtist("N$it", 1f / it) })
        coEvery { yt.resolveArtist(any()) } answers
            { ArtistSummary(firstArg<String>() + "_ID", firstArg(), null) }
        coEvery { yt.getArtist(any()) } answers {
            val id = firstArg<String>(); val name = id.removeSuffix("_ID")
            ArtistProfile(id, name, null, null, listOf(ts(name, 1)),   // ONE popular track
                emptyList(), emptyList(), emptyList())
        }
        coEvery { yt.getArtist("MBV") } returns prof("My Bloody Valentine", "MBV") // seed: 6 tracks

        val g = gen()
        val (session, first) = g.start(RadioSeed.Artist("My Bloody Valentine", "MBV"))
        val second = g.nextBatch(session)

        val widened = (13..20).map { "N$it" }
        assertTrue("widen actually ran (fresh N13..N20 present)",
            second.map { it.artist }.any { it in widened })
        val allIds = (first + second).mapNotNull { it.youtubeId }
        assertTrue("zero repeats across batches", allIds.toSet().size == allIds.size)
    }

    @Test fun `degenerate seed with no neighbors still returns seed-only tracks`() = runTest {
        coEvery { lastFm.getSimilarArtists(any(), any()) } returns Result.success(emptyList())
        coEvery { yt.getArtist("MBV") } returns prof("My Bloody Valentine", "MBV")

        val (_, batch) = gen().start(RadioSeed.Artist("My Bloody Valentine", "MBV"))
        assertTrue(batch.isNotEmpty())
        assertTrue(batch.all { it.artist == "My Bloody Valentine" })
    }

    @Test fun `lastfm failure degrades to seed-only, not empty`() = runTest {
        coEvery { lastFm.getSimilarArtists(any(), any()) } returns
            Result.failure(RuntimeException("429"))
        coEvery { yt.getArtist("MBV") } returns prof("My Bloody Valentine", "MBV")

        val (_, batch) = gen().start(RadioSeed.Artist("My Bloody Valentine", "MBV"))
        assertTrue(batch.isNotEmpty())
    }
}
