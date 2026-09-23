package com.stash.core.data.share

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.entity.SharedMixEntity
import org.junit.Test

class SharedMixFollowWorkerTest {
    private fun row(checked: Long?) = SharedMixEntity(1, "Kx7Qa2pL", SharedMixEntity.ROLE_FOLLOWER, name = "A", lastCheckedAt = checked)
    private val sixHours = 6 * 3600_000L

    @Test fun `forced checks always run, otherwise at most every 6 hours`() {
        val now = 10 * sixHours
        assertThat(SharedMixFollowWorker.isDue(row(now - 1000), now, force = true)).isTrue()
        assertThat(SharedMixFollowWorker.isDue(row(now - 1000), now, force = false)).isFalse()
        assertThat(SharedMixFollowWorker.isDue(row(now - sixHours), now, force = false)).isTrue()
        assertThat(SharedMixFollowWorker.isDue(row(null), now, force = false)).isTrue()
    }
}
