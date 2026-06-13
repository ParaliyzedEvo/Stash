package com.stash.core.data.mix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MixRecipeFormTest {
    @Test fun `maps genres moods era discovery to a TAG_GRAPH custom recipe`() {
        val form = MixRecipeForm(
            name = "Late Night Jazz",
            genreTags = setOf("jazz", "soul"),
            moodKeys = setOf("chill"),
            eraStartYear = 1990, eraEndYear = 1999,
            discoveryRatio = 0.85f,
        )
        val r = form.toRecipe(existingId = null)
        assertEquals("Late Night Jazz", r.name)
        assertEquals("jazz,soul", r.includeTagsCsv)
        assertEquals("chill", r.moodKeysCsv)
        assertEquals(1990, r.eraStartYear); assertEquals(1999, r.eraEndYear)
        assertEquals(0.85f, r.discoveryRatio, 0f)
        assertEquals("TAG_GRAPH", r.seedStrategy)
        assertFalse(r.isBuiltin)
        assertTrue(r.isActive)
        assertEquals(0L, r.id)
    }
    @Test fun `preserves id when editing`() {
        val r = MixRecipeForm(name = "X", genreTags = setOf("rock")).toRecipe(existingId = 42L)
        assertEquals(42L, r.id)
    }
    @Test fun `isValid requires at least one genre or mood, name optional`() {
        // Name is OPTIONAL now — a genre alone is enough.
        assertTrue(MixRecipeForm(name = "", genreTags = setOf("rock")).isValid)
        assertTrue(MixRecipeForm(name = "", moodKeys = setOf("chill")).isValid)
        // But you still need at least one genre or mood.
        assertFalse(MixRecipeForm(name = "X").isValid)
        assertFalse(MixRecipeForm(name = "").isValid)
    }

    @Test fun `displayName auto-generates from picks when name blank`() {
        val f = MixRecipeForm(name = "", genreTags = setOf("jazz", "soul"))
        assertEquals("Jazz · Soul Mix", f.displayName)
        // a mood-only blank-name mix still gets a name
        assertEquals("Chill Mix", MixRecipeForm(name = "", moodKeys = setOf("chill")).displayName)
        // explicit name always wins
        assertEquals("My Mix", MixRecipeForm(name = "My Mix", genreTags = setOf("jazz")).displayName)
    }

    @Test fun `toRecipe uses auto-name when the form name is blank`() {
        val r = MixRecipeForm(name = "  ", genreTags = setOf("jazz")).toRecipe(existingId = null)
        assertEquals("Jazz Mix", r.name)
    }
}
