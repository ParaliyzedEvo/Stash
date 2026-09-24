package com.stash.core.data.repository

import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.SharedMixDao
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.SharedMixEntity
import com.stash.core.data.share.SharedMixUnshareWorker
import com.stash.core.model.MusicSource
import com.stash.core.model.Playlist
import com.stash.core.model.PlaylistType
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Deleting an owned shared playlist must queue the server DELETE, since the row and edit key die with it. */
class MusicRepositorySharedMixUnshareTest {

    private val playlistDao = mockk<PlaylistDao>(relaxed = true)
    private val sharedMixDao = mockk<SharedMixDao>(relaxed = true)

    @Before fun setUp() {
        mockkObject(SharedMixUnshareWorker.Companion)
        every { SharedMixUnshareWorker.enqueue(any(), any(), any()) } returns Unit
        coEvery { playlistDao.getById(PID) } returns PlaylistEntity(
            id = PID, name = "Mix", source = MusicSource.BOTH, sourceId = "x", type = PlaylistType.CUSTOM,
        )
    }

    @After fun tearDown() = unmockkObject(SharedMixUnshareWorker.Companion)

    private fun row(role: String, status: String) = SharedMixEntity(
        playlistId = PID, shareId = "abc", role = role, editKey = "k", name = "Mix", status = status,
    )

    private fun repo() = MusicRepositoryImpl(
        context = mockk(relaxed = true),
        trackDao = mockk(relaxed = true),
        playlistDao = playlistDao,
        syncHistoryDao = mockk(relaxed = true),
        downloadQueueDao = mockk(relaxed = true),
        discoveryQueueDao = mockk(relaxed = true),
        blocklistGuard = mockk(relaxed = true),
        trackMatcher = mockk(relaxed = true),
        stashMixRecipeDao = mockk(relaxed = true),
        downloadNetworkPreference = mockk(relaxed = true),
        streamingPreference = mockk(relaxed = true),
        localFileOps = mockk(relaxed = true),
        syncPreferencesManager = mockk(relaxed = true),
        singleTrackDownloadEnqueuer = mockk(relaxed = true),
        lastFmRecommendationSource = mockk(relaxed = true),
        sharedMixDao = sharedMixDao,
    )

    private val playlist = Playlist(id = PID, name = "Mix", source = MusicSource.BOTH)

    @Test fun `removePlaylist of an owned active share queues the takedown`() = runTest {
        coEvery { sharedMixDao.forPlaylist(PID) } returns row(SharedMixEntity.ROLE_OWNER, SharedMixEntity.STATUS_ACTIVE)
        repo().removePlaylist(playlist)
        verify(exactly = 1) { SharedMixUnshareWorker.enqueue(any(), "abc", "k") }
    }

    @Test fun `deletePlaylistWithCascade of an owned active share queues the takedown`() = runTest {
        coEvery { sharedMixDao.forPlaylist(PID) } returns row(SharedMixEntity.ROLE_OWNER, SharedMixEntity.STATUS_ACTIVE)
        repo().deletePlaylistWithCascade(PID, alsoBlacklist = false)
        verify(exactly = 1) { SharedMixUnshareWorker.enqueue(any(), "abc", "k") }
    }

    @Test fun `a followed mix never takes the owner's link down`() = runTest {
        coEvery { sharedMixDao.forPlaylist(PID) } returns row(SharedMixEntity.ROLE_FOLLOWER, SharedMixEntity.STATUS_ACTIVE)
        repo().removePlaylist(playlist)
        verify(exactly = 0) { SharedMixUnshareWorker.enqueue(any(), any(), any()) }
    }

    @Test fun `an already stopped share is not deleted again`() = runTest {
        coEvery { sharedMixDao.forPlaylist(PID) } returns row(SharedMixEntity.ROLE_OWNER, SharedMixEntity.STATUS_REMOVED)
        repo().deletePlaylistWithCascade(PID, alsoBlacklist = false)
        verify(exactly = 0) { SharedMixUnshareWorker.enqueue(any(), any(), any()) }
    }

    @Test fun `a playlist that was never shared queues nothing`() = runTest {
        coEvery { sharedMixDao.forPlaylist(PID) } returns null
        repo().removePlaylist(playlist)
        verify(exactly = 0) { SharedMixUnshareWorker.enqueue(any(), any(), any()) }
    }

    private companion object { const val PID = 5L }
}
