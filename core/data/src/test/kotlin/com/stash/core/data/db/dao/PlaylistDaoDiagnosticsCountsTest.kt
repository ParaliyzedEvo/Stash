package com.stash.core.data.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/** The diagnostics bundle's Library counts: what the SQL counts, per source + type. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PlaylistDaoDiagnosticsCountsTest {

    private lateinit var db: StashDatabase
    private lateinit var playlistDao: PlaylistDao
    private lateinit var trackDao: TrackDao

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, StashDatabase::class.java)
            .allowMainThreadQueries().build()
        playlistDao = db.playlistDao()
        trackDao = db.trackDao()
    }

    @After fun tearDown() { db.close() }

    @Test fun `counts active playlists per source and type`() = runTest {
        trackDao.insert(track(1, downloaded = true))
        trackDao.insert(track(2, downloaded = true, missingAt = 5L))
        trackDao.insert(track(3, downloaded = false))

        // Spotify mixes: one synced with a live track, one switched on but never synced and
        // empty, one whose only track was removed (counts as empty).
        val synced = playlistDao.insert(playlist("a", PlaylistType.DAILY_MIX, on = true, synced = true))
        playlistDao.insertCrossRef(PlaylistTrackCrossRef(synced, 1))
        playlistDao.insert(playlist("b", PlaylistType.DAILY_MIX, on = true, synced = false))
        val removed = playlistDao.insert(playlist("c", PlaylistType.DAILY_MIX, on = false, synced = true))
        playlistDao.insertCrossRef(PlaylistTrackCrossRef(removed, 2, removedAt = Instant.now()))
        // A custom playlist, and an inactive one that must not be counted as active.
        playlistDao.insert(playlist("d", PlaylistType.CUSTOM, on = false, synced = true))
        playlistDao.insert(playlist("e", PlaylistType.CUSTOM, on = true, synced = true, active = false))

        assertEquals(
            listOf(
                PlaylistDiagnosticsRow("SPOTIFY", "CUSTOM", total = 1, switchedOn = 0, neverSynced = 0, empty = 1),
                PlaylistDiagnosticsRow("SPOTIFY", "DAILY_MIX", total = 3, switchedOn = 2, neverSynced = 1, empty = 2),
            ),
            playlistDao.diagnosticsCounts(),
        )
        assertEquals(1, playlistDao.inactiveCount())
        assertEquals(TrackDiagnosticsTotals(total = 3, downloaded = 2, missingFiles = 1), trackDao.diagnosticsTotals())
    }

    @Test fun `an empty library counts zero, not null`() = runTest {
        assertEquals(emptyList<PlaylistDiagnosticsRow>(), playlistDao.diagnosticsCounts())
        assertEquals(TrackDiagnosticsTotals(0, 0, 0), trackDao.diagnosticsTotals())
    }

    private fun playlist(id: String, type: PlaylistType, on: Boolean, synced: Boolean, active: Boolean = true) =
        PlaylistEntity(
            name = id,
            source = MusicSource.SPOTIFY,
            sourceId = id,
            type = type,
            syncEnabled = on,
            lastSynced = if (synced) Instant.now() else null,
            isActive = active,
        )

    private fun track(id: Long, downloaded: Boolean, missingAt: Long? = null) = TrackEntity(
        id = id,
        title = "t$id",
        artist = "a",
        canonicalTitle = "t$id",
        canonicalArtist = "a",
        source = MusicSource.SPOTIFY,
        isDownloaded = downloaded,
        downloadMissingAt = missingAt,
    )
}
