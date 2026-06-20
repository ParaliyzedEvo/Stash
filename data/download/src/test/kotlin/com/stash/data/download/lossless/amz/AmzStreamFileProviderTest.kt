package com.stash.data.download.lossless.amz

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.stash.data.download.lossless.LosslessUrlDownloader
import com.stash.data.download.lossless.SourceResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests for [AmzStreamFileProvider]: the decrypt-to-cache seam behind amz
 * streaming. Fetch+decrypt is delegated to [LosslessUrlDownloader] (slice 3,
 * already tested), so this mocks the downloader and verifies the caching,
 * cache-hit, eviction, and failure behaviour the provider adds on top.
 */
class AmzStreamFileProviderTest {

    @get:Rule val tmp = TemporaryFolder()

    private val context: Context = mockk()
    private val downloader: LosslessUrlDownloader = mockk()
    private lateinit var cacheRoot: File

    @Before fun setUp() {
        cacheRoot = tmp.newFolder("cache")
        every { context.cacheDir } returns cacheRoot
    }

    private fun provider() = AmzStreamFileProvider(context, downloader)

    private val key = "8164fe2db5ebd498c8265b3e873462c1"
    private val encUrl = "https://amz.squid.wtf/api/stream?asin=B001&country=US&tier=best"

    @Test fun `cache miss fetches+decrypts to asin flac and returns it`() = runTest {
        val destSlot = slot<File>()
        val sourceSlot = slot<SourceResult>()
        coEvery { downloader.download(capture(sourceSlot), capture(destSlot), any()) } answers {
            destSlot.captured.parentFile?.mkdirs()
            destSlot.captured.writeText("DECRYPTED_FLAC")
            Result.success(destSlot.captured)
        }

        val file = provider().resolveLocalFile("B001", encUrl, key)

        assertThat(file).isNotNull()
        assertThat(file!!.name).isEqualTo("B001.flac")
        assertThat(file.readText()).isEqualTo("DECRYPTED_FLAC")
        // The SourceResult handed to the downloader carries the encrypted URL
        // and the decryption key (so it runs the decrypt branch).
        assertThat(sourceSlot.captured.downloadUrl).isEqualTo(encUrl)
        assertThat(sourceSlot.captured.decryptionKey).isEqualTo(key)
    }

    @Test fun `cache hit returns existing file without re-downloading`() = runTest {
        val dir = File(cacheRoot, "amz_stream").apply { mkdirs() }
        File(dir, "B001.flac").writeText("ALREADY_DECRYPTED")

        val file = provider().resolveLocalFile("B001", encUrl, key)

        assertThat(file).isNotNull()
        assertThat(file!!.readText()).isEqualTo("ALREADY_DECRYPTED")
        coVerify(exactly = 0) { downloader.download(any(), any(), any()) }
    }

    @Test fun `zero-byte cache file is treated as a miss`() = runTest {
        val dir = File(cacheRoot, "amz_stream").apply { mkdirs() }
        File(dir, "B001.flac").createNewFile() // empty
        coEvery { downloader.download(any(), capture(slot<File>()), any()) } answers {
            secondArg<File>().writeText("FRESH")
            Result.success(secondArg<File>())
        }

        val file = provider().resolveLocalFile("B001", encUrl, key)

        assertThat(file!!.readText()).isEqualTo("FRESH")
        coVerify(exactly = 1) { downloader.download(any(), any(), any()) }
    }

    @Test fun `concurrent resolves of the same asin download only once`() = runTest {
        var downloads = 0
        coEvery { downloader.download(any(), any(), any()) } coAnswers {
            downloads++
            delay(50) // hold the lock so the second call overlaps
            secondArg<File>().writeText("DECRYPTED")
            Result.success(secondArg<File>())
        }

        val p = provider()
        val a = async { p.resolveLocalFile("B001", encUrl, key) }
        val b = async { p.resolveLocalFile("B001", encUrl, key) }
        val fileA = a.await()
        val fileB = b.await()

        // The second concurrent caller must wait on the first and then cache-hit,
        // not re-fetch into the same temp (which corrupts the file).
        assertThat(downloads).isEqualTo(1)
        assertThat(fileA!!.readText()).isEqualTo("DECRYPTED")
        assertThat(fileB!!.readText()).isEqualTo("DECRYPTED")
    }

    @Test fun `different asins resolve in parallel (no cross-blocking)`() = runTest {
        coEvery { downloader.download(any(), any(), any()) } answers {
            secondArg<File>().writeText("OK")
            Result.success(secondArg<File>())
        }

        val p = provider()
        assertThat(p.resolveLocalFile("AAA", encUrl, key)).isNotNull()
        assertThat(p.resolveLocalFile("BBB", encUrl, key)).isNotNull()
        coVerify(exactly = 2) { downloader.download(any(), any(), any()) }
    }

    @Test fun `download failure returns null`() = runTest {
        coEvery { downloader.download(any(), any(), any()) } returns
            Result.failure(IllegalStateException("decrypt failed"))

        assertThat(provider().resolveLocalFile("B001", encUrl, key)).isNull()
    }

    @Test fun `eviction trims oldest files when over the cap, keeping the new one`() = runTest {
        val dir = File(cacheRoot, "amz_stream").apply { mkdirs() }
        // Two pre-existing cached tracks, OLD then NEWER.
        val old = File(dir, "OLD.flac").apply { writeBytes(ByteArray(400)); setLastModified(1_000L) }
        val mid = File(dir, "MID.flac").apply { writeBytes(ByteArray(400)); setLastModified(2_000L) }
        coEvery { downloader.download(any(), any(), any()) } answers {
            secondArg<File>().writeBytes(ByteArray(400))
            secondArg<File>().setLastModified(3_000L)
            Result.success(secondArg<File>())
        }

        val p = provider().apply { maxCacheBytes = 1000L } // cap fits ~2 of the 400B files
        val fresh = p.resolveLocalFile("NEW", encUrl, key)

        assertThat(fresh).isNotNull()
        assertThat(File(dir, "NEW.flac").exists()).isTrue() // never evict the just-written file
        assertThat(old.exists()).isFalse()                  // oldest evicted first
        assertThat(mid.exists()).isTrue()                   // newer survivor kept
    }
}
