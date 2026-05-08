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
 * v0.9.17: Verifies migration v21 -> v22 introduces
 * `DownloadStatus.WAITING_FOR_LOSSLESS` without breaking existing rows.
 *
 * The status column on `download_queue` is TEXT storing the enum's
 * `.name`, so the new value parses without DDL — this migration is a
 * no-op on disk, and the test simply asserts that an existing v21 row
 * survives, the schema is still valid at v22, and the new enum name
 * round-trips cleanly through INSERT/SELECT.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV21V22Test {

    private val DB_NAME = "migration-v21v22-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `migration v21 to v22 keeps existing rows and accepts WAITING_FOR_LOSSLESS`() {
        // 1. Open at v21, write a parent track + a download_queue row in PENDING.
        helper.createDatabase(DB_NAME, 21).use { db ->
            db.insertTrackV21(id = 1L)
            db.insertDownloadQueueRowV21(trackId = 1L, status = "PENDING")
        }

        // 2. Run migration to v22.
        val migrated = helper.runMigrationsAndValidate(
            DB_NAME, 22, true, StashDatabase.MIGRATION_21_22,
        )

        // 3. Existing PENDING row survives the migration unchanged.
        migrated.query(
            "SELECT track_id, status FROM download_queue WHERE track_id = 1"
        ).use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(1L, c.getLong(0))
            assertEquals("PENDING", c.getString(1))
        }

        // 4. The new enum name is acceptable in the TEXT column at v22.
        migrated.insertTrackV21(id = 2L)
        migrated.insertDownloadQueueRowV21(trackId = 2L, status = "WAITING_FOR_LOSSLESS")

        migrated.query(
            "SELECT status FROM download_queue WHERE track_id = 2"
        ).use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("WAITING_FOR_LOSSLESS", c.getString(0))
        }

        // 5. Filtering by the new status works (covers index_download_queue_status).
        migrated.query(
            "SELECT COUNT(*) FROM download_queue WHERE status = 'WAITING_FOR_LOSSLESS'"
        ).use { c ->
            c.moveToFirst()
            assertTrue(c.getInt(0) >= 1)
        }
    }

    /**
     * Insert a minimally-populated tracks row so a download_queue row
     * referencing it via FK(track_id) is satisfied. Mirrors every
     * NOT NULL column on the v21 tracks schema.
     */
    private fun androidx.sqlite.db.SupportSQLiteDatabase.insertTrackV21(id: Long) {
        val cv = ContentValues().apply {
            put("id", id)
            put("title", "Test Track $id")
            put("artist", "Test Artist")
            put("album", "")
            put("duration_ms", 0L)
            put("file_format", "opus")
            put("quality_kbps", 0)
            put("file_size_bytes", 0L)
            put("source", "SPOTIFY")
            put("date_added", 0L)
            put("play_count", 0)
            put("is_downloaded", 0)
            put("canonical_title", "test track $id")
            put("canonical_artist", "test artist")
            put("match_confidence", 0f)
            put("match_dismissed", 0)
            put("match_flagged", 0)
            put("lastfm_user_loved", 0)
        }
        insert("tracks", SQLiteDatabase.CONFLICT_REPLACE, cv)
    }

    /**
     * Insert a download_queue row with all NOT NULL columns from the
     * v21 schema (track_id, status, search_query, retry_count,
     * failure_type, created_at). Other columns default to NULL.
     */
    private fun androidx.sqlite.db.SupportSQLiteDatabase.insertDownloadQueueRowV21(
        trackId: Long,
        status: String,
    ) {
        val cv = ContentValues().apply {
            put("track_id", trackId)
            put("status", status)
            put("search_query", "test query")
            put("retry_count", 0)
            put("failure_type", "NONE")
            put("created_at", 0L)
        }
        insert("download_queue", SQLiteDatabase.CONFLICT_REPLACE, cv)
    }
}
