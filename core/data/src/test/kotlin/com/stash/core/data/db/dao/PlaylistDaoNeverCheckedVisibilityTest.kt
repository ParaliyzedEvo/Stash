package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * The state a synced library is actually in: every track `is_streamable = 0`
 * with a NULL `is_streamable_checked_at`, because nothing in the app ever
 * writes that flag. [PlaylistDaoMixVisibilityTest] seeds `isStreamable = true`
 * by hand — a state production never reaches — so it stayed green while a
 * fresh Online install rendered an empty Home over a full library (#477, #478).
 *
 * Online, a never-checked track must count as playable, as AutoBrowse and
 * next-track prefetch already read it. Only a confirmed check is a negative.
 * Offline is unchanged: downloaded content, or nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PlaylistDaoNeverCheckedVisibilityTest {
    private lateinit var db: StashDatabase
    private lateinit var dao: PlaylistDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StashDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.playlistDao()
    }

    @After fun tearDown() = db.close()

    @Test fun `an un-opted playlist of never-checked tracks is visible online`() = runTest {
        val id = playlistWith(PlaylistType.CUSTOM, checkedAt = null)
        assertEquals(listOf(id), dao.getAllVisible(includeStreamable = true).first().map { it.id })
    }

    @Test fun `liked songs of never-checked tracks is visible online`() = runTest {
        val id = playlistWith(PlaylistType.LIKED_SONGS, checkedAt = null)
        assertEquals(listOf(id), dao.getAllVisible(includeStreamable = true).first().map { it.id })
    }

    /** Offline is the downloads-only surface; nothing here is downloaded. */
    @Test fun `the same playlist stays hidden offline`() = runTest {
        playlistWith(PlaylistType.CUSTOM, checkedAt = null)
        assertEquals(emptyList<Long>(), dao.getAllVisible(includeStreamable = false).first().map { it.id })
    }

    /** A check that came back negative is still a negative — the arm is not "everything". */
    @Test fun `a confirmed-unstreamable track does not surface the playlist`() = runTest {
        playlistWith(PlaylistType.CUSTOM, checkedAt = 1_000L)
        assertEquals(emptyList<Long>(), dao.getAllVisible(includeStreamable = true).first().map { it.id })
    }

    /** Seeds a sync-disabled, unpinned, active playlist holding one stream-only track. */
    private suspend fun playlistWith(type: PlaylistType, checkedAt: Long?): Long {
        val playlistId = dao.insert(
            PlaylistEntity(
                name = "Mine",
                source = MusicSource.SPOTIFY,
                sourceId = "spotify:playlist:mine",
                type = type,
                syncEnabled = false,
                isActive = true,
            )
        )
        val trackId = db.trackDao().insert(
            TrackEntity(
                title = "Borderline",
                artist = "Tame Impala",
                canonicalTitle = "borderline",
                canonicalArtist = "tame impala",
                isDownloaded = false,
                isStreamable = false,
                isStreamableCheckedAt = checkedAt,
            )
        )
        dao.insertCrossRef(
            PlaylistTrackCrossRef(
                playlistId = playlistId,
                trackId = trackId,
                position = 0,
                addedAt = Instant.parse("2026-09-16T00:00:00Z"),
                removedAt = null,
            )
        )
        return playlistId
    }
}
