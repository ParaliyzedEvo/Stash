package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.TrackBlocklistEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.MusicSource
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #380 — the Library Songs feed. `SELECT *` over a multi-thousand-row library
 * overflowed the ~2 MB CursorWindow and raced concurrent deletes (window
 * refills re-execute the statement and see a different table). The fix is a
 * narrow projection ([LibraryTrackRow], only the columns the domain mapper
 * consumes), the `is_downloaded` filter pushed into SQL (the repository used
 * to fetch stubs and drop them in memory), and `@Transaction` so every
 * emission reads one consistent snapshot. These tests pin the query's
 * contract; the CursorWindow behavior itself is device territory.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TrackDaoLibraryProjectionTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: TrackDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StashDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        dao = db.trackDao()
    }

    @After fun tearDown() { db.close() }

    private fun track(
        id: Long,
        title: String = "Song $id",
        downloaded: Boolean = true,
        dateAdded: Instant = Instant.ofEpochMilli(1_000_000 + id),
        canonicalArtist: String = "artist$id",
        canonicalTitle: String = "song$id",
    ) = TrackEntity(
        id = id,
        title = title,
        artist = "Artist $id",
        isDownloaded = downloaded,
        dateAdded = dateAdded,
        canonicalArtist = canonicalArtist,
        canonicalTitle = canonicalTitle,
    )

    @Test fun `returns only downloaded tracks`() = runTest {
        dao.insert(track(1, downloaded = true))
        dao.insert(track(2, downloaded = false))
        dao.insert(track(3, downloaded = true))

        val rows = dao.getLibraryByDateAdded().first()

        assertEquals(listOf(3L, 1L), rows.map { it.id })
    }

    @Test fun `orders by date added descending`() = runTest {
        dao.insert(track(1, dateAdded = Instant.ofEpochMilli(100)))
        dao.insert(track(2, dateAdded = Instant.ofEpochMilli(300)))
        dao.insert(track(3, dateAdded = Instant.ofEpochMilli(200)))

        val rows = dao.getLibraryByDateAdded().first()

        assertEquals(listOf(2L, 3L, 1L), rows.map { it.id })
    }

    @Test fun `excludes blocklisted tracks`() = runTest {
        dao.insert(track(1, canonicalArtist = "drake", canonicalTitle = "banned song"))
        dao.insert(track(2))
        db.trackBlocklistDao().insert(
            TrackBlocklistEntity(
                canonicalKey = "drake|banned song",
                artist = "Drake",
                title = "Banned Song",
                blockedAt = 1_000L,
                blockedFrom = "CONTEXT_MENU",
            )
        )

        val rows = dao.getLibraryByDateAdded().first()

        assertEquals(listOf(2L), rows.map { it.id })
    }

    @Test fun `projection carries every field the domain mapper consumes`() = runTest {
        val full = TrackEntity(
            id = 7,
            title = "Sinnerman",
            artist = "Nina Simone",
            album = "Pastel Blues",
            albumArtist = "Nina Simone",
            durationMs = 630_000,
            filePath = "/storage/emulated/0/Music/Nina Simone/Pastel Blues/Sinnerman.flac",
            fileFormat = "flac",
            qualityKbps = 1411,
            fileSizeBytes = 35_600_000,
            source = MusicSource.SPOTIFY,
            spotifyUri = "spotify:track:sinnerman7",
            youtubeId = "yt-sinnerman7",
            albumArtUrl = "https://img.example/sinnerman.jpg",
            albumArtPath = "/data/art/sinnerman.jpg",
            dateAdded = Instant.ofEpochMilli(1_700_000_000_000),
            lastPlayed = Instant.ofEpochMilli(1_700_000_500_000),
            playCount = 42,
            isDownloaded = true,
            matchConfidence = 0.93f,
            matchDismissed = false,
            isrc = "USVR10300001",
            explicit = false,
            bitsPerSample = 24,
            sampleRateHz = 96_000,
            spotifySavedAt = 111L,
            ytMusicSavedAt = 222L,
            lastFmLovedAt = 333L,
            stashLikedAt = 444L,
            isStreamable = true,
            isStreamableCheckedAt = 555L,
            metadataEmbeddedAt = 666L,
            lyricsFetchedAt = 777L,
        )
        dao.insert(full)

        val row = dao.getLibraryByDateAdded().first().single()

        assertEquals(full.id, row.id)
        assertEquals(full.title, row.title)
        assertEquals(full.artist, row.artist)
        assertEquals(full.album, row.album)
        assertEquals(full.albumArtist, row.albumArtist)
        assertEquals(full.durationMs, row.durationMs)
        assertEquals(full.filePath, row.filePath)
        assertEquals(full.fileFormat, row.fileFormat)
        assertEquals(full.qualityKbps, row.qualityKbps)
        assertEquals(full.fileSizeBytes, row.fileSizeBytes)
        assertEquals(full.source, row.source)
        assertEquals(full.spotifyUri, row.spotifyUri)
        assertEquals(full.youtubeId, row.youtubeId)
        assertEquals(full.albumArtUrl, row.albumArtUrl)
        assertEquals(full.albumArtPath, row.albumArtPath)
        assertEquals(full.dateAdded, row.dateAdded)
        assertEquals(full.lastPlayed, row.lastPlayed)
        assertEquals(full.playCount, row.playCount)
        assertEquals(full.isDownloaded, row.isDownloaded)
        assertEquals(full.matchConfidence, row.matchConfidence)
        assertEquals(full.matchDismissed, row.matchDismissed)
        assertEquals(full.isrc, row.isrc)
        assertEquals(full.explicit, row.explicit)
        assertEquals(full.bitsPerSample, row.bitsPerSample)
        assertEquals(full.sampleRateHz, row.sampleRateHz)
        assertEquals(full.spotifySavedAt, row.spotifySavedAt)
        assertEquals(full.ytMusicSavedAt, row.ytMusicSavedAt)
        assertEquals(full.lastFmLovedAt, row.lastFmLovedAt)
        assertEquals(full.stashLikedAt, row.stashLikedAt)
        assertEquals(full.isStreamable, row.isStreamable)
        assertEquals(full.isStreamableCheckedAt, row.isStreamableCheckedAt)
        assertEquals(full.metadataEmbeddedAt, row.metadataEmbeddedAt)
        assertEquals(full.lyricsFetchedAt, row.lyricsFetchedAt)
    }
}
