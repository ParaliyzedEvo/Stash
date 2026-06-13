package com.stash.core.data.lastfm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LastFmCacheKeyTest {

    @Test
    fun `key excludes api_key and format so it survives key rotation and pooling`() {
        val withKey1 = lastFmCacheKey(
            mapOf(
                "method" to "tag.gettoptracks", "tag" to "techno",
                "limit" to "50", "api_key" to "KEY_ONE", "format" to "json",
            ),
        )
        val withKey2 = lastFmCacheKey(
            mapOf(
                "method" to "tag.gettoptracks", "tag" to "techno",
                "limit" to "50", "api_key" to "KEY_TWO",
            ),
        )
        // Same logical request → same cache entry regardless of which API key
        // (or whether `format` was set) served it.
        assertEquals(withKey1, withKey2)
    }

    @Test
    fun `different request params produce different keys`() {
        assertNotEquals(
            lastFmCacheKey(mapOf("method" to "tag.gettoptracks", "tag" to "techno")),
            lastFmCacheKey(mapOf("method" to "tag.gettoptracks", "tag" to "house")),
        )
    }

    @Test
    fun `key is independent of param insertion order`() {
        assertEquals(
            lastFmCacheKey(linkedMapOf("a" to "1", "b" to "2", "method" to "x")),
            lastFmCacheKey(linkedMapOf("method" to "x", "b" to "2", "a" to "1")),
        )
    }
}
