package com.stash.core.data.mix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreCatalogTest {
    @Test fun `families are non-empty and each has genres`() {
        assertTrue(GenreCatalog.FAMILIES.isNotEmpty())
        GenreCatalog.FAMILIES.forEach { f ->
            assertTrue("family '${f.name}' has no genres", f.genres.isNotEmpty())
        }
    }
    @Test fun `all genre tags are lowercase, trimmed, unique across the catalog`() {
        val all = GenreCatalog.FAMILIES.flatMap { it.genres }
        assertEquals(all, all.map { it.trim().lowercase() })
        assertEquals("duplicate genre tag in catalog", all.size, all.toSet().size)
    }
    @Test fun `allGenres exposes the flattened set`() {
        assertEquals(GenreCatalog.FAMILIES.flatMap { it.genres }.toSet(), GenreCatalog.allGenres())
    }
}
