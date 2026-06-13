package com.stash.core.data.mix

import com.stash.core.data.db.entity.StashMixRecipeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeTagResolverTest {
    private fun recipe(include: String = "", moods: String = "", eraStart: Int? = null) =
        StashMixRecipeEntity(
            name = "X", includeTagsCsv = include, moodKeysCsv = moods,
            eraStartYear = eraStart, seedStrategy = "TAG_GRAPH",
        )

    @Test fun `unions genres moods and era decade tag`() {
        val tags = RecipeTagResolver.resolve(
            recipe(include = "jazz,soul", moods = "chill", eraStart = 1990),
            userTopTags = emptyList(),
        )
        assertTrue(tags.containsAll(listOf("jazz", "soul")))
        assertTrue(tags.contains("chill"))
        assertTrue(tags.contains("90s"))
        assertEquals(tags.size, tags.toSet().size)
    }
    @Test fun `falls back to user top tags when recipe has no explicit tags`() {
        assertEquals(
            listOf("indie", "rock"),
            RecipeTagResolver.resolve(recipe(), userTopTags = listOf("indie", "rock")),
        )
    }
    @Test fun `maps era start year to its decade tag`() {
        assertEquals("70s", RecipeTagResolver.decadeTag(1974))
        assertEquals("2000s", RecipeTagResolver.decadeTag(2003))
        assertEquals("2010s", RecipeTagResolver.decadeTag(2015))
    }
}
