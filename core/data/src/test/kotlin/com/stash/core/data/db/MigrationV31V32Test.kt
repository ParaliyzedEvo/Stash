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
 * Verifies migration v31 -> v32, which adds the additive
 * `spotify_resolution` side-table (trackId PK, status, spotifyUri,
 * matchedIsrc, titleSim, durDeltaSec, resolvedAtMs, expiresAtMs,
 * attempts DEFAULT 1) used to cache local-track → Spotify-URI resolution.
 *
 * As with v30->v31, `runMigrationsAndValidate` against `32.json` is the
 * primary assertion; the round-trip insert additionally confirms the
 * nullable columns and the `attempts` default behave as declared.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV31V32Test {

    private val DB_NAME = "migration-v31v32-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `migration v31 to v32 creates spotify_resolution`() {
        helper.createDatabase(DB_NAME, 31).use { db ->
            db.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='spotify_resolution'",
            ).use { c ->
                assertEquals(0, c.count)
            }
        }

        val migrated = helper.runMigrationsAndValidate(
            DB_NAME, 32, true, StashDatabase.MIGRATION_31_32,
        )

        migrated.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='spotify_resolution'",
        ).use { c ->
            assertEquals(1, c.count)
        }

        // Insert omitting the nullable columns AND `attempts` to confirm the
        // nullables accept absence and `attempts` falls back to its DEFAULT 1.
        migrated.insert(
            "spotify_resolution",
            SQLiteDatabase.CONFLICT_FAIL,
            ContentValues().apply {
                put("trackId", 42L)
                put("status", "POSITIVE")
                put("resolvedAtMs", 1_716_000_000_000L)
                put("expiresAtMs", 1_716_000_500_000L)
            },
        )
        migrated.query(
            "SELECT status, spotifyUri, attempts FROM spotify_resolution WHERE trackId = 42",
        ).use { c ->
            assertEquals(1, c.count)
            assertTrue(c.moveToFirst())
            assertEquals("POSITIVE", c.getString(0))
            assertTrue("spotifyUri should be NULL when omitted", c.isNull(1))
            assertEquals(1L, c.getLong(2)) // DEFAULT 1
        }
    }
}
