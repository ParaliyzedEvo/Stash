package com.stash.core.data.social.ytmusic

import com.stash.data.ytmusic.InnerTubeClient
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail
import org.junit.Test

class YtMusicLibraryApiClientTest {

    private val innerTube = mockk<InnerTubeClient>()
    private val client = YtMusicLibraryApiClient(innerTube)

    @Test fun `removeLike passes through on success`() = runBlocking {
        coEvery { innerTube.removeLike("vid") } returns true
        client.removeLike("vid") // must not throw
    }

    @Test fun `removeLike lifts false into YtMusicLibraryException`() = runBlocking {
        coEvery { innerTube.removeLike("vid") } returns false
        try {
            client.removeLike("vid")
            fail("expected YtMusicLibraryException")
        } catch (_: YtMusicLibraryException) { }
    }

    @Test fun `likeVideo lifts false into YtMusicLibraryException`() = runBlocking {
        coEvery { innerTube.likeVideo("vid") } returns false
        try {
            client.likeVideo("vid")
            fail("expected YtMusicLibraryException")
        } catch (_: YtMusicLibraryException) { }
    }
}
