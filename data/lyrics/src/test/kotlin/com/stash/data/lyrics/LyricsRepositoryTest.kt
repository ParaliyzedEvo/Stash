package com.stash.data.lyrics

import com.stash.core.common.Clock
import com.stash.core.data.db.dao.LyricsDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.LyricsEntity
import com.stash.data.lyrics.sidecar.LyricsSidecarWriter
import com.stash.data.lyrics.source.LyricsQuery
import com.stash.data.lyrics.source.LyricsResult
import com.stash.data.lyrics.source.LyricsSource
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsRepositoryTest {

    private val clock = object : Clock { override fun now() = 1_700_000_000_000L }

    @Test fun `success path - writes row, stamps, invokes sidecar`() = runTest {
        val lrclib = fakeSource("lrclib", LyricsResult("lrclib", "plain", "[00:01.00]plain", false, null, "42"))
        val ytm = fakeSource("innertube", null)
        val lyricsDao = mockk<LyricsDao>()
        val trackDao = mockk<TrackDao>()
        val sidecar = mockk<LyricsSidecarWriter>()
        coEvery { lyricsDao.upsert(any()) } just Runs
        coEvery { trackDao.setLyricsFetchedAt(any(), any()) } just Runs
        coEvery { sidecar.write(any(), any()) } just Runs

        val repo = LyricsRepository(listOf(lrclib, ytm), lyricsDao, trackDao, sidecar, clock)
        val result = repo.resolveAndStore(query(1L))

        assertNotNull(result)
        val capture = slot<LyricsEntity>()
        coVerify { lyricsDao.upsert(capture(capture)) }
        assertEquals(1L, capture.captured.trackId)
        assertEquals("lrclib", capture.captured.source)
        coVerify { trackDao.setLyricsFetchedAt(1L, 1_700_000_000_000L) }
        coVerify { sidecar.write(1L, any()) }
    }

    @Test fun `instrumental path - writes row, stamps, does NOT invoke sidecar`() = runTest {
        val lrclib = fakeSource("lrclib", LyricsResult("lrclib", null, null, true, null, "42"))
        val lyricsDao = mockk<LyricsDao>(relaxed = true)
        val trackDao = mockk<TrackDao>(relaxed = true)
        val sidecar = mockk<LyricsSidecarWriter>()
        val repo = LyricsRepository(listOf(lrclib), lyricsDao, trackDao, sidecar, clock)
        repo.resolveAndStore(query(1L))
        coVerify(exactly = 0) { sidecar.write(any(), any()) }
        coVerify { trackDao.setLyricsFetchedAt(1L, 1_700_000_000_000L) }
    }

    @Test fun `complete miss - stamps 0L, no row, no sidecar`() = runTest {
        val a = fakeSource("lrclib", null)
        val b = fakeSource("innertube", null)
        val lyricsDao = mockk<LyricsDao>(relaxed = true)
        val trackDao = mockk<TrackDao>(relaxed = true)
        val sidecar = mockk<LyricsSidecarWriter>()
        val repo = LyricsRepository(listOf(a, b), lyricsDao, trackDao, sidecar, clock)
        assertNull(repo.resolveAndStore(query(1L)))
        coVerify(exactly = 0) { lyricsDao.upsert(any()) }
        coVerify(exactly = 0) { sidecar.write(any(), any()) }
        coVerify { trackDao.setLyricsFetchedAt(1L, 0L) }
    }

    @Test fun `source failure with no hit - THROWS and does NOT stamp 0L`() = runTest {
        // The poisoning bug: a transient failure must never be recorded as a
        // permanent miss. The stamp stays untouched so the track is retryable.
        val failing = throwingSource("lrclib", java.io.IOException("lrclib get HTTP 429"))
        val miss = fakeSource("innertube", null)
        val lyricsDao = mockk<LyricsDao>(relaxed = true)
        val trackDao = mockk<TrackDao>(relaxed = true)
        val sidecar = mockk<LyricsSidecarWriter>(relaxed = true)
        val repo = LyricsRepository(listOf(failing, miss), lyricsDao, trackDao, sidecar, clock)
        try {
            repo.resolveAndStore(query(1L))
            org.junit.Assert.fail("expected the source failure to propagate")
        } catch (e: java.io.IOException) {
            assertEquals("lrclib get HTTP 429", e.message)
        }
        coVerify(exactly = 0) { trackDao.setLyricsFetchedAt(any(), any()) }
        coVerify(exactly = 0) { lyricsDao.upsert(any()) }
    }

    @Test fun `source failure does not block a later source hit`() = runTest {
        val failing = throwingSource("lrclib", java.io.IOException("timeout"))
        val hit = fakeSource("innertube", LyricsResult("innertube", "plain", null, false, null, null))
        val lyricsDao = mockk<LyricsDao>(relaxed = true)
        val trackDao = mockk<TrackDao>(relaxed = true)
        val sidecar = mockk<LyricsSidecarWriter>(relaxed = true)
        val repo = LyricsRepository(listOf(failing, hit), lyricsDao, trackDao, sidecar, clock)
        assertNotNull(repo.resolveAndStore(query(1L)))
        coVerify { trackDao.setLyricsFetchedAt(1L, 1_700_000_000_000L) }
    }

    @Test fun `resolveTransient propagates failure instead of null`() = runTest {
        val failing = throwingSource("lrclib", java.io.IOException("timeout"))
        val repo = LyricsRepository(
            listOf(failing), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), clock,
        )
        try {
            repo.resolveTransient(query(0L))
            org.junit.Assert.fail("expected the source failure to propagate")
        } catch (expected: java.io.IOException) {
        }
    }

    @Test fun `source-chain order - first non-null wins`() = runTest {
        val a = fakeSource("lrclib", LyricsResult("lrclib", "p", null, false, null, "1"))
        val b = mockk<LyricsSource>(relaxed = true)
        val lyricsDao = mockk<LyricsDao>(relaxed = true)
        val trackDao = mockk<TrackDao>(relaxed = true)
        val sidecar = mockk<LyricsSidecarWriter>(relaxed = true)
        val repo = LyricsRepository(listOf(a, b), lyricsDao, trackDao, sidecar, clock)
        repo.resolveAndStore(query(1L))
        coVerify(exactly = 0) { b.resolve(any()) }
    }

    @Test fun `sidecar failure does not unwind Room write`() = runTest {
        val lrclib = fakeSource("lrclib", LyricsResult("lrclib", "p", null, false, null, "1"))
        val lyricsDao = mockk<LyricsDao>(relaxed = true)
        val trackDao = mockk<TrackDao>(relaxed = true)
        val sidecar = mockk<LyricsSidecarWriter>()
        coEvery { sidecar.write(any(), any()) } throws RuntimeException("disk full")
        val repo = LyricsRepository(listOf(lrclib), lyricsDao, trackDao, sidecar, clock)
        // Should NOT throw
        repo.resolveAndStore(query(1L))
        coVerify { lyricsDao.upsert(any()) }
        coVerify { trackDao.setLyricsFetchedAt(1L, 1_700_000_000_000L) }
    }

    private fun fakeSource(sourceId: String, result: LyricsResult?): LyricsSource = object : LyricsSource {
        override val id = sourceId
        override val displayName = sourceId
        override suspend fun resolve(query: LyricsQuery): LyricsResult? = result
    }

    private fun throwingSource(sourceId: String, e: Exception): LyricsSource = object : LyricsSource {
        override val id = sourceId
        override val displayName = sourceId
        override suspend fun resolve(query: LyricsQuery): LyricsResult? = throw e
    }

    private fun query(id: Long) = LyricsQuery(
        trackId = id, title = "T", artist = "A", album = null, albumArtist = null,
        durationMs = 200_000, youtubeVideoId = null,
    )
}
