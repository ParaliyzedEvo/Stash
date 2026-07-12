package com.stash.core.data.radio

/**
 * Pure ordering of a radio candidate pool into play order.
 *
 * Groups candidates by artist (preserving each artist's own order), then draws
 * across the groups with smooth weighted round-robin: an artist's "credit"
 * accumulates by its weight each slot; the highest-credit artist that is not the
 * previous one is picked and pays 1 credit. This yields frequency ∝ weight and
 * avoids back-to-back repeats while alternatives remain. No I/O — unit-testable.
 */
object RadioInterleaver {
    fun order(pool: List<RadioCandidate>): List<RadioCandidate> {
        if (pool.size <= 1) return pool
        // Per-artist FIFO queues, and each artist's weight (candidates of one
        // artist share a weight; take the max as the artist's frequency weight).
        val queues = LinkedHashMap<String, ArrayDeque<RadioCandidate>>()
        val weights = LinkedHashMap<String, Float>()
        for (c in pool) {
            queues.getOrPut(c.artist) { ArrayDeque() }.addLast(c)
            weights[c.artist] = maxOf(weights[c.artist] ?: 0f, c.weight)
        }
        val credit = HashMap<String, Float>().apply { queues.keys.forEach { this[it] = 0f } }
        val out = ArrayList<RadioCandidate>(pool.size)
        var previous: String? = null
        repeat(pool.size) {
            for (a in queues.keys) if (queues.getValue(a).isNotEmpty()) {
                credit[a] = credit.getValue(a) + weights.getValue(a)
            }
            // Highest-credit non-empty artist that isn't `previous`; fall back to
            // `previous` only if it's the sole remaining non-empty artist.
            val nonEmpty = queues.keys.filter { queues.getValue(it).isNotEmpty() }
            val pick = (nonEmpty.filter { it != previous }.maxByOrNull { credit.getValue(it) }
                ?: nonEmpty.first())
            out.add(queues.getValue(pick).removeFirst())
            credit[pick] = credit.getValue(pick) - 1f
            previous = pick
        }
        return out
    }
}
