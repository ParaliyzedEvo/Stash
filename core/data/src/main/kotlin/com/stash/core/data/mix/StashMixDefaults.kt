package com.stash.core.data.mix

import com.stash.core.data.db.dao.StashMixRecipeDao
import com.stash.core.data.db.entity.StashMixRecipeEntity

/**
 * Ships Stash's built-in mix recipes. As of v0.9.40 there is a single
 * built-in flagship — **Daily Discover** (ARTIST_SIMILAR, 85% discovery /
 * 15% library) — and everything else is user-built via the Mix Builder.
 * The earlier "Deep Cuts" and "First Listen" builtins were retired:
 * `StashApplication.maybeRemoveRetiredBuiltinMixes` deletes them (and their
 * playlists) from existing installs; fresh installs never seed them.
 *
 * Only seeds when [StashMixRecipeDao.countBuiltins] is zero, so users
 * don't get defaults re-inserted every launch. Upgrades go through
 * `StashApplication.maybeRetuneStashMixes` which non-destructively updates
 * surviving built-in rows in place via [StashMixRecipeDao.retuneBuiltin].
 */
object StashMixDefaults {

    suspend fun seedIfNeeded(dao: StashMixRecipeDao) {
        if (dao.countBuiltins() > 0) return
        ALL.forEach { dao.insert(it) }
    }

    val ALL: List<StashMixRecipeEntity> = listOf(
        StashMixRecipeEntity(
            name = "Daily Discover",
            description = "Mostly fresh finds via similar-artists. A pinch of your library.",
            affinityBias = 0.0f,
            freshnessWindowDays = 7,
            discoveryRatio = 0.85f,
            targetLength = 40,
            seedStrategy = "ARTIST_SIMILAR",
            isBuiltin = true,
        ),
        // v0.9.40: "Deep Cuts" and "First Listen" retired — Daily Discover is
        // the sole built-in; users build the rest via the Mix Builder.
        // Existing installs get the two removed by
        // StashApplication.maybeRemoveRetiredBuiltinMixes().
    )
}
