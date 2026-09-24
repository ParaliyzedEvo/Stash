package com.stash.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV47V48Test {
    private val dbName = "migration-v47v48-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), StashDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun `v47 to v48 adds shared_mixes and keeps playlists`() {
        helper.createDatabase(dbName, 47).use { db ->
            db.execSQL("INSERT INTO playlists (id, name, source, source_id, type, track_count, is_active) VALUES (1, 'Ambient', 'BOTH', 'custom_1', 'CUSTOM', 110, 1)")
        }
        val db = helper.runMigrationsAndValidate(dbName, 48, true, StashDatabase.MIGRATION_47_48)
        db.execSQL("INSERT INTO shared_mixes (playlist_id, share_id, role, name, version, content_hash, auto_update, status, missing_count, notice_pending) VALUES (1, 'Kx7Qa2pL', 'OWNER', 'Ambient', 1, 'h', 1, 'ACTIVE', 0, 0)")
        db.query("SELECT share_id FROM shared_mixes WHERE playlist_id = 1").use { c ->
            assertTrue(c.moveToNext()); assertEquals("Kx7Qa2pL", c.getString(0))
        }
    }
}
