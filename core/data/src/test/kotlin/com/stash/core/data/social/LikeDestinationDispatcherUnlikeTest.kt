package com.stash.core.data.social

import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.social.spotify.SpotifyLibraryApiClient
import com.stash.core.data.social.stash.StashLikedPlaylistRepository
import com.stash.core.data.social.ytmusic.YtMusicLibraryApiClient
import com.stash.core.model.Track
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.9.52 symmetric un-like. The load-bearing rule: NEVER un-Like
 * what Stash never Liked — a destination only fires when its
 * `*_saved_at` timestamp is non-null, and a successful remote remove
 * clears that timestamp so a future re-heart re-fires.
 */
class LikeDestinationDispatcherUnlikeTest {

    private val spotify = mockk<SpotifyLibraryApiClient>(relaxed = true)
    private val ytMusic = mockk<YtMusicLibraryApiClient>(relaxed = true)
    private val stashLiked = mockk<StashLikedPlaylistRepository>(relaxed = true)
    private val trackDao = mockk<TrackDao>(relaxed = true)
    private val dispatcher = LikeDestinationDispatcher(spotify, ytMusic, stashLiked, trackDao)

    private fun track(
        spotifySavedAt: Long? = null,
        ytMusicSavedAt: Long? = null,
        spotifyUri: String? = "spotify:track:abc",
        youtubeId: String? = "vid123",
    ) = Track(
        id = 7L,
        title = "t",
        artist = "a",
        spotifyUri = spotifyUri,
        youtubeId = youtubeId,
        spotifySavedAt = spotifySavedAt,
        ytMusicSavedAt = ytMusicSavedAt,
    )

    @Test fun `skips destination Stash never saved`() = runBlocking {
        val result = dispatcher.unlike(track(spotifySavedAt = null), setOf(Destination.SPOTIFY))

        assertTrue(result[Destination.SPOTIFY]!!.isSuccess)
        coVerify(exactly = 0) { spotify.removeTracks(any()) }
        coVerify(exactly = 0) { trackDao.clearSpotifySaved(any()) }
    }

    @Test fun `removes on Spotify and clears dedup column when saved`() = runBlocking {
        val result = dispatcher.unlike(track(spotifySavedAt = 111L), setOf(Destination.SPOTIFY))

        assertTrue(result[Destination.SPOTIFY]!!.isSuccess)
        coVerify { spotify.removeTracks(listOf("spotify:track:abc")) }
        coVerify { trackDao.clearSpotifySaved(7L) }
    }

    @Test fun `removes on YT Music and clears dedup column when saved`() = runBlocking {
        val result = dispatcher.unlike(track(ytMusicSavedAt = 222L), setOf(Destination.YT_MUSIC))

        assertTrue(result[Destination.YT_MUSIC]!!.isSuccess)
        coVerify { ytMusic.removeLike("vid123") }
        coVerify { trackDao.clearYtMusicSaved(7L) }
    }

    @Test fun `API failure leaves dedup column untouched for organic retry`() = runBlocking {
        coEvery { spotify.removeTracks(any()) } throws RuntimeException("boom")

        val result = dispatcher.unlike(track(spotifySavedAt = 111L), setOf(Destination.SPOTIFY))

        assertTrue(result[Destination.SPOTIFY]!!.isFailure)
        coVerify(exactly = 0) { trackDao.clearSpotifySaved(any()) }
    }

    @Test fun `unlike routes STASH to local repository remove`() = runBlocking {
        val t = track().copy(stashLikedAt = 333L)

        val result = dispatcher.unlike(t, setOf(Destination.STASH))

        assertTrue(result[Destination.STASH]!!.isSuccess)
        coVerify { stashLiked.remove(7L) }
    }

    @Test fun `empty destination set is a no-op`() = runBlocking {
        assertTrue(dispatcher.unlike(track(), emptySet()).isEmpty())
    }
}
