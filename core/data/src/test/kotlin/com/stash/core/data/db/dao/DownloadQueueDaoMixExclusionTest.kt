package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.SyncHistoryEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.DownloadStatus
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * A mix makes a track download-eligible — and spares its queue row from a sweep
 * — exactly when the mix's switch is on. Same as any playlist.
 *
 * History: #368 banned mixes by TYPE at three predicates
 * ([DownloadQueueDao.getUnqueuedTrackIds],
 * [DownloadQueueDao.deleteOrphanedQueueEntries],
 * [DownloadQueueDao.cancelDownloadsWithNoEnabledPlaylist]) because auto-enabled
 * mixes kept their tracks eligible and the v0.9.85 sweep spared them. The
 * owner's rule (2026-09-16) — in Download mode a switched-on row downloads —
 * moves the protection to the switch: discovered mixes start OFF, and an OFF
 * mix is still ineligible and still swept. Stash Mixes are local recipes with
 * no switch and never count.
 *
 * One rule, applied at all three sites: a track is download-eligible only via a
 * membership in an active, sync-enabled playlist that is not a Stash Mix.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DownloadQueueDaoMixExclusionTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: DownloadQueueDao
    private lateinit var trackDao: TrackDao
    private lateinit var playlistDao: PlaylistDao

    private var dailyMixQueued = 0L
    private var stashMixQueued = 0L
    private var customQueued = 0L
    private var dailyMixUnqueued = 0L
    private var customUnqueued = 0L
    private var dailyMixManual = 0L
    private var dailyMixOffQueued = 0L
    private var dailyMixOffUnqueued = 0L

    @Before fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StashDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        dao = db.downloadQueueDao()
        trackDao = db.trackDao()
        playlistDao = db.playlistDao()

        // The daily mix and the custom playlist are switched ON; the Stash mix is
        // created sync_enabled but has no switch and is never download-eligible.
        val dailyMix = newPlaylist("Daily Mix 1", "spotify:playlist:dm", PlaylistType.DAILY_MIX)
        val stashMix = newPlaylist("Deep Cuts", "stash_mix_1", PlaylistType.STASH_MIX)
        val custom = newPlaylist("My Playlist", "spotify:playlist:mine", PlaylistType.CUSTOM)

        // Sync-created queue rows carry a sync_id — these are the phantom rows
        // the sweeps exist to drain.
        db.syncHistoryDao().insert(SyncHistoryEntity(id = 5L))
        dailyMixQueued = newTrack("A", dailyMix, queued = true, syncId = 5L)
        stashMixQueued = newTrack("B", stashMix, queued = true, syncId = 5L)
        customQueued = newTrack("C", custom, queued = true, syncId = 5L)
        dailyMixUnqueued = newTrack("D", dailyMix, queued = false)
        customUnqueued = newTrack("E", custom, queued = false)
        // A user's explicit Download tap on a mix-only track: manual partition
        // (sync_id NULL), no eligible parent playlist.
        dailyMixManual = newTrack("F", dailyMix, queued = true, syncId = null)
        // A mix nobody switched on — the #368 case, now expressed by the switch.
        val dailyMixOff = newPlaylist("Daily Mix 2", "spotify:playlist:dm-off", PlaylistType.DAILY_MIX, syncEnabled = false)
        dailyMixOffQueued = newTrack("G", dailyMixOff, queued = true, syncId = 5L)
        dailyMixOffUnqueued = newTrack("H", dailyMixOff, queued = false)
    }

    @After fun tearDown() { db.close() }

    @Test fun `a switched-on mix makes its track requeue-eligible, a switched-off one does not`() = runTest {
        val eligible = dao.getUnqueuedTrackIds(listOf(MusicSource.SPOTIFY.name))
        assertThat(eligible).contains(dailyMixUnqueued)
        assertThat(eligible).contains(customUnqueued)
        assertThat(eligible).doesNotContain(dailyMixOffUnqueued)
    }

    @Test fun `orphan sweep spares a switched-on mix and evicts a switched-off or stash mix`() = runTest {
        dao.deleteOrphanedQueueEntries()

        assertThat(dao.getByTrackId(dailyMixQueued)).isNotNull()
        assertThat(dao.getByTrackId(customQueued)).isNotNull()
        assertThat(dao.getByTrackId(dailyMixOffQueued)).isNull()
        assertThat(dao.getByTrackId(stashMixQueued)).isNull()
    }

    @Test fun `enabled-playlist sweep spares a switched-on mix and evicts a switched-off or stash mix`() = runTest {
        dao.cancelDownloadsWithNoEnabledPlaylist()

        assertThat(dao.getByTrackId(dailyMixQueued)).isNotNull()
        assertThat(dao.getByTrackId(customQueued)).isNotNull()
        assertThat(dao.getByTrackId(dailyMixOffQueued)).isNull()
        assertThat(dao.getByTrackId(stashMixQueued)).isNull()
    }

    /**
     * The other half of "only download what the user wants": a Download tap is
     * the strongest signal of intent there is, so neither sweep may evict it.
     * Manual rows live in the sync_id NULL partition; the sweeps exist to drain
     * phantom rows that a *sync* created, not a user's explicit request. Without
     * this, tapping Download on a track that lives only in a mix silently
     * produced nothing — the same class as the search-tab downloads that vanish
     * on relaunch.
     */
    @Test fun `neither sweep evicts a manually requested download`() = runTest {
        dao.deleteOrphanedQueueEntries()
        assertThat(dao.getByTrackId(dailyMixManual)).isNotNull()

        dao.cancelDownloadsWithNoEnabledPlaylist()
        assertThat(dao.getByTrackId(dailyMixManual)).isNotNull()
    }

    private suspend fun newPlaylist(
        name: String,
        sourceId: String,
        type: PlaylistType,
        syncEnabled: Boolean = true,
    ): Long =
        playlistDao.insert(
            PlaylistEntity(
                name = name,
                source = MusicSource.SPOTIFY,
                sourceId = sourceId,
                type = type,
                syncEnabled = syncEnabled,
                isActive = true,
            )
        )

    private suspend fun newTrack(
        tag: String,
        playlistId: Long,
        queued: Boolean,
        syncId: Long? = null,
    ): Long {
        val trackId = trackDao.insert(
            TrackEntity(
                title = "Track $tag",
                artist = "Artist $tag",
                canonicalTitle = "track ${tag.lowercase()}",
                canonicalArtist = "artist ${tag.lowercase()}",
                source = MusicSource.SPOTIFY,
                isDownloaded = false,
            )
        )
        playlistDao.insertCrossRef(
            PlaylistTrackCrossRef(
                playlistId = playlistId,
                trackId = trackId,
                position = 0,
                addedAt = Instant.parse("2026-07-01T00:00:00Z"),
                removedAt = null,
            )
        )
        if (queued) {
            dao.insert(
                DownloadQueueEntity(
                    trackId = trackId,
                    syncId = syncId,
                    status = DownloadStatus.PENDING,
                    searchQuery = "Artist $tag - Track $tag",
                )
            )
        }
        return trackId
    }
}
