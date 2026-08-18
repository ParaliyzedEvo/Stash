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

/**
 * Verifies migration v41 -> v42: `playlists` gains nullable
 * `pinned_to_home_at`; existing rows read back NULL (not pinned to Home)
 * and the column is writable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV41V42Test {

    private val DB_NAME = "migration-v41v42-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `existing playlists read back NULL pinned_to_home_at and the column is writable`() {
        helper.createDatabase(DB_NAME, 41).use { db ->
            db.execSQL(
                """
                INSERT INTO playlists (id, name, source, source_id, type, track_count, is_active)
                VALUES (1, 'Gym', 'SPOTIFY', 'sp1', 'CUSTOM', 10, 1)
                """.trimIndent(),
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            DB_NAME, 42, true, StashDatabase.MIGRATION_41_42,
        )

        migrated.query("SELECT pinned_to_home_at FROM playlists WHERE id = 1").use { c ->
            assertTrue(c.moveToNext())
            assertTrue("existing rows must not be pinned", c.isNull(0))
        }

        migrated.execSQL("UPDATE playlists SET pinned_to_home_at = 1723900000000 WHERE id = 1")
        migrated.query("SELECT pinned_to_home_at FROM playlists WHERE id = 1").use { c ->
            assertTrue(c.moveToNext())
            assertEquals(1723900000000L, c.getLong(0))
        }
    }
}
