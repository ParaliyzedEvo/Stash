package com.stash.core.data.mix

import com.stash.core.data.db.entity.StashMixRecipeEntity

/** Resolves a recipe's effective Last.fm tag set for TAG_GRAPH seeding. */
object RecipeTagResolver {
    fun resolve(recipe: StashMixRecipeEntity, userTopTags: List<String>): List<String> {
        val genres = recipe.includeTagsCsv.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val moods = MoodTagMap.expand(recipe.moodKeysCsv)
        val era = recipe.eraStartYear?.let { listOf(decadeTag(it)) }.orEmpty()
        val explicit = (genres + moods + era).distinct()
        return explicit.ifEmpty { userTopTags.map { it.lowercase() }.distinct() }
    }

    /** 1974 -> "70s", 2003 -> "2000s". */
    fun decadeTag(year: Int): String =
        if (year < 2000) "${(year % 100) / 10}0s" else "${(year / 10) * 10}s"
}
