package com.stash.core.data.lastfm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LastFmReadKeySelectorTest {

    @Test
    fun `picks the key at the round-robin start index`() {
        assertEquals("B", selectReadKey(listOf("A", "B", "C"), startIndex = 1) { false })
    }

    @Test
    fun `wraps around the pool with modulo`() {
        assertEquals("A", selectReadKey(listOf("A", "B", "C"), startIndex = 3) { false })
    }

    @Test
    fun `skips throttled keys and returns the next available one`() {
        val open = setOf("A", "B")
        assertEquals("C", selectReadKey(listOf("A", "B", "C"), startIndex = 0) { it in open })
    }

    @Test
    fun `returns null when every key is throttled`() {
        assertNull(selectReadKey(listOf("A", "B"), startIndex = 0) { true })
    }

    @Test
    fun `returns null for an empty pool`() {
        assertNull(selectReadKey(emptyList(), startIndex = 0) { false })
    }

    @Test
    fun `single-key pool returns that key when it is available`() {
        assertEquals("A", selectReadKey(listOf("A"), startIndex = 5) { false })
    }
}
