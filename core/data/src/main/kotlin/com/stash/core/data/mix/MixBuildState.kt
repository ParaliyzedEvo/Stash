package com.stash.core.data.mix

import com.stash.core.data.db.entity.StashMixRecipeEntity

/** Lifecycle of a custom Stash Mix as its tracks populate after creation. */
enum class MixBuildState { BUILDING, READY, EMPTY }

/**
 * Derive a custom mix's build state from its recipe, the materialized
 * playlist's track count, and how many non-failed discovery rows it has
 * (see [com.stash.core.data.db.dao.DiscoveryQueueDao.observeNonFailedCountsByRecipe]).
 *
 * - [MixBuildState.READY]: it has tracks (or it's a built-in).
 * - [MixBuildState.BUILDING]: not materialized yet, OR materialized-but-empty
 *   while discovery still owes tracks (in flight, or resolved but not yet
 *   linked into the playlist on the next materialize).
 * - [MixBuildState.EMPTY]: materialized, still has no tracks, and discovery has
 *   nothing left to give — i.e. the chosen genres/moods found nothing.
 *
 * Built-ins always read READY: they're never empty-on-create and shouldn't
 * show a "building" affordance.
 */
fun mixBuildState(
    recipe: StashMixRecipeEntity,
    trackCount: Int,
    nonFailedDiscoveryCount: Int,
): MixBuildState = when {
    recipe.isBuiltin -> MixBuildState.READY
    trackCount > 0 -> MixBuildState.READY
    recipe.playlistId == null -> MixBuildState.BUILDING
    nonFailedDiscoveryCount > 0 -> MixBuildState.BUILDING
    else -> MixBuildState.EMPTY
}
