package com.stash.data.download.preview

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The probe exists because "does this URL play" is not answerable from the head of
 * the file. A PO-token-gated InnerTube URL serves its first ~1 MB and then 403s:
 * it looks perfect in a search preview and dies mid-track in playback, which is
 * what got the InnerTube fast lane pulled off the playback path. Only the last
 * byte distinguishes the two.
 *
 * It fails OPEN on purpose. A false rejection costs a needless ~3.6 s yt-dlp
 * fallback; a false acceptance is still caught downstream by
 * RefreshingDataSourceFactory's 403 re-resolve. So absence of evidence (no
 * contentLength, a timeout, a dead socket) must never veto a URL.
 *
 * Uses `runBlocking`, NOT `runTest`: the probe does real socket I/O on
 * Dispatchers.IO while its budget is a `withTimeoutOrNull`. Under `runTest`'s
 * virtual clock that timeout fires instantly, the probe fails open, and every
 * assertion that depends on the real response code silently passes for the
 * wrong reason.
 */
class AudioUrlTailProbeTest {

    private lateinit var server: MockWebServer
    private lateinit var probe: AudioUrlTailProbe

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        probe = AudioUrlTailProbe(OkHttpClient())
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun `206 on the tail byte passes`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(206).setBody("x"))
        assertThat(probe.servesFullFile(server.url("/a").toString(), contentLength = 5_000_000L))
            .isTrue()
    }

    @Test fun `403 on the tail byte fails`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        assertThat(probe.servesFullFile(server.url("/a").toString(), contentLength = 5_000_000L))
            .isFalse()
    }

    @Test fun `missing contentLength passes without making a request`() = runBlocking {
        assertThat(probe.servesFullFile(server.url("/a").toString(), contentLength = null)).isTrue()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun `the probe requests only the final byte`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(206).setBody("x"))
        probe.servesFullFile(server.url("/a").toString(), contentLength = 1000L)
        assertThat(server.takeRequest().getHeader("Range")).isEqualTo("bytes=999-")
    }

    @Test fun `transport failure passes rather than vetoing`() = runBlocking {
        val url = server.url("/a").toString()
        server.shutdown()
        assertThat(probe.servesFullFile(url, contentLength = 1000L)).isTrue()
    }

    /**
     * 416 means "not in that shape", NOT "you may not have this" — so it must
     * pass, like every other non-refusal.
     *
     * This is a real regression, not a hypothetical. The probe originally
     * accepted 200/206 only, which was safe while the sole caller was
     * InnerTube, supplying an EXACT `contentLength` from adaptiveFormats. The
     * yt-dlp caller supplies `filesize_approx` when yt-dlp has no exact size,
     * and an over-estimate puts `contentLength - 1` past EOF: the CDN answers
     * 416, which proves the URL is alive and honouring ranges, yet the old
     * check scored it as a gate and demoted a working client to the next one
     * in the chain — pure latency, for a URL that was fine.
     */
    @Test fun `416 past the end of file passes - an approx size is not a gate`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(416))
        assertThat(probe.servesFullFile(server.url("/a").toString(), contentLength = 5_000_000L))
            .isTrue()
    }

    /** The other explicit refusals share 403's meaning and must also reject. */
    @Test fun `401 and 410 reject like 403`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        assertThat(probe.servesFullFile(server.url("/a").toString(), contentLength = 5_000_000L))
            .isFalse()
        server.enqueue(MockResponse().setResponseCode(410))
        assertThat(probe.servesFullFile(server.url("/b").toString(), contentLength = 5_000_000L))
            .isFalse()
    }
}
