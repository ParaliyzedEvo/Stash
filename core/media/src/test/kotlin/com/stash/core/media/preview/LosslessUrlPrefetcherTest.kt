package com.stash.core.media.preview

import com.stash.core.model.TrackItem
import com.stash.data.download.lossless.LosslessSourceRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The foreground (tap) [LosslessUrlPrefetcher.lookup] must resolve with
 * `bypassRateLimit = true` so a preview isn't throttled behind speculative
 * background prefetches, while the background [LosslessUrlPrefetcher.warmUp]
 * stays rate-limited (`bypassRateLimit = false`).
 */
class LosslessUrlPrefetcherTest {
    private val registry: LosslessSourceRegistry = mockk(relaxed = true)
    private val item = TrackItem(
        videoId = "v1", title = "t", artist = "a", durationSeconds = 180.0, thumbnailUrl = null,
    )

    @Test fun `cold lookup resolves with bypassRateLimit true`() = runTest {
        coEvery { registry.resolve(any(), any()) } returns null

        LosslessUrlPrefetcher(registry).lookup(item)

        // lookup awaits its own resolve, so the call has definitely landed here.
        coVerify { registry.resolve(any(), bypassRateLimit = true) }
    }

    @Test fun `warmUp resolves with bypassRateLimit false`() = runTest {
        coEvery { registry.resolve(any(), any()) } returns null

        LosslessUrlPrefetcher(registry).warmUp(item)

        // warmUp dispatches onto the prefetcher's own Dispatchers.IO scope (NOT the
        // runTest scheduler), so poll with a timeout rather than racing a bare verify.
        coVerify(timeout = 1000) { registry.resolve(any(), bypassRateLimit = false) }
    }
}
