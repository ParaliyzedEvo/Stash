package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PlaylistDaoLegacyMixConsentTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: PlaylistDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StashDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        dao = db.playlistDao()
    }

    @After fun tearDown() { db.close() }

    @Test fun `legacy cleanup disables only daily mixes`() = runTest {
        val spotifyMix = insert("Spotify Mix", MusicSource.SPOTIFY, PlaylistType.DAILY_MIX, true)
        val youtubeMix = insert("YouTube Mix", MusicSource.YOUTUBE, PlaylistType.DAILY_MIX, true)
        val custom = insert("Road Trip", MusicSource.YOUTUBE, PlaylistType.CUSTOM, true)
        val liked = insert("Liked Songs", MusicSource.YOUTUBE, PlaylistType.LIKED_SONGS, true)
        val alreadyOff = insert("Mix already off", MusicSource.YOUTUBE, PlaylistType.DAILY_MIX, false)

        assertThat(dao.disableLegacyDailyMixSync()).isEqualTo(2)

        assertThat(dao.getById(spotifyMix)!!.syncEnabled).isFalse()
        assertThat(dao.getById(youtubeMix)!!.syncEnabled).isFalse()
        assertThat(dao.getById(custom)!!.syncEnabled).isTrue()
        assertThat(dao.getById(liked)!!.syncEnabled).isTrue()
        assertThat(dao.getById(alreadyOff)!!.syncEnabled).isFalse()
        assertThat(dao.disableLegacyDailyMixSync()).isEqualTo(0)
    }

    private suspend fun insert(
        name: String,
        source: MusicSource,
        type: PlaylistType,
        syncEnabled: Boolean,
    ): Long = dao.insert(
        PlaylistEntity(
            name = name,
            source = source,
            sourceId = "$source:$name",
            type = type,
            syncEnabled = syncEnabled,
        )
    )
}
