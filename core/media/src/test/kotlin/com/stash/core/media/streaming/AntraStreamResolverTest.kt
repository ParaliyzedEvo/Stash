package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.download.lossless.antra.AntraClient
import com.stash.data.download.lossless.antra.AntraCredentialStore
import com.stash.data.download.lossless.antra.AntraJobGate
import com.stash.data.download.lossless.antra.AntraJobCreated
import com.stash.data.download.lossless.antra.AntraJobStatus
import com.stash.data.download.lossless.antra.AntraMe
import com.stash.data.download.lossless.spotifyresolve.SpotifyUriResolver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Unit tests for [AntraStreamResolver].
 *
 * Collaborators are mocked and a real temp directory backs the cache, so
 * the test exercises the two paths that matter for quota: a cache HIT must
 * never enqueue a job (no spend), and a cache MISS drives job→complete→bytes
 * and writes the file. Gating (no spotifyUri / not connected / job failed)
 * returns null so the streaming registry fails over.
 */
class AntraStreamResolverTest {

    private val client: AntraClient = mockk(relaxed = true)
    private val store: AntraCredentialStore = mockk()
    private val spotifyUriResolver: SpotifyUriResolver = mockk(relaxed = true)
    private lateinit var cacheDir: File
    private lateinit var resolver: AntraStreamResolver

    @Before fun setUp() {
        cacheDir = File.createTempFile("antra-cache", "").apply {
            delete(); mkdirs()
        }
        resolver = AntraStreamResolver(client, store, cacheDir, AntraJobGate(), spotifyUriResolver)
    }

    @After fun tearDown() {
        cacheDir.deleteRecursively()
    }

    private fun track() = TrackEntity(
        id = 42L,
        title = "Pusherman",
        artist = "Curtis Mayfield",
        album = "Super Fly",
        durationMs = 297_000L,
        spotifyUri = "spotify:track:abc123",
    )

    private fun cacheFile() = File(File(cacheDir, "antra"), "42.flac")

    @Test fun `cache hit returns local file without enqueuing a job`() = runTest {
        coEvery { store.isConnected() } returns true
        cacheFile().parentFile!!.mkdirs()
        cacheFile().writeText("CACHED-FLAC")

        val result = resolver.resolve(track())

        assertThat(result).isNotNull()
        assertThat(result!!.origin).isEqualTo("antra")
        assertThat(result.url).startsWith("file://")
        assertThat(result.url).contains("42.flac")
        assertThat(result.codec).isEqualTo("flac")
        assertThat(result.bitsPerSample).isEqualTo(24)
        // The whole point: a cache hit spends NO quota.
        coVerify(exactly = 0) { client.createJob(any(), any(), any()) }
    }

    @Test fun `cache miss runs job and writes the cache file`() = runTest {
        coEvery { store.isConnected() } returns true
        coEvery { client.me() } returns AntraMe(username = "rawn", singles_left = 5)
        coEvery { client.createJob(any(), 0, 1) } returns AntraJobCreated(job_id = "job-7")
        coEvery { client.pollStatus("job-7") } returns
            AntraJobStatus(job_id = "job-7", status = "complete")
        val destSlot = slot<File>()
        coEvery { client.downloadTo(eq("job-7"), capture(destSlot)) } answers {
            destSlot.captured.apply { parentFile?.mkdirs() }.writeText("FETCHED-FLAC")
            true
        }

        val result = resolver.resolve(track())

        assertThat(result).isNotNull()
        assertThat(result!!.url).contains("42.flac")
        assertThat(cacheFile().exists()).isTrue()
        assertThat(cacheFile().readText()).isEqualTo("FETCHED-FLAC")
    }

    @Test fun `concurrent resolves of the same track spend only one single`() = runTest {
        // Two callers (next-track prefetch + the foreground tap, or two
        // racing prefetches) hit the SAME uncached track at once. The cache
        // file is written only after the job completes, so without in-flight
        // coalescing each caller sees no cache, creates its own job, and
        // spends a single — observed on-device as 3 jobs (3 singles) for one
        // track. They must share ONE job and resolve to the same file.
        coEvery { store.isConnected() } returns true
        coEvery { client.me() } returns AntraMe(username = "rawn", singles_left = 5)
        coEvery { client.createJob(any(), 0, 1) } returns AntraJobCreated(job_id = "job-7")
        // Gate the poll on an external signal so both resolves are provably
        // in flight simultaneously before either job can finish.
        val pollRelease = CompletableDeferred<Unit>()
        coEvery { client.pollStatus("job-7") } coAnswers {
            pollRelease.await()
            AntraJobStatus(job_id = "job-7", status = "complete")
        }
        val destSlot = slot<File>()
        coEvery { client.downloadTo(eq("job-7"), capture(destSlot)) } answers {
            destSlot.captured.apply { parentFile?.mkdirs() }.writeText("FETCHED-FLAC")
            true
        }

        val a = async { resolver.resolve(track()) }
        val b = async { resolver.resolve(track()) }
        // Run both resolves up to their suspension points (the gated poll /
        // the in-flight join) at t=0 WITHOUT advancing the virtual clock —
        // advanceUntilIdle would fly past the 180s withTimeout and cancel
        // the owner. This is what forces the concurrent race the fix survives.
        runCurrent()
        pollRelease.complete(Unit)
        val ra = a.await()
        val rb = b.await()

        assertThat(ra).isNotNull()
        assertThat(rb).isNotNull()
        assertThat(ra!!.url).contains("42.flac")
        assertThat(rb!!.url).contains("42.flac")
        // The whole point: ONE job, ONE single spent for the two callers.
        coVerify(exactly = 1) { client.createJob(any(), 0, 1) }
    }

    @Test fun `null when track has no spotifyUri`() = runTest {
        coEvery { store.isConnected() } returns true
        assertThat(resolver.resolve(track().copy(spotifyUri = null))).isNull()
    }

    @Test fun `null when not connected`() = runTest {
        coEvery { store.isConnected() } returns false
        assertThat(resolver.resolve(track())).isNull()
    }

    @Test fun `null when job does not complete`() = runTest {
        coEvery { store.isConnected() } returns true
        coEvery { client.me() } returns AntraMe(username = "rawn", singles_left = 5)
        coEvery { client.createJob(any(), 0, 1) } returns AntraJobCreated(job_id = "job-7")
        coEvery { client.pollStatus("job-7") } returns
            AntraJobStatus(job_id = "job-7", status = "failed")

        assertThat(resolver.resolve(track())).isNull()
        assertThat(cacheFile().exists()).isFalse()
    }
}
