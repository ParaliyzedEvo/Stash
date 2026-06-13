package com.stash.core.data.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
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
 * Verifies migration v30 -> v31, which adds the additive
 * `lastfm_response_cache` table (cache_key PK, json, fetched_at).
 *
 * `runMigrationsAndValidate` (validateDroppedTables = true) is the load-
 * bearing assertion: it diffs the post-migration schema against the
 * exported `31.json`, so any column/type/constraint drift between the
 * hand-written DDL and the entity fails the test. The before/after
 * sqlite_master checks pin that the migration is what creates the table,
 * and the round-trip insert proves the columns are usable in practice.
 *
 * A bad migration is the worst bug class in the app — it bricks every
 * existing user's DB on update with no downgrade path — so the two newest
 * migrations (this and v31->v32) get the same coverage as the rest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV30V31Test {

    private val DB_NAME = "migration-v30v31-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `migration v30 to v31 creates lastfm_response_cache`() {
        helper.createDatabase(DB_NAME, 30).use { db ->
            // Precondition: the table does not exist at v30.
            db.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='lastfm_response_cache'",
            ).use { c ->
                assertEquals(0, c.count)
            }
        }

        val migrated = helper.runMigrationsAndValidate(
            DB_NAME, 31, true, StashDatabase.MIGRATION_30_31,
        )

        // The table now exists...
        migrated.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='lastfm_response_cache'",
        ).use { c ->
            assertEquals(1, c.count)
        }

        // ...and is functionally usable with all declared columns.
        migrated.insert(
            "lastfm_response_cache",
            SQLiteDatabase.CONFLICT_FAIL,
            ContentValues().apply {
                put("cache_key", "artist::track")
                put("json", """{"ok":true}""")
                put("fetched_at", 1_716_000_000_000L)
            },
        )
        migrated.query(
            "SELECT json, fetched_at FROM lastfm_response_cache WHERE cache_key = 'artist::track'",
        ).use { c ->
            assertEquals(1, c.count)
            assertTrue(c.moveToFirst())
            assertEquals("""{"ok":true}""", c.getString(0))
            assertEquals(1_716_000_000_000L, c.getLong(1))
        }
    }
}
