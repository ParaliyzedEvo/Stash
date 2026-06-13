package com.stash.core.data.mix

import com.stash.core.data.db.entity.StashMixRecipeEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class MixBuildStateTest {
    private fun recipe(builtin: Boolean = false, playlistId: Long? = 5L) =
        StashMixRecipeEntity(
            id = 1, name = "Lo-fi", includeTagsCsv = "lo-fi",
            seedStrategy = "TAG_GRAPH", isBuiltin = builtin, playlistId = playlistId,
            lastRefreshedAt = 1_000L,
        )

    @Test fun `has tracks is READY`() {
        assertEquals(MixBuildState.READY, mixBuildState(recipe(), trackCount = 12, nonFailedDiscoveryCount = 0))
    }

    @Test fun `not yet materialized is BUILDING`() {
        assertEquals(
            MixBuildState.BUILDING,
            mixBuildState(recipe(playlistId = null), trackCount = 0, nonFailedDiscoveryCount = 0),
        )
    }

    @Test fun `materialized but empty with discovery in flight is BUILDING`() {
        assertEquals(
            MixBuildState.BUILDING,
            mixBuildState(recipe(), trackCount = 0, nonFailedDiscoveryCount = 8),
        )
    }

    @Test fun `materialized empty with no discovery left is EMPTY`() {
        assertEquals(
            MixBuildState.EMPTY,
            mixBuildState(recipe(), trackCount = 0, nonFailedDiscoveryCount = 0),
        )
    }

    @Test fun `builtin mixes are always READY (never show building)`() {
        assertEquals(
            MixBuildState.READY,
            mixBuildState(recipe(builtin = true, playlistId = 6L), trackCount = 0, nonFailedDiscoveryCount = 0),
        )
    }
}
