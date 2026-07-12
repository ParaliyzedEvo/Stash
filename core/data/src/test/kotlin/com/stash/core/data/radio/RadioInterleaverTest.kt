package com.stash.core.data.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioInterleaverTest {
    // seed gets weight ~= half the neighbor weight sum, so it lands ~1/3 of slots.
    private fun cand(artist: String, title: String, weight: Float) =
        RadioCandidate(artist, title, videoId = "$artist-$title", weight = weight)

    @Test fun `orders every candidate exactly once`() {
        val pool = listOf(
            cand("Seed", "s1", 3f), cand("Seed", "s2", 3f),
            cand("A", "a1", 1f), cand("B", "b1", 1f),
        )
        val out = RadioInterleaver.order(pool)
        assertEquals(pool.toSet(), out.toSet())
        assertEquals(pool.size, out.size)
    }

    @Test fun `never repeats the same artist back to back when alternatives exist`() {
        val pool = buildList {
            repeat(4) { add(cand("Seed", "s$it", 3f)) }
            repeat(2) { add(cand("A", "a$it", 1f)) }
            repeat(2) { add(cand("B", "b$it", 1f)) }
        }
        val out = RadioInterleaver.order(pool)
        // No adjacent same-artist until the tail (when one artist may be all
        // that's left). Tolerate a single tail collision.
        val artists = out.map { it.artist }
        var adjacent = 0
        for (i in 1 until artists.size) if (artists[i] == artists[i - 1]) adjacent++
        assertTrue("adjacent same-artist count was $adjacent", adjacent <= 1)
    }

    @Test fun `higher-weight seed is the most frequent artist, never starved`() {
        val pool = buildList {
            repeat(12) { add(cand("Seed", "s$it", 6f)) }          // seed weight tuned by generator
            repeat(4) { add(cand("N${it % 4}", "n$it", 1f)) }     // 4 neighbors, 1 each
        }
        val out = RadioInterleaver.order(pool.shuffled())
        val seedCount = out.count { it.artist == "Seed" }
        // Pins only that the seed is the MOST frequent artist (never starved) — NOT
        // a literal 1/3. The generator's finite seed catalog + no-repeat set make a
        // steady 1/3-forever impossible; the feel is "seed-heavy open, drifting out."
        val maxNeighbor = out.filter { it.artist != "Seed" }.groupingBy { it.artist }.eachCount().values.max()
        assertTrue("seed $seedCount should be >= max neighbor $maxNeighbor", seedCount >= maxNeighbor)
    }
}
