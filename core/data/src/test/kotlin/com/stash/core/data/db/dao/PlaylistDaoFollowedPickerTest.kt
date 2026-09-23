package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.SharedMixEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PlaylistDaoFollowedPickerTest {
    private lateinit var db: StashDatabase
    @Before fun setUp() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java).allowMainThreadQueries().build() }
    @After fun tearDown() { db.close() }

    @Test fun `an active follow is not pickable, a converted one is`() = runTest {
        val dao = db.playlistDao()
        val mine = dao.insert(PlaylistEntity(name = "Mine", source = MusicSource.BOTH, sourceId = "custom_1", type = PlaylistType.CUSTOM, syncEnabled = true))
        val followed = dao.insert(PlaylistEntity(name = "Followed", source = MusicSource.BOTH, sourceId = "share:AAAAAAAA", type = PlaylistType.CUSTOM))
        db.sharedMixDao().insert(SharedMixEntity(followed, "AAAAAAAA", SharedMixEntity.ROLE_FOLLOWER, name = "Followed"))
        assertThat(dao.getPickablePlaylists().first().map { it.id }).containsExactly(mine)
        assertThat(dao.getUserCreatedPlaylists().first().map { it.id }).containsExactly(mine)
        // Followed with Download off and nothing downloaded: still in Library (the user asked for it).
        assertThat(dao.getAllVisible(includeStreamable = false).first().map { it.id }).contains(followed)
        db.sharedMixDao().markRemoved(followed, noticePending = false)
        assertThat(dao.getPickablePlaylists().first().map { it.id }).containsExactly(mine, followed)
    }
}
