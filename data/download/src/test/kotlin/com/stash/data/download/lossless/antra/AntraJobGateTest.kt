package com.stash.data.download.lossless.antra

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * [AntraJobGate] must serialize job lifecycles to one at a time — antra
 * rejects a second concurrent job with HTTP 429, so the download pipeline's
 * parallel workers (and the stream path) have to take turns.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AntraJobGateTest {

    @Test
    fun `withJob runs blocks mutually exclusively, second waits for first`() = runTest {
        val gate = AntraJobGate()
        val order = mutableListOf<String>()
        val firstInside = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()

        val job1 = launch {
            gate.withJob {
                order.add("1-start")
                firstInside.complete(Unit)
                releaseFirst.await()
                order.add("1-end")
            }
        }
        firstInside.await() // job1 is now inside the gate

        val job2 = launch {
            gate.withJob { order.add("2-start") }
        }
        runCurrent() // give job2 a chance to enter — it must NOT, gate is held

        assertThat(order).containsExactly("1-start")

        releaseFirst.complete(Unit)
        job1.join()
        job2.join()

        assertThat(order).containsExactly("1-start", "1-end", "2-start").inOrder()
    }
}
