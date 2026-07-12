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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioStationGeneratorArtistTest {
    private val lastFm: LastFmApiClient = mockk()
    private val yt: YTMusicApiClient = mockk()
    private fun gen() = RadioStationGenerator(lastFm, yt)

    private fun ts(artist: String, n: Int) =
        TrackSummary(videoId = "$artist$n", title = "$artist song $n", artist = artist,
            album = null, durationSeconds = 180.0, thumbnailUrl = null)
    private fun profile(name: String, browseId: String) = ArtistProfile(
        id = browseId, name = name, avatarUrl = null, subscribersText = null,
        popular = (1..6).map { ts(name, it) }, albums = emptyList(),
        singles = emptyList(), related = emptyList())

    @Test fun `artist seed builds a balanced first batch of real videoId tracks`() = runTest {
        coEvery { lastFm.getSimilarArtists("My Bloody Valentine", any()) } returns
            Result.success(listOf(
                LastFmSimilarArtist("Slowdive", 1.0f),
                LastFmSimilarArtist("Ride", 0.8f)))
        coEvery { yt.getArtist("MBV_ID") } returns profile("My Bloody Valentine", "MBV_ID")
        coEvery { yt.resolveArtist("Slowdive") } returns ArtistSummary("SD_ID", "Slowdive", null)
        coEvery { yt.getArtist("SD_ID") } returns profile("Slowdive", "SD_ID")
        coEvery { yt.resolveArtist("Ride") } returns ArtistSummary("RD_ID", "Ride", null)
        coEvery { yt.getArtist("RD_ID") } returns profile("Ride", "RD_ID")

        val (session, batch) = gen().start(
            RadioSeed.Artist("My Bloody Valentine", ytBrowseId = "MBV_ID"))

        assertTrue(batch.isNotEmpty())
        assertTrue(batch.all { it.youtubeId != null && it.youtubeId!!.isNotBlank() })
        assertTrue("no duplicate videoIds", batch.map { it.youtubeId }.toSet().size == batch.size)
        assertTrue("has seed", batch.any { it.artist == "My Bloody Valentine" })
        assertTrue("drifted outward", batch.any { it.artist != "My Bloody Valentine" })
        assertTrue(session.played.isNotEmpty())
    }

    @Test fun `uses the seed browseId directly and does not resolveArtist the seed`() = runTest {
        coEvery { lastFm.getSimilarArtists(any(), any()) } returns Result.success(emptyList())
        coEvery { yt.getArtist("MBV_ID") } returns profile("My Bloody Valentine", "MBV_ID")

        val (_, batch) = gen().start(RadioSeed.Artist("My Bloody Valentine", "MBV_ID"))

        assertTrue(batch.isNotEmpty()) // seed-only fallback still plays
        assertFalse(batch.isEmpty())
        // resolveArtist(seed) never stubbed → if generator called it, MockK throws.
    }
}
