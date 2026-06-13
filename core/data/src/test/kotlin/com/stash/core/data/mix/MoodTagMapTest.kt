package com.stash.core.data.mix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoodTagMapTest {
    @Test fun `expand resolves known moods to their tag sets`() {
        val tags = MoodTagMap.expand("chill,focus")
        assertTrue(tags.contains("chill"))
        assertTrue(tags.contains("ambient"))
        assertEquals(tags, tags.map { it.lowercase() })
        assertEquals(tags.size, tags.toSet().size)
    }
    @Test fun `expand skips unknown mood ids gracefully`() {
        assertEquals(emptyList<String>(), MoodTagMap.expand("not_a_mood"))
    }
    @Test fun `expand handles blank input`() {
        assertEquals(emptyList<String>(), MoodTagMap.expand(""))
        assertEquals(emptyList<String>(), MoodTagMap.expand("  "))
    }
}
