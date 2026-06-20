package com.stash.data.download.lossless

import com.google.common.truth.Truth.assertThat
import com.stash.data.download.lossless.amz.AmzDecryptor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests for [LosslessUrlDownloader] — in particular the amz decrypt branch:
 * when [SourceResult.decryptionKey] is set, the fetched body is encrypted CMAF
 * and must be ffmpeg-decrypted to clear FLAC before the file is usable. The
 * [AmzDecryptor] is mocked (it "is" ffmpeg); the clear-audio path (Qobuz/Kennyy,
 * no key) must NOT touch it.
 */
class LosslessUrlDownloaderTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val decryptor: AmzDecryptor = mockk()
    private lateinit var downloader: LosslessUrlDownloader

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        downloader = LosslessUrlDownloader(OkHttpClient(), decryptor)
    }

    @After fun tearDown() = server.shutdown()

    private fun source(url: String, key: String? = null) = SourceResult(
        sourceId = "amz",
        downloadUrl = url,
        format = AudioFormat(codec = "flac", bitrateKbps = 0, sampleRateHz = 0, bitsPerSample = 0),
        confidence = 0.9f,
        decryptionKey = key,
    )

    @Test fun `clear-audio path writes body to destination and never decrypts`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("CLEAR_FLAC_BYTES"))
        val dest = File(tmp.root, "track.flac")

        val result = downloader.download(source(server.url("/f.flac").toString(), key = null), dest)

        assertThat(result.isSuccess).isTrue()
        assertThat(dest.readText()).isEqualTo("CLEAR_FLAC_BYTES")
        coVerify(exactly = 0) { decryptor.decryptToFlac(any(), any(), any()) }
    }

    @Test fun `encrypted path fetches to a temp then decrypts to destination and cleans up`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ENCRYPTED_CMAF"))
        val dest = File(tmp.root, "track.flac")
        val enc = File("${dest.absolutePath}.enc")
        coEvery { decryptor.decryptToFlac(any(), any(), any()) } answers {
            // ffmpeg sees the encrypted temp, not the final destination.
            assertThat(firstArg<File>().readText()).isEqualTo("ENCRYPTED_CMAF")
            thirdArg<File>().writeText("DECRYPTED_FLAC")
            true
        }

        val result = downloader.download(source(server.url("/e").toString(), key = "8164fe2db5ebd498c8265b3e873462c1"), dest)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo(dest)
        assertThat(dest.readText()).isEqualTo("DECRYPTED_FLAC")
        assertThat(enc.exists()).isFalse()
        coVerify { decryptor.decryptToFlac(any(), eq("8164fe2db5ebd498c8265b3e873462c1"), dest) }
    }

    @Test fun `encrypted path returns failure and cleans up when decrypt fails`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ENCRYPTED_CMAF"))
        val dest = File(tmp.root, "track.flac")
        coEvery { decryptor.decryptToFlac(any(), any(), any()) } returns false

        val result = downloader.download(source(server.url("/e").toString(), key = "deadbeef"), dest)

        assertThat(result.isFailure).isTrue()
        assertThat(dest.exists()).isFalse()
        assertThat(File("${dest.absolutePath}.enc").exists()).isFalse()
    }
}
