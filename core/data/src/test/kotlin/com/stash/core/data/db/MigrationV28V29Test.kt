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
 * v0.9.38 / library-health-phase1: verifies migration v28 -> v29
 * rewrites every legacy `download_queue.failure_type = 'DOWNLOAD_ERROR'`
 * row to `'UNKNOWN'`.
 *
 * Why: Phase 1 deletes the `DOWNLOAD_ERROR` enum constant from
 * [com.stash.core.model.download.DownloadFailureType]. Without this
 * migration, `Converters.fromFailureType`'s `valueOf(it)` call would
 * crash with `IllegalArgumentException` the first time Room reads a
 * legacy row. Room runs migrations before any DAO read by contract,
 * so once this migration ships the converter is safe.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV28V29Test {

    private val DB_NAME = "migration-v28v29-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `migration v28 to v29 rewrites DOWNLOAD_ERROR rows to UNKNOWN`() {
        helper.createDatabase(DB_NAME, 28).use { db ->
            db.insertDownloadQueueRow(
                id = 1L,
                failureType = "DOWNLOAD_ERROR",
            )
            // Honest precondition: confirm the v28 seed actually stored
            // 'DOWNLOAD_ERROR' before the migration touches it. Without
            // this readback, a silently corrupted insert could still
            // produce a post-migration 'UNKNOWN' for the wrong reason.
            db.query("SELECT failure_type FROM download_queue WHERE id = 1").use { c ->
                assertEquals(1, c.count)
                assertTrue(c.moveToFirst())
                assertEquals("DOWNLOAD_ERROR", c.getString(0))
            }
        }

        val migrated = helper.runMigrationsAndValidate(
            DB_NAME, 29, true, StashDatabase.MIGRATION_28_29,
        )

        migrated.query("SELECT failure_type FROM download_queue WHERE id = 1").use { c ->
            assertEquals(1, c.count)
            assertTrue(c.moveToFirst())
            assertEquals("UNKNOWN", c.getString(0))
        }
    }

    @Test
    fun `migration v28 to v29 leaves non-DOWNLOAD_ERROR failure_types untouched`() {
        helper.createDatabase(DB_NAME, 28).use { db ->
            db.insertDownloadQueueRow(id = 1L, failureType = "NONE")
            db.insertDownloadQueueRow(id = 2L, failureType = "DOWNLOAD_ERROR")
            // NO_MATCH is a real pre-Task-1 enum constant that legitimately
            // appears in user databases; the migration must leave it alone.
            db.insertDownloadQueueRow(id = 3L, failureType = "NO_MATCH")
            // A deliberate non-enum literal that *contains* 'DOWNLOAD_ERROR'
            // as a substring. Asserts the WHERE clause is literal equality
            // (`= 'DOWNLOAD_ERROR'`), not a LIKE/substring match.
            db.insertDownloadQueueRow(id = 4L, failureType = "DOWNLOAD_ERROR_SUFFIX")
        }

        val migrated = helper.runMigrationsAndValidate(
            DB_NAME, 29, true, StashDatabase.MIGRATION_28_29,
        )

        migrated.query(
            "SELECT id, failure_type FROM download_queue ORDER BY id",
        ).use { c ->
            assertEquals(4, c.count)
            assertTrue(c.moveToFirst())
            assertEquals(1L, c.getLong(0))
            assertEquals("NONE", c.getString(1))
            assertTrue(c.moveToNext())
            assertEquals(2L, c.getLong(0))
            assertEquals("UNKNOWN", c.getString(1))
            assertTrue(c.moveToNext())
            assertEquals(3L, c.getLong(0))
            assertEquals("NO_MATCH", c.getString(1))
            assertTrue(c.moveToNext())
            assertEquals(4L, c.getLong(0))
            assertEquals("DOWNLOAD_ERROR_SUFFIX", c.getString(1))
        }
    }

    /**
     * Insert a download_queue row at v28 schema. The download_queue
     * table has a FK to `tracks(id)` ON DELETE CASCADE; FK enforcement
     * is off by default on a fresh test DB so the missing parent row
     * doesn't reject the insert. NOT NULL columns per 28.json:
     * id, track_id, status, search_query, retry_count, failure_type,
     * created_at.
     */
    private fun androidx.sqlite.db.SupportSQLiteDatabase.insertDownloadQueueRow(
        id: Long,
        failureType: String,
    ) {
        val cv = ContentValues().apply {
            put("id", id)
            put("track_id", id)
            put("status", "FAILED")
            put("search_query", "")
            put("retry_count", 0)
            put("failure_type", failureType)
            put("created_at", 1_716_000_000_000L)
        }
        insert("download_queue", SQLiteDatabase.CONFLICT_REPLACE, cv)
    }
}
