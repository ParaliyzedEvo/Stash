package com.stash.core.data.mix

import com.stash.core.data.db.entity.StashMixRecipeEntity

/** UI-agnostic builder form state + mapping to a custom TAG_GRAPH recipe. */
data class MixRecipeForm(
    val name: String = "",
    val genreTags: Set<String> = emptySet(),
    val moodKeys: Set<String> = emptySet(),
    val eraStartYear: Int? = null,
    val eraEndYear: Int? = null,
    val discoveryRatio: Float = 0.85f,
    val targetLength: Int = 40,
) {
    /**
     * A mix is saveable as soon as it has at least one genre or mood. The name
     * is OPTIONAL — if left blank we auto-generate one from the picks (see
     * [displayName]). This keeps "create from my preferences" friction-free.
     */
    val isValid: Boolean
        get() = genreTags.isNotEmpty() || moodKeys.isNotEmpty()

    /** The effective name: the user's trimmed input, or an auto-name from the picks. */
    val displayName: String
        get() = name.trim().ifBlank { autoName() }

    private fun autoName(): String {
        val parts = (genreTags + moodKeys).take(2).map { it.replaceFirstChar(Char::uppercase) }
        return if (parts.isEmpty()) "New Mix" else parts.joinToString(" · ") + " Mix"
    }

    fun toRecipe(existingId: Long?): StashMixRecipeEntity = StashMixRecipeEntity(
        id = existingId ?: 0L,
        name = displayName,
        includeTagsCsv = genreTags.joinToString(","),
        moodKeysCsv = moodKeys.joinToString(","),
        eraStartYear = eraStartYear,
        eraEndYear = eraEndYear,
        discoveryRatio = discoveryRatio,
        targetLength = targetLength,
        seedStrategy = "TAG_GRAPH",
        isBuiltin = false,
        isActive = true,
    )

    companion object {
        /** Rebuild form state from an existing recipe (for edit). */
        fun fromRecipe(r: StashMixRecipeEntity): MixRecipeForm = MixRecipeForm(
            name = r.name,
            genreTags = r.includeTagsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
            moodKeys = r.moodKeysCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
            eraStartYear = r.eraStartYear, eraEndYear = r.eraEndYear,
            discoveryRatio = r.discoveryRatio, targetLength = r.targetLength,
        )
    }
}
