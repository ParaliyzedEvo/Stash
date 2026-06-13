package com.stash.data.download.ytdlp

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests the once-per-session gate ([YtDlpManager.runFreshenOnce]) that the
 * download path uses to guarantee the freshest yt-dlp (nightly) before the
 * first download in a session, without re-running the (network) update on
 * every subsequent download.
 *
 * The native update operation is injected via [YtDlpManager.freshenOp] so
 * these tests never touch the youtubedl-android singleton.
 */
class YtDlpManagerFreshenTest {

    private fun manager() = YtDlpManager(mockk<Context>(relaxed = true))

    @Test
    fun `runFreshenOnce invokes the freshen op exactly once across many sequential calls`() = runTest {
        val mgr = manager()
        val calls = AtomicInteger(0)
        mgr.freshenOp = { calls.incrementAndGet() }

        repeat(5) { mgr.runFreshenOnce() }

        assertEquals(1, calls.get())
    }

    @Test
    fun `runFreshenOnce coalesces concurrent callers into a single freshen op`() = runTest {
        val mgr = manager()
        val calls = AtomicInteger(0)
        mgr.freshenOp = {
            // Yield inside the critical section to expose any missing lock.
            withContext(Dispatchers.Default) { delay(20) }
            calls.incrementAndGet()
        }

        val jobs = List(10) { async(Dispatchers.Default) { mgr.runFreshenOnce() } }
        jobs.awaitAll()

        assertEquals(1, calls.get())
    }
}
